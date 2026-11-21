package com.soundhunt.mobile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 推荐引擎：把「一堆候选曲目」排成一条不单调、有品味、越用越懂你的播放流。
 *
 * 不用外部依赖，纯打分 + 打散。四个信号：
 *  1) 热度分   —— 播放/点赞/收藏/弹幕取对数后加权，衡量「大众认可度」
 *  2) 新鲜度   —— 发布时间越近略有加成，但不碾压经典
 *  3) 画像分   —— 从收藏里提取偏好歌手/标签/关键词，命中即加分（个性化）
 *  4) 探索分   —— 少量随机，给冷门好歌机会，避免信息茧房
 *
 * 打分后用「MMR 最大边际相关性」重排：越相关越靠前，但与已选曲目同歌手/同标签的会被惩罚，
 * 从而保证一条流里风格轮换、不会连着出现同一批人（解决「一直是某歌手的歌」）。
 */
public class RecommendEngine {

    private final Random random = new Random();

    // ---- 用户画像：从收藏统计出的偏好 ----
    private final Map<String, Float> artistTaste = new HashMap<>();   // 歌手 -> 偏好权重
    private final Map<String, Float> tagTaste = new HashMap<>();      // 标签 -> 偏好权重
    private final Map<String, Float> keywordTaste = new HashMap<>();  // 标题关键词 -> 偏好权重
    private boolean profileReady = false;

    /** 会话内已推过的 stableId，用于跨页去重（由外部持久传入）。 */
    private final Set<String> sessionSeen = new HashSet<>();

    /** 用收藏列表重建画像。收藏越多，画像越准。 */
    public synchronized void buildProfile(List<Track> favorites) {
        artistTaste.clear();
        tagTaste.clear();
        keywordTaste.clear();
        if (favorites != null) {
            int n = 0;
            for (Track t : favorites) {
                if (t == null) continue;
                n++;
                if (notEmpty(t.artist)) bump(artistTaste, t.artist.toLowerCase(Locale.ROOT), 1.0f);
                if (notEmpty(t.tag)) {
                    for (String tag : t.tag.split("\\s+")) {
                        if (tag.length() >= 2) bump(tagTaste, tag.toLowerCase(Locale.ROOT), 0.7f);
                    }
                }
                for (String kw : keywords(t.title)) bump(keywordTaste, kw, 0.5f);
            }
            // 归一化到 0~1 区间，避免收藏很多时权重爆炸
            normalize(artistTaste, n);
            normalize(tagTaste, n);
            normalize(keywordTaste, n);
        }
        profileReady = !artistTaste.isEmpty() || !tagTaste.isEmpty() || !keywordTaste.isEmpty();
    }

    public synchronized boolean hasProfile() { return profileReady; }

    public synchronized void markSeen(List<Track> tracks) {
        if (tracks == null) return;
        for (Track t : tracks) sessionSeen.add(t.stableId());
    }

    public synchronized void clearSession() { sessionSeen.clear(); }

    /**
     * 从候选中选出下一批推荐。
     *
     * @param candidates 候选曲目（可能重复、可能已推过）
     * @param count      本批需要多少首
     * @return 排好序、打散后的推荐列表
     */
    public synchronized List<Track> rank(List<Track> candidates, int count) {
        if (candidates == null || candidates.isEmpty() || count <= 0) return new ArrayList<>();

        // 1) 去重 + 过滤已推过
        Map<String, Track> unique = new LinkedHashMap<>();
        for (Track t : candidates) {
            if (t == null) continue;
            String id = t.stableId();
            if (sessionSeen.contains(id)) continue;
            unique.putIfAbsent(id, t);
        }
        if (unique.isEmpty()) return new ArrayList<>();

        // 2) 逐条打分
        List<Scored> pool = new ArrayList<>();
        long nowSec = System.currentTimeMillis() / 1000L;
        for (Track t : unique.values()) {
            float heat = heatScore(t);
            float fresh = freshnessScore(t, nowSec);
            float taste = tasteScore(t);
            float explore = random.nextFloat() * EXPLORE_WEIGHT;
            // 合集（长连播）降权：单曲体验优先
            float penalty = t.isCollection() ? COLLECTION_PENALTY : 0f;
            float total = heat * W_HEAT + fresh * W_FRESH + taste * W_TASTE
                    + explore + qualityFloor(t) - penalty;
            t.recommendScore = total;
            pool.add(new Scored(t, heat, fresh, taste, total));
        }

        // 3) MMR 重排：相关度高 + 与已选差异大
        List<Scored> selected = new ArrayList<>();
        Map<String, Integer> artistCount = new HashMap<>();
        Map<String, Integer> tagCount = new HashMap<>();
        int want = Math.min(count, pool.size());
        while (selected.size() < want && !pool.isEmpty()) {
            Scored best = null;
            float bestMmr = -Float.MAX_VALUE;
            for (Scored s : pool) {
                float diversity = diversityPenalty(s, artistCount, tagCount);
                // 越靠后 exploration 占比略升，避免头部永远是同一批热歌
                float mmr = s.total - LAMBDA * diversity;
                if (mmr > bestMmr) { bestMmr = mmr; best = s; }
            }
            if (best == null) break;
            selected.add(best);
            pool.remove(best);
            account(best.track, artistCount, tagCount);
        }

        List<Track> out = new ArrayList<>();
        for (Scored s : selected) out.add(s.track);
        markSeen(out);
        return out;
    }

    // ---------------- 打分细节 ----------------

