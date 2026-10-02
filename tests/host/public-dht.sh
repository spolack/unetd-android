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

# First an independent probe: a minimal BEP 5 ping from Python to the
# well-known bootstrap routers, so "the routers are down" and "they do not
# answer unet-dht" can be told apart.
python3 - <<'PY'
import os, socket
routers = [("router.bittorrent.com", 6881), ("router.utorrent.com", 6881), ("dht.transmissionbt.com", 6881),
           ("dht.libtorrent.org", 25401), ("dht.aelitis.com", 6881)]
for h, port in routers:
    try:
        ip = socket.gethostbyname(h)
    except OSError as e:
        print(f"  {h}: DNS failed: {e}"); continue
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); s.settimeout(4)
    ping = b"d1:ad2:id20:" + os.urandom(20) + b"e1:q4:ping1:t2:aa1:y1:qe"
    try:
        s.sendto(ping, (ip, port)); d, a = s.recvfrom(1500)
        print(f"  {h}:{port} ({ip}): reply {len(d)} bytes")
    except socket.timeout:
        print(f"  {h}:{port} ({ip}): NO REPLY in 4 s")
    except OSError as e:
        print(f"  {h} ({ip}): {e}")
    s.close()

# The STUN servers the README recommends for stun-servers: one binding request each.
stun = [("stun.l.google.com", 19302), ("stun.cloudflare.com", 3478)]
import struct
for h, port in stun:
    try:
        ip = socket.gethostbyname(h)
    except OSError as e:
        print(f"  stun {h}: DNS failed: {e}"); continue
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); s.settimeout(4)
    tid = os.urandom(12)
    try:
        s.sendto(struct.pack("!HHI", 1, 0, 0x2112A442) + tid, (ip, port)); d, a = s.recvfrom(1500)
        ok = len(d) >= 20 and d[0:2] == b"\x01\x01" and d[8:20] == tid
        print(f"  stun {h}:{port} ({ip}): {'binding response' if ok else 'unexpected reply'} {len(d)} bytes")
    except socket.timeout:
        print(f"  stun {h}:{port} ({ip}): NO REPLY in 4 s")
    s.close()
PY

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
