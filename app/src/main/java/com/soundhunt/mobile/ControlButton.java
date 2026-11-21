package com.soundhunt.mobile;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/**
 * v2 播放控件：主按钮为琥珀渐变实心圆 + 深墨图标；
 * 副按钮不画底盘，只保留矢量图标（设计稿里的「白色三角」）。
 */
public class ControlButton extends View {
    public static final int PREVIOUS = 0;
    public static final int PLAY = 1;
    public static final int PAUSE = 2;
    public static final int NEXT = 3;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF tmp = new RectF();
    private int mode = PLAY;
    private boolean accent;

    public ControlButton(Context context) {
        super(context);
    }

    public void setMode(int value) {
        if (mode == value) return;
        mode = value;
        invalidate();
    }

    public void setAccent(boolean value) {
        if (accent == value) return;
        accent = value;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float r = Math.min(w, h) / 2f;
        float dp = getResources().getDisplayMetrics().density;
        if (r <= 0) return;

        if (accent) {
            // 玻璃底盘：随主题切换明度，左上偏亮收向边缘暗部，模拟抛光玻璃球面层次
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.WHITE);
            paint.setAlpha(255);
            int[] glass = Theme.night
                    ? new int[]{Color.rgb(52, 48, 42), Color.rgb(30, 28, 26), Color.rgb(17, 16, 16)}
                    : new int[]{Color.rgb(255, 252, 246), Color.rgb(243, 236, 224), Color.rgb(228, 217, 199)};
            paint.setShader(new RadialGradient(
                    cx - r * 0.28f, cy - r * 0.34f, r * 1.25f,
                    glass, new float[]{0f, 0.52f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, r - 1.2f * dp, paint);
            paint.setShader(null);

            // 一圈细金环描边（斜向金属光泽，与唱片金环呼应）
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.4f * dp);
            paint.setShader(new LinearGradient(cx - r, cy - r, cx + r, cy + r,
                    new int[]{Theme.GOLD, Theme.ACCENT, Theme.ACCENT_DEEP},
                    null, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, r - 1.5f * dp, paint);
            paint.setShader(null);
        }

        // 图标：一律暖白细图标（不再用深墨实心，避免廉价的“多媒体键”观感）
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Theme.TEXT);
        float s = r * (accent ? 0.34f : 0.36f);
        switch (mode) {
            case PLAY:
                path.reset();
                path.moveTo(cx - s * 0.56f, cy - s);
                path.lineTo(cx - s * 0.56f, cy + s);
                path.lineTo(cx + s * 0.92f, cy);
                path.close();
                canvas.drawPath(path, paint);
                break;
            case PAUSE:
                tmp.set(cx - s * 0.72f, cy - s, cx - s * 0.24f, cy + s);
                canvas.drawRoundRect(tmp, 1.8f * dp, 1.8f * dp, paint);
                tmp.set(cx + s * 0.24f, cy - s, cx + s * 0.72f, cy + s);
                canvas.drawRoundRect(tmp, 1.8f * dp, 1.8f * dp, paint);
                break;
            case PREVIOUS:
                // 设计图 v6.5：纯白实心左三角，不带竖条
                path.reset();
                path.moveTo(cx + s * 0.34f, cy - s);
                path.lineTo(cx + s * 0.34f, cy + s);
                path.lineTo(cx - s * 0.72f, cy);
                path.close();
                canvas.drawPath(path, paint);
                break;
            case NEXT:
                // 设计图 v6.5：纯白实心右三角，不带竖条
                path.reset();
                path.moveTo(cx - s * 0.34f, cy - s);
                path.lineTo(cx - s * 0.34f, cy + s);
                path.lineTo(cx + s * 0.72f, cy);
                path.close();
                canvas.drawPath(path, paint);
                break;
            default:
                break;
        }
    }
}
