package com.momen.phonedesk;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 42;

    private TextView status, address, pinView, perms;
    private Button shareBtn;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() { refresh(); ui.postDelayed(this, 1500); }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        sv.addView(root);

        TextView sub = text("Control this phone from a browser on your PC, laptop or another phone.", 14, false);
        sub.setTextColor(Color.DKGRAY);
        root.addView(sub);

        status = text("", 20, true);
        status.setPadding(0, dp(18), 0, dp(4));
        root.addView(status);

        address = text("", 17, false);
        address.setTextIsSelectable(true);
        root.addView(address);

        pinView = text("", 26, true);
        pinView.setPadding(0, dp(8), 0, dp(12));
        pinView.setTextIsSelectable(true);
        root.addView(pinView);

        shareBtn = button("▶  Start remote access", v -> {
            if (CaptureService.running) stopService(new Intent(this, CaptureService.class));
            else startCapture();
        });
        root.addView(shareBtn);
        root.addView(button("Enable remote touch (Accessibility)", v -> openAccessibility()));
        root.addView(button("Allow file access", v -> requestFiles()));
        root.addView(button("Generate new PIN", v -> { Pin.regenerate(this); refresh(); }));

        perms = text("", 15, false);
        perms.setPadding(0, dp(16), 0, dp(8));
        root.addView(perms);

        TextView help = text(
                "How to use\n" +
                "1. Tap Start remote access and allow screen capture.\n" +
                "2. Enable \"PhoneDesk remote touch\" in Accessibility so taps work.\n" +
                "   If it is greyed out (Android 13+): Settings → Apps → PhoneDesk → ⋮ → Allow restricted settings.\n" +
                "3. On the controlling device (PC or another phone) open one of the http:// addresses above and enter the PIN.\n" +
                "   Tick \"Remember this device\" on the login page to auto-connect next time.\n\n" +
                "Same Wi-Fi: use the Wi-Fi address.\n" +
                "From another network / mobile data: install Tailscale (free) on BOTH devices, sign in with the same account, then use the Tailscale 100.x address — it works from anywhere, phone-to-phone too.\n\n" +
                "Browser controls: click = tap, hold = long-press, drag = swipe, wheel = scroll, right-click = Back.\n\n" +
                "The connection stays up when the screen turns off. A notification is always shown while access is on; tap Stop to end it.",
                14, false);
        help.setTextColor(Color.DKGRAY);
        root.addView(help);

        setContentView(sv);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override protected void onResume() { super.onResume(); ui.post(tick); }
    @Override protected void onPause() { super.onPause(); ui.removeCallbacks(tick); }

    private void startCapture() {
        MediaProjectionManager m = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(m.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_CAPTURE) return;
        if (res == RESULT_OK && data != null) {
            Intent i = new Intent(this, CaptureService.class);
            i.putExtra(CaptureService.EXTRA_CODE, res);
            i.putExtra(CaptureService.EXTRA_DATA, data);
            startForegroundService(i);
            if (ControlService.instance == null) toast("Screen sharing on. Enable remote touch so clicks work.");
        } else {
            toast("Screen capture was not allowed");
        }
    }

    private void openAccessibility() {
        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        toast("Find \"PhoneDesk remote touch\" and turn it on");
    }

    private void requestFiles() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (Environment.isExternalStorageManager()) { toast("File access already allowed"); return; }
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, 2);
        }
    }

    private void refresh() {
        boolean on = CaptureService.running;
        status.setText(on ? "● Remote access ON" : "○ Remote access OFF");
        status.setTextColor(on ? Color.rgb(0, 140, 60) : Color.GRAY);
        shareBtn.setText(on ? "■  Stop remote access" : "▶  Start remote access");

        java.util.List<String[]> addrs = Net.addresses();
        // Show VPN / Tailscale first — that is the address that works from another network.
        addrs.sort((x, y) -> Integer.compare(rank(y[1]), rank(x[1])));
        StringBuilder sb = new StringBuilder("Open in your browser:\n");
        for (String[] a : addrs) {
            sb.append("  http://").append(a[0]).append(':').append(HttpServer.PORT)
              .append("   (").append(a[1]).append(")\n");
        }
        if (addrs.isEmpty()) sb.append("  (no network – connect to Wi-Fi)\n");
        address.setText(sb);
        pinView.setText("PIN: " + Pin.get(this));

        perms.setText(
                "Screen sharing: " + (on ? "✔ on" : "✘ off") + "\n" +
                "Remote touch:   " + (ControlService.instance != null ? "✔ enabled" : "✘ not enabled") + "\n" +
                "File access:    " + (Files.allowed(this) ? "✔ allowed" : "✘ not allowed"));
    }

    private Button button(String t, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(t);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private static int rank(String label) {
        if (label.contains("Tailscale") || label.contains("VPN")) return 3;
        if (label.equals("Wi-Fi")) return 2;
        if (label.equals("hotspot")) return 1;
        return 0;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
}
