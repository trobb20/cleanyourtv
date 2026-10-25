package com.teddy.glance;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Base64;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The launcher's Glance row (glance_row), read-only and behind the READ_ROWS signature permission:
 * {@code content://com.teddy.glance.rows/home} lists Now playing, Continue watching and the snowiest mountain, and
 * {@code .../home/poster/<id>} serves each picture as a small JPEG, so the launcher itself never goes online.
 */
public final class GlanceProvider extends ContentProvider {
    static final String[] COLUMNS = {"kind", "pkg", "label", "title", "subtitle", "progress", "poster", "open"};
    private static final int RESUME_MAX = 10;
    private static final int CACHE_FILES = 80;
    private static final int ART_WIDTH = 360;

    private final ExecutorService io = Executors.newSingleThreadExecutor();

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        Context c = getContext();
        MatrixCursor out = new MatrixCursor(COLUMNS);
        if (c == null || !"home".equals(uri.getLastPathSegment())) return out;

        MediaController mc = NowPlaying.current(c);
        MediaMetadata md = mc == null ? null : mc.getMetadata();
        if (md != null) {
            String app = Programs.appName(c, mc.getPackageName()).toUpperCase(Locale.US);
            String artist = NowPlaying.artist(md);
            out.addRow(new Object[]{"now", mc.getPackageName(),
                    (NowPlaying.paused(mc) ? "PAUSED · " : "NOW PLAYING · ") + app,
                    md.getString(MediaMetadata.METADATA_KEY_TITLE), artist == null ? "" : artist, null,
                    nowArt(c, md), ""});
        }

        int n = 0;
        for (Item it : Programs.continueWatching(c)) {
            if (n++ == RESUME_MAX) break;
            out.addRow(new Object[]{"resume", it.pkg, Programs.appName(c, it.pkg).toUpperCase(Locale.US),
                    it.title, it.subtitle, it.progress >= 0 ? it.progress : null,
                    it.poster.startsWith("https://") ? encode(it.poster) : "", it.intentUri});
        }

        Snow.Resort best = snowiest(c);
        if (best != null) {
            out.addRow(new Object[]{"snow", "", "SNOW · IKON", best.name,
                    (best.next72 >= 0.1 ? Snow.inches(best.next72) : "0″") + " next 3 days · " + best.highF + "°F",
                    null, "", ""});
        }
        return out;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        List<String> seg = uri.getPathSegments();
        if (!"r".equals(mode) || seg.size() != 3 || !"poster".equals(seg.get(1))) throw new FileNotFoundException();
        String id = seg.get(2);
        File file;
        if (id.startsWith("now-")) {
            file = new File(cacheDir(), id.replaceAll("[^a-z0-9-]", "") + ".jpg");
        } else {
            String url = decode(id);
            if (url == null || !url.startsWith("https://")) throw new FileNotFoundException();
            file = new File(cacheDir(), sha1(url) + ".jpg");
            if (!file.exists()) {
                Bitmap b = Images.fetch(url);
                if (b == null) throw new FileNotFoundException(url);
                write(b, file);
            }
        }
        if (!file.exists()) throw new FileNotFoundException();
        file.setLastModified(System.currentTimeMillis());
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /** Writes the session's art once per track; the id changes with the track so the launcher reloads it. */
    private String nowArt(Context c, MediaMetadata md) {
        Bitmap art = NowPlaying.art(md);
        String uri = NowPlaying.artUri(md);
        if (art == null) return uri != null && uri.startsWith("https://") ? encode(uri) : "";
        String id = "now-" + sha1(md.getString(MediaMetadata.METADATA_KEY_TITLE) + '\n' + NowPlaying.artist(md)).substring(0, 16);
        File file = new File(cacheDir(), id + ".jpg");
        if (!file.exists()) write(art, file);
        return id;
    }

    /** The mountain with the most snow in the next three days, from the hourly cache the page also uses. */
    private Snow.Resort snowiest(Context c) {
        SharedPreferences prefs = c.getSharedPreferences("glance", Context.MODE_PRIVATE);
        String json = prefs.getString("snow", null);
        if (json == null || System.currentTimeMillis() - prefs.getLong("snow_at", 0) > Snow.REFRESH_MS) {
            io.execute(() -> {   // the next read (Home's minute tick) gets it
                try {
                    String fresh = Snow.fetch();
                    Snow.parse(fresh);
                    prefs.edit().putString("snow", fresh).putLong("snow_at", System.currentTimeMillis()).apply();
                } catch (Exception ignored) {
                    // offline: keep the old numbers
                }
            });
        }
        if (json == null) return null;
        try {
            Snow.Resort best = null;
            for (Snow.Resort r : Snow.parse(json)) if (best == null || r.next72 > best.next72) best = r;
            return best;
        } catch (Exception e) {
            return null;
        }
    }

    private File cacheDir() {
        File dir = new File(getContext().getCacheDir(), "posters");
        if (!dir.isDirectory()) dir.mkdirs();
        return dir;
    }

    /** Scales to ART_WIDTH and keeps the folder to CACHE_FILES, oldest out first. */
    private void write(Bitmap b, File file) {
        if (b.getWidth() > ART_WIDTH) {
            b = Bitmap.createScaledBitmap(b, ART_WIDTH, Math.max(1, b.getHeight() * ART_WIDTH / b.getWidth()), true);
        }
        File tmp = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            b.compress(Bitmap.CompressFormat.JPEG, 85, out);
        } catch (IOException e) {
            tmp.delete();
            return;
        }
        tmp.renameTo(file);
        File[] all = cacheDir().listFiles();
        if (all != null && all.length > CACHE_FILES) {
            Arrays.sort(all, Comparator.comparingLong(File::lastModified));
            for (int i = 0; i < all.length - CACHE_FILES; i++) all[i].delete();
        }
    }

    private static String encode(String url) {
        return Base64.encodeToString(url.getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static String decode(String id) {
        try {
            return new String(Base64.decode(id, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING),
                    StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String sha1(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte x : d) hex.append(String.format(Locale.US, "%02x", x));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException("read-only");
    }
}
