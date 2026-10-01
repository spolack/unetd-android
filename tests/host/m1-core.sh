#!/bin/sh
#
# M1a: the unetd library wrapper (native/core) drives wireguard-go the way the
# Android VpnService will: start, add a network, read status, receive the
# interface update, remove, stop -- twice in one process.
#
# Requires: CAP_NET_ADMIN (wireguard-go creates a tun), /dev/net/tun.
#
# Usage: m1-core.sh <core-test binary> <unetd-src> <wireguard-go-binary>
set -e

CORE_TEST="${1:?usage: $0 <core-test> <unetd-src> <wireguard-go>}"
SRC="${2:?}"
WGGO="${3:?}"

IFNAME=wgm1
RUNDIR="$(mktemp -d)"
DATADIR="$RUNDIR/data"
mkdir -p "$DATADIR"
cp "$SRC/examples/net0.json" "$DATADIR/"

cleanup() {
	[ -n "$WGGO_PID" ] && kill "$WGGO_PID" 2>/dev/null || true
	sleep 1
	rm -rf "$RUNDIR"
}
trap cleanup EXIT

WG_PROCESS_FOREGROUND=1 "$WGGO" -f "$IFNAME" > "$RUNDIR/wgo.log" 2>&1 &
WGGO_PID=$!

SOCKDIR=/var/run/wireguard
i=0
while [ ! -S "$SOCKDIR/$IFNAME.sock" ]; do
	i=$((i + 1))
	[ "$i" -gt 50 ] && { echo "FAIL: wireguard-go never created its UAPI socket" >&2; exit 1; }
	sleep 0.1
done

KEY="$(cat "$SRC/examples/net0-master.key")"
NET_NAME="$IFNAME" "$CORE_TEST" "$DATADIR" "$SOCKDIR" \
	"{\"name\":\"$IFNAME\",\"type\":\"file\",\"key\":\"$KEY\",\"file\":\"$DATADIR/net0.json\"}"
