#!/bin/sh
#
# M1: DHT discovery between two unetd nodes that both sit behind NAT.
#
# Topology, all in network namespaces on one machine:
#
#   gw  --(LAN 192.168.10/24)-- gwnat --(10.100.1/24)-- inet --(10.100.2/24)-- phnat --(LAN 192.168.20/24)-- ph
#                                 MASQUERADE                 10 DHT nodes              MASQUERADE
#
# "gw" holds the signed network data; "ph" has only its key, the network's
# public key, and no gateway address at all. Both run unetd + wireguard-go +
# unet-dht. ph must find gw through the DHT, fetch the signed data over the
# global PEX socket, and bring the WireGuard tunnel up through both NATs.
#
# Requires root, iproute2, iptables (nft backend is fine), the host build
# (scripts/build-host.sh) and build/host/dhtnode (see CI / the Makefile line
# in host-tests.yml).
#
# Usage: nat-testbed.sh <build/host dir> [<state dir>]
set -e

HOST="${1:?usage: $0 <build/host dir> [<state dir>]}"
STATE="${2:-/tmp/unetd-nat-testbed}"
TIMEOUT="${TIMEOUT:-300}"
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
UNETD="$HOST/unetd/unetd"
UNET_TOOL="$HOST/unetd/unet-tool"
UDHT="$HOST/unetd/unet-dht"
WGGO="$HOST/wireguard-go"
DHTNODE="$HOST/dhtnode"
for f in "$UNETD" "$UNET_TOOL" "$UDHT" "$WGGO" "$DHTNODE"; do
	[ -x "$f" ] || { echo "missing $f" >&2; exit 1; }
done

PORT=51830
NET=unt
GW_IP=10.9.0.1
PH_IP=10.9.0.2
NS="unt-inet unt-gwnat unt-gw unt-phnat unt-ph"
PIDS=""

cleanup() {
	for p in $PIDS; do kill "$p" 2>/dev/null || true; done
	sleep 1
	for n in $NS; do ip netns del "$n" 2>/dev/null || true; done
}
trap cleanup EXIT INT TERM

rm -rf "$STATE"
mkdir -p "$STATE/gw/data" "$STATE/ph/data" "$STATE/dht"
cd "$STATE"
for n in $NS; do ip netns del "$n" 2>/dev/null || true; done

# ---- namespaces and NAT -----------------------------------------------------------
for n in $NS; do
	ip netns add "$n"
	ip -n "$n" link set lo up
done
link() { # link <ns-a> <if-a> <ip-a> <ns-b> <if-b> <ip-b>
	ip link add "$2" netns "$1" type veth peer name "$5" netns "$4"
	ip -n "$1" addr add "$3" dev "$2"; ip -n "$1" link set "$2" up
	ip -n "$4" addr add "$6" dev "$5"; ip -n "$4" link set "$5" up
}
link unt-inet  i-gw  10.100.1.1/24  unt-gwnat n-up  10.100.1.2/24
link unt-inet  i-ph  10.100.2.1/24  unt-phnat n-up  10.100.2.2/24
link unt-gwnat n-lan 192.168.10.1/24 unt-gw   eth0  192.168.10.2/24
link unt-phnat n-lan 192.168.20.1/24 unt-ph   eth0  192.168.20.2/24
for n in unt-gwnat unt-phnat; do
	ip netns exec "$n" sysctl -qw net.ipv4.ip_forward=1
	ip -n "$n" route add default via "$(ip -n "$n" -4 addr show n-up | sed -n 's/.*inet 10\.100\.\([0-9]\)\.2.*/10.100.\1.1/p')"
	# Port-preserving, address-and-port-restricted NAT: what a home router does.
	ip netns exec "$n" iptables -t nat -A POSTROUTING -o n-up -j MASQUERADE
	# And like a router, drop unsolicited WAN input. Without this the first
	# packet from the other side is accepted by the router's own stack, which
	# confirms a conntrack entry for that tuple, and the LAN host's later
	# outbound mapping can no longer keep its source port -- the DHT then
	# announces a port that is not the mapped one, on both sides at once.
	ip netns exec "$n" iptables -A INPUT -i n-up -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT
	ip netns exec "$n" iptables -A INPUT -i n-up -j DROP
