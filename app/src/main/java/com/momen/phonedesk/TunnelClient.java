package com.momen.phonedesk;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Connects OUT to the relay over a WebSocket and proxies each tunnelled request
 * to the local PhoneDesk server on 127.0.0.1:PORT. Lets the phone be reached
 * from any network without port-forwarding. Protocol matches relay/server.js.
 */
class TunnelClient {
    static volatile String state = "off";   // shown in the app

    private final String base, deviceId, secret;
    private OkHttpClient client;
    private volatile WebSocket ws;
    private volatile boolean stopped;
    private int backoff = 1;
    private final ConcurrentHashMap<Integer, Local> streams = new ConcurrentHashMap<>();

    TunnelClient(Context ignored, String base, String deviceId, String secret) {
        this.base = base;
        this.deviceId = deviceId;
        this.secret = secret == null ? "" : secret;
    }

    void start() {
        stopped = false;
        client = new OkHttpClient.Builder()
                .pingInterval(20, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(true)
                .build();
        connect();
    }

    void stop() {
        stopped = true;
        state = "off";
        WebSocket w = ws;
        if (w != null) w.close(1000, "bye");
        for (Local l : streams.values()) l.close();
        streams.clear();
    }

    private String wsUrl() {
        String b = base;
        if (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        if (b.startsWith("https://")) b = "wss://" + b.substring(8);
        else if (b.startsWith("http://")) b = "ws://" + b.substring(7);
        else b = "wss://" + b;
        return b + "/agent";
    }

    private void connect() {
        if (stopped) return;
        state = "connecting";
        try {
            ws = client.newWebSocket(new Request.Builder().url(wsUrl()).build(), new Listener());
        } catch (Exception e) {
            state = "error: " + e.getMessage();
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (stopped) return;
        final int delay = Math.min(backoff, 30);
        backoff = Math.min(backoff * 2, 30);
        new Thread(() -> {
            try { Thread.sleep(delay * 1000L); } catch (InterruptedException ignored) { }
            connect();
        }).start();
    }

    private void sendText(JSONObject o) {
        WebSocket w = ws;
        if (w != null) w.send(o.toString());
    }

    private void sendBin(int sid, byte[] data, int off, int len) {
        WebSocket w = ws;
        if (w == null) return;
        byte[] b = new byte[4 + len];
        b[0] = (byte) (sid >>> 24); b[1] = (byte) (sid >>> 16); b[2] = (byte) (sid >>> 8); b[3] = (byte) sid;
        System.arraycopy(data, off, b, 4, len);
        w.send(ByteString.of(b));
    }

    private final class Listener extends WebSocketListener {
        @Override public void onOpen(WebSocket w, Response r) {
            backoff = 1;
            state = "online";
            try {
                JSONObject h = new JSONObject();
                h.put("t", "hello"); h.put("id", deviceId); h.put("secret", secret);
                w.send(h.toString());
            } catch (Exception ignored) { }
        }

        @Override public void onMessage(WebSocket w, String text) {
            try {
                JSONObject m = new JSONObject(text);
                String t = m.optString("t");
                if ("ready".equals(t)) { state = "online"; return; }
                int sid = m.optInt("sid", -1);
                if ("req".equals(t)) {
                    Local l = new Local(sid, m.getString("method"), m.getString("path"),
                            m.optJSONObject("headers"));
                    streams.put(sid, l);
                    l.begin();
                } else if ("reqend".equals(t)) {
                    Local l = streams.get(sid);
                    if (l != null) l.endRequest();
                } else if ("close".equals(t)) {
                    Local l = streams.remove(sid);
                    if (l != null) l.close();
                }
            } catch (Exception ignored) { }
        }

        @Override public void onMessage(WebSocket w, ByteString bytes) {
            if (bytes.size() < 4) return;
            byte[] b = bytes.toByteArray();
            int sid = ((b[0] & 255) << 24) | ((b[1] & 255) << 16) | ((b[2] & 255) << 8) | (b[3] & 255);
            Local l = streams.get(sid);
            if (l != null) l.writeRequestBody(b, 4, b.length - 4);
        }

        @Override public void onClosing(WebSocket w, int code, String reason) {
            state = "offline";
            dropAll();
            scheduleReconnect();
        }

        @Override public void onFailure(WebSocket w, Throwable e, Response r) {
            state = "offline: " + e.getMessage();
            dropAll();
            scheduleReconnect();
        }
    }

    private void dropAll() {
        for (Iterator<Local> it = streams.values().iterator(); it.hasNext(); ) {
            it.next().close();
        }
        streams.clear();
    }

    /** One tunnelled request -> a raw socket to the local PhoneDesk server. */
    private final class Local {
        final int sid;
        final String method, path;
        final JSONObject headers;
        Socket sock;
        OutputStream out;
        volatile boolean closed;

        Local(int sid, String method, String path, JSONObject headers) {
            this.sid = sid; this.method = method; this.path = path; this.headers = headers;
        }

        void begin() {
            try {
                sock = new Socket();
                sock.connect(new InetSocketAddress("127.0.0.1", HttpServer.PORT), 5000);
                out = sock.getOutputStream();
                StringBuilder h = new StringBuilder();
                h.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
                h.append("Host: 127.0.0.1:").append(HttpServer.PORT).append("\r\n");
                if (headers != null) {
                    for (Iterator<String> it = headers.keys(); it.hasNext(); ) {
                        String k = it.next();
                        String lk = k.toLowerCase();
                        if (lk.equals("host") || lk.equals("accept-encoding") || lk.equals("connection")) continue;
                        h.append(k).append(": ").append(headers.optString(k)).append("\r\n");
                    }
                }
                h.append("Accept-Encoding: identity\r\n");
                h.append("Connection: close\r\n\r\n");
                out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
                Thread reader = new Thread(this::readResponse, "pd-tunnel-" + sid);
                reader.setDaemon(true);
                reader.start();
            } catch (Exception e) {
                sendErr(502, "local connect failed");
                streams.remove(sid);
            }
        }

        void writeRequestBody(byte[] b, int off, int len) {
            try { if (out != null) { out.write(b, off, len); out.flush(); } } catch (Exception ignored) { }
        }

        void endRequest() {
            try { if (out != null) out.flush(); } catch (Exception ignored) { }
        }

        private void readResponse() {
            try {
                InputStream in = sock.getInputStream();
                ByteArrayOutputStream head = new ByteArrayOutputStream();
                byte[] buf = new byte[16 * 1024];
                int split = -1;
                // Read until end of response headers (\r\n\r\n).
                while (split < 0) {
                    int n = in.read(buf);
                    if (n < 0) break;
                    head.write(buf, 0, n);
                    split = indexOfCrlfCrlf(head.toByteArray());
                    if (head.size() > 64 * 1024) break;
                }
                byte[] all = head.toByteArray();
                if (split < 0) { sendErr(502, "bad response"); return; }
                String headerText = new String(all, 0, split, StandardCharsets.ISO_8859_1);
                sendHead(headerText);
                int bodyStart = split + 4;
                if (all.length > bodyStart) sendBin(sid, all, bodyStart, all.length - bodyStart);
                // Stream the rest until the server closes the socket.
                int n;
                while (!closed && (n = in.read(buf)) >= 0) {
                    if (n > 0) sendBin(sid, buf, 0, n);
                }
            } catch (Exception ignored) {
            } finally {
                try { JSONObject o = new JSONObject(); o.put("t", "resend"); o.put("sid", sid); sendText(o); } catch (Exception ignored) { }
                close();
                streams.remove(sid);
            }
        }

        private void sendHead(String headerText) {
            try {
                String[] lines = headerText.split("\r\n");
                int status = 200;
                if (lines.length > 0) {
                    String[] p = lines[0].split(" ");
                    if (p.length > 1) try { status = Integer.parseInt(p[1]); } catch (Exception ignored) { }
                }
                JSONObject hdrs = new JSONObject();
                for (int i = 1; i < lines.length; i++) {
                    int c = lines[i].indexOf(':');
                    if (c > 0) hdrs.put(lines[i].substring(0, c).trim(), lines[i].substring(c + 1).trim());
                }
                JSONObject o = new JSONObject();
                o.put("t", "res"); o.put("sid", sid); o.put("status", status); o.put("headers", hdrs);
                sendText(o);
            } catch (Exception ignored) { }
        }

        private void sendErr(int status, String msg) {
            try {
                JSONObject hdrs = new JSONObject().put("Content-Type", "text/plain");
                sendText(new JSONObject().put("t", "res").put("sid", sid).put("status", status).put("headers", hdrs));
                byte[] m = msg.getBytes(StandardCharsets.UTF_8);
                sendBin(sid, m, 0, m.length);
                sendText(new JSONObject().put("t", "resend").put("sid", sid));
            } catch (Exception ignored) { }
        }

        void close() {
            closed = true;
            try { if (sock != null) sock.close(); } catch (Exception ignored) { }
        }
    }

    private static int indexOfCrlfCrlf(byte[] a) {
        for (int i = 0; i + 3 < a.length; i++) {
            if (a[i] == '\r' && a[i + 1] == '\n' && a[i + 2] == '\r' && a[i + 3] == '\n') return i;
        }
        return -1;
    }
}
