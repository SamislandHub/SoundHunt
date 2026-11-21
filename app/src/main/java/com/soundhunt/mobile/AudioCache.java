package com.soundhunt.mobile;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 音频本地缓存：播放当前曲目时，后台预下载上一首/下一首到 App 私有目录，
 * 切歌命中本地文件即可秒开、免流量、弱网可播；未命中回退在线流。
 *
 * 缓存键 = stableId + 音质档，音质切换后旧文件自动失效。
 * 容量上限 + LRU 淘汰，避免无限占用存储。
 */
public class AudioCache {
    private static final String TAG = "AudioCache";
    private static final long MAX_BYTES = 250L * 1024 * 1024;   // 总上限 250MB
    private static final int MAX_FILES = 40;                    // 最多 40 首
    private static final int CONNECT_TIMEOUT = 12000;
    private static final int READ_TIMEOUT = 20000;

    private final File dir;
    private final BiliClient bili;
    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    public AudioCache(Context context, BiliClient bili) {
        this.bili = bili;
        this.dir = new File(context.getCacheDir(), "audio-cache");
        if (!dir.exists()) dir.mkdirs();
    }

    /** 取本地缓存文件（存在且非空）。 */
    public synchronized File get(Track t, int quality) {
        if (t == null) return null;
        File f = new File(dir, key(t, quality) + ".m4a");
        return (f.exists() && f.length() > 0) ? f : null;
    }

    /** 是否已经在缓存或正在下载。 */
    public synchronized boolean has(Track t, int quality) {
        if (t == null) return true;
        String key = key(t, quality);
        return new File(dir, key + ".m4a").exists() || new File(dir, key + ".part").exists();
    }

    /**
     * 后台预取单曲（仅 B站源）。已缓存/下载中/非B站源会跳过。
     */
    public void prefetch(final Track t, final int quality) {
        if (t == null || !Track.SOURCE_BILIBILI.equals(t.source)) return;
        if (has(t, quality)) return;
        final String key = key(t, quality);
        pool.execute(() -> download(t, quality, key));
    }

    private void download(Track t, int quality, String key) {
        final File part = new File(dir, key + ".part");
        final File done = new File(dir, key + ".m4a");
        HttpURLConnection conn = null;
        try {
            BiliClient.QualityResult qr = bili.playCandidates(t, quality);
            if (qr.urls.isEmpty()) return;
            // 试各个线路直到下载成功
            for (String url : qr.urls) {
                try {
                    conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setConnectTimeout(CONNECT_TIMEOUT);
                    conn.setReadTimeout(READ_TIMEOUT);
                    for (Map.Entry<String, String> e : BiliClient.audioHeaders().entrySet()) {
                        conn.setRequestProperty(e.getKey(), e.getValue());
                    }
                    int code = conn.getResponseCode();
                    if (code < 200 || code >= 300) { conn.disconnect(); conn = null; continue; }
                    try (InputStream in = conn.getInputStream();
                         OutputStream out = new FileOutputStream(part)) {
                        byte[] buf = new byte[16 * 1024];
                        int n;
                        long total = 0;
                        while ((n = in.read(buf)) != -1) {
                            out.write(buf, 0, n);
                            total += n;
                            if (total > MAX_BYTES) throw new java.io.IOException("文件过大");
                        }
                        out.flush();
                    }
                    if (part.length() <= 0) { conn.disconnect(); conn = null; continue; }
                    if (done.exists()) done.delete();
                    if (!part.renameTo(done)) throw new java.io.IOException("重命名失败");
                    Log.i(TAG, "cached " + t.title + " (" + done.length() + " bytes)");
                    conn.disconnect();
                    trim();
                    return;
                } catch (Exception e) {
                    if (conn != null) { try { conn.disconnect(); } catch (Exception ignored) {} conn = null; }
                    if (part.exists()) part.delete();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "prefetch resolve failed: " + t.title + " " + e.getMessage());
            if (part.exists()) part.delete();
        }
    }

    /** 存储超限或文件数超限时按最旧淘汰。 */
    private synchronized void trim() {
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) return;
        List<File> list = new ArrayList<>();
        for (File f : files) if (f.getName().endsWith(".m4a")) list.add(f);
        list.sort(Comparator.comparingLong(File::lastModified));

        long total = 0;
        for (File f : list) total += f.length();
        int i = 0;
        while (i < list.size() && (total > MAX_BYTES || list.size() - i > MAX_FILES)) {
            File victim = list.get(i++);
            long len = victim.length();
            if (victim.delete()) total -= len;
        }
    }

    /** 音质切换等场景主动清空，避免旧档位文件残留。 */
    public synchronized void clear() {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String n = f.getName();
            if (n.endsWith(".m4a") || n.endsWith(".part")) f.delete();
        }
    }

    public synchronized void shutdown() { pool.shutdownNow(); }

    private static String key(Track t, int quality) {
        String raw = (t.source == null ? "" : t.source) + ":" + t.id;
        return Integer.toHexString(raw.hashCode()) + "_" + quality;
    }
}
