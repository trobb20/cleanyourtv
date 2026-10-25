package com.gathin.tvhome;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * glance_row: one row above the dock from the Glance add-on (com.teddy.glance): what is playing, Continue watching
 * and snow. Rows come from the add-on's provider, behind its signature permission, and posters arrive as files from
 * the same provider, so the launcher never goes online. Read when Home comes to the front and on the minute tick;
 * the cards are rebuilt only when the rows changed and none of them has focus.
 */
final class GlanceRow {
    static final String PACKAGE = "com.teddy.glance";
    private static final Uri HOME = Uri.parse("content://com.teddy.glance.rows/home");
    private static final int CARD_W_DP = 180;
    private static final int NOW_W_DP = 260;
    private static final int SNOW_W_DP = 150;
    private static final int CARD_H_DP = 101;
    private static final int GAP_DP = 16;
    /** Room around the cards for the focus zoom. */
    private static final int ZOOM_ROOM_DP = 10;

    /** Where focus goes when it leaves the row, and what Home does when a card takes it. */
    interface Host {
        void glanceUp();

        void glanceDown();

        void glanceFocused();
    }

    private static final class Row {
        String kind, pkg, label, title, subtitle, poster, open;
        float progress;
    }

    private final Activity activity;
    private final Host host;
    private final float density;
    private final boolean animations;
    private final boolean reducedMotion;
    private final HorizontalScrollView view;
    private final LinearLayout cards;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final LruCache<String, Bitmap> posters = new LruCache<String, Bitmap>(6 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    /** The rows on screen, as one string, to skip rebuilding identical cards every minute. */
    private String shown = "";
    private int lastFocused;
    private boolean hasCards;
    /** False while focus is in the grid below the dock, whose rows scroll up through this band. */
    private boolean wanted = true;

    GlanceRow(Activity activity, Host host, float density, boolean animations, boolean reducedMotion) {
        this.activity = activity;
        this.host = host;
        this.density = density;
        this.animations = animations;
        this.reducedMotion = reducedMotion;
        view = new HorizontalScrollView(activity);
        view.setHorizontalScrollBarEnabled(false);
        view.setFocusable(false);
        view.setClipToPadding(false);
        view.setClipChildren(false);
        int room = px(ZOOM_ROOM_DP);
        view.setPadding(room, room, room, room);
        view.setVisibility(View.GONE);
        cards = new LinearLayout(activity);
        cards.setClipChildren(false);
        view.addView(cards);
    }

    View view() {
        return view;
    }

    /**
     * Puts the row's bottom (its cards plus the zoom room) at bottomY in the parent, never above minTop, with the
     * first card starting at startX like the dock's first tile.
     */
    void place(float bottomY, float minTop, int startX) {
        float h = px(CARD_H_DP + 2 * ZOOM_ROOM_DP);
        // A layout margin, not setY: focus search goes by layout position and ignores translation, so a translated
        // row still counted as sitting beside the top bar and took Left/Right from its pills.
        int top = Math.round(Math.max(minTop, bottomY - h));
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
        if (lp != null && lp.topMargin != top) {
            lp.topMargin = top;
            view.setLayoutParams(lp);
        }
        int left = Math.max(0, startX - px(ZOOM_ROOM_DP));
        if (view.getPaddingLeft() != left) view.setPadding(left, view.getPaddingTop(), left, view.getPaddingBottom());
    }

    void refresh() {
        if (io.isShutdown()) return;
        io.execute(() -> {
            List<Row> rows = query();
            for (Row r : rows) {
                if (!r.poster.isEmpty() && posters.get(r.poster) == null) {
                    Bitmap b = decode(r.poster);
                    if (b != null) posters.put(r.poster, b);
                }
            }
            String signature = signature(rows);
            activity.runOnUiThread(() -> show(rows, signature));
        });
    }

    /** Shows or hides the row with the dock: hidden while focus is in the grid rows below it. */
    void setWanted(boolean wanted) {
        if (this.wanted == wanted) return;
        this.wanted = wanted;
        updateVisibility();
    }

    /** Focuses the card that had focus last; false when the row is not showing. */
    boolean focus() {
        if (!hasCards || !wanted || view.getVisibility() != View.VISIBLE) return false;
        View card = cards.getChildAt(Math.min(lastFocused, cards.getChildCount() - 1));
        return card != null && card.requestFocus();
    }

    /** Memory pressure while Home is in the background: drop the posters and cards; refresh() builds them again. */
    void release() {
        cards.removeAllViews();
        posters.evictAll();
        shown = "";
        hasCards = false;
        view.setVisibility(View.GONE);
    }

    void destroy() {
        io.shutdownNow();
        posters.evictAll();
    }

    private List<Row> query() {
        List<Row> rows = new ArrayList<>();
        ContentResolver cr = activity.getContentResolver();
        try (Cursor c = cr.query(HOME, null, null, null, null)) {
            while (c != null && c.moveToNext()) {
                Row r = new Row();
                r.kind = text(c, "kind");
                r.pkg = text(c, "pkg");
                r.label = text(c, "label");
                r.title = text(c, "title");
                r.subtitle = text(c, "subtitle");
                r.poster = text(c, "poster");
                r.open = text(c, "open");
                int i = c.getColumnIndex("progress");
                r.progress = i < 0 || c.isNull(i) ? -1f : c.getFloat(i);
                rows.add(r);
            }
        } catch (RuntimeException e) {
            // No add-on, no permission, or the add-on is being updated: no row this time.
        }
        return rows;
    }

    private Bitmap decode(String poster) {
        Uri uri = HOME.buildUpon().appendPath("poster").appendPath(poster).build();
        ContentResolver cr = activity.getContentResolver();
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = cr.openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, o);
            }
            int target = px(CARD_W_DP);
            o.inSampleSize = 1;
            while (o.outWidth / (o.inSampleSize * 2) >= target) o.inSampleSize *= 2;
            o.inJustDecodeBounds = false;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            try (InputStream in = cr.openInputStream(uri)) {
                return BitmapFactory.decodeStream(in, null, o);
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private void show(List<Row> rows, String signature) {
        if (activity.isDestroyed() || io.isShutdown()) return;
        if (!signature.equals(shown) && !cards.hasFocus()) {
            cards.removeAllViews();
            for (int i = 0; i < rows.size(); i++) cards.addView(card(rows.get(i), i, rows.size()));
            shown = signature;
            hasCards = !rows.isEmpty();
            lastFocused = Math.min(lastFocused, Math.max(0, rows.size() - 1));
        }
        updateVisibility();
    }

    private void updateVisibility() {
        boolean on = hasCards && wanted;
        if (on && view.getVisibility() != View.VISIBLE) {
            view.setAlpha(animations && !reducedMotion ? 0f : 1f);
            view.setVisibility(View.VISIBLE);
            if (animations && !reducedMotion) view.animate().alpha(1f).setDuration(220).start();
        } else if (!on && view.getVisibility() == View.VISIBLE) {
            view.animate().cancel();
            if (animations && !reducedMotion) {
                view.animate().alpha(0f).setDuration(140).withEndAction(() -> {
                    if (!(hasCards && this.wanted)) view.setVisibility(View.GONE);
                }).start();
            } else {
                view.setVisibility(View.GONE);
            }
        }
    }

    private View card(Row r, int index, int count) {
        boolean now = "now".equals(r.kind);
        boolean snow = "snow".equals(r.kind);
        int width = px(now ? NOW_W_DP : snow ? SNOW_W_DP : CARD_W_DP);
        FrameLayout card = new FrameLayout(activity);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(now ? Color.rgb(18, 40, 28) : snow ? Color.rgb(24, 46, 74) : Color.rgb(30, 38, 51));
        bg.setCornerRadius(AppAdapter.cornerRadius(width, density));
        card.setBackground(bg);
        card.setClipToOutline(true);
        card.setFocusable(true);
        card.setClickable(true);

        Bitmap art = r.poster.isEmpty() ? null : posters.get(r.poster);
        LinearLayout words = new LinearLayout(activity);
        words.setOrientation(LinearLayout.VERTICAL);
        if (now) {
            LinearLayout inner = new LinearLayout(activity);
            inner.setGravity(Gravity.CENTER_VERTICAL);
            ImageView cover = new ImageView(activity);
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.setBackgroundColor(Color.rgb(29, 185, 84));
            if (art != null) cover.setImageBitmap(art);
            inner.addView(cover, new LinearLayout.LayoutParams(px(CARD_H_DP), px(CARD_H_DP)));
            words.setPadding(px(12), 0, px(10), 0);
            inner.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
            card.addView(inner, new FrameLayout.LayoutParams(-1, -1));
        } else {
            if (art != null) {
                ImageView poster = new ImageView(activity);
                poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
                poster.setImageBitmap(art);
                card.addView(poster, new FrameLayout.LayoutParams(-1, -1));
                View scrim = new View(activity);
                scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[]{0x00000000, 0x33000000, 0xE6000000}));
                card.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
            }
            words.setPadding(px(8), 0, px(8), px(r.progress >= 0 ? 10 : 6));
            card.addView(words, new FrameLayout.LayoutParams(-1, -2, snow ? Gravity.CENTER_VERTICAL : Gravity.BOTTOM));
        }
        if (!r.label.isEmpty()) words.addView(text(r.label, 8, 0xB3FFFFFF, true));
        words.addView(text(r.title, snow ? 15 : 13, Color.WHITE, true));
        if (!r.subtitle.isEmpty()) words.addView(text(r.subtitle, 10, snow ? 0xFFAADCFF : 0xCCFFFFFF, snow));

