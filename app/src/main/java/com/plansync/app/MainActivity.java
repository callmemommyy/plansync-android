package com.plansync.app;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.Button;
import androidx.appcompat.app.AppCompatActivity;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends AppCompatActivity {

    private static final String APP_URL      = "https://plansyncapk.vercel.app";
    private static final String GITHUB_OWNER = "callmemommyy";
    private static final String GITHUB_REPO  = "plansync-android";

    private WebView webView;
    private SwipeRefreshLayout swipeRefresh;
    private LinearLayout offlineLayout;

    // Get versionCode from the installed package — no BuildConfig needed
    private int getInstalledVersionCode() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 1;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView       = findViewById(R.id.webview);
        swipeRefresh  = findViewById(R.id.swipe_refresh);
        offlineLayout = findViewById(R.id.offline_layout);
        Button retryBtn = findViewById(R.id.retry_button);

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

        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) { loadApp(APP_URL); return; }
        Uri data = intent.getData();
        if (data != null) {
            String url = data.toString();
            if (url.contains("plansyncapk.vercel.app")) {
                loadApp(url);
                return;
            }
        }
        loadApp(APP_URL);
    }

    @SuppressLint("SetJavaScriptEnabled")
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
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();

                // Stay in WebView for the app
                if (url.contains("plansyncapk.vercel.app")) return false;

                // Stay in WebView for Google auth
                if (url.contains("accounts.google.com")          ||
                    url.contains("googleapis.com")                ||
                    url.contains("google.com/o/oauth2")           ||
                    url.contains("securetoken.google.com")        ||
                    url.contains("identitytoolkit.googleapis.com")) {
                    return false;
                }

                // Open everything else in external browser
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

    // ── Update checker ────────────────────────────────────────────────────────
    @SuppressWarnings("deprecation")
    private void checkForUpdate() {
        final int currentVersion = getInstalledVersionCode();

        new AsyncTask<Void, Void, UpdateInfo>() {
            @Override
            protected UpdateInfo doInBackground(Void... voids) {
                try {
                    String apiUrl = "https://api.github.com/repos/"
                            + GITHUB_OWNER + "/" + GITHUB_REPO + "/releases/latest";
                    HttpURLConnection conn =
                            (HttpURLConnection) new URL(apiUrl).openConnection();
                    conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);

                    if (conn.getResponseCode() != 200) return null;

                    BufferedReader reader =
                            new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    reader.close();

                    JSONObject json = new JSONObject(sb.toString());
                    String tagName = json.getString("tag_name"); // e.g. "v37"
                    int latestVersion = Integer.parseInt(tagName.replace("v", "").trim());

                    // Get direct APK download URL from release assets
                    String downloadUrl = null;
                    JSONArray assets = json.getJSONArray("assets");
                    for (int i = 0; i < assets.length(); i++) {
                        String name = assets.getJSONObject(i).getString("name");
                        if (name.endsWith(".apk")) {
                            downloadUrl = assets.getJSONObject(i)
                                    .getString("browser_download_url");
                            break;
                        }
                    }
                    if (downloadUrl == null) downloadUrl = json.getString("html_url");

                    return new UpdateInfo(latestVersion, tagName, downloadUrl);
                } catch (Exception e) {
                    return null;
                }
            }

            @Override
            protected void onPostExecute(UpdateInfo info) {
                if (info != null && info.versionCode > currentVersion) {
                    showUpdateDialog(info.versionName, info.downloadUrl);
                }
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
        final int versionCode;
        final String versionName;
        final String downloadUrl;
        UpdateInfo(int code, String name, String url) {
            versionCode = code; versionName = name; downloadUrl = url;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        webView.restoreState(savedInstanceState);
    }

    private boolean isOnline() {
        ConnectivityManager cm =
                (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo info = cm.getActiveNetworkInfo();
        return info != null && info.isConnected();
    }
}
