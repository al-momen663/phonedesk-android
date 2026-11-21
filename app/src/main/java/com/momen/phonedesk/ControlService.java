package com.momen.phonedesk;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/** Performs the taps / swipes / keys / text that come from the browser. */
public class ControlService extends AccessibilityService {
    static volatile ControlService instance;

    @Override protected void onServiceConnected() { instance = this; }
    @Override public boolean onUnbind(Intent i) { instance = null; return super.onUnbind(i); }
    @Override public void onDestroy() { instance = null; super.onDestroy(); }
    @Override public void onAccessibilityEvent(AccessibilityEvent e) { }
    @Override public void onInterrupt() { }

    private static float clamp(float v) { return Math.max(0f, Math.min(1f, v)); }

    private float[] px(float nx, float ny) {
        DisplayMetrics m = new DisplayMetrics();
        ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(m);
        return new float[]{clamp(nx) * (m.widthPixels - 1), clamp(ny) * (m.heightPixels - 1)};
    }

    /** Coordinates are 0..1 fractions of the screen. */
    boolean swipe(float x1, float y1, float x2, float y2, long ms) {
        float[] a = px(x1, y1), b = px(x2, y2);
        Path p = new Path();
        p.moveTo(a[0], a[1]);
        p.lineTo(b[0], b[1]);
        long d = Math.max(1, Math.min(ms, 10000));
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, d)).build();
        return dispatchGesture(g, null, null);
    }

    boolean tap(float x, float y) { return swipe(x, y, x, y, 40); }

    boolean longPress(float x, float y) { return swipe(x, y, x, y, 700); }

    boolean key(String k) {
        if (k == null) return false;
        switch (k) {
            case "back": return performGlobalAction(GLOBAL_ACTION_BACK);
            case "home": return performGlobalAction(GLOBAL_ACTION_HOME);
            case "recents": return performGlobalAction(GLOBAL_ACTION_RECENTS);
            case "notifications": return performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
            case "quick": return performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS);
            case "power": return performGlobalAction(GLOBAL_ACTION_POWER_DIALOG);
            case "split": return performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN);
            case "lock": return Build.VERSION.SDK_INT >= 28 && performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
            case "screenshot": return Build.VERSION.SDK_INT >= 28 && performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT);
            default: return false;
        }
    }

    private AccessibilityNodeInfo focused() {
        AccessibilityNodeInfo f = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (f == null) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        }
        return (f != null && f.isEditable()) ? f : null;
    }

    private static String current(AccessibilityNodeInfo f) {
        if (Build.VERSION.SDK_INT >= 26 && f.isShowingHintText()) return "";
        CharSequence t = f.getText();
        return t == null ? "" : t.toString();
    }

    private static boolean setText(AccessibilityNodeInfo f, String s) {
        Bundle b = new Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, s);
        return f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
    }

    /** returns null on success, otherwise an error message. */
    String type(String t) {
        AccessibilityNodeInfo f = focused();
        if (f == null) return "No text box is focused on the phone. Tap a text field first.";
        return setText(f, current(f) + t) ? null : "This text field refused input";
    }

    String edit(String op) {
        AccessibilityNodeInfo f = focused();
        if (f == null) return "No text box is focused on the phone. Tap a text field first.";
        String cur = current(f);
        boolean ok;
        switch (op == null ? "" : op) {
            case "backspace":
                if (cur.isEmpty()) return null;
                ok = setText(f, cur.substring(0, cur.offsetByCodePoints(cur.length(), -1)));
                break;
            case "clear":
                ok = setText(f, "");
                break;
            case "enter":
                if (Build.VERSION.SDK_INT >= 30) {
                    ok = f.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
                } else {
                    ok = setText(f, cur + "\n");
                }
                break;
            default:
                return "Unknown edit op";
        }
        return ok ? null : "Edit failed";
    }
}
