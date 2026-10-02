# Native entry points are resolved by name from C, so they must survive shrinking.
-keepclasseswithmembernames class * {
    native <methods>;
}

# The JNI bridges: Java_<class>_<method> symbols in libunet-android.so and
# libwg-go.so are looked up by class and method name at load time.
-keep class org.unetd.android.nativebridge.** { *; }

# Called from C by name (unetd_jni.c) on whichever object implements it.
-keep interface org.unetd.android.nativebridge.Unetd$Callbacks { *; }
-keepclassmembers class * implements org.unetd.android.nativebridge.Unetd$Callbacks {
    public boolean protectSocket(int);
    public void onNetworkUpdate(java.lang.String);
    public void onEvent(int, java.lang.String, java.lang.String);
}
