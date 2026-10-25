package com.teddy.glance;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Snow at the New England Ikon Pass mountains from Open-Meteo (no key): daily snowfall in inches at each summit's
 * elevation, yesterday plus the next days. One request for all of them.
 */
final class Snow {
    static final long REFRESH_MS = 60 * 60 * 1000L;

    /** Name, summit latitude, longitude and elevation in metres. Approximate summits; edit to change the list. */
    static final String[] NAMES = {"Killington", "Sugarbush", "Stratton", "Sunday River", "Sugarloaf", "Loon"};
    private static final double[][] SUMMITS = {
            {43.6045, -72.8201, 1288}, {44.1359, -72.9030, 1245}, {43.1134, -72.9081, 1180},
            {44.4734, -70.8565, 958}, {45.0314, -70.3131, 1291}, {44.0364, -71.6214, 930},
    };

    static final class Resort {
        final String name;
        final double last24;
        final double next72;
        /** Today and the following days, in inches. */
        final double[] days;
        final String[] dates;
        final int highF;

        Resort(String name, double last24, double next72, double[] days, String[] dates, int highF) {
            this.name = name;
            this.last24 = last24;
            this.next72 = next72;
            this.days = days;
            this.dates = dates;
            this.highF = highF;
        }
    }

    private Snow() {
    }

    static String url() {
        StringBuilder lat = new StringBuilder(), lon = new StringBuilder(), ele = new StringBuilder();
        for (double[] s : SUMMITS) {
            if (lat.length() > 0) {
                lat.append(',');
                lon.append(',');
                ele.append(',');
            }
            lat.append(s[0]);
            lon.append(s[1]);
            ele.append((int) s[2]);
        }
        return "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon + "&elevation=" + ele
                + "&daily=snowfall_sum,temperature_2m_max&past_days=1&forecast_days=6"
                + "&timezone=America%2FNew_York&precipitation_unit=inch&temperature_unit=fahrenheit";
    }

    static String fetch() throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url()).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        try (InputStream in = conn.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (conn.getResponseCode() != 200) throw new IOException("Open-Meteo " + conn.getResponseCode());
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            conn.disconnect();
        }
    }

    /** Parses the multi-location reply (a JSON array, one object per summit, in request order). */
    static List<Resort> parse(String json) throws JSONException {
        JSONArray all = new JSONArray(json);
        List<Resort> out = new ArrayList<>();
        for (int r = 0; r < all.length() && r < NAMES.length; r++) {
            JSONObject daily = all.getJSONObject(r).getJSONObject("daily");
            JSONArray snow = daily.getJSONArray("snowfall_sum");
            JSONArray time = daily.getJSONArray("time");
            JSONArray high = daily.getJSONArray("temperature_2m_max");
            int n = snow.length() - 1;   // index 0 is yesterday
            double[] days = new double[n];
            String[] dates = new String[n];
            double next72 = 0;
            for (int d = 0; d < n; d++) {
                days[d] = snow.optDouble(d + 1, 0);
                dates[d] = time.getString(d + 1);
                if (d < 3) next72 += days[d];
            }
            out.add(new Resort(NAMES[r], snow.optDouble(0, 0), next72, days, dates,
                    (int) Math.round(high.optDouble(1, 0))));
        }
        return out;
    }

    /** "8.5″", or "–" for a dusting under a tenth of an inch. */
    static String inches(double v) {
        return v < 0.1 ? "–" : (v < 10 ? String.format(java.util.Locale.US, "%.1f″", v) : Math.round(v) + "″");
    }
}
