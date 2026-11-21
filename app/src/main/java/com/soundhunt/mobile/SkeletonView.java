package com.soundhunt.mobile;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * 列表骨架屏：接口返回之前先铺一层与真实行同构的占位块，
 * 用一道缓慢扫过的高光表达"正在加载"，避免空白页、spinner 和随后的高度跳动。
 *
 * 这里的尺寸全部按 dp 手写、与 TrackAdapter#trackRow 一一对应：
 * 行高 70 / 排名位 24 / 缩略图 48 圆形（唱片语言）/ 标题条 12 / 副条 9 / 右侧两个 18 圆。
 */
public class SkeletonView extends View {

    private static final float ROW_DP = 70f;      // 与 trackRow 行高一致
    private static final float TOP_DP = 10f;      // 与 ListView 的 paddingTop 一致
    private static final int ROWS_MAX = 12;

    private final Paint block = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final ValueAnimator shimmer;
    private final float density;

    private boolean ranked = true;   // 首页推荐带排名位，搜索结果不带
    private float phase;             // 0..1 扫光进度
    private float sweepX;            // 扫光中心 x（像素）
    private float sweepW;            // 半宽（像素）

    public SkeletonView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        block.setStyle(Paint.Style.FILL);
        block.setColor(Theme.CARD_ALT);   // 跟随主题：比底色略亮的卡片层
        shine.setStyle(Paint.Style.FILL);
        shine.setColor(Color.WHITE);

        shimmer = ValueAnimator.ofFloat(0f, 1f);
        shimmer.setDuration(1500);
        shimmer.setRepeatCount(ValueAnimator.INFINITE);
        shimmer.setInterpolator(new LinearInterpolator());
        shimmer.setStartDelay(120);   // 让淡入先走完一点点，扫光不至于和出现同时发生
        shimmer.addUpdateListener(a -> {
            phase = (Float) a.getAnimatedValue();
            invalidate();
        });
    }

    /** 是否保留左侧排名位。 */
    public void setRanked(boolean ranked) {
        this.ranked = ranked;
        invalidate();
    }

    public void start() {
        if (!shimmer.isStarted()) shimmer.start();
    }

    public void stop() {
        if (shimmer.isStarted()) shimmer.cancel();
    }

    @Override protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        int w = getWidth();
        if (w <= 0) return;
        float rowH = dp(ROW_DP);
        int rows = Math.min(ROWS_MAX, Math.max(3, (int) Math.ceil(getHeight() / rowH) + 1));

        sweepW = w * 0.42f;
        sweepX = (w + sweepW * 2f) * phase - sweepW;

        float top = dp(TOP_DP);
        for (int i = 0; i < rows; i++) {
            drawRow(canvas, w, top + i * rowH, rowH, i);
        }
    }

    private void drawRow(Canvas canvas, int width, float top, float rowH, int index) {
        float cy = top + rowH / 2f;
        float x;
        if (ranked) {
            bar(canvas, dp(9), cy - dp(5), dp(14), dp(10), dp(3));
            x = dp(25);   // 排名位 24dp 后紧接缩略图
        } else {
            x = dp(1);    // 缩略图左边距已去掉，与筛选项左对齐
        }

        // 封面
        bar(canvas, x, cy - dp(24), dp(48), dp(48), dp(24));   // 圆形，对齐 DiscThumbView

        // 标题 / 副标题：宽度按行号错开，避免整齐得像表格
        float textX = x + dp(48) + dp(13);
        float avail = width - textX - dp(74);
        float titleW = Math.min(avail, avail * (index % 3 == 0 ? 0.88f : 0.62f));
        bar(canvas, textX, cy - dp(13), titleW, dp(12), dp(6));
        bar(canvas, textX, cy + dp(5), Math.min(titleW * 0.45f, dp(84)), dp(9), dp(4.5f));

        // 右侧爱心 / 播放位（圆心与 trackRow 的 30dp+26dp 两个按钮对齐）
        bar(canvas, width - dp(64), cy - dp(9), dp(18), dp(18), dp(9));
        bar(canvas, width - dp(22), cy - dp(9), dp(18), dp(18), dp(9));
    }

    /** 一个占位块：先铺底色，再按扫光位置叠一层白光。 */
    private void bar(Canvas canvas, float x, float y, float w, float h, float radius) {
        if (w <= 0 || h <= 0) return;
        rect.set(x, y, x + w, y + h);
        canvas.drawRoundRect(rect, radius, radius, block);

        float k = beam(x, x + w);
        if (k > 0.01f) {
            shine.setAlpha((int) (k * 24));   // 最高约 9% 白
            canvas.drawRoundRect(rect, radius, radius, shine);
        }
    }

    /**
     * 块上某点的光强：以块的左右端分别对扫光中心求距离，取更近的一端，
     * 再做 smoothstep。这样长条也是被"扫"过去，而不是整块一起闪。
     */
    private float beam(float x0, float x1) {
        float a = 1f - Math.abs(x0 - sweepX) / sweepW;
        float b = 1f - Math.abs(x1 - sweepX) / sweepW;
        float k = Math.max(a, b);
        if (k <= 0f) return 0f;
        if (k >= 1f) return 1f;
        return k * k * (3f - 2f * k);
    }

    private float dp(float value) {
        return value * density;
    }
}
