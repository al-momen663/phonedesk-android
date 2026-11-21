package com.momen.phonedesk;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.Arrays;

final class Files {
    private Files() { }

    static boolean allowed(Context c) {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return c.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    static String home() { return Environment.getExternalStorageDirectory().getAbsolutePath(); }

    static JSONObject list(String path) throws Exception {
        File d = new File(path == null || path.isEmpty() ? home() : path);
        JSONObject o = new JSONObject();
        o.put("path", d.getAbsolutePath());
        File parent = d.getParentFile();
        o.put("parent", parent == null ? JSONObject.NULL : parent.getAbsolutePath());
        File[] fs = d.listFiles();
        JSONArray a = new JSONArray();
        if (fs == null) {
            o.put("error", "Cannot read this folder. Tap \"Allow file access\" in the PhoneDesk app.");
        } else {
            Arrays.sort(fs, (x, y) -> {
                if (x.isDirectory() != y.isDirectory()) return x.isDirectory() ? -1 : 1;
                return x.getName().compareToIgnoreCase(y.getName());
            });
            for (File f : fs) {
                JSONObject e = new JSONObject();
                e.put("n", f.getName());
                e.put("d", f.isDirectory());
                e.put("s", f.isDirectory() ? 0 : f.length());
                e.put("m", f.lastModified());
                a.put(e);
            }
        }
        o.put("items", a);
        return o;
    }

    static boolean delete(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) delete(k);
        }
        return f.delete();
    }
}
