package com.teddy.glance;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Glance page, full screen: what is playing, Continue watching from the apps' Watch Next rows, snow at the
 * Ikon mountains and For you from the apps' preview rows. Everything is read when the page comes on screen
 * (snow at most hourly) and nothing runs while it is not.
 */
public final class GlanceActivity extends Activity {
    private static final int BG = Color.rgb(12, 16, 24);
    private static final int CARD = Color.rgb(30, 38, 51);
    private static final int ACCENT = Color.rgb(74, 144, 217);
    private static final int SNOW = Color.rgb(170, 220, 255);

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Images images;
    private SharedPreferences prefs;
    private float dp;

    private LinearLayout nowRow;
    private View nowSection;
    private LinearLayout continueRow;
    private LinearLayout snowRow;
    private LinearLayout forYouRow;

    /** Set by the first remote key, so a late-loading row never steals focus. */
    private boolean moved;
    private MediaSessionManager sessions;
    private MediaController playing;
    private final MediaController.Callback playingChanged = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            showNowPlaying();
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            showNowPlaying();
        }
    };
    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged = list -> pickSession(list);

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        dp = getResources().getDisplayMetrics().density;
        images = new Images();
        prefs = getSharedPreferences("glance", MODE_PRIVATE);
        sessions = getSystemService(MediaSessionManager.class);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(px(40), px(20), 0, px(28));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, px(40), 0);
        TextView title = text(getString(R.string.app_name), 24, Color.WHITE, true);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("EEE h:mm");
        clock.setFormat24Hour("EEE H:mm");
        clock.setTextSize(18);
        clock.setTextColor(0xCCFFFFFF);
        header.addView(clock);
        column.addView(header);

        nowRow = new LinearLayout(this);
        nowSection = section(column, getString(R.string.now_playing), nowRow);
        nowSection.setVisibility(View.GONE);
        continueRow = new LinearLayout(this);
        section(column, getString(R.string.continue_watching), continueRow);
        snowRow = new LinearLayout(this);
        section(column, getString(R.string.snow_title), snowRow);
        forYouRow = new LinearLayout(this);
        section(column, getString(R.string.for_you), forYouRow);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        scroll.setFocusable(false);   // focus belongs on the cards
        scroll.addView(column);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        moved = false;
        refreshPrograms();
        refreshSnow();
        try {
            sessions.addOnActiveSessionsChangedListener(sessionsChanged, NowPlaying.listener(this), main);
            pickSession(NowPlaying.sessions(this));
        } catch (SecurityException e) {
            pickSession(null);   // notification access not granted: no Now playing
        }
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        moved = true;
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && !moved) firstCard();
    }

    @Override
    protected void onPause() {
        super.onPause();
        sessions.removeOnActiveSessionsChangedListener(sessionsChanged);
        if (playing != null) playing.unregisterCallback(playingChanged);
        playing = null;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
        images.shutdown();
    }

    // Continue watching and For you

    private void refreshPrograms() {
        io.execute(() -> {
            List<Item> resume = Programs.continueWatching(this);
            List<Item> picks = Programs.forYou(this);
            main.post(() -> {
                fill(continueRow, resume, getString(R.string.nothing_to_resume));
                fill(forYouRow, picks, getString(R.string.nothing_for_you));
                if (!moved) continueRow.post(this::firstCard);   // after layout, unless the remote already moved
            });
        });
    }

    private void fill(LinearLayout row, List<Item> items, String empty) {
        row.removeAllViews();
        if (items.isEmpty()) {
            row.addView(text(empty, 16, 0x99FFFFFF, false));
            return;
        }
        for (Item it : items) row.addView(posterCard(it));
    }

    private View posterCard(Item it) {
        FrameLayout card = card(224, 126);
        ImageView art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(art, new FrameLayout.LayoutParams(-1, -1));
        if (!it.poster.isEmpty()) images.load(art, it.poster);

        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0x22000000, 0xE6000000}));
        card.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(px(10), 0, px(10), px(it.progress >= 0 ? 12 : 8));
        words.addView(text(Programs.appName(this, it.pkg).toUpperCase(Locale.US), 9, 0xB3FFFFFF, true));
        words.addView(text(it.title, 14, Color.WHITE, true));
        if (!it.subtitle.isEmpty()) words.addView(text(it.subtitle, 11, 0xCCFFFFFF, false));
        card.addView(words, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        if (it.progress >= 0) {
            ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(1000);
            bar.setProgress(Math.max(30, (int) (it.progress * 1000)));
            bar.setProgressTintList(ColorStateList.valueOf(ACCENT));
            bar.setProgressBackgroundTintList(ColorStateList.valueOf(0x55FFFFFF));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, px(4), Gravity.BOTTOM);
            lp.setMargins(px(10), 0, px(10), px(5));
            card.addView(bar, lp);
        }
        card.setOnClickListener(v -> launch(Programs.open(this, it.pkg, it.intentUri)));
        return card;
    }

    /** Focus the first card on the page, so the highlight shows from the start. */
    private void firstCard() {
        for (LinearLayout row : new LinearLayout[]{nowRow, continueRow, snowRow, forYouRow}) {
            if (row.getChildCount() > 0 && row.getChildAt(0).isFocusable() && ((View) row.getParent().getParent()).getVisibility() == View.VISIBLE) {
                row.getChildAt(0).requestFocus();
                return;
            }
        }
    }

    // Snow

    private void refreshSnow() {
        String cached = prefs.getString("snow", null);
        if (cached != null) showSnow(cached);
        if (cached != null && System.currentTimeMillis() - prefs.getLong("snow_at", 0) < Snow.REFRESH_MS) return;
        io.execute(() -> {
            try {
                String json = Snow.fetch();
                Snow.parse(json);   // only keep a reply that parses
                prefs.edit().putString("snow", json).putLong("snow_at", System.currentTimeMillis()).apply();
                main.post(() -> showSnow(json));
            } catch (Exception e) {
                if (cached == null) main.post(() -> {
                    snowRow.removeAllViews();
                    snowRow.addView(text(getString(R.string.snow_offline), 16, 0x99FFFFFF, false));
                });
            }
        });
    }

    private void showSnow(String json) {
        List<Snow.Resort> resorts;
        try {
            resorts = Snow.parse(json);
        } catch (Exception e) {
            return;
        }
        resorts.sort((a, b) -> Double.compare(b.next72, a.next72));
        snowRow.removeAllViews();
        for (Snow.Resort r : resorts) snowRow.addView(snowCard(r));
    }

    private View snowCard(Snow.Resort r) {
        FrameLayout card = card(196, 126);
        if (r.next72 >= 6) {
            ((GradientDrawable) card.getBackground()).setColor(Color.rgb(28, 58, 92));   // a storm coming
        }
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(12), px(8), px(12), px(8));
        col.addView(text(r.name, 14, Color.WHITE, true));
        LinearLayout big = new LinearLayout(this);
        big.setGravity(Gravity.BOTTOM);
        big.addView(text(r.next72 >= 0.1 ? Snow.inches(r.next72) : "0″", 24, r.next72 >= 0.1 ? SNOW : 0x80FFFFFF, true));
        TextView label = text("  " + getString(R.string.next_3_days), 11, 0x99FFFFFF, false);
        label.setPadding(0, 0, 0, px(5));
        big.addView(label);
        col.addView(big);

        LinearLayout days = new LinearLayout(this);
        for (int d = 0; d < Math.min(5, r.days.length); d++) {
            LinearLayout day = new LinearLayout(this);
            day.setOrientation(LinearLayout.VERTICAL);
            day.setGravity(Gravity.CENTER_HORIZONTAL);
            day.addView(text(dayName(r.dates[d], d), 9, 0x99FFFFFF, false));
            day.addView(text(Snow.inches(r.days[d]), 11, r.days[d] >= 0.1 ? SNOW : 0x66FFFFFF, true));
            days.addView(day, new LinearLayout.LayoutParams(0, -2, 1));
        }
        col.addView(days);
        TextView footer = text(getString(R.string.snow_footer, r.highF, Snow.inches(r.last24)), 9, 0x80FFFFFF, false);
        footer.setPadding(0, px(6), 0, 0);
        col.addView(footer);
        card.addView(col, new FrameLayout.LayoutParams(-1, -1));
        card.setOnClickListener(v -> { });
        return card;
    }

    private static String dayName(String date, int index) {
        if (index == 0) return "Today";
        try {
            return LocalDate.parse(date).getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.US);
        } catch (RuntimeException e) {
            return date;
        }
    }

    // Now playing

    /** Follows the session NowPlaying picks, or none. */
    private void pickSession(List<MediaController> list) {
        MediaController next = NowPlaying.pick(list);
        if (playing != null) playing.unregisterCallback(playingChanged);
        playing = next;
        if (playing != null) playing.registerCallback(playingChanged, main);
        showNowPlaying();
    }

    private void showNowPlaying() {
        nowRow.removeAllViews();
        MediaMetadata md = playing == null ? null : playing.getMetadata();
        if (md == null || TextUtils.isEmpty(md.getString(MediaMetadata.METADATA_KEY_TITLE))) {
            nowSection.setVisibility(View.GONE);
            return;
        }
        nowSection.setVisibility(View.VISIBLE);
        MediaController mc = playing;
        FrameLayout card = card(440, 126);
        LinearLayout inner = new LinearLayout(this);
        inner.setGravity(Gravity.CENTER_VERTICAL);

        ImageView art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setBackgroundColor(Color.rgb(29, 185, 84));
        Bitmap bmp = NowPlaying.art(md);
        if (bmp != null) {
            art.setImageBitmap(bmp);
        } else {
            String uri = NowPlaying.artUri(md);
            if (uri != null && uri.startsWith("http")) images.load(art, uri);
        }
        inner.addView(art, new LinearLayout.LayoutParams(px(126), px(126)));

        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(px(18), 0, px(18), 0);
        boolean paused = NowPlaying.paused(mc);
        String app = Programs.appName(this, mc.getPackageName()).toUpperCase(Locale.US);
        words.addView(text(paused ? app + " · " + getString(R.string.paused) : app, 9, 0xB3FFFFFF, true));
        words.addView(text(md.getString(MediaMetadata.METADATA_KEY_TITLE), 18, Color.WHITE, true));
        String artist = NowPlaying.artist(md);
        if (artist != null) words.addView(text(artist, 14, 0xCCFFFFFF, false));
        inner.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(inner, new FrameLayout.LayoutParams(-1, -1));

        card.setOnClickListener(v -> NowPlaying.open(this, mc));
        nowRow.addView(card);
    }

    // Views

    private View section(LinearLayout column, String title, LinearLayout row) {
        LinearLayout s = new LinearLayout(this);
        s.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(title, 15, 0xE6FFFFFF, true);
        t.setPadding(0, px(14), 0, px(4));
        s.addView(t);
        HorizontalScrollView h = new HorizontalScrollView(this);
        h.setHorizontalScrollBarEnabled(false);
        h.setFocusable(false);
        h.setClipToPadding(false);
        h.setClipChildren(false);
        h.setPadding(px(6), px(8), px(40), px(8));
        row.setClipChildren(false);
        h.addView(row);
        s.addView(h);
        column.addView(s);
        return s;
    }

    private FrameLayout card(int widthDp, int heightDp) {
        FrameLayout card = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(px(10));
        card.setBackground(bg);
        card.setClipToOutline(true);
        card.setFocusable(true);
        card.setClickable(true);
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(px(10));
        ring.setStroke(px(3), Color.WHITE);
        card.setOnFocusChangeListener((v, has) -> {
            v.setForeground(has ? ring : null);
            v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).translationZ(has ? px(8) : 0)
                    .setDuration(140).start();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(widthDp), px(heightDp));
        lp.setMargins(0, 0, px(14), 0);
        card.setLayoutParams(lp);
        return card;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private void launch(Intent i) {
        if (i == null) return;
        try {
            startActivity(i);
        } catch (RuntimeException e) {
            // the app refused its own deep link: open it at its start screen instead
            Intent app = getPackageManager().getLeanbackLaunchIntentForPackage(String.valueOf(i.getPackage()));
            if (app != null) startActivity(app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    private int px(float v) {
        return Math.round(v * dp);
    }
}
