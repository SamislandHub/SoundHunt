package com.soundhunt.mobile;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 沉浸式播放流（抖音式启动页）：进来即自动播放推荐热歌，上下滑动切歌。
 * 底部两个 Tab：首页（沉浸流）/ 收藏。点顶部搜索条进入搜索页。
 * 与 MainActivity（搜索）、PlayerActivity 共享同一个 MusicService 播放状态。
 */
public class ImmersiveActivity extends Activity {
    private static final int TAB_HOME = 0;
    private static final int TAB_FAV = 1;
    private static final int TAB_H = 64;

    private MusicService service;
    private boolean bound;
    private boolean feedRequested;
    private boolean loadingMore;
    // 进入搜索页前快照推荐流队列与位置，返回时恢复，避免续播搜索的曲目
    private List<Track> feedQueueBackup;
    private int feedIndexBackup = 0;
    private String lastTrackId = "";
    private int pendingDir;
    private int currentTab = TAB_HOME;

    private FrameLayout root;
    // 首页（沉浸流）
    private LinearLayout homePage;
    private LinearLayout stage;
    private VinylDiscView vinyl;
    private FrameLayout centerPlayBox;   // 唱片中心播放/暂停图标（不随唱片旋转）
    private GlyphView centerPlayIcon;
    private TextView title;
    private TextView artist;
    private TextView hintText;
    private TextView statusText;
    private GlyphView heart;
    // 收藏页
    private LinearLayout favPage;
    private ListView favList;
    private TextView favCount;
    private TextView sortLabel;
    private boolean sortByTitle;
    private boolean shuffleMode = true;
    private GlyphView shuffleIcon;
    private TextView shuffleText;
    private FavAdapter favAdapter;
    // 收藏页悬浮迷你播放条
    private FrameLayout miniBar;
    private FrameLayout miniProgressTrack;
    private View miniProgressFill;
    private TextView miniTitle;
    private TextView miniArtist;
    private DiscThumbView miniCover;
    private ControlButton miniPlay;
    private String lastMiniId = "";
    // 底部 Tab
    private FrameLayout tabBar;
    private LinearLayout homeTab;
    private LinearLayout favTab;
    private GlyphView homeIcon;
    private GlyphView favIcon;
    private TextView homeLabel;
    private TextView favLabel;
    private View tabIndicator;

