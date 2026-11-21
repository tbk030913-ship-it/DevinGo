# kotlinx.serialization: keep generated serializers and companion accessors.
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-dontwarn kotlinx.serialization.**
-keepclassmembers class com.devingo.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.devingo.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class com.devingo.app.acp.** { *; }

# R8 strips pieces of the Compose animation machinery that animateValueAsState
# resumes reflectively on the UI dispatcher (NPE in released builds).
-keep class androidx.compose.animation.** { *; }
