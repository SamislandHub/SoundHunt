package com.soundhunt.mobile;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.WindowInsets;
import android.view.ViewOutlineProvider;
import android.widget.SeekBar;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.FrameLayout;
import android.view.Gravity;
import android.view.ViewGroup;

/**
 * 「小点声」v2 视觉体系：墨黑底 + 单一琥珀暖光。
 * 设计稿：小点声-视觉设计稿.html（A 方案）。全 App 唯一配色来源。
 *
 * 规范：
 *   - 层级靠明度与透明度，不靠描边堆叠；全稿只用一个强调色（琥珀）
 *   - 圆角 20-24 卡片 / 12 封面 / 99 胶囊；描边 1px 白色 5%-8%
 *   - 动效 200-280ms，仅 transform / opacity
 *   - 图标 1.6px 细线矢量，不用字符与 emoji；衬线只用于歌名与排名
 */
public final class Theme {
    // ---- 主题模式：dark / light，由 SharedPreferences 持久化 ----
    private static final String PREF = "soundhunt_theme";
    private static final String KEY_NIGHT = "night";
    private static boolean initialized;

    /** 当前是否深色主题。 */
    public static boolean night = true;

    // 背景与表面
    public static int BG;                 // 墨底 / 浅色纸白
    public static int BG_TOP;
    public static int SURFACE;
    public static int CARD;
    public static int CARD_ALT;
    public static int STROKE;
    public static int LINE;
    public static int LINE2;

    // 文字三级
    public static int TEXT;
    public static int SUB;
    public static int DIM;

    // 强调：琥珀
    public static int ACCENT;
    public static int ACCENT_DEEP;
    public static int ON_ACCENT;
    public static int GOLD;
    public static int SOFT;
    public static int GREEN;
    public static int VINYL;
    // 以下三个仅为兼容旧引用保留
    public static int WOOD;
    public static int WOOD_DARK;
    public static int SHADOW;

    /** 顶部暖光晕颜色（ambient 背景用）。 */
    public static int AMBIENT_GLOW;

    /** 从持久化设置加载主题并应用配色；每个 Activity.onCreate 前调用。 */
    public static void init(Context context) {
        if (!initialized) {
            night = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .getBoolean(KEY_NIGHT, true);
            initialized = true;
        }
        applyPalette();
    }

