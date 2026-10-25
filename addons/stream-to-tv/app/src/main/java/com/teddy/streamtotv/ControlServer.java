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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * The tiny HTTP server the laptop talks to, on the home network, only while the app's screen is open
 * (started in onStart, closed in onStop). Every POST carries the PIN shown on the TV.
 *
 *   GET  /status                       {"app","state","renewals","stalls","errors"}; title and host with X-PIN
 *   POST /play   {"pin","url","headers":{...},"title","bufferSeconds"}   start a stream
 *   POST /token  {"pin","url"}         the same playlist with a renewed token; playback keeps going
 *   POST /stop   {"pin"}
 *
 * Only the browser add-on should get in, never a web page open on some laptop on the network: there are no CORS
 * headers, a request with a web page's Origin is refused, a POST must be application/json (which a page cannot
 * send cross-site without a preflight), and five wrong PINs in a row lock the PIN out for a minute, doubling up
 * to an hour. Bodies are capped at 64 KB, and each connection is handled on a small pool so one slow client
 * cannot hold up a token renewal.
 */
final class ControlServer {
    static final int PORT = 8642;
    private static final String TAG = "StreamToTV";
    private static final int MAX_BODY = 64 * 1024;
    private static final int FREE_TRIES = 5;
    private static final long FIRST_LOCK_MS = 60_000, MAX_LOCK_MS = 3_600_000;

    interface Handler {
        /** Returns null when accepted, or a reason. Called on the server thread. */
        /** {@code bufferSeconds}: how far behind live to play; 0 for as close as the stream allows, -1 when not given. */
        String play(String url, Map<String, String> headers, String title, int bufferSeconds);
        String token(String url);
        void stop();
        /** The playing title and host are left out unless {@code full}. */
        JSONObject status(boolean full);
        /** The port could not be opened; called on the server thread. */
        void serverFailed(String reason);
    }

    private final String pin;
    private final Handler handler;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private ServerSocket socket;
    private boolean closed;
    private int wrongPins;
    private long lockMs = FIRST_LOCK_MS, lockedUntil;

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
        ServerSocket s;
        synchronized (this) {
            closed = true;
            s = socket;
            socket = null;
        }
        pool.shutdownNow();
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void serve() {
        ServerSocket s = bind();
        if (s == null) return;
        try {
            while (true) {
                Socket c = s.accept();
                try {
                    pool.execute(() -> handle(c));
                } catch (RejectedExecutionException e) {
                    c.close();
                }
            }
        } catch (IOException e) {
            if (isOpen()) Log.w(TAG, "server stopped: " + e);
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * The listening socket, or null when close() came first or the port stays taken. The screen closing and
     * opening again quickly can find the old socket not quite released, so binding is retried for a few seconds.
     */
    private ServerSocket bind() {
        IOException last = null;
        for (int attempt = 0; attempt < 10 && isOpen(); attempt++) {
            try {
                ServerSocket s = new ServerSocket();
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(PORT));
                synchronized (this) {
                    if (!closed) {
                        socket = s;
                        return s;
                    }
                }
                s.close();
                return null;
            } catch (IOException e) {
                last = e;
                try {
                    Thread.sleep(500);
                } catch (InterruptedException interrupted) {
                    return null;
                }
            }
        }
        if (isOpen()) {
            Log.w(TAG, "could not open port " + PORT + ": " + last);
            handler.serverFailed("Port " + PORT + " is taken: " + last.getMessage());
        }
        return null;
    }

    private synchronized boolean isOpen() {
        return !closed;
    }

    /**
     * Whether {@code given} is the PIN, counting wrong guesses: after FREE_TRIES in a row every guess is refused
     * until the lockout ends, and each lockout is twice as long as the one before.
     */
    private synchronized PinResult checkPin(String given) {
        long now = System.currentTimeMillis();
        if (now < lockedUntil) return PinResult.LOCKED;
        if (pin.equals(given)) {
            wrongPins = 0;
            lockMs = FIRST_LOCK_MS;
            return PinResult.OK;
        }
        if (++wrongPins >= FREE_TRIES) {
            wrongPins = 0;
            lockedUntil = now + lockMs;
            Log.w(TAG, "too many wrong PINs; locked for " + lockMs / 1000 + " s");
            lockMs = Math.min(lockMs * 2, MAX_LOCK_MS);
        }
        return PinResult.WRONG;
    }

    private enum PinResult { OK, WRONG, LOCKED }

    private void handle(Socket c) {
        try (Socket conn = c) {
            conn.setSoTimeout(5000);
            serveOne(conn);
        } catch (IOException | RuntimeException e) {
            // One bad request must not take the server down with it.
            if (isOpen()) Log.w(TAG, "request failed: " + e);
        }
    }

    private void serveOne(Socket c) throws IOException {
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
        String origin = null, contentType = "", headerPin = null;
        for (String line; (line = readLine(in)) != null && !line.isEmpty(); ) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            switch (name) {
                case "content-length":
                    try {
                        contentLength = Integer.parseInt(value);
                    } catch (NumberFormatException e) {
                        respond(c, 400, error("bad length"));
                        return;
                    }
                    break;
                case "origin":
                    origin = value;
                    break;
                case "content-type":
                    contentType = value.toLowerCase(Locale.ROOT);
                    break;
                case "x-pin":
                    headerPin = value;
                    break;
                default:
                    break;
            }
        }
        if (origin != null && !origin.startsWith("moz-extension://") && !origin.startsWith("chrome-extension://")) {
            // A web page, or a sandboxed frame ("null"). The add-on's own requests have no Origin or its own.
            respond(c, 403, error("web pages may not control the TV"));
            return;
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

        if (method.equals("GET") && path.equals("/status")) {
            PinResult r = headerPin == null ? PinResult.WRONG : checkPin(headerPin);
            if (r == PinResult.LOCKED) {
                respond(c, 429, error("too many wrong PINs; wait a minute"));
            } else if (headerPin != null && r == PinResult.WRONG) {
                respond(c, 403, error("wrong PIN"));
            } else {
                respond(c, 200, handler.status(r == PinResult.OK));
            }
            return;
        }
        if (!method.equals("POST")) {
            respond(c, 404, error("not found"));
            return;
        }
        if (!contentType.startsWith("application/json")) {
            respond(c, 415, error("send application/json"));
            return;
        }
        JSONObject json;
        try {
            json = new JSONObject(new String(body, StandardCharsets.UTF_8));
        } catch (JSONException e) {
            respond(c, 400, error("body is not JSON"));
            return;
        }
        PinResult r = checkPin(json.optString("pin"));
        if (r != PinResult.OK) {
            respond(c, r == PinResult.LOCKED ? 429 : 403,
                    error(r == PinResult.LOCKED ? "too many wrong PINs; wait a minute" : "wrong PIN"));
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
                problem = handler.play(json.optString("url"), headers, json.optString("title"),
                        json.optInt("bufferSeconds", -1));
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
            respond(c, 200, handler.status(true));
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
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        String reason = code == 200 ? "OK" : code == 403 ? "Forbidden" : code == 429 ? "Too Many Requests" : "Error";
        String head = "HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
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
