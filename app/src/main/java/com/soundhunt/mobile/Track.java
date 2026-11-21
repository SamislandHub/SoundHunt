package com.soundhunt.mobile;

import org.json.JSONException;
import org.json.JSONObject;

public class Track {
    public static final String SOURCE_BILIBILI = "bilibili";
    public static final String SOURCE_AUDIUS = "audius";
    /** 已下线的 Apple 试听源，仅用于清理旧收藏里的历史条目。 */
    private static final String RETIRED_ITUNES = "itunes";

    public static boolean isRetiredSource(String source) {
        return RETIRED_ITUNES.equals(source);
    }

    public String id;
    public String title;
    public String artist;
    public String cover;
    public String source;
    public String sourceUrl;
    public String bvid;
    public String previewUrl;
    public long durationMs;
    public boolean previewOnly;
    /** 视频分 P 序号，0 表示整视频（未拆分的合集或单曲）。 */
    public int page;
    /** 已解析的分 P / 视频 cid，>0 时播放可跳过 view 请求直接取播放地址。 */
    public long cid;
    /** 拆分来源合集的标题；空表示单曲。播放页据此显示「选集」入口。 */
    public String parentTitle;

    // ---- 推荐用热度信号（来自 B站搜索接口，缺失时为 0）----
    public long playCount;      // 播放量
    public long danmakuCount;   // 弹幕数
    public long favoriteCount;  // 收藏数
    public long likeCount;      // 点赞数
    public long pubdate;        // 发布时间（秒）
    public String tag;          // 视频标签（空格分隔）
    /** 热度打分缓存，仅内存态，不序列化。 */
    public transient float recommendScore;

    /**
     * 合集判定：搜索接口本身就带时长，单首歌曲基本在 8 分钟以内，
     * 超过的大概率是「50 首连播」类合集或串烧，列表里需要单独标注。
     */
    public boolean isCollection() {
        return durationMs >= 8 * 60_000L;
    }

    public String stableId() {
        return source + ":" + id;
    }

    /** 保留 Audius 分支只为兼容早前收藏的条目，新搜索不会再产生该来源。 */
    public String sourceLabel() {
        if (SOURCE_BILIBILI.equals(source)) return "B站";
        if (SOURCE_AUDIUS.equals(source)) return "Audius";
        return "B站";
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("title", title);
        o.put("artist", artist);
        o.put("cover", cover);
        o.put("source", source);
        o.put("sourceUrl", sourceUrl);
        o.put("bvid", bvid);
        o.put("previewUrl", previewUrl);
        o.put("durationMs", durationMs);
        o.put("previewOnly", previewOnly);
        o.put("page", page);
        o.put("cid", cid);
        o.put("parentTitle", parentTitle == null ? "" : parentTitle);
        o.put("playCount", playCount);
        o.put("danmakuCount", danmakuCount);
        o.put("favoriteCount", favoriteCount);
        o.put("likeCount", likeCount);
        o.put("pubdate", pubdate);
        o.put("tag", tag == null ? "" : tag);
        return o;
    }

    public static Track fromJson(JSONObject o) {
        Track t = new Track();
        t.id = o.optString("id");
        t.title = o.optString("title");
        t.artist = o.optString("artist");
        t.cover = o.optString("cover");
        t.source = o.optString("source");
        t.sourceUrl = o.optString("sourceUrl");
        t.bvid = o.optString("bvid");
        t.previewUrl = o.optString("previewUrl");
        t.durationMs = o.optLong("durationMs");
        t.previewOnly = o.optBoolean("previewOnly");
        t.page = o.optInt("page");
        t.cid = o.optLong("cid");
        t.parentTitle = o.optString("parentTitle");
        t.playCount = o.optLong("playCount");
        t.danmakuCount = o.optLong("danmakuCount");
        t.favoriteCount = o.optLong("favoriteCount");
        t.likeCount = o.optLong("likeCount");
        t.pubdate = o.optLong("pubdate");
        t.tag = o.optString("tag");
        return t;
    }
}
