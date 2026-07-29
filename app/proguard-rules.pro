# Keep Room generated code and Hilt components working under R8 if minification is enabled.
-keepattributes *Annotation*
-keep class com.eklab.adblocker.db.** { *; }
-keep class com.eklab.adblocker.core.** { *; }
