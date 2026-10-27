# Keep the JNI entry points used by native Android libraries.
-keepclasseswithmembernames class * {
    native <methods>;
}

# Valhalla's JNI-facing API must keep the names and constructors expected by
# its native library. Other dependencies retain their own consumer rules.
-keep class com.valhalla.valhalla.** { *; }
