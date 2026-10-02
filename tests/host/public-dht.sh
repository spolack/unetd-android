#!/bin/sh
#
# Informational: do the public DHT bootstrap routers answer unet-dht from this
# machine? Runs unetd (no networks) with its control socket and unet-dht with
# the default bootstrap list, and reports whether a pong arrived. Depends on
# the internet and on third parties, so CI runs it with continue-on-error.
#
# Usage: public-dht.sh <build/host dir> [seconds]
set -e
HOST="${1:?usage: $0 <build/host dir> [seconds]}"
WAIT="${2:-45}"
RUNDIR="$(mktemp -d)"
cleanup() { kill $(cat "$RUNDIR/pids" 2>/dev/null) 2>/dev/null || true; sleep 1; rm -rf "$RUNDIR"; }
trap cleanup EXIT
: > "$RUNDIR/pids"
mkdir -p "$RUNDIR/data"

"$HOST/unetd/unetd" -d -D "$RUNDIR/data" -u "$RUNDIR/unetd.sock" > "$RUNDIR/unetd.log" 2>&1 &
echo $! >> "$RUNDIR/pids"
i=0; while [ ! -S "$RUNDIR/unetd.sock" ]; do i=$((i + 1)); [ "$i" -gt 50 ] && { cat "$RUNDIR/unetd.log"; exit 1; }; sleep 0.1; done

"$HOST/unetd/unet-dht" -d -u "$RUNDIR/unetd.sock" public-dht-probe > "$RUNDIR/udht.log" 2>&1 &
echo $! >> "$RUNDIR/pids"

i=0
until grep -q "^Pong!" "$RUNDIR/udht.log"; do
	i=$((i + 1))
	if [ "$i" -gt $((WAIT * 10)) ]; then
		echo "no pong from the public bootstrap routers within $WAIT s"
		echo "--- unet-dht"; cat "$RUNDIR/udht.log"
		echo "--- unetd"; grep -i "pex\|dht" "$RUNDIR/unetd.log" | head -20
		exit 1
	fi
	sleep 0.1
done
echo "public DHT answered after $((i / 10)).$((i % 10)) s"
sleep 20
echo "--- unet-dht after 20 s more"; tail -15 "$RUNDIR/udht.log"
grep -q "^DHT is ready" "$RUNDIR/udht.log" && echo "DHT is ready" || echo "DHT not ready yet (bootstrap still in progress)"
