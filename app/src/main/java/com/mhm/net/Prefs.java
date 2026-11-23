package com.mhm.net;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Simple key/value storage for settings, speed results and the activity log. */
public final class Prefs {
    private Prefs() {}

    public static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences("mhm_net", Context.MODE_PRIVATE);
    }

    public static String ssid1(Context c) { return p(c).getString("ssid1", "M 4g"); }
    public static String ssid2(Context c) { return p(c).getString("ssid2", "M 5g"); }
    public static String pass(Context c, String ssid) { return p(c).getString("pass_" + ssid, ""); }

    public static void saveNetworks(Context c, String s1, String p1, String s2, String p2) {
        p(c).edit()
                .putString("ssid1", s1.trim())
                .putString("ssid2", s2.trim())
                .putString("pass_" + s1.trim(), p1)
                .putString("pass_" + s2.trim(), p2)
                .apply();
    }

    public static boolean autoSwitch(Context c) { return p(c).getBoolean("auto", false); }
    public static void setAutoSwitch(Context c, boolean v) { p(c).edit().putBoolean("auto", v).apply(); }

    public static int intervalMin(Context c) { return p(c).getInt("interval", 30); }
    public static void setIntervalMin(Context c, int v) { p(c).edit().putInt("interval", v).apply(); }

    public static void saveResult(Context c, String ssid, SpeedTest.Result r) {
        if (ssid == null || r == null || !r.ok) return;
        p(c).edit()
                .putFloat("dl_" + ssid, (float) r.dl)
                .putFloat("ul_" + ssid, (float) r.ul)
                .putLong("ping_" + ssid, r.pingMs)
                .putLong("t_" + ssid, r.time)
                .apply();
    }

    public static SpeedTest.Result lastResult(Context c, String ssid) {
        SharedPreferences sp = p(c);
        long t = sp.getLong("t_" + ssid, 0);
        if (t == 0) return null;
        SpeedTest.Result r = new SpeedTest.Result();
        r.ok = true;
        r.time = t;
        r.dl = sp.getFloat("dl_" + ssid, 0);
        r.ul = sp.getFloat("ul_" + ssid, 0);
        r.pingMs = sp.getLong("ping_" + ssid, 0);
        return r;
    }

    /** Keeps the last 25 log lines. */
    public static synchronized void log(Context c, String msg) {
        String time = new SimpleDateFormat("dd/MM HH:mm", Locale.US).format(new Date());
        String old = p(c).getString("log", "");
        String all = time + "  " + msg + (old.isEmpty() ? "" : "\n" + old);
        String[] lines = all.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(25, lines.length); i++) {
            if (i > 0) sb.append('\n');
            sb.append(lines[i]);
        }
        p(c).edit().putString("log", sb.toString()).apply();
    }

    public static String logText(Context c) { return p(c).getString("log", ""); }
}
