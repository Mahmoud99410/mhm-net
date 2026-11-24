package com.mhm.net;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
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
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_VPN = 10, REQ_PERM = 11;
    private static final int TAB_HOME = 0, TAB_NETS = 1, TAB_BLOCK = 2, TAB_APPS = 3;
    private static final double MB = 1024.0 * 1024.0;

    private LinearLayout content;
    private ScrollView scroll;
    private final LinearLayout[] navItems = new LinearLayout[4];
    private final TextView[] navIcons = new TextView[4], navLabels = new TextView[4];
    private int tab = TAB_HOME, period = UsageRepo.TODAY;
    private int gen = 0;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();

    // live speed
    private TextView hdrLive, heroDl, heroUl, heroNet, heroStatus, heroBtn, heroLive, heroLink, progressTv;
    private SparkView spark;
    private long lastRx = -1, lastTx, lastT;

    // quick speed test shown on the home screen
    private volatile boolean testing;
    private volatile int testPhase;
    private volatile double testDl, testUl;
    private SpeedTest.Result quick;
    private String quickLabel, quickError;
    private boolean autoTested;

    private Runnable pendingVpn;
    private List<AppItem> appsCache;
    private final Map<String, AppItem> appsByPkg = new HashMap<>();
    private String search = "";

    private static class AppItem {
        String pkg, name;
        Drawable icon;
    }

    private interface Cb<T> { void on(T t); }

    // ================================================================== lifecycle

    @Override
    protected void onCreate(Bundle b) {
        Ui.apply(this);
        super.onCreate(b);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.NAV);
        if (!Ui.dark && Build.VERSION.SDK_INT >= 23) {
            int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }

        LinearLayout root = Ui.vbox(this);
        root.setBackgroundColor(Ui.BG);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        // ---- header
        LinearLayout header = Ui.hbox(this);
        header.setPadding(dp(18), dp(16), dp(14), dp(6));
        LinearLayout titles = Ui.vbox(this);
        TextView title = Ui.text(this, "MHM NET", 24, Ui.ACCENT, true);
        title.setLetterSpacing(0.04f);
        titles.addView(title);
        titles.addView(Ui.text(this, "مراقبة وإدارة الإنترنت", 12, Ui.SUB, false));
        header.addView(titles, Ui.weight());

        hdrLive = Ui.text(this, "↓ --  ↑ -- MB/s", 12, Ui.TEXT, true);
        hdrLive.setBackground(Ui.round(this, Ui.CARD, 20));
        hdrLive.setPadding(dp(12), dp(6), dp(12), dp(6));
        hdrLive.setTextDirection(View.TEXT_DIRECTION_LTR);
        header.addView(hdrLive);

        TextView gear = Ui.text(this, "⚙", 20, Ui.SUB, false);
        gear.setGravity(Gravity.CENTER);
        gear.setBackground(Ui.round(this, Ui.CARD, 20));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(dp(40), dp(40));
        glp.setMarginStart(dp(8));
        gear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { settingsDialog(); }
        });
        header.addView(gear, glp);
        root.addView(header);

        // ---- content
        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        content = Ui.vbox(this);
        content.setPadding(dp(14), 0, dp(14), dp(24));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1f));

        // ---- bottom navigation
        LinearLayout nav = Ui.hbox(this);
        GradientDrawable navBg = new GradientDrawable();
        navBg.setColor(Ui.NAV);
        nav.setBackground(navBg);
        nav.setElevation(dp(8));
        nav.setPadding(dp(6), dp(6), dp(6), dp(6));
        String[] icons = {"⌂", "📶", "🛡", "📊"};
        String[] names = {"الرئيسية", "الشبكات", "الحظر", "التطبيقات"};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            LinearLayout item = Ui.vbox(this);
            item.setGravity(Gravity.CENTER);
            item.setPadding(0, dp(6), 0, dp(6));
            TextView ic = Ui.text(this, icons[i], 18, Ui.SUB, false);
            ic.setGravity(Gravity.CENTER);
            TextView lb = Ui.text(this, names[i], 11, Ui.SUB, true);
            lb.setGravity(Gravity.CENTER);
            item.addView(ic);
            item.addView(lb);
            item.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { showTab(idx); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.WRAP, 1f);
            lp.setMargins(dp(3), 0, dp(3), 0);
            nav.addView(item, lp);
            navItems[i] = item;
            navIcons[i] = ic;
            navLabels[i] = lb;
        }
        root.addView(nav);
        setContentView(root);

        spark = new SparkView(this);

        tab = getIntent().getIntExtra("tab", TAB_HOME);
        askPermissions();
        bg.execute(new Runnable() {
            @Override public void run() {
                GroupStore.cleanProtected(MainActivity.this);
                SwitchJob.sync(MainActivity.this);
                if (!FirewallStore.load(MainActivity.this).isEmpty() && VpnService.prepare(MainActivity.this) == null) {
                    FirewallVpnService.refresh(MainActivity.this);
                }
            }
        });
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

    @Override
    protected void onDestroy() {
        bg.shutdownNow();
        super.onDestroy();
    }

    private final Runnable liveTick = new Runnable() {
        @Override public void run() {
            long now = SystemClock.elapsedRealtime();
            long rx = TrafficStats.getTotalRxBytes(), tx = TrafficStats.getTotalTxBytes();
            if (lastRx >= 0 && now > lastT) {
                double sec = (now - lastT) / 1000.0;
                double d = Math.max(0, (rx - lastRx) / sec), u = Math.max(0, (tx - lastTx) / sec);
                spark.push(d, u);
                if (heroLive != null) {
                    heroLive.setText(String.format(Locale.US, "↓ %.2f  ↑ %.2f MB/s", d / MB, u / MB));
                    heroNet.setText(NetUtil.activeLabel(MainActivity.this));
                    heroLink.setText(linkText());
                }
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
        heroDl = heroUl = heroNet = heroStatus = heroBtn = heroLive = heroLink = progressTv = null;
        for (int k = 0; k < navItems.length; k++) {
            boolean sel = k == i;
            navItems[k].setBackground(sel ? Ui.round(this, Ui.alpha(Ui.ACCENT, 0x24), 16) : null);
            navIcons[k].setTextColor(sel ? Ui.ACCENT : Ui.SUB);
            navLabels[k].setTextColor(sel ? Ui.ACCENT : Ui.SUB);
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

    private AlertDialog.Builder dialog() { return new AlertDialog.Builder(this, Ui.dialogTheme()); }

    // ================================================================== settings (theme)

    private void settingsDialog() {
        String[] opts = {"حسب نظام الجهاز", "الوضع الفاتح ☀️", "الوضع الداكن 🌙"};
        dialog().setTitle("مظهر التطبيق")
                .setSingleChoiceItems(opts, Ui.themeMode(this), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        d.dismiss();
                        if (w != Ui.themeMode(MainActivity.this)) {
                            Ui.setThemeMode(MainActivity.this, w);
                            getIntent().putExtra("tab", tab);
                            recreate();
                        }
                    }
                })
                .setNegativeButton("إغلاق", null)
                .show();
    }

    // ================================================================== permissions

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

    private void warning(String msg, String btn, View.OnClickListener cl) {
        LinearLayout c = Ui.card(this);
        GradientDrawable g = Ui.round(this, Ui.alpha(Ui.MOBILE, 0x1F), 18);
        g.setStroke(dp(1), Ui.alpha(Ui.MOBILE, 0x66));
        c.setBackground(g);
        c.addView(Ui.text(this, "⚠️  " + msg, 14, Ui.TEXT, false));
        c.addView(Ui.button(this, btn, Ui.MOBILE, cl));
        content.addView(c);
    }

    private void addUsageWarning() {
        if (UsageRepo.hasAccess(this)) return;
        warning("لعرض استهلاك الإنترنت فعّل إذن «الوصول إلى بيانات الاستخدام» لتطبيق MHM NET.", "منح الإذن",
                new View.OnClickListener() {
                    @Override public void onClick(View v) { openSafe(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)); }
                });
    }

    private void openSafe(Intent i) {
        try { startActivity(i); } catch (Exception e) { Ui.toast(this, "تعذر فتح الإعدادات"); }
    }

    // ================================================================== segmented period control

    private View periodSelector(final int targetTab) {
        LinearLayout row = Ui.hbox(this);
        row.setBackground(Ui.round(this, Ui.CARD2, 14));
        row.setPadding(dp(4), dp(4), dp(4), dp(4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP);
        lp.topMargin = dp(10);
        row.setLayoutParams(lp);
        String[] names = {"اليوم", "الأسبوع", "الشهر"};
        for (int i = 0; i < 3; i++) {
            final int p = i;
            boolean sel = i == period;
            TextView t = Ui.text(this, names[i], 14, sel ? Ui.ON_ACCENT : Ui.SUB, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(9), 0, dp(9));
            if (sel) t.setBackground(Ui.round(this, Ui.ACCENT, 11));
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { period = p; showTab(targetTab); }
            });
            row.addView(t, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
        }
        return row;
    }

    // ================================================================== TAB 1: home

    private void buildHome() {
        addUsageWarning();

        // ---- hero: real internet speed (speed test) + live usage
        LinearLayout hero = Ui.vbox(this);
        hero.setBackground(Ui.gradient(this, Ui.HERO1, Ui.HERO2, 22));
        hero.setPadding(dp(18), dp(16), dp(18), dp(14));
        hero.setElevation(dp(4));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP);
        hlp.topMargin = dp(8);
        hero.setLayoutParams(hlp);

        LinearLayout top = Ui.hbox(this);
        top.addView(Ui.text(this, "⚡ سرعة الإنترنت", 15, 0xFFFFFFFF, true), Ui.weight());
        heroNet = Ui.text(this, NetUtil.activeLabel(this), 11, 0xFFFFFFFF, true);
        heroNet.setBackground(Ui.round(this, 0x33FFFFFF, 20));
        heroNet.setPadding(dp(10), dp(4), dp(10), dp(4));
        heroNet.setMaxLines(1);
        top.addView(heroNet);
        hero.addView(top);

        LinearLayout nums = Ui.hbox(this);
        nums.setPadding(0, dp(12), 0, 0);
        heroDl = Ui.text(this, "--", 40, 0xFFFFFFFF, true);
        heroUl = Ui.text(this, "--", 40, 0xFFFDE68A, true);
        nums.addView(speedColumn("↓ تنزيل", heroDl), Ui.weight());
        nums.addView(speedColumn("↑ رفع", heroUl), Ui.weight());
        hero.addView(nums);

        heroStatus = Ui.text(this, "", 12, 0xE6FFFFFF, false);
        heroStatus.setPadding(0, dp(4), 0, 0);
        hero.addView(heroStatus);

        heroBtn = Ui.text(this, "↻ قياس الآن", 14, 0xFFFFFFFF, true);
        heroBtn.setGravity(Gravity.CENTER);
        heroBtn.setBackground(Ui.round(this, 0x33FFFFFF, 14));
        heroBtn.setPadding(0, dp(10), 0, dp(10));
        heroBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startQuickTest(); }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP);
        blp.topMargin = dp(10);
        hero.addView(heroBtn, blp);

        View div = new View(this);
        div.setBackgroundColor(0x33FFFFFF);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(Ui.MATCH, dp(1));
        dlp.topMargin = dp(14);
        hero.addView(div, dlp);

        LinearLayout liveRow = Ui.hbox(this);
        liveRow.setPadding(0, dp(10), 0, 0);
        liveRow.addView(Ui.text(this, "الاستخدام الآن", 12, 0xCCFFFFFF, true), Ui.weight());
        heroLive = Ui.text(this, "↓ 0.00  ↑ 0.00 MB/s", 12, 0xFFFFFFFF, true);
        heroLive.setTextDirection(View.TEXT_DIRECTION_LTR);
        liveRow.addView(heroLive);
        hero.addView(liveRow);

        if (spark.getParent() != null) ((ViewGroup) spark.getParent()).removeView(spark);
        spark.setColors(0xFFFFFFFF, 0xFFFDE68A);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(Ui.MATCH, dp(56));
        slp.topMargin = dp(6);
        hero.addView(spark, slp);

        heroLink = Ui.text(this, "", 11, 0xCCFFFFFF, false);
        heroLink.setPadding(0, dp(6), 0, 0);
        hero.addView(heroLink);
        content.addView(hero);
        updateHero();
        maybeAutoTest();

        // ---- usage
        content.addView(Ui.section(this, "استهلاك الإنترنت"));
        content.addView(periodSelector(TAB_HOME));

        LinearLayout tiles = Ui.hbox(this);
        final LinearLayout wifiTile = Ui.card(this), mobTile = Ui.card(this);
        LinearLayout.LayoutParams t1 = new LinearLayout.LayoutParams(0, Ui.WRAP, 1f);
        t1.setMarginEnd(dp(6));
        t1.topMargin = dp(12);
        LinearLayout.LayoutParams t2 = new LinearLayout.LayoutParams(0, Ui.WRAP, 1f);
        t2.setMarginStart(dp(6));
        t2.topMargin = dp(12);
        tiles.addView(wifiTile, t1);
        tiles.addView(mobTile, t2);
        fillTile(wifiTile, "📶", "الواي فاي", Ui.WIFI, -1, -1);
        fillTile(mobTile, "📱", "بيانات الهاتف", Ui.MOBILE, -1, -1);
        content.addView(tiles);

        final LinearLayout share = Ui.card(this);
        content.addView(share);

        final LinearLayout days = Ui.card(this);
        days.addView(Ui.text(this, "آخر 7 أيام", 15, Ui.TEXT, true));
        content.addView(days);

        final long[] r = UsageRepo.range(period);
        async(new Callable<UsageRepo.Totals>() {
            @Override public UsageRepo.Totals call() { return UsageRepo.totals(MainActivity.this, r[0], r[1]); }
        }, new Cb<UsageRepo.Totals>() {
            @Override public void on(UsageRepo.Totals t) {
                if (t == null) t = new UsageRepo.Totals();
                fillTile(wifiTile, "📶", "الواي فاي", Ui.WIFI, t.wifiRx, t.wifiTx);
                fillTile(mobTile, "📱", "بيانات الهاتف", Ui.MOBILE, t.mobRx, t.mobTx);
                fillShare(share, t.wifiRx + t.wifiTx, t.mobRx + t.mobTx);
            }
        });
        async(new Callable<long[][]>() {
            @Override public long[][] call() { return UsageRepo.daily(MainActivity.this, 7); }
        }, new Cb<long[][]>() {
            @Override public void on(long[][] d) { fillDays(days, d); }
        });
    }

    private static String mbs(double bps) { return String.format(Locale.US, "%.2f", bps / MB); }

    private String linkText() {
        if (NetUtil.wifiNetwork(this) != null) {
            int mbps = NetUtil.wifiLinkMbps(this);
            if (mbps > 0) return String.format(Locale.US, "سرعة الاتصال بالراوتر: %d Mbps (≈ %.1f MB/s كحد أقصى)", mbps, mbps / 8.0);
            return "";
        }
        int[] est = NetUtil.cellularEstimateKbps(this);
        if (est != null && est[0] > 0) {
            return String.format(Locale.US, "تقدير أندرويد لبيانات الهاتف: ↓ %.1f MB/s  ↑ %.1f MB/s", est[0] / 8192.0, est[1] / 8192.0);
        }
        return "";
    }

    /** Refreshes the hero card and the header pill from the current test state. */
    private void updateHero() {
        if (testing) {
            hdrLive.setText("↓ " + mbs(testDl) + "  ↑ " + mbs(testUl) + " MB/s");
        } else if (quick != null) {
            hdrLive.setText("↓ " + mbs(quick.dl) + "  ↑ " + mbs(quick.ul) + " MB/s");
        }
        if (heroDl == null) return;
        if (testing) {
            heroDl.setText(testPhase >= SpeedTest.PHASE_DOWN ? mbs(testDl) : "…");
            heroUl.setText(testPhase >= SpeedTest.PHASE_UP ? mbs(testUl) : "…");
            String[] ph = {"جاري قياس الاستجابة…", "جاري قياس سرعة التنزيل…", "جاري قياس سرعة الرفع…"};
            heroStatus.setText(ph[Math.max(0, Math.min(2, testPhase))]);
            heroBtn.setText("⏳ جاري القياس…");
            heroBtn.setEnabled(false);
            heroBtn.setAlpha(0.7f);
            return;
        }
        heroBtn.setEnabled(true);
        heroBtn.setAlpha(1f);
        heroBtn.setText("↻ إعادة القياس");
        if (quick != null) {
            heroDl.setText(mbs(quick.dl));
            heroUl.setText(mbs(quick.ul));
            heroStatus.setText("آخر قياس " + Fmt.ago(quick.time) + "  •  الاستجابة " + quick.pingMs + " ms"
                    + (quickLabel != null ? "  •  " + quickLabel : ""));
        } else {
            heroDl.setText("--");
            heroUl.setText("--");
            heroBtn.setText("↻ قياس الآن");
            heroStatus.setText(quickError != null ? "فشل القياس: " + quickError
                    : NetUtil.wifiNetwork(this) == null && NetUtil.cellularNetwork(this) != null
                    ? "على بيانات الهاتف لا يتم القياس تلقائياً لتوفير الباقة (يستهلك حوالي 20–40 ميجا)"
                    : "اضغط «قياس الآن» لمعرفة سرعة الإنترنت");
        }
    }

    /** On Wi-Fi the speed is measured automatically when the app opens (and again after 5 minutes). */
    private void maybeAutoTest() {
        if (testing || NetUtil.wifiNetwork(this) == null) return;
        if (quick == null || System.currentTimeMillis() - quick.time > 5 * 60_000L || !autoTested) {
            autoTested = true;
            h.postDelayed(new Runnable() {
                @Override public void run() { if (tab == TAB_HOME) startQuickTest(); }
            }, 600);
        }
    }

    private void startQuickTest() {
        if (testing) return;
        if (WifiSwitcher.isRunning()) { Ui.toast(this, "يوجد فحص للشبكات يعمل الآن"); return; }
        final Network n = NetUtil.internetNetwork(this);
        if (n == null) { quickError = "أنت غير متصل بالإنترنت"; quick = null; updateHero(); return; }
        final boolean wifi = NetUtil.isWifi(this, n);
        final String ssid = wifi ? NetUtil.currentSsid(this) : null;
        final String label = NetUtil.activeLabel(this);
        testing = true;
        testPhase = SpeedTest.PHASE_PING;
        testDl = testUl = 0;
        quickError = null;
        updateHero();
        final Runnable refresh = new Runnable() { @Override public void run() { updateHero(); } };
        new Thread(new Runnable() {
            @Override public void run() {
                final SpeedTest.Result r = SpeedTest.run(n, true, new SpeedTest.Progress() {
                    @Override public void update(int phase, double bps) {
                        testPhase = phase;
                        if (phase == SpeedTest.PHASE_DOWN) testDl = bps;
                        if (phase == SpeedTest.PHASE_UP) testUl = bps;
                        h.removeCallbacks(refresh);
                        h.post(refresh);
                    }
                });
                if (r.ok && wifi && ssid != null) Prefs.saveResult(MainActivity.this, ssid, r);
                h.post(new Runnable() {
                    @Override public void run() {
                        testing = false;
                        if (r.ok) { quick = r; quickLabel = label; }
                        else { quickError = r.error != null ? r.error : "تعذر الاتصال بخادم القياس"; }
                        updateHero();
                    }
                });
            }
        }).start();
    }

    private View speedColumn(String label, TextView value) {
        LinearLayout col = Ui.vbox(this);
        col.addView(Ui.text(this, label, 13, 0xCCFFFFFF, true));
        LinearLayout row = Ui.hbox(this);
        row.setGravity(Gravity.BOTTOM);
        value.setTextDirection(View.TEXT_DIRECTION_LTR);
        row.addView(value);
        TextView unit = Ui.text(this, " MB/s", 13, 0xCCFFFFFF, true);
        unit.setPadding(dp(4), 0, 0, dp(8));
        row.addView(unit);
        col.addView(row);
        return col;
    }

    private void fillTile(LinearLayout tile, String icon, String title, int color, long rx, long tx) {
        tile.removeAllViews();
        LinearLayout top = Ui.hbox(this);
        TextView ic = Ui.text(this, icon, 16, color, false);
        ic.setGravity(Gravity.CENTER);
        ic.setBackground(Ui.round(this, Ui.alpha(color, 0x26), 12));
        top.addView(ic, new LinearLayout.LayoutParams(dp(36), dp(36)));
        TextView tt = Ui.text(this, title, 13, Ui.SUB, true);
        tt.setPadding(dp(8), 0, 0, 0);
        top.addView(tt);
        tile.addView(top);
        TextView total = Ui.text(this, rx < 0 ? "…" : Fmt.bytes(rx + tx), 22, Ui.TEXT, true);
        total.setPadding(0, dp(10), 0, 0);
        total.setTextDirection(View.TEXT_DIRECTION_LTR);
        total.setGravity(Gravity.START);
        tile.addView(total);
        if (rx >= 0) {
            tile.addView(Ui.text(this, "↓ " + Fmt.bytes(rx), 12, Ui.SUB, false));
            tile.addView(Ui.text(this, "↑ " + Fmt.bytes(tx), 12, Ui.SUB, false));
        }
        tile.addView(Ui.text(this, UsageRepo.periodName(period), 11, color, true));
    }

    private void fillShare(LinearLayout card, long wifi, long mob) {
        card.removeAllViews();
        card.addView(Ui.text(this, "توزيع الاستهلاك — " + UsageRepo.periodName(period), 14, Ui.TEXT, true));
        card.addView(Ui.split(this, wifi, mob, Ui.WIFI, Ui.MOBILE));
        long total = wifi + mob;
        int pw = total == 0 ? 0 : Math.round(wifi * 100f / total);
        LinearLayout leg = Ui.hbox(this);
        leg.setPadding(0, dp(8), 0, 0);
        leg.addView(Ui.text(this, "● واي فاي " + pw + "%", 12, Ui.WIFI, true), Ui.weight());
        leg.addView(Ui.text(this, "● بيانات " + (total == 0 ? 0 : 100 - pw) + "%", 12, Ui.MOBILE, true));
        card.addView(leg);
        card.addView(Ui.text(this, "المجموع: " + Fmt.bytes(total), 12, Ui.SUB, false));
    }

    private void fillDays(LinearLayout card, long[][] d) {
        card.removeAllViews();
        LinearLayout top = Ui.hbox(this);
        top.addView(Ui.text(this, "آخر 7 أيام", 15, Ui.TEXT, true), Ui.weight());
        top.addView(Ui.text(this, "● واي فاي  ", 11, Ui.WIFI, true));
        top.addView(Ui.text(this, "● بيانات", 11, Ui.MOBILE, true));
        card.addView(top);
        if (d == null) return;
        long max = 1;
        for (long[] x : d) max = Math.max(max, Math.max(x[0], x[1]));
        SimpleDateFormat dayName = new SimpleDateFormat("EEEE", new Locale("ar"));
        SimpleDateFormat dayNum = new SimpleDateFormat("d/M", Locale.US);
        Calendar cal = Calendar.getInstance();
        for (int i = 0; i < d.length; i++) {
            Date date = cal.getTime();
            LinearLayout row = Ui.hbox(this);
            row.setPadding(0, dp(12), 0, 0);
            row.addView(Ui.text(this, (i == 0 ? "اليوم" : dayName.format(date)) + "  " + dayNum.format(date), 13, Ui.TEXT, true), Ui.weight());
            row.addView(Ui.text(this, Fmt.bytes(d[i][0] + d[i][1]), 12, Ui.SUB, true));
            card.addView(row);
            card.addView(Ui.bar(this, d[i][0] / (float) max, Ui.WIFI));
            card.addView(Ui.bar(this, d[i][1] / (float) max, Ui.MOBILE));
            cal.add(Calendar.DAY_OF_MONTH, -1);
        }
    }

    // ================================================================== TAB 2: networks

    private void buildNetworks() {
        final String s1 = Prefs.ssid1(this), s2 = Prefs.ssid2(this);
        String cur = NetUtil.currentSsid(this);

        if (!hasLocation()) {
            warning("أندرويد يحتاج إذن الموقع حتى يعرف التطبيق اسم شبكة الواي فاي ويبدّل بينها.", "منح إذن الموقع",
                    new View.OnClickListener() { @Override public void onClick(View v) { askPermissions(); } });
        } else if (!NetUtil.locationEnabled(this)) {
            warning("خدمة الموقع (GPS) مغلقة — شغّلها حتى يعمل التبديل التلقائي.", "تشغيل الموقع",
                    new View.OnClickListener() { @Override public void onClick(View v) { openSafe(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)); } });
        }

        String sug = Prefs.p(this).getString("suggest", null);
        if (sug != null && sug.equals(cur)) {
            Prefs.p(this).edit().remove("suggest").apply();
        } else if (sug != null) {
            warning("الشبكة الأسرع الآن هي «" + sug + "» لكن أندرويد منع الانتقال التلقائي.", "الانتقال إلى " + sug,
                    new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            openSafe(new Intent(Build.VERSION.SDK_INT >= 29 ? Settings.Panel.ACTION_WIFI : Settings.ACTION_WIFI_SETTINGS));
                        }
                    });
        }

        content.addView(Ui.section(this, "شبكاتك"));
        SpeedTest.Result r1 = Prefs.lastResult(this, s1), r2 = Prefs.lastResult(this, s2);
        double max = 1;
        for (SpeedTest.Result r : new SpeedTest.Result[]{r1, r2}) {
            if (r != null) max = Math.max(max, Math.max(r.dl, r.ul));
        }
        String fastest = null;
        if (r1 != null && r2 != null) fastest = r1.score() >= r2.score() ? s1 : s2;
        content.addView(networkCard(s1, r1, s1.equals(cur), s1.equals(fastest), max));
        content.addView(networkCard(s2, r2, s2.equals(cur), s2.equals(fastest), max));

        LinearLayout act = Ui.card(this);
        act.addView(Ui.button(this, "⚡ اختبر الشبكتين الآن واختر الأسرع", Ui.ACCENT, new View.OnClickListener() {
            @Override public void onClick(View v) { runTest(true); }
        }));
        act.addView(Ui.button(this, "اختبر الشبكة الحالية فقط", Ui.CARD2, new View.OnClickListener() {
            @Override public void onClick(View v) { runTest(false); }
        }));
        progressTv = Ui.text(this, WifiSwitcher.isRunning() ? "يوجد فحص يعمل الآن…" : "", 14, Ui.ACCENT, true);
        progressTv.setPadding(0, dp(8), 0, 0);
        act.addView(progressTv);
        content.addView(act);

        content.addView(Ui.section(this, "التبديل التلقائي"));
        LinearLayout auto = Ui.card(this);
        Switch sw = new Switch(this);
        sw.setText("التبديل التلقائي إلى الشبكة الأسرع");
        sw.setTextColor(Ui.TEXT);
        sw.setTextSize(15);
        sw.setThumbTintList(Ui.tint(Ui.ACCENT));
        sw.setChecked(Prefs.autoSwitch(this));
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean on) {
                Prefs.setAutoSwitch(MainActivity.this, on);
                SwitchJob.sync(MainActivity.this);
                if (on) Ui.toast(MainActivity.this, "تم التفعيل — يعمل في الخلفية بدون إشعارات");
            }
        });
        auto.addView(sw);
        TextView iv = Ui.text(this, "الفحص كل:", 13, Ui.SUB, true);
        iv.setPadding(0, dp(12), 0, dp(4));
        auto.addView(iv);
        LinearLayout ints = Ui.hbox(this);
        ints.setBackground(Ui.round(this, Ui.CARD2, 14));
        ints.setPadding(dp(4), dp(4), dp(4), dp(4));
        final int[] opts = {15, 30, 60, 120};
        for (final int m : opts) {
            boolean sel = Prefs.intervalMin(this) == m;
            TextView t = Ui.text(this, m < 60 ? m + " دقيقة" : (m / 60) + (m == 60 ? " ساعة" : " ساعتان"), 13, sel ? Ui.ON_ACCENT : Ui.SUB, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(9), 0, dp(9));
            if (sel) t.setBackground(Ui.round(this, Ui.ACCENT, 11));
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Prefs.setIntervalMin(MainActivity.this, m);
                    SwitchJob.sync(MainActivity.this);
                    showTab(TAB_NETS);
                }
            });
            ints.addView(t, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
        }
        auto.addView(ints);
        auto.addView(Ui.button(this, "أسماء الشبكات وكلمات المرور", Ui.CARD2, new View.OnClickListener() {
            @Override public void onClick(View v) { networksDialog(); }
        }));
        auto.addView(Ui.button(this, "فتح إعدادات الواي فاي", Ui.CARD2, new View.OnClickListener() {
            @Override public void onClick(View v) { openSafe(new Intent(Settings.ACTION_WIFI_SETTINGS)); }
        }));
        auto.addView(Ui.button(this, "استثناء التطبيق من توفير البطارية", Ui.CARD2, new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                i.setData(Uri.parse("package:" + getPackageName()));
                openSafe(i);
            }
        }));
        content.addView(auto);

        LinearLayout note = Ui.card(this);
        note.addView(Ui.text(this, "ملاحظات", 14, Ui.TEXT, true));
        note.addView(Ui.text(this,
                "• كل اختبار يستهلك تقريباً 20–100 ميجابايت من الواي فاي.\n"
                        + "• أثناء فحص الشبكة الثانية قد ينقطع الإنترنت لثوانٍ.\n"
                        + "• التطبيق ينتقل فقط إذا كانت الشبكة الأخرى أسرع بـ 10% على الأقل.\n"
                        + "• إذا رفض أندرويد الانتقال، أضف كلمة مرور الشبكتين، أو سيظهر لك تنبيه في أعلى هذه الصفحة للانتقال بضغطة.\n• يعمل الفحص في الخلفية بدون أي إشعارات.",
                13, Ui.SUB, false));
        content.addView(note);

        LinearLayout logCard = Ui.card(this);
        logCard.addView(Ui.text(this, "سجل آخر الفحوصات", 14, Ui.TEXT, true));
        String log = Prefs.logText(this);
        TextView lt = Ui.text(this, log.isEmpty() ? "لا يوجد بعد" : log, 12, Ui.SUB, false);
        lt.setPadding(0, dp(6), 0, 0);
        logCard.addView(lt);
        content.addView(logCard);
    }

    private View networkCard(String ssid, SpeedTest.Result r, boolean connected, boolean fastest, double max) {
        LinearLayout c = Ui.card(this);
        if (connected) {
            GradientDrawable g = Ui.round(this, Ui.CARD, 18);
            g.setStroke(dp(2), Ui.ACCENT);
            c.setBackground(g);
        }
        LinearLayout top = Ui.hbox(this);
        TextView ic = Ui.text(this, "📶", 16, Ui.WIFI, false);
        ic.setGravity(Gravity.CENTER);
        ic.setBackground(Ui.round(this, Ui.alpha(Ui.WIFI, 0x26), 12));
        top.addView(ic, new LinearLayout.LayoutParams(dp(38), dp(38)));
        TextView name = Ui.text(this, ssid, 18, Ui.TEXT, true);
        name.setPadding(dp(10), 0, 0, 0);
        top.addView(name, Ui.weight());
        if (fastest) {
            TextView ch = Ui.chip(this, "🏆 الأسرع", Ui.MOBILE);
            top.addView(ch);
        }
        if (connected) {
            TextView ch = Ui.chip(this, "● متصل", Ui.ACCENT);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP);
            lp.setMarginStart(dp(6));
            top.addView(ch, lp);
        }
        c.addView(top);
        if (r == null) {
            TextView t = Ui.text(this, "لم يتم القياس بعد — اضغط «اختبر الشبكتين الآن»", 13, Ui.SUB, false);
            t.setPadding(0, dp(10), 0, 0);
            c.addView(t);
            return c;
        }
        LinearLayout row = Ui.hbox(this);
        row.setPadding(0, dp(12), 0, 0);
        row.addView(metric("↓ تنزيل", Fmt.speed(r.dl), Ui.ACCENT), Ui.weight());
        row.addView(metric("↑ رفع", Fmt.speed(r.ul), Ui.WIFI), Ui.weight());
        row.addView(metric("الاستجابة", r.pingMs + " ms", Ui.SUB), Ui.weight());
        c.addView(row);
        c.addView(Ui.bar(this, (float) (r.dl / max), Ui.ACCENT));
        c.addView(Ui.bar(this, (float) (r.ul / max), Ui.WIFI));
        TextView meta = Ui.text(this, "آخر قياس: " + Fmt.ago(r.time), 12, Ui.SUB, false);
        meta.setPadding(0, dp(8), 0, 0);
        c.addView(meta);
        return c;
    }

    private View metric(String label, String value, int color) {
        LinearLayout col = Ui.vbox(this);
        col.addView(Ui.text(this, label, 12, Ui.SUB, true));
        TextView v = Ui.text(this, value, 16, color, true);
        v.setTextDirection(View.TEXT_DIRECTION_LTR);
        v.setGravity(Gravity.START);
        col.addView(v);
        return col;
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
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(Ui.SUB);
        if (password) e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        parent.addView(e);
        return e;
    }

    private void networksDialog() {
        LinearLayout f = Ui.vbox(this);
        f.setPadding(dp(22), dp(8), dp(22), 0);
        final String s1 = Prefs.ssid1(this), s2 = Prefs.ssid2(this);
        f.addView(Ui.text(this, "الشبكة الأولى", 13, Ui.SUB, true));
        final EditText e1 = field(f, "اسم الشبكة", s1, false);
        final EditText p1 = field(f, "كلمة المرور (اختياري)", Prefs.pass(this, s1), true);
        TextView l2 = Ui.text(this, "الشبكة الثانية", 13, Ui.SUB, true);
        l2.setPadding(0, dp(10), 0, 0);
        f.addView(l2);
        final EditText e2 = field(f, "اسم الشبكة", s2, false);
        final EditText p2 = field(f, "كلمة المرور (اختياري)", Prefs.pass(this, s2), true);
        f.addView(Ui.text(this, "الاسم يجب أن يطابق اسم الشبكة تماماً. كلمة المرور تُستخدم فقط إذا رفض أندرويد الانتقال، وتُحفظ داخل الهاتف فقط.", 12, Ui.SUB, false));
        dialog().setTitle("الشبكات")
                .setView(f)
                .setPositiveButton("حفظ", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String n1 = e1.getText().toString().trim(), n2 = e2.getText().toString().trim();
                        if (n1.isEmpty() || n2.isEmpty()) { Ui.toast(MainActivity.this, "أدخل اسم الشبكتين"); return; }
                        Prefs.saveNetworks(MainActivity.this, n1, p1.getText().toString(), n2, p2.getText().toString());
                        showTab(TAB_NETS);
                    }
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    // ================================================================== TAB 3: blocking

    /** Everything the blocking tab needs, prepared on a background thread (keeps the UI responsive). */
    private static class BlockData {
        Set<String> active;
        List<FirewallStore.Rule> rules;
        List<GroupStore.Group> groups;
        final Map<String, List<String>> installed = new HashMap<>();
        List<AppItem> apps;
    }

    private boolean showAllApps = false;
    private Runnable searchRun;

    private void setApps(List<AppItem> items) {
        if (items == null) items = new ArrayList<>();
        appsCache = items;
        appsByPkg.clear();
        for (AppItem a : items) appsByPkg.put(a.pkg, a);
    }

    private void buildBlock() {
        final long now = System.currentTimeMillis();
        LinearLayout wait = Ui.card(this);
        wait.addView(Ui.text(this, "جاري التحميل…", 14, Ui.SUB, false));
        content.addView(wait);
        final List<AppItem> cached = appsCache;
        async(new Callable<BlockData>() {
            @Override public BlockData call() {
                BlockData d = new BlockData();
                d.apps = cached != null ? cached : loadApps();
                d.active = FirewallStore.activePackages(MainActivity.this, now);
                d.rules = FirewallStore.load(MainActivity.this);
                d.groups = GroupStore.load(MainActivity.this);
                for (GroupStore.Group g : d.groups) d.installed.put(g.id, GroupStore.installed(MainActivity.this, g));
                return d;
            }
        }, new Cb<BlockData>() {
            @Override public void on(BlockData d) {
                if (d == null) return;
                if (appsCache == null) setApps(d.apps);
                content.removeAllViews();
                renderBlock(d, now);
            }
        });
    }

    private void renderBlock(final BlockData d, final long now) {
        // ---- status
        LinearLayout st = Ui.card(this);
        LinearLayout top = Ui.hbox(this);
        TextView ic = Ui.text(this, "🛡", 18, Ui.RED, false);
        ic.setGravity(Gravity.CENTER);
        ic.setBackground(Ui.round(this, Ui.alpha(Ui.RED, 0x22), 12));
        top.addView(ic, new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout tt = Ui.vbox(this);
        tt.setPadding(dp(10), 0, 0, 0);
        tt.addView(Ui.text(this, "حظر الإنترنت عن التطبيقات", 16, Ui.TEXT, true));
        tt.addView(Ui.text(this, d.active.isEmpty() ? "لا يوجد تطبيق محظور الآن" : "محظور الآن: " + d.active.size() + " تطبيق",
                13, d.active.isEmpty() ? Ui.SUB : Ui.RED, true));
        top.addView(tt, Ui.weight());
        st.addView(top);
        TextView note = Ui.text(this, "يعمل عبر VPN محلي داخل هاتفك فقط (يظهر رمز المفتاح 🔑 أعلى الشاشة)، ولا يعمل مع VPN آخر في نفس الوقت. خدمات Google Play لا تُحظر أبداً.", 12, Ui.SUB, false);
        note.setPadding(0, dp(8), 0, 0);
        st.addView(note);
        if (!d.rules.isEmpty()) {
            st.addView(Ui.button(this, "🔓 إعادة الإنترنت لكل التطبيقات", Ui.CARD2, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    FirewallStore.clear(MainActivity.this);
                    FirewallVpnService.refresh(MainActivity.this);
                    Ui.toast(MainActivity.this, "تم إلغاء كل الحظر");
                    showTab(TAB_BLOCK);
                }
            }));
        }
        content.addView(st);

        // ---- groups
        LinearLayout gh = Ui.hbox(this);
        gh.addView(Ui.section(this, "مجموعات الحظر (بضغطة واحدة)"), Ui.weight());
        TextView add = Ui.chip(this, "＋ مجموعة جديدة", Ui.ACCENT);
        add.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { editGroup(null); }
        });
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP);
        alp.topMargin = dp(16);
        gh.addView(add, alp);
        content.addView(gh);
        if (d.groups.isEmpty()) {
            LinearLayout c = Ui.card(this);
            c.addView(Ui.text(this, "لا توجد مجموعات. أنشئ مجموعة واختر التطبيقات التي تريد حظرها معاً.", 13, Ui.SUB, false));
            content.addView(c);
        }
        for (GroupStore.Group g : d.groups) {
            List<String> inst = d.installed.get(g.id);
            content.addView(groupCard(g, inst != null ? inst : new ArrayList<String>(), d.active));
        }

        // ---- currently blocked
        if (!d.rules.isEmpty()) {
            content.addView(Ui.section(this, "المحظور حالياً"));
            LinearLayout rc = Ui.card(this);
            PackageManager pm = getPackageManager();
            for (final FirewallStore.Rule r : d.rules) {
                LinearLayout row = Ui.hbox(this);
                row.setPadding(0, dp(6), 0, dp(6));
                row.addView(icon(appIcon(pm, r.pkg), 30));
                LinearLayout texts = Ui.vbox(this);
                texts.setPadding(dp(10), 0, dp(8), 0);
                texts.addView(Ui.text(this, label(pm, r.pkg), 14, Ui.TEXT, true));
                texts.addView(Ui.text(this, FirewallStore.describe(r, now), 12,
                        FirewallStore.isActive(r, now) ? Ui.RED : Ui.SUB, false));
                row.addView(texts, Ui.weight());
                TextView b = Ui.chip(this, "إلغاء", Ui.ACCENT);
                b.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        FirewallStore.remove(MainActivity.this, r.pkg);
                        FirewallVpnService.refresh(MainActivity.this);
                        showTab(TAB_BLOCK);
                    }
                });
                row.addView(b);
                rc.addView(row);
            }
            content.addView(rc);
        }

        // ---- single app
        content.addView(Ui.section(this, "حظر تطبيق واحد"));
        LinearLayout lc = Ui.card(this);
        final EditText searchBox = searchField();
        searchBox.setText(search);
        lc.addView(searchBox);
        final LinearLayout list = Ui.vbox(this);
        lc.addView(list);
        content.addView(lc);
        renderApps(list, d.active);
        searchBox.addTextChangedListener(new SimpleWatcher() {
            @Override public void afterTextChanged(Editable s) {
                search = s.toString().trim();
                if (searchRun != null) h.removeCallbacks(searchRun);
                searchRun = new Runnable() { @Override public void run() { renderApps(list, d.active); } };
                h.postDelayed(searchRun, 300);
            }
        });
    }

    private abstract static class SimpleWatcher implements TextWatcher {
        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
        @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
    }

    private EditText searchField() {
        EditText e = new EditText(this);
        e.setHint("🔍  بحث عن تطبيق…");
        e.setSingleLine(true);
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(Ui.SUB);
        e.setTextSize(14);
        e.setBackground(Ui.round(this, Ui.CARD2, 12));
        e.setPadding(dp(14), dp(10), dp(14), dp(10));
        return e;
    }

    private View groupCard(final GroupStore.Group g, final List<String> inst, Set<String> active) {
        int blockedN = 0;
        for (String p : inst) if (active.contains(p)) blockedN++;
        final boolean anyBlocked = blockedN > 0;
        boolean allBlocked = !inst.isEmpty() && blockedN == inst.size();

        LinearLayout c = Ui.card(this);
        if (anyBlocked) {
            GradientDrawable bgd = Ui.round(this, Ui.CARD, 18);
            bgd.setStroke(dp(2), Ui.alpha(Ui.RED, 0xAA));
            c.setBackground(bgd);
        }
        LinearLayout top = Ui.hbox(this);
        top.addView(Ui.text(this, g.name, 17, Ui.TEXT, true), Ui.weight());
        if (allBlocked) top.addView(Ui.chip(this, "🔒 محظورة", Ui.RED));
        else if (anyBlocked) top.addView(Ui.chip(this, "محظورة جزئياً", Ui.MOBILE));
        else top.addView(Ui.chip(this, "✓ الإنترنت يعمل", Ui.ACCENT));
        c.addView(top);

        PackageManager pm = getPackageManager();
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < Math.min(4, inst.size()); i++) {
            if (i > 0) names.append("، ");
            names.append(label(pm, inst.get(i)));
        }
        if (inst.size() > 4) names.append(" و ").append(inst.size() - 4).append(" غيرها");
        TextView sub = Ui.text(this, inst.isEmpty() ? "المجموعة فارغة — اضغط «تعديل» لاختيار التطبيقات"
                : inst.size() + " تطبيقات: " + names, 12, Ui.SUB, false);
        sub.setPadding(0, dp(4), 0, 0);
        c.addView(sub);

        if (!inst.isEmpty()) {
            LinearLayout icons = Ui.hbox(this);
            icons.setPadding(0, dp(10), 0, 0);
            for (int i = 0; i < Math.min(8, inst.size()); i++) {
                View iv = icon(appIcon(pm, inst.get(i)), 30);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(30), dp(30));
                lp.setMarginEnd(dp(6));
                iv.setAlpha(active.contains(inst.get(i)) ? 0.35f : 1f);
                icons.addView(iv, lp);
            }
            c.addView(icons);
        }

        LinearLayout btns = Ui.hbox(this);
        Button main = Ui.button(this, anyBlocked ? "🔓 إلغاء الحظر" : "🔒 حظر الآن",
                anyBlocked ? Ui.ACCENT : Ui.RED, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (inst.isEmpty()) { editGroup(g); return; }
                        if (anyBlocked) {
                            for (String p : inst) FirewallStore.remove(MainActivity.this, p);
                            FirewallVpnService.refresh(MainActivity.this);
                            Ui.toast(MainActivity.this, "عاد الإنترنت إلى «" + g.name + "»");
                            showTab(TAB_BLOCK);
                        } else {
                            applyRules(inst, FirewallStore.FOREVER, 0, 0, 0, "تم حظر «" + g.name + "» حتى تلغيه");
                        }
                    }
                });
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0, Ui.WRAP, 2f);
        mlp.topMargin = dp(12);
        btns.addView(main, mlp);
        Button timed = Ui.button(this, "⏱ لمدة", Ui.CARD2, new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (inst.isEmpty()) { editGroup(g); return; }
                chooseDuration(inst, g.name);
            }
        });
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, Ui.WRAP, 1f);
        tlp.topMargin = dp(12);
        tlp.setMarginStart(dp(8));
        btns.addView(timed, tlp);
        Button edit = Ui.button(this, "✏️ تعديل", Ui.CARD2, new View.OnClickListener() {
            @Override public void onClick(View v) { editGroup(g); }
        });
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(0, Ui.WRAP, 1f);
        elp.topMargin = dp(12);
        elp.setMarginStart(dp(8));
        btns.addView(edit, elp);
        c.addView(btns);
        return c;
    }

    /** Create (g == null) or edit a group: name + multi-select list with smart detection. */
    private void editGroup(final GroupStore.Group original) {
        withApps(new Runnable() {
            @Override public void run() {
                final Set<String> sel = new HashSet<>();
                if (original != null) {
                    for (String p : original.pkgs) if (!GroupStore.isProtected(p)) sel.add(p);
                } else {
                    sel.addAll(GroupStore.detectSocial(MainActivity.this));
                }
                // apps in the group that are not in the normal list (no launcher icon) – shown so they can be unticked
                final List<AppItem> extra = new ArrayList<>();
                PackageManager pm = getPackageManager();
                for (String p : sel) {
                    if (appsByPkg.containsKey(p)) continue;
                    AppItem a = new AppItem();
                    a.pkg = p;
                    a.name = label(pm, p);
                    a.icon = appIcon(pm, p);
                    extra.add(a);
                }

                LinearLayout f = Ui.vbox(MainActivity.this);
                f.setPadding(dp(20), dp(6), dp(20), 0);
                final EditText name = field(f, "اسم المجموعة (مثلاً: التواصل، الألعاب، الفيديو)",
                        original != null ? original.name : "مجموعتي", false);
                final TextView counter = Ui.text(MainActivity.this, "", 13, Ui.ACCENT, true);
                counter.setPadding(0, dp(8), 0, 0);
                final LinearLayout list = Ui.vbox(MainActivity.this);
                final EditText sbox = searchField();
                final String[] q = {""};
                final boolean[] all = {false};

                final Runnable[] render = new Runnable[1];
                render[0] = new Runnable() {
                    @Override public void run() {
                        counter.setText("محدد: " + sel.size() + " تطبيق");
                        list.removeAllViews();
                        List<AppItem> ordered = new ArrayList<>(extra);
                        for (AppItem a : appsCache) if (sel.contains(a.pkg)) ordered.add(a);
                        int selectedCount = ordered.size();
                        for (AppItem a : appsCache) if (!sel.contains(a.pkg)) ordered.add(a);
                        String qq = q[0].toLowerCase(Locale.ROOT);
                        int shown = 0, hidden = 0;
                        for (int i = 0; i < ordered.size(); i++) {
                            final AppItem a = ordered.get(i);
                            if (!qq.isEmpty() && !a.name.toLowerCase(Locale.ROOT).contains(qq) && !a.pkg.contains(qq)) continue;
                            if (qq.isEmpty() && !all[0] && i >= selectedCount && shown >= selectedCount + 40) { hidden++; continue; }
                            shown++;
                            LinearLayout row = Ui.hbox(MainActivity.this);
                            row.setPadding(0, dp(5), 0, dp(5));
                            final CheckBox cb = new CheckBox(MainActivity.this);
                            cb.setChecked(sel.contains(a.pkg));
                            cb.setButtonTintList(Ui.tint(Ui.ACCENT));
                            cb.setClickable(false);
                            row.addView(cb);
                            row.addView(icon(a.icon, 30));
                            TextView n = Ui.text(MainActivity.this, a.name, 14, Ui.TEXT, false);
                            n.setPadding(dp(10), 0, 0, 0);
                            row.addView(n, Ui.weight());
                            row.setOnClickListener(new View.OnClickListener() {
                                @Override public void onClick(View v) {
                                    if (sel.contains(a.pkg)) sel.remove(a.pkg); else sel.add(a.pkg);
                                    cb.setChecked(sel.contains(a.pkg));
                                    counter.setText("محدد: " + sel.size() + " تطبيق");
                                }
                            });
                            list.addView(row);
                        }
                        if (hidden > 0) {
                            Button more = Ui.button(MainActivity.this, "عرض كل التطبيقات (" + hidden + " أخرى)", Ui.CARD2,
                                    new View.OnClickListener() {
                                        @Override public void onClick(View v) { all[0] = true; render[0].run(); }
                                    });
                            list.addView(more);
                        }
                    }
                };

                LinearLayout quickRow = Ui.hbox(MainActivity.this);
                Button smart = Ui.button(MainActivity.this, "✨ تحديد التواصل تلقائياً", Ui.ACCENT, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        List<String> soc = GroupStore.detectSocial(MainActivity.this);
                        sel.addAll(soc);
                        Ui.toast(MainActivity.this, "تم تحديد " + soc.size() + " تطبيق تواصل");
                        render[0].run();
                    }
                });
                smart.setTextSize(12);
                quickRow.addView(smart, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
                Button clear = Ui.button(MainActivity.this, "إلغاء التحديد", Ui.CARD2, new View.OnClickListener() {
                    @Override public void onClick(View v) { sel.clear(); render[0].run(); }
                });
                clear.setTextSize(12);
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, Ui.WRAP, 1f);
                clp.setMarginStart(dp(8));
                quickRow.addView(clear, clp);
                f.addView(quickRow);
                f.addView(counter);
                LinearLayout.LayoutParams sblp = new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP);
                sblp.topMargin = dp(8);
                f.addView(sbox, sblp);
                final Runnable[] deb = new Runnable[1];
                sbox.addTextChangedListener(new SimpleWatcher() {
                    @Override public void afterTextChanged(Editable s) {
                        q[0] = s.toString().trim();
                        if (deb[0] != null) h.removeCallbacks(deb[0]);
                        deb[0] = render[0];
                        h.postDelayed(deb[0], 300);
                    }
                });
                ScrollView sv = new ScrollView(MainActivity.this);
                sv.addView(list);
                f.addView(sv, new LinearLayout.LayoutParams(Ui.MATCH, dp(300)));
                render[0].run();

                AlertDialog.Builder b = dialog()
                        .setTitle(original == null ? "مجموعة حظر جديدة" : "تعديل المجموعة")
                        .setView(f)
                        .setPositiveButton("حفظ", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                GroupStore.Group g = new GroupStore.Group();
                                g.id = original != null ? original.id : null;
                                String nm = name.getText().toString().trim();
                                g.name = nm.isEmpty() ? "مجموعتي" : nm;
                                g.pkgs = new ArrayList<>(sel);
                                GroupStore.upsert(MainActivity.this, g);
                                if (original != null) syncBlockedGroup(original, g);
                                Ui.toast(MainActivity.this, "تم حفظ المجموعة ✅");
                                showTab(TAB_BLOCK);
                            }
                        })
                        .setNegativeButton("إلغاء", null);
                if (original != null) {
                    b.setNeutralButton("حذف", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) { confirmDelete(original); }
                    });
                }
                b.show();
            }
        });
    }

    /** If an edited group was blocked, apply the same block to newly added apps and lift it from removed ones. */
    private void syncBlockedGroup(GroupStore.Group before, GroupStore.Group after) {
        List<FirewallStore.Rule> rules = FirewallStore.load(this);
        Map<String, FirewallStore.Rule> byPkg = new HashMap<>();
        for (FirewallStore.Rule r : rules) byPkg.put(r.pkg, r);
        FirewallStore.Rule template = null;
        for (String p : before.pkgs) if (byPkg.containsKey(p)) { template = byPkg.get(p); break; }
        if (template == null) return;
        boolean changed = false;
        for (String p : before.pkgs) {
            if (!after.pkgs.contains(p) && byPkg.containsKey(p)) { FirewallStore.remove(this, p); changed = true; }
        }
        for (String p : after.pkgs) {
            if (!byPkg.containsKey(p)) {
                FirewallStore.Rule r = new FirewallStore.Rule();
                r.pkg = p; r.mode = template.mode; r.until = template.until; r.from = template.from; r.to = template.to;
                FirewallStore.put(this, r);
                changed = true;
            }
        }
        if (changed) FirewallVpnService.refresh(this);
    }

    private void confirmDelete(final GroupStore.Group g) {
        dialog().setTitle("حذف «" + g.name + "»؟")
                .setMessage("سيتم حذف المجموعة فقط. إذا كانت محظورة سيعود الإنترنت لتطبيقاتها.")
                .setPositiveButton("حذف", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        for (String p : g.pkgs) FirewallStore.remove(MainActivity.this, p);
                        FirewallVpnService.refresh(MainActivity.this);
                        GroupStore.delete(MainActivity.this, g.id);
                        showTab(TAB_BLOCK);
                    }
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void withApps(final Runnable r) {
        if (appsCache != null) { r.run(); return; }
        Ui.toast(this, "جاري تحميل التطبيقات…");
        async(new Callable<List<AppItem>>() {
            @Override public List<AppItem> call() { return loadApps(); }
        }, new Cb<List<AppItem>>() {
            @Override public void on(List<AppItem> items) {
                setApps(items);
                r.run();
            }
        });
    }

    private String label(PackageManager pm, String pkg) {
        AppItem a = appsByPkg.get(pkg);
        if (a != null) return a.name;
        try { return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0))); }
        catch (Exception e) { return pkg; }
    }

    private Drawable appIcon(PackageManager pm, String pkg) {
        AppItem a = appsByPkg.get(pkg);
        if (a != null) return a.icon;
        try { return pm.getApplicationIcon(pkg); } catch (Exception e) { return null; }
    }

    private List<AppItem> loadApps() {
        PackageManager pm = getPackageManager();
        List<AppItem> out = new ArrayList<>();
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            if (ai.packageName.equals(getPackageName()) || GroupStore.isProtected(ai.packageName)) continue;
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

    private void renderApps(final LinearLayout list, final Set<String> blocked) {
        list.removeAllViews();
        if (appsCache == null) return;
        String q = search.toLowerCase(Locale.ROOT);
        int shown = 0, hidden = 0;
        for (final AppItem a : appsCache) {
            if (!q.isEmpty() && !a.name.toLowerCase(Locale.ROOT).contains(q) && !a.pkg.contains(q)) continue;
            if (q.isEmpty() && !showAllApps && shown >= 40) { hidden++; continue; }
            shown++;
            LinearLayout row = Ui.hbox(this);
            row.setPadding(0, dp(9), 0, dp(9));
            row.addView(icon(a.icon, 34));
            TextView name = Ui.text(this, a.name, 15, Ui.TEXT, false);
            name.setPadding(dp(12), 0, dp(10), 0);
            row.addView(name, Ui.weight());
            if (blocked.contains(a.pkg)) row.addView(Ui.chip(this, "🔒 محظور", Ui.RED));
            else row.addView(Ui.text(this, "‹", 18, Ui.SUB, false));
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    List<String> one = new ArrayList<>();
                    one.add(a.pkg);
                    chooseDuration(one, a.name);
                }
            });
            list.addView(row);
        }
        if (hidden > 0) {
            list.addView(Ui.button(this, "عرض كل التطبيقات (" + hidden + " أخرى)", Ui.CARD2, new View.OnClickListener() {
                @Override public void onClick(View v) { showAllApps = true; renderApps(list, blocked); }
            }));
        }
    }

    private View icon(Drawable d, int sizeDp) {
        ImageView iv = new ImageView(this);
        if (d != null) iv.setImageDrawable(d);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return iv;
    }

    private void chooseDuration(final List<String> pkgs, final String title) {
        String[] opts = {"15 دقيقة", "30 دقيقة", "ساعة", "ساعتان", "3 ساعات",
                "حتى أعيد التفعيل بنفسي", "يومياً في وقت محدد…", "مدة مخصصة (بالدقائق)…"};
        final int[] mins = {15, 30, 60, 120, 180};
        dialog().setTitle("حظر الإنترنت عن: " + title)
                .setItems(opts, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        if (w < 5) {
                            applyRules(pkgs, FirewallStore.UNTIL, System.currentTimeMillis() + mins[w] * 60_000L, 0, 0, "تم الحظر ✅");
                        } else if (w == 5) {
                            applyRules(pkgs, FirewallStore.FOREVER, 0, 0, 0, "تم الحظر ✅");
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
        TimePickerDialog from = new TimePickerDialog(this, Ui.dialogTheme(), new TimePickerDialog.OnTimeSetListener() {
            @Override public void onTimeSet(TimePicker v, int hh, int mm) {
                final int fromMin = hh * 60 + mm;
                TimePickerDialog to = new TimePickerDialog(MainActivity.this, Ui.dialogTheme(), new TimePickerDialog.OnTimeSetListener() {
                    @Override public void onTimeSet(TimePicker v2, int h2, int m2) {
                        int toMin = h2 * 60 + m2;
                        if (toMin == fromMin) { Ui.toast(MainActivity.this, "وقت البداية والنهاية متساويان"); return; }
                        applyRules(pkgs, FirewallStore.DAILY, 0, fromMin, toMin, "تم ضبط الحظر اليومي ✅");
                    }
                }, 7, 0, false);
                to.setTitle("وقت انتهاء الحظر (رجوع النت)");
                to.show();
            }
        }, 23, 0, false);
        from.setTitle("وقت بدء الحظر");
        from.show();
    }

    private void customMinutes(final List<String> pkgs) {
        final EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setHint("عدد الدقائق");
        e.setGravity(Gravity.CENTER);
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(Ui.SUB);
        dialog().setTitle("مدة الحظر")
                .setView(e)
                .setPositiveButton("حظر", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        long m;
                        try { m = Long.parseLong(e.getText().toString().trim()); } catch (Exception ex) { m = 0; }
                        if (m <= 0) { Ui.toast(MainActivity.this, "أدخل رقماً صحيحاً"); return; }
                        applyRules(pkgs, FirewallStore.UNTIL, System.currentTimeMillis() + m * 60_000L, 0, 0, "تم الحظر ✅");
                    }
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void applyRules(final List<String> pkgs, final int mode, final long until, final int from, final int to,
                            final String doneMsg) {
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
                Ui.toast(MainActivity.this, doneMsg);
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
        Ui.toast(this, "اسمح بإنشاء الـ VPN المحلي حتى يعمل الحظر");
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
            else Ui.toast(this, "لم يتم السماح — الحظر لن يعمل بدون هذا الإذن");
        }
    }

    // ================================================================== TAB 4: app statistics

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
        box.setPadding(0, dp(12), 0, 0);
        LinearLayout row = Ui.hbox(this);
        row.addView(icon(a.icon, 34));
        LinearLayout texts = Ui.vbox(this);
        texts.setPadding(dp(10), 0, dp(10), 0);
        texts.addView(Ui.text(this, a.name, 14, Ui.TEXT, true));
        texts.addView(Ui.text(this, "واي فاي " + Fmt.bytes(a.wifi) + "  •  بيانات " + Fmt.bytes(a.mobile), 12, Ui.SUB, false));
        row.addView(texts, Ui.weight());
        row.addView(Ui.text(this, Fmt.bytes(a.total()), 14, Ui.ACCENT, true));
        box.addView(row);
        box.addView(Ui.split(this, a.wifi, a.mobile, Ui.WIFI, Ui.MOBILE));
        View spacer = box.getChildAt(1);
        ViewGroup.LayoutParams lp = spacer.getLayoutParams();
        if (lp instanceof LinearLayout.LayoutParams) {
            ((LinearLayout.LayoutParams) lp).width = Math.max(dp(24), (int) (getResources().getDisplayMetrics().widthPixels * 0.78f * a.total() / max));
            ((LinearLayout.LayoutParams) lp).topMargin = dp(6);
        }
        return box;
    }
}