    /** 切换主题并持久化。 */
    public static void setNight(Context context, boolean value) {
        night = value;
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_NIGHT, value).apply();
        applyPalette();
    }

    /** 按当前模式给所有颜色字段赋值。 */
    private static void applyPalette() {
        if (night) {
            BG = Color.rgb(11, 11, 13);
            BG_TOP = Color.rgb(14, 14, 17);
            SURFACE = Color.rgb(20, 20, 23);
            CARD = Color.rgb(26, 26, 30);
            CARD_ALT = Color.rgb(34, 34, 39);
            STROKE = Color.argb(22, 255, 255, 255);
            LINE = Color.argb(20, 255, 255, 255);
            LINE2 = Color.argb(12, 255, 255, 255);
            TEXT = Color.rgb(244, 242, 238);
            SUB = Color.rgb(142, 139, 133);
            DIM = Color.rgb(92, 89, 83);
            ACCENT = Color.rgb(227, 179, 106);
            ACCENT_DEEP = Color.rgb(184, 132, 60);
            ON_ACCENT = Color.rgb(36, 26, 12);
            GOLD = Color.rgb(244, 208, 148);
            SOFT = Color.rgb(38, 30, 20);
            GREEN = Color.rgb(126, 154, 136);
            VINYL = Color.rgb(17, 17, 19);
            WOOD = Color.rgb(44, 42, 39);
            WOOD_DARK = Color.rgb(28, 27, 25);
            SHADOW = Color.rgb(0, 0, 0);
            AMBIENT_GLOW = Color.rgb(28, 24, 18);
        } else {
            // 浅色：暖白纸底 + 深墨文字；强调色加深以保证浅底上的对比度
            BG = Color.rgb(247, 245, 241);
            BG_TOP = Color.rgb(255, 253, 250);
            SURFACE = Color.rgb(255, 255, 255);
            CARD = Color.rgb(255, 255, 255);
            CARD_ALT = Color.rgb(238, 235, 229);
            STROKE = Color.argb(26, 40, 36, 30);
            LINE = Color.argb(22, 40, 36, 30);
            LINE2 = Color.argb(14, 40, 36, 30);
            TEXT = Color.rgb(30, 28, 25);
            SUB = Color.rgb(104, 100, 93);
            DIM = Color.rgb(150, 145, 137);
            ACCENT = Color.rgb(166, 112, 38);
            ACCENT_DEEP = Color.rgb(138, 90, 28);
            ON_ACCENT = Color.rgb(255, 253, 248);
            GOLD = Color.rgb(176, 122, 46);
            SOFT = Color.rgb(244, 234, 216);
            GREEN = Color.rgb(74, 118, 92);
            VINYL = Color.rgb(28, 28, 30);
            WOOD = Color.rgb(214, 208, 198);
            WOOD_DARK = Color.rgb(196, 190, 180);
            SHADOW = Color.rgb(120, 112, 100);
            AMBIENT_GLOW = Color.rgb(250, 240, 222);
        }
    }

    private Theme() { }

    /**
     * 系统栏安全区适配。
     *
     * targetSdk 35 在 Android 15+ 上强制 edge-to-edge：状态栏/导航栏透明，
     * 内容默认从屏幕顶端开始绘制，若不处理就会与状态栏图标重叠。
     * 这里把根布局按 WindowInsets 加上顶部/底部 padding：
     *   - 根布局的背景仍铺满整屏（含状态栏区域），视觉上是一整块暖光墨底；
     *   - 所有子 View（内容、迷你播放器、底部 Tab）被推入安全区；
     *   - 传统非 edge-to-edge 版本上，decor 已消费系统栏 inset，
     *     传到这里的值为 0，不会重复偏移，因此同一套代码兼容 26~35。
     */
    @SuppressWarnings("deprecation")
    public static void applySafeArea(final View root) {
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = Math.max(0, insets.getSystemWindowInsetTop());
            int bottom = Math.max(0, insets.getSystemWindowInsetBottom());
            if (v.getPaddingTop() != top || v.getPaddingBottom() != bottom) {
                v.setPadding(v.getPaddingLeft(), top, v.getPaddingRight(), bottom);
            }
            return insets;
        });
        root.requestApplyInsets();
    }

    public static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    /** 给任意颜色替换 alpha 通道（用于随主题变化的强调色描边）。 */
    public static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    /** 按当前主题设置状态栏/导航栏颜色与图标明暗。 */
    @SuppressWarnings("deprecation")
    public static void applySystemBars(android.app.Activity activity) {
        android.view.Window w = activity.getWindow();
        w.setStatusBarColor(BG);
        w.setNavigationBarColor(BG);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            android.view.View decor = w.getDecorView();
            int flags = decor.getSystemUiVisibility();
            if (night) {
                flags &= ~android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            } else {
                flags |= android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                if (night) {
                    flags &= ~android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                } else {
                    flags |= android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                }
            }
            decor.setSystemUiVisibility(flags);
        }
    }

    public static Typeface serif() {
        return Typeface.create(Typeface.SERIF, Typeface.NORMAL);
    }

    public static GradientDrawable rect(Context context, int fill, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(context, radiusDp));
        return d;
    }

    public static GradientDrawable stroke(Context context, int fill, float radiusDp, int strokeColor, float strokeDp) {
        GradientDrawable d = rect(context, fill, radiusDp);
        d.setStroke(Math.max(1, dp(context, strokeDp)), strokeColor);
        return d;
    }

    /** 玻璃卡片：极淡填充 + 1px 描边。 */
    public static GradientDrawable glass(Context context, float radiusDp) {
        return stroke(context, CARD, radiusDp, STROKE, 0.5f);
    }

    public static GradientDrawable vertical(Context context, int[] colors, float radiusDp) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors);
        d.setCornerRadius(dp(context, radiusDp));
        return d;
    }

    /** 琥珀渐变（主按钮 / 进度条 / 排名高光）。 */
    public static GradientDrawable amber(Context context, float radiusDp) {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{GOLD, ACCENT, ACCENT_DEEP});
        d.setCornerRadius(dp(context, radiusDp));
        return d;
    }

    /** 无封面时的程序化渐变占位，对应设计稿里的 6 组专辑色。 */
    public static GradientDrawable cover(Context context, int index, float radiusDp) {
        int[][] sets = {
                {Color.rgb(35, 54, 69), Color.rgb(99, 135, 155), Color.rgb(210, 172, 121)},
                {Color.rgb(240, 201, 135), Color.rgb(154, 82, 48), Color.rgb(58, 33, 26)},
                {Color.rgb(30, 43, 42), Color.rgb(62, 107, 96), Color.rgb(199, 181, 138)},
                {Color.rgb(59, 42, 71), Color.rgb(139, 106, 158), Color.rgb(224, 185, 160)},
                {Color.rgb(74, 44, 34), Color.rgb(169, 103, 63), Color.rgb(230, 199, 155)},
                {Color.rgb(27, 42, 58), Color.rgb(76, 111, 134), Color.rgb(185, 199, 201)},
        };
        int[] c = sets[Math.abs(index) % sets.length];
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR, c);
        d.setCornerRadius(dp(context, radiusDp));
        return d;
    }

    /** 1px 分隔线。 */
    public static Drawable divider(Context context) {
        return new ColorLine(LINE2);
    }

    /** 圆角裁切（封面用方形圆角，不再是圆形）。 */
    public static void round(View view, float radiusDp) {
        final float r = dp(view.getContext(), radiusDp);
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r);
            }
        });
        view.setClipToOutline(true);
    }

    /** 让 ImageView 以圆形裁切。 */
    public static void circle(View view) {
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline outline) {
                outline.setOval(0, 0, v.getWidth(), v.getHeight());
            }
        });
        view.setClipToOutline(true);
    }

    public static void elevate(View view, float radiusDp, float elevationDp) {
        final float r = dp(view.getContext(), radiusDp);
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r);
            }
        });
        view.setElevation(dp(view.getContext(), elevationDp));
    }

    /** 首页 / 歌单背景：墨底 + 顶部极淡暖光晕。 */
    public static Drawable ambient(Context context) {
        return new Ambient();
    }

    /** 播放页唱片区暖光晕。 */
    public static Drawable glow(Context context) {
        return new Glow();
    }

    /** 自定义进度条：3px 轨道 + 琥珀渐变已播放 + 11px 圆点。 */
    public static void styleSeekBar(SeekBar bar) {
        Context c = bar.getContext();
        bar.setProgressDrawable(new SeekTrack(c));
        GradientDrawable thumb = new GradientDrawable();
        thumb.setShape(GradientDrawable.OVAL);
        thumb.setColor(GOLD);
        int size = dp(c, 11);
        thumb.setSize(size, size);
        bar.setThumb(thumb);
        bar.setThumbOffset(dp(c, 5));
        bar.setPadding(0, 0, 0, 0);
    }

    /**
     * 设计稿里的胶囊 Toast：玻璃底 + 琥珀勾 + 短文案。
     * 用页面内浮层实现（系统自定义 Toast 视图在 targetSdk 30+ 已不可靠），2 秒淡出。
     */
    public static void toast(final ViewGroup root, String message) {
        final Context context = root.getContext();
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setBackground(stroke(context, CARD_ALT, 99, STROKE, 0.5f));
        box.setPadding(dp(context, 16), dp(context, 10), dp(context, 16), dp(context, 10));
        GlyphView check = new GlyphView(context);
        check.setMode(GlyphView.CHECK);
        check.setGlyphColor(ACCENT);
        box.addView(check, new LinearLayout.LayoutParams(dp(context, 15), dp(context, 15)));
        TextView label = new TextView(context);
        label.setText(message);
        label.setTextSize(12);
        label.setTextColor(TEXT);
        label.setPadding(dp(context, 9), 0, 0, 0);
        box.addView(label);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        params.bottomMargin = dp(context, 92);
        box.setLayoutParams(params);
        box.setAlpha(0f);
        root.addView(box);
        box.animate().alpha(1f).setDuration(200).withEndAction(() ->
                box.animate().alpha(0f).setStartDelay(1600).setDuration(240)
                        .withEndAction(() -> root.removeView(box)).start()
        ).start();
    }

    /** 进度条 Drawable，level 由 SeekBar 自动设置为 0..10000。 */
    public static final class SeekTrack extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float barHeight;

        public SeekTrack(Context c) {
            barHeight = dp(c, 3);
        }

        @Override public void draw(Canvas canvas) {
            Rect b = getBounds();
            float mid = b.centerY();
            float top = mid - barHeight / 2f, bottom = mid + barHeight / 2f;
            float radius = barHeight / 2f;
            paint.setShader(null);
            paint.setColor(withAlpha(SUB, night ? 46 : 60));  // 轨道底色随主题
            canvas.drawRoundRect(new RectF(b.left, top, b.right, bottom), radius, radius, paint);
            float ratio = getLevel() / 10000f;
            if (ratio > 0f) {
                float right = b.left + (b.right - b.left) * ratio;
                float rx = Math.min(radius, (right - b.left) / 2f);
                paint.setShader(new LinearGradient(b.left, 0, b.right, 0,
                        new int[]{GOLD, ACCENT, ACCENT_DEEP}, null, Shader.TileMode.CLAMP));
                canvas.drawRoundRect(new RectF(b.left, top, right, bottom), rx, rx, paint);
                paint.setShader(null);
            }
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private static final class ColorLine extends Drawable {
        private final Paint paint = new Paint();
        ColorLine(int color) { paint.setColor(color); }
        @Override public void draw(Canvas canvas) { canvas.drawRect(getBounds(), paint); }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        @Override public int getIntrinsicHeight() { return 1; }
    }

    /** 页面背景：顶部极淡暖光，向下迅速回到墨底。 */
    public static final class Ambient extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        @Override public void draw(Canvas canvas) {
            Rect b = getBounds();
            float w = b.width(), h = b.height();
            if (w <= 0 || h <= 0) return;
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new RadialGradient(w * 0.5f, -h * 0.08f, Math.max(w, h) * 0.72f,
                    new int[]{AMBIENT_GLOW, BG_TOP, BG},
                    new float[]{0f, 0.34f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRect(b, paint);
            paint.setShader(null);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.OPAQUE; }
    }

    /** 唱片背后的柔光，制造「暖光从黑胶后透出」的层次。 */
    public static final class Glow extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        @Override public void draw(Canvas canvas) {
            Rect b = getBounds();
            float cx = b.centerX(), cy = b.centerY();
            float r = Math.max(b.width(), b.height()) * 0.62f;
            if (r <= 0) return;
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new RadialGradient(cx, cy, r,
                    new int[]{Color.argb(46, 227, 179, 106), Color.argb(14, 227, 179, 106),
                            Color.TRANSPARENT},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRect(b, paint);
            paint.setShader(null);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
