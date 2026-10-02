package org.unetd.android.vpn

import android.util.Log
import org.unetd.android.nativebridge.Unetd

/**
 * One line into the native log ring, so it shows up in the Log screen.
 *
 * It goes through JNI on purpose. On Android, System.err is wired to logcat,
 * and FileDescriptor.err is a dup of fd 2 made at process start, so a Java
 * write to either never reaches the pipe the native capture put on fd 2. The
 * first attempt did exactly that and the lines vanished on a phone.
 */
object AppLog {
    private const val TAG = "unetd-android"

    fun line(text: String) {
        Log.i(TAG, text)
        runCatching { Unetd.log(text) }
    }
}
