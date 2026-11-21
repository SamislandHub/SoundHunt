package com.soundhunt.mobile;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 首页 / 歌单（我的收藏）—— v2 视觉：深色墨底 + 单一琥珀暖光。
 * 结构：顶部随 Tab 切换的 header、就地显示的列表、悬浮迷你播放器、底部两个 Tab。
 * 搜索结果不跳页；收藏、排序、随机播放、换源等逻辑保持原样。
 */
public class MainActivity extends Activity {
    private static final int MODE_HOME = 0;
    private static final int MODE_PLAYLIST = 1;
    private static final String[] HOT = {"抖音热歌", "稻香", "周杰伦", "治愈纯音", "Lo-fi",
            "古风", "粤语经典", "Taylor Swift", "华语流行"};
    private static final int TAB_H = 76;

    private final List<Track> tracks = new ArrayList<>();
    private final List<Track> searchResults = new ArrayList<>();
    private final List<Track> trendingTracks = new ArrayList<>();
    private final ExecutorService searchExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService trendingExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService expandExecutor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private SearchRepository repository;
    private FavoriteStore favorites;
    private TrackAdapter adapter;

    private FrameLayout root;
    private LinearLayout homeHeader;
    private LinearLayout playlistHeader;
    private EditText searchInput;
    private TextView sectionTitle;
    private TextView refreshLabel;
    private TextView statusText;
    private TextView playlistSubtitle;
    private TextView collageRecent;
    private TextView sortLabel;
    private ListView list;
    private FrameLayout listHost;
    private SkeletonView skeleton;
    private boolean skeletonVisible;

    // 两个 Tab 共用一个 ListView，各自记住自己的滚动位置，切换时不互相干扰
    private int homePos, homeTop;
    private int searchPos, searchTop;
    private int playlistPos, playlistTop;

    private LinearLayout homeTab;
    private LinearLayout playlistTab;
    private GlyphView homeIcon;
    private GlyphView playlistIcon;
    private FrameLayout tabTrack;
    private View tabIndicator;

    private FrameLayout miniBar;
    private FrameLayout miniProgressTrack;
    private View miniProgressFill;
    private TextView miniTitle;
    private TextView miniArtist;
    private DiscThumbView miniCover;
    private ControlButton miniPlay;
    private String lastMiniId = "";