        if (r.progress >= 0 && !now) {
            ProgressBar bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(1000);
            bar.setProgress(Math.max(30, (int) (r.progress * 1000)));
            bar.setProgressTintList(ColorStateList.valueOf(Color.rgb(74, 144, 217)));
            bar.setProgressBackgroundTintList(ColorStateList.valueOf(0x55FFFFFF));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, px(3), Gravity.BOTTOM);
            lp.setMargins(px(8), 0, px(8), px(5));
            card.addView(bar, lp);
        }

        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(AppAdapter.cornerRadius(width, density));
        ring.setStroke(px(2), Color.WHITE);
        card.setOnFocusChangeListener((v, focused) -> {
            v.setForeground(focused ? ring : null);
            if (focused) {
                lastFocused = index;
                view.requestChildRectangleOnScreen(cards, new android.graphics.Rect(
                        v.getLeft() - px(GAP_DP), 0, v.getRight() + px(GAP_DP), v.getHeight()), !animations);
                host.glanceFocused();
            }
            float s = focused && animations ? 1.10f : 1f;
            v.animate().cancel();
            if (!animations) {
                v.setScaleX(1f);
                v.setScaleY(1f);
            } else {
                v.animate().scaleX(s).scaleY(s).setDuration(reducedMotion ? Motion.REDUCED_MS : focused ? 220 : 160)
                        .setInterpolator(new DecelerateInterpolator(1.5f)).start();
            }
        });
        card.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    host.glanceUp();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    host.glanceDown();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    return index == 0;            // the row's ends: focus never escapes sideways
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    return index == count - 1;
                default:
                    return false;
            }
        });
        card.setOnClickListener(v -> open(r));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width, px(CARD_H_DP));
        lp.setMargins(0, 0, index == count - 1 ? 0 : px(GAP_DP), 0);
        card.setLayoutParams(lp);
        return card;
    }

    /** Opens a card through the add-on, which keeps its deep link to the app that published it. */
    private void open(Row r) {
        Intent i = new Intent().setClassName(PACKAGE, PACKAGE + ".OpenActivity")
                .putExtra("kind", r.kind).putExtra("pkg", r.pkg).putExtra("open", r.open)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            activity.startActivity(i);
        } catch (RuntimeException e) {
            // The add-on was removed since the row was read: nothing to open.
        }
    }

    private static String signature(List<Row> rows) {
        StringBuilder s = new StringBuilder();
        for (Row r : rows) {
            s.append(r.kind).append('|').append(r.pkg).append('|').append(r.title).append('|').append(r.subtitle)
                    .append('|').append(r.poster).append('|').append(Math.round(r.progress * 50)).append('\n');
        }
        return s.toString();
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(activity);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private static String text(Cursor c, String column) {
        int i = c.getColumnIndex(column);
        return i < 0 || c.isNull(i) ? "" : c.getString(i);
    }

    private int px(float dp) {
        return Math.round(dp * density);
    }
}
