package com.mhm.net;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
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
 * and every packet they send is simply dropped -> those apps have no internet.
 * All other apps are not affected at all.
 */
public class FirewallVpnService extends VpnService {
    public static final String ACTION_REFRESH = "com.mhm.net.FW_REFRESH";
    public static final String ACTION_STOP = "com.mhm.net.FW_STOP";

    private static final long TICK_MS = 15000;

    private ParcelFileDescriptor tun;
    private Set<String> current = new HashSet<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            apply();
            handler.postDelayed(this, TICK_MS);
        }
    };

    /** Call after any rule change. */
    public static void refresh(Context c) {
        Context app = c.getApplicationContext();
        boolean hasRules = !FirewallStore.load(app).isEmpty();
        Intent i = new Intent(app, FirewallVpnService.class);
        if (!hasRules) {
            i.setAction(ACTION_STOP);
            try { app.startService(i); } catch (Exception ignored) {}
            return;
        }
        if (VpnService.prepare(app) != null) return; // user has not allowed the VPN yet
        i.setAction(ACTION_REFRESH);
        if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i);
        else app.startService(i);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(Notif.ID_FIREWALL, notification("جاري التحضير…"));
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            shutdown();
            return START_NOT_STICKY;
        }
        handler.removeCallbacks(tick);
        handler.post(tick);
        return START_STICKY;
    }

    private void apply() {
        if (FirewallStore.load(this).isEmpty()) { shutdown(); return; }
        Set<String> want = new HashSet<>();
        PackageManager pm = getPackageManager();
        for (String p : FirewallStore.activePackages(this, System.currentTimeMillis())) {
            if (p.equals(getPackageName())) continue;
            try { pm.getApplicationInfo(p, 0); want.add(p); } catch (PackageManager.NameNotFoundException ignored) {}
        }

        if (want.isEmpty()) {
            // IMPORTANT: never establish with zero apps -> that would block the whole phone.
            closeTun();
            current = want;
            updateNotification("حجب مجدول — لا يوجد تطبيق محجوب الآن");
            return;
        }
        if (want.equals(current) && tun != null) {
            updateNotification("محجوب الآن: " + want.size() + " تطبيق");
            return;
        }

        Builder b = new Builder();
        b.setSession("MHM NET - حجب");
        b.addAddress("10.111.222.1", 32);
        b.addRoute("0.0.0.0", 0);
        try {
            b.addAddress("fd00:111:222::1", 128);
            b.addRoute("::", 0);
        } catch (Exception ignored) {}
        int added = 0;
        for (String p : want) {
            try { b.addAllowedApplication(p); added++; } catch (PackageManager.NameNotFoundException ignored) {}
        }
        if (added == 0) { closeTun(); current = new HashSet<>(); return; }

        ParcelFileDescriptor fresh;
        try {
            fresh = b.establish();
        } catch (Exception e) {
            fresh = null;
        }
        if (fresh == null) {
            Prefs.log(this, "تعذر تشغيل الحجب (لم يتم السماح بالـ VPN)");
            shutdown();
            return;
        }
        ParcelFileDescriptor old = tun;
        tun = fresh;
        new Drain(fresh).start();
        if (old != null) { try { old.close(); } catch (IOException ignored) {} }
        current = want;
        updateNotification("محجوب الآن: " + want.size() + " تطبيق");
    }

    /** Reads and throws away everything the blocked apps try to send. */
    private static class Drain extends Thread {
        private final ParcelFileDescriptor pfd;
        Drain(ParcelFileDescriptor pfd) { this.pfd = pfd; setDaemon(true); }
        @Override public void run() {
            // Non-blocking fd + poll with timeout, so the thread always notices when the
            // interface is closed (a blocking read would keep the old interface alive).
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
        handler.removeCallbacks(tick);
        closeTun();
        current = new HashSet<>();
        stopForeground(true);
        stopSelf();
    }

    private Notification notification(String text) {
        return Notif.build(this, Notif.CH_FIREWALL, "حجب الإنترنت عن التطبيقات", text,
                Notif.openApp(this, 2), true);
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(Notif.ID_FIREWALL, notification(text));
    }

    @Override
    public void onRevoke() {
        Prefs.log(this, "تم إيقاف الحجب لأن VPN آخر تم تشغيله أو أُلغي الإذن");
        shutdown();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(tick);
        closeTun();
        super.onDestroy();
    }
}