    private MusicService service;
    private boolean bound;
    private int mode = MODE_HOME;
    private boolean themeNightAtCreate;
    private boolean showingSearch;
    private boolean trendingLoaded;
    private boolean sortByTitle;
    private int searchSerial;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((MusicService.LocalBinder) binder).getService();
            bound = true;
            service.addListener(playbackListener);
            updateMiniPlayer();
            adapter.notifyDataSetChanged();
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            bound = false;
            service = null;
        }
    };

    private final MusicService.Listener playbackListener = new MusicService.Listener() {
        @Override public void onPlaybackChanged() {
            updateMiniPlayer();
            if (adapter != null) adapter.notifyDataSetChanged();
        }

        @Override public void onError(String message) {
            Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
        }
    };

    private final Runnable miniTicker = new Runnable() {
        @Override public void run() {
            updateMiniProgress();
            main.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        Theme.init(this);
        themeNightAtCreate = Theme.night;
        super.onCreate(savedInstanceState);
        Theme.applySystemBars(this);
        repository = new SearchRepository();
        favorites = new FavoriteStore(this);
        adapter = new TrackAdapter();
        buildUi();
        // 从沉浸式播放流的搜索条进入：自动聚焦搜索框并唤起键盘
        if (getIntent().getBooleanExtra("focusSearch", false)) {
            searchInput.post(() -> {
                searchInput.requestFocus();
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT);
            });
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 10);
        }
        loadTrending();
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackground(Theme.ambient(this));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(10), dp(22), dp(16));
        root.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        homeHeader = new LinearLayout(this);
        homeHeader.setOrientation(LinearLayout.VERTICAL);
        content.addView(homeHeader, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        buildHomeHeader();

        playlistHeader = new LinearLayout(this);
        playlistHeader.setOrientation(LinearLayout.VERTICAL);
        playlistHeader.setVisibility(View.GONE);
        content.addView(playlistHeader, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        buildPlaylistHeader();

        list = new ListView(this);
        list.setAdapter(adapter);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setSelector(new ColorDrawable(0));
        list.setVerticalScrollBarEnabled(false);
        list.setClipToPadding(false);
        list.setPadding(0, dp(10), 0, dp(86));
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (tracks.isEmpty()) {
                if (mode != MODE_PLAYLIST && position < HOT.length) {
                    searchInput.setText(HOT[position]);
                    performSearch();
                }
                return;
            }
            if (bound) expandAndPlay(tracks, position);
        });
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            if (tracks.isEmpty() || position >= tracks.size()) return true;
            Track track = tracks.get(position);
            if (track.sourceUrl != null && !track.sourceUrl.isEmpty()) {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(track.sourceUrl)));
            }
            return true;
        });
        listHost = new FrameLayout(this);
        listHost.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 骨架层压在列表上，只在请求返回前出现；结构对齐 trackRow，切换时不跳高度
        skeleton = new SkeletonView(this);
        skeleton.setVisibility(View.GONE);
        listHost.addView(skeleton, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        content.addView(listHost, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        buildMiniPlayer();
        // 搜索页不再展示底部 Tab；Tab 移到沉浸式首页，抖音式页面内切换
        setContentView(root);
        Theme.applySafeArea(root);
        refreshList();
    }

    // ---------------- 首页 header ----------------

    private void buildHomeHeader() {
        // 搜索框：玻璃胶囊 + 细线放大镜 + hint + 右侧前往按钮
        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setPadding(dp(16), 0, dp(8), 0);
        searchRow.setBackground(Theme.glass(this, 16));
        homeHeader.addView(searchRow, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        GlyphView searchIcon = glyph(GlyphView.SEARCH, Theme.SUB, dp(20));
        searchRow.addView(searchIcon, lp(dp(20), dp(20)));

        searchInput = new EditText(this);
        searchInput.setHint("搜索歌曲、歌手");
        searchInput.setHintTextColor(Theme.DIM);
        searchInput.setTextColor(Theme.TEXT);
        searchInput.setTextSize(13);
        searchInput.setSingleLine(true);
        searchInput.setBackground(null);
        searchInput.setPadding(dp(11), 0, 0, 0);
        searchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch();
                return true;
            }
            return false;
        });
        searchRow.addView(searchInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));

        FrameLayout goBox = new FrameLayout(this);
        goBox.setBackground(Theme.stroke(this, Color.TRANSPARENT, 7, Theme.LINE, 0.5f));
        GlyphView goIcon = glyph(GlyphView.ARROW, Theme.DIM, dp(14));
        goBox.addView(goIcon, new FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER));
        goBox.setOnClickListener(v -> performSearch());
        searchRow.addView(goBox, lp(dp(24), dp(24)));

        // 热搜词：首个琥珀高亮，其余细线胶囊，可横向滑动
        HorizontalScrollView hotRow = new HorizontalScrollView(this);
        hotRow.setHorizontalScrollBarEnabled(false);
        hotRow.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout.LayoutParams hotParams = lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(31));
        hotParams.setMargins(0, dp(18), 0, 0);
        homeHeader.addView(hotRow, hotParams);
        LinearLayout chips = new LinearLayout(this);
        hotRow.addView(chips, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        for (int i = 0; i < HOT.length; i++) {
            String word = HOT[i];
            TextView chip = text(word, 11.5f, i == 0 ? Theme.ACCENT : Theme.SUB);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(14), 0, dp(14), 0);
            chip.setBackground(i == 0
                    ? Theme.stroke(this, Theme.SOFT, 99, Theme.withAlpha(Theme.ACCENT, 80), 0.5f)
                    : Theme.glass(this, 99));
            chip.setOnClickListener(v -> {
                searchInput.setText(word);
                performSearch();
            });
            LinearLayout.LayoutParams chipParams = lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            chipParams.setMargins(0, 0, dp(8), 0);
            chips.addView(chip, chipParams);
        }

        // 分区标题 + 换一批 / 返回推荐
        LinearLayout section = new LinearLayout(this);
        section.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams sectionParams = lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(26));
        sectionParams.setMargins(0, dp(32), 0, 0);
        homeHeader.addView(section, sectionParams);
        sectionTitle = text("为你推荐", 17, Theme.TEXT);
        sectionTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        section.addView(sectionTitle, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        GlyphView refreshIcon = glyph(GlyphView.REFRESH, Theme.DIM, dp(14));
        section.addView(refreshIcon, lp(dp(14), dp(14)));
        refreshLabel = text("换一批", 11.5f, Theme.DIM);
        refreshLabel.setPadding(dp(6), 0, 0, 0);
        refreshLabel.setOnClickListener(v -> onRefresh());
        refreshIcon.setOnClickListener(v -> onRefresh());
        section.addView(refreshLabel);

        // 状态副标题
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams statusParams = lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(20));
        statusParams.setMargins(0, dp(6), 0, dp(8));
        homeHeader.addView(statusRow, statusParams);
        statusText = text("", 11, Theme.DIM);
        statusText.setSingleLine(true);
        statusText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        statusRow.addView(statusText, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
    }

    // ---------------- 歌单 header ----------------

    private void buildPlaylistHeader() {
        // 标题行：标题占左侧，右侧设置按钮
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams titleRowParams = lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        titleRowParams.setMargins(0, dp(10), 0, 0);
        playlistHeader.addView(titleRow, titleRowParams);

        TextView title = text("我的收藏", 26, Theme.TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        titleRow.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        FrameLayout settingsBtn = new FrameLayout(this);
        settingsBtn.setBackground(Theme.stroke(this, Theme.CARD, 99, Theme.STROKE, 0.5f));
        GlyphView settingsIcon = glyph(GlyphView.SETTINGS, Theme.SUB, dp(17));
        settingsBtn.addView(settingsIcon, new FrameLayout.LayoutParams(dp(17), dp(17), Gravity.CENTER));
        settingsBtn.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        titleRow.addView(settingsBtn, lp(dp(38), dp(38)));

        playlistSubtitle = text("", 11, Theme.DIM);
        LinearLayout.LayoutParams subParams = lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        subParams.setMargins(0, dp(7), 0, 0);
        playlistHeader.addView(playlistSubtitle, subParams);

        // 封面拼贴卡
        FrameLayout collage = new FrameLayout(this);
        collage.setBackground(Theme.stroke(this, Theme.CARD_ALT, 20, Theme.STROKE, 0.5f));
        LinearLayout.LayoutParams collageParams = lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(132));
        collageParams.setMargins(0, dp(24), 0, 0);
        playlistHeader.addView(collage, collageParams);

        FrameLayout tiles = new FrameLayout(this);
        // 容器需容纳错位后最下一张唱片（y 偏移 34dp + 56dp = 90dp），
        // 88dp 会把底边金边裁掉，增高到 96dp 留出余量
        FrameLayout.LayoutParams tilesParams = new FrameLayout.LayoutParams(dp(88), dp(96), Gravity.CENTER_VERTICAL);
        tilesParams.setMargins(dp(20), 0, 0, 0);
        collage.addView(tiles, tilesParams);
        List<Track> preview = favorites.all();
        for (int i = 2; i >= 0; i--) {
            // 收藏页封面也用唱片语言：单张黑胶 + 中心封面（不旋转）
            DiscThumbView tile = new DiscThumbView(this);
            boolean noCover = i >= preview.size()
                    || preview.get(i).cover == null || preview.get(i).cover.isEmpty();
            tile.setTrack(noCover ? null : preview.get(i).cover, noCover, i + 2);
            FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(dp(56), dp(56));
            p.setMargins(dp(i == 0 ? 0 : (i == 1 ? 28 : 14)),
                    dp(i == 0 ? 0 : (i == 1 ? 16 : 34)), 0, 0);
            tiles.addView(tile, p);
        }

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams infoParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL);
        infoParams.setMargins(dp(128), 0, dp(70), 0);
        collage.addView(info, infoParams);
        TextView collageTitle = text("循环这一叠", 15, Theme.TEXT);
        collageTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        info.addView(collageTitle);
        collageRecent = text("最近添加 · —", 11, Theme.DIM);
        collageRecent.setPadding(0, dp(8), 0, 0);
        info.addView(collageRecent);

        ControlButton playAll = new ControlButton(this);
        playAll.setAccent(true);
        playAll.setMode(ControlButton.PLAY);
        FrameLayout.LayoutParams playParams = new FrameLayout.LayoutParams(dp(44), dp(44),
                Gravity.CENTER_VERTICAL | Gravity.END);
        playParams.setMargins(0, 0, dp(20), 0);
        playAll.setOnClickListener(v -> {
            List<Track> all = favorites.all();
            if (bound && !all.isEmpty()) expandAndPlay(all, 0);
        });
        collage.addView(playAll, playParams);

        // 工具行：随机播放 / 排序 / 排序方式
        LinearLayout tools = new LinearLayout(this);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams toolsParams = lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(34));
        toolsParams.setMargins(0, dp(22), 0, dp(4));
        playlistHeader.addView(tools, toolsParams);

        LinearLayout shufflePill = pill(true);
        shufflePill.addView(glyph(GlyphView.SHUFFLE, Theme.ACCENT, dp(14)), lp(dp(14), dp(14)));
        TextView shuffleText = text("随机播放", 11.5f, Theme.ACCENT);
        shuffleText.setPadding(dp(7), 0, 0, 0);
        shufflePill.addView(shuffleText);
        shufflePill.setOnClickListener(v -> {
            List<Track> all = favorites.all();
            if (bound && !all.isEmpty()) {
                expandAndPlay(all, (int) (Math.random() * all.size()));
                Theme.toast(root, "随机播放已开始");
            }
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
            refreshList();
            Theme.toast(root, sortByTitle ? "按歌名排序" : "按添加时间排序");
        });
        tools.addView(sortPill, sortPillParams);

        sortLabel = text("按添加时间", 11, Theme.DIM);
        tools.addView(sortLabel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        // 右对齐
        sortLabel.setGravity(Gravity.END);
    }

    // ---------------- 迷你播放器 ----------------

    private void buildMiniPlayer() {
        miniBar = new FrameLayout(this);
        miniBar.setBackground(Theme.stroke(this, Theme.CARD, 18, Theme.STROKE, 0.5f));
        Theme.elevate(miniBar, 18, 5);
        FrameLayout.LayoutParams miniParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(60), Gravity.BOTTOM);
        miniParams.setMargins(dp(14), 0, dp(14), dp(16));
        root.addView(miniBar, miniParams);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(8), dp(12), dp(8));
        bar.setOnClickListener(v -> openPlayer());
        miniBar.addView(bar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        miniCover = new DiscThumbView(this);
        bar.addView(miniCover, lp(dp(40), dp(40)));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.CENTER_VERTICAL);
        info.setPadding(dp(11), 0, dp(6), 0);
        bar.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
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

    // ---------------- 底部 Tab ----------------

    private void buildTabs() {
        FrameLayout tabs = new FrameLayout(this);
        tabs.setBackground(Theme.rect(this, Theme.BG, 0));
        FrameLayout.LayoutParams tabsParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(TAB_H), Gravity.BOTTOM);
        root.addView(tabs, tabsParams);

        View topLine = new View(this);
        topLine.setBackground(Theme.divider(this));
        tabs.addView(topLine, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));

        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, dp(12), 0, 0);
        tabs.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        homeTab = tabItem("首页", GlyphView.HOME, true);
        playlistTab = tabItem("歌单", GlyphView.LIST, false);
        homeIcon = (GlyphView) homeTab.getTag();
        playlistIcon = (GlyphView) playlistTab.getTag();
        homeTab.setOnClickListener(v -> showHome());
        playlistTab.setOnClickListener(v -> showPlaylist());
        row.addView(homeTab, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        row.addView(playlistTab, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));

        // 琥珀短指示线，切换时 240ms 平移
        tabTrack = tabs;
        tabIndicator = new View(this);
        tabIndicator.setBackground(Theme.amber(this, 1));
        tabs.addView(tabIndicator, new FrameLayout.LayoutParams(dp(22), dp(2), Gravity.TOP));
        positionIndicator(0);
    }

    /** 一个 Tab = 细线图标 + 文字；图标放在 tag 里，便于切换时改色。 */
    private LinearLayout tabItem(String label, int iconMode, boolean active) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        GlyphView icon = glyph(iconMode, active ? Theme.ACCENT : Theme.DIM, dp(21));
        box.addView(icon, lp(dp(21), dp(21)));
        TextView labelView = text(label, 10.5f, active ? Theme.ACCENT : Theme.DIM);
        labelView.setGravity(Gravity.CENTER);
        labelView.setPadding(0, dp(6), 0, 0);
        box.addView(labelView);
        box.setTag(icon);
        return box;
    }

    private void styleTab(LinearLayout box, GlyphView icon, boolean active) {
        if (box == null) return;
        if (box.getChildAt(1) instanceof TextView) {
            ((TextView) box.getChildAt(1)).setTextColor(active ? Theme.ACCENT : Theme.DIM);
        }
        if (icon != null) icon.setGlyphColor(active ? Theme.ACCENT : Theme.DIM);
    }

    private void positionIndicator(int index) {
        if (tabTrack == null || tabIndicator == null) return;
        tabTrack.post(() -> {
            int width = tabTrack.getWidth();
            if (width <= 0) return;
            float target = (index == 0 ? width * 0.25f : width * 0.75f) - dp(11);
            tabIndicator.animate().translationX(target).setDuration(240).start();
        });
    }

    // ---------------- 数据 / 状态 ----------------

    /**
     * 铺骨架。ranked 决定是否留左侧排名位（首页推荐有、搜索结果没有）。
     * 淡入 160ms，期间列表先隐藏，否则骨架下面会露出热词行。
     */
    private void showSkeleton(boolean ranked) {
        if (skeleton == null) return;
        skeleton.animate().cancel();
        skeleton.setRanked(ranked);
        skeleton.setVisibility(View.VISIBLE);
        skeleton.animate().alpha(1f).setDuration(160).start();
        skeleton.start();
        if (list != null) list.setVisibility(View.INVISIBLE);
        skeletonVisible = true;
    }

    private void hideSkeleton() {
        if (skeleton == null) return;
        if (!skeletonVisible) {
            if (list != null) list.setVisibility(View.VISIBLE);
            return;
        }
        skeletonVisible = false;
        skeleton.animate().cancel();
        skeleton.animate().alpha(0f).setDuration(180).withEndAction(() -> {
            if (skeletonVisible) return;   // 淡出期间又来了新请求，别收
            skeleton.setVisibility(View.GONE);
            skeleton.stop();
            if (list != null) list.setVisibility(View.VISIBLE);
        }).start();
    }

    /** 切到歌单这类本地数据时用，不做过场，直接收掉。 */
    private void hideSkeletonNow() {
        if (skeleton == null) return;
        skeletonVisible = false;
        skeleton.animate().cancel();
        skeleton.setAlpha(0f);
        skeleton.setVisibility(View.GONE);
        skeleton.stop();
        if (list != null) list.setVisibility(View.VISIBLE);
    }

    private void loadTrending() {
        trendingLoaded = false;
        if (mode == MODE_HOME && !showingSearch) showSkeleton(true);
        updateStatus();
        trendingExecutor.execute(() -> {
            SearchRepository.Result result = repository.trending();
            main.post(() -> {
                trendingLoaded = true;
                hideSkeleton();
                trendingTracks.clear();
                trendingTracks.addAll(result.tracks);
                if (mode == MODE_HOME && !showingSearch) {
                    refreshList();
                    restoreScroll();
                } else {
                    updateStatus();
                }
            });
        });
    }

    private void performSearch() {
        String query = searchInput.getText().toString().trim();
        if (query.isEmpty()) {
            Theme.toast(root, "先输入歌名或歌手");
            return;
        }
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
        captureScroll();
        mode = MODE_HOME;
        showingSearch = true;
        searchPos = 0;
        searchTop = 0;
        int serial = ++searchSerial;
        showSkeleton(false);
        refreshList();
        restoreScroll();
        statusText.setText("搜索中…");
        searchExecutor.execute(() -> {
            SearchRepository.Result result = repository.search(query);
            main.post(() -> {
                if (serial != searchSerial) return;
                hideSkeleton();
                searchResults.clear();
                searchResults.addAll(result.tracks);
                if (mode == MODE_HOME) refreshList();
                if (searchResults.isEmpty() && !result.errors.isEmpty()) {
                    statusText.setText("搜索失败，请重试");
                }
            });
        });
    }

    private void onRefresh() {
        if (showingSearch) {
            showingSearch = false;
            searchInput.setText("");
            homePos = 0;
            homeTop = 0;
            refreshList();
            restoreScroll();
            if (trendingTracks.isEmpty()) loadTrending();
        } else {
            homePos = 0;
            homeTop = 0;
            loadTrending();
            Theme.toast(root, "已换一批推荐");
        }
    }

    /**
     * 记录当前列表的滚动位置。首页推荐、首页搜索结果、歌单三个状态各自独立，
     * 否则切 Tab 时 ListView 会沿用上一个列表的滚动偏移，看起来像"位置串了"。
     */
    private void captureScroll() {
        if (list == null || adapter == null) return;
        int pos = list.getFirstVisiblePosition();
        View child = list.getChildAt(0);
        int top = child == null ? 0 : child.getTop() - list.getPaddingTop();
        if (mode == MODE_PLAYLIST) {
            playlistPos = pos;
            playlistTop = top;
        } else if (showingSearch) {
            searchPos = pos;
            searchTop = top;
        } else {
            homePos = pos;
            homeTop = top;
        }
    }

    /** 恢复对应状态的滚动位置。必须 post 到布局之后，否则会被 notifyDataSetChanged 覆盖。 */
    private void restoreScroll() {
        if (list == null) return;
        int pos;
        int top;
        if (mode == MODE_PLAYLIST) {
            pos = playlistPos;
            top = playlistTop;
        } else if (showingSearch) {
            pos = searchPos;
            top = searchTop;
        } else {
            pos = homePos;
            top = homeTop;
        }
        final int targetPos = Math.max(0, pos);
        final int targetTop = top;
        list.post(() -> list.setSelectionFromTop(targetPos, targetTop));
    }

    private void showHome() {
        captureScroll();
        mode = MODE_HOME;
        showingSearch = false;
        searchInput.setText("");
        refreshList();
        restoreScroll();
    }

    private void showPlaylist() {
        captureScroll();
        mode = MODE_PLAYLIST;
        refreshList();
        restoreScroll();
    }

    private void refreshList() {
        tracks.clear();
        if (mode == MODE_HOME) {
            tracks.addAll(showingSearch ? searchResults : trendingTracks);
        } else {
            tracks.addAll(favorites.all());
            if (sortByTitle) tracks.sort((a, b) -> a.title.compareToIgnoreCase(b.title));
        }
        adapter.notifyDataSetChanged();
        homeHeader.setVisibility(mode == MODE_HOME ? View.VISIBLE : View.GONE);
        playlistHeader.setVisibility(mode == MODE_PLAYLIST ? View.VISIBLE : View.GONE);
        sectionTitle.setText(showingSearch ? "搜索结果" : "为你推荐");
        refreshLabel.setText(showingSearch ? "返回推荐" : "换一批");
        styleTab(homeTab, homeIcon, mode == MODE_HOME);
        styleTab(playlistTab, playlistIcon, mode == MODE_PLAYLIST);
        positionIndicator(mode == MODE_HOME ? 0 : 1);
        if (mode == MODE_PLAYLIST) {
            playlistSubtitle.setText(tracks.size() + " 首 · 本地收藏");
            sortLabel.setText(sortByTitle ? "按歌名" : "按添加时间");
            List<Track> all = favorites.all();
            collageRecent.setText(all.isEmpty() ? "还没有收藏" : "最近添加 · " + all.get(0).title);
        }
        // 歌单是本地数据，立即收骨架；首页若还在等接口，切回来时要补上
        if (mode == MODE_PLAYLIST) {
            hideSkeletonNow();
        } else if (!skeletonVisible && !trendingLoaded && !showingSearch) {
            showSkeleton(true);
        }
        updateStatus();
        updateMiniPlayer();
    }

    private void updateStatus() {
        if (statusText == null) return;
        if (mode == MODE_PLAYLIST) return;
        if (showingSearch) {
            if (searchResults.isEmpty()) {
                statusText.setText(trendingLoaded ? "没有找到结果，换个词试试" : "搜索中…");
            } else {
                // 搜索结果不带分P信息，列表不再区分合集，只报条数
                statusText.setText("找到 " + searchResults.size() + " 条");
            }
            return;
        }
        if (!trendingLoaded) {
            statusText.setText("正在连接 B站音源…");
            return;
        }
        statusText.setText(trendingTracks.isEmpty()
                ? "推荐源暂时离线 · 点上方热词试试"
                : "B站公开音源 · 实时更新");
    }

    private void updateMiniPlayer() {
        if (miniBar == null) return;
        Track current = bound ? service.getCurrent() : null;
        if (current == null) {
            miniBar.setVisibility(View.GONE);
            list.setPadding(0, dp(10), 0, dp(86));
            lastMiniId = "";
            return;
        }
        miniBar.setVisibility(View.VISIBLE);
        list.setPadding(0, dp(10), 0, dp(60 + 16 + 12 + 10));
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

    private void openPlayer() {
        if (bound && service.getCurrent() != null) startActivity(new Intent(this, PlayerActivity.class));
    }

    /**
     * 统一点播入口：命中合集时先尝试展开。
     *
     * B站搜索「抖音热歌」这类词，返回的前排几乎都是「100 首连播」合集；直接整段播放
     * 会有 40 分钟的进度条和错误的切歌边界。多 P 合集可以拆成真单曲队列，所以这里
     * 后台先 expand：成功 → 播放拆分后的单曲列表；不可拆（单 P 串烧）或失败 → 原样播放。
     */
    private void expandAndPlay(List<Track> list, int position) {
        if (!bound || position < 0 || position >= list.size()) return;
        final List<Track> snapshot = new ArrayList<>(list);
        final Track target = snapshot.get(position);
        // 列表不区分合集：所有 B站条目都异步试探真实分P，能拆就拆成单曲队列，
        // 拆不动（单P / 请求失败）就原样播放；expand 内部已按真实分P数判定。
        if (!Track.SOURCE_BILIBILI.equals(target.source)) {
            service.playQueue(snapshot, position, MusicService.QUEUE_SEARCH);
            return;
        }
        expandExecutor.execute(() -> {
            List<Track> songs = repository.expand(target);
            main.post(() -> {
                if (!bound) return;
                if (songs == null || songs.isEmpty()) {
                    service.playQueue(snapshot, position, MusicService.QUEUE_SEARCH);
                } else {
                    service.playQueue(songs, 0, MusicService.QUEUE_SEARCH);
                    Theme.toast(root, "已展开合集：共 " + songs.size() + " 首");
                }
            });
        });
    }

    @Override protected void onResume() {
        super.onResume();
        // 从设置页切换主题返回后，重建以套用新配色
        if (Theme.night != themeNightAtCreate) recreate();
    }

    @Override protected void onStart() {
        super.onStart();
        Intent intent = new Intent(this, MusicService.class);
        startService(intent);
        bindService(intent, connection, Context.BIND_AUTO_CREATE);
        main.post(miniTicker);
    }

    @Override protected void onStop() {
        main.removeCallbacks(miniTicker);
        if (bound) {
            service.removeListener(playbackListener);
            unbindService(connection);
            bound = false;
            service = null;
        }
        super.onStop();
    }

    @Override protected void onDestroy() {
        searchSerial++;
        searchExecutor.shutdownNow();
        trendingExecutor.shutdownNow();
        expandExecutor.shutdownNow();
        repository.close();
        super.onDestroy();
    }

    // ---------------- 视图工具 ----------------

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

    private LinearLayout.LayoutParams lp(int width, int height) {
        return new LinearLayout.LayoutParams(width, height);
    }

    private int dp(int value) {
        return Theme.dp(this, value);
    }

    private TextView text(String value, float sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        return view;
    }

    private static String formatTime(long ms) {
        long seconds = Math.max(0, ms / 1000);
        return String.format(Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60);
    }

    // ---------------- 列表 ----------------

    private class TrackAdapter extends BaseAdapter {
        @Override public int getCount() {
            if (tracks.isEmpty()) return mode == MODE_PLAYLIST ? 1 : HOT.length;
            return tracks.size();
        }
        @Override public Object getItem(int position) { return null; }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            if (tracks.isEmpty()) {
                return mode == MODE_PLAYLIST ? emptyFavoriteRow() : hotWordRow(position);
            }
            return trackRow(tracks.get(position), position);
        }

        private View hotWordRow(int position) {
            LinearLayout wrap = rowWrap();
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), 0, dp(12), 0);
            row.setBackground(Theme.glass(MainActivity.this, 12));
            TextView rank = text(String.format(Locale.getDefault(), "%02d", position + 1),
                    14, position < 3 ? Theme.GOLD : Theme.DIM);
            rank.setTypeface(Theme.serif());
            rank.setGravity(Gravity.CENTER);
            row.addView(rank, lp(dp(30), dp(54)));
            TextView word = text(HOT[position], 13.5f, Theme.TEXT);
            row.addView(word, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            GlyphView arrow = glyph(GlyphView.ARROW, Theme.DIM, dp(16));
            row.addView(arrow, lp(dp(16), dp(16)));
            wrap.addView(row, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));
            return wrap;
        }

        private View emptyFavoriteRow() {
            LinearLayout wrap = rowWrap();
            LinearLayout box = new LinearLayout(MainActivity.this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            box.setPadding(dp(20), dp(14), dp(20), dp(14));
            FrameLayout iconBox = new FrameLayout(MainActivity.this);
            iconBox.setBackground(Theme.glass(MainActivity.this, 24));
            GlyphView note = glyph(GlyphView.MUSIC_BOX, Theme.ACCENT, dp(32));
            iconBox.addView(note, new FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER));
            box.addView(iconBox, lp(dp(76), dp(76)));
            TextView empty = text("还没有收藏", 15, Theme.TEXT);
            empty.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams ep = lp(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            ep.setMargins(0, dp(14), 0, 0);
            box.addView(empty, ep);
            TextView sub = text("去首页逛逛，点爱心就能存到这里", 11.5f, Theme.DIM);
            sub.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams sp = lp(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            sp.setMargins(0, dp(7), 0, 0);
            box.addView(sub, sp);
            TextView btn = text("去首页逛逛", 11.5f, Theme.ACCENT);
            btn.setGravity(Gravity.CENTER);
            btn.setPadding(dp(20), dp(9), dp(20), dp(9));
            btn.setBackground(Theme.stroke(MainActivity.this, Theme.SOFT, 99,
                    Theme.withAlpha(Theme.ACCENT, 80), 0.5f));
            btn.setOnClickListener(v -> showHome());
            LinearLayout.LayoutParams bp = lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            bp.setMargins(0, dp(16), 0, 0);
            box.addView(btn, bp);
            wrap.addView(box, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            return wrap;
        }

        /** 行副标题：只显示歌手，不展示来源/合集标注（搜索结果无分P信息，列表不区分合集）。 */
        private CharSequence metaLine(Track track) {
            String artist = track.artist == null ? "" : track.artist;
            if (track.previewOnly) {
                return artist.isEmpty() ? "试听片段" : artist + " · 试听片段";
            }
            return artist;
        }

        private View trackRow(Track track, int position) {
            LinearLayout wrap = rowWrap();
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            boolean playing = bound && service.getCurrent() != null
                    && service.getCurrent().stableId().equals(track.stableId());
            boolean ranked = mode == MODE_HOME && !showingSearch;
            if (ranked) {
                TextView rank = text(String.format(Locale.getDefault(), "%02d", position + 1), 17,
                        position < 3 ? Theme.GOLD : Theme.DIM);
                rank.setTypeface(Theme.serif());
                rank.setGravity(Gravity.CENTER);
                row.addView(rank, lp(dp(24), ViewGroup.LayoutParams.MATCH_PARENT));
            }

            // 列表不区分合集：所有条目统一一张黑胶唱片，不用叠放
            DiscThumbView thumb = new DiscThumbView(MainActivity.this);
            boolean noCover = track.cover == null || track.cover.isEmpty();
            thumb.setTrack(track.cover, noCover, position);
            // 列表缩略图不旋转——旋转只保留在底部迷你播放器上
            // 左边距去掉，使唱片与上方筛选项（随机播放/排序）左边缘对齐
            LinearLayout.LayoutParams thumbParams = lp(dp(54), dp(52));
            row.addView(thumb, thumbParams);

            LinearLayout info = new LinearLayout(MainActivity.this);
            info.setOrientation(LinearLayout.VERTICAL);
            info.setGravity(Gravity.CENTER_VERTICAL);
            info.setPadding(dp(13), 0, dp(5), 0);
            row.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            TextView name = text(track.title, 13.5f, playing ? Theme.ACCENT : Theme.TEXT);
            name.setTypeface(Typeface.DEFAULT, playing ? Typeface.BOLD : Typeface.NORMAL);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            info.addView(name);

            LinearLayout meta = new LinearLayout(MainActivity.this);
            meta.setGravity(Gravity.CENTER_VERTICAL);
            meta.setPadding(0, dp(6), 0, 0);
            info.addView(meta);
            // 副标题：只显示歌手
            CharSequence metaText = metaLine(track);
            TextView artist = new TextView(MainActivity.this);
            artist.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
            artist.setTextColor(Theme.DIM);
            artist.setText(metaText);
            artist.setSingleLine(true);
            artist.setEllipsize(android.text.TextUtils.TruncateAt.END);
            artist.setPadding(0, dp(6), 0, 0);
            artist.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            meta.addView(artist);

            LinearLayout actions = new LinearLayout(MainActivity.this);
            actions.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(actions, lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            boolean saved = favorites.contains(track);
            GlyphView heart = glyph(saved ? GlyphView.HEART_FILLED : GlyphView.HEART,
                    saved ? Theme.ACCENT : Theme.DIM, dp(18));
            heart.setOnClickListener(v -> {
                boolean added = favorites.toggle(track);
                Theme.toast(root, added ? "已加入我的收藏" : "已移出收藏");
                refreshList();
            });
            LinearLayout.LayoutParams heartParams = lp(dp(30), dp(30));
            actions.addView(heart, heartParams);

            if (mode == MODE_PLAYLIST) {
                GlyphView more = glyph(GlyphView.MORE, Theme.DIM, dp(18));
                more.setOnClickListener(v -> showTrackMenu(v, track));
                actions.addView(more, lp(dp(26), dp(30)));
            } else {
                GlyphView play = glyph(GlyphView.PLAY, Theme.DIM, dp(18));
                play.setOnClickListener(v -> {
                    if (bound) expandAndPlay(tracks, position);
                });
                LinearLayout.LayoutParams playParams = lp(dp(26), dp(30));
                playParams.setMargins(dp(14), 0, 0, 0);
                actions.addView(play, playParams);
            }

            wrap.addView(row, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(70)));
            return wrap;
        }

        private void showTrackMenu(View anchor, Track track) {
            LinearLayout box = new LinearLayout(MainActivity.this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(6), dp(6), dp(6), dp(6));
            box.setBackground(Theme.stroke(MainActivity.this, Theme.SURFACE, 14, Theme.STROKE, 0.5f));
            box.setElevation(dp(8));

            final PopupWindow[] ref = new PopupWindow[1];
            String[] labels = {"打开原始页面", "移出收藏"};
            for (int i = 0; i < labels.length; i++) {
                final int action = i;
                TextView row = text(labels[i], 13, Theme.TEXT);
                row.setPadding(dp(14), dp(11), dp(18), dp(11));
                row.setOnClickListener(v -> {
                    if (action == 0 && track.sourceUrl != null && !track.sourceUrl.isEmpty()) {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(track.sourceUrl)));
                    } else if (action == 1) {
                        favorites.toggle(track);
                        Theme.toast(root, "已移出收藏");
                        refreshList();
                    }
                    if (ref[0] != null) ref[0].dismiss();
                });
                box.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }

            PopupWindow popup = new PopupWindow(box, dp(150),
                    ViewGroup.LayoutParams.WRAP_CONTENT, true);
            popup.setOutsideTouchable(true);
            popup.setBackgroundDrawable(null);
            popup.setElevation(dp(8));
            ref[0] = popup;
            popup.showAsDropDown(anchor, -dp(108), dp(4));
        }

        private LinearLayout rowWrap() {
            LinearLayout wrap = new LinearLayout(MainActivity.this);
            wrap.setOrientation(LinearLayout.VERTICAL);
            return wrap;
        }
    }
}
