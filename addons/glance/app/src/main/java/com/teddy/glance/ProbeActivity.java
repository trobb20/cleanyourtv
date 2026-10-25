package com.teddy.glance;

import android.app.Activity;
import android.content.ComponentName;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The probe: what this TV actually shares. Reads the whole Watch Next, preview and channel tables (no selection, see
 * docs/watch-next.md) and the active media sessions, shows a per-app summary on screen and writes it to logcat
 * under the tag "GlanceProbe" so it can be read from a computer.
 */
public final class ProbeActivity extends Activity {
    private static final String TAG = "GlanceProbe";
    private static final int SAMPLES = 3;

    private final StringBuilder out = new StringBuilder();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        TextView text = new TextView(this);
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextSize(13);
        text.setTextColor(Color.WHITE);
        text.setPadding(48, 32, 48, 32);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(16, 20, 28));
        scroll.setFocusable(true);
        scroll.addView(text);
        setContentView(scroll);
        scroll.requestFocus();

        probe();
        text.setText(out);
    }

    private void probe() {
        line("Glance probe");
        table("Watch Next", TvContract.WatchNextPrograms.CONTENT_URI, TvContract.WatchNextPrograms.COLUMN_TITLE);
        table("Preview programs", TvContract.PreviewPrograms.CONTENT_URI, TvContract.PreviewPrograms.COLUMN_TITLE);
        table("Channels", TvContract.Channels.CONTENT_URI, TvContract.Channels.COLUMN_DISPLAY_NAME);
        sessions();
    }

    /** Counts rows per package and prints a few samples from each, with the columns a Continue watching card needs. */
    private void table(String name, Uri uri, String titleColumn) {
        line("");
        line("== " + name);
        Map<String, Integer> counts = new TreeMap<>();
        Map<String, StringBuilder> samples = new TreeMap<>();
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c == null) {
                line("no cursor");
                return;
            }
            while (c.moveToNext()) {
                String pkg = string(c, TvContract.BaseTvColumns.COLUMN_PACKAGE_NAME);
                int n = counts.merge(pkg, 1, Integer::sum);
                if (n > SAMPLES) continue;
                StringBuilder s = samples.computeIfAbsent(pkg, k -> new StringBuilder());
                s.append("    ").append(string(c, titleColumn));
                append(s, c, "episode_title", "ep");
                append(s, c, "watch_next_type", "type");
                append(s, c, "last_engagement_time_utc_millis", "engaged");
                append(s, c, "last_playback_position_millis", "pos");
                append(s, c, "duration_millis", "dur");
                append(s, c, "browsable", "browsable");
                append(s, c, "poster_art_uri", "poster");
                append(s, c, "intent_uri", "intent");
                s.append('\n');
            }
        } catch (SecurityException e) {
            line("denied: " + e.getMessage());
            return;
        } catch (RuntimeException e) {
            line("failed: " + e);
            return;
        }
        if (counts.isEmpty()) line("(none)");
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            line(e.getKey() + ": " + e.getValue());
            out.append(samples.get(e.getKey()));
            Log.i(TAG, samples.get(e.getKey()).toString());
        }
    }

    private void sessions() {
        line("");
        line("== Media sessions");
        try {
            MediaSessionManager msm = getSystemService(MediaSessionManager.class);
            List<MediaController> list = msm.getActiveSessions(new ComponentName(this, MediaListener.class));
            if (list.isEmpty()) line("(none playing)");
            for (MediaController mc : list) {
                MediaMetadata md = mc.getMetadata();
                PlaybackState ps = mc.getPlaybackState();
                line(mc.getPackageName()
                        + " state=" + (ps == null ? "?" : ps.getState())
                        + (md == null ? "" : " | " + md.getString(MediaMetadata.METADATA_KEY_TITLE)
                        + " — " + md.getString(MediaMetadata.METADATA_KEY_ARTIST)
                        + " art=" + (md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) != null
                        || md.getBitmap(MediaMetadata.METADATA_KEY_ART) != null
                        || md.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI) != null)));
            }
        } catch (SecurityException e) {
            line("no notification access yet: " + e.getMessage());
        }
    }

    private static String string(Cursor c, String column) {
        int i = c.getColumnIndex(column);
        return i < 0 || c.isNull(i) ? "" : c.getString(i);
    }

    private static void append(StringBuilder s, Cursor c, String column, String label) {
        String v = string(c, column);
        if (v.isEmpty()) return;
        if (v.length() > 80) v = v.substring(0, 80) + "…";
        s.append(" | ").append(label).append('=').append(v);
    }

    private void line(String s) {
        out.append(s).append('\n');
        Log.i(TAG, s);
    }
}
