package com.soundhunt.mobile;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/**
 * 声纹粒子光河：一条橙→金→白→蓝的能量脊上，粒子随音乐节拍喷发、重力散落。
 * 参考 AE Particular 粒子本质——关键实现：
 *  - 预渲染多色辉光贴图（RadialGradient 光点存成 Bitmap），绘制时直接贴，不做实时模糊
 *  - PorterDuff.ADD 加性混合：光叠光过曝发白 = 真发光
 *  - 固定容量粒子对象池 + 复用，不每帧 new，避免 GC 卡顿
 *  - 节拍骨架驱动喷射脉冲；低/中/高频分别调制喷射强度/速度/扰动
 * 全程硬件加速，60fps 目标；暂停收平、detach 停刷新。
 */
public class WaveView extends View {

    private static final long BEAT_MS = 60000 / 100;   // 100 BPM 节拍骨架
    private static final long FRAME_MS = 16;           // ~60fps
    private static final int MAX_PARTICLES = 220;
    private static final float PAUSED_SCALE = 0.05f;
    private static final int SPRITE_COUNT = 10;

    // 粒子对象池（SoA 结构，全程复用，不分配对象）
    private final boolean[] alive = new boolean[MAX_PARTICLES];
    private final float[] px = new float[MAX_PARTICLES], py = new float[MAX_PARTICLES];
    private final float[] vx = new float[MAX_PARTICLES], vy = new float[MAX_PARTICLES];
    private final float[] life = new float[MAX_PARTICLES], maxLife = new float[MAX_PARTICLES];
    private final float[] size = new float[MAX_PARTICLES], alpha = new float[MAX_PARTICLES];
    private final int[] spriteIdx = new int[MAX_PARTICLES];
    private final float[] trailX = new float[MAX_PARTICLES * 3];
    private final float[] trailY = new float[MAX_PARTICLES * 3];
    private int cursor = 0;

    private static final int[] RAMP = {
            Color.rgb(255, 110, 60), Color.rgb(255, 168, 92),
            Color.rgb(255, 205, 120), Color.rgb(255, 236, 190),
            Color.rgb(255, 248, 232), Color.rgb(206, 222, 255),
            Color.rgb(166, 202, 255), Color.rgb(130, 180, 255),
            Color.rgb(104, 160, 255), Color.rgb(88, 148, 255),
    };
    private final Bitmap[] sprites = new Bitmap[SPRITE_COUNT];
    private final Paint spritePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ridgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path ridgePath = new Path();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean attached, playing, layoutDone, ticking;
    private long lastFrame;
    private float ampScale = 1f, targetScale = 1f;
    private float beatEnv = 0f;
    private final float[] target = new float[3];
    private final float[] smooth = new float[3];
    private long lastBandAt;

    public WaveView(Context context) { super(context); init(); }
    public WaveView(Context context, AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        // ADD 混合挂在 Paint 上（Canvas 没有 setXfermode）——硬件加速下正常工作
        spritePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        ridgePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        ridgePaint.setStyle(Paint.Style.STROKE);
        ridgePaint.setStrokeCap(Paint.Cap.ROUND);
        ridgePaint.setStrokeJoin(Paint.Join.ROUND);
        for (int i = 0; i < SPRITE_COUNT; i++) sprites[i] = makeSprite(RAMP[i]);
    }

    /** 预渲染径向衰减辉光光点：中心实色，边缘透明。 */
    private Bitmap makeSprite(int color) {
        int s = 48;
        Bitmap bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float r = s / 2f;
        int edge = Color.argb(0, Color.red(color), Color.green(color), Color.blue(color));
        p.setShader(new RadialGradient(r, r, r,
                new int[]{color, color, edge}, new float[]{0f, 0.16f, 1f},
                Shader.TileMode.CLAMP));
        c.drawCircle(r, r, r, p);
        return bmp;
    }