done
ip netns exec unt-inet sysctl -qw net.ipv4.ip_forward=1
ip -n unt-gw route add default via 192.168.10.1
ip -n unt-ph route add default via 192.168.20.1
# The LANs are private; nothing routes to them from "inet" (that is the point).

# ---- a private DHT: ten nodes on the "internet" --------------------------------------
BOOT=""
i=0
while [ $i -lt 10 ]; do
	p=$((6881 + i))
	ip netns exec unt-inet "$DHTNODE" 10.100.1.1 $p $BOOT > "dht/node-$p.log" 2>&1 &
	PIDS="$PIDS $!"
	BOOT="$BOOT 10.100.1.1:$p"
	i=$((i + 1))
done
# From the LANs, the DHT is reachable through the NATs on the inet addresses.
DHT_BOOTSTRAP="-b 10.100.1.1:6881 -b 10.100.1.1:6882 -b 10.100.1.1:6883 -b 10.100.1.1:6884"

# ---- keys and the signed network ------------------------------------------------
"$UNET_TOOL" -G -o net.key
"$UNET_TOOL" -G -o gw/host.key
"$UNET_TOOL" -G -o ph/host.key
AUTH_KEY="$("$UNET_TOOL" -P -K net.key)"
cat > "$NET.json" <<JSON
{
	"config": { "port": $PORT, "keepalive": 10 },
	"hosts": {
		"gw":    { "key": "$("$UNET_TOOL" -H -K gw/host.key)", "ipaddr": [ "$GW_IP" ] },
		"phone": { "key": "$("$UNET_TOOL" -H -K ph/host.key)", "ipaddr": [ "$PH_IP" ] }
	}
}
JSON
# No endpoints anywhere: the gateway's address has to come from the DHT.
"$UNET_TOOL" -S -K net.key -o "gw/data/untgw.bin" "$NET.json"

# ---- one unetd stack per side ---------------------------------------------------------
# The network name is local to each node (it is the interface name), so each
# side gets its own: wireguard-go serves every namespace from the same socket
# directory, and the signed data for a dynamic network is cached as <name>.bin.
stack() { # stack <side> <ns> <ifname> <my-ip> <peer-ip>
	side="$1"; ns="$2"; ifname="$3"
	LOG_LEVEL=debug WG_PROCESS_FOREGROUND=1 ip netns exec "$ns" "$WGGO" -f "$ifname" > "$side/wireguard-go.log" 2>&1 &
	PIDS="$PIDS $!"
	j=0; while [ ! -S "/var/run/wireguard/$ifname.sock" ]; do j=$((j+1)); [ $j -gt 50 ] && { echo "no UAPI socket for $side" >&2; exit 1; }; sleep 0.1; done
	ip -n "$ns" addr add "$4/32" dev "$ifname"
	ip -n "$ns" link set "$ifname" up
	ip -n "$ns" route add "$5/32" dev "$ifname"
	# A short path: sun_path is limited to 108 bytes.
	CTL="/tmp/unt-$side.sock"; rm -f "$CTL"
	ip netns exec "$ns" "$UNETD" -d -S /var/run/wireguard -D "$STATE/$side/data" -u "$CTL" \
		-N "{\"name\":\"$ifname\",\"type\":\"dynamic\",\"auth_key\":\"$AUTH_KEY\",\"key\":\"$(cat $side/host.key)\",\"keepalive\":10}" \
		> "$side/unetd.log" 2>&1 &
	PIDS="$PIDS $!"
	sleep 1
	ip netns exec "$ns" "$UDHT" -d $DHT_BOOTSTRAP -u "$CTL" -N "$AUTH_KEY" "$side-node" > "$side/udht.log" 2>&1 &
	PIDS="$PIDS $!"
}
stack gw unt-gw untgw "$GW_IP" "$PH_IP"
stack ph unt-ph untph "$PH_IP" "$GW_IP"

