package com.termux.app;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.HttpAuthHandler;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.termux.BuildConfig;
import com.termux.R;
import com.termux.shared.android.PermissionUtils;
import com.termux.shared.errors.Error;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PlatformActivity extends AppCompatActivity {

    private static final int NOTIFICATION_PERMISSION_REQUEST = 100;
    private static final int BATTERY_OPTIMIZATION_REQUEST = 101;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService healthExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService diagnosticsExecutor = Executors.newSingleThreadExecutor();

    private FrameLayout root;
    private LinearLayout statusOverlay;
    private TextView statusText;
    private TextView diagnosticsText;
    private TextView diagnosticsFeedback;
    private ProgressBar progress;
    private Button retry;
    private WebView webView;
    private int healthPollAttempt;
    private long startupStartedMillis;
    private String lastHealthResult = "not checked";
    private String lastRecordedHealthResult = "";
    private String currentDiagnostics = "";
    private boolean diagnosticsVisible;
    private boolean diagnosticsRefreshScheduled;
    private boolean destroyed;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildContentView();
        if (!requestBatteryOptimizationExemption()) {
            continueInitialisation();
        }
    }

    private boolean requestBatteryOptimizationExemption() {
        if (PermissionUtils.checkIfBatteryOptimizationsDisabled(this)) return false;
        Error error = PermissionUtils.requestDisableBatteryOptimizations(
            this,
            BATTERY_OPTIMIZATION_REQUEST
        );
        if (error == null) return true;
        PlatformDiagnostics.record(
            this,
            "POWER",
            "Unable to open the battery optimization prompt: " + error.getMessage()
        );
        return false;
    }

    private void continueInitialisation() {
        requestNotificationPermission();
        initialiseRuntime();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == BATTERY_OPTIMIZATION_REQUEST) {
            continueInitialisation();
        }
    }

    private void buildContentView() {
        root = new FrameLayout(this);
        root.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));
        createWebView();

        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        statusOverlay = new LinearLayout(this);
        statusOverlay.setOrientation(LinearLayout.VERTICAL);
        statusOverlay.setGravity(Gravity.CENTER_HORIZONTAL);
        statusOverlay.setPadding(padding, padding, padding, padding);
        root.addView(statusOverlay, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));

        TextView title = new TextView(this);
        title.setText(R.string.application_name);
        title.setTextSize(28);
        title.setGravity(Gravity.CENTER);
        statusOverlay.addView(title);

        statusText = new TextView(this);
        statusText.setText(R.string.platform_preparing_runtime);
        statusText.setTextSize(16);
        statusText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusLayout = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        statusLayout.setMargins(0, padding, 0, padding);
        statusOverlay.addView(statusText, statusLayout);

        progress = new ProgressBar(this);
        statusOverlay.addView(progress);

        diagnosticsText = new TextView(this);
        diagnosticsText.setText(R.string.platform_diagnostics_waiting);
        diagnosticsText.setTextSize(12);
        diagnosticsText.setTypeface(Typeface.MONOSPACE);
        diagnosticsText.setTextIsSelectable(true);
        diagnosticsText.setGravity(Gravity.START);
        int diagnosticPadding = Math.round(12 * getResources().getDisplayMetrics().density);
        diagnosticsText.setPadding(diagnosticPadding, diagnosticPadding, diagnosticPadding, diagnosticPadding);
        ScrollView diagnosticScroll = new ScrollView(this);
        diagnosticScroll.setFillViewport(true);
        diagnosticScroll.addView(diagnosticsText, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        LinearLayout.LayoutParams diagnosticLayout = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1
        );
        diagnosticLayout.setMargins(0, padding, 0, diagnosticPadding);
        statusOverlay.addView(diagnosticScroll, diagnosticLayout);

        diagnosticsFeedback = new TextView(this);
        diagnosticsFeedback.setGravity(Gravity.CENTER);
        diagnosticsFeedback.setVisibility(View.GONE);
        statusOverlay.addView(diagnosticsFeedback);

        LinearLayout diagnosticActions = new LinearLayout(this);
        diagnosticActions.setOrientation(LinearLayout.HORIZONTAL);
        diagnosticActions.setGravity(Gravity.CENTER);
        Button copyDiagnostics = new Button(this);
        copyDiagnostics.setText(R.string.platform_copy_diagnostics);
        copyDiagnostics.setOnClickListener(view -> {
            copyDiagnostics();
            copyDiagnostics.setText(R.string.platform_diagnostics_copied);
        });
        diagnosticActions.addView(copyDiagnostics);
        Button shareDiagnostics = new Button(this);
        shareDiagnostics.setText(R.string.platform_share_diagnostics);
        shareDiagnostics.setOnClickListener(view -> shareDiagnostics());
        diagnosticActions.addView(shareDiagnostics);
        statusOverlay.addView(diagnosticActions);

        retry = new Button(this);
        retry.setText(R.string.platform_retry);
        retry.setVisibility(View.GONE);
        retry.setOnClickListener(view -> initialiseRuntime());
        statusOverlay.addView(retry);
        setContentView(root);
    }

    private void createWebView() {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
        webView = new WebView(this);
        webView.setVisibility(View.INVISIBLE);
        configureWebView(webView);
        root.addView(webView, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));
    }

    private void configureWebView(WebView target) {
        WebSettings settings = target.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setGeolocationEnabled(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(true);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSafeBrowsingEnabled(true);
        settings.setUserAgentString(
            settings.getUserAgentString() + " NixShipAndroid/" + BuildConfig.VERSION_NAME
        );

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(target, false);
        target.setWebViewClient(new PlatformWebViewClient());
        target.setWebChromeClient(new PlatformWebChromeClient());
        target.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) ->
            openExternalUri(Uri.parse(url))
        );
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                new String[]{Manifest.permission.POST_NOTIFICATIONS},
                NOTIFICATION_PERMISSION_REQUEST
            );
        }
    }

    private void initialiseRuntime() {
        mainHandler.removeCallbacksAndMessages(null);
        if (webView == null) createWebView();
        healthPollAttempt = 0;
        startupStartedMillis = System.currentTimeMillis();
        lastHealthResult = "not checked";
        lastRecordedHealthResult = "";
        diagnosticsVisible = true;
        diagnosticsFeedback.setVisibility(View.GONE);
        webView.setVisibility(View.INVISIBLE);
        PlatformDiagnostics.record(this, "APP", "Startup requested");
        showBusyStatus(R.string.platform_verifying_runtime);
        scheduleDiagnosticsRefresh(0);
        TermuxInstaller.setupPlatformBootstrapIfNeeded(this, () -> runOnUiThread(() -> {
            try {
                showBusyStatus(R.string.platform_starting_server);
                PlatformDiagnostics.record(this, "SERVICE", "Requesting foreground runtime service");
                ContextCompat.startForegroundService(this, PlatformRuntime.startIntent(this));
                showBusyStatus(R.string.platform_waiting_server);
                scheduleHealthCheck(0);
            } catch (RuntimeException error) {
                PlatformDiagnostics.record(
                    this,
                    "ERROR",
                    "Foreground service start failed: " + error.getClass().getSimpleName()
                        + ": " + error.getMessage()
                );
                showRetryStatus(R.string.platform_service_blocked);
            }
        }));
    }

    private void scheduleDiagnosticsRefresh(long delayMillis) {
        if (diagnosticsRefreshScheduled) return;
        diagnosticsRefreshScheduled = true;
        mainHandler.postDelayed(() -> {
            diagnosticsRefreshScheduled = false;
            if (destroyed || !diagnosticsVisible || diagnosticsExecutor.isShutdown()) return;
            diagnosticsExecutor.execute(() -> {
                String snapshot = PlatformDiagnostics.snapshot(
                    this,
                    startupStartedMillis,
                    healthPollAttempt,
                    lastHealthResult
                );
                mainHandler.post(() -> {
                    if (destroyed || !diagnosticsVisible) return;
                    currentDiagnostics = snapshot;
                    diagnosticsText.setText(snapshot);
                    scheduleDiagnosticsRefresh(BuildConfig.DIAGNOSTICS_REFRESH_MILLIS);
                });
            });
        }, delayMillis);
    }

    private void scheduleHealthCheck(long delayMillis) {
        mainHandler.postDelayed(() -> {
            if (destroyed || healthExecutor.isShutdown()) return;
            healthExecutor.execute(this::checkHealth);
        }, delayMillis);
    }

    private void checkHealth() {
        boolean healthy = false;
        String healthResult;
        HttpURLConnection connection = null;
        try {
            URL health = new URL(loopbackBaseUrl() + BuildConfig.HEALTH_PATH);
            connection = (HttpURLConnection) health.openConnection();
            connection.setConnectTimeout((int) Math.min(BuildConfig.POLL_INTERVAL_MILLIS, 5_000L));
            connection.setReadTimeout((int) Math.min(BuildConfig.POLL_INTERVAL_MILLIS, 5_000L));
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            int responseCode = connection.getResponseCode();
            healthy = responseCode >= 200 && responseCode < 300;
            healthResult = "HTTP " + responseCode;
        } catch (Exception error) {
            healthResult = error.getClass().getSimpleName()
                + (error.getMessage() == null ? "" : ": " + error.getMessage());
        } finally {
            if (connection != null) connection.disconnect();
        }
        lastHealthResult = healthResult;
        if (!healthResult.equals(lastRecordedHealthResult)) {
            lastRecordedHealthResult = healthResult;
            PlatformDiagnostics.record(this, "HEALTH", healthResult);
        }

        if (healthy) {
            PlatformDiagnostics.record(this, "READY", "Loopback health check passed");
            Uri startUri = resolveStartUri();
            mainHandler.post(() -> showDashboard(startUri));
            return;
        }

        mainHandler.post(() -> {
            healthPollAttempt++;
            long elapsedMillis = healthPollAttempt * BuildConfig.POLL_INTERVAL_MILLIS;
            if (elapsedMillis >= BuildConfig.STARTUP_TIMEOUT_SECONDS * 1_000L) {
                PlatformDiagnostics.record(this, "ERROR", "Startup health timeout reached");
                showRetryStatus(R.string.platform_server_timeout);
            } else {
                scheduleHealthCheck(BuildConfig.POLL_INTERVAL_MILLIS);
            }
        });
    }

    private Uri resolveStartUri() {
        Uri dashboard = Uri.parse(loopbackBaseUrl());
        String setupToken = readSetupToken();
        if (setupToken == null) return dashboard;
        return dashboard.buildUpon()
            .path(BuildConfig.SETUP_CLAIM_PATH)
            .appendQueryParameter("token", setupToken)
            .build();
    }

    @Nullable
    private String readSetupToken() {
        File tokenFile = new File(
            new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".nix-platform/data"),
            "setup-token.txt"
        );
        return PlatformSetupToken.read(
            tokenFile,
            BuildConfig.SETUP_TOKEN_MIN_CHARACTERS,
            BuildConfig.SETUP_TOKEN_MAX_BYTES
        );
    }

    private void showDashboard(Uri uri) {
        if (destroyed || webView == null) return;
        healthPollAttempt = 0;
        diagnosticsVisible = false;
        webView.loadUrl(uri.toString());
    }

    private void showBusyStatus(int message) {
        statusOverlay.setVisibility(View.VISIBLE);
        statusText.setText(message);
        progress.setVisibility(View.VISIBLE);
        retry.setVisibility(View.GONE);
    }

    private void showRetryStatus(int message) {
        diagnosticsVisible = true;
        statusOverlay.setVisibility(View.VISIBLE);
        statusText.setText(message);
        progress.setVisibility(View.GONE);
        retry.setVisibility(View.VISIBLE);
        scheduleDiagnosticsRefresh(0);
    }

    private String diagnosticsForExport() {
        if (!currentDiagnostics.isEmpty()) return currentDiagnostics;
        return PlatformDiagnostics.snapshot(
            this,
            startupStartedMillis,
            healthPollAttempt,
            lastHealthResult
        );
    }

    private void copyDiagnostics() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(
            ClipData.newPlainText(getString(R.string.platform_diagnostics_title), diagnosticsForExport())
        );
        diagnosticsFeedback.setText(R.string.platform_diagnostics_copied);
        diagnosticsFeedback.setVisibility(View.VISIBLE);
    }

    private void shareDiagnostics() {
        Intent share = new Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.platform_diagnostics_title))
            .putExtra(Intent.EXTRA_TEXT, diagnosticsForExport());
        try {
            startActivity(Intent.createChooser(share, getString(R.string.platform_share_diagnostics)));
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, R.string.platform_no_share_target, Toast.LENGTH_SHORT).show();
        }
    }

    private String loopbackBaseUrl() {
        return "http://" + BuildConfig.LOOPBACK_HOST + ":" + BuildConfig.SERVER_PORT;
    }

    private boolean isAllowedInAppUri(@Nullable Uri uri) {
        return PlatformNavigationPolicy.isAllowed(
            uri == null ? null : uri.toString(),
            BuildConfig.LOOPBACK_HOST,
            BuildConfig.SERVER_PORT,
            BuildConfig.QUICK_TUNNEL_HOST_SUFFIX
        );
    }

    private void openRequestedUri(Uri uri) {
        if (isAllowedInAppUri(uri) && webView != null) {
            webView.loadUrl(uri.toString());
        } else {
            openExternalUri(uri);
        }
    }

    private void openExternalUri(@Nullable Uri uri) {
        if (uri == null || uri.getScheme() == null) return;
        if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException ignored) {
            // A browser is optional; unhandled external links remain closed.
        }
    }

    private void handleWebRendererGone() {
        if (webView != null) {
            root.removeView(webView);
            webView.destroy();
            webView = null;
        }
        showRetryStatus(R.string.platform_webview_crashed);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        mainHandler.removeCallbacksAndMessages(null);
        healthExecutor.shutdownNow();
        diagnosticsExecutor.shutdownNow();
        if (webView != null) {
            root.removeView(webView);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private final class PlatformWebViewClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (isAllowedInAppUri(uri)) return false;
            openExternalUri(uri);
            return true;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            if (isAllowedInAppUri(Uri.parse(url))) showBusyStatus(R.string.platform_waiting_server);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (!isAllowedInAppUri(Uri.parse(url))) return;
            statusOverlay.setVisibility(View.GONE);
            view.setVisibility(View.VISIBLE);
        }

        @Override
        public void onReceivedError(
            WebView view,
            WebResourceRequest request,
            WebResourceError error
        ) {
            if (request.isForMainFrame()) showRetryStatus(R.string.platform_page_error);
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            handler.cancel();
        }

        @Override
        public void onReceivedHttpAuthRequest(
            WebView view,
            HttpAuthHandler handler,
            String host,
            String realm
        ) {
            handler.cancel();
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            handleWebRendererGone();
            return true;
        }
    }

    private final class PlatformWebChromeClient extends WebChromeClient {
        @Override
        public boolean onCreateWindow(
            WebView view,
            boolean isDialog,
            boolean isUserGesture,
            Message resultMsg
        ) {
            if (!isUserGesture) return false;
            WebView bridge = new WebView(PlatformActivity.this);
            AtomicBoolean handled = new AtomicBoolean();
            bridge.setWebViewClient(new WebViewClient() {
                private void handle(String url) {
                    if (!handled.compareAndSet(false, true)) return;
                    openRequestedUri(Uri.parse(url));
                    bridge.stopLoading();
                    bridge.destroy();
                }

                @Override
                public boolean shouldOverrideUrlLoading(WebView child, WebResourceRequest request) {
                    handle(request.getUrl().toString());
                    return true;
                }

                @Override
                public void onPageStarted(WebView child, String url, Bitmap favicon) {
                    if (!"about:blank".equals(url)) handle(url);
                }
            });
            WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
            transport.setWebView(bridge);
            resultMsg.sendToTarget();
            return true;
        }
    }
}
