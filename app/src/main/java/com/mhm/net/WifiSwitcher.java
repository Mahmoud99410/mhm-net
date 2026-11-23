package com.mhm.net;

import android.Manifest;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Network;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tests the speed of the two home Wi-Fi networks and stays on the faster one.
 * Uses the legacy WifiManager API (works because targetSdk = 28).
 */
@SuppressWarnings("deprecation")
public final class WifiSwitcher {
    private WifiSwitcher() {}

    public interface Listener { void onProgress(String msg); }

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    /** The other network must be at least this much better before we switch (avoids flip-flopping). */
    private static final double MARGIN = 1.10;

    public static boolean isRunning() { return RUNNING.get(); }

    public static void runCheck(Context ctx, Listener l) {
        if (!RUNNING.compareAndSet(false, true)) {
            say(ctx, l, "يوجد فحص يعمل الآن…", false);
            return;
        }
        try {
            doCheck(ctx.getApplicationContext(), l);
        } catch (Exception e) {
            say(ctx, l, "خطأ أثناء الفحص: " + e.getMessage(), true);
        } finally {
            RUNNING.set(false);
        }
    }

    private static void say(Context c, Listener l, String msg, boolean log) {
        if (l != null) l.onProgress(msg);
        if (log) Prefs.log(c, msg);
    }

    private static void doCheck(Context c, Listener l) {
        WifiManager wm = NetUtil.wifi(c);
        if (!wm.isWifiEnabled()) {
            say(c, l, "الواي فاي مغلق — لم يتم الفحص", true);
            return;
        }
        String s1 = Prefs.ssid1(c), s2 = Prefs.ssid2(c);
        boolean locPerm = Build.VERSION.SDK_INT < 23
                || c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!locPerm || !NetUtil.locationEnabled(c)) {
            say(c, l, "لا يمكن معرفة اسم الشبكة — شغّل الموقع (GPS) وامنح إذن الموقع للتطبيق", true);
            return;
        }
        String start = NetUtil.currentSsid(c);
        if (start != null && !start.equals(s1) && !start.equals(s2)) {
            say(c, l, "أنت متصل بشبكة أخرى (" + start + ") — لن يتم التبديل", false);
            return;
        }
        Set<String> visible = visibleSsids(wm);

        List<String> order = new ArrayList<>();
        if (s2.equals(start)) { order.add(s2); order.add(s1); }
        else { order.add(s1); order.add(s2); }

        Map<String, SpeedTest.Result> res = new LinkedHashMap<>();
        boolean blocked = false;
        for (String ssid : order) {
            if (!ssid.equals(start) && !visible.isEmpty() && !visible.contains(ssid)) {
                say(c, l, ssid + " غير ظاهرة حالياً (خارج التغطية أو مطفأة)", true);
                continue;
            }
            if (!ssid.equals(NetUtil.currentSsid(c))) {
                say(c, l, "جاري الانتقال إلى " + ssid + " …", false);
                if (!connect(c, ssid)) {
                    blocked = true;
                    say(c, l, "أندرويد رفض الانتقال إلى " + ssid + " (أضف كلمة مرورها من إعدادات الشبكات داخل التطبيق)", true);
                    continue;
                }
                if (!waitFor(c, ssid, 25000)) {
                    say(c, l, "لم يتم الاتصال بـ " + ssid + " (قد تكون خارج التغطية)", true);
                    continue;
                }
            } else {
                waitFor(c, ssid, 8000);
            }
            say(c, l, "قياس سرعة " + ssid + " …", false);
            Network net = NetUtil.wifiNetwork(c);
            SpeedTest.Result r = SpeedTest.run(net);
            if (r.ok) {
                Prefs.saveResult(c, ssid, r);
                res.put(ssid, r);
                say(c, l, ssid + ": ↓ " + Fmt.speed(r.dl) + "  ↑ " + Fmt.speed(r.ul) + "  (" + r.pingMs + " ms)", true);
            } else {
                say(c, l, "فشل قياس " + ssid + (r.error != null ? ": " + r.error : ""), true);
            }
        }

