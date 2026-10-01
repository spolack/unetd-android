#!/bin/sh
#
# Install the app and its instrumented tests on the connected emulator, grant
# the VPN consent app-op, run the tests and leave instrument.txt + logcat.txt
# behind. Exit status follows the tests.
#
# Inputs (environment): AUTH_KEY, PHONE_KEY, ROUTER_ADDRESS (all optional;
# without them the data-path tests are skipped by the test itself);
# USE_DHT=1 with DHT_BOOTSTRAP=host:port,... runs the variant in which the app
# has no gateway configured and must find the router through the DHT.
set -e
MODE="${USE_DHT:+dht}"; MODE="${MODE:-gateway}"

PKG=org.unetd.android
APK=app/build/outputs/apk/debug/app-debug.apk
TEST_APK=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

adb install -r "$APK"
adb install -r "$TEST_APK"
# A clean slate: no saved config, no cached network data from a previous pass.
adb shell pm clear "$PKG" >/dev/null
# The consent dialog cannot be clicked on a headless emulator; this is the
# app-op the dialog would set (see isVpnPreConsented in the framework's Vpn.java).
adb shell appops set "$PKG" ACTIVATE_VPN allow
# Android 17: packets to local-network addresses (the host is 10.0.2.2) need
# this runtime permission, which the test cannot click through either.
adb shell pm grant "$PKG" android.permission.ACCESS_LOCAL_NETWORK || true
adb logcat -c

set -- -w -r
[ -n "$AUTH_KEY" ] && set -- "$@" -e authKey "$AUTH_KEY"
[ -n "$PHONE_KEY" ] && set -- "$@" -e phoneKey "$PHONE_KEY"
[ -n "$ROUTER_ADDRESS" ] && set -- "$@" -e routerAddress "$ROUTER_ADDRESS"
if [ -n "$USE_DHT" ]; then
	set -- "$@" -e useDht true
	[ -n "$DHT_BOOTSTRAP" ] && set -- "$@" -e dhtBootstrap "$DHT_BOOTSTRAP"
fi

echo "=== instrumented tests, $MODE mode"
adb shell am instrument "$@" "$PKG.test/androidx.test.runner.AndroidJUnitRunner" | tee "instrument-$MODE.txt"
adb logcat -d > "logcat-$MODE.txt"

if grep -q "INSTRUMENTATION_STATUS_CODE: -1\|INSTRUMENTATION_STATUS_CODE: -2\|INSTRUMENTATION_FAILED\|Process crashed\|FAILURES!!!" "instrument-$MODE.txt"; then
	echo "instrumented tests FAILED ($MODE mode)" >&2
	exit 1
fi
grep -q "INSTRUMENTATION_RESULT: stream=" "instrument-$MODE.txt" || { echo "no test result reported" >&2; exit 1; }
echo "instrumented tests passed ($MODE mode)"
