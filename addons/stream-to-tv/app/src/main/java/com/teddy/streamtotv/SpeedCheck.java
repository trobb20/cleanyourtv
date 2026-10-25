package com.teddy.streamtotv;

/**
 * Tells whether the TV downloads the stream as fast as it plays it. Each finished segment adds its size, how long
 * it took to download and how long it plays; over the last few segments, downloading taking longer than playing
 * means the buffer drains and the picture pauses every few seconds, whatever the delay behind live.
 *
 * Plain Java, so the JVM tests can run it without Android.
 */
final class SpeedCheck {
    /** Segments looked at: enough that one slow segment is not a verdict. */
    static final int WINDOW = 8;
    /** Slow once downloading takes longer than playing; fine again below 85 %, so it does not flicker. */
    private static final double SLOW = 1.0, FINE = 0.85;

    private final long[] bytes = new long[WINDOW], loadMs = new long[WINDOW], mediaMs = new long[WINDOW];
    private int count, next;
    private boolean slow;

    synchronized void reset() {
        count = 0;
        next = 0;
        slow = false;
    }

    /** Adds a downloaded segment. Returns true when the verdict changed. */
    synchronized boolean add(long segmentBytes, long downloadMs, long playMs) {
        if (segmentBytes <= 0 || downloadMs < 0 || playMs <= 0) return false;
        bytes[next] = segmentBytes;
        loadMs[next] = downloadMs;
        mediaMs[next] = playMs;
        next = (next + 1) % WINDOW;
        if (count < WINDOW) count++;
        if (count < WINDOW) return false;
        double ratio = (double) sum(loadMs) / sum(mediaMs);
        boolean was = slow;
        if (ratio > SLOW) slow = true;
        else if (ratio < FINE) slow = false;
        return slow != was;
    }

    synchronized boolean slow() {
        return slow;
    }

    /** The TV's download speed over the window, in Mbit/s; 0 before the first segment. */
    synchronized double downloadMbps() {
        long ms = sum(loadMs);
        return ms == 0 ? 0 : sum(bytes) * 8.0 / ms / 1000;
    }

    /** The speed the stream needs to play without pausing, in Mbit/s. */
    synchronized double streamMbps() {
        long ms = sum(mediaMs);
        return ms == 0 ? 0 : sum(bytes) * 8.0 / ms / 1000;
    }

    private long sum(long[] a) {
        long s = 0;
        for (int i = 0; i < count; i++) s += a[i];
        return s;
    }
}
