package com.teddy.streamtotv;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The tiny HTTP server the laptop talks to, on the home network, only while the app's screen is open
 * (started in onStart, closed in onStop). Every request carries the PIN shown on the TV.
 *
 *   GET  /status                       {"app":"stream-to-tv","state":...}
 *   POST /play   {"pin","url","headers":{...},"title"}   start a stream
 *   POST /token  {"pin","url"}         the same playlist with a renewed token; playback keeps going
 *   POST /stop   {"pin"}
 *
 * CORS is open (any origin may ask) because the PIN is what guards it, and the browser add-on may call from a
 * page context. Bodies are capped at 64 KB.
 */
final class ControlServer {
    static final int PORT = 8642;
    private static final String TAG = "StreamToTV";
    private static final int MAX_BODY = 64 * 1024;

    interface Handler {
        /** Returns null when accepted, or a reason. Called on the server thread. */
        String play(String url, Map<String, String> headers, String title);
        String token(String url);
        void stop();
        JSONObject status();
    }

    private final String pin;
    private final Handler handler;
    private volatile ServerSocket socket;

    ControlServer(String pin, Handler handler) {
        this.pin = pin;
        this.handler = handler;
    }

    void start() {
        Thread t = new Thread(this::serve, "stream-to-tv-server");
        t.setDaemon(true);
        t.start();
    }

    void close() {
        ServerSocket s = socket;
        socket = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void serve() {
        try (ServerSocket s = new ServerSocket()) {
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(PORT));
            socket = s;
            while (socket == s) {
                try (Socket c = s.accept()) {
                    c.setSoTimeout(5000);
                    handle(c);
                } catch (IOException | RuntimeException e) {
                    // One bad request must not take the server down with it.
                    if (socket == s) Log.w(TAG, "request failed: " + e);
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "server stopped: " + e);
        }
    }

    private void handle(Socket c) throws IOException {
        InputStream in = new BufferedInputStream(c.getInputStream());
        String requestLine = readLine(in);
        if (requestLine == null) return;
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) {
            respond(c, 400, error("bad request"));
            return;
        }
        String method = parts[0].toUpperCase(Locale.ROOT);
        String path = parts[1];
        int contentLength = 0;
        for (String line; (line = readLine(in)) != null && !line.isEmpty(); ) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                try {
                    contentLength = Integer.parseInt(line.substring(colon + 1).trim());
                } catch (NumberFormatException e) {
                    respond(c, 400, error("bad length"));
                    return;
                }
            }
        }
        if (contentLength < 0 || contentLength > MAX_BODY) {
            respond(c, 413, error("too large"));
            return;
        }
        // InputStream.readNBytes is API 33; this TV runs Android 11.
        byte[] body = new byte[contentLength];
        for (int read = 0, n; read < contentLength; read += n) {
            n = in.read(body, read, contentLength - read);
            if (n < 0) throw new IOException("body cut short");
        }

        if (method.equals("OPTIONS")) {
            respond(c, 204, null);
            return;
        }
        if (method.equals("GET") && path.equals("/status")) {
            respond(c, 200, handler.status());
            return;
        }
        if (!method.equals("POST")) {
            respond(c, 404, error("not found"));
            return;
        }
        JSONObject json;
        try {
            json = new JSONObject(new String(body, StandardCharsets.UTF_8));
        } catch (JSONException e) {
            respond(c, 400, error("body is not JSON"));
            return;
        }
        if (!pin.equals(json.optString("pin"))) {
            respond(c, 403, error("wrong PIN"));
            return;
        }
        String problem;
        switch (path) {
            case "/play":
                Map<String, String> headers = new LinkedHashMap<>();
                JSONObject h = json.optJSONObject("headers");
                if (h != null) {
                    for (java.util.Iterator<String> it = h.keys(); it.hasNext(); ) {
                        String k = it.next();
                        headers.put(k, h.optString(k));
                    }
                }
                problem = handler.play(json.optString("url"), headers, json.optString("title"));
                break;
            case "/token":
                problem = handler.token(json.optString("url"));
                break;
            case "/stop":
                handler.stop();
                problem = null;
                break;
            default:
                respond(c, 404, error("not found"));
                return;
        }
        if (problem == null) {
            respond(c, 200, handler.status());
        } else {
            respond(c, 400, error(problem));
        }
    }

    private static JSONObject error(String message) {
        JSONObject o = new JSONObject();
        try {
            o.put("error", message);
        } catch (JSONException ignored) {
        }
        return o;
    }

    private static void respond(Socket c, int code, JSONObject body) throws IOException {
        byte[] bytes = body == null ? new byte[0] : body.toString().getBytes(StandardCharsets.UTF_8);
        String reason = code == 200 ? "OK" : code == 204 ? "No Content" : code == 403 ? "Forbidden" : "Error";
        String head = "HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
                + "Access-Control-Allow-Headers: Content-Type\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream out = c.getOutputStream();
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.flush();
    }

    /** One CRLF- or LF-terminated line, or null at end of stream. Lines are capped at 8 KB. */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int ch;
        while ((ch = in.read()) != -1) {
            if (ch == '\n') break;
            if (ch != '\r') b.write(ch);
            if (b.size() > 8192) throw new IOException("line too long");
        }
        if (ch == -1 && b.size() == 0) return null;
        return b.toString(StandardCharsets.US_ASCII.name());
    }
}