    public void setPlaying(boolean playing) {
        if (this.playing == playing) return;
        this.playing = playing;
        targetScale = playing ? 1f : PAUSED_SCALE;
        ensureTicker();
    }

    public void setBands(float bass, float mid, float hi) {
        target[0] = bass; target[1] = mid; target[2] = hi;
        lastBandAt = SystemClock.uptimeMillis();
        ensureTicker();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow(); attached = true; ensureTicker();
    }

    @Override protected void onDetachedFromWindow() {
        attached = false;
        handler.removeCallbacksAndMessages(null);
        super.onDetachedFromWindow();
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0) layoutDone = true;
    }

    private void ensureTicker() {
        if (!attached || ticking) return;
        ticking = true; lastFrame = 0;
        handler.post(ticker);
    }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            long now = SystemClock.uptimeMillis();
            float dt = lastFrame == 0 ? FRAME_MS : Math.min(now - lastFrame, 100f);
            lastFrame = now;

            ampScale += (targetScale - ampScale) * Math.min(1f, dt / 220f);
            for (int i = 0; i < 3; i++) {
                float tau = target[i] > smooth[i] ? 60f : 200f;
                smooth[i] += (target[i] - smooth[i]) * Math.min(1f, dt / tau);
            }
            if (now - lastBandAt > 300) {
                for (int i = 0; i < 3; i++) target[i] *= 0.94f;
            }
            float phase = (now % BEAT_MS) / (float) BEAT_MS;
            float kick = (float) Math.exp(-phase * 4.5f);
            float targetEnv = playing ? kick : 0f;
            beatEnv += (targetEnv - beatEnv) * Math.min(1f, dt / (kick > beatEnv ? 24f : 130f));

            boolean settled = !playing && ampScale < 0.006f && beatEnv < 0.006f
                    && target[0] + target[1] + target[2] < 0.005f;
            if (settled) {
                ampScale = targetScale; beatEnv = 0f;
                for (int i = 0; i < MAX_PARTICLES; i++) alive[i] = false;
                ticking = false; invalidate(); return;
            }
            invalidate();
            handler.postDelayed(this, FRAME_MS);
        }
    };

    /** 能量脊相对 cy 的纵向偏移（px，向上为负）。 */
    private float ridgeOffset(float n, long now, float amp) {
        float env = (float) Math.pow(Math.sin(Math.PI * n), 0.7);
        float wave = (float) Math.sin(2 * Math.PI * n * 1.6 + now * 0.0009f);
        float spike = (float) Math.pow(Math.sin(2 * Math.PI * n * 4.3 - now * 0.0015f), 5);
        return -env * (0.45f + 0.35f * wave + 0.30f * spike) * amp;
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!layoutDone) return;
        int w = getWidth(), h = getHeight();
        long now = SystemClock.uptimeMillis();
        float dp = getResources().getDisplayMetrics().density;

        int pad = Math.round(26 * Math.max(1f, dp));
        float span = w - 2f * pad;
        float cy = h * 0.60f;
        float amp = h * 0.16f * ampScale;

        boolean live = smooth[0] + smooth[1] + smooth[2] > 0.03f;
        float surge = 0.45f + 1.5f * beatEnv;
        if (live) surge *= (0.7f + smooth[0] * 0.9f + smooth[2] * 0.3f);
        surge *= ampScale;
        int budget = Math.round(2 + 9 * surge);

        // 发射
        if (playing && ampScale > 0.1f) {
            for (int k = 0; k < budget; k++) {
                float n = (float) Math.random();
                float off = ridgeOffset(n, now, amp);
                float e = Math.min(1f, Math.abs(off) / Math.max(1f, amp));
                float speed = (0.06f + e * 0.16f) * (1f + 0.4f * beatEnv)
                        * (live ? (0.85f + smooth[1] * 0.5f) : 1f);
                float ivx = ((float) Math.random() - 0.5f) * 0.09f * dp + (n - 0.5f) * 0.06f * dp;
                float ivy = -speed * dp * (0.7f + 0.6f * (float) Math.random());
                float sz = (7f + 10f * (float) Math.random()) * dp;
                int si = Math.min(SPRITE_COUNT - 1, (int) (n * SPRITE_COUNT));
                int ml = (int) (700 + 800 * (float) Math.random());
                spawn(pad + n * span, cy + off, ivx, ivy, sz, si, ml);
            }
        }

        // 更新
        for (int i = 0; i < MAX_PARTICLES; i++) {
            if (!alive[i]) continue;
            life[i] += FRAME_MS;
            if (life[i] >= maxLife[i]) { alive[i] = false; continue; }
            int b = i * 3;
            trailX[b + 2] = trailX[b + 1]; trailY[b + 2] = trailY[b + 1];
            trailX[b + 1] = trailX[b];     trailY[b + 1] = trailY[b];
            trailX[b] = px[i];             trailY[b] = py[i];
            vy[i] += 0.00022f * dp * FRAME_MS;
            vx[i] *= 0.988f; vy[i] *= 0.988f;
            px[i] += vx[i] * FRAME_MS;
            py[i] += vy[i] * FRAME_MS;
            float t = life[i] / maxLife[i];
            float fade = (1f - t) * (1f - t) * Math.min(1f, t * 9f);
            alpha[i] = Math.max(0f, Math.min(1f, fade));
        }

        // 绘制粒子（ADD）
        for (int i = 0; i < MAX_PARTICLES; i++) {
            if (!alive[i] || alpha[i] <= 0f) continue;
            Bitmap spr = sprites[spriteIdx[i]];
            int b = i * 3;
            for (int t = 0; t < 3; t++) {
                if (trailX[b + t] == 0f && trailY[b + t] == 0f) continue;
                float tw = size[i] * (0.32f + 0.16f * t);
                drawSprite(canvas, spr, trailX[b + t], trailY[b + t], tw,
                        (int) (46 * alpha[i] * (t + 1) / 3f));
            }
            drawSprite(canvas, spr, px[i], py[i], size[i], (int) (215 * alpha[i]));
        }

        // 绘制能量脊（分段着色，ADD）
        ridgePaint.setStrokeWidth(2.2f * dp);
        ridgePaint.setAlpha(150);
        for (int seg = 0; seg < SPRITE_COUNT; seg++) {
            float n0 = seg / (float) SPRITE_COUNT;
            float n1 = (seg + 1) / (float) SPRITE_COUNT;
            ridgePaint.setColor(RAMP[seg]);
            float x0 = pad + n0 * span, x1 = pad + n1 * span;
            float y0 = cy + ridgeOffset(n0, now, amp);
            float ym = cy + ridgeOffset((n0 + n1) * 0.5f, now, amp);
            float y1 = cy + ridgeOffset(n1, now, amp);
            ridgePath.reset();
            ridgePath.moveTo(x0, y0);
            ridgePath.quadTo(x1, ym, x1, y1);
            canvas.drawPath(ridgePath, ridgePaint);
        }
    }

    private void drawSprite(Canvas canvas, Bitmap spr, float x, float y, float size, int a) {
        if (a <= 0) return;
        spritePaint.setAlpha(a);
        float half = size / 2f;
        canvas.drawBitmap(spr, x - half, y - half, spritePaint);
    }

    private void spawn(float x, float y, float ivx, float ivy, float sz, int si, int ml) {
        int i = cursor; cursor = (cursor + 1) % MAX_PARTICLES;
        alive[i] = true;
        px[i] = x; py[i] = y; vx[i] = ivx; vy[i] = ivy;
        size[i] = sz; spriteIdx[i] = si; life[i] = 0; maxLife[i] = ml;
        int b = i * 3;
        trailX[b] = trailX[b + 1] = trailX[b + 2] = 0f;
        trailY[b] = trailY[b + 1] = trailY[b + 2] = 0f;
    }
}
