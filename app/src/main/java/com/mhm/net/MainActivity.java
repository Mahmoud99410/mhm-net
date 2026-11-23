package com.mhm.net;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Network;
import android.net.TrafficStats;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.TimePicker;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_VPN = 10, REQ_PERM = 11;
    private static final int TAB_HOME = 0, TAB_NETS = 1, TAB_BLOCK = 2, TAB_APPS = 3;

    private static final String[] SOCIAL = {
            "com.facebook.katana", "com.facebook.lite", "com.facebook.orca", "com.facebook.mlite",
            "com.instagram.android", "com.instagram.lite", "com.instagram.barcelona",
            "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.zhiliaoapp.musically.go",
            "com.snapchat.android", "com.twitter.android", "com.whatsapp", "com.whatsapp.w4b",
            "org.telegram.messenger", "org.telegram.messenger.web", "com.google.android.youtube",
            "com.reddit.frontpage", "com.linkedin.android", "com.pinterest"
    };

    private LinearLayout content;
    private ScrollView scroll;
    private final Button[] tabBtns = new Button[4];
    private int tab = TAB_HOME, period = UsageRepo.TODAY;
    private int gen = 0;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();

    private TextView liveDl, liveUl, liveNet, progressTv;
    private long lastRx = -1, lastTx, lastT;
    private Runnable pendingVpn;
    private List<AppItem> appsCache;
    private String search = "";

    private static class AppItem {
        String pkg, name;
        Drawable icon;
    }

    private interface Cb<T> { void on(T t); }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Notif.channels(this);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.CARD);

        LinearLayout root = Ui.vbox(this);
        root.setBackgroundColor(Ui.BG);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        LinearLayout header = Ui.hbox(this);
        header.setPadding(dp(16), dp(14), dp(16), dp(4));
        TextView title = Ui.text(this, "MHM NET", 22, Ui.ACCENT, true);
        header.addView(title, Ui.weight());
        header.addView(Ui.text(this, "مراقبة وإدارة الإنترنت", 13, Ui.SUB, false));
        root.addView(header);

        scroll = new ScrollView(this);
        content = Ui.vbox(this);
        content.setPadding(dp(12), 0, dp(12), dp(20));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1f));

        LinearLayout bar = Ui.hbox(this);
        bar.setBackgroundColor(Ui.CARD);
        String[] names = {"الرئيسية", "الشبكات", "الحجب", "التطبيقات"};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            Button t = new Button(this);
            t.setText(names[i]);
            t.setAllCaps(false);
            t.setTextSize(14);
            t.setBackgroundColor(0);
            t.setStateListAnimator(null);
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { showTab(idx); }
            });
            bar.addView(t, new LinearLayout.LayoutParams(0, dp(56), 1f));
            tabBtns[i] = t;
        }
        root.addView(bar);
        setContentView(root);

        tab = getIntent().getIntExtra("tab", TAB_HOME);
        askPermissions();
        if (Prefs.autoSwitch(this)) MonitorService.start(this);
        if (!FirewallStore.load(this).isEmpty() && VpnService.prepare(this) == null) {
            FirewallVpnService.refresh(this);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.hasExtra("tab")) showTab(intent.getIntExtra("tab", TAB_HOME));
    }

    @Override
    protected void onResume() {
        super.onResume();
        showTab(tab);
        lastRx = -1;
        h.removeCallbacks(liveTick);
        h.post(liveTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        h.removeCallbacks(liveTick);
    }

    private final Runnable liveTick = new Runnable() {
        @Override public void run() {
            long now = SystemClock.elapsedRealtime();
            long rx = TrafficStats.getTotalRxBytes(), tx = TrafficStats.getTotalTxBytes();
            if (lastRx >= 0 && now > lastT && liveDl != null) {
                double sec = (now - lastT) / 1000.0;
                liveDl.setText("↓ " + Fmt.speed((rx - lastRx) / sec));
                liveUl.setText("↑ " + Fmt.speed((tx - lastTx) / sec));
                liveNet.setText(NetUtil.activeLabel(MainActivity.this));
            }
            lastRx = rx; lastTx = tx; lastT = now;
            h.postDelayed(this, 1000);
        }
    };

    private int dp(float v) { return Ui.dp(this, v); }

    private <T> void async(final Callable<T> work, final Cb<T> ui) {
        final int g = gen;
        bg.execute(new Runnable() {
            @Override public void run() {
                T r;
                try { r = work.call(); } catch (Exception e) { r = null; }
                final T fr = r;
                h.post(new Runnable() {
                    @Override public void run() {
                        if (g == gen && !isFinishing()) ui.on(fr);
                    }
                });
            }
        });
    }

    private void showTab(int i) {
        tab = i;
        gen++;
        liveDl = liveUl = liveNet = progressTv = null;
        for (int k = 0; k < tabBtns.length; k++) {
            tabBtns[k].setTextColor(k == i ? Ui.ACCENT : Ui.SUB);
        }
        content.removeAllViews();
        switch (i) {
            case TAB_HOME: buildHome(); break;
            case TAB_NETS: buildNetworks(); break;
            case TAB_BLOCK: buildBlock(); break;
            default: buildApps(); break;
        }
        scroll.scrollTo(0, 0);
    }

    // ------------------------------------------------------------------ permissions

    private void askPermissions() {
        if (Build.VERSION.SDK_INT < 23) return;
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
            need.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.READ_PHONE_STATE);
        }
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), REQ_PERM);
    }

    private boolean hasLocation() {
        return Build.VERSION.SDK_INT < 23
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        showTab(tab);
    }

    private void addUsageWarning() {
        if (UsageRepo.hasAccess(this)) return;
        LinearLayout c = Ui.card(this);
        c.addView(Ui.text(this, "⚠️ مطلوب إذن", 16, Ui.MOBILE, true));
        c.addView(Ui.text(this, "لعرض استهلاك الإنترنت يجب تفعيل إذن «الوصول إلى بيانات الاستخدام» لتطبيق MHM NET.", 14, Ui.TEXT, false));
        c.addView(Ui.button(this, "منح الإذن", Ui.MOBILE, new View.OnClickListener() {
            @Override public void onClick(View v) { openSafe(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)); }
        }));
        content.addView(c);
    }

    private void openSafe(Intent i) {
        try { startActivity(i); } catch (Exception e) { Ui.toast(this, "تعذر فتح الإعدادات"); }
    }

    // ------------------------------------------------------------------ period selector

    private View periodSelector(final int targetTab) {
        LinearLayout row = Ui.hbox(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP);
        lp.topMargin = dp(10);
        row.setLayoutParams(lp);
        String[] names = {"اليوم", "الأسبوع", "الشهر"};
        for (int i = 0; i < 3; i++) {
            final int p = i;
            Button b = Ui.button(this, names[i], i == period ? Ui.ACCENT : Ui.CARD2, new View.OnClickListener() {
                @Override public void onClick(View v) { period = p; showTab(targetTab); }
            });
            b.setTextColor(i == period ? Ui.BG : Ui.TEXT);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, Ui.WRAP, 1f);
            blp.setMargins(dp(3), 0, dp(3), 0);
            row.addView(b, blp);
        }
        return row;
    }

    // ------------------------------------------------------------------ TAB 1: home

    private void buildHome() {
        addUsageWarning();

        LinearLayout live = Ui.card(this);
        live.addView(Ui.text(this, "السرعة الآن", 14, Ui.SUB, false));
        LinearLayout row = Ui.hbox(this);
        liveDl = Ui.text(this, "↓ 0.00 MB/s", 20, Ui.ACCENT, true);
        liveUl = Ui.text(this, "↑ 0.00 MB/s", 20, Ui.WIFI, true);
        row.addView(liveDl, Ui.weight());
        row.addView(liveUl, Ui.weight());
        live.addView(row);
        liveNet = Ui.text(this, NetUtil.activeLabel(this), 13, Ui.SUB, false);
        live.addView(liveNet);
        content.addView(live);

        content.addView(periodSelector(TAB_HOME));

        final LinearLayout wifiCard = Ui.card(this), mobCard = Ui.card(this);
        wifiCard.addView(Ui.text(this, "جاري الحساب…", 14, Ui.SUB, false));
        mobCard.addView(Ui.text(this, "جاري الحساب…", 14, Ui.SUB, false));
        content.addView(wifiCard);
        content.addView(mobCard);

        final LinearLayout days = Ui.card(this);
        content.addView(days);

        final long[] r = UsageRepo.range(period);
        async(new Callable<UsageRepo.Totals>() {
            @Override public UsageRepo.Totals call() { return UsageRepo.totals(MainActivity.this, r[0], r[1]); }
        }, new Cb<UsageRepo.Totals>() {
            @Override public void on(UsageRepo.Totals t) {
                if (t == null) t = new UsageRepo.Totals();
                fillTotal(wifiCard, "📶 الواي فاي", Ui.WIFI, t.wifiRx, t.wifiTx);
                fillTotal(mobCard, "📱 بيانات الهاتف", Ui.MOBILE, t.mobRx, t.mobTx);
            }
        });
        async(new Callable<long[][]>() {
            @Override public long[][] call() { return UsageRepo.daily(MainActivity.this, 7); }
        }, new Cb<long[][]>() {
            @Override public void on(long[][] d) { fillDays(days, d); }
        });
    }

    private void fillTotal(LinearLayout card, String title, int color, long rx, long tx) {
        card.removeAllViews();
        LinearLayout top = Ui.hbox(this);
        top.addView(Ui.text(this, title + " — " + UsageRepo.periodName(period), 15, color, true), Ui.weight());
        card.addView(top);
        card.addView(Ui.text(this, Fmt.bytes(rx + tx), 28, Ui.TEXT, true));
        LinearLayout row = Ui.hbox(this);
        row.addView(Ui.text(this, "تنزيل: " + Fmt.bytes(rx), 14, Ui.SUB, false), Ui.weight());
        row.addView(Ui.text(this, "رفع: " + Fmt.bytes(tx), 14, Ui.SUB, false), Ui.weight());
        card.addView(row);
    }

    private void fillDays(LinearLayout card, long[][] d) {
        card.removeAllViews();
        card.addView(Ui.text(this, "آخر 7 أيام", 15, Ui.TEXT, true));
        if (d == null) return;
        long max = 1;
        for (long[] x : d) max = Math.max(max, Math.max(x[0], x[1]));
        SimpleDateFormat dayName = new SimpleDateFormat("EEEE", new Locale("ar"));
        SimpleDateFormat dayNum = new SimpleDateFormat("d/M", Locale.US);
        Calendar cal = Calendar.getInstance();
        for (int i = 0; i < d.length; i++) {
            Date date = cal.getTime();
            String label = (i == 0 ? "اليوم" : dayName.format(date)) + "  " + dayNum.format(date);
            TextView t = Ui.text(this, label, 13, Ui.SUB, false);
            t.setPadding(0, dp(10), 0, 0);
            card.addView(t);
            card.addView(Ui.bar(this, d[i][0] / (float) max, Ui.WIFI));
            card.addView(Ui.bar(this, d[i][1] / (float) max, Ui.MOBILE));
            LinearLayout row = Ui.hbox(this);
            row.addView(Ui.text(this, "واي فاي " + Fmt.bytes(d[i][0]), 12, Ui.WIFI, false), Ui.weight());
            row.addView(Ui.text(this, "بيانات " + Fmt.bytes(d[i][1]), 12, Ui.MOBILE, false), Ui.weight());
            card.addView(row);
            cal.add(Calendar.DAY_OF_MONTH, -1);
        }
    }

    // ------------------------------------------------------------------ TAB 2: networks

    private void buildNetworks() {
        final String s1 = Prefs.ssid1(this), s2 = Prefs.ssid2(this);
        String cur = NetUtil.currentSsid(this);

        if (!hasLocation()) {
            LinearLayout c = Ui.card(this);
            c.addView(Ui.text(this, "⚠️ أندرويد يحتاج إذن الموقع حتى يعرف التطبيق اسم شبكة الواي فاي ويبدّل بينها.", 14, Ui.TEXT, false));
            c.addView(Ui.button(this, "منح إذن الموقع", Ui.MOBILE, new View.OnClickListener() {
                @Override public void onClick(View v) { askPermissions(); }
            }));
            content.addView(c);
        } else if (!NetUtil.locationEnabled(this)) {
            LinearLayout c = Ui.card(this);
            c.addView(Ui.text(this, "⚠️ خدمة الموقع (GPS) مغلقة — شغّلها حتى يعمل التبديل التلقائي.", 14, Ui.TEXT, false));
            c.addView(Ui.button(this, "تشغيل الموقع", Ui.MOBILE, new View.OnClickListener() {
                @Override public void onClick(View v) { openSafe(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)); }
            }));
            content.addView(c);
        }

        SpeedTest.Result r1 = Prefs.lastResult(this, s1), r2 = Prefs.lastResult(this, s2);
        double max = 1;
        for (SpeedTest.Result r : new SpeedTest.Result[]{r1, r2}) {
            if (r != null) max = Math.max(max, Math.max(r.dl, r.ul));
        }
        content.addView(networkCard(s1, r1, s1.equals(cur), max));
        content.addView(networkCard(s2, r2, s2.equals(cur), max));

        LinearLayout auto = Ui.card(this);
        Switch sw = new Switch(this);
        sw.setText("التبديل التلقائي إلى الشبكة الأسرع");
        sw.setTextColor(Ui.TEXT);
        sw.setTextSize(15);
        sw.setChecked(Prefs.autoSwitch(this));
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                Prefs.setAutoSwitch(MainActivity.this, on);
                if (on) { MonitorService.start(MainActivity.this); Ui.toast(MainActivity.this, "تم التفعيل — أول فحص بعد دقيقة"); }
                else MonitorService.stop(MainActivity.this);
            }
        });
        auto.addView(sw);
        TextView iv = Ui.text(this, "الفحص كل:", 14, Ui.SUB, false);
        iv.setPadding(0, dp(10), 0, 0);
        auto.addView(iv);
        LinearLayout ints = Ui.hbox(this);
        final int[] opts = {15, 30, 60, 120};
        for (final int m : opts) {
            boolean sel = Prefs.intervalMin(this) == m;
            Button b = Ui.button(this, m < 60 ? m + " د" : (m / 60) + " س", sel ? Ui.ACCENT : Ui.CARD2, new View.OnClickListener() {
                @Override public void onClick(View v) { Prefs.setIntervalMin(MainActivity.this, m); showTab(TAB_NETS); }
            });
            b.setTextColor(sel ? Ui.BG : Ui.TEXT);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.WRAP, 1f);
            lp.setMargins(dp(3), 0, dp(3), 0);
            ints.addView(b, lp);
        }
        auto.addView(ints);
        content.addView(auto);

        LinearLayout act = Ui.card(this);
        act.addView(Ui.button(this, "⚡ اختبر الشبكتين الآن واختر الأسرع", Ui.ACCENT, new View.OnClickListener() {
            @Override public void onClick(View v) { runTest(true); }
        }));
        act.addView(Ui.button(this, "اختبر الشبكة الحالية فقط", Ui.WIFI, new View.OnClickListener() {
            @Override public void onClick(View v) { runTest(false); }
        }));
        progressTv = Ui.text(this, WifiSwitcher.isRunning() ? "يوجد فحص يعمل الآن…" : "", 14, Ui.ACCENT, true);
        progressTv.setPadding(0, dp(8), 0, 0);
        act.addView(progressTv);
        act.addView(Ui.button(this, "أسماء الشبكات وكلمات المرور", Ui.CARD2, new View.OnClickListener() {
            @Override public void onClick(View v) { networksDialog(); }
        }));
        act.addView(Ui.button(this, "فتح إعدادات الواي فاي", Ui.CARD2, new View.OnClickListener() {
            @Override public void onClick(View v) { openSafe(new Intent(Settings.ACTION_WIFI_SETTINGS)); }
        }));
        act.addView(Ui.button(this, "استثناء التطبيق من توفير البطارية", Ui.CARD2, new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                i.setData(Uri.parse("package:" + getPackageName()));
                openSafe(i);
            }
        }));
        for (int i = 2; i < act.getChildCount(); i++) {
            View v = act.getChildAt(i);
            if (v instanceof Button && v != progressTv) ((Button) v).setTextColor(Ui.TEXT);
        }
        content.addView(act);

        LinearLayout note = Ui.card(this);
        note.addView(Ui.text(this, "ملاحظات", 15, Ui.TEXT, true));
        note.addView(Ui.text(this,
                "• كل اختبار يستهلك تقريباً 20–100 ميجابايت من الواي فاي.\n"
                        + "• أثناء فحص الشبكة الثانية قد ينقطع الإنترنت لثوانٍ.\n"
                        + "• التطبيق ينتقل فقط إذا كانت الشبكة الأخرى أسرع بـ 10% على الأقل.\n"
                        + "• إذا رفض أندرويد الانتقال، أضف كلمة مرور الشبكتين من زر «أسماء الشبكات وكلمات المرور» أو سيصلك إشعار للانتقال بضغطة.",
                13, Ui.SUB, false));
        content.addView(note);

        LinearLayout logCard = Ui.card(this);
        logCard.addView(Ui.text(this, "سجل آخر الفحوصات", 15, Ui.TEXT, true));
        String log = Prefs.logText(this);
        logCard.addView(Ui.text(this, log.isEmpty() ? "لا يوجد بعد" : log, 12, Ui.SUB, false));
        content.addView(logCard);
    }

    private View networkCard(String ssid, SpeedTest.Result r, boolean connected, double max) {
        LinearLayout c = Ui.card(this);
        LinearLayout top = Ui.hbox(this);
        top.addView(Ui.text(this, "📶 " + ssid, 18, Ui.TEXT, true), Ui.weight());
        if (connected) top.addView(Ui.text(this, "● متصل الآن", 13, Ui.ACCENT, true));
        c.addView(top);
        if (r == null) {
            c.addView(Ui.text(this, "لم يتم القياس بعد — اضغط «اختبر الشبكتين الآن»", 13, Ui.SUB, false));
            return c;
        }
        LinearLayout row = Ui.hbox(this);
        row.setPadding(0, dp(6), 0, 0);
        row.addView(Ui.text(this, "↓ تنزيل\n" + Fmt.speed(r.dl), 17, Ui.ACCENT, true), Ui.weight());
        row.addView(Ui.text(this, "↑ رفع\n" + Fmt.speed(r.ul), 17, Ui.WIFI, true), Ui.weight());
        c.addView(row);
        c.addView(Ui.bar(this, (float) (r.dl / max), Ui.ACCENT));
        c.addView(Ui.bar(this, (float) (r.ul / max), Ui.WIFI));
        TextView meta = Ui.text(this, "زمن الاستجابة: " + r.pingMs + " ms   •   آخر قياس: " + Fmt.ago(r.time), 12, Ui.SUB, false);
        meta.setPadding(0, dp(6), 0, 0);
        c.addView(meta);
        return c;
    }

    private void runTest(final boolean both) {
        if (WifiSwitcher.isRunning()) { Ui.toast(this, "يوجد فحص يعمل الآن"); return; }
        if (progressTv != null) progressTv.setText("جاري البدء…");
        final WifiSwitcher.Listener l = new WifiSwitcher.Listener() {
            @Override public void onProgress(final String msg) {
                h.post(new Runnable() {
                    @Override public void run() { if (progressTv != null) progressTv.setText(msg); }
                });
            }
        };
        new Thread(new Runnable() {
            @Override public void run() {
                if (both) {
                    WifiSwitcher.runCheck(MainActivity.this, l);
                } else {
                    Network n = NetUtil.wifiNetwork(MainActivity.this);
                    if (n == null) {
                        l.onProgress("أنت غير متصل بالواي فاي");
                        return;
                    }
                    String ssid = NetUtil.currentSsid(MainActivity.this);
                    l.onProgress("قياس سرعة " + (ssid != null ? ssid : "الشبكة الحالية") + " …");
                    SpeedTest.Result r = SpeedTest.run(n);
                    if (r.ok) {
                        Prefs.saveResult(MainActivity.this, ssid, r);
                        String msg = (ssid != null ? ssid : "الشبكة") + ": ↓ " + Fmt.speed(r.dl) + "  ↑ " + Fmt.speed(r.ul);
                        Prefs.log(MainActivity.this, msg);
                        l.onProgress(msg);
                    } else {
                        l.onProgress("فشل القياس" + (r.error != null ? ": " + r.error : ""));
                        return;
                    }
                }
                h.postDelayed(new Runnable() {
                    @Override public void run() { if (tab == TAB_NETS) showTab(TAB_NETS); }
                }, 2500);
            }
        }).start();
    }

    private EditText field(LinearLayout parent, String hint, String value, boolean password) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setSingleLine(true);
        if (password) e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        parent.addView(e);
        return e;
    }

    private void networksDialog() {
        LinearLayout f = Ui.vbox(this);
        f.setPadding(dp(20), dp(8), dp(20), 0);
        final String s1 = Prefs.ssid1(this), s2 = Prefs.ssid2(this);
        f.addView(Ui.text(this, "الشبكة الأولى", 13, Ui.SUB, false));
        final EditText e1 = field(f, "اسم الشبكة", s1, false);
        final EditText p1 = field(f, "كلمة المرور (اختياري)", Prefs.pass(this, s1), true);
        f.addView(Ui.text(this, "الشبكة الثانية", 13, Ui.SUB, false));
        final EditText e2 = field(f, "اسم الشبكة", s2, false);
        final EditText p2 = field(f, "كلمة المرور (اختياري)", Prefs.pass(this, s2), true);
        f.addView(Ui.text(this, "الاسم يجب أن يطابق اسم الشبكة تماماً (حروف كبيرة/صغيرة ومسافات). كلمة المرور تُستخدم فقط إذا رفض أندرويد الانتقال للشبكة المحفوظة، وتُحفظ داخل الهاتف فقط.", 12, Ui.SUB, false));
        new AlertDialog.Builder(this)
                .setTitle("الشبكات")
                .setView(f)
                .setPositiveButton("حفظ", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        String n1 = e1.getText().toString().trim(), n2 = e2.getText().toString().trim();
                        if (n1.isEmpty() || n2.isEmpty()) { Ui.toast(MainActivity.this, "أدخل اسم الشبكتين"); return; }
                        Prefs.saveNetworks(MainActivity.this, n1, p1.getText().toString(), n2, p2.getText().toString());
                        showTab(TAB_NETS);
                    }
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    // ------------------------------------------------------------------ TAB 3: block

    private void buildBlock() {
        final long now = System.currentTimeMillis();
        List<FirewallStore.Rule> rules = FirewallStore.load(this);
        Set<String> active = FirewallStore.activePackages(this, now);
        rules = FirewallStore.load(this); // expired ones were removed

        LinearLayout st = Ui.card(this);
        st.addView(Ui.text(this, "🔒 حجب الإنترنت عن التطبيقات", 17, Ui.TEXT, true));
        st.addView(Ui.text(this, active.isEmpty() ? "لا يوجد تطبيق محجوب الآن" : "محجوب الآن: " + active.size() + " تطبيق",
                14, active.isEmpty() ? Ui.SUB : Ui.RED, true));
        st.addView(Ui.text(this, "يعمل عبر VPN محلي داخل هاتفك فقط (لا يرسل بياناتك لأي مكان). لا يمكن تشغيله مع VPN آخر في نفس الوقت.", 12, Ui.SUB, false));
        st.addView(Ui.button(this, "حجب مواقع التواصل الاجتماعي", Ui.RED, new View.OnClickListener() {
            @Override public void onClick(View v) {
                List<String> soc = new ArrayList<>();
                PackageManager pm = getPackageManager();
                for (String p : SOCIAL) {
                    try { pm.getApplicationInfo(p, 0); soc.add(p); } catch (PackageManager.NameNotFoundException ignored) {}
                }
                if (soc.isEmpty()) { Ui.toast(MainActivity.this, "لم أجد تطبيقات تواصل مثبتة"); return; }
                chooseDuration(soc, "مواقع التواصل (" + soc.size() + " تطبيق)");
            }
        }));
        if (!rules.isEmpty()) {
            Button all = Ui.button(this, "إعادة تفعيل الإنترنت لكل التطبيقات", Ui.CARD2, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    FirewallStore.clear(MainActivity.this);
                    FirewallVpnService.refresh(MainActivity.this);
                    Ui.toast(MainActivity.this, "تم إلغاء كل الحجب");
                    showTab(TAB_BLOCK);
                }
            });
            all.setTextColor(Ui.TEXT);
            st.addView(all);
        }
        content.addView(st);

        if (!rules.isEmpty()) {
            LinearLayout rc = Ui.card(this);
            rc.addView(Ui.text(this, "التطبيقات المحجوبة", 15, Ui.TEXT, true));
            PackageManager pm = getPackageManager();
            for (final FirewallStore.Rule r : rules) {
                LinearLayout row = Ui.hbox(this);
                row.setPadding(0, dp(8), 0, 0);
                LinearLayout texts = Ui.vbox(this);
                texts.addView(Ui.text(this, label(pm, r.pkg), 15, Ui.TEXT, true));
                texts.addView(Ui.text(this, FirewallStore.describe(r, now), 12,
                        FirewallStore.isActive(r, now) ? Ui.RED : Ui.SUB, false));
                row.addView(texts, Ui.weight());
                Button b = Ui.button(this, "تفعيل النت", Ui.ACCENT, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        FirewallStore.remove(MainActivity.this, r.pkg);
                        FirewallVpnService.refresh(MainActivity.this);
                        showTab(TAB_BLOCK);
                    }
                });
                row.addView(b, new LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP));
                rc.addView(row);
            }
            content.addView(rc);
        }

        LinearLayout lc = Ui.card(this);
        lc.addView(Ui.text(this, "اختر تطبيقاً لحجبه", 15, Ui.TEXT, true));
        final EditText searchBox = new EditText(this);
        searchBox.setHint("بحث…");
        searchBox.setSingleLine(true);
        searchBox.setTextColor(Ui.TEXT);
        searchBox.setHintTextColor(Ui.SUB);
        searchBox.setText(search);
        lc.addView(searchBox);
        final LinearLayout list = Ui.vbox(this);
        lc.addView(list);
        content.addView(lc);

        final Set<String> blockedNow = active;
        final Runnable render = new Runnable() {
            @Override public void run() { renderApps(list, blockedNow); }
        };
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { search = s.toString().trim(); render.run(); }
        });

        if (appsCache != null) {
            render.run();
        } else {
            list.addView(Ui.text(this, "جاري تحميل التطبيقات…", 13, Ui.SUB, false));
            async(new Callable<List<AppItem>>() {
                @Override public List<AppItem> call() { return loadApps(); }
            }, new Cb<List<AppItem>>() {
                @Override public void on(List<AppItem> items) { appsCache = items; render.run(); }
            });
        }
    }

    private String label(PackageManager pm, String pkg) {
        try { return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0))); }
        catch (Exception e) { return pkg; }
    }

    private List<AppItem> loadApps() {
        PackageManager pm = getPackageManager();
        List<AppItem> out = new ArrayList<>();
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            if (ai.packageName.equals(getPackageName())) continue;
            if (pm.getLaunchIntentForPackage(ai.packageName) == null) continue;
            if (pm.checkPermission(Manifest.permission.INTERNET, ai.packageName) != PackageManager.PERMISSION_GRANTED) continue;
            AppItem a = new AppItem();
            a.pkg = ai.packageName;
            a.name = String.valueOf(pm.getApplicationLabel(ai));
            try { a.icon = pm.getApplicationIcon(ai); } catch (Exception ignored) {}
            out.add(a);
        }
        Collections.sort(out, new Comparator<AppItem>() {
            @Override public int compare(AppItem x, AppItem y) { return x.name.compareToIgnoreCase(y.name); }
        });
        return out;
    }

    private void renderApps(LinearLayout list, Set<String> blocked) {
        list.removeAllViews();
        if (appsCache == null) return;
        String q = search.toLowerCase(Locale.ROOT);
        for (final AppItem a : appsCache) {
            if (!q.isEmpty() && !a.name.toLowerCase(Locale.ROOT).contains(q) && !a.pkg.contains(q)) continue;
            LinearLayout row = Ui.hbox(this);
            row.setPadding(0, dp(8), 0, dp(8));
            row.addView(icon(a.icon));
            TextView name = Ui.text(this, a.name, 15, Ui.TEXT, false);
            name.setPadding(dp(10), 0, dp(10), 0);
            row.addView(name, Ui.weight());
            if (blocked.contains(a.pkg)) row.addView(Ui.text(this, "🔒 محجوب", 13, Ui.RED, true));
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    List<String> one = new ArrayList<>();
                    one.add(a.pkg);
                    chooseDuration(one, a.name);
                }
            });
            list.addView(row);
        }
    }

    private View icon(Drawable d) {
        ImageView iv = new ImageView(this);
        if (d != null) iv.setImageDrawable(d);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(34), dp(34)));
        return iv;
    }

    private void chooseDuration(final List<String> pkgs, String title) {
        String[] opts = {"15 دقيقة", "30 دقيقة", "ساعة", "ساعتان", "3 ساعات",
                "حتى أعيد التفعيل بنفسي", "يومياً في وقت محدد…", "مدة مخصصة (بالدقائق)…"};
        final int[] mins = {15, 30, 60, 120, 180};
        new AlertDialog.Builder(this)
                .setTitle("حجب الإنترنت عن: " + title)
                .setItems(opts, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        if (w < 5) {
                            applyRules(pkgs, FirewallStore.UNTIL, System.currentTimeMillis() + mins[w] * 60_000L, 0, 0);
                        } else if (w == 5) {
                            applyRules(pkgs, FirewallStore.FOREVER, 0, 0, 0);
                        } else if (w == 6) {
                            pickDaily(pkgs);
                        } else {
                            customMinutes(pkgs);
                        }
                    }
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void pickDaily(final List<String> pkgs) {
        TimePickerDialog from = new TimePickerDialog(this, new TimePickerDialog.OnTimeSetListener() {
            @Override public void onTimeSet(TimePicker v, int hh, int mm) {
                final int fromMin = hh * 60 + mm;
                TimePickerDialog to = new TimePickerDialog(MainActivity.this, new TimePickerDialog.OnTimeSetListener() {
                    @Override public void onTimeSet(TimePicker v2, int h2, int m2) {
                        int toMin = h2 * 60 + m2;
                        if (toMin == fromMin) { Ui.toast(MainActivity.this, "وقت البداية والنهاية متساويان"); return; }
                        applyRules(pkgs, FirewallStore.DAILY, 0, fromMin, toMin);
                    }
                }, 7, 0, true);
                to.setTitle("وقت انتهاء الحجب (رجوع النت)");
                to.show();
            }
        }, 23, 0, true);
        from.setTitle("وقت بدء الحجب");
        from.show();
    }

    private void customMinutes(final List<String> pkgs) {
        final EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setHint("عدد الدقائق");
        e.setGravity(Gravity.CENTER);
        new AlertDialog.Builder(this)
                .setTitle("مدة الحجب")
                .setView(e)
                .setPositiveButton("حجب", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        long m;
                        try { m = Long.parseLong(e.getText().toString().trim()); } catch (Exception ex) { m = 0; }
                        if (m <= 0) { Ui.toast(MainActivity.this, "أدخل رقماً صحيحاً"); return; }
                        applyRules(pkgs, FirewallStore.UNTIL, System.currentTimeMillis() + m * 60_000L, 0, 0);
                    }
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void applyRules(final List<String> pkgs, final int mode, final long until, final int from, final int to) {
        ensureVpn(new Runnable() {
            @Override public void run() {
                for (String p : pkgs) {
                    FirewallStore.Rule r = new FirewallStore.Rule();
                    r.pkg = p;
                    r.mode = mode;
                    r.until = until;
                    r.from = from;
                    r.to = to;
                    FirewallStore.put(MainActivity.this, r);
                }
                FirewallVpnService.refresh(MainActivity.this);
                Ui.toast(MainActivity.this, "تم الحجب ✅");
                h.postDelayed(new Runnable() {
                    @Override public void run() { if (tab == TAB_BLOCK) showTab(TAB_BLOCK); }
                }, 400);
            }
        });
    }

    private void ensureVpn(Runnable r) {
        Intent i = VpnService.prepare(this);
        if (i == null) { r.run(); return; }
        pendingVpn = r;
        Ui.toast(this, "اسمح بإنشاء الـ VPN المحلي حتى يعمل الحجب");
        try { startActivityForResult(i, REQ_VPN); }
        catch (Exception e) { Ui.toast(this, "تعذر طلب إذن الـ VPN"); pendingVpn = null; }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VPN) {
            Runnable r = pendingVpn;
            pendingVpn = null;
            if (resultCode == RESULT_OK && r != null) r.run();
            else Ui.toast(this, "لم يتم السماح — الحجب لن يعمل بدون هذا الإذن");
        }
    }

    // ------------------------------------------------------------------ TAB 4: app statistics

    private void buildApps() {
        LinearLayout head = Ui.card(this);
        head.addView(Ui.text(this, "📊 إحصائيات التطبيقات", 17, Ui.TEXT, true));
        head.addView(Ui.text(this, "للاطلاع فقط — كم استهلك كل تطبيق من الواي فاي وبيانات الهاتف.", 13, Ui.SUB, false));
        content.addView(head);
        addUsageWarning();
        content.addView(periodSelector(TAB_APPS));

        final LinearLayout list = Ui.card(this);
        list.addView(Ui.text(this, "جاري التحميل…", 13, Ui.SUB, false));
        content.addView(list);

        final long[] r = UsageRepo.range(period);
        async(new Callable<List<UsageRepo.AppUsage>>() {
            @Override public List<UsageRepo.AppUsage> call() { return UsageRepo.perApp(MainActivity.this, r[0], r[1]); }
        }, new Cb<List<UsageRepo.AppUsage>>() {
            @Override public void on(List<UsageRepo.AppUsage> items) {
                list.removeAllViews();
                list.addView(Ui.text(MainActivity.this, "الأكثر استهلاكاً — " + UsageRepo.periodName(period), 15, Ui.TEXT, true));
                if (items == null || items.isEmpty()) {
                    list.addView(Ui.text(MainActivity.this, "لا توجد بيانات لهذه الفترة", 13, Ui.SUB, false));
                    return;
                }
                long max = Math.max(1, items.get(0).total());
                int shown = 0;
                for (UsageRepo.AppUsage a : items) {
                    if (shown++ >= 150) break;
                    list.addView(appUsageRow(a, max));
                }
            }
        });
    }

    private View appUsageRow(UsageRepo.AppUsage a, long max) {
        LinearLayout box = Ui.vbox(this);
        box.setPadding(0, dp(10), 0, 0);
        LinearLayout row = Ui.hbox(this);
        row.addView(icon(a.icon));
        LinearLayout texts = Ui.vbox(this);
        texts.setPadding(dp(10), 0, dp(10), 0);
        texts.addView(Ui.text(this, a.name, 15, Ui.TEXT, false));
        texts.addView(Ui.text(this, "واي فاي " + Fmt.bytes(a.wifi) + "  •  بيانات " + Fmt.bytes(a.mobile), 12, Ui.SUB, false));
        row.addView(texts, Ui.weight());
        row.addView(Ui.text(this, Fmt.bytes(a.total()), 14, Ui.ACCENT, true));
        box.addView(row);
        box.addView(Ui.bar(this, a.total() / (float) max, Ui.ACCENT));
        return box;
    }

    @Override
    protected void onDestroy() {
        bg.shutdownNow();
        super.onDestroy();
    }
}
