package com.plansync.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class NotificationBridge {

    static final int REQUEST_CODE = 1001;

    private final Activity activity;
    private final WebView  webView;

    public NotificationBridge(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView  = webView;
    }

    @JavascriptInterface
    public String getPermissionStatus() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return "granted";
        }
        int result = ContextCompat.checkSelfPermission(
                activity, Manifest.permission.POST_NOTIFICATIONS);
        if (result == PackageManager.PERMISSION_GRANTED) return "granted";

        boolean shouldShow = ActivityCompat.shouldShowRequestPermissionRationale(
                activity, Manifest.permission.POST_NOTIFICATIONS);
        return shouldShow ? "default" : "denied";
    }

    @JavascriptInterface
    public void requestPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.runOnUiThread(() ->
                ActivityCompat.requestPermissions(
                    activity,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_CODE
                )
            );
        } else {
            deliverResult("granted");
        }
    }

    @JavascriptInterface
    public String getFcmToken() {
        String token = MainActivity.latestFcmToken;
        return token != null ? token : "";
    }

    /**
     * Called by the web app after login with the Firebase user UID.
     * Stores it in SharedPreferences and immediately saves the FCM token
     * to Firestore directly from Android — no JS timing issues.
     */
    @JavascriptInterface
    public void saveUserUid(String uid) {
        if (uid == null || uid.isEmpty()) return;
        activity.getSharedPreferences("plansync", Context.MODE_PRIVATE)
                .edit().putString("user_uid", uid).apply();
        MainActivity.saveTokenToFirestore(activity, uid);
    }

    /**
     * Called by the web app on logout.
     */
    @JavascriptInterface
    public void clearUserUid() {
        activity.getSharedPreferences("plansync", Context.MODE_PRIVATE)
                .edit().remove("user_uid").apply();
    }

    void deliverResult(String result) {
        String js = "if(typeof window.onNotificationPermissionResult==='function')" +
                    "window.onNotificationPermissionResult('" + result + "');";
        activity.runOnUiThread(() -> webView.evaluateJavascript(js, null));
    }
}