# Something to reach through the tunnel: a UDP echo on the gateway's tunnel
# address (ping is not installed everywhere; python3 is).
ip netns exec unt-gw python3 -c '
import socket
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); s.bind(("'"$GW_IP"'", 51777))
while True:
    d, a = s.recvfrom(1500); s.sendto(d, a)
' > gw/echo.log 2>&1 &
PIDS="$PIDS $!"
tunnel_probe() {
	ip netns exec unt-ph python3 -c '
import socket, sys
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); s.settimeout(1.5)
s.sendto(b"unetd", ("'"$GW_IP"'", 51777))
try:
    d, a = s.recvfrom(1500); sys.exit(0 if d == b"unetd" else 1)
except socket.timeout:
    sys.exit(1)
' 2>/dev/null
}

# ---- wait -------------------------------------------------------------------------
START=$(date +%s)
echo "waiting up to ${TIMEOUT}s for the phone to find the gateway through the DHT (epoch $START)..."
t=0; found=""; data=""; tunnel=""; epn=""
while [ $t -lt "$TIMEOUT" ]; do
	[ -z "$found" ] && grep -q "^Node: 10.100.1.2:" ph/udht.log 2>/dev/null && { found=$t; echo "  ${t}s: phone's DHT search returned the gateway's external address"; }
	[ -z "$data" ] && grep -q "received updated network data" ph/unetd.log 2>/dev/null && { data=$t; echo "  ${t}s: phone received the signed network data over PEX"; }
	[ -z "$epn" ] && grep -q "receive endpoint notification" ph/unetd.log 2>/dev/null && { epn=$t; echo "  ${t}s: phone learned the gateway's WireGuard endpoint"; }
	if [ -n "$data" ] && tunnel_probe; then
		tunnel=$t; echo "  ${t}s: UDP echo through the WireGuard tunnel, both ends behind NAT"; break
	fi
	sleep 2; t=$((t + 2))
done

echo
echo "--- DHT nodes (last report each):"; for f in dht/node-*.log; do tail -1 "$f"; done
echo "--- gw/udht.log:"; grep -v "^$" gw/udht.log | tail -8
echo "--- ph/udht.log:"; grep -v "^$" ph/udht.log | tail -12
echo "--- ph/unetd.log:"; grep -i "request network\|received updated\|PEX global rx\|pex: \|connected\|endpoint\|update:" ph/unetd.log | tail -15
echo "--- gw/unetd.log:"; grep -i "receive update\|PEX global rx\|pex: \|endpoint" gw/unetd.log | tail -8
echo "--- NAT conntrack (gwnat):"; ip netns exec unt-gwnat conntrack -L 2>/dev/null | grep -i udp | head -6 || true
for side in gw ph; do
	ifn=unt$side
	echo "--- $side WireGuard (UAPI):"
	python3 "$REPO/tests/host/uapi.py" "/var/run/wireguard/$ifn.sock" 2>/dev/null | grep -E "^(listen_port|public_key|endpoint|last_handshake_time_sec|rx_bytes|tx_bytes|persistent_keepalive_interval)=" | head -8
	echo "--- $side wireguard-go (handshakes):"; grep -i "handshake\|sending\|receiv" "$side/wireguard-go.log" | head -6
done
echo "--- tun state:"
ip -n unt-ph -s link show untph | sed -n 3,6p
ip -n unt-gw -s link show untgw | sed -n 3,6p
echo "epoch now $(date +%s), handshake seen at: gw $(python3 "$REPO/tests/host/uapi.py" /var/run/wireguard/untgw.sock 2>/dev/null | sed -n 's/^last_handshake_time_sec=//p') ph $(python3 "$REPO/tests/host/uapi.py" /var/run/wireguard/untph.sock 2>/dev/null | sed -n 's/^last_handshake_time_sec=//p')"
echo
[ -n "$found" ] || { echo "FAIL: the phone never learned the gateway's address from the DHT"; exit 1; }
[ -n "$data" ] || { echo "FAIL: the phone found the gateway but never received the network data"; exit 1; }
[ -n "$tunnel" ] || { echo "FAIL: network data received but no traffic through the tunnel (NAT traversal)"; exit 1; }
echo "PASS: DHT rendezvous + PEX + WireGuard through two NATs in ${tunnel}s"
