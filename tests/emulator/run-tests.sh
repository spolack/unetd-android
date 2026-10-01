#!/bin/sh
#
# Install the app and its instrumented tests on the connected emulator, grant
# the VPN consent app-op, run the tests and leave instrument.txt + logcat.txt
# behind. Exit status follows the tests.
#
# Inputs (environment): AUTH_KEY, PHONE_KEY, ROUTER_ADDRESS (all optional;
# without them the data-path tests are skipped by the test itself).
set -e

PKG=org.unetd.android
APK=app/build/outputs/apk/debug/app-debug.apk
TEST_APK=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

adb install -r "$APK"
adb install -r "$TEST_APK"
# The consent dialog cannot be clicked on a headless emulator; this is the
# app-op the dialog would set (see isVpnPreConsented in the framework's Vpn.java).
adb shell appops set "$PKG" ACTIVATE_VPN allow
adb logcat -c

set -- -w -r
[ -n "$AUTH_KEY" ] && set -- "$@" -e authKey "$AUTH_KEY"
[ -n "$PHONE_KEY" ] && set -- "$@" -e phoneKey "$PHONE_KEY"
[ -n "$ROUTER_ADDRESS" ] && set -- "$@" -e routerAddress "$ROUTER_ADDRESS"

adb shell am instrument "$@" "$PKG.test/androidx.test.runner.AndroidJUnitRunner" | tee instrument.txt
adb logcat -d > logcat.txt

if grep -q "INSTRUMENTATION_STATUS_CODE: -1\|INSTRUMENTATION_STATUS_CODE: -2\|INSTRUMENTATION_FAILED\|Process crashed\|FAILURES!!!" instrument.txt; then
	echo "instrumented tests FAILED" >&2
	exit 1
fi
grep -q "INSTRUMENTATION_RESULT: stream=" instrument.txt || { echo "no test result reported" >&2; exit 1; }
echo "instrumented tests passed"
