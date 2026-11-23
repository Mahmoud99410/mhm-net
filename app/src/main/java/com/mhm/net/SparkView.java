package com.mhm.net;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.View;

/** Tiny live graph of the last 60 seconds of download / upload speed. */
public class SparkView extends View {
    private static final int N = 60;
    private final float[] dl = new float[N], ul = new float[N];
    private int count = 0;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private int dlColor = 0xFFFFFFFF, ulColor = 0xFFFDE68A;

    public SparkView(Context c) {
        super(c);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(Ui.dp(c, 2.2f));
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        fill.setStyle(Paint.Style.FILL);
    }

    public void setColors(int dlColor, int ulColor) { this.dlColor = dlColor; this.ulColor = ulColor; }

    public void push(double dlBps, double ulBps) {
        System.arraycopy(dl, 1, dl, 0, N - 1);
        System.arraycopy(ul, 1, ul, 0, N - 1);
        dl[N - 1] = (float) Math.max(0, dlBps);
        ul[N - 1] = (float) Math.max(0, ulBps);
        if (count < N) count++;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float max = 64 * 1024; // at least 64 KB/s scale so idle looks flat
        for (int i = 0; i < N; i++) max = Math.max(max, Math.max(dl[i], ul[i]));
        draw(c, ul, ulColor, max, w, h, false);
        draw(c, dl, dlColor, max, w, h, true);
    }

    private void draw(Canvas c, float[] v, int color, float max, float w, float h, boolean withFill) {
        float step = w / (N - 1);
        float pad = Ui.dp(getContext(), 3);
        path.reset();
        // RTL-friendly: newest sample on the left
        for (int i = 0; i < N; i++) {
            float x = w - i * step;
            float y = h - pad - (v[i] / max) * (h - 2 * pad);
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        if (withFill) {
            Path f = new Path(path);
            f.lineTo(w - (N - 1) * step, h);
            f.lineTo(w, h);
            f.close();
            fill.setShader(new LinearGradient(0, 0, 0, h, (color & 0x00FFFFFF) | 0x55000000,
                    color & 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawPath(f, fill);
        }
        line.setColor(color);
        c.drawPath(path, line);
    }
}
