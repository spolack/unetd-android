#!/bin/sh
#
# M0: prove that unetd, built in the Android configuration, drives wireguard-go
# entirely over the cross-platform WireGuard UAPI socket.
#
# "Android configuration" means: no ubus, no VXLAN, and no kernel WireGuard
# backend -- so the only way unetd can program a device is wg-user.c talking to
# a unix socket, which is exactly what wireguard-go serves on Android.
#
# Requires: CAP_NET_ADMIN (to create the tun), /dev/net/tun.
# Does NOT require: IPv6, root on the host, an Android device.
#
# Usage: m0-uapi-hinge.sh <build-dir> <unetd-src> <wireguard-go-binary>
set -e

BUILD="${1:?usage: $0 <build-dir> <unetd-src> <wireguard-go>}"
SRC="${2:?}"
WGGO="${3:?}"

IFNAME=wgm0
RUNDIR="$(mktemp -d)"
SOCKDIR="$RUNDIR/wireguard"
DATADIR="$RUNDIR/data"
mkdir -p "$SOCKDIR" "$DATADIR"
cp "$SRC/examples/net0.json" "$DATADIR/"

cleanup() {
	[ -n "$UNETD_PID" ] && kill "$UNETD_PID" 2>/dev/null || true
	[ -n "$WGGO_PID" ] && kill "$WGGO_PID" 2>/dev/null || true
	sleep 1
	rm -rf "$RUNDIR"
}
trap cleanup EXIT

fail() { echo "FAIL: $*" >&2; exit 1; }

# wireguard-go takes the socket directory from a linker flag, the same knob
# wireguard-android uses to point it at the app's cache dir.
WG_PROCESS_FOREGROUND=1 "$WGGO" -f "$IFNAME" > "$RUNDIR/wgo.log" 2>&1 &
WGGO_PID=$!

i=0
while [ ! -S "$SOCKDIR/$IFNAME.sock" ] && [ ! -S "/var/run/wireguard/$IFNAME.sock" ]; do
	i=$((i + 1))
	[ "$i" -gt 50 ] && fail "wireguard-go never created its UAPI socket"
	sleep 0.1
done
[ -S "/var/run/wireguard/$IFNAME.sock" ] && SOCKDIR=/var/run/wireguard

echo "ok: wireguard-go serving UAPI at $SOCKDIR/$IFNAME.sock"

KEY="$(cat "$SRC/examples/net0-master.key")"
"$BUILD/unetd" -d -S "$SOCKDIR" -D "$DATADIR" \
	-N "{\"name\":\"$IFNAME\",\"type\":\"file\",\"key\":\"$KEY\",\"file\":\"$DATADIR/net0.json\"}" \
	> "$RUNDIR/unetd.log" 2>&1 &
UNETD_PID=$!
sleep 3

kill -0 "$UNETD_PID" 2>/dev/null || fail "unetd exited early; log:
$(cat "$RUNDIR/unetd.log")"

DUMP="$(python3 "$(dirname "$0")/uapi.py" "$SOCKDIR/$IFNAME.sock")"
echo "$DUMP" > "$RUNDIR/uapi.txt"

# The private key must have been pushed by unetd.
echo "$DUMP" | grep -q '^private_key=' || fail "unetd did not set private_key"
echo "ok: private_key pushed over UAPI"

# Both remote hosts from net0.json must exist as peers, keyed by public key.
for pk in \
	fa188ffb5159722f47a78e205843e281bb0c1cc7ba0deee79cc6d50c91610e35 \
	9b1410c69c229e50f1cb46e9e7ae1bdb98a5d680e269ffdaf2391a29005c8f0e
do
	echo "$DUMP" | grep -q "^public_key=$pk$" || fail "peer $pk missing"
done
echo "ok: both peers created"

# AllowedIPs must carry the configured subnets and the derived ULA /128s.
for ip in 192.168.4.0/24 192.168.5.0/24 192.168.4.1/32 192.168.5.1/32; do
	echo "$DUMP" | grep -q "^allowed_ip=$ip$" || fail "allowed_ip $ip missing"
done
echo "ok: configured subnets and addresses present in AllowedIPs"

ULA_COUNT="$(echo "$DUMP" | grep -c '^allowed_ip=fd.*/128$' || true)"
[ "$ULA_COUNT" -eq 2 ] || fail "expected 2 derived ULA /128 AllowedIPs, got $ULA_COUNT"
echo "ok: both derived ULA /128 addresses present"

# Every derived address must sit inside one /64, which is what lets the Android
# client install a single stable route at VpnService.Builder.establish() time.
PREFIXES="$(echo "$DUMP" | sed -n 's/^allowed_ip=\(fd[0-9a-f:]*\)\/128$/\1/p' \
	| awk -F: '{ print $1":"$2":"$3":"$4 }' | sort -u | wc -l)"
[ "$PREFIXES" -eq 1 ] || fail "derived ULAs span $PREFIXES /64 prefixes, expected 1"
echo "ok: all derived addresses share one /64 -- a single Android route suffices"

echo "$DUMP" | grep -q '^errno=0$' || fail "UAPI reported an error"
echo
echo "PASS: unetd drove wireguard-go over UAPI with no ubus, no VXLAN and no kernel backend"
