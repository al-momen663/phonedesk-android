package com.momen.phonedesk;

import android.content.Context;
import android.os.BatteryManager;
import android.os.Build;
import android.os.StatFs;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import org.json.JSONObject;

final class Info {
    private Info() { }

    static JSONObject get(Context c) throws Exception {
        JSONObject o = new JSONObject();
        o.put("model", Build.MANUFACTURER + " " + Build.MODEL);
        o.put("android", Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")");
        BatteryManager bm = (BatteryManager) c.getSystemService(Context.BATTERY_SERVICE);
        o.put("battery", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY));
        o.put("charging", bm.isCharging());
        try {
            StatFs st = new StatFs(Files.home());
            o.put("storageFree", st.getAvailableBytes());
            o.put("storageTotal", st.getTotalBytes());
        } catch (Exception ignored) { }
        DisplayMetrics m = new DisplayMetrics();
        ((WindowManager) c.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(m);
        o.put("screen", m.widthPixels + "x" + m.heightPixels);
        o.put("uptimeMin", SystemClock.elapsedRealtime() / 60000);
        o.put("touch", ControlService.instance != null);
        o.put("files", Files.allowed(c));
        o.put("quality", CaptureService.quality);
        o.put("res", CaptureService.maxShortSide);
        return o;
    }
}
