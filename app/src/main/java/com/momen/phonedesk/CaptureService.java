package com.momen.phonedesk;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/** Captures the screen with MediaProjection, encodes JPEG frames and runs the web server. */
public class CaptureService extends Service {
    static final String EXTRA_CODE = "code";
    static final String EXTRA_DATA = "data";
    static final String ACTION_STOP = "com.momen.phonedesk.STOP";
    private static final String CHANNEL = "phonedesk";

    static volatile boolean running;
    static volatile byte[] frame;
    static volatile long frameNo;
    static volatile int quality = 60;
    static volatile int maxShortSide = 720;
    static final Object FRAME_LOCK = new Object();

    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread thread;
    private Handler handler;
    private HttpServer server;
    private Bitmap bmp;
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifiLock;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent == null || running) return START_NOT_STICKY;

        startForegroundNow();

        int code = intent.getIntExtra(EXTRA_CODE, 0);
        Intent data = Build.VERSION.SDK_INT >= 33
                ? intent.getParcelableExtra(EXTRA_DATA, Intent.class)
                : intent.getParcelableExtra(EXTRA_DATA);
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        try {
            projection = mpm.getMediaProjection(code, data);
        } catch (Exception e) {
            projection = null;
        }
        if (projection == null) { stopSelf(); return START_NOT_STICKY; }

        thread = new HandlerThread("phonedesk-capture");
        thread.start();
        handler = new Handler(thread.getLooper());
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { stopSelf(); }
        }, handler);

        int[] s = size();
        reader = newReader(s[0], s[1]);
        display = projection.createVirtualDisplay("PhoneDesk", s[0], s[1], s[2],
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, handler);

        server = new HttpServer(getApplicationContext());
        server.start();

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "phonedesk:server");
        wake.acquire();

        // Keep the Wi-Fi radio fully on so the server stays reachable with the screen off.
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wm != null) {
            int mode = Build.VERSION.SDK_INT >= 29
                    ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                    : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
            wifiLock = wm.createWifiLock(mode, "phonedesk:wifi");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        }

        running = true;
        return START_NOT_STICKY;
    }

    /** returns {width, height, dpi} of the capture surface (scaled down for speed). */
    private int[] size() {
        DisplayMetrics m = new DisplayMetrics();
        ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(m);
        float scale = Math.min(1f, (float) maxShortSide / Math.min(m.widthPixels, m.heightPixels));
        int w = Math.max(2, Math.round(m.widthPixels * scale)) & ~1;
        int h = Math.max(2, Math.round(m.heightPixels * scale)) & ~1;
        return new int[]{w, h, m.densityDpi};
    }

    private ImageReader newReader(int w, int h) {
        ImageReader r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
        r.setOnImageAvailableListener(this::onImage, handler);
        return r;
    }

    /** Re-create the surface when the phone rotates or quality setting changes. */
    void resize() {
        if (handler == null) return;
        handler.post(() -> {
            if (display == null || reader == null) return;
            int[] s = size();
            if (s[0] == reader.getWidth() && s[1] == reader.getHeight()) return;
            ImageReader old = reader;
            reader = newReader(s[0], s[1]);
            display.resize(s[0], s[1], s[2]);
            display.setSurface(reader.getSurface());
            old.close();
            bmp = null;
        });
    }

    static volatile CaptureService self;
    @Override public void onCreate() { super.onCreate(); self = this; }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        if (handler != null) handler.postDelayed(this::resize, 300);
    }

    private void onImage(ImageReader r) {
        Image img = null;
        try {
            img = r.acquireLatestImage();
            if (img == null) return;
            Image.Plane p = img.getPlanes()[0];
            ByteBuffer buf = p.getBuffer();
            int ps = p.getPixelStride(), rs = p.getRowStride();
            int w = img.getWidth(), h = img.getHeight();
            int bw = rs / ps;
            if (bmp == null || bmp.getWidth() != bw || bmp.getHeight() != h) {
                bmp = Bitmap.createBitmap(bw, h, Bitmap.Config.ARGB_8888);
            }
            buf.rewind();
            bmp.copyPixelsFromBuffer(buf);
            Bitmap out = (bw == w) ? bmp : Bitmap.createBitmap(bmp, 0, 0, w, h);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(96 * 1024);
            out.compress(Bitmap.CompressFormat.JPEG, quality, bos);
            if (out != bmp) out.recycle();
            synchronized (FRAME_LOCK) {
                frame = bos.toByteArray();
                frameNo++;
                FRAME_LOCK.notifyAll();
            }
        } catch (Exception ignored) {
        } finally {
            if (img != null) img.close();
        }
    }

    private void startForegroundNow() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Remote access", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, CaptureService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle("PhoneDesk: remote access ON")
                .setContentText("Screen shared on port " + HttpServer.PORT + " (PIN protected)")
                .setSmallIcon(R.drawable.ic_notify)
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(1, n);
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        self = null;
        if (server != null) server.shutdown();
        if (display != null) display.release();
        if (projection != null) { try { projection.stop(); } catch (Exception ignored) { } }
        if (reader != null) reader.close();
        if (thread != null) thread.quitSafely();
        if (wake != null && wake.isHeld()) wake.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        synchronized (FRAME_LOCK) { frame = null; FRAME_LOCK.notifyAll(); }
        super.onDestroy();
    }
}
