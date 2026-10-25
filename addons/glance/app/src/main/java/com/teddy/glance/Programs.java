package com.teddy.glance;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;

import java.net.URISyntaxException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Other apps' rows in the TV provider (docs/watch-next.md): Watch Next for Continue watching, preview programs for
 * For you. Needs READ_TV_LISTINGS, and the query must not pass a selection, so whole tables are read and filtered here.
 */
final class Programs {
    static final int MAX = 12;

    private Programs() {
    }

    /** Watch Next, most recently watched first, one card per show. */
    static List<Item> continueWatching(Context c) {
        List<Item> all = new ArrayList<>();
        try (Cursor cur = c.getContentResolver().query(TvContract.WatchNextPrograms.CONTENT_URI, null, null, null, null)) {
            while (cur != null && cur.moveToNext()) {
                if (num(cur, "browsable", 1) == 0) continue;
                long pos = num(cur, "last_playback_position_millis", 0);
                long dur = num(cur, "duration_millis", 0);
                float progress = pos > 0 && dur > 0 ? Math.min(1f, (float) pos / dur) : -1f;
                String episode = str(cur, "episode_title");
                String subtitle = num(cur, "watch_next_type", 0) == TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT
                        ? (episode.isEmpty() ? "Up next" : "Up next · " + episode) : episode;
                all.add(new Item(str(cur, "package_name"), str(cur, "title"), subtitle,
                        poster(cur), str(cur, "intent_uri"), progress,
                        num(cur, "last_engagement_time_utc_millis", 0)));
            }
        } catch (RuntimeException e) {
            return all;   // no permission yet, or a provider without the table
        }
        all.sort((a, b) -> Long.compare(b.engaged, a.engaged));
        List<Item> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Item it : all) {
            if (seen.add(it.pkg + '\n' + it.title)) out.add(it);
            if (out.size() == MAX) break;
        }
        return out;
    }

    /** Preview programs, taken in turn from each app so one app cannot fill the row. */
    static List<Item> forYou(Context c) {
        Map<String, ArrayDeque<Item>> byApp = new LinkedHashMap<>();
        try (Cursor cur = c.getContentResolver().query(TvContract.PreviewPrograms.CONTENT_URI, null, null, null, null)) {
            while (cur != null && cur.moveToNext()) {
                String pkg = str(cur, "package_name");
                String poster = poster(cur);
                if (num(cur, "browsable", 1) == 0 || !poster.startsWith("http")) continue;
                byApp.computeIfAbsent(pkg, k -> new ArrayDeque<>()).add(new Item(pkg, str(cur, "title"),
                        str(cur, "episode_title"), poster, str(cur, "intent_uri"), -1f, 0));
            }
        } catch (RuntimeException e) {
            return new ArrayList<>();
        }
        List<Item> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        while (out.size() < MAX && !byApp.isEmpty()) {
            byApp.values().removeIf(q -> {
                Item it = q.poll();
                if (it != null && out.size() < MAX && seen.add(it.title)) out.add(it);
                return q.isEmpty();
            });
        }
        return out;
    }

    /**
     * The intent that opens a card, kept to the app that published it: its own deep link when that resolves inside the
     * app, otherwise the app's start screen. Never another app's component, a selector or a URI grant.
     */
    static Intent open(Context c, String pkg, String intentUri) {
        PackageManager pm = c.getPackageManager();
        Intent i = null;
        try {
            if (intentUri.startsWith("intent:") || intentUri.contains("#Intent;")) {
                i = Intent.parseUri(intentUri, Intent.URI_INTENT_SCHEME);
            } else if (!intentUri.isEmpty()) {
                i = new Intent(Intent.ACTION_VIEW, Uri.parse(intentUri));
            }
        } catch (URISyntaxException ignored) {
            i = null;
        }
        if (i != null) {
            i.setSelector(null);
            ComponentName cn = i.getComponent();
            if (cn != null && !cn.getPackageName().equals(pkg)) i.setComponent(null);
            i.setPackage(pkg);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (i.resolveActivity(pm) != null) return i;
        }
        Intent app = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (app == null) app = pm.getLaunchIntentForPackage(pkg);
        if (app != null) app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return app;
    }

    /** The app's name for the small label on a card. */
    static String appName(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private static String poster(Cursor cur) {
        String p = str(cur, "poster_art_uri");
        return p.isEmpty() ? str(cur, "thumbnail_uri") : p;
    }

    private static String str(Cursor c, String column) {
        int i = c.getColumnIndex(column);
        return i < 0 || c.isNull(i) ? "" : c.getString(i);
    }

    private static long num(Cursor c, String column, long fallback) {
        int i = c.getColumnIndex(column);
        return i < 0 || c.isNull(i) ? fallback : c.getLong(i);
    }
}
