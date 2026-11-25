package com.leon.durakbot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.nio.ByteBuffer;

/** Держит запись экрана и отдаёт последний кадр. */
public class CaptureService extends Service {

    static volatile CaptureService instance;

    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread thread;

    private final Object lock = new Object();
    private final Frame frame = new Frame();
    private long seq = 0;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("cap", "Запись экрана", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, "cap")
                .setContentTitle("Дурак Бот")
                .setContentText("Бот видит экран")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(1, n);
        }

        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        int code = intent.getIntExtra("code", 0);
        Intent data = intent.getParcelableExtra("data");
        release();

        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        try {
            projection = mpm.getMediaProjection(code, data);
        } catch (Exception e) {
            projection = null;
        }
        if (projection == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        thread = new HandlerThread("capture");
        thread.start();
        Handler h = new Handler(thread.getLooper());
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                instance = null;
                release();
                stopSelf();
            }
        }, h);

        DisplayMetrics dm = new DisplayMetrics();
        getSystemService(WindowManager.class).getDefaultDisplay().getRealMetrics(dm);
        int w = dm.widthPixels, hgt = dm.heightPixels;
        reader = ImageReader.newInstance(w, hgt, PixelFormat.RGBA_8888, 2);
        display = projection.createVirtualDisplay("bbbot", w, hgt, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, h);
        instance = this;
        return START_NOT_STICKY;
    }

    /** Последний кадр (если новых нет — предыдущий). Вызывать только из потока бота. */
    Frame grab() {
        synchronized (lock) {
            ImageReader rd = reader;
            if (rd == null) return null;
            Image img = null;
            try {
                img = rd.acquireLatestImage();
                if (img != null) {
                    Image.Plane p = img.getPlanes()[0];
                    ByteBuffer buf = p.getBuffer();
                    buf.rewind();
                    int len = buf.remaining();
                    if (frame.data == null || frame.data.length != len) frame.data = new byte[len];
                    buf.get(frame.data, 0, len);
                    frame.w = img.getWidth();
                    frame.h = img.getHeight();
                    frame.rowStride = p.getRowStride();
                    frame.pixelStride = p.getPixelStride();
                    frame.seq = ++seq;
                }
            } catch (Exception e) {
                // пропускаем кадр
            } finally {
                if (img != null) img.close();
            }
            return frame.data == null ? null : frame;
        }
    }

    long currentSeq() {
        synchronized (lock) {
            return seq;
        }
    }

    private void release() {
        synchronized (lock) {
            if (display != null) { display.release(); display = null; }
            if (reader != null) { reader.close(); reader = null; }
        }
        if (thread != null) { thread.quitSafely(); thread = null; }
    }

    @Override
    public void onDestroy() {
        instance = null;
        MediaProjection p = projection;
        projection = null;
        release();
        if (p != null) {
            try { p.stop(); } catch (Exception ignored) { }
        }
        super.onDestroy();
    }
}
