package io.freemytv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;

/**
 * The crypto card of the Apple TV home screen and of the screensaver: one row per coin with its logo, ticker and
 * name, a chart over Prefs.chart() (green when the price went up over that range, red when it went down), the price
 * and the change. `refresh` makes the text and paths when the data changes; `draw` only draws them. Sizes are on the
 * 1920×1080 design, times `k` (1 where the canvas is already scaled).
 */
final class CoinsCard {
    static final float W = 560, ROW = 96, PAD = 28;
    private static final float SPARK_X = 236, SPARK_W = 168, SPARK_H = 46;

    private final Context context;
    private final int n = HomeData.COINS.length;
    private final Path[] line = new Path[n], area = new Path[n];
    private final Shader[] shade = new Shader[n];
    private final int[] color = new int[n];
    private final String[] price = new String[n], change = new String[n];
    private final Paint ticker = Ui.text(Ui.semibold, 28, 0xFFFFFFFF), value = Ui.numbers(Ui.semibold, 28, 0xFFFFFFFF);
    private final Paint caption = Ui.text(Ui.medium, 22, 0x99EBEBF5);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int[] coins = new int[0];
    private float left, top, k = 1;

    CoinsCard(Context context) {
        this.context = context;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
    }

    int rows() { return coins.length; }

    /** Height for `rows` coins, on the design scale. */
    static float height(int rows) { return 2 * PAD + ROW * rows; }

    void refresh(HomeData data, float left, float top, float k) {
        this.left = left;
        this.top = top;
        this.k = k;
        ticker.setTextSize(28 * k);
        value.setTextSize(28 * k);
        caption.setTextSize(22 * k);
        stroke.setStrokeWidth(3 * k);
        coins = Prefs.quotes() ? HomeData.active() : new int[0];
        for (int r = 0; r < coins.length; r++) {
            int c = coins[r];
            double now = data.prices[c];
            float[] chart = data.charts[c];
            double pct = data.changes[c];
            // Over the chart's range when there is one: its first close against now.
            if (chart != null && chart.length > 1 && chart[0] > 0 && !Double.isNaN(now)) pct = (now - chart[0]) / chart[0] * 100;
            price[r] = HomeData.brl(now);
            change[r] = HomeData.change(pct);
            color[r] = Double.isNaN(pct) ? 0x99EBEBF5 : pct >= 0 ? 0xFF30D158 : 0xFFFF453A;
            line[r] = null;
            area[r] = null;
            if (chart == null || chart.length < 2) continue;
            float x0 = left + SPARK_X * k, y0 = rowCenter(r) - SPARK_H * k / 2, w = SPARK_W * k, h = SPARK_H * k;
            float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
            for (float v : chart) { min = Math.min(min, v); max = Math.max(max, v); }
            if (!Double.isNaN(now)) { min = Math.min(min, (float) now); max = Math.max(max, (float) now); }
            float span = Math.max(max - min, max * 1e-4f);
            int points = chart.length + (Double.isNaN(now) ? 0 : 1);
            Path l = new Path(), a = new Path();
            for (int i = 0; i < points; i++) {
                float v = i < chart.length ? chart[i] : (float) now;
                float x = x0 + w * i / (points - 1), y = y0 + h * (1 - (v - min) / span);
                if (i == 0) { l.moveTo(x, y); a.moveTo(x, y0 + h); a.lineTo(x, y); }
                else { l.lineTo(x, y); a.lineTo(x, y); }
            }
            a.lineTo(x0 + w, y0 + h);
            a.close();
            line[r] = l;
            area[r] = a;
            shade[r] = new LinearGradient(0, y0, 0, y0 + h, Ui.alpha(color[r], 0.35f), 0, Shader.TileMode.CLAMP);
        }
    }

    private float rowCenter(int r) { return top + (PAD + ROW * r + ROW / 2) * k; }

    void draw(Canvas c, float a) {
        float right = left + (W - 32) * k;
        for (int r = 0; r < coins.length; r++) {
            String coin = HomeData.COINS[coins[r]];
            float cy = rowCenter(r);
            Glyphs.coin(context, c, coin, left + 56 * k, cy, 26 * k, Ui.alpha(0xFFFFFFFF, a));
            ticker.setColor(Ui.alpha(0xFFFFFFFF, a));
            c.drawText(coin, left + 100 * k, cy - 4 * k, ticker);
            caption.setColor(Ui.alpha(0x99EBEBF5, a));
            c.drawText(HomeData.coinName(coins[r]), left + 100 * k, cy + 28 * k, caption);
            if (line[r] != null) {
                fill.setShader(shade[r]);
                fill.setAlpha(Math.round(255 * a));
                c.drawPath(area[r], fill);
                stroke.setColor(Ui.alpha(color[r], a));
                c.drawPath(line[r], stroke);
            }
            value.setColor(Ui.alpha(0xFFFFFFFF, a));
            c.drawText(price[r], right - value.measureText(price[r]), cy - 4 * k, value);
            caption.setColor(Ui.alpha(color[r], a));
            c.drawText(change[r], right - caption.measureText(change[r]), cy + 28 * k, caption);
        }
    }
}
