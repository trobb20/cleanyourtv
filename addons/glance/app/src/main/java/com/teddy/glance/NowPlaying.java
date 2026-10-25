package com.teddy.glance;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.text.TextUtils;

import java.util.Collections;
import java.util.List;

/** The media session to show as Now playing, for the page and for the launcher's row. */
final class NowPlaying {
    private NowPlaying() {
    }

    static ComponentName listener(Context c) {
        return new ComponentName(c, MediaListener.class);
    }

    /** Active sessions, or none without notification access. */
    static List<MediaController> sessions(Context c) {
        try {
            return c.getSystemService(MediaSessionManager.class).getActiveSessions(listener(c));
        } catch (SecurityException e) {
            return Collections.emptyList();
        }
    }

    /** The first session that is playing, else the first with a track; null when nothing has one. */
    static MediaController pick(List<MediaController> list) {
        MediaController next = null;
        if (list == null) return null;
        for (MediaController mc : list) {
            MediaMetadata md = mc.getMetadata();
            if (md == null || TextUtils.isEmpty(md.getString(MediaMetadata.METADATA_KEY_TITLE))) continue;
            PlaybackState ps = mc.getPlaybackState();
            if (ps != null && ps.getState() == PlaybackState.STATE_PLAYING) return mc;
            if (next == null) next = mc;
        }
        return next;
    }

    static MediaController current(Context c) {
        return pick(sessions(c));
    }

    static boolean paused(MediaController mc) {
        PlaybackState ps = mc.getPlaybackState();
        return ps != null && ps.getState() != PlaybackState.STATE_PLAYING;
    }

    static String artist(MediaMetadata md) {
        String a = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
        return a != null ? a : md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
    }

    static Bitmap art(MediaMetadata md) {
        Bitmap b = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        return b != null ? b : md.getBitmap(MediaMetadata.METADATA_KEY_ART);
    }

    static String artUri(MediaMetadata md) {
        String u = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI);
        return u != null ? u : md.getString(MediaMetadata.METADATA_KEY_ART_URI);
    }

    /** Opens the player: the session's own activity, else the app's start screen. */
    static void open(Context c, MediaController mc) {
        PendingIntent pi = mc.getSessionActivity();
        try {
            if (pi != null) {
                pi.send();
                return;
            }
        } catch (PendingIntent.CanceledException ignored) {
            // fall through to the app's start screen
        }
        Intent app = Programs.open(c, mc.getPackageName(), "");
        if (app != null) c.startActivity(app);
    }
}
