package com.mhm.net;

import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.TrafficStats;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

/** Keeps running while auto-switch is on: shows live speed and checks the networks every N minutes. */
public class MonitorService extends Service {
    public static final String ACTION_CHECK_NOW = "com.mhm.net.CHECK_NOW";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastRx = -1, lastTx, lastT;
    private long nextCheck;
    private volatile String lastMsg = "";

    public static void start(Context c) {
        Intent i = new Intent(c.getApplicationContext(), MonitorService.class);
        if (Build.VERSION.SDK_INT >= 26) c.getApplicationContext().startForegroundService(i);
        else c.getApplicationContext().startService(i);
    }

    public static void stop(Context c) {
        c.getApplicationContext().stopService(new Intent(c.getApplicationContext(), MonitorService.class));
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            long now = SystemClock.elapsedRealtime();
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            boolean screenOn = pm == null || pm.isInteractive();

            long rx = TrafficStats.getTotalRxBytes(), tx = TrafficStats.getTotalTxBytes();
            if (screenOn && lastRx >= 0 && now > lastT) {
                double sec = (now - lastT) / 1000.0;
                String text = "↓ " + Fmt.speed((rx - lastRx) / sec) + "   ↑ " + Fmt.speed((tx - lastTx) / sec)
                        + "\n" + NetUtil.activeLabel(MonitorService.this)
                        + (lastMsg.isEmpty() ? "" : "\n" + lastMsg);
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm != null) nm.notify(Notif.ID_MONITOR, Notif.build(MonitorService.this, Notif.CH_MONITOR,
                        "MHM NET — التبديل التلقائي مفعّل", text, Notif.openApp(MonitorService.this, 1), true));
            }
            lastRx = rx; lastTx = tx; lastT = now;

            if (now >= nextCheck && !WifiSwitcher.isRunning()) {
                nextCheck = now + Prefs.intervalMin(MonitorService.this) * 60_000L;
                new Thread(new Runnable() {
                    @Override public void run() {
                        WifiSwitcher.runCheck(MonitorService.this, new WifiSwitcher.Listener() {
                            @Override public void onProgress(String msg) { lastMsg = msg; }
                        });
                    }
                }).start();
            }
            handler.postDelayed(this, 2000);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(Notif.ID_MONITOR, Notif.build(this, Notif.CH_MONITOR,
                "MHM NET — التبديل التلقائي مفعّل", "جاري المراقبة…", Notif.openApp(this, 1), true));
        nextCheck = SystemClock.elapsedRealtime() + 60_000L; // first check one minute after start
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!Prefs.autoSwitch(this)) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_CHECK_NOW.equals(intent.getAction())) nextCheck = 0;
        handler.removeCallbacks(tick);
        handler.post(tick);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(tick);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
