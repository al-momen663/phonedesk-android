package com.momen.phonedesk;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;
import java.util.Locale;

final class Pin {
    private Pin() { }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("phonedesk", Context.MODE_PRIVATE);
    }

    static synchronized String get(Context c) {
        String p = prefs(c).getString("pin", null);
        return p != null ? p : regenerate(c);
    }

    static synchronized String regenerate(Context c) {
        String p = String.format(Locale.US, "%06d", new SecureRandom().nextInt(1_000_000));
        prefs(c).edit().putString("pin", p)
                .putString("token", newToken())
                .putLong("pinVersion", System.nanoTime()).apply();
        return p;
    }

    /** Long-lived session token, persisted so a "remember me" browser stays
     *  logged in across reconnects and app restarts. Rotated only on PIN change. */
    static synchronized String token(Context c) {
        String t = prefs(c).getString("token", null);
        if (t == null) { t = newToken(); prefs(c).edit().putString("token", t).apply(); }
        return t;
    }

    private static String newToken() {
        byte[] r = new byte[24];
        new SecureRandom().nextBytes(r);
        StringBuilder sb = new StringBuilder();
        for (byte x : r) sb.append(String.format(Locale.US, "%02x", x));
        return sb.toString();
    }

    /** Changes whenever the PIN changes, so old browser sessions are logged out. */
    static long version(Context c) {
        get(c);
        return prefs(c).getLong("pinVersion", 0);
    }
}
