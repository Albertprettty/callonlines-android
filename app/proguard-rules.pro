# WebView
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class * extends android.webkit.WebView { *; }