        // choose the best network
        String best = null;
        double bestScore = -1;
        for (Map.Entry<String, SpeedTest.Result> e : res.entrySet()) {
            if (e.getValue().score() > bestScore) { bestScore = e.getValue().score(); best = e.getKey(); }
        }
        if (best == null) {
            // nothing measured now; if switching is blocked, advise from older results
            if (blocked) suggestFromHistory(c, s1, s2);
            return;
        }
        if (start != null && res.containsKey(start) && !best.equals(start)
                && bestScore < res.get(start).score() * MARGIN) {
            best = start; // difference too small, stay where we started
        }

        // only one network measured because Android blocked switching -> use old result of the other
        if (blocked && res.size() == 1) {
            String other = best.equals(s1) ? s2 : s1;
            SpeedTest.Result old = Prefs.lastResult(c, other);
            if (old != null && System.currentTimeMillis() - old.time < 24 * 3600_000L
                    && old.score() > bestScore * MARGIN) {
                notifySwitch(c, other, old);
            }
            return;
        }

        if (!best.equals(NetUtil.currentSsid(c))) {
            say(c, l, "الانتقال إلى الأسرع: " + best, false);
            if (connect(c, best) && waitFor(c, best, 25000)) {
                say(c, l, "✅ تم اختيار الشبكة الأسرع: " + best, true);
            } else {
                notifySwitch(c, best, res.get(best));
            }
        } else {
            say(c, l, "✅ أنت على الشبكة الأسرع: " + best, true);
        }
    }

    private static Set<String> visibleSsids(WifiManager wm) {
        Set<String> out = new HashSet<>();
        try {
            List<ScanResult> list = wm.getScanResults();
            if (list != null) for (ScanResult r : list) if (r.SSID != null) out.add(r.SSID);
        } catch (SecurityException ignored) {}
        return out;
    }

    /** Tries to connect to a saved network (or adds it using the password stored in the app). */
    public static boolean connect(Context c, String ssid) {
        WifiManager wm = NetUtil.wifi(c);
        String q = "\"" + ssid + "\"";
        int id = -1;
        try {
            List<WifiConfiguration> list = wm.getConfiguredNetworks();
            if (list != null) {
                for (WifiConfiguration cfg : list) {
                    if (q.equals(cfg.SSID)) { id = cfg.networkId; break; }
                }
            }
        } catch (SecurityException ignored) {}

        try {
            if (id != -1 && wm.enableNetwork(id, true)) {
                wm.reconnect();
                return true;
            }
            String pass = Prefs.pass(c, ssid);
            if (pass.length() >= 8) {
                WifiConfiguration cfg = new WifiConfiguration();
                cfg.SSID = q;
                cfg.preSharedKey = "\"" + pass + "\"";
                int nid = wm.addNetwork(cfg);
                if (nid != -1 && wm.enableNetwork(nid, true)) {
                    wm.reconnect();
                    return true;
                }
            }
        } catch (SecurityException ignored) {}
        return false;
    }

    private static boolean waitFor(Context c, String ssid, long timeoutMs) {
        long end = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < end) {
            if (ssid.equals(NetUtil.currentSsid(c)) && NetUtil.wifiValidated(c)) {
                SystemClock.sleep(1500);
                return true;
            }
            SystemClock.sleep(1000);
        }
        return false;
    }

    private static void suggestFromHistory(Context c, String s1, String s2) {
        SpeedTest.Result a = Prefs.lastResult(c, s1), b = Prefs.lastResult(c, s2);
        if (a == null || b == null) return;
        String cur = NetUtil.currentSsid(c);
        String best = a.score() >= b.score() ? s1 : s2;
        if (!best.equals(cur)) notifySwitch(c, best, best.equals(s1) ? a : b);
    }

    private static void notifySwitch(Context c, String ssid, SpeedTest.Result r) {
        Intent i = Build.VERSION.SDK_INT >= 29
                ? new Intent(Settings.Panel.ACTION_WIFI)
                : new Intent(Settings.ACTION_WIFI_SETTINGS);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(c, 99, i, flags);
        String speed = r != null ? " (↓ " + Fmt.speed(r.dl) + ")" : "";
        Notif.alert(c, "الشبكة الأسرع الآن: " + ssid,
                "اضغط هنا للانتقال إلى " + ssid + speed, pi);
        Prefs.log(c, "تم إرسال تنبيه للانتقال يدوياً إلى " + ssid);
    }
}
