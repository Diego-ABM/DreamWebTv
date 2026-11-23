# Interfaces expuestas a JavaScript (frontera SnifferBridge) — R10
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.example.dreamweb.engine.SnifferBridge { *; }

# Factories de Media3 instanciadas por reflexión desde
# DefaultMediaSourceFactory (HLS/DASH se degradarían sin esto)
-keep class androidx.media3.exoplayer.hls.HlsMediaSource$Factory { *; }
-keep class androidx.media3.exoplayer.dash.DashMediaSource$Factory { *; }
