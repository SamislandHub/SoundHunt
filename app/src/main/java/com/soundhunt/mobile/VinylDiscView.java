package com.soundhunt.mobile;

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
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;

/**
 * 黑胶唱片：纯黑胶沟槽 + 中心圆形封面 + 冷白高光。
 * 转速由音乐频谱驱动——低频能量越强转得越快，能量弱时回落到基础转速，
 * 暂停时缓缓停下。旋转用自身 ticker 累加 View.rotation，硬件加速、无额外对象分配。
 */
public class VinylDiscView extends View {
    private static final long FRAME_MS = 16;
    private static final float BASE_DPS = 24f;   // 基础转速：度/秒（≈15秒/圈）
    private static final float MAX_DPS = 150f;   // 满能量转速（≈2.4秒/圈）

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean attached, playing, ticking;
    private long lastFrame;
    private float angle = 0f;
    private float curDps = 0f;          // 当前角速度（平滑）
    private float energy = 0f;          // 平滑后的低频能量 0..1
    private float targetEnergy = 0f;
    private long lastBandAt;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix coverMatrix = new Matrix();
    private final Path notePath = new Path();
    private final RectF tmp = new RectF();
    private Bitmap cover;
    private BitmapShader coverShader;
    private String coverUrl;

    public VinylDiscView(Context context) {
        super(context);
    }

    public void setCoverUrl(String url) {
        coverUrl = url;
        cover = null;
        coverShader = null;
        invalidate();
        if (url != null && !url.isEmpty()) {
            CoverCache.get().loadAsync(url, bitmap -> {
                if (!url.equals(coverUrl)) return;
                setCover(bitmap);
            });
        }
    }

    public void setCover(Bitmap bitmap) {
        cover = bitmap;
        coverShader = null;
        invalidate();
    }

    /** 播放/暂停：播放恢复基础转速，暂停让唱片缓缓停下（不瞬间定格）。 */
    public void setPlaying(boolean playing) {
        if (this.playing == playing) return;
        this.playing = playing;
        ensureTicker();
    }

    /** 外部喂入低频能量（0..1），驱动转速。由播放页从 Visualizer 读取。 */
    public void setBands(float bass, float mid, float hi) {
        targetEnergy = Math.max(0f, Math.min(1f, bass));
        lastBandAt = SystemClock.uptimeMillis();
        ensureTicker();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        ensureTicker();
    }

    @Override protected void onDetachedFromWindow() {
        attached = false;
        handler.removeCallbacksAndMessages(null);
        super.onDetachedFromWindow();
    }

    private void ensureTicker() {
        if (!attached || ticking) return;
        ticking = true;
        lastFrame = 0;
        handler.post(ticker);
    }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            long now = SystemClock.uptimeMillis();
            float dt = lastFrame == 0 ? FRAME_MS : Math.min(now - lastFrame, 100f);
            lastFrame = now;

            // 超过 300ms 没新数据：缓慢回落，并用节拍脉冲兜底（机型采不到频谱时也随节奏转）
            if (now - lastBandAt > 300) {
                targetEnergy *= 0.94f;
                if (playing) {
                    float phase = (now % 60000 / 100f) / 600f;  // 100BPM 每拍
                    targetEnergy = Math.max(targetEnergy, (float) Math.exp(-phase * 4f) * 0.8f);
                }
            }
            // 能量平滑：上升快、回落慢，鼓点推动有冲击又不抖
            float tau = targetEnergy > energy ? 90f : 260f;
            energy += (targetEnergy - energy) * Math.min(1f, dt / tau);

            // 目标角速度 = 基础 + 能量增量；暂停时归零
            float targetDps = playing ? (BASE_DPS + (MAX_DPS - BASE_DPS) * energy) : 0f;
            curDps += (targetDps - curDps) * Math.min(1f, dt / 180f);

            if (Math.abs(curDps) > 0.05f) {
                angle = (angle + curDps * dt / 1000f) % 360f;
                setRotation(angle);
            }

