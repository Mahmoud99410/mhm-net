package com.mhm.net;

import android.app.AppOpsManager;
import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.Process;
import android.telephony.TelephonyManager;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Reads Android's own data-usage counters, split into Wi-Fi and mobile data. */
public final class UsageRepo {
    private UsageRepo() {}

    public static final int TODAY = 0, WEEK = 1, MONTH = 2;

    public static class Totals {
        public long wifiRx, wifiTx, mobRx, mobTx;
    }

    public static class AppUsage {
        public int uid;
        public String name;
        public Drawable icon;
        public long wifi, mobile;
        public long total() { return wifi + mobile; }
    }

    @SuppressWarnings("deprecation")
    public static boolean hasAccess(Context c) {
        try {
            AppOpsManager ops = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    public static long startOfDay(long t) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** {start, end} for today / last 7 days / this month. */
    public static long[] range(int period) {
        long now = System.currentTimeMillis();
        long today = startOfDay(now);
        if (period == WEEK) {
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(today);
            c.add(Calendar.DAY_OF_MONTH, -6);
            return new long[]{c.getTimeInMillis(), now + 60_000};
        }
        if (period == MONTH) {
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(today);
            c.set(Calendar.DAY_OF_MONTH, 1);
            return new long[]{c.getTimeInMillis(), now + 60_000};
        }
        return new long[]{today, now + 60_000};
    }

    public static String periodName(int period) {
        if (period == WEEK) return "آخر 7 أيام";
        if (period == MONTH) return "هذا الشهر";
        return "اليوم";
    }

    @SuppressWarnings({"deprecation", "MissingPermission", "HardwareIds"})
    private static String subscriber(Context c) {
        if (Build.VERSION.SDK_INT >= 29) return null; // null = all SIM cards on Android 10+
        try {
            TelephonyManager tm = (TelephonyManager) c.getSystemService(Context.TELEPHONY_SERVICE);
            return tm.getSubscriberId();
        } catch (Exception e) {
            return null;
        }
    }

    private static NetworkStatsManager nsm(Context c) {
        return (NetworkStatsManager) c.getSystemService(Context.NETWORK_STATS_SERVICE);
    }

    private static long[] device(NetworkStatsManager nsm, int type, String sub, long s, long e) {
        try {
            NetworkStats.Bucket b = nsm.querySummaryForDevice(type, sub, s, e);
            if (b == null) return new long[]{0, 0};
            return new long[]{b.getRxBytes(), b.getTxBytes()};
        } catch (Exception ex) {
            return new long[]{0, 0};
        }
    }

    @SuppressWarnings("deprecation")
    public static Totals totals(Context c, long s, long e) {
        NetworkStatsManager nsm = nsm(c);
        String sub = subscriber(c);
        Totals t = new Totals();
        long[] w = device(nsm, ConnectivityManager.TYPE_WIFI, null, s, e);
        long[] m = device(nsm, ConnectivityManager.TYPE_MOBILE, sub, s, e);
        t.wifiRx = w[0]; t.wifiTx = w[1];
        t.mobRx = m[0]; t.mobTx = m[1];
        return t;
    }

    /** Usage per day for the last {@code days} days. [i][0] = wifi, [i][1] = mobile, index 0 = today. */
    @SuppressWarnings("deprecation")
    public static long[][] daily(Context c, int days) {
        NetworkStatsManager nsm = nsm(c);
        String sub = subscriber(c);
        long[][] out = new long[days][2];
        long dayEnd = System.currentTimeMillis() + 60_000;
        long dayStart = startOfDay(System.currentTimeMillis());
        for (int i = 0; i < days; i++) {
            long[] w = device(nsm, ConnectivityManager.TYPE_WIFI, null, dayStart, dayEnd);
            long[] m = device(nsm, ConnectivityManager.TYPE_MOBILE, sub, dayStart, dayEnd);
            out[i][0] = w[0] + w[1];
            out[i][1] = m[0] + m[1];
            dayEnd = dayStart;
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(dayStart);
            cal.add(Calendar.DAY_OF_MONTH, -1);
            dayStart = cal.getTimeInMillis();
        }
        return out;
    }

    private static void perUid(NetworkStatsManager nsm, int type, String sub, long s, long e, Map<Integer, long[]> map, int slot) {
        NetworkStats ns = null;
        try {
            ns = nsm.querySummary(type, sub, s, e);
            NetworkStats.Bucket b = new NetworkStats.Bucket();
            while (ns.hasNextBucket()) {
                ns.getNextBucket(b);
                long[] v = map.get(b.getUid());
                if (v == null) { v = new long[2]; map.put(b.getUid(), v); }
                v[slot] += b.getRxBytes() + b.getTxBytes();
            }
        } catch (Exception ignored) {
        } finally {
            if (ns != null) ns.close();
        }
    }

    @SuppressWarnings("deprecation")
    public static List<AppUsage> perApp(Context c, long s, long e) {
        NetworkStatsManager nsm = nsm(c);
        String sub = subscriber(c);
        Map<Integer, long[]> map = new HashMap<>();
        perUid(nsm, ConnectivityManager.TYPE_WIFI, null, s, e, map, 0);
        perUid(nsm, ConnectivityManager.TYPE_MOBILE, sub, s, e, map, 1);

        PackageManager pm = c.getPackageManager();
        List<AppUsage> out = new ArrayList<>();
        for (Map.Entry<Integer, long[]> en : map.entrySet()) {
            long[] v = en.getValue();
            if (v[0] + v[1] <= 0) continue;
            AppUsage a = new AppUsage();
            a.uid = en.getKey();
            a.wifi = v[0];
            a.mobile = v[1];
            fillName(pm, a);
            out.add(a);
        }
        Collections.sort(out, new Comparator<AppUsage>() {
            @Override public int compare(AppUsage x, AppUsage y) { return Long.compare(y.total(), x.total()); }
        });
        return out;
    }

    private static void fillName(PackageManager pm, AppUsage a) {
        if (a.uid == NetworkStats.Bucket.UID_REMOVED) { a.name = "تطبيقات محذوفة"; return; }
        if (a.uid == NetworkStats.Bucket.UID_TETHERING) { a.name = "نقطة الاتصال (مشاركة الإنترنت)"; return; }
        if (a.uid == Process.SYSTEM_UID) { a.name = "نظام أندرويد"; return; }
        if (a.uid == 0) { a.name = "النظام (root)"; return; }
        String[] pkgs = pm.getPackagesForUid(a.uid);
        if (pkgs == null || pkgs.length == 0) { a.name = "UID " + a.uid; return; }
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkgs[0], 0);
            a.name = String.valueOf(pm.getApplicationLabel(ai));
            a.icon = pm.getApplicationIcon(ai);
        } catch (Exception ex) {
            a.name = pkgs[0];
        }
        if (pkgs.length > 1) a.name += " (+" + (pkgs.length - 1) + ")";
    }
}
