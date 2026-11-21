package com.soundhunt.mobile;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
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
import android.widget.PopupWindow;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

/**
 * 播放页 v6.5：墨底六层流动声波律动场（暂停收平变暗）、衬线歌名左对齐、
 * 3px 金色进度条、70dp 琥珀纯色主播放键（保持最早样式）。
 * 三点菜单 = 切换音质（64K / 132K / 无损，续播原进度）。
 */
public class PlayerActivity extends Activity {
    private MusicService service;
    private boolean bound;
    private boolean dragging;
    private String lastTrackId = "";
    private int pendingDir;
    private FrameLayout root;
    private FrameLayout stage;
    private VinylDiscView vinyl;
    private TextView title;
    private TextView artist;
    private TextView positionText;
    private TextView durationText;
    private TextView qualityLabel;
    private TextView homeSource;
    private LinearLayout originLabel;
    private GlyphView heart;
    private GlyphView repeatButton;
    private GlyphView shuffleButton;
    private SeekBar seekBar;
    private ControlButton playButton;
    private FavoriteStore favorites;
    // 合集选集：入口行（进度条下方）+ 底部弹出的分 P 列表
    private LinearLayout episodeRow;
    private TextView episodeLabel;
    private TextView episodeIndex;
    private FrameLayout scrim;
    private LinearLayout episodeSheet;
    private ListView episodeList;
    private EpisodeAdapter episodeAdapter;
    private TextView sheetTitle;
    private TextView sheetCount;
    private boolean sheetShown;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((MusicService.LocalBinder) binder).getService();
            bound = true;
            service.addListener(listener);
            updateTrack();
            updatePlayback();
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
        }
        @Override public void onError(String message) {
            Toast.makeText(PlayerActivity.this, message, Toast.LENGTH_SHORT).show();
        }
    };

    private final Runnable progressUpdater = new Runnable() {
        @Override public void run() {
            updateProgress();
            handler.postDelayed(this, 500);
        }
    };

    // 频谱 pump：以 ~40ms 频率把真实音频低频能量喂给唱片，驱动转速
    private final Runnable spectrumPump = new Runnable() {
        @Override public void run() {
            if (vinyl == null) { handler.postDelayed(this, 40); return; }
            if (bound && service != null && service.isPlaying()) {
                vinyl.setBands(service.getBass(), service.getMid(), service.getHi());
            } else {
                vinyl.setBands(0f, 0f, 0f);
            }
            handler.postDelayed(this, 40);
        }
    };

    private boolean themeNightAtCreate;

    @Override protected void onCreate(Bundle savedInstanceState) {
        Theme.init(this);
        themeNightAtCreate = Theme.night;
        super.onCreate(savedInstanceState);
        Theme.applySystemBars(this);
        favorites = new FavoriteStore(this);
        buildUi();
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackground(Theme.ambient(this));

        // 手势层：竖直滑动切歌，横向（进度条）与点击不拦截，保证按钮/进度条可用
        SwipeLayer swipe = new SwipeLayer(this);
        swipe.setListener(this::onSwipe);
        root.addView(swipe, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        swipe.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 顶栏：返回 / 正在播放 / 更多
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(22), 0, dp(22), 0);
        content.addView(top, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        FrameLayout backBox = new FrameLayout(this);
        GlyphView backIcon = glyph(GlyphView.BACK, Theme.TEXT, dp(20));
        backBox.addView(backIcon, new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
        backBox.setOnClickListener(v -> finish());
        top.addView(backBox, lp(dp(36), dp(36)));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setGravity(Gravity.CENTER);
        top.addView(header, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        TextView caption = text("正在播放", 11, Theme.SUB);
        caption.setLetterSpacing(0.16f);
        caption.setGravity(Gravity.CENTER);
        header.addView(caption);
        homeSource = text("来自 · 为你推荐", 9, Theme.DIM);
        homeSource.setLetterSpacing(0.08f);
        homeSource.setGravity(Gravity.CENTER);
        homeSource.setPadding(0, dp(4), 0, 0);
        header.addView(homeSource);

        FrameLayout moreBox = new FrameLayout(this);
        GlyphView moreIcon = glyph(GlyphView.MORE, Theme.SUB, dp(20));
        moreBox.addView(moreIcon, new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
        moreBox.setOnClickListener(v -> showMore(moreBox));
        top.addView(moreBox, lp(dp(36), dp(36)));

        // 舞台：黑胶唱片，转速随音乐频谱变化；与底部留白按权重分配剩余高度
        stage = new FrameLayout(this);
        content.addView(stage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 2f));
        vinyl = new VinylDiscView(this);
        stage.addView(vinyl, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        // 歌名：衬线 19sp 左对齐单行 + 收藏心形（设计图 v6.5：字号收紧、上移）
        LinearLayout meta = new LinearLayout(this);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        meta.setPadding(dp(34), dp(16), dp(34), 0);
        content.addView(meta, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout metaText = new LinearLayout(this);
        metaText.setOrientation(LinearLayout.VERTICAL);
        meta.addView(metaText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        title = text("尚未播放", 16.5f, Theme.TEXT);
        title.setTypeface(Theme.serif());
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        metaText.addView(title);
        artist = text("挑一首开始听", 11f, Theme.DIM);
        artist.setSingleLine(true);
        artist.setEllipsize(android.text.TextUtils.TruncateAt.END);
        artist.setPadding(0, dp(6), 0, 0);
        metaText.addView(artist);
        heart = glyph(GlyphView.HEART, Theme.DIM, dp(23));
        heart.setOnClickListener(v -> toggleFavorite());
        meta.addView(heart, lp(dp(30), dp(30)));

        // 进度：3px 金条 + 时间
        LinearLayout progressBlock = new LinearLayout(this);
        progressBlock.setOrientation(LinearLayout.VERTICAL);
        progressBlock.setPadding(dp(34), dp(36), dp(34), 0);
        content.addView(progressBlock, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        seekBar = new SeekBar(this);
        seekBar.setMax(1000);
        Theme.styleSeekBar(seekBar);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser && bound) positionText.setText(time(progress));
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                dragging = false;
                if (bound) service.seekTo(bar.getProgress());
            }
        });
        progressBlock.addView(seekBar, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(22)));
        LinearLayout times = new LinearLayout(this);
        times.setPadding(0, dp(12), 0, 0);
        progressBlock.addView(times, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(14)));
        positionText = text("0:00", 10, Theme.DIM);
        times.addView(positionText, new LinearLayout.LayoutParams(0, dp(14), 1));
        durationText = text("0:00", 10, Theme.DIM);
        durationText.setGravity(Gravity.END);
        times.addView(durationText, lp(ViewGroup.LayoutParams.WRAP_CONTENT, dp(14)));

        // 合集选集入口：仅当当前曲目来自拆分的合集时可见
        episodeRow = new LinearLayout(this);
        episodeRow.setGravity(Gravity.CENTER_VERTICAL);
        episodeRow.setPadding(dp(34), dp(10), dp(34), dp(2));
        episodeRow.setVisibility(View.GONE);
        episodeRow.setOnClickListener(v -> showEpisodeSheet());
        content.addView(episodeRow, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));
        GlyphView queueIcon = glyph(GlyphView.QUEUE, Theme.ACCENT, dp(14));
        episodeRow.addView(queueIcon, lp(dp(14), dp(14)));
        episodeLabel = text("", 10.5f, Theme.SUB);
        episodeLabel.setSingleLine(true);
        episodeLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams epLabelParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        epLabelParams.setMargins(dp(8), 0, dp(8), 0);
        episodeRow.addView(episodeLabel, epLabelParams);
        // 集数固定在右侧，合集名再长也不会把它挤掉
        episodeIndex = text("", 10.5f, Theme.SUB);
        episodeIndex.setTypeface(Theme.serif(), Typeface.BOLD);
        episodeRow.addView(episodeIndex, lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        GlyphView epChevron = glyph(GlyphView.ARROW, Theme.DIM, dp(13));
        LinearLayout.LayoutParams chevronParams = lp(dp(13), dp(13));
        chevronParams.setMargins(dp(10), 0, 0, 0);
        episodeRow.addView(epChevron, chevronParams);

        // 控制行（设计图 v6.5）：随机 / 上一集 / 主播放 / 下一集 / 循环
        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(dp(22), dp(10), dp(22), 0);
        content.addView(controls, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(92)));
        shuffleButton = glyph(GlyphView.SHUFFLE, Theme.DIM, dp(20));
        shuffleButton.setOnClickListener(v -> {
            if (bound && service.getQueueSize() > 0) {
                service.playAt((int) (Math.random() * service.getQueueSize()));
                Theme.toast(root, "随机播放");
            }
        });
        controls.addView(shuffleButton, new LinearLayout.LayoutParams(0, dp(24), 1));
        ControlButton previous = new ControlButton(this);
        previous.setMode(ControlButton.PREVIOUS);
        previous.setOnClickListener(v -> { if (bound) service.previous(); });
        controls.addView(previous, lp(dp(40), dp(40)));
        playButton = new ControlButton(this);
        playButton.setAccent(true);
        playButton.setMode(ControlButton.PLAY);
        playButton.setOnClickListener(v -> { if (bound) service.toggle(); });
        LinearLayout.LayoutParams playParams = lp(dp(66), dp(66));
        playParams.setMargins(dp(14), 0, dp(14), 0);
        controls.addView(playButton, playParams);
        ControlButton next = new ControlButton(this);
        next.setMode(ControlButton.NEXT);
        next.setOnClickListener(v -> { if (bound) service.next(); });
        controls.addView(next, lp(dp(40), dp(40)));
        repeatButton = glyph(GlyphView.REPEAT, Theme.DIM, dp(20));
        repeatButton.setOnClickListener(v -> {
            if (bound) {
                service.setRepeat(!service.isRepeat());
                updatePlayback();
                Theme.toast(root, service.isRepeat() ? "单曲循环 · 开" : "单曲循环 · 关");
            }
        });
        controls.addView(repeatButton, new LinearLayout.LayoutParams(0, dp(24), 1));

        // 底部留白：与律动场按比例分配剩余高度，控制行落在设计图位置（略微留白，不贴底）
        View bottomSpacer = new View(this);
        content.addView(bottomSpacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.28f));

        // 设计图 v6.5：去掉最底部一行（音质标签 / 收藏 / 原始页面）

        // 合集选集面板：scrim + 底部弹层，默认隐藏
        scrim = new FrameLayout(this);
        scrim.setBackgroundColor(0xB3000000);
        scrim.setVisibility(View.GONE);
        scrim.setOnClickListener(v -> hideEpisodeSheet());
        root.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        episodeSheet = new LinearLayout(this);
        episodeSheet.setOrientation(LinearLayout.VERTICAL);
        episodeSheet.setBackground(Theme.stroke(this, Theme.SURFACE, 22, Theme.STROKE, 0.5f));
        episodeSheet.setPadding(dp(22), dp(18), dp(22), dp(18));
        scrim.addView(episodeSheet, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        LinearLayout sheetHead = new LinearLayout(this);
        sheetHead.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout sheetHeadText = new LinearLayout(this);
        sheetHeadText.setOrientation(LinearLayout.VERTICAL);
        sheetHead.addView(sheetHeadText, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        sheetTitle = text("", 15, Theme.TEXT);
        sheetTitle.setTypeface(Theme.serif(), Typeface.BOLD);
        sheetTitle.setSingleLine(true);
        sheetTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        sheetHeadText.addView(sheetTitle);
        sheetCount = text("", 10, Theme.DIM);
        sheetCount.setPadding(0, dp(4), 0, 0);
        sheetHeadText.addView(sheetCount);
        GlyphView sheetClose = glyph(GlyphView.BACK, Theme.SUB, dp(18));
        sheetClose.setOnClickListener(v -> hideEpisodeSheet());
        sheetHead.addView(sheetClose, lp(dp(30), dp(30)));
        episodeSheet.addView(sheetHead, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        episodeList = new ListView(this);
        episodeList.setDivider(null);
        episodeList.setDividerHeight(0);
        episodeList.setVerticalScrollBarEnabled(false);
        episodeList.setOverScrollMode(View.OVER_SCROLL_NEVER);
        episodeAdapter = new EpisodeAdapter();
        episodeList.setAdapter(episodeAdapter);
        LinearLayout.LayoutParams listParams = lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(312));
        listParams.setMargins(0, dp(6), 0, 0);
        episodeSheet.addView(episodeList, listParams);

        setContentView(root);
        Theme.applySafeArea(root);
    }

    // ---------------- 合集选集 ----------------

    /** 弹出底部选集面板，列表滚动到当前集。 */
    private void showEpisodeSheet() {
        if (!bound) return;
        List<Track> queue = service.getQueue();
        Track current = service.getCurrent();
        if (queue.size() < 2 || current == null
                || current.parentTitle == null || current.parentTitle.isEmpty()) return;
        sheetTitle.setText(current.parentTitle);
        sheetCount.setText("共 " + queue.size() + " 集 · 点选任意一集播放");
        episodeAdapter.notifyDataSetChanged();
        int rows = Math.min(queue.size(), 6);
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) episodeList.getLayoutParams();
        params.height = dp(rows * 52);
        episodeList.setLayoutParams(params);
        scrim.setVisibility(View.VISIBLE);
        scrim.setAlpha(0f);
        scrim.animate().alpha(1f).setDuration(180).start();
        episodeSheet.setTranslationY(dp(rows * 52 + 90));
        episodeSheet.animate().translationY(0f).setDuration(260)
                .setInterpolator(new DecelerateInterpolator(1.4f)).start();
        sheetShown = true;
        int current0 = Math.max(0, service.getCurrentIndex());
        episodeList.post(() -> episodeList.setSelection(Math.max(0, current0 - 2)));
    }

    private void hideEpisodeSheet() {
        if (!sheetShown) return;
        sheetShown = false;
        scrim.animate().alpha(0f).setDuration(180).start();
        episodeSheet.animate().translationY(episodeSheet.getHeight()).setDuration(220)
                .withEndAction(() -> {
                    if (!sheetShown) scrim.setVisibility(View.GONE);
                }).start();
    }

    /** 选集列表：序号 + 歌名 + 时长；当前集琥珀高亮。顺序与播放队列一致，点选即跳播。 */
    private class EpisodeAdapter extends BaseAdapter {
        @Override public int getCount() {
            return bound ? service.getQueue().size() : 0;
        }
        @Override public Object getItem(int position) { return null; }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            List<Track> queue = service.getQueue();
            if (position >= queue.size()) return new View(PlayerActivity.this);
            Track track = queue.get(position);
            boolean current0 = position == service.getCurrentIndex();

            LinearLayout row = new LinearLayout(PlayerActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), 0, dp(8), 0);

            TextView index = text(String.format(Locale.getDefault(), "%02d", position + 1),
                    12, current0 ? Theme.ACCENT : Theme.DIM);
            index.setTypeface(Theme.serif());
            index.setGravity(Gravity.CENTER);
            row.addView(index, lp(dp(30), dp(44)));

            LinearLayout info = new LinearLayout(PlayerActivity.this);
            info.setOrientation(LinearLayout.VERTICAL);
            info.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            TextView name = text(track.title, 12.5f, current0 ? Theme.ACCENT : Theme.TEXT);
            name.setTypeface(Typeface.DEFAULT, current0 ? Typeface.BOLD : Typeface.NORMAL);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            info.addView(name);
            TextView meta = text(track.artist.isEmpty() ? track.sourceLabel() : track.artist,
                    9.5f, Theme.DIM);
            meta.setSingleLine(true);
            meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
            meta.setPadding(0, dp(3), 0, 0);
            info.addView(meta);

            TextView duration = text(time(track.durationMs), 10,
                    current0 ? Theme.ACCENT : Theme.DIM);
            row.addView(duration, lp(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));

            if (current0) {
                GlyphView playing = glyph(GlyphView.PLAY, Theme.ACCENT, dp(13));
                row.addView(playing, lp(dp(13), dp(13)));
                ((LinearLayout.LayoutParams) playing.getLayoutParams()).setMargins(dp(10), 0, 0, 0);
            } else {
                View spacer = new View(PlayerActivity.this);
                row.addView(spacer, lp(dp(23), dp(44)));
            }

            row.setOnClickListener(v -> {
                if (bound && position != service.getCurrentIndex()) {
                    service.playAt(position);
                }
                hideEpisodeSheet();
            });
            return row;
        }
    }

    /** 三点菜单 = 切换音质：自绘弹框，跟随深浅主题。 */
    private void showMore(View anchor) {
        int current = bound ? service.getQualityPref() : BiliClient.QUALITY_132K;
        int[] ids = {BiliClient.QUALITY_64K, BiliClient.QUALITY_132K, BiliClient.QUALITY_FLAC};
        String[] names = {"标准 64K", "高清 132K", "无损 FLAC"};

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(6), dp(6), dp(6), dp(6));
        box.setBackground(Theme.stroke(this, Theme.SURFACE, 14, Theme.STROKE, 0.5f));
        box.setElevation(dp(8));

        final PopupWindow[] popupRef = new PopupWindow[1];
        for (int i = 0; i < ids.length; i++) {
            final int quality = ids[i];
            boolean selected = quality == current;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(11), dp(16), dp(11));

            GlyphView check = new GlyphView(this);
            check.setMode(GlyphView.CHECK);
            check.setGlyphColor(selected ? Theme.ACCENT : Color.TRANSPARENT);
            row.addView(check, lp(dp(16), dp(16)));

            TextView label = text(names[i], 13, selected ? Theme.ACCENT : Theme.TEXT);
            label.setPadding(dp(9), 0, 0, 0);
            if (selected) label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            row.addView(label, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1));

            row.setOnClickListener(v -> {
                switchQuality(quality);
                if (popupRef[0] != null) popupRef[0].dismiss();
            });
            box.addView(row, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        PopupWindow popup = new PopupWindow(box,
                dp(150), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setOutsideTouchable(true);
        popup.setBackgroundDrawable(null);
        popup.setElevation(dp(8));
        popupRef[0] = popup;
        popup.showAsDropDown(anchor, -dp(110), dp(6));
    }

    private void switchQuality(int id) {
        if (!bound || service.getCurrent() == null) {
            getSharedPreferences("player", MODE_PRIVATE).edit()
                    .putInt("audio_quality", id).apply();
            Theme.toast(root, "音质已设为 " + BiliClient.qualityLabel(id));
            return;
        }
        int before = service.getQualityPref();
        service.switchQuality(id);
        String name = BiliClient.qualityLabel(id);
        Theme.toast(root, before == id ? "音质已是 " + name + "，重新加载中"
                : "音质切换为 " + name);
        updatePlayback();
    }

    private void toggleFavorite() {
        if (!bound || service.getCurrent() == null) return;
        boolean added = favorites.toggle(service.getCurrent());
        updateFavorite();
        Theme.toast(root, added ? "已加入我的收藏" : "已移出收藏");
    }

    // ---------------- 滑动切歌 ----------------

    private void onSwipe(int dir) {
        // 选集弹层打开时不切歌，避免与列表滚动冲突
        if (sheetShown) return;
        if (!bound || service.getQueueSize() == 0) return;
        pendingDir = dir;
        if (dir > 0) service.next(); else service.previous();
    }

    /** 切歌时舞台轻微位移淡入，方向暗示上/下一首。 */
    private void playSwapAnimation(int dir) {
        if (stage == null) return;
        float h = stage.getHeight();
        if (h <= 0) h = dp(400);
        float fromY = dir == 0 ? 0f : -dir * h * 0.10f;
        stage.setAlpha(0.45f);
        stage.setTranslationY(fromY);
        stage.animate().alpha(1f).translationY(0f)
                .setDuration(240).setInterpolator(new DecelerateInterpolator()).start();
    }

    /** 竖直滑动拦截层：只在明显竖滑时拦截，避免影响进度条与按钮点击。 */
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

    /** 播放=唱片随节奏转，暂停=缓缓停下。 */
    private void syncMotion() {
        if (!bound || vinyl == null) return;
        vinyl.setPlaying(service.isPlaying());
    }

    private void updateTrack() {
        if (!bound || service.getCurrent() == null) return;
        Track track = service.getCurrent();
        List<Track> queue = service.getQueue();
        boolean collection = track.parentTitle != null && !track.parentTitle.isEmpty()
                && track.page > 0 && queue.size() > 1;
        episodeRow.setVisibility(collection ? View.VISIBLE : View.GONE);
        if (collection) {
            episodeLabel.setText(track.parentTitle);
            episodeIndex.setText("第 " + (service.getCurrentIndex() + 1) + " / " + queue.size() + " 集");
        }
        if (sheetShown && episodeAdapter != null) episodeAdapter.notifyDataSetChanged();
        if (track.stableId().equals(lastTrackId)) return;
        lastTrackId = track.stableId();
        playSwapAnimation(pendingDir);
        pendingDir = 0;
        if (vinyl != null) {
            boolean noCover = track.cover == null || track.cover.isEmpty();
            vinyl.setCoverUrl(noCover ? null : track.cover);
        }
        title.setText(track.title);
        // 中间行只显示歌手，不带来源「B站」
        artist.setText(track.artist);
        homeSource.setText("来自 · " + track.sourceLabel());
        updateFavorite();
    }

    private void updatePlayback() {
        if (!bound) return;
        playButton.setMode(service.isPlaying() ? ControlButton.PAUSE : ControlButton.PLAY);
        repeatButton.setGlyphColor(service.isRepeat() ? Theme.ACCENT : Theme.DIM);
        syncMotion();
        updateProgress();
    }

    private void updateProgress() {
        if (!bound || dragging) return;
        int duration = Math.max(0, service.getDuration());
        int position = Math.max(0, service.getPosition());
        seekBar.setMax(Math.max(duration, 1));
        seekBar.setProgress(Math.min(position, Math.max(duration, 1)));
        positionText.setText(time(position));
        durationText.setText(time(duration));
    }

    private void updateFavorite() {
        if (bound && service.getCurrent() != null) {
            boolean saved = favorites.contains(service.getCurrent());
            heart.setMode(saved ? GlyphView.HEART_FILLED : GlyphView.HEART);
            heart.setGlyphColor(saved ? Theme.ACCENT : Theme.DIM);
            if (originLabel != null) setFooterLabel(originLabel,saved ? "已收藏" : "收藏");
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (Theme.night != themeNightAtCreate) recreate();
    }

    @Override protected void onStart() {
        super.onStart();
        bindService(new Intent(this, MusicService.class), connection, Context.BIND_AUTO_CREATE);
        handler.post(progressUpdater);
        handler.post(spectrumPump);
    }

    @Override protected void onStop() {
        handler.removeCallbacks(progressUpdater);
        handler.removeCallbacks(spectrumPump);
        if (bound) {
            service.removeListener(listener);
            unbindService(connection);
            bound = false;
            service = null;
        }
        super.onStop();
    }

    // ---------------- 视图工具 ----------------

    private LinearLayout footerAction(String label, int iconMode) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        GlyphView icon = glyph(iconMode, Theme.DIM, dp(15));
        box.addView(icon, lp(dp(15), dp(15)));
        TextView labelView = text(label, 10.5f, Theme.DIM);
        labelView.setPadding(dp(6), 0, 0, 0);
        box.addView(labelView);
        return box;
    }

    private void setFooterLabel(LinearLayout box, String value) {
        if (box != null && box.getChildAt(1) instanceof TextView) {
            ((TextView) box.getChildAt(1)).setText(value);
        }
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

    private int dp(int value) {
        return Theme.dp(this, value);
    }

    private static String time(long ms) {
        long seconds = Math.max(0, ms / 1000);
        return String.format(Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60);
    }
}
