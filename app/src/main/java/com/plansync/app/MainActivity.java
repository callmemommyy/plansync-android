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
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.Button;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.browser.customtabs.CustomTabColorSchemeParams;
import androidx.browser.customtabs.CustomTabsIntent;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;
import java.util.HashMap;
import java.util.Map;
import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends AppCompatActivity {

    private static final String TAG          = "PlanSync";
    private static final String APP_URL      = "https://plansyncapk.vercel.app";
    private static final String GITHUB_OWNER = "callmemommyy";
    private static final String GITHUB_REPO  = "plansync-android";

    // SharedPreferences key for the last release timestamp the user was shown
    private static final String PREF_LAST_SEEN_RELEASE = "last_seen_release";

    private WebView            webView;
    private SwipeRefreshLayout swipeRefresh;
    private LinearLayout       offlineLayout;
    private NotificationBridge notificationBridge;

    // File chooser callback — holds the pending <input type="file"> callback
    private ValueCallback<Uri[]> fileChooserCallback = null;

    // Launcher for picking files/photos from gallery or camera
    private ActivityResultLauncher<Intent> filePickerLauncher;

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

        // Register file picker launcher — must be done in onCreate
        filePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (fileChooserCallback == null) return;
                    Uri[] results = null;
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri dataUri = result.getData().getData();
                        if (dataUri != null) results = new Uri[]{dataUri};
                    }
                    fileChooserCallback.onReceiveValue(results);
                    fileChooserCallback = null;
                });

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
                .addOnSuccessListener(token -> {
                    latestFcmToken = token;
                    // Deliver token to WebView so it saves to Firestore immediately
                    postTokenRefresh(token);
                });

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
            // onFcmTokenRefreshed — handles token rotation (existing)
            // androidFcmReady    — handles first-time token save on login (new)
            String js = "if(typeof window.onFcmTokenRefreshed==='function')" +
                        "window.onFcmTokenRefreshed('" + newToken + "');" +
                        "if(typeof window.androidFcmReady==='function')" +
                        "window.androidFcmReady('" + newToken + "');";
            act.webView.evaluateJavascript(js, null);
        });
    }

    /**
     * Save FCM token directly to Firestore from Android.
     * Called when we have both the UID (from JS) and the token.
     */
    static void saveTokenToFirestore(Context context, String uid) {
        String token = latestFcmToken;
        if (token == null || token.isEmpty() || uid == null || uid.isEmpty()) return;

        Map<String, Object> data = new HashMap<>();
        data.put("token", token);
        data.put("platform", "android");

        FirebaseFirestore.getInstance()
            .collection("users")
            .document(uid)
            .collection("fcmTokens")
            .document(token)
            .set(data, SetOptions.merge())
            .addOnSuccessListener(v -> Log.d("PlanSyncFCM", "Token saved to Firestore for " + uid))
            .addOnFailureListener(e -> Log.w("PlanSyncFCM", "Failed to save token", e));
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
        settings.setAllowFileAccess(true);           // needed for file upload
        settings.setAllowContentAccess(true);        // needed for content:// URIs
        settings.setSupportZoom(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        // ── JS Bridge: Native Google Sign-In ─────────────────────────────────
        webView.addJavascriptInterface(new Object() {

            @JavascriptInterface
            public void triggerNativeGoogleSignIn() {
                runOnUiThread(() -> {
                    GoogleSignInHelper.signIn(MainActivity.this, new GoogleSignInHelper.Callback() {
                        @Override
                        public void onSuccess(String idToken) {
                            runOnUiThread(() -> {
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
                                String safeError = error.replace("'", "\\'").replace("\n", " ");
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
                // Re-deliver token on every page load so freshly logged-in
                // users always get their token saved to Firestore
                if (latestFcmToken != null) {
                    String safeToken = latestFcmToken;
                    String js = "if(typeof window.androidFcmReady==='function')" +
                                "window.androidFcmReady('" + safeToken + "');";
                    view.evaluateJavascript(js, null);
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

        // ── WebChromeClient handles <input type="file"> ───────────────────────
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView,
                                             ValueCallback<Uri[]> filePathCallback,
                                             FileChooserParams fileChooserParams) {
                // Cancel any previous pending callback
                if (fileChooserCallback != null) {
                    fileChooserCallback.onReceiveValue(null);
                }
                fileChooserCallback = filePathCallback;

                // Request storage/camera permission first if needed, then open picker
                requestStoragePermissionAndOpenPicker(fileChooserParams);
                return true;
            }
        });
    }

    private void requestStoragePermissionAndOpenPicker(WebChromeClient.FileChooserParams params) {
        // On Android 13+ use READ_MEDIA_IMAGES; below that use READ_EXTERNAL_STORAGE
        String permission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.READ_MEDIA_IMAGES
                : Manifest.permission.READ_EXTERNAL_STORAGE;

        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            openFilePicker(params);
        } else {
            // Store params and request permission; result handled in onRequestPermissionsResult
            pendingFileChooserParams = params;
            requestPermissions(new String[]{permission, Manifest.permission.CAMERA},
                    REQUEST_CODE_STORAGE);
        }
    }

    // Temporary holder for params while permission dialog is shown
    private WebChromeClient.FileChooserParams pendingFileChooserParams = null;
    private static final int REQUEST_CODE_STORAGE = 200;

    private void openFilePicker(WebChromeClient.FileChooserParams params) {
        // Build a chooser: gallery images + camera capture
        Intent galleryIntent = new Intent(Intent.ACTION_GET_CONTENT);
        galleryIntent.setType("image/*");
        galleryIntent.addCategory(Intent.CATEGORY_OPENABLE);

        Intent chooser = Intent.createChooser(galleryIntent, "Select Photo");
        filePickerLauncher.launch(chooser);
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

        if (requestCode == REQUEST_CODE_STORAGE) {
            // Whether granted or denied, try to open picker anyway
            // (user may have granted partial permission or denied — let the OS handle it)
            if (pendingFileChooserParams != null) {
                openFilePicker(pendingFileChooserParams);
                pendingFileChooserParams = null;
            }
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

    // ── Update check ─────────────────────────────────────────────────────────
    //
    // Instead of comparing integer version codes (which don't change when you
    // re-publish a new APK under the same tag), we compare the release's
    // `published_at` ISO-8601 timestamp against the last one the user was
    // notified about.  A brand-new release always has a newer timestamp, even
    // when the tag name/version number stays the same.
    //
    @SuppressWarnings("deprecation")
    private void checkForUpdate() {
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

                    JSONObject json      = new JSONObject(sb.toString());
                    String tagName       = json.getString("tag_name");
                    // published_at format: "2025-05-28T10:00:00Z" — lexicographic
                    // comparison works correctly for ISO-8601 UTC timestamps.
                    String publishedAt   = json.getString("published_at");

                    String downloadUrl = null;
                    JSONArray assets   = json.getJSONArray("assets");
                    for (int i = 0; i < assets.length(); i++) {
                        if (assets.getJSONObject(i).getString("name").endsWith(".apk")) {
                            downloadUrl = assets.getJSONObject(i)
                                                .getString("browser_download_url");
                            break;
                        }
                    }
                    if (downloadUrl == null) downloadUrl = json.getString("html_url");

                    return new UpdateInfo(tagName, publishedAt, downloadUrl);
                } catch (Exception e) {
                    Log.w(TAG, "Update check failed: " + e.getMessage());
                    return null;
                }
            }

            @Override
            protected void onPostExecute(UpdateInfo info) {
                if (info != null && isNewerRelease(info.publishedAt)) {
                    showUpdateDialog(info.versionName, info.publishedAt, info.downloadUrl);
                }
            }
        }.execute();
    }

    /**
     * Returns true when the release timestamp from GitHub is strictly newer
     * than the last one we told the user about.
     * ISO-8601 UTC strings ("2025-05-28T10:00:00Z") sort correctly as plain
     * strings, so no date parsing is needed.
     */
    private boolean isNewerRelease(String publishedAt) {
        if (publishedAt == null || publishedAt.isEmpty()) return false;
        String lastSeen = getSharedPreferences("plansync", MODE_PRIVATE)
                              .getString(PREF_LAST_SEEN_RELEASE, "");
        return publishedAt.compareTo(lastSeen) > 0;
    }

    /**
     * Show the "Update available" dialog and remember this release so we
     * don't nag the user again on the next app launch.
     */
    private void showUpdateDialog(String versionName, String publishedAt, String downloadUrl) {
        // Persist the timestamp immediately so repeated launches don't re-show
        // the dialog for the same release even if the user taps "Later".
        getSharedPreferences("plansync", MODE_PRIVATE)
                .edit()
                .putString(PREF_LAST_SEEN_RELEASE, publishedAt)
                .apply();

        new AlertDialog.Builder(this)
                .setTitle("Update Available")
                .setMessage("PlanSync " + versionName + " is available. Tap Update to install.")
                .setPositiveButton("Update", (d, w) ->
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl))))
                .setNegativeButton("Later", null)
                .setCancelable(true)
                .show();
    }

    // ── UpdateInfo — carries release metadata from the background thread ──────
    private static class UpdateInfo {
        final String versionName;   // tag_name  e.g. "v5"
        final String publishedAt;   // ISO-8601  e.g. "2025-05-28T10:00:00Z"
        final String downloadUrl;   // direct APK or release page URL

        UpdateInfo(String versionName, String publishedAt, String downloadUrl) {
            this.versionName  = versionName;
            this.publishedAt  = publishedAt;
            this.downloadUrl  = downloadUrl;
        }
    }

    // getInstalledVersionCode() is no longer used for the update comparison but
    // kept here in case other parts of the codebase reference it in future.
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
