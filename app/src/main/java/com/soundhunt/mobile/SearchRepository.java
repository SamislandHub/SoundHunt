package com.soundhunt.mobile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 音源：只保留 B站。
 *
 * B站是当前唯一「无需登录、无需签名、音频流可直连」的中文曲库，
 * search/type → view → player/playurl 三段公开接口即可拿到 dash 音轨。
 *
 * 已下线的源：
 * - Apple iTunes 30 秒试听（只给片段，体验不完整）
 * - Audius（公开 API 可用，但中文曲库几乎为空，与中文听歌场景不匹配）
 * - 抖音（web 端 2483 要求登录、移动端空列表、音乐接口 403 风控）
 *
 * 抖音热歌的内容改由 B站关键词覆盖，首页热搜词首位即是入口。
 */
public class SearchRepository {
    public static class Result {
        public final List<Track> tracks = new ArrayList<>();
        public final List<String> errors = new ArrayList<>();
    }

    /**
     * 推荐词池：覆盖不同曲风/场景/年代，轮转取样，避免总是同一批歌手。
     * 每页只并发其中 QUERIES_PER_PAGE 个，页与页之间主题不同。
     */
    private static final String[] TRENDING_QUERIES = {
            "抖音热歌 歌曲", "华语流行 歌曲", "经典老歌 金曲", "网络热歌 新歌",
            "古风 歌曲", "说唱 rap 歌曲", "摇滚 歌曲", "民谣 歌曲",
            "轻音乐 纯音乐", "粤语歌 经典", "英文歌 热门", "影视 OST 主题曲",
            "电子音乐 EDM", "周杰伦 歌曲", "热门 BGM 背景音乐", "治愈 歌曲"
    };

    private static final int TRENDING_SIZE = 12;      // 每批产出数量
    private static final int QUERIES_PER_PAGE = 3;    // 每页并发主题数
    private static final int PAGE_SIZE = 20;

    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final BiliClient bili = new BiliClient();
    private final RecommendEngine engine = new RecommendEngine();

    // 主题轮转游标：每页从词池取不同的一组，打乱后轮流上岗
    private int queryCursor = 0;
    private int pagePerQuery = 1;

    /** 关键词搜索：只走 B站，按 stableId 去重。 */
    public Result search(String query) {
        Result result = new Result();
        try {
            result.tracks.addAll(bili.search(query));
        } catch (Exception e) {
            result.errors.add("B站搜索暂时不可用");
        }
        dedupe(result.tracks);
        return result;
    }

    /** 用收藏重建推荐画像，收藏变化时调用即可让推荐越用越准。 */
    public void refreshProfile(List<Track> favorites) {
        engine.buildProfile(favorites);
    }

    /** 会话结束时清空去重记忆（如切换账号场景），供外部按需调用。 */
    public void resetFeedSession() {
        engine.clearSession();
        queryCursor = 0;
        pagePerQuery = 1;
    }

    /**
     * 首页推荐流：每页从词池轮转取若干主题并发搜索，合并候选后交给推荐引擎
     * 按「热度 + 新鲜度 + 收藏画像 + 探索」打分并做多样性打散，跨页去重。
     * 每调用一次产出下一批，可反复调用实现无限下拉且不单调。
     */
    public synchronized Result trending() {
        Result result = new Result();

        // 轮转选出本页主题：从游标开始环形取 QUERIES_PER_PAGE 个，顺带翻页
        String[] batch = new String[QUERIES_PER_PAGE];
        for (int i = 0; i < QUERIES_PER_PAGE; i++) {
            batch[i] = TRENDING_QUERIES[queryCursor % TRENDING_QUERIES.length];
            queryCursor++;
        }
        final int page = pagePerQuery++;

        List<Future<List<Track>>> jobs = new ArrayList<>();
        for (String query : batch) {
            jobs.add(pool.submit(() -> bili.search(query, page)));
        }

        // 合并候选；按主题分组做交错，保证单页内也先混合不同主题
        List<List<Track>> groups = new ArrayList<>();
        for (Future<List<Track>> job : jobs) {
            try {
                groups.add(job.get());
            } catch (Exception e) {
                groups.add(new ArrayList<>());
                result.errors.add("B站推荐暂时不可用");
            }
        }

        List<Track> candidates = new ArrayList<>();
        Map<String, Track> seenThisPage = new LinkedHashMap<>();
        int longest = 0;
        for (List<Track> g : groups) longest = Math.max(longest, g.size());
        for (int i = 0; i < longest; i++) {
            for (List<Track> g : groups) {
                if (i >= g.size()) continue;
                Track t = g.get(i);
                seenThisPage.putIfAbsent(t.stableId(), t);
            }
        }
        candidates.addAll(seenThisPage.values());

        // 交给推荐引擎打分 + 画像 + 打散 + 跨页去重
        result.tracks.addAll(engine.rank(candidates, TRENDING_SIZE));
        if (result.tracks.isEmpty()) result.errors.add("推荐源暂时离线");
        return result;
    }

    /** 合集展开：多 P 合集拆成单曲列表；不可拆返回 null，由调用方回退整段播放。 */
    public List<Track> expand(Track track) {
        try {
            return bili.expand(track);
        } catch (Exception e) {
            return null;
        }
    }

    private static void dedupe(List<Track> tracks) {
        Map<String, Track> unique = new LinkedHashMap<>();
        for (Track t : tracks) unique.putIfAbsent(t.stableId(), t);
        tracks.clear();
        tracks.addAll(unique.values());
    }

    public void close() {
        pool.shutdownNow();
    }
}
