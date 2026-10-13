# ProGuard / R8 rules for zai-shell release builds

# Keep app classes
-keep class com.zai.shell.** { *; }
-keep class com.zai.shell.plugin.** { *; }

# Capacitor plugins - keep annotations and plugin classes
-keep class com.getcapacitor.** { *; }
-keepclassmembers class * {
    @com.getcapacitor.annotation.CapacitorPlugin <methods>;
    @com.getcapacitor.annotation.CapacitorPlugin <fields>;
}
-keep @com.getcapacitor.annotation.CapacitorPlugin class * { *; }

# OkHttp (used by ZaiSseBridge)
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-keep interface okhttp3.** { *; }

# Kotlin metadata
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-dontwarn kotlin.**

# Keep BuildConfig for runtime version checks
-keep class com.zai.shell.BuildConfig { *; }

# Keep model classes used in JSON serialization
-keep class org.json.** { *; }

# Suppress warnings for missing optional classes
-dontwarn javax.annotation.**
