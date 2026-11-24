package com.mhm.net;

import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * Local "VPN" that never leaves the phone: only the blocked apps are routed into it,
 * and every packet they send is dropped -> those apps have no internet.
 *
 * No permanent notification: once the tunnel is up Android itself keeps the VPN service alive
 * (only the small key icon shows in the status bar). All work runs on a background thread.
 */
public class FirewallVpnService extends VpnService {
    public static final String ACTION_REFRESH = "com.mhm.net.FW_REFRESH";
    public static final String ACTION_STOP = "com.mhm.net.FW_STOP";

    private static final long TICK_MS = 15000;
    private static final Set<String> IDLE = new HashSet<>();
    static { IDLE.add("__idle__"); }

    private ParcelFileDescriptor tun;
    private Set<String> current = new HashSet<>();
    private HandlerThread thread;
    private Handler worker;
    private boolean foreground;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            apply();
            if (worker != null) worker.postDelayed(this, TICK_MS);
        }
    };

    /** Call after any rule change. */
    public static void refresh(Context c) {
        Context app = c.getApplicationContext();
        boolean hasRules = !FirewallStore.load(app).isEmpty();
        Intent i = new Intent(app, FirewallVpnService.class);
        try {
            if (!hasRules) {
                i.setAction(ACTION_STOP);
                app.startService(i);
                return;
            }
            if (VpnService.prepare(app) != null) return; // user has not allowed the VPN yet
            i.setAction(ACTION_REFRESH);
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i);
            else app.startService(i);
        } catch (Exception ignored) {}
    }

    @Override
    public void onCreate() {
        super.onCreate();
        thread = new HandlerThread("mhm-firewall");
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Android requires this right after startForegroundService(); it is removed again
        // as soon as the tunnel is up (a fraction of a second).
        if (Build.VERSION.SDK_INT >= 26) {
            startForeground(Notif.ID_FIREWALL, notification());
            foreground = true;
        }
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            worker.post(new Runnable() { @Override public void run() { shutdown(); } });
            return START_NOT_STICKY;
        }
        worker.removeCallbacks(tick);
        worker.post(tick);
        return START_STICKY;
    }

    /** Alarm that restarts blocking when the next daily window begins. */
    static void scheduleWake(Context c) {
        try {
            android.app.AlarmManager am = (android.app.AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            Intent i = new Intent(c, BootReceiver.class).setAction(BootReceiver.ACTION_WAKE);
            android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(c, 77, i,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT);
            long at = FirewallStore.nextStart(c, System.currentTimeMillis());
            if (at < 0) { am.cancel(pi); return; }
            if (Build.VERSION.SDK_INT >= 23) am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, pi);
            else am.setExact(android.app.AlarmManager.RTC_WAKEUP, at, pi);
        } catch (Exception ignored) {}
    }

    private void leaveForeground() {
        if (foreground) {
            stopForeground(true);
            foreground = false;
        }
    }

    /** Runs on the worker thread. */
    private void apply() {
        if (FirewallStore.load(this).isEmpty()) { shutdown(); return; }
        Set<String> want = new HashSet<>();
        PackageManager pm = getPackageManager();
        for (String p : FirewallStore.activePackages(this, System.currentTimeMillis())) {
            if (p.equals(getPackageName()) || GroupStore.isProtected(p)) continue;
            try { pm.getApplicationInfo(p, 0); want.add(p); } catch (PackageManager.NameNotFoundException ignored) {}
        }
        // Nothing blocked right now (e.g. outside the daily window): close the tunnel completely
        // so the key icon disappears, and wake up again at the next start time via an alarm.
        if (want.isEmpty()) {
            scheduleWake(this);
            shutdown();
            return;
        }

        if (want.equals(current) && tun != null) { leaveForeground(); return; }

        Builder b = new Builder();
        b.setSession("MHM NET");
        b.addAddress("10.111.222.1", 32);
        int added = 0;
        if (want == IDLE) {
            // only our own app, and only a private unused address range -> affects nothing
            b.addRoute("10.111.222.0", 24);
            try { b.addAllowedApplication(getPackageName()); added = 1; } catch (PackageManager.NameNotFoundException ignored) {}
        } else {
            b.addRoute("0.0.0.0", 0);
            try {
                b.addAddress("fd00:111:222::1", 128);
                b.addRoute("::", 0);
            } catch (Exception ignored) {}
            for (String p : want) {
                try { b.addAllowedApplication(p); added++; } catch (PackageManager.NameNotFoundException ignored) {}
            }
        }
        // IMPORTANT: never establish with zero apps -> that would block the whole phone.
        if (added == 0) { closeTun(); current = new HashSet<>(); leaveForeground(); return; }

        ParcelFileDescriptor fresh;
        try {
            fresh = b.establish();
        } catch (Exception e) {
            fresh = null;
        }
        if (fresh == null) {
            Prefs.log(this, "تعذر تشغيل الحظر (لم يتم السماح بالـ VPN)");
            shutdown();
            return;
        }
        ParcelFileDescriptor old = tun;
        tun = fresh;
        new Drain(fresh).start();
        if (old != null) { try { old.close(); } catch (IOException ignored) {} }
        current = want;
        leaveForeground();
    }

    /** Reads and throws away everything the blocked apps try to send. */
    private static class Drain extends Thread {
        private final ParcelFileDescriptor pfd;
        Drain(ParcelFileDescriptor pfd) { this.pfd = pfd; setDaemon(true); }
        @Override public void run() {
            byte[] buf = new byte[32767];
            FileDescriptor fd = pfd.getFileDescriptor();
            FileInputStream in = new FileInputStream(fd);
            StructPollfd p = new StructPollfd();
            p.fd = fd;
            p.events = (short) OsConstants.POLLIN;
            StructPollfd[] fds = new StructPollfd[]{p};
            while (!isInterrupted() && fd.valid()) {
                try {
                    int r = Os.poll(fds, 1000);
                    if (r > 0) {
                        try {
                            while (in.read(buf) > 0) { /* drop packet */ }
                        } catch (IOException ignored) {
                            // EAGAIN (queue empty) or closed
                        }
                    }
                } catch (ErrnoException e) {
                    if (e.errno != OsConstants.EINTR) break;
                }
            }
        }
    }

    private void closeTun() {
        if (tun != null) {
            try { tun.close(); } catch (IOException ignored) {}
            tun = null;
        }
    }

    private void shutdown() {
        if (worker != null) worker.removeCallbacks(tick);
        closeTun();
        current = new HashSet<>();
        leaveForeground();
        stopSelf();
    }

    private Notification notification() {
        return Notif.build(this, Notif.CH_FIREWALL, "MHM NET", "جاري تشغيل الحظر…",
                Notif.openApp(this, 2), true);
    }

    @Override
    public void onRevoke() {
        Prefs.log(this, "تم إيقاف الحظر لأن VPN آخر تم تشغيله أو أُلغي الإذن");
        if (worker != null) worker.post(new Runnable() { @Override public void run() { shutdown(); } });
    }

    @Override
    public void onDestroy() {
        if (worker != null) worker.removeCallbacks(tick);
        closeTun();
        if (thread != null) thread.quitSafely();
        worker = null;
        super.onDestroy();
    }
}
