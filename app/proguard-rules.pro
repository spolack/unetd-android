# Native entry points are resolved by name from C, so they must survive shrinking.
-keepclasseswithmembernames class * {
    native <methods>;
}
