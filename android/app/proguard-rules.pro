# Motion Mouse ProGuard Rules

# Keep application class
-keep class com.motionmouse.app.MotionMouseApplication { *; }

# Keep all Hilt generated components
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ActivityComponentManager { *; }

# Keep data classes used in protocol and settings
# (their field names are not used in reflection, but keeping them
#  avoids obfuscation issues with DataStore key names)
-keep class com.motionmouse.app.settings.MotionSettings { *; }
-keep class com.motionmouse.app.connection.ConnectionState { *; }
-keep class com.motionmouse.app.connection.DiscoveredHost { *; }
-keep class com.motionmouse.app.protocol.** { *; }

# Keep enums (obfuscation can break enum valueOf() calls)
-keepclassmembers enum com.motionmouse.app.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep Kotlin serialization (used for UDP JSON)
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# Keep Bluetooth and sensor classes (Android framework — not in our package)
-keep class android.bluetooth.** { *; }
-keep class android.hardware.** { *; }

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# DataStore
-keep class androidx.datastore.** { *; }
