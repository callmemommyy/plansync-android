package com.plansync.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class NotificationBridge {

    static final int REQUEST_CODE = 1001;
    private static final String TAG = "PlanSyncFCM";
    private static final String REGISTER_URL = "https://plansyncapk.vercel.app/api/register-token";
    private static final String NOTIFY_SECRET = "plansync-super-secret-2026-xk92mqfuck";

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
     * Saves UID to SharedPreferences then calls Vercel /api/register-token
     * directly from Java — completely bypasses JS/bridge timing issues.
     */
    @JavascriptInterface
    public void saveUserUid(String uid) {
        if (uid == null || uid.isEmpty()) return;

        // Store uid for future token refreshes
        activity.getSharedPreferences("plansync", Context.MODE_PRIVATE)
                .edit().putString("user_uid", uid).apply();

        // Register token immediately in background thread
        String token = MainActivity.latestFcmToken;
        if (token != null && !token.isEmpty()) {
            registerTokenWithVercel(uid, token);
        } else {
            // Token not ready yet — retry every second for 20 seconds
            new Thread(() -> {
                for (int i = 0; i < 20; i++) {
                    try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
                    String t = MainActivity.latestFcmToken;
                    if (t != null && !t.isEmpty()) {
                        registerTokenWithVercel(uid, t);
                        return;
                    }
                }
                Log.w(TAG, "Token never became available for uid=" + uid);
            }).start();
        }
    }

    /**
     * Called when FCM token rotates — re-register with new token.
     * Called from MainActivity.postTokenRefresh()
     */
    public void onTokenRefreshed(String token) {
        String uid = activity.getSharedPreferences("plansync", Context.MODE_PRIVATE)
                .getString("user_uid", null);
        if (uid != null && !uid.isEmpty()) {
            registerTokenWithVercel(uid, token);
        }
    }

    /**
     * Called by the web app on logout.
     */
    @JavascriptInterface
    public void clearUserUid() {
        activity.getSharedPreferences("plansync", Context.MODE_PRIVATE)
                .edit().remove("user_uid").apply();
    }

    /**
     * POST { uid, token } to Vercel /api/register-token
     * Runs on a background thread — never blocks UI.
     */
    static void registerTokenWithVercel(String uid, String token) {
        new Thread(() -> {
            try {
                URL url = new URL(REGISTER_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("x-notify-secret", NOTIFY_SECRET);
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);

                String body = "{\"uid\":\"" + uid + "\",\"token\":\"" + token + "\"}";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bytes);
                }

                int code = conn.getResponseCode();
                if (code == 200) {
                    Log.d(TAG, "Token registered successfully for uid=" + uid);
                } else {
                    Log.w(TAG, "Token registration failed: HTTP " + code);
                }
                conn.disconnect();

            } catch (Exception e) {
                Log.e(TAG, "Token registration error", e);
            }
        }).start();
    }

    void deliverResult(String result) {
        String js = "if(typeof window.onNotificationPermissionResult==='function')" +
                    "window.onNotificationPermissionResult('" + result + "');";
        activity.runOnUiThread(() -> webView.evaluateJavascript(js, null));
    }
}
