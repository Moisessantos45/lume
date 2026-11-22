# Add project specific ProGuard rules here.

# Preserve line numbers for crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Media3 / ExoPlayer - keep session callbacks
-keep class androidx.media3.session.** { *; }
-keep class androidx.media3.exoplayer.** { *; }
-keep class androidx.media3.common.** { *; }

# Keep service entry points (referenced from AndroidManifest)
-keep public class com.example.lume.services.PlaybackService { *; }
-keep public class com.example.lume.MainActivity { *; }