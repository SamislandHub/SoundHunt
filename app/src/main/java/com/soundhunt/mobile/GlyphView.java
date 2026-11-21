package com.soundhunt.mobile;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * v2 统一细线矢量图标（1.6px stroke），对应设计稿的图标字典。
 * 全部 Canvas Path 自绘，不依赖字体字形，不使用字符 / emoji。
 * 旧常量保持兼容，新增图标供首页、歌单、播放页使用。
 */
public class GlyphView extends View {
    public static final int SEARCH = 0;
    public static final int BACK = 1;
    public static final int ARROW = 2;
    public static final int HEART = 3;
    public static final int HEART_FILLED = 4;
    public static final int NOTE = 5;
    public static final int PLAY = 6;
    public static final int CROWN = 7;

    public static final int PAUSE = 8;
    public static final int NEXT = 9;
    public static final int PREVIOUS = 10;
    public static final int REFRESH = 11;
    public static final int HOME = 12;
    public static final int LIST = 13;
    public static final int MORE = 14;
    public static final int SHUFFLE = 15;
    public static final int REPEAT = 16;
    public static final int TIMER = 17;
    public static final int QUEUE = 18;
    public static final int SLIDERS = 19;
    public static final int CHECK = 20;
    public static final int MUSIC_BOX = 21;
    public static final int SCAN = 22;
    public static final int PLUS = 23;
    public static final int SETTINGS = 24;
    public static final int MOON = 25;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF tmp = new RectF();
    private int mode = SEARCH;
    private int glyphColor = Theme.SUB;

    public GlyphView(Context context) {
        super(context);
    }

    public void setMode(int value) {
        if (mode == value) return;
        mode = value;
        invalidate();
    }

