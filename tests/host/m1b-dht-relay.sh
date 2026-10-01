#!/bin/sh
#
# M1b: the unetd library wrapper (native/core) relays unet-dht traffic.
#
# unet-dht owns no UDP socket. It connects to unetd's control socket, passes
# one end of a socketpair over it, and from then on hands every DHT packet to
# unetd, which sends it from the global PEX socket and feeds replies back
# through the socketpair. The Android app runs exactly this: unet-dht in its
# :dht process, unetd as the library wrapper in the VPN process. The NAT
# testbed (M1) checks the relay with the unetd *binary*; this checks it with
# the wrapper, which core-test drives with its control socket enabled.
#
# A single DHT node (tests/dht/dhtnode.c) listens on a loopback alias -- not
# 127.0.0.1, which the DHT rejects as a martian source -- and unet-dht,
# bootstrapped from it, must get a pong.
#
# Requires: CAP_NET_ADMIN (wireguard-go creates a tun, an address goes on lo).
#
# Usage: m1b-dht-relay.sh <core-test binary> <unetd-src> <build/host dir>
set -e

CORE_TEST="${1:?usage: $0 <core-test> <unetd-src> <build/host dir>}"
SRC="${2:?}"
HOST="${3:?}"

IFNAME=wgm1b
DHT_ADDR="${DHT_ADDR:-10.200.1.1}"
DHT_PORT=6881
RUNDIR="$(mktemp -d)"
DATADIR="$RUNDIR/data"
mkdir -p "$DATADIR"
cp "$SRC/examples/net0.json" "$DATADIR/"
: > "$RUNDIR/pids"

cleanup() {
	kill $(cat "$RUNDIR/pids") 2>/dev/null || true
	sleep 1
	ip addr del "$DHT_ADDR/32" dev lo 2>/dev/null || true
	rm -rf "$RUNDIR"
}
trap cleanup EXIT

fail() {
	echo "FAIL: $*" >&2
	echo "--- unet-dht"; cat "$RUNDIR/udht.log"
	echo "--- dhtnode"; cat "$RUNDIR/dhtnode.log"
	echo "--- core-test"; cat "$RUNDIR/core.log"
	exit 1
}

ip addr add "$DHT_ADDR/32" dev lo 2>/dev/null || true
"$HOST/dhtnode" "$DHT_ADDR" "$DHT_PORT" > "$RUNDIR/dhtnode.log" 2>&1 &
echo $! >> "$RUNDIR/pids"

WG_PROCESS_FOREGROUND=1 "$HOST/wireguard-go" -f "$IFNAME" > "$RUNDIR/wgo.log" 2>&1 &
echo $! >> "$RUNDIR/pids"

SOCKDIR=/var/run/wireguard
i=0
while [ ! -S "$SOCKDIR/$IFNAME.sock" ]; do
	i=$((i + 1))
	[ "$i" -gt 50 ] && fail "wireguard-go never created its UAPI socket"
	sleep 0.1
done

# core-test runs its usual two cycles; CORE_TEST_HOLD keeps round 1 up long
# enough for unet-dht to connect, ping and be answered.
KEY="$(cat "$SRC/examples/net0-master.key")"
CORE_TEST_UNIX_SOCKET="$RUNDIR/unetd.sock" CORE_TEST_HOLD=25 NET_NAME="$IFNAME" \
	"$CORE_TEST" "$DATADIR" "$SOCKDIR" \
	"{\"name\":\"$IFNAME\",\"type\":\"file\",\"key\":\"$KEY\",\"file\":\"$DATADIR/net0.json\"}" \
	> "$RUNDIR/core.log" 2>&1 &
CORE_PID=$!
echo $CORE_PID >> "$RUNDIR/pids"

i=0
while [ ! -S "$RUNDIR/unetd.sock" ]; do
	i=$((i + 1))
	[ "$i" -gt 100 ] && fail "the wrapper never opened its control socket"
	sleep 0.1
done

AUTH_KEY="$("$HOST/unetd/unet-tool" -P -K "$SRC/examples/net0-master.key")"
"$HOST/unetd/unet-dht" -d -b "$DHT_ADDR:$DHT_PORT" -u "$RUNDIR/unetd.sock" -N "$AUTH_KEY" m1b-node \
	> "$RUNDIR/udht.log" 2>&1 &
echo $! >> "$RUNDIR/pids"

i=0
until grep -q "^Pong!" "$RUNDIR/udht.log"; do
	i=$((i + 1))
	[ "$i" -gt 150 ] && fail "no pong within 15 s: the wrapper did not relay the ping or the reply"
	sleep 0.1
done
echo "unet-dht got its pong through the wrapper after $((i / 10)).$((i % 10)) s"

grep -q "^DHT connected" "$RUNDIR/udht.log" || fail "unet-dht never reported the connection"

# core-test must still finish both of its rounds cleanly with the socket open.
wait "$CORE_PID" || fail "core-test failed with the control socket enabled"
grep -q "^round 2 ok" "$RUNDIR/core.log" || fail "core-test did not complete round 2"

echo "PASS: M1b - unet-dht ping relayed through the library wrapper and answered"
