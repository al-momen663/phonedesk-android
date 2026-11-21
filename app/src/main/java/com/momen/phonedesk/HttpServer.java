package com.momen.phonedesk;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Tiny dependency-free HTTP server: web UI, MJPEG screen stream, control + file API. */
class HttpServer extends Thread {
    static final int PORT = 8080;

    private final Context ctx;
    private volatile ServerSocket ss;
    private volatile boolean alive = true;
    private int fails;
    private long lockedUntil;

    HttpServer(Context c) {
        super("phonedesk-http");
        ctx = c;
        setDaemon(true);
    }

    @Override
    public void run() {
        try {
            ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(PORT));
            while (alive) {
                Socket s = ss.accept();
                Thread t = new Thread(() -> serve(s), "phonedesk-conn");
                t.setDaemon(true);
                t.start();
            }
        } catch (IOException ignored) { }
    }

    void shutdown() {
        alive = false;
        try { if (ss != null) ss.close(); } catch (IOException ignored) { }
    }

    // ------------------------------------------------------------------ request
    private static final class Req {
        String method, path;
        final Map<String, String> q = new HashMap<>();
        final Map<String, String> h = new HashMap<>();
        InputStream in;
        long len;
        String str(String k) { return q.get(k); }
        float f(String k) { try { return Float.parseFloat(q.get(k)); } catch (Exception e) { return 0f; } }
        long l(String k, long def) { try { return Long.parseLong(q.get(k)); } catch (Exception e) { return def; } }
    }

    private void serve(Socket sock) {
        try (Socket s = sock) {
            s.setSoTimeout(30000);
            s.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = new BufferedOutputStream(s.getOutputStream(), 64 * 1024);
            String line = readLine(in);
            if (line == null || line.isEmpty()) return;
            String[] p = line.split(" ");
            if (p.length < 2) return;
            Req r = new Req();
            r.method = p[0].toUpperCase(Locale.ROOT);
            String target = p[1];
            int qi = target.indexOf('?');
            r.path = qi >= 0 ? target.substring(0, qi) : target;
            if (qi >= 0) parseForm(target.substring(qi + 1), r.q);
            String hl;
            while ((hl = readLine(in)) != null && !hl.isEmpty()) {
                int c = hl.indexOf(':');
                if (c > 0) r.h.put(hl.substring(0, c).trim().toLowerCase(Locale.ROOT), hl.substring(c + 1).trim());
            }
            try { r.len = Long.parseLong(r.h.get("content-length")); } catch (Exception e) { r.len = 0; }
            r.in = in;
            route(r, out);
            out.flush();
        } catch (Exception ignored) { }
    }

    private void route(Req r, OutputStream out) throws Exception {
        if (r.path.equals("/login") && r.method.equals("POST")) { login(r, out); return; }
        if (!authed(r)) {
            if (r.path.equals("/") || r.path.equals("/index.html")) {
                send(out, 200, "text/html; charset=utf-8", asset("login.html"), null);
            } else {
                send(out, 401, "application/json", "{\"ok\":false,\"error\":\"login\"}".getBytes(), null);
            }
            return;
        }
        ControlService c = ControlService.instance;
        switch (r.path) {
            case "/":
            case "/index.html":
                send(out, 200, "text/html; charset=utf-8", asset("index.html"), null); return;
            case "/logout":
                send(out, 303, "text/plain", new byte[0], "Location: /\r\nSet-Cookie: pd=; Path=/; Max-Age=0\r\n"); return;
            case "/stream": stream(out); return;
            case "/frame.jpg": {
                byte[] f = CaptureService.frame;
                if (f == null) send(out, 503, "text/plain", "no frame".getBytes(), null);
                else send(out, 200, "image/jpeg", f, null);
                return;
            }
            case "/api/info": json(out, Info.get(ctx)); return;

            // ---- input
            case "/api/tap":   result(out, c, c != null && c.tap(r.f("x"), r.f("y"))); return;
            case "/api/long":  result(out, c, c != null && c.longPress(r.f("x"), r.f("y"))); return;
            case "/api/swipe": result(out, c, c != null && c.swipe(r.f("x1"), r.f("y1"), r.f("x2"), r.f("y2"), r.l("ms", 200))); return;
            case "/api/key":   result(out, c, c != null && c.key(r.str("k"))); return;
            case "/api/text": {
                String t = new String(readBody(r, 64 * 1024), StandardCharsets.UTF_8);
                msg(out, c == null ? noTouch() : c.type(t)); return;
            }
            case "/api/edit": msg(out, c == null ? noTouch() : c.edit(r.str("op"))); return;
            case "/api/quality": {
                CaptureService.quality = (int) Math.max(20, Math.min(95, r.l("q", 60)));
                CaptureService.maxShortSide = (int) Math.max(360, Math.min(1440, r.l("res", 720)));
                CaptureService cs = CaptureService.self;
                if (cs != null) cs.resize();
                msg(out, null); return;
            }

            // ---- files
            case "/api/ls": json(out, Files.list(r.str("path"))); return;
            case "/api/dl": download(r, out); return;
            case "/api/up": upload(r, out); return;
            case "/api/rm": {
                File f = new File(nz(r.str("path")));
                msg(out, f.exists() && Files.delete(f) ? null : "Delete failed"); return;
            }
            case "/api/mkdir": msg(out, new File(nz(r.str("path"))).mkdirs() ? null : "Could not create folder"); return;
            default:
                send(out, 404, "text/plain", "Not found".getBytes(), null);
        }
    }

    // ------------------------------------------------------------------ auth
    private boolean authed(Req r) {
        String cookie = r.h.get("cookie");
        if (cookie == null) return false;
        String token = Pin.token(ctx);
        for (String part : cookie.split(";")) {
            if (part.trim().equals("pd=" + token)) return true;
        }
        return false;
    }

    private void login(Req r, OutputStream out) throws IOException {
        Map<String, String> form = new HashMap<>();
        parseForm(new String(readBody(r, 1024), StandardCharsets.UTF_8), form);
        long now = System.currentTimeMillis();
        boolean good;
        synchronized (this) {
            if (now < lockedUntil) {
                send(out, 303, "text/plain", new byte[0], "Location: /?locked=1\r\n");
                return;
            }
            good = Pin.get(ctx).equals(nz(form.get("pin")).trim());
            if (good) fails = 0;
            else if (++fails >= 5) { fails = 0; lockedUntil = now + 60_000; }
        }
        if (good) {
            // "remember" -> 30-day cookie that survives app restarts (token is persisted,
            // rotated only when the PIN changes). Otherwise a session cookie.
            String age = "1".equals(nz(form.get("remember"))) ? "; Max-Age=2592000" : "";
            send(out, 303, "text/plain", new byte[0],
                    "Location: /\r\nSet-Cookie: pd=" + Pin.token(ctx) + "; Path=/; HttpOnly; SameSite=Strict" + age + "\r\n");
        } else {
            send(out, 303, "text/plain", new byte[0], "Location: /?bad=1\r\n");
        }
    }

    // ------------------------------------------------------------------ stream
    private void stream(OutputStream out) throws IOException, InterruptedException {
        out.write(("HTTP/1.1 200 OK\r\n" +
                "Content-Type: multipart/x-mixed-replace; boundary=pdframe\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        long last = -1;
        while (CaptureService.running && alive) {
            byte[] f;
            synchronized (CaptureService.FRAME_LOCK) {
                if (CaptureService.frameNo == last) CaptureService.FRAME_LOCK.wait(1000);
                f = CaptureService.frame;
                last = CaptureService.frameNo;
            }
            if (f == null) continue;
            out.write(("--pdframe\r\nContent-Type: image/jpeg\r\nContent-Length: " + f.length + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.write(f);
            out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }
    }

    // ------------------------------------------------------------------ files
    private void download(Req r, OutputStream out) throws IOException {
        File f = new File(nz(r.str("path")));
        if (!f.isFile() || !f.canRead()) { send(out, 404, "text/plain", "File not found".getBytes(), null); return; }
        String ascii = f.getName().replaceAll("[^\\x20-\\x7E]", "_").replace("\"", "_");
        String enc = URLEncoder.encode(f.getName(), "UTF-8").replace("+", "%20");
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: " + f.length() +
                "\r\nContent-Disposition: attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + enc +
                "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        try (InputStream fi = new FileInputStream(f)) { copy(fi, out, Long.MAX_VALUE); }
        out.flush();
    }

    private void upload(Req r, OutputStream out) throws IOException {
        File dir = new File(nz(r.str("dir")));
        String name = new File(nz(r.str("name"))).getName();
        if (name.isEmpty() || !dir.isDirectory()) { msg(out, "Bad target folder"); return; }
        File dst = new File(dir, name);
        try (OutputStream fo = new FileOutputStream(dst)) {
            long n = copy(r.in, fo, r.len);
            if (n != r.len) { msg(out, "Upload interrupted"); return; }
        } catch (IOException e) {
            msg(out, "Cannot write here: " + e.getMessage());
            return;
        }
        msg(out, null);
    }

    // ------------------------------------------------------------------ helpers
    private static String noTouch() {
        return "Remote touch is off. On the phone: PhoneDesk → Enable remote touch (Accessibility).";
    }

    private void result(OutputStream out, ControlService c, boolean ok) throws IOException {
        msg(out, c == null ? noTouch() : ok ? null : "Action not supported on this phone");
    }

    private void msg(OutputStream out, String error) throws IOException {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", error == null);
            if (error != null) o.put("error", error);
            json(out, o);
        } catch (org.json.JSONException e) { throw new IOException(e); }
    }

    private void json(OutputStream out, JSONObject o) throws IOException {
        send(out, 200, "application/json; charset=utf-8", o.toString().getBytes(StandardCharsets.UTF_8), null);
    }

    private static void send(OutputStream out, int code, String type, byte[] body, String extra) throws IOException {
        StringBuilder sb = new StringBuilder("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n")
                .append("Content-Type: ").append(type).append("\r\n")
                .append("Content-Length: ").append(body.length).append("\r\n")
                .append("Cache-Control: no-store\r\nConnection: close\r\n")
                .append("X-Frame-Options: DENY\r\nX-Content-Type-Options: nosniff\r\n");
        if (extra != null) sb.append(extra);
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    private static String reason(int c) {
        switch (c) {
            case 200: return "OK";
            case 303: return "See Other";
            case 401: return "Unauthorized";
            case 404: return "Not Found";
            default: return "Status";
        }
    }

    private byte[] asset(String name) throws IOException {
        try (InputStream in = ctx.getAssets().open(name)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            copy(in, b, Long.MAX_VALUE);
            return b.toByteArray();
        }
    }

    private static byte[] readBody(Req r, int max) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        copy(r.in, b, Math.min(r.len, max));
        return b.toByteArray();
    }

    private static long copy(InputStream in, OutputStream out, long limit) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long total = 0;
        while (total < limit) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, limit - total));
            if (n < 0) break;
            out.write(buf, 0, n);
            total += n;
        }
        return total;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 16384) throw new IOException("line too long");
        }
        if (c == -1 && b.size() == 0) return null;
        return b.toString("UTF-8");
    }

    private static void parseForm(String s, Map<String, String> into) {
        for (String kv : s.split("&")) {
            if (kv.isEmpty()) continue;
            int e = kv.indexOf('=');
            try {
                String k = URLDecoder.decode(e >= 0 ? kv.substring(0, e) : kv, "UTF-8");
                String v = e >= 0 ? URLDecoder.decode(kv.substring(e + 1), "UTF-8") : "";
                into.put(k, v);
            } catch (Exception ignored) { }
        }
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
