package com.plansync.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Button;
import androidx.appcompat.app.AppCompatActivity;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

public class MainActivity extends AppCompatActivity {

    private static final String APP_URL = "https://plansyncapk.vercel.app";

    private WebView webView;
    private SwipeRefreshLayout swipeRefresh;
    private LinearLayout offlineLayout;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView       = findViewById(R.id.webview);
        swipeRefresh  = findViewById(R.id.swipe_refresh);
        offlineLayout = findViewById(R.id.offline_layout);
        Button retryBtn = findViewById(R.id.retry_button);

        // ── WebView settings ────────────────────────────────────────────────
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);       // localStorage (Firebase Auth needs this)
        settings.setDatabaseEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(false);
        settings.setGeolocationEnabled(false);
        settings.setSupportZoom(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);

        // Persist cookies across sessions
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        // ── WebViewClient — keep navigation inside the app ───────────────────
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                // Stay in-app for plansyncapk.vercel.app; open everything else in browser
                if (url.startsWith(APP_URL)) {
                    return false;
                }
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                startActivity(intent);
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
            public void onReceivedError(android.webkit.WebView view,
                                        android.webkit.WebResourceRequest request,
                                        android.webkit.WebResourceError error) {
                if (request.isForMainFrame()) {
                    swipeRefresh.setRefreshing(false);
                    webView.setVisibility(View.GONE);
                    offlineLayout.setVisibility(View.VISIBLE);
                }
            }
        });

        // ── WebChromeClient — needed for file inputs, alerts, etc. ───────────
        webView.setWebChromeClient(new WebChromeClient());

        // ── Pull-to-refresh ─────────────────────────────────────────────────
        swipeRefresh.setOnRefreshListener(() -> webView.reload());
        swipeRefresh.setColorSchemeResources(R.color.colorPrimary);

        // ── Retry button on offline screen ───────────────────────────────────
        retryBtn.setOnClickListener(v -> {
            if (isOnline()) {
                offlineLayout.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);
                webView.reload();
            }
        });

        // ── Handle deep links ────────────────────────────────────────────────
        String url = APP_URL;
        Intent intent = getIntent();
        if (intent != null && intent.getData() != null) {
            url = intent.getData().toString();
        }

        if (isOnline()) {
            webView.loadUrl(url);
        } else {
            offlineLayout.setVisibility(View.VISIBLE);
            webView.setVisibility(View.GONE);
        }
    }

    // ── Back button navigates WebView history ────────────────────────────────
    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    // ── Restore state on rotation ────────────────────────────────────────────
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
