package org.unetd.android.vpn

import android.util.Log
import java.io.FileDescriptor
import java.io.FileOutputStream

/**
 * One line into the native log ring, so it shows up in the Log screen.
 *
 * On Android, System.out and System.err go to logcat, not to the process's
 * file descriptors; the native capture (Unetd.startLogCapture) replaces fd 2
 * with its pipe, so writing to fd 2 directly is what lands in the ring (and is
 * echoed to logcat from there). Use this for anything the user should be able
 * to read on the phone without adb.
 */
object AppLog {
    private const val TAG = "unetd-android"

    fun line(text: String) {
        Log.i(TAG, text)
        runCatching {
            FileOutputStream(FileDescriptor.err).use { it.write((text + "\n").toByteArray()) }
        }
    }
}
