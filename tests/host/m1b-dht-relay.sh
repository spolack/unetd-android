#!/bin/sh
#
# M1b: the unetd library wrapper (native/core) runs unet-dht on its own loop
# and relays its traffic.
#
# unet-dht owns no UDP socket. It connects to unetd's control socket, passes
# one end of a socketpair over it, and from then on hands every DHT packet to
# unetd, which sends it from the global PEX socket and feeds replies back
# through the socketpair. The Android app runs exactly this, in one process:
# the DHT node on unetd's uloop (patch 0013), the relay sockets non-blocking
# (patch 0014). The NAT testbed (M1) checks the relay with the unetd and
# unet-dht *binaries*; this checks it with the wrapper, which core-test
# drives: in both of its rounds it starts the node, waits for the pong, reads
# the node's progress from the status snapshot and stops it again.
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

KEY="$(cat "$SRC/examples/net0-master.key")"
AUTH_KEY="$("$HOST/unetd/unet-tool" -P -K "$SRC/examples/net0-master.key")"
if CORE_TEST_UNIX_SOCKET="$RUNDIR/unetd.sock" NET_NAME="$IFNAME" \
	CORE_TEST_DHT_BOOTSTRAP="$DHT_ADDR:$DHT_PORT" CORE_TEST_DHT_KEY="$AUTH_KEY" \
	"$CORE_TEST" "$DATADIR" "$SOCKDIR" \
	"{\"name\":\"$IFNAME\",\"type\":\"file\",\"key\":\"$KEY\",\"file\":\"$DATADIR/net0.json\"}" \
	> "$RUNDIR/core.log" 2>&1; then
	:
else
	fail "core-test failed with the DHT node on its loop"
fi

grep -q "^round 1: the DHT node got its pong" "$RUNDIR/core.log" || fail "no pong in round 1"
grep -q "^round 2: the DHT node got its pong" "$RUNDIR/core.log" || fail "no pong in round 2: the node did not restart cleanly"
grep -q "^round 2 ok" "$RUNDIR/core.log" || fail "core-test did not complete round 2"

echo "PASS: M1b - unet-dht on the wrapper's loop, ping relayed and answered, twice"
