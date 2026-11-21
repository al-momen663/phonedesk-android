package com.momen.phonedesk;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;
import java.util.Locale;

/** Remote-relay settings: the relay base URL, this phone's device id, on/off. */
final class Config {
    private Config() { }

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("phonedesk", Context.MODE_PRIVATE);
    }

    static String relayBase(Context c) { return p(c).getString("relayBase", "").trim(); }
    static void setRelayBase(Context c, String v) { p(c).edit().putString("relayBase", v == null ? "" : v.trim()).apply(); }

    static String relaySecret(Context c) { return p(c).getString("relaySecret", ""); }
    static void setRelaySecret(Context c, String v) { p(c).edit().putString("relaySecret", v == null ? "" : v).apply(); }

    static boolean remoteOn(Context c) { return p(c).getBoolean("remoteOn", false); }
    static void setRemoteOn(Context c, boolean v) { p(c).edit().putBoolean("remoteOn", v).apply(); }

    static synchronized String deviceId(Context c) {
        String d = p(c).getString("deviceId", null);
        if (d == null) {
            byte[] r = new byte[9];
            new SecureRandom().nextBytes(r);
            StringBuilder sb = new StringBuilder();
            for (byte x : r) sb.append(String.format(Locale.US, "%02x", x));
            d = sb.toString();
            p(c).edit().putString("deviceId", d).apply();
        }
        return d;
    }

    /** Full browser URL to open from another network, or "" if no relay set. */
    static String browserUrl(Context c) {
        String base = relayBase(c);
        if (base.isEmpty()) return "";
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/d/" + deviceId(c) + "/";
    }
}
