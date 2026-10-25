package com.teddy.streamprobe;

import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

/**
 * Throwaway feasibility probe for Stream to TV: loads a stream page in a WebView on the TV and logs every
 * HLS playlist request the page makes (URL plus request headers), to see whether the page keeps renewing its
 * stream token on its own. Not shipped.
 *
 *   am start -n com.teddy.streamprobe/.MainActivity -e url <page> [-e block ts]
 *
 * block=ts answers the page's video segment requests with an empty 204, so only playlists are fetched.
 * OK clicks the middle of the page (to start a player that waits for a click). Log tag: STVProbe;
 * also appended to files/probe.log.
 */
public class MainActivity extends Activity {
    private static final String TAG = "STVProbe";
    private static final String FIREFOX_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:158.0) Gecko/20100101 Firefox/158.0";

    private WebView web;
    private TextView overlay;
    private final ArrayDeque<String> lines = new ArrayDeque<>();
    private String startHost;
    private boolean blockSegments;
    private int segmentsBlocked;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        String url = getIntent().getStringExtra("url");
        blockSegments = "ts".equals(getIntent().getStringExtra("block"));

        FrameLayout root = new FrameLayout(this);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        overlay = new TextView(this);
        overlay.setTextColor(Color.WHITE);
        overlay.setTextSize(12);
        overlay.setBackgroundColor(0xAA000000);
        overlay.setPadding(16, 8, 16, 8);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        root.addView(overlay, lp);
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportMultipleWindows(true); // with onCreateWindow returning false, popups are dropped
        s.setUserAgentString(FIREFOX_UA);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView view, boolean dialog, boolean userGesture, android.os.Message msg) {
                log("popup blocked");
                return false;
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                if (!req.isForMainFrame()) return false;
                String host = req.getUrl().getHost();
                if (startHost != null && host != null && !host.endsWith(startHost)) {
                    log("redirect blocked: " + host);
                    return true;
                }
                return false;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                String path = u.getPath() == null ? "" : u.getPath();
                if (path.endsWith(".m3u8") || path.endsWith(".mpd")) {
                    StringBuilder h = new StringBuilder();
                    for (Map.Entry<String, String> e : req.getRequestHeaders().entrySet()) {
                        h.append(" | ").append(e.getKey()).append(": ").append(e.getValue());
                    }
                    log("PLAYLIST " + u + h);
                } else if (blockSegments && (path.endsWith(".ts") || path.endsWith(".m4s"))) {
                    if (++segmentsBlocked % 15 == 1) log("segments blocked: " + segmentsBlocked);
                    return new WebResourceResponse("video/mp2t", null, 204, "No Content", null,
                            new ByteArrayInputStream(new byte[0]));
                }
                return null;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                log("page loaded: " + url);
            }
        });

        if (url == null) {
            log("no url: am start ... -e url <page>");
            return;
        }
        startHost = baseHost(Uri.parse(url).getHost());
        log("probe start, block=" + (blockSegments ? "ts" : "none") + ", url=" + url);
        web.loadUrl(url);
    }

    /** Last two labels of a host, so www.example.com and live.example.com count as the same site. */
    private static String baseHost(String host) {
        if (host == null) return null;
        String[] p = host.split("\\.");
        return p.length <= 2 ? host : p[p.length - 2] + "." + p[p.length - 1];
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            float x = web.getWidth() / 2f, y = web.getHeight() / 2f;
            long t = SystemClock.uptimeMillis();
            web.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0));
            web.dispatchTouchEvent(MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, x, y, 0));
            log("clicked page center");
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void log(String msg) {
        String line = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + " " + msg;
        Log.i(TAG, line);
        try (FileWriter w = new FileWriter(new File(getFilesDir(), "probe.log"), true)) {
            w.write(line + "\n");
        } catch (IOException ignored) {
        }
        runOnUiThread(() -> {
            lines.addLast(line.length() > 160 ? line.substring(0, 160) + "…" : line);
            while (lines.size() > 6) lines.removeFirst();
            overlay.setText(String.join("\n", lines));
        });
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }
}