    private FavoriteStore favorites;
    private SearchRepository repository;
    private final ExecutorService feedExecutor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean themeNightAtCreate;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((MusicService.LocalBinder) binder).getService();
            bound = true;
            service.addListener(listener);
            // 任何页面切到首页：只要当前处于首页 tab 且队列不是推荐流，
            // 就恢复首页推荐播放（搜索/收藏/播放页回来都一样）。
            // 停留在收藏页时保留收藏播放，不打断用户当前选择。
            if (currentTab == TAB_HOME
                    && service.getQueueSource() != MusicService.QUEUE_FEED) {
                restoreFeed();
            } else {
                ensureFeed();
            }
            updateTrack();
            updatePlayback();
            if (currentTab == TAB_FAV) favAdapter.notifyDataSetChanged();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            bound = false;
            service = null;
        }
    };

    private final MusicService.Listener listener = new MusicService.Listener() {
        @Override public void onPlaybackChanged() {
            updateTrack();
            updatePlayback();
            if (favAdapter != null) favAdapter.notifyDataSetChanged();
        }
        @Override public void onError(String message) {
            Toast.makeText(ImmersiveActivity.this, message, Toast.LENGTH_SHORT).show();
        }
    };

    /** 频谱 pump：把真实音频低频能量喂给黑胶，驱动转速随节奏变化。 */
    private final Runnable spectrumPump = new Runnable() {
        @Override public void run() {
            if (vinyl != null) {
                if (bound && service != null && service.isPlaying()) {
                    vinyl.setBands(service.getBass(), service.getMid(), service.getHi());
                } else {
                    vinyl.setBands(0f, 0f, 0f);
                }
            }
            handler.postDelayed(this, 40);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        Theme.init(this);
        themeNightAtCreate = Theme.night;
        super.onCreate(savedInstanceState);
        Theme.applySystemBars(this);
        favorites = new FavoriteStore(this);
        repository = new SearchRepository();
        repository.refreshProfile(favorites.all());
        buildUi();
        switchTab(TAB_HOME);
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackground(Theme.ambient(this));

        // 页面容器：首页沉浸流 / 收藏，二者叠放切换
        FrameLayout pages = new FrameLayout(this);
        root.addView(pages, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        buildHomePage();
        pages.addView(homePage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        buildFavPage();
        pages.addView(favPage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        buildTabs();
        buildMiniPlayer();
        setContentView(root);
        Theme.applySafeArea(root);
    }

    // ---------------- 首页：沉浸流 ----------------

    private void buildHomePage() {
        homePage = new LinearLayout(this);
        homePage.setOrientation(LinearLayout.VERTICAL);

        // 顶部搜索条：点击进入搜索/收藏首页并自动聚焦输入框
        FrameLayout searchBox = new FrameLayout(this);
        searchBox.setPadding(dp(18), 0, dp(18), 0);
        homePage.addView(searchBox, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));

        LinearLayout searchBar = new LinearLayout(this);
        searchBar.setOrientation(LinearLayout.HORIZONTAL);
        searchBar.setGravity(Gravity.CENTER_VERTICAL);
        searchBar.setPadding(dp(16), 0, dp(14), 0);
        searchBar.setBackground(Theme.glass(this, 99));
        searchBar.setOnClickListener(v -> openSearch());
        searchBox.addView(searchBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44), Gravity.CENTER_VERTICAL));

        GlyphView searchIcon = glyph(GlyphView.SEARCH, Theme.SUB, dp(19));
        searchBar.addView(searchIcon, lp(dp(19), dp(19)));
        TextView searchHint = text("搜索歌曲、歌手", 13f, Theme.DIM);
        searchHint.setSingleLine(true);
        searchHint.setGravity(Gravity.CENTER_VERTICAL);
        searchHint.setPadding(dp(11), 0, 0, 0);
        searchBar.addView(searchHint, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        TextView actionText = text("搜索", 13f, Theme.SUB);
        searchBar.addView(actionText, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 手势层：竖直滑动切歌，横向/点击不拦截，保证进度条与按钮可用
        SwipeLayer swipe = new SwipeLayer(this);
        swipe.setListener(this::onSwipe);
        homePage.addView(swipe, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        stage = new LinearLayout(this);
        stage.setOrientation(LinearLayout.VERTICAL);
        // 底部留出 Tab 高度，避免歌名/状态文字被 Tab 遮挡
        stage.setPadding(0, 0, 0, dp(TAB_H));
        swipe.addView(stage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 舞台：黑胶随节奏旋转。唱片边长约屏宽 72%，四周留白更舒服
        FrameLayout vinylArea = new FrameLayout(this);
        stage.addView(vinylArea, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(dm);
        int discSide = Math.round(Math.min(dm.widthPixels, dm.heightPixels) * 0.72f);
        vinyl = new VinylDiscView(this);
        vinylArea.addView(vinyl, new FrameLayout.LayoutParams(
                discSide, discSide, Gravity.CENTER));

        // 唱片中心播放图标：独立 View 叠在唱片上，不随唱片旋转。
        // 只在暂停时显示播放三角，播放时隐藏，保持唱片画面纯净。
        centerPlayBox = new FrameLayout(this);
        centerPlayBox.setBackground(Theme.stroke(this, Theme.withAlpha(Theme.CARD_ALT, 210),
                99, Theme.withAlpha(Theme.GOLD, 120), 1f));
        centerPlayIcon = glyph(GlyphView.PLAY, Theme.TEXT, dp(26));
        centerPlayBox.addView(centerPlayIcon, new FrameLayout.LayoutParams(
                dp(26), dp(26), Gravity.CENTER));
        // 点击图标恢复播放；竖直滑动仍由外层 SwipeLayer 拦截切歌
        centerPlayBox.setClickable(true);
        centerPlayBox.setOnClickListener(v -> { if (bound) service.toggle(); });
        int boxSide = Math.round(discSide * 0.19f);
        centerPlayBox.setAlpha(0.92f);
        centerPlayBox.setVisibility(View.GONE);
        vinylArea.addView(centerPlayBox, new FrameLayout.LayoutParams(
                boxSide, boxSide, Gravity.CENTER));

        // 轻点播放/暂停 + 上下滑切歌 提示（短暂显示）
        hintText = text("轻点播放 · 上下滑动切歌", 10.5f, Theme.DIM);
        hintText.setGravity(Gravity.CENTER);
        hintText.setLetterSpacing(0.08f);
        stage.addView(hintText, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // 歌名 + 歌手 + 收藏（收紧上间距）
        LinearLayout meta = new LinearLayout(this);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        meta.setPadding(dp(34), dp(10), dp(34), 0);
        stage.addView(meta, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout metaText = new LinearLayout(this);
        metaText.setOrientation(LinearLayout.VERTICAL);
        meta.addView(metaText, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        title = text("正在连接音源…", 18f, Theme.TEXT);
        title.setTypeface(Theme.serif());
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        metaText.addView(title);
        artist = text("为你推荐加载中", 11f, Theme.SUB);
        artist.setSingleLine(true);
        artist.setEllipsize(android.text.TextUtils.TruncateAt.END);
        artist.setPadding(0, dp(5), 0, 0);
        metaText.addView(artist);
        heart = glyph(GlyphView.HEART, Theme.DIM, dp(23));
        heart.setOnClickListener(v -> toggleFavorite());
        meta.addView(heart, lp(dp(30), dp(30)));

        // 底部状态提示（精简，不占被 Tab 遮挡的区域）
        statusText = text("", 10f, Theme.DIM);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(0, dp(14), 0, dp(10));
        stage.addView(statusText, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // 轻点舞台任意处播放/暂停；心形有独立点击，不会冒泡到这里
        stage.setClickable(true);
        stage.setOnClickListener(v -> { if (bound) service.toggle(); });
    }

    // ---------------- 收藏页 ----------------

    private void buildFavPage() {
        favPage = new LinearLayout(this);
        favPage.setOrientation(LinearLayout.VERTICAL);
        favPage.setBackground(Theme.ambient(this));

        // 顶部标题行：标题 + 数量 + 设置按钮
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(22), dp(8), dp(22), 0);
        favPage.addView(head, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));
        TextView headTitle = text("我的收藏", 24, Theme.TEXT);
        headTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        head.addView(headTitle, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        favCount = text("", 11, Theme.DIM);
        favCount.setGravity(Gravity.END);
        head.addView(favCount, lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout settingsBtn = new FrameLayout(this);
        settingsBtn.setBackground(Theme.stroke(this, Theme.CARD, 99, Theme.STROKE, 0.5f));
        settingsBtn.addView(glyph(GlyphView.SETTINGS, Theme.SUB, dp(17)),
                new FrameLayout.LayoutParams(dp(17), dp(17), Gravity.CENTER));
        settingsBtn.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        LinearLayout.LayoutParams setParams = lp(dp(38), dp(38));
        setParams.setMargins(dp(10), 0, 0, 0);
        head.addView(settingsBtn, setParams);

        // 工具行：播放模式（随机/顺序切换） / 排序
        LinearLayout tools = new LinearLayout(this);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        tools.setPadding(dp(22), 0, dp(22), 0);
        LinearLayout.LayoutParams toolsParams = lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(34));
        toolsParams.setMargins(0, dp(20), 0, dp(2));
        favPage.addView(tools, toolsParams);

        LinearLayout shufflePill = pill(true);
        shuffleIcon = glyph(GlyphView.SHUFFLE, Theme.ACCENT, dp(14));
        shufflePill.addView(shuffleIcon, lp(dp(14), dp(14)));
        shuffleText = text("随机播放", 11.5f, Theme.ACCENT);
        shuffleText.setPadding(dp(7), 0, 0, 0);
        shufflePill.addView(shuffleText);
        shufflePill.setOnClickListener(v -> {
            shuffleMode = !shuffleMode;
            updateShufflePill();
            List<Track> all = new ArrayList<>(favorites.all());
            if (sortByTitle) all.sort((a, b) -> a.title.compareToIgnoreCase(b.title));
            if (shuffleMode) java.util.Collections.shuffle(all);
            if (bound && !all.isEmpty()) {
                pendingDir = 0;
                service.playQueue(all, 0, MusicService.QUEUE_FAV);
            }
            Theme.toast(root, shuffleMode ? "随机播放" : "顺序播放");
        });
        tools.addView(shufflePill, lp(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)));

        LinearLayout sortPill = pill(false);
        LinearLayout.LayoutParams sortPillParams = lp(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
        sortPillParams.setMargins(dp(10), 0, 0, 0);
        sortPill.addView(glyph(GlyphView.SLIDERS, Theme.SUB, dp(14)), lp(dp(14), dp(14)));
        TextView sortText = text("排序", 11.5f, Theme.SUB);
        sortText.setPadding(dp(7), 0, 0, 0);
        sortPill.addView(sortText);
        sortPill.setOnClickListener(v -> {
            sortByTitle = !sortByTitle;
            favAdapter.notifyDataSetChanged();
            Theme.toast(root, sortByTitle ? "按歌名排序" : "按添加时间排序");
        });
        tools.addView(sortPill, sortPillParams);

        sortLabel = text("按添加时间", 11, Theme.DIM);
        sortLabel.setGravity(Gravity.END);
        tools.addView(sortLabel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        favAdapter = new FavAdapter();
        favList = new ListView(this);
        favList.setAdapter(favAdapter);
        favList.setDivider(null);
        favList.setDividerHeight(0);
        favList.setSelector(new android.graphics.drawable.ColorDrawable(0));
        favList.setVerticalScrollBarEnabled(false);
        // 底部留出 Tab + 迷你条高度，最后几首不被悬浮条遮住
        favList.setPadding(0, dp(6), 0, dp(TAB_H + 86));
        favList.setOnItemClickListener((parent, view, position, id) -> {
            if (!bound || position >= favAdapter.getCount()) return;
            pendingDir = 0;
            service.playQueue(new ArrayList<>(favAdapter.visibleData()), position, MusicService.QUEUE_FAV);
        });
        favPage.addView(favList, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
    }

    private class FavAdapter extends BaseAdapter {
        private List<Track> data = new ArrayList<>();
        @Override public void notifyDataSetChanged() {
            data = new ArrayList<>(favorites.all());
            if (sortByTitle) data.sort((a, b) -> a.title.compareToIgnoreCase(b.title));
            favCount.setText(data.isEmpty() ? "" : data.size() + " 首");
            if (sortLabel != null) sortLabel.setText(sortByTitle ? "按歌名" : "按添加时间");
            super.notifyDataSetChanged();
        }
        List<Track> visibleData() { return data; }
        @Override public int getCount() { return data.size(); }
        @Override public Object getItem(int i) { return data.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public View getView(int i, View convertView, ViewGroup parent) {
            LinearLayout row = new LinearLayout(ImmersiveActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(22), dp(9), dp(22), dp(9));
            Track track = data.get(i);
            boolean playing = bound && service.getCurrent() != null
                    && service.getCurrent().stableId().equals(track.stableId());
            DiscThumbView thumb = new DiscThumbView(ImmersiveActivity.this);
            boolean noCover = track.cover == null || track.cover.isEmpty();
            thumb.setTrack(track.cover, noCover, i);
            row.addView(thumb, lp(dp(50), dp(50)));
            LinearLayout info = new LinearLayout(ImmersiveActivity.this);
            info.setOrientation(LinearLayout.VERTICAL);
            info.setGravity(Gravity.CENTER_VERTICAL);
            info.setPadding(dp(13), 0, dp(8), 0);
            row.addView(info, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            TextView name = text(track.title, 13.5f, playing ? Theme.ACCENT : Theme.TEXT);
            name.setTypeface(Typeface.DEFAULT, playing ? Typeface.BOLD : Typeface.NORMAL);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            info.addView(name);
            TextView artistView = text(track.artist, 11f, Theme.DIM);
            artistView.setSingleLine(true);
            artistView.setEllipsize(android.text.TextUtils.TruncateAt.END);
            artistView.setPadding(0, dp(5), 0, 0);
            info.addView(artistView);
            GlyphView heartIcon = glyph(GlyphView.HEART_FILLED, Theme.ACCENT, dp(18));
            heartIcon.setOnClickListener(v -> {
                favorites.toggle(track);
                Theme.toast(root, "已移出收藏");
                favAdapter.notifyDataSetChanged();
            });
            row.addView(heartIcon, lp(dp(30), dp(30)));
            return row;
        }
    }

    // ---------------- 底部 Tab ----------------

    private void buildTabs() {
        tabBar = new FrameLayout(this);
        tabBar.setBackground(Theme.rect(this, Theme.BG, 0));
        FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(TAB_H), Gravity.BOTTOM);
        root.addView(tabBar, barParams);

        View topLine = new View(this);
        topLine.setBackground(Theme.divider(this));
        tabBar.addView(topLine, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1));

        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, dp(8), 0, 0);
        tabBar.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        homeTab = tabItem("首页", GlyphView.HOME, true);
        favTab = tabItem("收藏", GlyphView.LIST, false);
        homeTab.setOnClickListener(v -> {
            switchTab(TAB_HOME);
            // 从收藏页等切回首页：队列非推荐流时恢复首页推荐播放
            if (bound && service.getQueueSource() != MusicService.QUEUE_FEED) {
                restoreFeed();
            }
        });
        favTab.setOnClickListener(v -> switchTab(TAB_FAV));
        row.addView(homeTab, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1));
        row.addView(favTab, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1));

        tabIndicator = new View(this);
        tabIndicator.setBackground(Theme.amber(this, 1));
        tabBar.addView(tabIndicator, new FrameLayout.LayoutParams(dp(22), dp(2), Gravity.TOP));
    }

    private LinearLayout tabItem(String label, int iconMode, boolean isHome) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        GlyphView icon = glyph(iconMode, Theme.DIM, dp(21));
        box.addView(icon, lp(dp(21), dp(21)));
        TextView labelView = text(label, 10.5f, Theme.DIM);
        labelView.setGravity(Gravity.CENTER);
        labelView.setPadding(0, dp(5), 0, 0);
        box.addView(labelView);
        // 直接保存图标与文字引用，避免使用带 key 的 setTag（要求应用资源 id）
        if (isHome) {
            homeIcon = icon;
            homeLabel = labelView;
        } else {
            favIcon = icon;
            favLabel = labelView;
        }
        return box;
    }

    private void switchTab(int tab) {
        currentTab = tab;
        homePage.setVisibility(tab == TAB_HOME ? View.VISIBLE : View.GONE);
        favPage.setVisibility(tab == TAB_FAV ? View.VISIBLE : View.GONE);
        boolean home = tab == TAB_HOME;
        homeIcon.setGlyphColor(home ? Theme.ACCENT : Theme.DIM);
        favIcon.setGlyphColor(home ? Theme.DIM : Theme.ACCENT);
        homeLabel.setTextColor(home ? Theme.ACCENT : Theme.DIM);
        favLabel.setTextColor(home ? Theme.DIM : Theme.ACCENT);
        tabBar.post(() -> {
            int width = tabBar.getWidth();
            if (width <= 0) return;
            float target = (home ? width * 0.25f : width * 0.75f) - dp(11);
            tabIndicator.animate().translationX(target).setDuration(240).start();
        });
        if (tab == TAB_FAV) favAdapter.notifyDataSetChanged();
        updateMiniPlayer();
    }

    // ---------------- 收藏页悬浮迷你播放条 ----------------

    private void buildMiniPlayer() {
        miniBar = new FrameLayout(this);
        miniBar.setBackground(Theme.stroke(this, Theme.CARD, 18, Theme.STROKE, 0.5f));
        Theme.elevate(miniBar, 18, 5);
        FrameLayout.LayoutParams miniParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(60), Gravity.BOTTOM);
        // 悬浮在底部 Tab 之上
        miniParams.setMargins(dp(14), 0, dp(14), dp(TAB_H + 10));
        root.addView(miniBar, miniParams);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(8), dp(12), dp(8));
        bar.setOnClickListener(v -> {
            if (bound && service.getCurrent() != null)
                startActivity(new Intent(this, PlayerActivity.class));
        });
        miniBar.addView(bar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        miniCover = new DiscThumbView(this);
        bar.addView(miniCover, lp(dp(40), dp(40)));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.CENTER_VERTICAL);
        info.setPadding(dp(11), 0, dp(6), 0);
        bar.addView(info, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1));
        miniTitle = text("", 12, Theme.TEXT);
        miniTitle.setSingleLine(true);
        miniTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(miniTitle);
        miniArtist = text("", 10, Theme.DIM);
        miniArtist.setSingleLine(true);
        miniArtist.setEllipsize(android.text.TextUtils.TruncateAt.END);
        miniArtist.setPadding(0, dp(4), 0, 0);
        info.addView(miniArtist);

        miniPlay = new ControlButton(this);
        miniPlay.setMode(ControlButton.PLAY);
        miniPlay.setOnClickListener(v -> { if (bound) service.toggle(); });
        bar.addView(miniPlay, lp(dp(24), dp(24)));
        GlyphView next = glyph(GlyphView.NEXT, Theme.TEXT, dp(18));
        next.setOnClickListener(v -> { if (bound) service.next(); });
        LinearLayout.LayoutParams nextParams = lp(dp(20), dp(20));
        nextParams.setMargins(dp(14), 0, 0, 0);
        bar.addView(next, nextParams);

        miniProgressTrack = new FrameLayout(this);
        miniProgressTrack.setBackground(Theme.rect(this, Theme.withAlpha(Theme.SUB, 46), 1));
        FrameLayout.LayoutParams trackParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(2), Gravity.BOTTOM);
        trackParams.setMargins(dp(18), 0, dp(18), 0);
        miniBar.addView(miniProgressTrack, trackParams);
        miniProgressFill = new View(this);
        miniProgressFill.setBackground(Theme.amber(this, 1));
        miniProgressTrack.addView(miniProgressFill, new FrameLayout.LayoutParams(0, dp(2)));
    }

    /** 迷你条只在收藏页显示，首页是沉浸播放页不需重复。 */
    private void updateMiniPlayer() {
        if (miniBar == null) return;
        Track current = bound ? service.getCurrent() : null;
        if (current == null || currentTab != TAB_FAV) {
            miniBar.setVisibility(View.GONE);
            lastMiniId = "";
            return;
        }
        miniBar.setVisibility(View.VISIBLE);
        miniTitle.setText(current.title);
        miniArtist.setText(current.artist);
        miniCover.setSpinning(bound && service.isPlaying());
        miniPlay.setMode(bound && service.isPlaying() ? ControlButton.PAUSE : ControlButton.PLAY);
        if (!current.stableId().equals(lastMiniId)) {
            lastMiniId = current.stableId();
            boolean noCover = current.cover == null || current.cover.isEmpty();
            miniCover.setTrack(current.cover, noCover, 3);
        }
        updateMiniProgress();
    }

    private void updateMiniProgress() {
        if (miniProgressTrack == null || miniProgressFill == null) return;
        int width = miniProgressTrack.getWidth();
        if (width <= 0) return;
        int duration = bound ? service.getDuration() : 0;
        int position = bound ? service.getPosition() : 0;
        float ratio = duration > 0 ? Math.min(1f, Math.max(0f, position / (float) duration)) : 0f;
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) miniProgressFill.getLayoutParams();
        params.width = (int) (width * ratio);
        miniProgressFill.setLayoutParams(params);
    }

    // ---------------- 播放数据流 ----------------

    /** 首次进入且队列为空时，拉推荐热歌并自动播放。 */
    private void ensureFeed() {
        if (feedRequested || !bound) return;
        feedRequested = true;
        if (service.getQueueSize() > 0 && service.getCurrent() != null) {
            updateTrack();
            return;
        }
        loadFeed(false);
    }

    /**
     * 拉取下一页推荐。append=false 首次替换队列并开播；append=true 续接到队尾。
     * 每次拉到的条目优先单曲，且由 repository 跨页去重。
     */
    private void loadFeed(boolean append) {
        if (!bound || loadingMore) return;
        loadingMore = true;
        if (!append) statusText.setText("正在连接 B站音源…");
        feedExecutor.execute(() -> {
            SearchRepository.Result result = repository.trending();
            main(() -> {
                loadingMore = false;
                if (!bound) return;
                List<Track> playable = new ArrayList<>();
                for (Track t : result.tracks) if (!t.isCollection()) playable.add(t);
                if (playable.isEmpty()) playable.addAll(result.tracks);
                if (playable.isEmpty()) {
                    if (!append) statusText.setText("推荐源暂时离线 · 点搜索找歌");
                    return;
                }
                if (append) {
                    service.appendQueue(playable);
                } else {
                    service.playQueue(playable, 0, MusicService.QUEUE_FEED);
                    statusText.setText("");
                    if (hintText != null) hintText.animate().alpha(0f).setStartDelay(2600)
                            .setDuration(600).start();
                }
            });
        });
    }

    /** 快滑到队尾时提前续拉，实现无限下拉。 */
    private void maybeLoadMore() {
        if (!bound || loadingMore) return;
        int size = service.getQueueSize();
        if (size == 0 || service.getCurrentIndex() < size - 3) return;
        loadFeed(true);
    }

    private void goNext() {
        if (!bound || service.getQueueSize() == 0) return;
        pendingDir = 1;
        service.next();
    }

    private void goPrev() {
        if (!bound || service.getQueueSize() == 0) return;
        pendingDir = -1;
        service.previous();
    }

    private void onSwipe(int dir) {
        if (dir > 0) goNext(); else goPrev();
    }

    private void updateTrack() {
        if (!bound) return;
        Track track = service.getCurrent();
        if (track == null) return;
        // 播放推荐流时持续维护快照，任何页面切回首页都能恢复到当前这首
        if (service.getQueueSource() == MusicService.QUEUE_FEED) {
            feedQueueBackup = service.getQueue();
            feedIndexBackup = service.getCurrentIndex();
        }
        boolean changed = !track.stableId().equals(lastTrackId);
        if (changed) {
            lastTrackId = track.stableId();
            boolean noCover = track.cover == null || track.cover.isEmpty();
            vinyl.setCoverUrl(noCover ? null : track.cover);
            title.setText(track.title);
            artist.setText(track.artist);
            updateFavorite();
            playSwapAnimation(pendingDir);
            pendingDir = 0;
        }
        maybeLoadMore();
    }

    private void playSwapAnimation(int dir) {
        if (stage == null) return;
        float h = stage.getHeight();
        if (h <= 0) h = dp(400);
        float fromY = dir == 0 ? 0f : dir * h * 0.10f;
        stage.setAlpha(0.4f);
        stage.setTranslationY(fromY);
        stage.setScaleX(dir == 0 ? 0.98f : 1f);
        stage.setScaleY(dir == 0 ? 0.98f : 1f);
        stage.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setDuration(220).setInterpolator(new DecelerateInterpolator()).start();
    }

    private void updatePlayback() {
        if (!bound) return;
        boolean playing = service.isPlaying();
        vinyl.setPlaying(playing);
        // 播放时唱片保持纯净不显示图标；暂停时才在中心显示播放图标提示可继续
        if (centerPlayBox != null) {
            centerPlayBox.setVisibility(playing ? View.GONE : View.VISIBLE);
        }
        updateMiniPlayer();
    }

    private void toggleFavorite() {
        if (!bound || service.getCurrent() == null) return;
        boolean added = favorites.toggle(service.getCurrent());
        updateFavorite();
        repository.refreshProfile(favorites.all());
        Theme.toast(root, added ? "已加入我的收藏" : "已移出收藏");
    }

    private void updateFavorite() {
        if (!bound || service.getCurrent() == null) return;
        boolean saved = favorites.contains(service.getCurrent());
        heart.setMode(saved ? GlyphView.HEART_FILLED : GlyphView.HEART);
        heart.setGlyphColor(saved ? Theme.ACCENT : Theme.DIM);
    }

    private void openSearch() {
        // 只备份推荐流队列；若当前播的是收藏队列，返回后应保留收藏播放，不覆盖
        if (bound && service.getQueueSource() == MusicService.QUEUE_FEED) {
            feedQueueBackup = service.getQueue();
            feedIndexBackup = service.getCurrentIndex();
        } else {
            feedQueueBackup = null;
        }
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra("focusSearch", true);
        startActivity(intent);
    }

    /** 从搜索页返回：恢复进入搜索前的推荐流队列和播放位置。 */
    private void restoreFeed() {
        if (feedQueueBackup != null && !feedQueueBackup.isEmpty()) {
            int idx = feedIndexBackup;
            if (idx < 0 || idx >= feedQueueBackup.size()) idx = 0;
            service.playQueue(feedQueueBackup, idx, MusicService.QUEUE_FEED);
            feedQueueBackup = null;
        } else {
            // 没有快照（如首次进首页就直接搜索）：强制重新拉一页推荐覆盖搜索队列。
            // 不能走 ensureFeed()——它会因搜索队列非空而跳过加载。
            feedRequested = true;
            loadFeed(false);
        }
        // 回到首页 tab，让恢复的推荐流可见
        switchTab(TAB_HOME);
    }

    // ---------------- 生命周期 ----------------

    @Override protected void onResume() {
        super.onResume();
        if (Theme.night != themeNightAtCreate) recreate();
        if (favAdapter != null) favAdapter.notifyDataSetChanged();
    }

    /** 迷你条进度刷新（每秒）。 */
    private final Runnable miniTicker = new Runnable() {
        @Override public void run() {
            if (currentTab == TAB_FAV) updateMiniProgress();
            handler.postDelayed(this, 1000);
        }
    };

    @Override protected void onStart() {
        super.onStart();
        Intent intent = new Intent(this, MusicService.class);
        startService(intent);
        bindService(intent, connection, Context.BIND_AUTO_CREATE);
        handler.post(spectrumPump);
        handler.post(miniTicker);
    }

    @Override protected void onStop() {
        handler.removeCallbacks(spectrumPump);
        handler.removeCallbacks(miniTicker);
        if (bound) {
            service.removeListener(listener);
            unbindService(connection);
            bound = false;
            service = null;
        }
        super.onStop();
    }

    @Override protected void onDestroy() {
        feedExecutor.shutdownNow();
        super.onDestroy();
    }

    /** 切换随机/顺序播放时更新按钮图标与文案。 */
    private void updateShufflePill() {
        if (shuffleIcon == null || shuffleText == null) return;
        shuffleIcon.setMode(shuffleMode ? GlyphView.SHUFFLE : GlyphView.REPEAT);
        shuffleText.setText(shuffleMode ? "随机播放" : "顺序播放");
    }

    // ---------------- 视图工具 ----------------

    private void main(Runnable r) { handler.post(r); }

    private LinearLayout pill(boolean primary) {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.HORIZONTAL);
        p.setGravity(Gravity.CENTER_VERTICAL);
        p.setPadding(dp(15), 0, dp(15), 0);
        p.setBackground(primary
                ? Theme.stroke(this, Theme.SOFT, 99, Theme.withAlpha(Theme.ACCENT, 70), 0.5f)
                : Theme.glass(this, 99));
        return p;
    }

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

    private LinearLayout.LayoutParams lp(int width, int height) {
        return new LinearLayout.LayoutParams(width, height);
    }

    private int dp(int value) { return Theme.dp(this, value); }

    /** 竖直滑动拦截层：只在明显竖滑时拦截，避免影响点击。 */
    private static class SwipeLayer extends FrameLayout {
        interface SwipeListener { void onSwipe(int dir); }
        private SwipeListener listener;
        private final int slop;
        private final int threshold;
        private float downX, downY;
        private boolean intercepting;

        SwipeLayer(Activity activity) {
            super(activity);
            slop = ViewConfiguration.get(activity).getScaledTouchSlop();
            threshold = Theme.dp(activity, 84);
        }
        void setListener(SwipeListener l) { listener = l; }

        @Override public boolean onInterceptTouchEvent(MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = ev.getX(); downY = ev.getY();
                    intercepting = false;
                    break;
                case MotionEvent.ACTION_MOVE: {
                    float dy = ev.getY() - downY;
                    float dx = ev.getX() - downX;
                    if (Math.abs(dy) > slop && Math.abs(dy) > Math.abs(dx)) intercepting = true;
                    break;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    intercepting = false;
                    break;
            }
            return intercepting;
        }

        @Override public boolean onTouchEvent(MotionEvent ev) {
            if (ev.getActionMasked() == MotionEvent.ACTION_UP) {
                float dy = ev.getY() - downY;
                if (dy < -threshold && listener != null) listener.onSwipe(1);
                else if (dy > threshold && listener != null) listener.onSwipe(-1);
                intercepting = false;
                return true;
            }
            return true;
        }
    }
}
