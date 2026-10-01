#!/bin/sh
#
# Cross-compile libwg-go (wireguard-go + JNI glue in native/libwg-go) for one
# Android ABI. Invoked by native/CMakeLists.txt once per ABI with the toolchain
# CMake already resolved, so Go and C are built with the same NDK clang.
#
# Usage: build-libwg-go.sh --abi <abi> --cc <clang> --target <triple>
#                          --sysroot <dir> --out <libwg-go.so> [--cflags "..."]
#                          [--ldflags "..."] [--go <go binary>]
set -e

GO="${GO:-go}"
CFLAGS=""
LDFLAGS=""
while [ $# -gt 0 ]; do
	case "$1" in
	--abi) ABI="$2"; shift 2 ;;
	--cc) CC="$2"; shift 2 ;;
	--target) TARGET="$2"; shift 2 ;;
	--sysroot) SYSROOT="$2"; shift 2 ;;
	--out) OUT="$2"; shift 2 ;;
	--cflags) CFLAGS="$2"; shift 2 ;;
	--ldflags) LDFLAGS="$2"; shift 2 ;;
	--go) GO="$2"; shift 2 ;;
	*) echo "unknown argument: $1" >&2; exit 2 ;;
	esac
done
: "${ABI:?--abi required}" "${CC:?--cc required}" "${TARGET:?--target required}"
: "${SYSROOT:?--sysroot required}" "${OUT:?--out required}"

case "$ABI" in
	arm64-v8a)   GOARCH=arm64 ;;
	armeabi-v7a) GOARCH=arm; export GOARM=7 ;;
	x86_64)      GOARCH=amd64 ;;
	x86)         GOARCH=386 ;;
	*) echo "unsupported ABI: $ABI" >&2; exit 2 ;;
esac

SRC="$(cd "$(dirname "$0")/../native/libwg-go" && pwd)"
case "$OUT" in /*) ;; *) OUT="$PWD/$OUT" ;; esac
mkdir -p "$(dirname "$OUT")"

# Go's own CLOCK_MONOTONIC timers stop during suspend; -mthumb confuses cgo on
# armv7 (as in wireguard-android's Makefile), so it is swapped for -marm.
CFLAGS="$(printf '%s' "$CFLAGS" | sed 's/-mthumb/-marm/g')"

export GOOS=android GOARCH CGO_ENABLED=1 CC
export CGO_CFLAGS="--target=$TARGET --sysroot=$SYSROOT $CFLAGS"
# 16 KiB page alignment: required by Android 15+ devices with 16 KiB pages.
export CGO_LDFLAGS="--target=$TARGET --sysroot=$SYSROOT $LDFLAGS -Wl,-soname=libwg-go.so -Wl,-z,max-page-size=16384"

cd "$SRC"
"$GO" build -trimpath -buildvcs=false -ldflags="-buildid=" -buildmode=c-shared -o "$OUT" .
rm -f "${OUT%.so}.h"
echo "built $OUT ($GOOS/$GOARCH)"
