#!/bin/sh
#
# Build unetd (in the Android configuration) plus wireguard-go for the host, so
# the architecture can be exercised on Linux before any Android work.
#
# "Android configuration" = no ubus, no VXLAN, no kernel WireGuard backend, so
# the only available backend is the portable userspace UAPI one (wg-user.c).
#
# Requires: cmake, ninja or make, a C compiler, go, libjson-c-dev.
set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-$ROOT/build/host}"
PREFIX="$OUT/prefix"

mkdir -p "$OUT" "$PREFIX"

echo "==> libubox"
cmake -S "$ROOT/third_party/libubox" -B "$OUT/libubox" \
	-DBUILD_LUA=OFF -DBUILD_EXAMPLES=OFF -DBUILD_TESTS=OFF \
	-DCMAKE_INSTALL_PREFIX="$PREFIX" >/dev/null
cmake --build "$OUT/libubox" --target install -j"$(nproc)" >/dev/null

echo "==> unetd (UBUS=off VXLAN=off WG_LINUX=off)"
cmake -S "$ROOT/third_party/unetd" -B "$OUT/unetd" \
	-DUBUS_SUPPORT=off -DVXLAN_SUPPORT=off -DWG_LINUX_SUPPORT=off -DWERROR=off \
	-DCMAKE_C_FLAGS="-I$PREFIX/include" \
	-DCMAKE_EXE_LINKER_FLAGS="-L$PREFIX/lib -Wl,-rpath,$PREFIX/lib" \
	-DCMAKE_SHARED_LINKER_FLAGS="-L$PREFIX/lib" >/dev/null
cmake --build "$OUT/unetd" -j"$(nproc)" >/dev/null

echo "==> wireguard-go"
if [ ! -d "$OUT/wireguard-go-src" ]; then
	git clone -q --depth 1 https://github.com/WireGuard/wireguard-go.git "$OUT/wireguard-go-src"
fi
(cd "$OUT/wireguard-go-src" && go build -o "$OUT/wireguard-go" .)

echo
echo "built:"
echo "  $OUT/unetd/unetd"
echo "  $OUT/unetd/unet-tool"
echo "  $OUT/wireguard-go"
