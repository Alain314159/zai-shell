# Add project specific ProGuard rules here.
-keep class com.zai.shell.** { *; }
-keep class com.zai.shell.plugin.** { *; }
-keepclassmembers class * {
    @com.getcapacitor.annotation.CapacitorPlugin <methods>;
}
-dontwarn com.getcapacitor.**
