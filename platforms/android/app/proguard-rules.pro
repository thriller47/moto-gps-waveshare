# Keep the JNI entry points and parameter/return descriptor classes used by native Android libraries.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Valhalla's JNI-facing API, models, and config must keep the names and constructors expected by
# its native library.
-keep class com.valhalla.** { *; }

# zstd-jni native compression wrapper used by ValhallaGraphImporter.
-keep class com.github.luben.zstd.** { *; }

# MotoGPS JNI and BLE protocol models passed across native boundaries.
-keep class com.motogps.android.jni.** { *; }

# Preserve readable stack traces in release builds.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Strip verbose/debug logs in release builds to avoid runtime overhead.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
