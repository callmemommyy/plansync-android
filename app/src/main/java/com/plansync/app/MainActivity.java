package com.plansync.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;
import androidx.browser.customtabs.CustomTabColorSchemeParams;
import androidx.browser.customtabs.CustomTabsIntent;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.firebase.messaging.FirebaseMessaging;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends AppCompatActivity {

    private static final String TAG        = "PlanSync";
    private static final String APP_URL    = "https://plansyncapk.vercel.app";
    private static final String GITHUB_OWNER = "callmemommyy";
    private static final String GITHUB_REPO  = "plansync-android";

    private WebView            webView;
    private SwipeRefreshLayout swipeRefresh;
    private LinearLayout       offlineLayout;
    private NotificationBridge notificationBridge;

    static volatile String latestFcmToken = null;
    private static MainActivity instance  = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;
        setContentView(R.layout.activity_main);

        webView       = findViewById(R.id.webview);
        swipeRefresh  = findViewById(R.id.swipe_refresh);
        offlineLayout = findViewById(R.id.offline_layout);
        Button retryBtn = findViewById(R.id.retry_button);

        PlanSyncFirebaseService.ensureChannel(this);
        setupWebView();

        swipeRefresh.setOnRefreshListener(() -> webView.reload());
        swipeRefresh.setColorSchemeResources(R.color.colorPrimary);

        retryBtn.setOnClickListener(v -> {
            if (isOnline()) {
                offlineLayout.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);
                webView.reload();
            }
        });

        FirebaseMessaging.getInstance().getToken()
                .addOnSuccessListener(token -> latestFcmToken = token);

        requestNotificationPermissionIfNeeded();
        handleIntent(getIntent());
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (instance == this) instance = null;
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    static void postTokenRefresh(String newToken) {
        latestFcmToken = newToken;
        MainActivity act = instance;
        if (act == null) return;
        act.runOnUiThread(() -> {
            String js = "if(typeof window.onFcmTokenRefreshed==='function')" +
                        "window.onFcmTokenRefreshed('" + newToken + "');";
            act.webView.evaluateJavascript(js, null);
        });
    }

    private void handleIntent(Intent intent) {
        if (intent == null) { loadApp(APP_URL); return; }
        Uri data = intent.getData();
        if (data != null && data.toString().startsWith(APP_URL)) {
            loadApp(data.toString());
            return;
        }
        loadApp(APP_URL);
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(false);
        settings.setSupportZoom(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        // ── JS Bridge: Native Google Sign-In ─────────────────────────────────
        // The web app calls Android.triggerNativeGoogleSignIn() instead of
        // using signInWithRedirect/popup. We launch Credential Manager,
        // get an ID token, and post it back via window.onNativeGoogleToken().
        webView.addJavascriptInterface(new Object() {

            @JavascriptInterface
            public void triggerNativeGoogleSignIn() {
                // Must run on main thread for Credential Manager UI
                runOnUiThread(() -> {
                    GoogleSignInHelper.signIn(MainActivity.this, new GoogleSignInHelper.Callback() {
                        @Override
                        public void onSuccess(String idToken) {
                            runOnUiThread(() -> {
                                // Escape the token for safe JS injection
                                String safeToken = idToken.replace("'", "\\'");
                                String js = "if(typeof window.onNativeGoogleToken==='function')" +
                                            "window.onNativeGoogleToken('" + safeToken + "');";
                                webView.evaluateJavascript(js, null);
                            });
                        }

                        @Override
                        public void onFailure(String error) {
                            Log.e(TAG, "Native Google Sign-In failed: " + error);
                            runOnUiThread(() -> {
                                String safeError = error.replace("'", "\\'")
                                                        .replace("\n", " ");
                                String js = "if(typeof window.onNativeGoogleError==='function')" +
                                            "window.onNativeGoogleError('" + safeError + "');";
                                webView.evaluateJavascript(js, null);
                            });
                        }

                        @Override
                        public void onCancelled() {
                            runOnUiThread(() -> {
                                String js = "if(typeof window.onNativeGoogleCancelled==='function')" +
                                            "window.onNativeGoogleCancelled();";
                                webView.evaluateJavascript(js, null);
                            });
                        }
                    });
                });
            }

            // Legacy fallback — kept so old JS calls don't crash
            @JavascriptInterface
            public void openGoogleAuth(String url) {
                runOnUiThread(() -> openInCustomTab(url));
            }

        }, "Android");

        // ── JS Bridge: Notification permission ───────────────────────────────
        notificationBridge = new NotificationBridge(this, webView);
        webView.addJavascriptInterface(notificationBridge, "NotificationBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.startsWith(APP_URL)) return false;

                // Fallback: if a Google auth URL somehow slips through, use Custom Tab
                if (url.contains("accounts.google.com") ||
                    url.contains("google.com/o/oauth2") ||
                    url.contains("identitytoolkit.googleapis.com")) {
                    openInCustomTab(url);
                    return true;
                }

                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception ignored) {}
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                swipeRefresh.setRefreshing(false);
                if (isOnline()) {
                    webView.setVisibility(View.VISIBLE);
                    offlineLayout.setVisibility(View.GONE);
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        android.webkit.WebResourceError error) {
                if (request.isForMainFrame()) {
                    swipeRefresh.setRefreshing(false);
                    webView.setVisibility(View.GONE);
                    offlineLayout.setVisibility(View.VISIBLE);
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient());
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) return;
        requestPermissions(
            new String[]{Manifest.permission.POST_NOTIFICATIONS},
            NotificationBridge.REQUEST_CODE
        );
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == NotificationBridge.REQUEST_CODE && notificationBridge != null) {
            boolean granted = grantResults.length > 0 &&
                              grantResults[0] == PackageManager.PERMISSION_GRANTED;
            notificationBridge.deliverResult(granted ? "granted" : "denied");
        }
    }

    private void openInCustomTab(String url) {
        CustomTabsIntent intent = new CustomTabsIntent.Builder()
                .setDefaultColorSchemeParams(
                        new CustomTabColorSchemeParams.Builder()
                                .setToolbarColor(Color.parseColor("#6366F1"))
                                .build())
                .setShowTitle(true)
                .build();
        intent.launchUrl(this, Uri.parse(url));
    }

    private void loadApp(String url) {
        if (isOnline()) {
            webView.setVisibility(View.VISIBLE);
            offlineLayout.setVisibility(View.GONE);
            webView.loadUrl(url);
            checkForUpdate();
        } else {
            offlineLayout.setVisibility(View.VISIBLE);
            webView.setVisibility(View.GONE);
        }
    }

    @SuppressWarnings("deprecation")
    private void checkForUpdate() {
        final int currentVersion = getInstalledVersionCode();
        new AsyncTask<Void, Void, UpdateInfo>() {
            @Override
            protected UpdateInfo doInBackground(Void... v) {
                try {
                    String apiUrl = "https://api.github.com/repos/"
                            + GITHUB_OWNER + "/" + GITHUB_REPO + "/releases/latest";
                    HttpURLConnection conn =
                            (HttpURLConnection) new URL(apiUrl).openConnection();
                    conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);
                    if (conn.getResponseCode() != 200) return null;
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    reader.close();
                    JSONObject json   = new JSONObject(sb.toString());
                    String tagName    = json.getString("tag_name");
                    int latestVersion = Integer.parseInt(tagName.replace("v", "").trim());
                    String downloadUrl = null;
                    JSONArray assets  = json.getJSONArray("assets");
                    for (int i = 0; i < assets.length(); i++) {
                        if (assets.getJSONObject(i).getString("name").endsWith(".apk")) {
                            downloadUrl = assets.getJSONObject(i).getString("browser_download_url");
                            break;
                        }
                    }
                    if (downloadUrl == null) downloadUrl = json.getString("html_url");
                    return new UpdateInfo(latestVersion, tagName, downloadUrl);
                } catch (Exception e) { return null; }
            }
            @Override
            protected void onPostExecute(UpdateInfo info) {
                if (info != null && info.versionCode > currentVersion)
                    showUpdateDialog(info.versionName, info.downloadUrl);
            }
        }.execute();
    }

    private void showUpdateDialog(String versionName, String downloadUrl) {
        new AlertDialog.Builder(this)
                .setTitle("Update Available")
                .setMessage("PlanSync " + versionName + " is available. Tap Update to install.")
                .setPositiveButton("Update", (d, w) ->
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl))))
                .setNegativeButton("Later", null)
                .setCancelable(true)
                .show();
    }

    private static class UpdateInfo {
        final int versionCode; final String versionName, downloadUrl;
        UpdateInfo(int c, String n, String u) { versionCode=c; versionName=n; downloadUrl=u; }
    }

    private int getInstalledVersionCode() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionCode;
        } catch (PackageManager.NameNotFoundException e) { return 1; }
    }

    @Override public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        webView.restoreState(savedInstanceState);
    }

    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo info = cm.getActiveNetworkInfo();
        return info != null && info.isConnected();
    }
}
