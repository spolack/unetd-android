package org.unetd.android.vpn

import android.util.Log
import org.unetd.android.nativebridge.Unetd

/** One line into logcat and into the native log ring, so it shows up in the Log screen. */
object AppLog {
    private const val TAG = "unetd-android"

    fun line(text: String) {
        Log.i(TAG, text)
        runCatching { Unetd.log(text) }
    }
}
