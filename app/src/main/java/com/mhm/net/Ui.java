package com.mhm.net;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** UI helpers + light / dark palettes (UI is built in code, no XML layouts). */
final class Ui {
    private Ui() {}

    static final int THEME_SYSTEM = 0, THEME_LIGHT = 1, THEME_DARK = 2;

    static boolean dark = true;
    static int BG, CARD, CARD2, STROKE, TEXT, SUB, ACCENT, WIFI, MOBILE, RED, ON_ACCENT, NAV;
    static int HERO1, HERO2;

    static final int MATCH = LinearLayout.LayoutParams.MATCH_PARENT;
    static final int WRAP = LinearLayout.LayoutParams.WRAP_CONTENT;

    static { setPalette(true); }

    static int themeMode(Context c) { return Prefs.p(c).getInt("theme", THEME_SYSTEM); }

    static void setThemeMode(Context c, int m) { Prefs.p(c).edit().putInt("theme", m).apply(); }

    /** Call in Activity.onCreate before super.onCreate. */
    static void apply(Activity a) {
        int mode = themeMode(a);
        boolean d;
        if (mode == THEME_LIGHT) d = false;
        else if (mode == THEME_DARK) d = true;
        else d = (a.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        setPalette(d);
        a.setTheme(d ? android.R.style.Theme_Material_NoActionBar
                : android.R.style.Theme_Material_Light_NoActionBar);
    }

    private static void setPalette(boolean d) {
        dark = d;
        if (d) {
            BG = 0xFF0A0F1C; CARD = 0xFF141C2E; CARD2 = 0xFF222D45; STROKE = 0x1FFFFFFF;
            TEXT = 0xFFF1F5F9; SUB = 0xFF94A3B8; NAV = 0xFF111827;
            ACCENT = 0xFF22C55E; WIFI = 0xFF38BDF8; MOBILE = 0xFFFBBF24; RED = 0xFFF87171;
            ON_ACCENT = 0xFF06130B;
            HERO1 = 0xFF0F766E; HERO2 = 0xFF1E3A8A;
        } else {
            BG = 0xFFF2F5FA; CARD = 0xFFFFFFFF; CARD2 = 0xFFE7ECF4; STROKE = 0x14000000;
            TEXT = 0xFF0F172A; SUB = 0xFF64748B; NAV = 0xFFFFFFFF;
            ACCENT = 0xFF16A34A; WIFI = 0xFF0284C7; MOBILE = 0xFFD97706; RED = 0xFFDC2626;
            ON_ACCENT = 0xFFFFFFFF;
            HERO1 = 0xFF10B981; HERO2 = 0xFF3B82F6;
        }
    }

    static int dialogTheme() {
        return dark ? android.R.style.Theme_Material_Dialog_Alert
                : android.R.style.Theme_Material_Light_Dialog_Alert;
    }

    static int alpha(int color, int a) { return (color & 0x00FFFFFF) | (a << 24); }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static LinearLayout vbox(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout hbox(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static GradientDrawable round(Context c, int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    static GradientDrawable gradient(Context c, int from, int to, float radiusDp) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{from, to});
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        return t;
    }

    /** Small rounded label. */
    static TextView chip(Context c, String s, int color) {
        TextView t = text(c, s, 12, color, true);
        t.setBackground(round(c, alpha(color, 0x26), 20));
        t.setPadding(dp(c, 10), dp(c, 3), dp(c, 10), dp(c, 3));
        return t;
    }

    static LinearLayout card(Context c) {
        LinearLayout l = vbox(c);
        GradientDrawable g = round(c, CARD, 18);
        g.setStroke(dp(c, 1), STROKE);
        l.setBackground(g);
        int p = dp(c, 16);
        l.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.topMargin = dp(c, 12);
        l.setLayoutParams(lp);
        if (!dark) l.setElevation(dp(c, 1.5f));
        return l;
    }

    static TextView section(Context c, String s) {
        TextView t = text(c, s, 15, TEXT, true);
        t.setPadding(dp(c, 4), dp(c, 18), dp(c, 4), 0);
        return t;
    }

    static Button button(Context c, String s, int color, View.OnClickListener cl) {
        Button b = new Button(c);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(color == CARD2 ? TEXT : ON_ACCENT);
        b.setTextSize(14);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(round(c, color, 12));
        b.setStateListAnimator(null);
        b.setMinHeight(dp(c, 46));
        b.setOnClickListener(cl);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.topMargin = dp(c, 8);
        b.setLayoutParams(lp);
        return b;
    }

    /** Horizontal bar, fraction 0..1 */
    static View bar(Context c, float frac, int color) {
        if (Float.isNaN(frac) || frac < 0) frac = 0;
        if (frac > 1) frac = 1;
        if (frac > 0 && frac < 0.02f) frac = 0.02f;
        LinearLayout l = hbox(c);
        l.setBackground(round(c, CARD2, 4));
        l.setWeightSum(1f);
        View fill = new View(c);
        fill.setBackground(round(c, color, 4));
        l.addView(fill, new LinearLayout.LayoutParams(0, dp(c, 7), frac));
        l.addView(new View(c), new LinearLayout.LayoutParams(0, dp(c, 7), 1f - frac));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.topMargin = dp(c, 5);
        l.setLayoutParams(lp);
        return l;
    }

    /** Two-colour split bar (e.g. Wi-Fi vs mobile share). */
    static View split(Context c, long a, long b, int ca, int cb) {
        LinearLayout l = hbox(c);
        l.setWeightSum(1f);
        float fa = (a + b) == 0 ? 0.5f : a / (float) (a + b);
        View va = new View(c), vb = new View(c);
        va.setBackground(round(c, (a + b) == 0 ? CARD2 : ca, 5));
        vb.setBackground(round(c, (a + b) == 0 ? CARD2 : cb, 5));
        LinearLayout.LayoutParams pa = new LinearLayout.LayoutParams(0, dp(c, 10), Math.max(fa, 0.001f));
        LinearLayout.LayoutParams pb = new LinearLayout.LayoutParams(0, dp(c, 10), Math.max(1 - fa, 0.001f));
        pa.setMarginEnd(dp(c, 2));
        l.addView(va, pa);
        l.addView(vb, pb);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.topMargin = dp(c, 10);
        l.setLayoutParams(lp);
        return l;
    }

    static ColorStateList tint(int color) { return ColorStateList.valueOf(color); }

    static LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, WRAP, 1f);
    }

    static void toast(Context c, String s) {
        Toast.makeText(c, s, Toast.LENGTH_SHORT).show();
    }
}
