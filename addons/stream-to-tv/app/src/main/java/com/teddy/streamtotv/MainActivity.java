package com.teddy.streamtotv;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.ResolvingDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import org.json.JSONException;
import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Stream to TV: plays a live stream the laptop sends (see ControlServer), on the TV's own hardware decoder,
 * and keeps it playing across the site's token renewals (see TokenRewriter). Nothing runs once this screen
 * is closed: the server stops and the player is released in onStop.
 */
@OptIn(markerClass = UnstableApi.class)
public class MainActivity extends Activity implements ControlServer.Handler {
    private static final String TAG = "StreamToTV";
    /** Request headers a browser adds on its own; the TV's HTTP stack sets its own or must not send them. */
    private static final java.util.Set<String> SKIP_HEADERS = new java.util.HashSet<>(java.util.Arrays.asList(
            "host", "connection", "content-length", "accept-encoding", "te", "upgrade-insecure-requests",
            "sec-fetch-dest", "sec-fetch-mode", "sec-fetch-site", "sec-fetch-user", "priority", "pragma",
            "cache-control", "if-none-match", "if-modified-since", "range"));
    private static final long RETRY_MS = 3000, TOKEN_WAIT_RETRY_MS = 10000;

    enum State { READY, LOADING, PLAYING, WAITING_FOR_TOKEN, RETRYING }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final TokenRewriter rewriter = new TokenRewriter();
    private ControlServer server;
    private String pin;

    private PlayerView playerView;
    private LinearLayout panel;
    private TextView status;
    private TextView corner;
    private ExoPlayer player;

    private volatile State state = State.READY;
    private volatile String title = "";
    private volatile String streamHost;
    private int failures;
    /** Since the stream started: rebuffers while it was playing, and player errors. Shown in /status. */
    private volatile int stalls, errors;
    private final Runnable retryTask = this::retry;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        pin = loadPin();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        playerView = new PlayerView(this);
        playerView.setUseController(false);
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        root.addView(playerView, new FrameLayout.LayoutParams(-1, -1));

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setBackgroundColor(0xE6101418);
        TextView name = text(46, true);
        name.setText(getString(R.string.app_name));
        TextView where = text(26, false);
        where.setText(String.format(Locale.US, "Address  %s:%d\nPIN  %s", ipAddress(), ControlServer.PORT, pin));
        where.setTextColor(0xFF34C759);
        status = text(24, false);
        status.setTextColor(0xFFB0B8C0);
        panel.addView(name);
        panel.addView(where);
        panel.addView(status);
        root.addView(panel, new FrameLayout.LayoutParams(-1, -1));

