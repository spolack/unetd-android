#!/bin/sh
#
# A unetd "router" for the emulator test: the thing the app on the emulator
# fetches its network data from, handshakes with, and sends HTTP through.
#
# Creates a two-host network (router = this machine, phone = the emulator),
# signs it, runs wireguard-go + unetd for the router side, puts an IPv4 address
# on the router's tun and serves HTTP on it. Prints the values the test needs.
#
# Requires root (tun), the host build from scripts/build-host.sh, python3.
#
# Usage: router.sh <build/host dir> [<state dir>]
# Outputs (stdout, key=value, and $GITHUB_OUTPUT when set):
#   authKey, phoneKey, routerAddress, phoneAddress
set -e

HOST="${1:?usage: $0 <build/host dir> [<state dir>]}"
STATE="${2:-/tmp/unetd-router}"
NET=wgci
PORT=51830
ROUTER_IP=10.9.0.1
PHONE_IP=10.9.0.2
# The emulator's address for the machine it runs on.
ROUTER_ENDPOINT="${ROUTER_ENDPOINT:-10.0.2.2}"

UNETD="$HOST/unetd/unetd"
UNET_TOOL="$HOST/unetd/unet-tool"
WGGO="$HOST/wireguard-go"
for f in "$UNETD" "$UNET_TOOL" "$WGGO"; do
	[ -x "$f" ] || { echo "missing $f (run scripts/build-host.sh)" >&2; exit 1; }
done

mkdir -p "$STATE/data"
cd "$STATE"

# ---- keys ----------------------------------------------------------------
"$UNET_TOOL" -G -o net.key
"$UNET_TOOL" -G -o router.key
"$UNET_TOOL" -G -o phone.key
AUTH_KEY="$("$UNET_TOOL" -P -K net.key)"
ROUTER_PUB="$("$UNET_TOOL" -H -K router.key)"
PHONE_PUB="$("$UNET_TOOL" -H -K phone.key)"

# ---- the network ----------------------------------------------------------
# Layer 3 only: no services, no tunnels. No peer-exchange-port and no STUN,
# matching what the app ships as a conservative v1.
cat > "$NET.json" <<JSON
{
	"config": {
		"port": $PORT,
		"keepalive": 10
	},
	"hosts": {
		"router": {
			"key": "$ROUTER_PUB",
			"endpoint": "$ROUTER_ENDPOINT",
			"ipaddr": [ "$ROUTER_IP" ]
		},
		"phone": {
			"key": "$PHONE_PUB",
			"ipaddr": [ "$PHONE_IP" ]
		}
	}
}
JSON
# A dynamic network reads its signed data from <data_dir>/<name>.bin.
"$UNET_TOOL" -S -K net.key -o "data/$NET.bin" "$NET.json"

# ---- wireguard-go + unetd for the router -------------------------------------
WG_PROCESS_FOREGROUND=1 "$WGGO" -f "$NET" > wireguard-go.log 2>&1 &
echo $! > wireguard-go.pid
SOCKDIR=/var/run/wireguard
i=0
while [ ! -S "$SOCKDIR/$NET.sock" ]; do
	i=$((i + 1))
	[ "$i" -gt 50 ] && { echo "wireguard-go never created $SOCKDIR/$NET.sock" >&2; cat wireguard-go.log >&2; exit 1; }
	sleep 0.1
done

"$UNETD" -d -S "$SOCKDIR" -D "$STATE/data" \
	-N "{\"name\":\"$NET\",\"type\":\"dynamic\",\"auth_key\":\"$AUTH_KEY\",\"key\":\"$(cat router.key)\",\"keepalive\":10}" \
	> unetd.log 2>&1 &
echo $! > unetd.pid
sleep 2
kill -0 "$(cat unetd.pid)" 2>/dev/null || { echo "unetd exited:" >&2; cat unetd.log >&2; exit 1; }

# unetd never touches addresses or routes (that is update-cmd's job on a
# router); do here what the netifd script would.
ip addr add "$ROUTER_IP/32" dev "$NET"
ip link set "$NET" up
ip route add "$PHONE_IP/32" dev "$NET"

# Diagnostics: a UDP echo on the host (the test checks that UDP from the
# emulator reaches the machine at all) and a capture of the PEX/WireGuard ports.
python3 - > udp-echo.log 2>&1 <<'PYEOF' &
import socket
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
s.bind(("0.0.0.0", 51999))
while True:
    data, addr = s.recvfrom(2048)
    print("echo", addr, data[:64], flush=True)
    s.sendto(data, addr)
PYEOF
echo $! > udp-echo.pid
if command -v tcpdump >/dev/null 2>&1; then
	tcpdump -i any -n -U "udp port 51819 or udp port $PORT or udp port 51999" -w capture.pcap > tcpdump.log 2>&1 &
	echo $! > tcpdump.pid
fi

# What the test fetches through the tunnel.
mkdir -p www && echo "hello from the router over unetd" > www/index.html
python3 -m http.server 8080 --bind "$ROUTER_IP" --directory www > http.log 2>&1 &
echo $! > http.pid

echo "router up: unetd pid $(cat unetd.pid), wireguard-go pid $(cat wireguard-go.pid), http on $ROUTER_IP:8080"
grep -q "listen_port\|Updating private key" wireguard-go.log 2>/dev/null && echo "router WireGuard configured by unetd" || true

out() {
	echo "$1=$2"
	if [ -n "$GITHUB_OUTPUT" ]; then
		echo "$1=$2" >> "$GITHUB_OUTPUT"
	fi
}
out authKey "$AUTH_KEY"
out phoneKey "$(cat phone.key)"
out routerAddress "$ROUTER_IP"
out phoneAddress "$PHONE_IP"
