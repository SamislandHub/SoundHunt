package com.soundhunt.mobile;

import android.text.Html;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class BiliClient {
    private static final String TAG = "BiliClient";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36";
    private static final String REFERER = "https://www.bilibili.com/";
    private static final String COOKIE = "buvid3=8F2A4C1E-1234-4A5B-9C6D-7E8F9A0B1C2Dinfoc";

    // ---------------- 音质档位（dash audio id） ----------------
    public static final int QUALITY_64K = 30216;   // 标准 64K
    public static final int QUALITY_132K = 30232;  // 高清 132K（默认）
    public static final int QUALITY_FLAC = 30280;  // 无损 FLAC（通常需登录，游客多拿不到）

    /** 播放地址 + 实际命中的音质标签（目标档缺失时回退最高音质，标签随之变化）。 */
    public static class QualityResult {
        public final List<String> urls;
        public final String label;
        QualityResult(List<String> urls, String label) { this.urls = urls; this.label = label; }
    }

    private static JSONObject getJson(String url) throws Exception {
        return getJson(url, true);
    }

    /** lenient=false 时不校验业务 code（nav 未登录返回 -101 但 wbi_img 可用）。 */
    private static JSONObject getJson(String url, boolean strict) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Referer", REFERER);
        c.setRequestProperty("Origin", "https://www.bilibili.com");
        c.setRequestProperty("Cookie", COOKIE);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Accept-Encoding", "identity");
        int code = c.getResponseCode();
        String body = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
        c.disconnect();
        if (code < 200 || code >= 300) throw new IOException("HTTP " + code);
        JSONObject json = new JSONObject(body);
        if (strict && json.optInt("code", -1) != 0) {
            throw new IOException("B站接口：" + json.optString("message", "请求失败"));
        }
        return json;
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) >= 0) out.append(buf, 0, n);
        }
        return out.toString();
    }

    private static String clean(String value) {
        if (value == null) return "";
        return Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString().trim();
    }

    /** 营销尾巴关键词：出现在这些词之后的都是宣传话术，整段砍掉。 */
    private static final String TITLE_TAIL =
            "(?i)[\\s\\-—_|｜]*(歌词版|纯享|分集播放|持续更新|值得收藏|已打包|可下载|无损音质|Hi-Res|HiRes|高品质|顶级文案|录音棚|\\d+\\s*首)[\\s\\S]*$";

    /**
     * 轻量清洗 B站标题：去掉【】/[]/（）包裹的宣传语和营销尾巴，保留主体。
     * 只做确定安全的规则，不过度猜测；清得过短时回退原标题。
     * 真正的「歌单类」标题交由 expand() 展开成分P真名，这里不强行洗。
     */
    private static String cleanTitle(String value) {
        String raw = clean(value);
        String s = raw.replaceAll("【[^】]*】", " ").replaceAll("\\[[^\\]]*\\]", " ");
        s = s.replaceAll("（[^）]*）", " ");
        s = s.replaceAll(TITLE_TAIL, " ");
        s = s.replaceAll("\\s+", " ")
                .replaceAll("^[\\s\\-—_|｜,，、.。]+|[\\s\\-—_|｜,，、.。]+$", "").trim();
        return s.length() >= 2 ? s : raw;
    }

    private static long durationMs(String value) {
        if (value == null || value.isEmpty()) return 0;
        String[] parts = value.split(":");
        long seconds = 0;
        try {
            for (String p : parts) seconds = seconds * 60 + Long.parseLong(p);
        } catch (NumberFormatException ignored) {
            return 0;
        }
        return seconds * 1000;
    }

    /** 解析 B站计数：可能是数字，也可能是「10.3万」「1.2亿」字符串。 */
    private static long count(Object raw) {
        if (raw == null) return 0;
        if (raw instanceof Number) return ((Number) raw).longValue();
        String s = String.valueOf(raw).trim().replace(",", "");
        if (s.isEmpty()) return 0;
        try {
            double factor = 1;
            if (s.endsWith("万")) { factor = 10_000; s = s.substring(0, s.length() - 1); }
            else if (s.endsWith("亿")) { factor = 100_000_000; s = s.substring(0, s.length() - 1); }
            return (long) (Double.parseDouble(s) * factor);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String https(String value) {
        if (value == null) return "";
        if (value.startsWith("//")) return "https:" + value;
        return value.replace("http://", "https://");
    }

    // ---------------- wbi 签名（web 接口风控要求 w_rid + wts） ----------------

    /** mixin key 混淆表（B站前端固定表，非敏感数据）。 */
    private static final int[] MIXIN_TAB = {
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
            33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40, 61,
            26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36,
            20, 34, 44, 52
    };

    private static volatile String mixinKey;
    private static volatile long mixinKeyAt;

    /** 从 nav 接口取 img_key + sub_key，按混淆表拼成 54 位 mixin key；缓存 12 小时。 */
    private static String mixinKey() throws Exception {
        String cached = mixinKey;
        if (cached != null && System.currentTimeMillis() - mixinKeyAt < 12 * 3600_000L) return cached;
        synchronized (BiliClient.class) {
            if (mixinKey != null && System.currentTimeMillis() - mixinKeyAt < 12 * 3600_000L) {
                return mixinKey;
            }
            // 未登录 nav 返回 code=-101，但 data.wbi_img 仍有效，因此用宽松模式
            JSONObject data = getJson("https://api.bilibili.com/x/web-interface/nav", false)
                    .getJSONObject("data");
            JSONObject wbi = data.getJSONObject("wbi_img");
            String imgKey = wbi.optString("img_key", "");
            String subKey = wbi.optString("sub_key", "");
            if (imgKey.isEmpty()) imgKey = fileNameKey(wbi.optString("img_url", ""));
            if (subKey.isEmpty()) subKey = fileNameKey(wbi.optString("sub_url", ""));
            if (imgKey.isEmpty() || subKey.isEmpty()) throw new IOException("wbi key 获取失败");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 54; i++) {
                String both = imgKey + subKey;
                sb.append(both.charAt(MIXIN_TAB[i]));
            }
            mixinKey = sb.toString();
            mixinKeyAt = System.currentTimeMillis();
            Log.i(TAG, "wbi mixin key refreshed");
            return mixinKey;
        }
    }

    /** img_url / sub_url 形如 .../yyyymmdd/xxx.png → 取文件名去掉扩展名。 */
    private static String fileNameKey(String url) {
        if (url == null || url.isEmpty()) return "";
        String name = url.substring(url.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** 对参数做 wbi 签名，返回已编码的 query（含 w_rid 与 wts），可直接拼到接口路径后。 */
    private static String signWbi(Map<String, String> params) throws Exception {
        String key = mixinKey();
        TreeMap<String, String> sorted = new TreeMap<>(params);
        sorted.put("wts", String.valueOf(System.currentTimeMillis() / 1000L));
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (query.length() > 0) query.append('&');
            // B站前端会过滤值里的 !'()* 再编码
            String value = e.getValue().replaceAll("[!'()*]", "");
            query.append(urlEncode(e.getKey())).append('=').append(urlEncode(value));
        }
        String wrid = md5Hex(query.toString() + key);
        query.append("&w_rid=").append(wrid);
        return query.toString();
    }

    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 编码不可用", e);
        }
    }

    private static String md5Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("MD5 不可用", e);
        }
    }

    public List<Track> search(String query) throws Exception {
        return search(query, 1);
    }

    /** page 从 1 开始，供推荐流翻页续拉。 */
    public List<Track> search(String query, int page) throws Exception {
        Map<String, String> params = new TreeMap<>();
        params.put("search_type", "video");
        params.put("page", String.valueOf(Math.max(1, page)));
        params.put("page_size", "20");
        params.put("keyword", query);
        String url = "https://api.bilibili.com/x/web-interface/search/type?" + signWbi(params);
        JSONObject data = getJson(url).getJSONObject("data");
        JSONArray result = data.optJSONArray("result");
        List<Track> tracks = new ArrayList<>();
        if (result == null) return tracks;
        for (int i = 0; i < result.length(); i++) {
            JSONObject item = result.optJSONObject(i);
            if (item == null || !"video".equals(item.optString("type", "video"))) continue;
            String bvid = item.optString("bvid");
            if (bvid.isEmpty()) continue;
            Track t = new Track();
            t.id = bvid;
            t.bvid = bvid;
            t.title = cleanTitle(item.optString("title"));
            t.artist = clean(item.optString("author"));
            t.cover = https(item.optString("pic"));
            t.durationMs = durationMs(item.optString("duration"));
            t.source = Track.SOURCE_BILIBILI;
            t.sourceUrl = "https://www.bilibili.com/video/" + bvid;
            // 推荐热度信号：play 在新版接口多为纯数字，个别为「万」字符串，统一兜底
            t.playCount = count(item.opt("play"));
            t.danmakuCount = count(item.opt("review"));
            t.favoriteCount = count(item.opt("favorites"));
            t.likeCount = count(item.opt("like"));
            t.pubdate = item.optLong("pubdate");
            t.tag = clean(item.optString("tag"));
            tracks.add(t);
        }
        return tracks;
    }

    /** 音质 id → 展示标签。 */
    public static String qualityLabel(int id) {
        switch (id) {
            case QUALITY_64K: return "64K";
            case QUALITY_FLAC: return "无损 FLAC";
            default: return "高清 132K";
        }
    }

    /** 取播放地址：优先命中目标音质档；缺失时回退最高音质并在标签中体现。 */
    public QualityResult playCandidates(Track track, int targetQuality) throws Exception {
        // 已带 cid 的条目（合集拆出的单曲、历史收藏）跳过 view 请求，直接取播放地址
        long cid = track.cid;
        if (cid == 0) {
            JSONObject view = getJson("https://api.bilibili.com/x/web-interface/view?bvid=" + track.bvid)
                    .getJSONObject("data");
            cid = view.optLong("cid");
            if (cid == 0) {
                JSONArray pages = view.optJSONArray("pages");
                if (pages != null && pages.length() > 0) cid = pages.getJSONObject(0).optLong("cid");
            }
        }
        if (cid == 0) throw new IOException("无法读取视频分集");
        JSONObject play = getJson("https://api.bilibili.com/x/player/playurl?bvid=" + track.bvid
                + "&cid=" + cid + "&qn=0&fnver=0&fnval=16&fourk=1").getJSONObject("data");
        JSONArray audio = play.optJSONObject("dash") == null
                ? null : play.getJSONObject("dash").optJSONArray("audio");
        if (audio == null || audio.length() == 0) throw new IOException("该内容暂无公开音频流");

        List<JSONObject> sorted = new ArrayList<>();
        for (int i = 0; i < audio.length(); i++) sorted.add(audio.getJSONObject(i));
        sorted.sort(Comparator.comparingLong(o -> -o.optLong("bandwidth")));

        // 命中目标档则用它，否则用最高档并标注回退
        JSONObject chosen = null;
        boolean fallback = false;
        for (JSONObject s : sorted) {
            if (s.optInt("id", 0) == targetQuality) { chosen = s; break; }
        }
        if (chosen == null) { chosen = sorted.get(0); fallback = true; }

        String label = fallback
                ? qualityLabel(chosen.optInt("id", 0)) + "（当前曲目无 " + qualityLabel(targetQuality) + "）"
                : qualityLabel(chosen.optInt("id", 0));
        List<String> urls = new ArrayList<>();
        collectStream(urls, chosen);
        // 其余档位按带宽降序作为容错备选
        for (JSONObject s : sorted) {
            if (s != chosen) collectStream(urls, s);
        }
        if (urls.isEmpty()) throw new IOException("未获得可播放地址");
        return new QualityResult(urls, label);
    }

    private static void collectStream(List<String> urls, JSONObject stream) {
        addUrl(urls, stream.optString("baseUrl"));
        JSONArray backups = stream.optJSONArray("backupUrl");
        if (backups != null) {
            for (int i = 0; i < backups.length(); i++) addUrl(urls, backups.optString(i));
        }
    }

    private static void addUrl(List<String> urls, String url) {
        if (url == null || url.isEmpty() || urls.contains(url)) return;
        urls.add(url);
    }

    /** 单个分 P 时长上限：超过它说明这个 P 自己也是串烧，拆了没意义。 */
    private static final long PAGE_MAX_MS = 8 * 60_000L;
    /** 展开上限：B站合集最大 1024 P，但队列里塞太多没有意义。 */
    private static final int EXPAND_MAX = 120;

    /**
     * 尝试把合集展开为单曲列表。
     *
     * 只处理「真多 P 合集」——up 主把每首歌剪成一个分 P（如《2026抖音热歌100首》），
     * 每个 P 有独立 cid 与时长，拆出来就是标准的单曲队列，进度/切歌/收藏全部按单曲走。
     * 两类拆不了：单 P 串烧（歌烧在一条流里）、每个 P 本身也是几十分钟的串烧。
     *
     * @return 拆分结果；不可拆或请求失败时返回 null，调用方回退为整段播放合集
     */
    public List<Track> expand(Track track) throws Exception {
        if (track.bvid == null || track.bvid.isEmpty()) return null;
        JSONObject data = getJson("https://api.bilibili.com/x/web-interface/view?bvid=" + track.bvid)
                .getJSONObject("data");
        JSONArray pages = data.optJSONArray("pages");
        if (pages == null || pages.length() < 2) return null;

        // 前 3 个 P 里至少 2 个是歌曲长度，才值得拆
        int songLike = 0;
        int probe = Math.min(pages.length(), 3);
        for (int i = 0; i < probe; i++) {
            if (pages.getJSONObject(i).optLong("duration", 0) * 1000L <= PAGE_MAX_MS) songLike++;
        }
        if (songLike < Math.min(2, probe)) return null;

        // 分 P 歌名优先用 up 主兜底补歌手位
        JSONObject ownerObj = data.optJSONObject("owner");
        String upName = ownerObj == null ? clean(data.optString("author"))
                : clean(ownerObj.optString("name"));
        String cover = https(data.optString("pic"));
        String collectionTitle = clean(data.optString("title"));
        int count = Math.min(pages.length(), EXPAND_MAX);

        List<Track> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            JSONObject page = pages.getJSONObject(i);
            long durationMs = page.optLong("duration", 0) * 1000L;
            if (durationMs > PAGE_MAX_MS) continue;   // 该 P 自身是串烧，跳过
            String[] song = parsePart(page.optString("part"), upName, i + 1);
            Track t = new Track();
            t.title = song[0];
            t.artist = song[1];
            t.cover = cover;
            t.source = Track.SOURCE_BILIBILI;
            t.bvid = track.bvid;
            t.page = page.optInt("page", i + 1);
            t.cid = page.optLong("cid");
            t.durationMs = durationMs;
            t.parentTitle = collectionTitle;
            t.id = track.bvid + "?p=" + t.page;
            t.sourceUrl = "https://www.bilibili.com/video/" + track.bvid + "?p=" + t.page;
            out.add(t);
        }
        if (out.size() < 2) return null;
        return out;
    }

    /**
     * 分 P 标题一般是「001. 歌名 - 歌手」或「01 歌名」，清洗出歌名与歌手；
     * 没有歌手信息的用合集 up 主兜底，实在缺名字的标「第 n 首」。
     */
    private static String[] parsePart(String raw, String fallbackArtist, int page) {
        String value = raw == null ? "" : raw.trim();
        value = value.replaceAll("^[0-9]{1,3}\\s*[.、．\\-—)）]?\\s*", "");   // 去前导编号
        value = value.replaceAll("^[【\\[][^】\\]]*[】\\]]\\s*", "");          // 去开头的【纯享】类标记
        value = value.trim();
        String title = value;
        String artist = fallbackArtist;
        String[] split = value.split("\\s+[-—]\\s+");
        if (split.length >= 2) {
            title = split[0].trim();
            artist = split[split.length - 1].trim();
        }
        title = cleanTitle(title);
        if (title.isEmpty()) title = "第 " + page + " 首";
        if (artist.isEmpty()) artist = fallbackArtist;
        return new String[]{title, artist};
    }

    public static java.util.Map<String, String> audioHeaders() {
        java.util.Map<String, String> h = new java.util.HashMap<>();
        h.put("User-Agent", UA);
        h.put("Referer", REFERER);
        h.put("Origin", "https://www.bilibili.com");
        return h;
    }
}
