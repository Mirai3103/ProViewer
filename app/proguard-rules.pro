# uniffi's Kotlin runtime loads the native library through JNA by reflection.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class uniffi.** { *; }
-dontwarn java.awt.*

# Bench-only CorpusGenerator: keep the class and its native method names so the JVM can
# resolve them in the minified benchmark variant (JNI name mangling).
-keepclasseswithmembernames class com.proviewer.app.bench.CorpusGenerator {
    native <methods>;
}

# JNI pixel path (Task 4): keep the NativePixels native method names for JNI resolution, and keep
# NativeRenderException with its (String, String) constructor so the Rust side can find and
# construct it by name.
-keepclasseswithmembernames class com.proviewer.app.engine.NativePixels {
    native <methods>;
}
-keep class com.proviewer.app.engine.NativeRenderException {
    <init>(java.lang.String, java.lang.String);
}