        corner = text(18, false);
        corner.setBackgroundColor(0xB0000000);
        corner.setPadding(24, 12, 24, 12);
        corner.setVisibility(View.GONE);
        root.addView(corner, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END));
        setContentView(root);
        show(State.READY, "Open the stream in Firefox and press Send to TV.");
    }

    private TextView text(int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setTextColor(Color.WHITE);
        t.setTextSize(sp);
        t.setGravity(Gravity.CENTER);
        t.setPadding(48, 16, 48, 16);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    @Override
    protected void onStart() {
        super.onStart();
        server = new ControlServer(pin, this);
        server.start();
    }

    @Override
    protected void onStop() {
        server.close();
        server = null;
        stopPlayback("Open the stream in Firefox and press Send to TV.");
        super.onStop();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && player != null) {
            stopPlayback("Stopped. Send again from Firefox to resume.");
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ControlServer.Handler: called on the server thread.

    @Override
    public String play(String url, Map<String, String> headers, String newTitle) {
        if (TokenRewriter.hostOf(url) == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            return "url must be http or https";
        }
        main.post(() -> startPlayback(url, headers, newTitle));
        return null;
    }

    @Override
    public String token(String url) {
        if (player == null && state == State.READY) return "nothing is playing";
        if (rewriter.update(url)) {
            Log.i(TAG, "token renewed, generation " + rewriter.generation());
            main.post(() -> {
                flash("Stream token renewed");
                if (state == State.WAITING_FOR_TOKEN) retry();
            });
        }
        return null;
    }

    @Override
    public void stop() {
        main.post(() -> stopPlayback("Stopped from the laptop."));
    }

    @Override
    public JSONObject status(boolean full) {
        JSONObject o = new JSONObject();
        try {
            o.put("app", "stream-to-tv");
            o.put("state", state.name().toLowerCase(Locale.ROOT));
            o.put("renewals", rewriter.generation());
            o.put("stalls", stalls);
            o.put("errors", errors);
            if (full) {
                o.put("title", title);
                o.put("host", streamHost == null ? JSONObject.NULL : streamHost);
            }
        } catch (JSONException ignored) {
        }
        return o;
    }

    @Override
    public void serverFailed(String reason) {
        main.post(() -> {
            if (state == State.READY) show(State.READY, reason + "\nClose Stream to TV and open it again.");
        });
    }

    // Playback, on the main thread.

    private void startPlayback(String url, Map<String, String> headers, String newTitle) {
        releasePlayer();
        title = newTitle == null ? "" : newTitle;
        streamHost = TokenRewriter.hostOf(url);
        rewriter.reset(url);
        failures = 0;
        stalls = 0;
        errors = 0;

        Map<String, String> sendHeaders = new LinkedHashMap<>();
        String userAgent = null;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            String k = e.getKey().toLowerCase(Locale.ROOT);
            if (k.equals("user-agent")) userAgent = e.getValue();
            else if (!SKIP_HEADERS.contains(k)) sendHeaders.put(e.getKey(), e.getValue());
        }
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(userAgent != null ? userAgent : "Mozilla/5.0 (Android TV) StreamToTV/0.2")
                .setDefaultRequestProperties(sendHeaders)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(10_000)
                .setReadTimeoutMs(15_000);
        ResolvingDataSource.Factory resolving = new ResolvingDataSource.Factory(http, spec -> {
            String before = spec.uri.toString();
            String after = rewriter.rewrite(before);
            return after.equals(before) ? spec : spec.withUri(Uri.parse(after));
        });

        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(resolving))
                .build();
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int s) {
                if (s == Player.STATE_READY && player != null && player.getPlayWhenReady()) {
                    failures = 0;
                    show(State.PLAYING, "");
                } else if (s == Player.STATE_BUFFERING && state == State.PLAYING) {
                    stalls++;
                    Log.i(TAG, "buffering, stall " + stalls);
                    flash("Buffering…");
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                handleError(error);
            }
        });
        playerView.setPlayer(player);

        MediaItem.Builder item = new MediaItem.Builder().setUri(url);
        if (looksLikeHls(Uri.parse(url))) item.setMimeType(MimeTypes.APPLICATION_M3U8);
        player.setMediaItem(item.build());
        player.setPlayWhenReady(true);
        player.prepare();
        show(State.LOADING, "Loading " + (title.isEmpty() ? streamHost : title) + "…");
        Log.i(TAG, "playing " + streamHost + " with " + sendHeaders.keySet());
    }

    /**
     * HLS unless the URL says otherwise. The add-on sends only playlists, and some have no .m3u8 in the path (an
     * ad-inserting proxy's), which ExoPlayer would otherwise take for a plain media file.
     */
    private static boolean looksLikeHls(Uri uri) {
        int type = Util.inferContentType(uri);
        if (type != C.CONTENT_TYPE_OTHER) return type == C.CONTENT_TYPE_HLS;
        String last = uri.getLastPathSegment();
        return last == null || !last.matches("(?i).*\\.(mp4|m4v|mkv|webm|mov|mp3|m4a|aac|ts)");
    }

    private void handleError(PlaybackException error) {
        Log.w(TAG, "playback error " + error.getErrorCodeName(), error);
        if (player == null) return;
        errors++;
        int http = httpStatus(error);
        if (http == 401 || http == 403 || http == 410) {
            // The site's token has expired. The laptop sends a fresh one as soon as its page gets it; retry
            // then, and every few seconds in case the refusal was a blip.
            show(State.WAITING_FOR_TOKEN, "The stream's token expired.\nWaiting for Firefox to send a fresh one…");
            main.removeCallbacks(retryTask);
            main.postDelayed(retryTask, TOKEN_WAIT_RETRY_MS);
            return;
        }
        failures++;
        long delay = Math.min(RETRY_MS * failures, 15_000);
        show(State.RETRYING, "Connection problem (" + error.getErrorCodeName() + ").\nTrying again…");
        main.removeCallbacks(retryTask);
        main.postDelayed(retryTask, delay);
    }

    private static int httpStatus(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof HttpDataSource.InvalidResponseCodeException) {
                return ((HttpDataSource.InvalidResponseCodeException) c).responseCode;
            }
        }
        return -1;
    }

    private void retry() {
        main.removeCallbacks(retryTask);
        if (player == null) return;
        // A live stream resumes at the live edge rather than where it stopped.
        player.seekToDefaultPosition();
        player.prepare();
        player.setPlayWhenReady(true);
    }

    private void stopPlayback(String message) {
        releasePlayer();
        title = "";
        streamHost = null;
        show(State.READY, message);
    }

    private void releasePlayer() {
        main.removeCallbacks(retryTask);
        if (player != null) {
            playerView.setPlayer(null);
            player.release();
            player = null;
        }
    }

    private void show(State s, String message) {
        state = s;
        boolean video = s == State.PLAYING;
        panel.setVisibility(video ? View.GONE : View.VISIBLE);
        status.setText(message);
        // The screen may sleep on the idle screen; while a stream loads, plays or waits for a token it stays on.
        if (s != State.READY) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private final Runnable hideCorner = () -> corner.setVisibility(View.GONE);

    private void flash(String message) {
        corner.setText(message);
        corner.setVisibility(View.VISIBLE);
        main.removeCallbacks(hideCorner);
        main.postDelayed(hideCorner, 3000);
    }

    private String loadPin() {
        SharedPreferences p = getSharedPreferences("stream-to-tv", MODE_PRIVATE);
        String saved = p.getString("pin", null);
        if (saved != null) return saved;
        String fresh = String.format(Locale.US, "%04d", new SecureRandom().nextInt(10_000));
        p.edit().putString("pin", fresh).apply();
        return fresh;
    }

    /** The TV's address on the home network, for the laptop's settings. */
    private static String ipAddress() {
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!n.isUp() || n.isLoopback()) continue;
                for (InetAddress a : Collections.list(n.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return "this TV's IP";
    }
}
