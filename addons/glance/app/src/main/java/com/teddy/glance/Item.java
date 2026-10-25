package com.teddy.glance;

/** One card in a row: a show to resume, a pick from an app, or what is playing. */
final class Item {
    final String pkg;
    final String title;
    final String subtitle;
    final String poster;
    final String intentUri;
    /** 0..1 of the way through, or -1 when the app did not say. */
    final float progress;
    final long engaged;

    Item(String pkg, String title, String subtitle, String poster, String intentUri, float progress, long engaged) {
        this.pkg = pkg;
        this.title = title;
        this.subtitle = subtitle;
        this.poster = poster;
        this.intentUri = intentUri;
        this.progress = progress;
        this.engaged = engaged;
    }
}