            boolean settled = !playing && curDps < 0.05f && energy < 0.005f;
            if (settled) { curDps = 0f; ticking = false; return; }
            handler.postDelayed(this, FRAME_MS);
        }
    };

    /**
     * 抛光香槟金渐变：沿圆周分布两处高光，模拟真实金属随角度的反光。
     * 与唱片同心，旋转时高光会流动；同一 Shader 可复用于外圈/封面边/孔沿。
     */
    private Shader metalGold(float cx, float cy) {
        return new SweepGradient(cx, cy, new int[]{
                Color.rgb(148, 110, 58),   // 暗
                Color.rgb(206, 167, 101),  // 中
                Color.rgb(255, 243, 210),  // 高光
                Color.rgb(206, 167, 101),
                Color.rgb(148, 110, 58),
                Color.rgb(206, 167, 101),
                Color.rgb(255, 243, 210),  // 第二处高光（对称）
                Color.rgb(206, 167, 101),
                Color.rgb(148, 110, 58)}, null);
    }

    @Override protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        // 直径上限 296dp（设计稿），舞台更高时唱片不跟着撑大
        float discR = Math.min(Math.min(w, h) / 2f - Theme.dp(getContext(), 3),
                Theme.dp(getContext(), 148));
        if (discR <= 0) return;
        float dp = getResources().getDisplayMetrics().density;

        // 1. 黑胶本体：中心略亮的石墨灰，向外收到近黑
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new RadialGradient(cx, cy, discR,
                new int[]{Color.rgb(29, 29, 32), Color.rgb(19, 19, 22), Color.rgb(14, 14, 16)},
                new float[]{0f, 0.62f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, discR, paint);
        paint.setShader(null);

        // 2. 细密沟槽：极淡冷白 / 极淡黑交替（起始半径让开中心封面）
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(0.7f * dp);
        int grooves = 46;
        for (int i = 0; i < grooves; i++) {
            float rr = discR * (0.455f + 0.505f * i / (float) grooves);
            paint.setColor(i % 2 == 0
                    ? Color.argb(13, 255, 255, 255)
                    : Color.argb(9, 0, 0, 0));
            canvas.drawCircle(cx, cy, rr, paint);
        }

        // 3. 外圈抛光金属金环：暗底凹槽 + 随角度流动的香槟金高光 + 内缘倒角
        paint.setStyle(Paint.Style.STROKE);
        // 3a. 暗底凹槽（略宽、近黑棕），衬托金环、形成嵌入感
        paint.setStrokeWidth(3.2f * dp);
        paint.setColor(Color.argb(210, 26, 20, 13));
        canvas.drawCircle(cx, cy, discR - 1.6f * dp, paint);
        // 3b. 金属金环本体：SweepGradient 高光随旋转流动
        float rimR = discR - 1.6f * dp;
        paint.setStrokeWidth(1.9f * dp);
        paint.setColor(Color.WHITE);
        paint.setShader(metalGold(cx, cy));
        canvas.drawCircle(cx, cy, rimR, paint);
        paint.setShader(null);
        // 3c. 上缘一道亮高光线，强化抛光倒角
        paint.setStrokeWidth(0.6f * dp);
        paint.setShader(metalGold(cx, cy));
        canvas.drawCircle(cx, cy, rimR + 0.9f * dp, paint);
        paint.setShader(null);
        // 3d. 内圈冷白细线
        paint.setStrokeWidth(0.8f * dp);
        paint.setColor(Color.argb(20, 255, 255, 255));
        canvas.drawCircle(cx, cy, discR * 0.72f, paint);

        // 4. 随唱片旋转的盘面反光带——让“黑胶本体在转”清晰可见的关键。
        //    必须先恢复全不透明：上一步内圈细线把 alpha 留在了 20，若不重置，
        //    SweepGradient 会被按约 8% 绘制，高光几乎看不见（只剩封面在转的错觉）。
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        paint.setAlpha(255);
        paint.setShader(new SweepGradient(cx, cy,
                new int[]{
                        Color.TRANSPARENT,
                        Color.argb(0, 255, 244, 220),
                        Color.argb(62, 255, 244, 220),   // 主反光带
                        Color.argb(0, 255, 244, 220),
                        Color.TRANSPARENT,
                        Color.argb(0, 255, 244, 220),
                        Color.argb(30, 255, 244, 220),   // 次反光带
                        Color.argb(0, 255, 244, 220),
                        Color.TRANSPARENT},
                new float[]{0f, 0.10f, 0.16f, 0.24f, 0.50f, 0.58f, 0.66f, 0.74f, 1f}));
        canvas.drawCircle(cx, cy, discR * 0.99f, paint);
        paint.setShader(null);

        // 5. 中心标签：封面或琥珀渐变占位（半径放大到 0.43，封面更醒目）
        float labelR = discR * 0.43f;
        paint.setStyle(Paint.Style.FILL);
        // 关键：前面画内圈细线时把 paint 的 alpha 留在了 16，若不重置，
        // 封面会被按约 6% 不透明绘制而发灰发暗。这里显式恢复为全不透明原图。
        paint.setColor(Color.WHITE);
        paint.setAlpha(255);
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
            paint.setShader(new RadialGradient(cx - labelR * 0.3f, cy - labelR * 0.3f, labelR * 1.4f,
                    new int[]{Theme.GOLD, Theme.ACCENT_DEEP, Color.rgb(58, 40, 20)},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, labelR, paint);
            paint.setShader(null);
            drawNote(canvas, cx, cy, labelR * 0.42f);
        }

        // 6. 封面周围的抛光金属边框（与外圈同一套流动高光）
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(Color.WHITE);
        // 暗底凹槽
        paint.setStrokeWidth(2.6f * dp);
        paint.setColor(Color.argb(210, 26, 20, 13));
        canvas.drawCircle(cx, cy, labelR - 0.4f * dp, paint);
        // 金属边框本体
        paint.setStrokeWidth(1.5f * dp);
        paint.setColor(Color.WHITE);
        paint.setShader(metalGold(cx, cy));
        canvas.drawCircle(cx, cy, labelR - 0.4f * dp, paint);
        paint.setShader(null);
    }

    /** 无封面时在中心标签上画一枚深墨细线八分音符。 */
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