    public void setGlyphColor(int value) {
        if (glyphColor == value) return;
        glyphColor = value;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float s = Math.min(getWidth(), getHeight()) * 0.34f;
        float dp = getResources().getDisplayMetrics().density;
        if (s <= 0) return;

        paint.setColor(glyphColor);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.6f * dp);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);

        switch (mode) {
            case SEARCH:
                canvas.drawCircle(cx - s * 0.16f, cy - s * 0.16f, s * 0.62f, paint);
                canvas.drawLine(cx + s * 0.30f, cy + s * 0.30f,
                        cx + s * 0.84f, cy + s * 0.84f, paint);
                break;

            case BACK:
                path.reset();
                path.moveTo(cx + s * 0.24f, cy - s * 0.70f);
                path.lineTo(cx - s * 0.34f, cy);
                path.lineTo(cx + s * 0.24f, cy + s * 0.70f);
                canvas.drawPath(path, paint);
                break;

            case ARROW:
                canvas.drawLine(cx - s * 0.74f, cy, cx + s * 0.58f, cy, paint);
                path.reset();
                path.moveTo(cx + s * 0.10f, cy - s * 0.48f);
                path.lineTo(cx + s * 0.68f, cy);
                path.lineTo(cx + s * 0.10f, cy + s * 0.48f);
                canvas.drawPath(path, paint);
                break;

            case HEART:
            case HEART_FILLED:
                path.reset();
                path.moveTo(cx, cy + s * 0.80f);
                path.cubicTo(cx - s * 1.46f, cy - s * 0.04f,
                        cx - s * 0.62f, cy - s * 1.12f, cx, cy - s * 0.34f);
                path.cubicTo(cx + s * 0.62f, cy - s * 1.12f,
                        cx + s * 1.46f, cy - s * 0.04f, cx, cy + s * 0.80f);
                path.close();
                if (mode == HEART_FILLED) paint.setStyle(Paint.Style.FILL);
                canvas.drawPath(path, paint);
                break;

            case NOTE:
                paint.setStyle(Paint.Style.FILL);
                tmp.set(cx - s * 0.80f, cy + s * 0.16f, cx + s * 0.14f, cy + s * 0.86f);
                canvas.drawOval(tmp, paint);
                canvas.drawRect(cx + s * 0.02f, cy - s * 0.92f,
                        cx + s * 0.22f, cy + s * 0.54f, paint);
                path.reset();
                path.moveTo(cx + s * 0.22f, cy - s * 0.92f);
                path.quadTo(cx + s * 1.02f, cy - s * 0.60f, cx + s * 0.78f, cy + s * 0.06f);
                path.lineTo(cx + s * 0.58f, cy - s * 0.14f);
                path.quadTo(cx + s * 0.74f, cy - s * 0.54f, cx + s * 0.22f, cy - s * 0.60f);
                path.close();
                canvas.drawPath(path, paint);
                break;

            case PLAY:
                path.reset();
                path.moveTo(cx - s * 0.50f, cy - s * 0.74f);
                path.lineTo(cx - s * 0.50f, cy + s * 0.74f);
                path.lineTo(cx + s * 0.76f, cy);
                path.close();
                paint.setStyle(Paint.Style.FILL);
                canvas.drawPath(path, paint);
                break;

            case CROWN:
                path.reset();
                path.moveTo(cx - s * 0.90f, cy + s * 0.58f);
                path.lineTo(cx - s * 1.02f, cy - s * 0.56f);
                path.lineTo(cx - s * 0.42f, cy - s * 0.12f);
                path.lineTo(cx, cy - s * 0.88f);
                path.lineTo(cx + s * 0.42f, cy - s * 0.12f);
                path.lineTo(cx + s * 1.02f, cy - s * 0.56f);
                path.lineTo(cx + s * 0.90f, cy + s * 0.58f);
                path.close();
                paint.setStyle(Paint.Style.FILL);
                canvas.drawPath(path, paint);
                break;

            case PAUSE:
                paint.setStyle(Paint.Style.FILL);
                tmp.set(cx - s * 0.60f, cy - s * 0.82f, cx - s * 0.20f, cy + s * 0.82f);
                canvas.drawRoundRect(tmp, 1.6f * dp, 1.6f * dp, paint);
                tmp.set(cx + s * 0.20f, cy - s * 0.82f, cx + s * 0.60f, cy + s * 0.82f);
                canvas.drawRoundRect(tmp, 1.6f * dp, 1.6f * dp, paint);
                break;

            case NEXT:
                paint.setStyle(Paint.Style.FILL);
                path.reset();
                path.moveTo(cx - s * 0.74f, cy - s * 0.80f);
                path.lineTo(cx + s * 0.28f, cy);
                path.lineTo(cx - s * 0.74f, cy + s * 0.80f);
                path.close();
                canvas.drawPath(path, paint);
                tmp.set(cx + s * 0.54f, cy - s * 0.80f, cx + s * 0.76f, cy + s * 0.80f);
                canvas.drawRect(tmp, paint);
                break;

            case PREVIOUS:
                paint.setStyle(Paint.Style.FILL);
                path.reset();
                path.moveTo(cx + s * 0.74f, cy - s * 0.80f);
                path.lineTo(cx - s * 0.28f, cy);
                path.lineTo(cx + s * 0.74f, cy + s * 0.80f);
                path.close();
                canvas.drawPath(path, paint);
                tmp.set(cx - s * 0.76f, cy - s * 0.80f, cx - s * 0.54f, cy + s * 0.80f);
                canvas.drawRect(tmp, paint);
                break;

            case REFRESH:
                tmp.set(cx - s * 0.78f, cy - s * 0.78f, cx + s * 0.78f, cy + s * 0.78f);
                canvas.drawArc(tmp, 34, 308, false, paint);
                path.reset();
                path.moveTo(cx + s * 0.44f, cy - s * 0.86f);
                path.lineTo(cx + s * 0.90f, cy - s * 0.30f);
                path.lineTo(cx + s * 0.36f, cy - s * 0.10f);
                path.close();
                paint.setStyle(Paint.Style.FILL);
                canvas.drawPath(path, paint);
                break;

            case HOME:
                path.reset();
                path.moveTo(cx - s * 0.84f, cy - s * 0.02f);
                path.lineTo(cx, cy - s * 0.80f);
                path.lineTo(cx + s * 0.84f, cy - s * 0.02f);
                path.moveTo(cx - s * 0.62f, cy + s * 0.10f);
                path.lineTo(cx - s * 0.62f, cy + s * 0.80f);
                path.lineTo(cx + s * 0.62f, cy + s * 0.80f);
                path.lineTo(cx + s * 0.62f, cy + s * 0.10f);
                path.moveTo(cx - s * 0.24f, cy + s * 0.80f);
                path.lineTo(cx - s * 0.24f, cy + s * 0.32f);
                path.lineTo(cx + s * 0.24f, cy + s * 0.32f);
                path.lineTo(cx + s * 0.24f, cy + s * 0.80f);
                canvas.drawPath(path, paint);
                break;

            case LIST:
                canvas.drawLine(cx - s * 0.80f, cy - s * 0.50f, cx + s * 0.80f, cy - s * 0.50f, paint);
                canvas.drawLine(cx - s * 0.80f, cy, cx + s * 0.80f, cy, paint);
                canvas.drawLine(cx - s * 0.80f, cy + s * 0.50f, cx + s * 0.30f, cy + s * 0.50f, paint);
                break;

            case MORE:
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(cx - s * 0.62f, cy, 1.15f * dp, paint);
                canvas.drawCircle(cx, cy, 1.15f * dp, paint);
                canvas.drawCircle(cx + s * 0.62f, cy, 1.15f * dp, paint);
                break;

            case SHUFFLE:
                // 设计图：两条平滑交叉曲线，右侧各带一个箭头（标准 shuffle）
                // 曲线一：左上 → 右下（穿过中心），末端箭头朝右下
                path.reset();
                path.moveTo(cx - s * 0.92f, cy - s * 0.66f);
                path.cubicTo(cx - s * 0.30f, cy - s * 0.66f,
                        cx - s * 0.20f, cy, cx, cy);
                path.cubicTo(cx + s * 0.22f, cy,
                        cx + s * 0.34f, cy + s * 0.66f,
                        cx + s * 0.92f, cy + s * 0.66f);
                canvas.drawPath(path, paint);
                // 曲线二：左下 → 右上，末端箭头朝右上
                path.reset();
                path.moveTo(cx - s * 0.92f, cy + s * 0.66f);
                path.cubicTo(cx - s * 0.38f, cy + s * 0.66f,
                        cx - s * 0.26f, cy, cx - s * 0.02f, cy);
                path.cubicTo(cx + s * 0.30f, cy,
                        cx + s * 0.42f, cy - s * 0.66f,
                        cx + s * 0.92f, cy - s * 0.66f);
                canvas.drawPath(path, paint);
                // 右上箭头
                drawArrow(canvas, cx + s * 0.92f, cy - s * 0.66f, -45f, s, dp);
                // 右下箭头
                drawArrow(canvas, cx + s * 0.92f, cy + s * 0.66f, 45f, s, dp);
                break;

            case REPEAT:
                // 设计图：圆角矩形回环，上边右端箭头朝右、下边左端箭头朝左
                float rw = s * 0.78f, rh = s * 0.52f, rad = s * 0.42f;
                // 上层：从左中 → 上边 → 右端（留箭头缺口）
                path.reset();
                path.moveTo(cx - rw + dp, cy - rh);
                path.lineTo(cx + rw - s * 0.34f - dp, cy - rh);
                canvas.drawPath(path, paint);
                // 下层：从右中 → 下边 → 左端（留箭头缺口）
                path.reset();
                path.moveTo(cx + rw - dp, cy + rh);
                path.lineTo(cx - rw + s * 0.34f + dp, cy + rh);
                canvas.drawPath(path, paint);
                // 左侧半圆连接（上→下）
                tmp.set(cx - rw - rad * 0.32f, cy - rh, cx - rw + rad * 0.68f, cy + rh);
                canvas.drawArc(tmp, 90, 180, false, paint);
                // 右侧半圆连接（下→上）
                tmp.set(cx + rw - rad * 0.68f, cy - rh, cx + rw + rad * 0.32f, cy + rh);
                canvas.drawArc(tmp, -90, 180, false, paint);
                // 上边箭头（朝右）与下边箭头（朝左）
                drawArrow(canvas, cx + rw - s * 0.22f, cy - rh, 0f, s, dp);
                drawArrow(canvas, cx - rw + s * 0.22f, cy + rh, 180f, s, dp);
                break;

            case TIMER:
                canvas.drawCircle(cx, cy + s * 0.10f, s * 0.70f, paint);
                canvas.drawLine(cx, cy + s * 0.10f, cx, cy - s * 0.30f, paint);
                canvas.drawLine(cx, cy + s * 0.10f, cx + s * 0.32f, cy + s * 0.22f, paint);
                canvas.drawLine(cx - s * 0.18f, cy - s * 0.62f, cx + s * 0.18f, cy - s * 0.62f, paint);
                break;

            case QUEUE:
                canvas.drawLine(cx - s * 0.84f, cy - s * 0.52f, cx + s * 0.28f, cy - s * 0.52f, paint);
                canvas.drawLine(cx - s * 0.84f, cy, cx + s * 0.28f, cy, paint);
                canvas.drawLine(cx - s * 0.84f, cy + s * 0.52f, cx + s * 0.28f, cy + s * 0.52f, paint);
                canvas.drawLine(cx + s * 0.62f, cy + s * 0.14f, cx + s * 0.62f, cy + s * 0.86f, paint);
                canvas.drawLine(cx + s * 0.28f, cy + s * 0.50f, cx + s * 0.96f, cy + s * 0.50f, paint);
                break;

            case SLIDERS:
                canvas.drawLine(cx - s * 0.86f, cy - s * 0.42f, cx - s * 0.10f, cy - s * 0.42f, paint);
                canvas.drawLine(cx + s * 0.30f, cy - s * 0.42f, cx + s * 0.86f, cy - s * 0.42f, paint);
                canvas.drawLine(cx - s * 0.86f, cy + s * 0.42f, cx + s * 0.06f, cy + s * 0.42f, paint);
                canvas.drawLine(cx + s * 0.46f, cy + s * 0.42f, cx + s * 0.86f, cy + s * 0.42f, paint);
                canvas.drawCircle(cx + s * 0.10f, cy - s * 0.42f, s * 0.19f, paint);
                canvas.drawCircle(cx + s * 0.26f, cy + s * 0.42f, s * 0.19f, paint);
                break;

            case CHECK:
                paint.setStrokeWidth(2f * dp);
                path.reset();
                path.moveTo(cx - s * 0.66f, cy - s * 0.02f);
                path.lineTo(cx - s * 0.14f, cy + s * 0.48f);
                path.lineTo(cx + s * 0.70f, cy - s * 0.52f);
                canvas.drawPath(path, paint);
                break;

            case MUSIC_BOX:
                tmp.set(cx - s * 0.84f, cy - s * 0.26f, cx + s * 0.84f, cy + s * 0.78f);
                canvas.drawRoundRect(tmp, s * 0.14f, s * 0.14f, paint);
                path.reset();
                path.moveTo(cx - s * 0.84f, cy - s * 0.26f);
                path.lineTo(cx - s * 0.50f, cy - s * 0.82f);
                path.lineTo(cx + s * 0.84f, cy - s * 0.82f);
                path.lineTo(cx + s * 0.84f, cy - s * 0.26f);
                canvas.drawPath(path, paint);
                tmp.set(cx - s * 0.34f, cy - s * 0.66f, cx - s * 0.04f, cy - s * 0.40f);
                canvas.drawOval(tmp, paint);
                canvas.drawLine(cx - s * 0.04f, cy - s * 0.66f, cx - s * 0.04f, cy - s * 0.02f, paint);
                break;

            case SCAN:
                path.reset();
                path.moveTo(cx - s * 0.80f, cy - s * 0.42f);
                path.lineTo(cx - s * 0.80f, cy - s * 0.80f);
                path.lineTo(cx - s * 0.42f, cy - s * 0.80f);
                path.moveTo(cx + s * 0.42f, cy - s * 0.80f);
                path.lineTo(cx + s * 0.80f, cy - s * 0.80f);
                path.lineTo(cx + s * 0.80f, cy - s * 0.42f);
                path.moveTo(cx - s * 0.80f, cy + s * 0.42f);
                path.lineTo(cx - s * 0.80f, cy + s * 0.80f);
                path.lineTo(cx - s * 0.42f, cy + s * 0.80f);
                path.moveTo(cx + s * 0.42f, cy + s * 0.80f);
                path.lineTo(cx + s * 0.80f, cy + s * 0.80f);
                path.lineTo(cx + s * 0.80f, cy + s * 0.42f);
                canvas.drawPath(path, paint);
                canvas.drawLine(cx - s * 0.54f, cy, cx + s * 0.54f, cy, paint);
                break;

            case PLUS:
                canvas.drawLine(cx - s * 0.62f, cy, cx + s * 0.62f, cy, paint);
                canvas.drawLine(cx, cy - s * 0.62f, cx, cy + s * 0.62f, paint);
                break;

            case MOON:
                // 弯月：两条二次贝塞尔围出的月牙（描边风格）
                path.reset();
                path.moveTo(cx + s * 0.55f, cy - s * 0.68f);
                path.quadTo(cx - s * 0.55f, cy - s * 0.52f, cx - s * 0.52f, cy + s * 0.20f);
                path.quadTo(cx - s * 0.50f, cy + s * 0.78f, cx + s * 0.30f, cy + s * 0.70f);
                path.quadTo(cx - s * 0.18f, cy + s * 0.32f, cx - s * 0.10f, cy - s * 0.14f);
                path.quadTo(cx - s * 0.02f, cy - s * 0.52f, cx + s * 0.55f, cy - s * 0.68f);
                path.close();
                canvas.drawPath(path, paint);
                break;

            case SETTINGS:
                // 齿轮：中心圆 + 8 个沿半径伸出的齿（短线段）
                canvas.drawCircle(cx, cy, s * 0.34f, paint);
                canvas.drawCircle(cx, cy, s * 0.60f, paint);
                for (int i = 0; i < 8; i++) {
                    double ang = Math.PI * 2 * i / 8;
                    float dx = (float) Math.cos(ang), dy = (float) Math.sin(ang);
                    canvas.drawLine(cx + dx * s * 0.62f, cy + dy * s * 0.62f,
                            cx + dx * s * 0.90f, cy + dy * s * 0.90f, paint);
                }
                break;

            default:
                break;
        }
    }

    /** 在 (tx,ty) 画一个朝 dirDeg 方向的 V 形箭头尖（STROKE 模式，两段短线）。 */
    private void drawArrow(Canvas canvas, float tx, float ty, float dirDeg, float s, float dp) {
        float len = s * 0.30f;                       // 箭翼长度
        float spread = 0.62f;                        // 箭翼张开弧度
        float rad = (float) Math.toRadians(dirDeg);
        float backX = (float) Math.cos(rad), backY = (float) Math.sin(rad);
        float px = -backY, py = backX;               // 垂直方向
        for (int sign = -1; sign <= 1; sign += 2) {
            float vx = -backX * len * 0.7f + px * len * spread * sign;
            float vy = -backY * len * 0.7f + py * len * spread * sign;
            canvas.drawLine(tx, ty, tx + vx, ty + vy, paint);
        }
    }
}
