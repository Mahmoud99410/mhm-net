package com.mhm.net;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** Small helpers to build the UI in code (no XML layouts). */
final class Ui {
    private Ui() {}

    static final int BG = 0xFF0B1220, CARD = 0xFF162032, CARD2 = 0xFF243149,
            TEXT = 0xFFE5E7EB, SUB = 0xFF94A3B8, ACCENT = 0xFF22C55E,
            WIFI = 0xFF38BDF8, MOBILE = 0xFFF59E0B, RED = 0xFFEF4444;
    static final int MATCH = LinearLayout.LayoutParams.MATCH_PARENT;
    static final int WRAP = LinearLayout.LayoutParams.WRAP_CONTENT;

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

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        return t;
    }

    static LinearLayout card(Context c) {
        LinearLayout l = vbox(c);
        l.setBackground(round(c, CARD, 14));
        int p = dp(c, 14);
        l.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.topMargin = dp(c, 10);
        l.setLayoutParams(lp);
        return l;
    }

    static Button button(Context c, String s, int color, View.OnClickListener cl) {
        Button b = new Button(c);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(BG);
        b.setTextSize(14);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(round(c, color, 10));
        b.setStateListAnimator(null);
        b.setOnClickListener(cl);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.topMargin = dp(c, 8);
        b.setLayoutParams(lp);
        return b;
    }

    /** Horizontal progress-like bar, fraction 0..1 */
    static View bar(Context c, float frac, int color) {
        if (Float.isNaN(frac) || frac < 0) frac = 0;
        if (frac > 1) frac = 1;
        if (frac > 0 && frac < 0.02f) frac = 0.02f;
        LinearLayout l = hbox(c);
        l.setBackground(round(c, CARD2, 4));
        l.setWeightSum(1f);
        View fill = new View(c);
        fill.setBackground(round(c, color, 4));
        l.addView(fill, new LinearLayout.LayoutParams(0, dp(c, 8), frac));
        l.addView(new View(c), new LinearLayout.LayoutParams(0, dp(c, 8), 1f - frac));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.topMargin = dp(c, 4);
        l.setLayoutParams(lp);
        return l;
    }

    static LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, WRAP, 1f);
    }

    static void toast(Context c, String s) {
        Toast.makeText(c, s, Toast.LENGTH_SHORT).show();
    }
}
