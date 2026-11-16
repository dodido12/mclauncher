# Métodos llamados desde C++ vía JNI
-keep class com.example.mclauncher.launch.NativeBridge { *; }
-keep class com.example.mclauncher.input.LwjglBridge { *; }
-keepclassmembers class * { @kotlinx.serialization.Serializable *; }
