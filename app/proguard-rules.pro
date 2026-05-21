# Keep WebView JS interface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep MainActivity
-keep class com.plansync.app.MainActivity { *; }
