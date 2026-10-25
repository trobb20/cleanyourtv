package com.teddy.glance;

import android.app.Activity;
import android.content.Intent;
import android.media.session.MediaController;
import android.os.Bundle;

/**
 * Opens a card from the launcher's Glance row, then closes with nothing drawn. Only holders of READ_ROWS may start
 * it; the deep link is kept to the app that published it (Programs.open).
 */
public final class OpenActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent in = getIntent();
        String kind = String.valueOf(in.getStringExtra("kind"));
        String pkg = in.getStringExtra("pkg");
        String open = in.getStringExtra("open");
        try {
            if ("now".equals(kind)) {
                MediaController mc = NowPlaying.current(this);
                if (mc != null) NowPlaying.open(this, mc);
            } else if ("snow".equals(kind)) {
                startActivity(new Intent(this, GlanceActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } else if (pkg != null) {
                Intent i = Programs.open(this, pkg, open == null ? "" : open);
                if (i != null) startActivity(i);
            }
        } catch (RuntimeException ignored) {
            // the app refused its link or went away; Home stays as it was
        }
        finish();
    }
}
