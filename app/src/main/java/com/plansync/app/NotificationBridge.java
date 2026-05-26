package com.plansync.app;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * JavaScript bridge injected as window.NotificationBridge in the WebView.
 *
 * The web app calls these methods when the user tries to enable push
 * notifications. Without this bridge, window.Notification is undefined in
 * WebView and the FCM SDK cannot request permission or get a token.
 *
 * JS usage (already handled by notification-webview-shim.ts):
 *
 *   window.NotificationBridge.getPermissionStatus() → "granted"|"denied"|"default"
 *   window.NotificationBridge.requestPermission()   → fires Android dialog,
 *                                                      result delivered via
 *                                                      window.onNotificationPermissionResult("granted"|"denied")
 *   window.NotificationBridge.getFcmToken()         → current FCM token string or ""
 */
public class NotificationBridge {

    static final int REQUEST_CODE = 1001;

    private final Activity  activity;
    private final WebView   webView;

    public NotificationBridge(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView  = webView;
    }

    /** Returns the current Android notification permission status. */
    @JavascriptInterface
    public String getPermissionStatus() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return "granted"; // Implicit on < Android 13
        }
        int result = ContextCompat.checkSelfPermission(
                activity, Manifest.permission.POST_NOTIFICATIONS);
        if (result == PackageManager.PERMISSION_GRANTED) return "granted";

        boolean shouldShow = ActivityCompat.shouldShowRequestPermissionRationale(
                activity, Manifest.permission.POST_NOTIFICATIONS);
        return shouldShow ? "default" : "denied";
    }

    /**
     * Called by the web app to request permission.
     * Result is delivered back via window.onNotificationPermissionResult().
     */
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
            // Pre-Android 13: always granted, callback immediately
            deliverResult("granted");
        }
    }

    /**
     * Returns the current FCM token so the web app can save it to Firestore
     * without making a separate getToken() call.
     * Returns "" if no token is available yet.
     */
    @JavascriptInterface
    public String getFcmToken() {
        // MainActivity caches the latest token in a static field
        String token = MainActivity.latestFcmToken;
        return token != null ? token : "";
    }

    /** Deliver the permission result back to JavaScript. */
    void deliverResult(String result) {
        String js = "if(typeof window.onNotificationPermissionResult==='function')" +
                    "window.onNotificationPermissionResult('" + result + "');";
        activity.runOnUiThread(() -> webView.evaluateJavascript(js, null));
    }
}