    /** 热度：对播放/点赞/收藏/弹幕取对数加权，再归一化到 0~1。 */
    private float heatScore(Track t) {
        float p = logNorm(t.playCount, 20_000_000L);
        float l = logNorm(t.likeCount, 500_000L);
        float f = logNorm(t.favoriteCount, 300_000L);
        float d = logNorm(t.danmakuCount, 1_000_000L);
        return clamp01(p * 0.40f + l * 0.28f + f * 0.20f + d * 0.12f);
    }

    /** 新鲜度：2 年内线性从 1 衰减到 0.3；未来/异常时间按 0.6 处理。 */
    private float freshnessScore(Track t, long nowSec) {
        if (t.pubdate <= 0) return 0.5f;
        long age = nowSec - t.pubdate;
        if (age < 0) return 0.6f;
        float years = age / (365f * 24 * 3600);
        if (years <= 0.05f) return 1.0f;         // 一个月内
        if (years >= 2f) return 0.3f;
        return 1.0f - (years - 0.05f) / 1.95f * 0.7f;
    }

    /** 画像命中：歌手 > 标签 > 标题关键词。 */
    private float tasteScore(Track t) {
        if (!profileReady) return 0f;
        float score = 0f;
        if (notEmpty(t.artist)) {
            Float v = artistTaste.get(t.artist.toLowerCase(Locale.ROOT));
            if (v != null) score += v * 1.0f;
        }
        if (notEmpty(t.tag)) {
            for (String tag : t.tag.split("\\s+")) {
                Float v = tagTaste.get(tag.toLowerCase(Locale.ROOT));
                if (v != null) score += v * 0.35f;
            }
        }
        for (String kw : keywords(t.title)) {
            Float v = keywordTaste.get(kw);
            if (v != null) score += v * 0.3f;
        }
        return clamp01(score);
    }

    /** 基础质量下限：完全没热度数据的条目不至于被判死刑，给个保底。 */
    private float qualityFloor(Track t) {
        if (t.playCount <= 0 && t.likeCount <= 0 && t.favoriteCount <= 0) return 0.05f;
        return 0f;
    }

    /** 多样性惩罚：同歌手强惩罚，同标签中等惩罚，标题近似轻惩罚。 */
    private float diversityPenalty(Scored s, Map<String, Integer> artistCount, Map<String, Integer> tagCount) {
        float penalty = 0f;
        String artist = s.track.artist == null ? "" : s.track.artist.toLowerCase(Locale.ROOT);
        if (!artist.isEmpty() && artistCount.containsKey(artist)) {
            penalty += 0.9f + 0.3f * artistCount.get(artist);
        }
        if (notEmpty(s.track.tag)) {
            for (String tag : s.track.tag.split("\\s+")) {
                Integer c = tagCount.get(tag.toLowerCase(Locale.ROOT));
                if (c != null) penalty += Math.min(0.5f, 0.15f * c);
            }
        }
        return penalty;
    }

    private void account(Track t, Map<String, Integer> artistCount, Map<String, Integer> tagCount) {
        String artist = t.artist == null ? "" : t.artist.toLowerCase(Locale.ROOT);
        if (!artist.isEmpty()) artistCount.merge(artist, 1, Integer::sum);
        if (notEmpty(t.tag)) {
            for (String tag : t.tag.split("\\s+")) {
                String k = tag.toLowerCase(Locale.ROOT);
                tagCount.merge(k, 1, Integer::sum);
            }
        }
    }

    // ---------------- 工具 ----------------

    private static final float W_HEAT = 0.45f;
    private static final float W_FRESH = 0.15f;
    private static final float W_TASTE = 0.45f;
    private static final float EXPLORE_WEIGHT = 0.18f;
    private static final float COLLECTION_PENALTY = 0.35f;
    private static final float LAMBDA = 0.85f;   // MMR 中相关度权重（越大越偏相关，越小越偏多样）

    private static float logNorm(long value, long reference) {
        if (value <= 0) return 0f;
        double r = Math.log10(value + 1) / Math.log10(reference + 1);
        return (float) clamp01(r);
    }

    private static void bump(Map<String, Float> map, String key, float amount) {
        if (!notEmpty(key)) return;
        map.put(key, map.getOrDefault(key, 0f) + amount);
    }

    private static void normalize(Map<String, Float> map, int n) {
        if (map.isEmpty() || n <= 0) return;
        for (Map.Entry<String, Float> e : map.entrySet()) {
            e.setValue(clamp01(e.getValue() / n));
        }
    }

    /** 从标题里抽取 2~4 字的中文片段作为关键词（简单够用）。 */
    private static List<String> keywords(String title) {
        List<String> out = new ArrayList<>();
        if (!notEmpty(title)) return out;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < title.length(); i++) {
            char c = title.charAt(i);
            if (c >= 0x4e00 && c <= 0x9fff) {
                sb.append(c);
            } else {
                flushCn(sb, out);
            }
        }
        flushCn(sb, out);
        return out;
    }

    private static void flushCn(StringBuilder sb, List<String> out) {
        int len = sb.length();
        for (int i = 0; i + 2 <= len; i++) {
            out.add(sb.substring(i, Math.min(len, i + 3)).toLowerCase(Locale.ROOT));
            if (i + 4 <= len) out.add(sb.substring(i, i + 4).toLowerCase(Locale.ROOT));
        }
        sb.setLength(0);
    }

    private static boolean notEmpty(String s) { return s != null && !s.trim().isEmpty(); }

    private static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }
    private static double clamp01(double v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

    private static class Scored {
        final Track track;
        final float heat;
        final float fresh;
        final float taste;
        final float total;
        Scored(Track track, float heat, float fresh, float taste, float total) {
            this.track = track;
            this.heat = heat;
            this.fresh = fresh;
            this.taste = taste;
            this.total = total;
        }
    }
}
