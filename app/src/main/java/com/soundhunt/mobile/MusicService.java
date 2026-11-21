package com.soundhunt.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.audiofx.Visualizer;
import android.net.Uri;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MusicService extends Service {
    public static final String ACTION_PLAY_PAUSE = "com.soundhunt.mobile.PLAY_PAUSE";
    public static final String ACTION_NEXT = "com.soundhunt.mobile.NEXT";
    public static final String ACTION_PREVIOUS = "com.soundhunt.mobile.PREVIOUS";
    private static final String CHANNEL = "playback";
    private static final int NOTIFICATION_ID = 41;
    private static final String TAG = "MusicBoxPlayer";

    private final IBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final BiliClient bili = new BiliClient();
    private AudioCache audioCache;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final List<Track> queue = new ArrayList<>();
    // 队列来源：区分首页推荐/搜索/收藏，供首页在重新进入时决定是否恢复推荐流
    public static final int QUEUE_FEED = 0;
    public static final int QUEUE_SEARCH = 1;
    public static final int QUEUE_FAV = 2;
    private int queueSource = QUEUE_FEED;
    private MediaPlayer player;
    // 频谱：Visualizer 绑定当前 MediaPlayer 的 audioSessionId（读会话输出，无需 RECORD_AUDIO 权限）
    private Visualizer visualizer;
    private volatile float bandBass, bandMid, bandHi;   // 低/中/高频能量 0..1，跨线程读
    private MediaSession mediaSession;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private Track current;
    private int currentIndex = -1;
    private int requestSerial = 0;
    private int candidateIndex = 0;
    private int autoFailures = 0;
    private boolean prepared;
    private boolean shouldPlay;
    private boolean repeat;
    private List<String> candidates = new ArrayList<>();
    // 音质：偏好档 + 当前曲目实际命中的档位标签 + 切音质续播位置
    private int qualityPref = BiliClient.QUALITY_132K;
    private volatile String qualityLabel = "高清 132K";
    private volatile int pendingSeekMs;
    private static final String PREFS = "player";
    private static final String PREF_QUALITY = "audio_quality";

    public class LocalBinder extends Binder {
        public MusicService getService() {
            return MusicService.this;
        }
    }

    public interface Listener {
        void onPlaybackChanged();
        void onError(String message);
    }

    @Override public void onCreate() {
        super.onCreate();
        audioCache = new AudioCache(this, bili);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "音乐播放", NotificationManager.IMPORTANCE_LOW));
        mediaSession = new MediaSession(this, "小点声");
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS | MediaSession.FLAG_HANDLES_MEDIA_BUTTONS);
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { resume(); }
            @Override public void onPause() { pause(); }
            @Override public void onSkipToNext() { next(); }
            @Override public void onSkipToPrevious() { previous(); }
            @Override public void onSeekTo(long pos) { seekTo((int) pos); }
        });
        mediaSession.setActive(true);
    }

    @Override public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case ACTION_PLAY_PAUSE: toggle(); break;
                case ACTION_NEXT: next(); break;
                case ACTION_PREVIOUS: previous(); break;
                default: break;
            }
        }
        return START_NOT_STICKY;
    }

    public void addListener(Listener listener) {
        if (!listeners.contains(listener)) listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public void playQueue(List<Track> tracks, int index) {
        playQueue(tracks, index, QUEUE_FEED);
    }

    /** 播放指定队列，并标记来源，便于首页判断回到首页时是否恢复推荐流。 */
    public void playQueue(List<Track> tracks, int index, int source) {
        queue.clear();
        queue.addAll(tracks);
        queueSource = source;
        autoFailures = 0;
        playAt(index);
    }

    public int getQueueSource() {
        return queueSource;
    }

    /** 无限下拉：把新推荐的曲目追加到队尾，不改变当前播放。按 stableId 去重。 */
    public void appendQueue(List<Track> tracks) {
        if (tracks == null || tracks.isEmpty()) return;
        Set<String> existing = new java.util.HashSet<>();
        for (Track t : queue) existing.add(t.stableId());
        for (Track t : tracks) {
            if (existing.add(t.stableId())) queue.add(t);
        }
        // 队列延长后补一次预取，让无限流即将到来的下一首也提前缓存
        prefetchNeighbors();
    }

    /** 切换音质：记偏好，重取当前曲目的播放地址并续播到原进度。 */
    public void switchQuality(int id) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(PREF_QUALITY, id).apply();
        qualityPref = id;
        if (current == null) return;
        pendingSeekMs = prepared && player != null ? Math.max(0, player.getCurrentPosition()) : 0;
        playAt(currentIndex);
    }

    /** 当前曲目实际命中的音质标签（如「132K」或「无损 FLAC」）。 */
    public String getQualityLabel() { return qualityLabel; }

    /** 当前音质偏好档 id（菜单勾选用）。 */
    public int getQualityPref() { return qualityPref; }

    public void playAt(int index) {
        if (index < 0 || index >= queue.size()) return;
        currentIndex = index;
        current = queue.get(index);
        prepared = false;
        shouldPlay = true;
        candidateIndex = 0;
        candidates = new ArrayList<>();
        int serial = ++requestSerial;
        releasePlayer();
        notifyChanged();
        updateMetadata();
        updatePlaybackState(PlaybackState.STATE_BUFFERING);
        startForeground(NOTIFICATION_ID, buildNotification());
        executor.execute(() -> {
            try {
                qualityPref = getSharedPreferences(PREFS, MODE_PRIVATE)
                        .getInt(PREF_QUALITY, BiliClient.QUALITY_132K);
                BiliClient.QualityResult qr;
                // 命中本地缓存：直接用文件地址，秒开且免流量
                java.io.File cached = Track.SOURCE_BILIBILI.equals(current.source)
                        ? audioCache.get(current, qualityPref) : null;
                if (cached != null) {
                    List<String> local = new ArrayList<>();
                    local.add("file://" + cached.getAbsolutePath());
                    qr = new BiliClient.QualityResult(local, "本地缓存");
                } else {
                    qr = Track.SOURCE_BILIBILI.equals(current.source)
                            ? bili.playCandidates(current, qualityPref)
                            : new BiliClient.QualityResult(resolveFreeStream(current.previewUrl), "在线直链");
                }
                final String label = qr.label;
                main.post(() -> {
                    if (serial != requestSerial) return;
                    qualityLabel = label;
                    candidates = qr.urls;
                    candidateIndex = 0;
                    startCandidate(serial);
                    // 当前曲目起播后，后台预取上一首/下一首
                    prefetchNeighbors();
                });
            } catch (Exception e) {
                main.post(() -> {
                    if (serial == requestSerial) handleFailure(errorText(e));
                });
            }
        });
    }

    /** 后台预取队里上一首和下一首，为切歌做准备。 */
    private void prefetchNeighbors() {
        if (audioCache == null || queue.size() < 2) return;
        int q = qualityPref;
        if (currentIndex > 0) audioCache.prefetch(queue.get(currentIndex - 1), q);
        if (currentIndex < queue.size() - 1) audioCache.prefetch(queue.get(currentIndex + 1), q);
    }

    /**
     * 历史收藏里可能还留着非 B站 来源的条目（如早期的 Audius），其 stream 端点会
     * 302 到内容节点（带时效签名）。在后台线程先解析出最终直链再交给 MediaPlayer，
     * 避免部分机型对跨主机重定向处理不稳定；解析失败时回退到原始端点作为第二个候选。
     */
    private List<String> resolveFreeStream(String streamUrl) throws Exception {
        List<String> out = new ArrayList<>();
        if (streamUrl == null || streamUrl.isEmpty()) throw new IOException("无可播放地址");
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(streamUrl).openConnection();
            c.setInstanceFollowRedirects(true);
            c.setConnectTimeout(12000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", "SoundHunt/1.5 Android");
            c.setRequestProperty("Accept", "*/*");
            c.setRequestProperty("Range", "bytes=0-0");
            int code = c.getResponseCode();
            String direct = c.getURL().toString();
            c.disconnect();
            if (code < 400 && !direct.isEmpty() && !direct.equals(streamUrl)) out.add(direct);
        } catch (Exception ignored) {
            // 解析失败：回退原始端点
        }
        out.add(streamUrl);
        return out;
    }

    private void startCandidate(int serial) {
        if (serial != requestSerial) return;
        if (candidateIndex >= candidates.size()) {
            handleFailure("所有播放线路均不可用");
            return;
        }
        releasePlayer();
        player = new MediaPlayer();
        player.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setUsage(AudioAttributes.USAGE_MEDIA).build());
        player.setOnPreparedListener(mp -> {
            if (serial != requestSerial) return;
            prepared = true;
            autoFailures = 0;
            Log.i(TAG, "Prepared: " + current.title + ", duration=" + mp.getDuration());
            attachVisualizer(mp.getAudioSessionId());
            if (shouldPlay && requestAudioFocus()) mp.start();
            // 切音质/重连后的续播：跳回原进度
            if (pendingSeekMs > 0) {
                int target = Math.min(pendingSeekMs, Math.max(0, mp.getDuration() - 800));
                if (target > 0) mp.seekTo(target);
                pendingSeekMs = 0;
            }
            updatePlaybackState(mp.isPlaying() ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED);
            updateMetadata();
            notifyChanged();
        });
        player.setOnCompletionListener(mp -> next());
        player.setOnErrorListener((mp, what, extra) -> {
            Log.w(TAG, "MediaPlayer error what=" + what + " extra=" + extra + ", candidate=" + candidateIndex);
            candidateIndex++;
            startCandidate(serial);
            return true;
        });
        try {
            String source = candidates.get(candidateIndex);
            if (source.startsWith("file://")) {
                player.setDataSource(this, Uri.parse(source));
            } else {
                Map<String, String> headers = Track.SOURCE_BILIBILI.equals(current.source)
                        ? BiliClient.audioHeaders() : Collections.emptyMap();
                player.setDataSource(this, Uri.parse(source), headers);
            }
            player.prepareAsync();
        } catch (Exception e) {
            candidateIndex++;
            startCandidate(serial);
        }
    }

    private void handleFailure(String message) {
        autoFailures++;
        Log.e(TAG, message);
        notifyError(current == null ? message : "《" + current.title + "》" + message);
        if (!queue.isEmpty() && autoFailures < Math.min(queue.size(), 4)) {
            playAt((currentIndex + 1) % queue.size());
        } else {
            shouldPlay = false;
            prepared = false;
            updatePlaybackState(PlaybackState.STATE_ERROR);
            notifyChanged();
        }
    }

    private String errorText(Exception e) {
        String message = e.getMessage();
        return message == null || message.isEmpty() ? "播放地址获取失败" : message;
    }

    public void toggle() {
        if (current == null) {
            if (!queue.isEmpty()) playAt(Math.max(0, currentIndex));
            return;
        }
        if (isPlaying()) pause(); else resume();
    }

    public void pause() {
        shouldPlay = false;
        if (player != null && prepared && player.isPlaying()) player.pause();
        bandBass = bandMid = bandHi = 0f;
        abandonAudioFocus();
        updatePlaybackState(PlaybackState.STATE_PAUSED);
        notifyChanged();
    }

    public void resume() {
        if (current == null) return;
        shouldPlay = true;
        if (player != null && prepared) {
            if (requestAudioFocus()) player.start();
        } else if (player == null) {
            playAt(Math.max(0, currentIndex));
        }
        updatePlaybackState(isPlaying() ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_BUFFERING);
        notifyChanged();
    }

    public void next() {
        if (queue.isEmpty()) return;
        int next = currentIndex + 1;
        if (next >= queue.size()) {
            if (!repeat) {
                pause();
                return;
            }
            next = 0;
        }
        playAt(next);
    }

    public void previous() {
        if (queue.isEmpty()) return;
        if (getPosition() > 5000 && player != null) {
            seekTo(0);
            return;
        }
        playAt((currentIndex - 1 + queue.size()) % queue.size());
    }

    public void seekTo(int position) {
        if (player != null && prepared) player.seekTo(position);
        updatePlaybackState(isPlaying() ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED);
    }

    public boolean isPlaying() {
        return player != null && prepared && player.isPlaying();
    }

    // ===== 频谱（供播放页律动动画读取）=====

    /** 绑定当前播放器会话，采集 FFT 并折算成低/中/高三段能量。失败时静默降级。 */
    private void attachVisualizer(int sessionId) {
        releaseVisualizer();
        if (sessionId <= 0) return;
        try {
            visualizer = new Visualizer(sessionId);
            int[] range = visualizer.getCaptureSizeRange();
            int size = range != null && range.length > 1 ? range[1] : 1024; // 取最大档，频率分辨率最高
            visualizer.setCaptureSize(size);
            visualizer.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override public void onWaveFormDataCapture(Visualizer v, byte[] waveform, int rate) { }
                @Override public void onFftDataCapture(Visualizer v, byte[] fft, int rate) {
                    computeBands(fft);
                }
            }, Math.min(Visualizer.getMaxCaptureRate(), 20000), false, true);
            visualizer.setEnabled(true);
        } catch (Exception e) {
            Log.w(TAG, "Visualizer unavailable: " + e.getMessage());
            releaseVisualizer();
        }
    }

    /**
     * FFT 字节流 → 三段能量（0..1）。fft 为交织的实部/虚部：
     * index 0=DC, 1=Nyquist, 2i/2i+1=第 i 频点。幅值取模后按段求均值并归一化。
     */
    private void computeBands(byte[] fft) {
        if (fft == null || fft.length < 4) return;
        int bins = fft.length / 2;              // 有效频点数
        // 采样率近似 44100Hz（Visualizer 输出固定），每 bin 覆盖 ~43Hz；按能量段取整
        int bassEnd = Math.max(2, (int) (180f / 22050f * bins));
        int midEnd = Math.max(bassEnd + 1, (int) (2000f / 22050f * bins));
        int hiEnd = Math.min(bins, Math.max(midEnd + 1, (int) (7000f / 22050f * bins)));
        bandBass = norm(fft, 1, bassEnd, 1.9f);
        bandMid = norm(fft, bassEnd, midEnd, 2.4f);
        bandHi = norm(fft, midEnd, hiEnd, 3.2f);
    }

    /** 某段频点的平均幅值（0..1），带增益与限幅。 */
    private float norm(byte[] fft, int from, int to, float gain) {
        if (to <= from) return 0f;
        float sum = 0f;
        int count = 0;
        for (int i = from; i < to && i < fft.length / 2; i++) {
            float re = fft[i * 2];
            float im = fft[i * 2 + 1];
            float mag = (float) Math.sqrt(re * re + im * im) / 128f; // 0..1
            sum += mag;
            count++;
        }
        if (count == 0) return 0f;
        return Math.min(1f, sum / count * gain);
    }

    private void releaseVisualizer() {
        if (visualizer != null) {
            try { visualizer.setEnabled(false); } catch (Exception ignored) { }
            try { visualizer.release(); } catch (Exception ignored) { }
            visualizer = null;
        }
    }

    /** 低/中/高频能量（0..1），播放页据此驱动光河。未取到频谱时返回 0。 */
    public float getBass() { return isPlaying() ? bandBass : 0f; }
    public float getMid() { return isPlaying() ? bandMid : 0f; }
    public float getHi() { return isPlaying() ? bandHi : 0f; }
    /** 频谱是否真的在工作（能量非零），用于动画决定用真律动还是回退节拍模拟。 */
    public boolean hasSpectrum() { return bandBass + bandMid + bandHi > 0.01f; }

    public boolean isBuffering() {
        return current != null && shouldPlay && !prepared;
    }

    public int getPosition() {
        return player != null && prepared ? player.getCurrentPosition() : 0;
    }

    public int getDuration() {
        return player != null && prepared ? player.getDuration() : (int) (current == null ? 0 : current.durationMs);
    }

    public Track getCurrent() {
        return current;
    }

    /** 当前队列快照（拷贝），供播放页合集选集面板展示。 */
    public List<Track> getQueue() {
        return new ArrayList<>(queue);
    }

    public int getCurrentIndex() {
        return currentIndex;
    }

    public int getQueueSize() {
        return queue.size();
    }

    public boolean isRepeat() {
        return repeat;
    }

    public void setRepeat(boolean value) {
        repeat = value;
        notifyChanged();
    }

    private boolean requestAudioFocus() {
        if (focusRequest == null) {
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setOnAudioFocusChangeListener(focus -> {
                        if (focus == AudioManager.AUDIOFOCUS_LOSS || focus == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause();
                    }).build();
        }
        return audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonAudioFocus() {
        if (focusRequest != null) audioManager.abandonAudioFocusRequest(focusRequest);
    }

    private void updateMetadata() {
        if (current == null) return;
        MediaMetadata.Builder metadata = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, current.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, current.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, current.sourceLabel())
                .putLong(MediaMetadata.METADATA_KEY_DURATION, getDuration());
        mediaSession.setMetadata(metadata.build());
        CoverCache.get().loadAsync(current.cover, bitmap -> {
            if (current == null) return;
            MediaMetadata updated = new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, current.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, current.artist)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, current.sourceLabel())
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, getDuration())
                    .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bitmap).build();
            mediaSession.setMetadata(updated);
            updateNotification();
        });
    }

    private void updatePlaybackState(int state) {
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO;
        mediaSession.setPlaybackState(new PlaybackState.Builder().setActions(actions)
                .setState(state, getPosition(), 1f).build());
        updateNotification();
    }

    private void updateNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIFICATION_ID, buildNotification());
    }

    private Notification buildNotification() {
        Intent content = new Intent(this, MainActivity.class);
        content.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, content,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setColor(Theme.ACCENT)
                .setContentTitle(current == null ? "小点声" : current.title)
                .setContentText(current == null ? "挑一首开始听" : current.artist + " · " + current.sourceLabel())
                .setContentIntent(contentIntent)
                .setOnlyAlertOnce(true)
                .setOngoing(isPlaying() || isBuffering())
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setStyle(new Notification.MediaStyle().setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));
        builder.addAction(new Notification.Action.Builder(android.R.drawable.ic_media_previous, "上一首",
                serviceIntent(ACTION_PREVIOUS, 1)).build());
        builder.addAction(new Notification.Action.Builder(isPlaying() ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                isPlaying() ? "暂停" : "播放", serviceIntent(ACTION_PLAY_PAUSE, 2)).build());
        builder.addAction(new Notification.Action.Builder(android.R.drawable.ic_media_next, "下一首",
                serviceIntent(ACTION_NEXT, 3)).build());
        return builder.build();
    }

    private PendingIntent serviceIntent(String action, int request) {
        Intent intent = new Intent(this, MusicService.class).setAction(action);
        return PendingIntent.getService(this, request, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void notifyChanged() {
        for (Listener listener : listeners) listener.onPlaybackChanged();
    }

    private void notifyError(String message) {
        for (Listener listener : listeners) listener.onError(message);
    }

    private void releasePlayer() {
        prepared = false;
        bandBass = bandMid = bandHi = 0f;
        releaseVisualizer();
        if (player != null) {
            try { player.reset(); } catch (Exception ignored) { }
            player.release();
            player = null;
        }
    }

    @Override public void onDestroy() {
        requestSerial++;
        releasePlayer();
        abandonAudioFocus();
        executor.shutdownNow();
        if (audioCache != null) audioCache.shutdown();
        if (mediaSession != null) mediaSession.release();
        super.onDestroy();
    }
}
