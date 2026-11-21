package com.soundhunt.mobile;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 设置页（当前仅一项：深色模式）。
 * 纯代码构建，配色全部走 Theme，切换后写回 SharedPreferences 并重建界面。
 */
public class SettingsActivity extends Activity {
    private ToggleView themeToggle;

    @Override protected void onCreate(Bundle savedInstanceState) {
        Theme.init(this);
        super.onCreate(savedInstanceState);
        Theme.applySystemBars(this);
        buildUi();
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackground(Theme.ambient(this));
        setContentView(root);
        Theme.applySafeArea(root);

        int pad = dp(22);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, dp(10), pad, dp(24));
        root.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 顶栏：返回 + 标题
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        content.addView(top, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        FrameLayout back = new FrameLayout(this);
        GlyphView backIcon = glyph(GlyphView.BACK, Theme.TEXT, dp(19));
        back.addView(backIcon, new FrameLayout.LayoutParams(dp(19), dp(19), Gravity.CENTER));
        back.setOnClickListener(v -> finish());
        top.addView(back, lp(dp(40), dp(40)));

        TextView title = text("设置", 22, Theme.TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setPadding(dp(8), 0, 0, 0);
        top.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        // 分组标题
        TextView groupTitle = text("外观", 12, Theme.DIM);
        groupTitle.setPadding(dp(4), dp(30), 0, dp(10));
        content.addView(groupTitle);

        // 设置卡片：深色模式
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16), 0, dp(15), 0);
        card.setBackground(Theme.glass(this, 16));
        content.addView(card, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)));

        GlyphView icon = glyph(GlyphView.MOON, Theme.ACCENT, dp(19));
        card.addView(icon, lp(dp(19), dp(19)));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(13), 0, 0, 0);
        card.addView(labels, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView name = text("深色模式", 14, Theme.TEXT);
        labels.addView(name);
        TextView sub = text("切换应用的整体配色", 10.5f, Theme.DIM);
        sub.setPadding(0, dp(3), 0, 0);
        labels.addView(sub);

        themeToggle = new ToggleView(this);
        themeToggle.setChecked(Theme.night, false);
        themeToggle.setOnChangeListener(checked -> {
            Theme.setNight(this, checked);
            // 稍作延迟让开关动画可见，再重建当前页与返回后的主页面
            themeToggle.postDelayed(this::recreateUI, 180);
        });
        card.addView(themeToggle, lp(dp(46), dp(28)));

        TextView hint = text("切换后会立即生效", 11, Theme.DIM);
        hint.setPadding(dp(4), dp(16), 0, 0);
        content.addView(hint);
    }

    private void recreateUI() {
        Theme.applySystemBars(this);
        recreate();
    }

    // ---------------- 工具 ----------------

    private GlyphView glyph(int mode, int color, int size) {
        GlyphView g = new GlyphView(this);
        g.setMode(mode);
        g.setGlyphColor(color);
        g.setLayoutParams(lp(size, size));
        return g;
    }

    private TextView text(String value, float sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        return view;
    }

    private LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    private int dp(float v) {
        return Theme.dp(this, v);
    }

    /** 自绘开关：圆角轨道 + 滑块，带 200ms 位移动画。 */
    private static class ToggleView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF track = new RectF();
        private boolean checked;
        private float progress;
        private ValueAnimator animator;
        private OnChangeListener listener;
        private boolean fromUser;

        interface OnChangeListener { void onChanged(boolean checked); }

        ToggleView(Activity activity) {
            super(activity);
            progress = 0f;
            setOnClickListener(v -> {
                boolean next = !checked;
                setChecked(next, true);
                // 关键：用户点击必须回调，否则主题不会真正切换
                if (listener != null) listener.onChanged(next);
            });
        }

        void setOnChangeListener(OnChangeListener l) { listener = l; }

        void setChecked(boolean value, boolean animate) {
            if (checked == value && animator == null) {
                progress = value ? 1f : 0f;
                invalidate();
                return;
            }
            checked = value;
            if (animator != null) animator.cancel();
            if (!animate) {
                progress = value ? 1f : 0f;
                invalidate();
                return;
            }
            float start = progress, end = value ? 1f : 0f;
            animator = ValueAnimator.ofFloat(start, end);
            animator.setDuration(200);
            animator.setInterpolator(new DecelerateInterpolator());
            animator.addUpdateListener(a -> {
                progress = (float) a.getAnimatedValue();
                invalidate();
            });
            animator.start();
        }

        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float dp = getResources().getDisplayMetrics().density;
            float r = h / 2f;

            track.set(0, 0, w, h);
            // 轨道底色：关=灰，开=琥珀
            int off = Theme.withAlpha(Theme.SUB, 70);
            int on = Theme.ACCENT;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(blend(off, on, progress));
            canvas.drawRoundRect(track, r, r, paint);

            // 滑块：在轨道内左右滑动
            float pad = 3f * dp;
            float knobR = r - pad;
            float x = pad + knobR + (w - 2 * pad - 2 * knobR) * progress;
            paint.setColor(Color.WHITE);
            canvas.drawCircle(x, r, knobR, paint);
        }

        private static int blend(int a, int b, float t) {
            int ar = Color.red(a), ag = Color.green(a), ab = Color.blue(a);
            int br = Color.red(b), bg = Color.green(b), bb = Color.blue(b);
            return Color.rgb((int) (ar + (br - ar) * t),
                    (int) (ag + (bg - ag) * t),
                    (int) (ab + (bb - ab) * t));
        }
    }
}
