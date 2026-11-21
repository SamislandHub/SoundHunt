package com.soundhunt.mobile;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.view.animation.LinearInterpolator;
import android.view.View;

/**
 * 列表行唱片缩略图（设计稿 v4 唱片语法）：
 * 单曲 = 一张 48dp 黑胶（沟槽环纹 + 44% 封面裁圆标签 + 双段冷白高光）；
 * 封面标签之上不加任何遮罩、描边、中孔——封面清晰可见。
 * 合集 = 一叠三张，向左上错位 4.5 / 9px、缩放 0.955 / 0.9、透明度 62% / 36%，
 * 张数不贴角标——叠放本身就是"多"。
 */
public class DiscThumbView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix coverMatrix = new Matrix();
    private final RectF tmp = new RectF();
    private final Path notePath = new Path();
    private Bitmap cover;
    private BitmapShader coverShader;
    private String coverUrl;
    private boolean stack;          // 合集 = 一叠唱片
    private boolean placeholderAmber; // 无封面时用琥珀渐变占位
    private int variant;            // 占位渐变色相变体，行与行错开
    private boolean wantsSpin;      // 正在播放 → 唱片匀速旋转
    private boolean attached;
    private ObjectAnimator spin;

    public DiscThumbView(Context context) {
        super(context);
    }

    /** 正在播放的唱片匀速转（9s 一圈）；暂停/切走停在当前角度。 */
    public void setSpinning(boolean on) {
        wantsSpin = on;
        if (!attached) return;
        if (on) startSpin(); else stopSpin();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        if (wantsSpin) startSpin();
    }

    @Override protected void onDetachedFromWindow() {
        attached = false;
        stopSpin();
        super.onDetachedFromWindow();
    }

    private void startSpin() {
        if (spin != null && spin.isRunning()) return;
        // 从当前角度续 360°，避免重建时角度跳回 0
        spin = ObjectAnimator.ofFloat(this, View.ROTATION,
                getRotation(), getRotation() + 360f);
        spin.setDuration(9000);
        spin.setInterpolator(new LinearInterpolator());
        spin.setRepeatCount(ValueAnimator.INFINITE);
        spin.setRepeatMode(ValueAnimator.RESTART);
        spin.start();
    }

    private void stopSpin() {
        if (spin != null) {
            spin.cancel();
            spin = null;
        }
    }

    /** 单张唱片。 */
    public void setTrack(String url, boolean amberPlaceholder, int variant) {
        stack = false;
        coverUrl = url;
        placeholderAmber = amberPlaceholder;
        this.variant = variant;
        resetCover();
    }

    /** 一叠唱片（合集）。 */
    public void setCollection(String url, boolean amberPlaceholder, int variant) {
        stack = true;
        coverUrl = url;
        placeholderAmber = amberPlaceholder;
        this.variant = variant;
        resetCover();
    }

    private void resetCover() {
        cover = null;
        coverShader = null;
        invalidate();
        if (coverUrl != null && !coverUrl.isEmpty()) {
            final String url = coverUrl;
            CoverCache.get().loadAsync(url, bitmap -> {
                if (!url.equals(coverUrl)) return;
                cover = bitmap;
                coverShader = null;
                invalidate();
            });
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        float dp = getResources().getDisplayMetrics().density;
        int w = getWidth(), h = getHeight();
        // 半径随容器自适应：迷你条只有 40dp，写死 24dp 会被裁成不圆的月牙
        float r = Math.min(w, h) / 2f - dp;
        if (stack) {
            // 叠唱片：三张向左上错位；只有最上层带高光
            drawDisc(canvas, w / 2f - r * 0.36f, h / 2f - r * 0.30f, r * 0.9f, 0.36f, false, dp);
            drawDisc(canvas, w / 2f - r * 0.18f, h / 2f - r * 0.15f, r * 0.955f, 0.62f, false, dp);
            drawDisc(canvas, w / 2f, h / 2f, r, 1f, true, dp);
        } else {
            drawDisc(canvas, w / 2f, h / 2f, r, 1f, true, dp);
        }
    }

    /** 画一张：经典黑胶（近纯黑盘面 + 极淡沟槽 + 弧形高光 + 金色细边）+ 58% 中心封面标签。 */
    private void drawDisc(Canvas canvas, float cx, float cy, float r,
                          float alpha, boolean sheen, float dp) {
        int save = canvas.save();
        canvas.saveLayerAlpha(cx - r - 1, cy - r - 1, cx + r + 1, cy + r + 1,
                Math.round(255 * alpha));

        // 1. 盘面：经典黑胶——近纯黑，边缘略收深，避免发灰
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new RadialGradient(cx, cy, r,
                new int[]{Color.rgb(24, 24, 26), Color.rgb(16, 16, 18), Color.rgb(9, 9, 11)},
                new float[]{0f, 0.62f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, r, paint);
        paint.setShader(null);

        // 2. 沟槽：极淡的细丝纹，只在近处隐约可见，不做明暗年轮
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(0.7f * dp);
        paint.setColor(Color.argb(20, 255, 255, 255)); // 冷白 ~8%
        int rings = 16;
        for (int i = 0; i < rings; i++) {
            float rr = r * (0.66f + 0.31f * i / (float) rings);
            canvas.drawCircle(cx, cy, rr, paint);
        }

        // 3. 高光：一道明显的弧形反射扫过盘面（只画最上层，绝不压到中心封面）
        if (sheen) {
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new SweepGradient(cx, cy,
                    new int[]{Color.TRANSPARENT, Color.argb(46, 255, 255, 255), Color.TRANSPARENT,
                            Color.TRANSPARENT, Color.argb(22, 255, 255, 255), Color.TRANSPARENT},
                    new float[]{0f, 0.10f, 0.22f, 0.52f, 0.60f, 0.72f}));
            canvas.drawCircle(cx, cy, r * 0.96f, paint);
            paint.setShader(null);
        }

        // 4. 金色细边框：极细、淡雅的一圈琥珀金，只做轻微点缀
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(0.7f * dp);
        paint.setColor(Color.argb(120, 227, 179, 106)); // #E3B36A 琥珀金，低透明度
        canvas.drawCircle(cx, cy, r - 0.5f * dp, paint);

        // 4. 中心标签 58%：封面裁圆（铺满标签）或琥珀渐变占位
        //    关键：画封面前必须把 paint 恢复成不透明白——上面沟槽把 paint 设成了半透明，
        //    若不带白恢复，封面会以极低不透明度叠在黑盘上，看起来又暗又灰、看不清。
        float labelR = r * 0.58f;
        paint.setShader(null);
        paint.setColor(Color.WHITE);
        paint.setAlpha(255);
        paint.setStyle(Paint.Style.FILL);
        if (cover != null && !cover.isRecycled()) {
            if (coverShader == null) {
                coverShader = new BitmapShader(cover, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            }
            float side = Math.min(cover.getWidth(), cover.getHeight());
            float scale = (labelR * 2f) / Math.max(1f, side);
            coverMatrix.reset();
            coverMatrix.setScale(scale, scale);
            coverMatrix.postTranslate(cx - cover.getWidth() * scale / 2f,
                    cy - cover.getHeight() * scale / 2f);
            coverShader.setLocalMatrix(coverMatrix);
            paint.setShader(coverShader);
            canvas.drawCircle(cx, cy, labelR, paint);
            paint.setShader(null);
        } else {
            float[] amber;
            switch (variant % 4) {
                case 1: amber = new float[]{0.62f, 0.55f, 0.42f}; break;
                case 2: amber = new float[]{0.55f, 0.47f, 0.38f}; break;
                case 3: amber = new float[]{0.58f, 0.42f, 0.30f}; break;
                default: amber = new float[]{0.72f, 0.55f, 0.32f};
            }
            paint.setShader(new RadialGradient(cx - labelR * 0.34f, cy - labelR * 0.4f,
                    labelR * 1.7f,
                    new int[]{Color.rgb((int) (amber[0] * 255 + 40),
                            (int) (amber[1] * 255 + 34), (int) (amber[2] * 255 + 22)),
                            Color.rgb(Math.round(amber[0] * 255), Math.round(amber[1] * 255),
                                    Math.round(amber[2] * 255)),
                            Color.rgb(Math.round(amber[0] * 120), Math.round(amber[1] * 110),
                                    Math.round(amber[2] * 70))},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, labelR, paint);
            paint.setShader(null);
            if (alpha >= 0.99f) drawNote(canvas, cx, cy, labelR * 0.30f);
        }

        canvas.restoreToCount(save);
        paint.setShader(null);
    }

    /** 占位标签上的八分音符（与播放页大唱片同款语言）。 */
    private void drawNote(Canvas canvas, float cx, float cy, float s) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(190, 36, 26, 12));
        tmp.set(cx - s * 0.80f, cy + s * 0.16f, cx + s * 0.14f, cy + s * 0.86f);
        canvas.drawOval(tmp, paint);
        canvas.drawRect(cx + s * 0.02f, cy - s * 0.92f,
                cx + s * 0.22f, cy + s * 0.54f, paint);
        notePath.reset();
        notePath.moveTo(cx + s * 0.22f, cy - s * 0.92f);
        notePath.quadTo(cx + s * 1.02f, cy - s * 0.60f, cx + s * 0.78f, cy + s * 0.06f);
        notePath.lineTo(cx + s * 0.58f, cy - s * 0.14f);
        notePath.quadTo(cx + s * 0.74f, cy - s * 0.54f, cx + s * 0.22f, cy - s * 0.60f);
        notePath.close();
        canvas.drawPath(notePath, paint);
    }
}
