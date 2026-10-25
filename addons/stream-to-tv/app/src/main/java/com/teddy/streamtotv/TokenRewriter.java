package com.teddy.streamtotv;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Keeps a live stream going across token renewals. Stream sites put a short-lived token in the query of the
 * playlist URL and of every segment URL the playlist lists (?token=..., ?wmsAuthSign=..., ?e=...&st=...). When
 * the page in the laptop's browser fetches the playlist with a new token, the laptop sends that URL here, and
 * every later request to the same host gets the new values for the query parameters it already carries.
 * Parameters the request does not have are never added, and other hosts are left alone.
 *
 * Plain Java on strings, so the JVM tests can run it without Android.
 */
final class TokenRewriter {
    private String host;
    private Map<String, String> latest = new LinkedHashMap<>();
    private int generation;

    /** Starts over with the URL the laptop sent for a new stream. */
    synchronized void reset(String url) {
        host = hostOf(url);
        latest = params(url);
        generation = 0;
    }

    /**
     * Takes the parameters of a renewed playlist URL. Returns false (and changes nothing) for another host or a
     * URL with no query, or when the values are the ones already held.
     */
    synchronized boolean update(String url) {
        String h = hostOf(url);
        Map<String, String> p = params(url);
        if (h == null || !h.equals(host) || p.isEmpty() || p.equals(latest)) return false;
        latest = p;
        generation++;
        return true;
    }

    /** How many renewals have arrived since the stream started. */
    synchronized int generation() {
        return generation;
    }

    /** The URL with each parameter it already carries set to the newest value, for requests to the stream's host. */
    synchronized String rewrite(String url) {
        if (host == null || latest.isEmpty() || !host.equals(hostOf(url))) return url;
        int q = url.indexOf('?');
        if (q < 0) return url;
        int hash = url.indexOf('#', q);
        String query = hash < 0 ? url.substring(q + 1) : url.substring(q + 1, hash);
        StringBuilder out = new StringBuilder(url.length()).append(url, 0, q + 1);
        boolean changed = false;
        String[] pairs = query.split("&", -1);
        for (int i = 0; i < pairs.length; i++) {
            if (i > 0) out.append('&');
            String pair = pairs[i];
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = latest.get(key);
            if (eq >= 0 && value != null && !value.equals(pair.substring(eq + 1))) {
                out.append(key).append('=').append(value);
                changed = true;
            } else {
                out.append(pair);
            }
        }
        if (!changed) return url;
        if (hash >= 0) out.append(url.substring(hash));
        return out.toString();
    }

    /** Lower-case host of an http(s) URL, or null. */
    static String hostOf(String url) {
        if (url == null) return null;
        int scheme = url.indexOf("://");
        if (scheme < 0) return null;
        int start = scheme + 3;
        int end = start;
        while (end < url.length() && "/?#".indexOf(url.charAt(end)) < 0) end++;
        String authority = url.substring(start, end);
        int at = authority.lastIndexOf('@');
        if (at >= 0) authority = authority.substring(at + 1);
        return authority.isEmpty() ? null : authority.toLowerCase(java.util.Locale.ROOT);
    }

    /** The query's key=value pairs, raw (still percent-encoded), in order. */
    static Map<String, String> params(String url) {
        Map<String, String> p = new LinkedHashMap<>();
        if (url == null) return p;
        int q = url.indexOf('?');
        if (q < 0) return p;
        int hash = url.indexOf('#', q);
        String query = hash < 0 ? url.substring(q + 1) : url.substring(q + 1, hash);
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) p.put(pair.substring(0, eq), pair.substring(eq + 1));
        }
        return p;
    }
}
