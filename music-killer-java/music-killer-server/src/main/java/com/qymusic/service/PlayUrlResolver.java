package com.qymusic.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import com.qymusic.entity.PlayInfo;
import com.qymusic.lx.LxSourceService;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * 播放地址解析。
 *
 * <p>严格串行，前一级拿到可用地址就不再尝试下一级：</p>
 * <ol>
 *   <li>平台自带音源：酷狗交给 {@link KugouPlayService}（songinfo v2 / v5 url / getSongInfo）；
 *       QQ 的原生 vkey 需要登录 Cookie，由 PlayController 先试；网易云没有可用的原生取地址接口；</li>
 *   <li>内置洛雪音源脚本（按音质逐级降级，每级单独限时）；</li>
 *   <li>第三方聚合接口（music-api.gdstudio.xyz）。</li>
 * </ol>
 *
 * <p>每一级失败只记录原因、不抛异常，整次解析控制在约 {@code 20s} 内。
 * 拿不到地址时返回 null，由上层返回明确的错误信息。</p>
 */
@Slf4j
@Service
public class PlayUrlResolver {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120.0.0.0 Safari/537.36";

    private static final String FALLBACK_API = "https://music-api.gdstudio.xyz/api.php";

    /** 成功结果缓存时间：1 小时 */
    private static final long TTL_OK = 60L * 60L * 1000L;
    /** 失败结果缓存时间：3 分钟（避免频繁打第三方接口） */
    private static final long TTL_FAIL = 3L * 60L * 1000L;
    /** 音源脚本给出的第三方直链有效期较短，缓存 5 分钟 */
    private static final long TTL_LX = 5L * 60L * 1000L;

    /** 翻唱 / 伴奏 / 片段等干扰项标记（"原唱" 是翻唱者对原唱的标注，同样属于翻唱） */
    private static final java.util.regex.Pattern COVER_MARK = java.util.regex.Pattern.compile(
            "翻唱|翻自|cover|伴奏|remix|dj版|纯音乐|钢琴版|吉他版|铃声|片段|试听|demo|女声版|男声版|抖音|原唱|致敬|模仿",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /** 单次解析最多向备用音源发起的取地址请求数（控制响应时间） */
    private static final int MAX_FALLBACK_ATTEMPTS = 6;

    /** 解析缓存的最大条目数（超出后先清理过期项，防止长期运行内存增长） */
    private static final int MAX_CACHE_SIZE = 5000;

    /** 一次解析的总预算：平台原生 → 音源脚本 → 第三方聚合，串行执行 */
    private static final long TOTAL_BUDGET_MS = 20_000L;

    /** 平台原生接口的预算（超时即放弃，交给后面的兜底链路） */
    private static final long NATIVE_BUDGET_MS = 10_000L;

    /** 音源脚本单级音质的预算（拿不到就降一级，避免一直等） */
    private static final long LX_STEP_BUDGET_MS = 5_000L;

    /** 音源脚本整条降级链的总预算 */
    private static final long LX_BUDGET_MS = 10_000L;

    /** 给第三方聚合接口预留的时间，避免音源脚本把总预算吃光 */
    private static final long FALLBACK_RESERVE_MS = 5_000L;

    /** 第三方聚合接口单次请求的超时 */
    private static final long REQUEST_TIMEOUT_MS = 8_000L;

    private final ObjectMapper mapper = new ObjectMapper();

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    @Resource
    private LxSourceService lxSourceService;

    @Resource
    private KugouPlayService kugouPlayService;

    /** 平台原生音源的单级执行池（超时后立刻放弃该级，避免拖垮整次解析） */
    private final ExecutorService stagePool = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "play-stage");
        t.setDaemon(true);
        return t;
    });

    private record CacheEntry(PlayInfo info, long expireAt) {
    }

    private record SongInfo(String name, String artist, String album, Integer duration) {
    }

    /**
     * 解析歌曲播放地址（默认按网易云处理）。
     *
     * @param id     平台歌曲 id
     * @param bridge 音质（形如 128kmp3 / 320kmp3 / 2000kflac）
     * @return 播放信息，解析不到时返回 null
     */
    public PlayInfo resolve(String id, String bridge) {
        return resolve(id, bridge, "wy", null, null, null, null);
    }

    /**
     * 解析歌曲播放地址。
     *
     * @param source   音源平台：kg=酷狗，wy=网易云，qq=QQ 音乐
     * @param name     歌名
     * @param singer   歌手
     * @param album    专辑
     * @param duration 时长（秒）
     */
    public PlayInfo resolve(String id, String bridge, String source, String name,
                            String singer, String album, Integer duration) {
        return resolve(id, bridge, source, null, name, singer, album, duration, null);
    }

    /**
     * 解析歌曲播放地址（带平台原始 ID 与酷狗 Cookie）。
     *
     * @param sourceId 平台原始 ID（酷狗为 32 位 hash）
     * @param kgCookie 酷狗概念版 Cookie（仅 kg 使用）
     */
    public PlayInfo resolve(String id, String bridge, String source, String sourceId,
                            String name, String singer, String album, Integer duration,
                            String kgCookie) {
        String src = (source == null || source.isBlank()) ? "wy" : source.trim().toLowerCase();
        if (!"kg".equals(src) && (id == null || id.isBlank())) {
            return null;
        }
        // 平台原始 ID：酷狗是 32 位 hash，QQ 是 songmid；其它平台直接用 id
        String platformId = firstNonBlank(sourceId, id);
        if (platformId.isBlank()) {
            return null;
        }
        // 缓存 key 与原实现保持一致：酷狗用 hash，其它平台用 id
        String cacheKey = "kg".equals(src)
                ? "kg|" + platformId + "|" + bridge
                : src + "|" + id + "|" + bridge;
        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && cached.expireAt() > System.currentTimeMillis()) {
            return cached.info();
        }
        if (!"kg".equals(src) && (name == null || name.isBlank())) {
            // 原生链路之外的兜底都依赖歌名，缺歌名时不缓存失败结果（保持原行为）
            log.info("歌曲缺少歌名，无法解析 source={} id={}", src, id);
            return null;
        }

        SongInfo song = new SongInfo(
                name == null ? "" : name,
                singer == null ? "" : singer,
                album == null ? "" : album,
                duration == null ? 0 : duration);

        PlayInfo info = null;
        try {
            info = resolveByChain(src, platformId,
                    id == null || id.isBlank() ? platformId : id, bridge, song, kgCookie);
        } catch (Exception e) {
            log.warn("播放地址解析失败 source={} id={} bridge={} : {}", src, id, bridge,
                    e.getMessage());
        }

        long ttl = TTL_OK;
        if (info == null) {
            ttl = TTL_FAIL;
        } else if (info.getSource() != null && info.getSource().startsWith("lx:")) {
            ttl = TTL_LX;
        }
        cache.put(cacheKey, new CacheEntry(info, System.currentTimeMillis() + ttl));
        evictIfNeeded();

        if (info == null) {
            log.info("播放地址解析无结果 id={} bridge={}", id, bridge);
        } else {
            log.info("播放地址解析成功 id={} source={} bitrate={}", id, info.getSource(),
                    info.getBitrate());
        }
        return info;
    }

    // ------------------------------------------------------------ 串行解析链路

    /**
     * 严格串行：平台原生 → 洛雪音源脚本（按音质降级）→ 第三方聚合接口。
     *
     * <p>前一级拿到可用地址立刻返回，后续级别不再发起请求；每一级失败只记录原因。</p>
     *
     * @param platformId 平台原始 ID（酷狗 hash / QQ songmid）
     * @param cacheId    用于回填 {@link PlayInfo#getMusicId()} 的平台歌曲 id
     */
    private PlayInfo resolveByChain(String source, String platformId, String cacheId,
                                    String bridge, SongInfo song, String kgCookie) {
        long deadline = System.currentTimeMillis() + TOTAL_BUDGET_MS;
        Long musicId = parseLong(cacheId);

        // 1. 平台自带音源
        PlayInfo info = resolveFromPlatform(source, platformId, bridge, kgCookie, deadline);
        if (isUsable(info)) {
            return info;
        }

        if (song.name().isBlank()) {
            log.info("歌曲缺少歌名，跳过音源脚本与第三方兜底 source={} id={}", source, cacheId);
            return null;
        }

        // 2. 洛雪音源脚本：按音质逐级降级，每级单独限时
        try {
            long lxDeadline = Math.min(deadline - FALLBACK_RESERVE_MS,
                    System.currentTimeMillis() + LX_BUDGET_MS);
            info = resolveFromLxScriptDowngrade(source, platformId, bridge, song, musicId,
                    lxDeadline);
            if (isUsable(info)) {
                return info;
            }
            log.info("音源脚本未命中，进入第三方兜底 source={} id={}", source, cacheId);
        } catch (Exception e) {
            log.warn("音源脚本解析异常 source={} id={} : {}", source, cacheId, e.getMessage());
        }

        // 3. 第三方聚合接口
        try {
            info = resolveFromFallback(song, cacheId, bridge, musicId, deadline);
            if (isUsable(info)) {
                return info;
            }
            log.info("第三方聚合接口未命中 source={} id={}", source, cacheId);
        } catch (Exception e) {
            log.warn("第三方聚合接口异常 source={} id={} : {}", source, cacheId, e.getMessage());
        }
        return null;
    }

    /**
     * 平台自带音源。
     *
     * <ul>
     *   <li>酷狗：{@link KugouPlayService}（v5/url → songinfo v2 → getSongInfo → trackercdn）；</li>
     *   <li>QQ：原生 vkey 需要用户 Cookie，已在 PlayController 里先试过，这里直接走兜底；</li>
     *   <li>网易云：没有可用的原生取地址接口（/api 只提供目录与歌词），跳过。</li>
     * </ul>
     */
    private PlayInfo resolveFromPlatform(String source, String platformId, String bridge,
                                         String kgCookie, long deadline) {
        if (!"kg".equals(source)) {
            return null;
        }
        long budget = remaining(deadline, NATIVE_BUDGET_MS);
        PlayInfo info = runWithBudget("酷狗原生", platformId, budget,
                () -> kugouPlayService.resolve(platformId, bridge, kgCookie));
        if (!isUsable(info)) {
            log.info("酷狗原生未取到地址 hash={} bridge={}，进入兜底", platformId, bridge);
            return null;
        }
        return info;
    }

    /**
     * 单级限时执行：超时直接放弃该级（底层请求仍在后台跑完，不影响返回）。
     */
    private PlayInfo runWithBudget(String stage, String id, long budgetMs,
                                   Supplier<PlayInfo> task) {
        if (budgetMs <= 0) {
            log.info("{} 跳过（剩余时间不足） id={}", stage, id);
            return null;
        }
        Future<PlayInfo> future = stagePool.submit(task::get);
        try {
            return future.get(budgetMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("{} 超时 {}ms，放弃该级 id={}", stage, budgetMs, id);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            log.warn("{} 被中断，放弃该级 id={}", stage, id);
        } catch (Exception e) {
            log.warn("{} 失败 id={} : {}", stage, id, e.getMessage());
        }
        return null;
    }

    private boolean isUsable(PlayInfo info) {
        return info != null && info.getUrl() != null && !info.getUrl().isBlank();
    }

    /** 距离截止时间还剩多少毫秒，同时不超过单级上限 */
    private long remaining(long deadline, long capMs) {
        long left = deadline - System.currentTimeMillis();
        if (left <= 0) {
            return 0;
        }
        return capMs > 0 ? Math.min(left, capMs) : left;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    // ------------------------------------------------------------------ 音源脚本

    /**
     * 按音质逐级降级尝试音源脚本：拿不到无损就退 320k，再退 128k。
     * 每一步单独限时，且不超过整条降级链的截止时间。
     */
    private PlayInfo resolveFromLxScriptDowngrade(String source, String id, String bridge,
                                                  SongInfo song, Long musicId, long deadline) {
        for (String candidateBridge : lxBridges(bridge)) {
            long budget = remaining(deadline, LX_STEP_BUDGET_MS);
            if (budget <= 0) {
                log.info("音源脚本剩余时间不足，停止降级 id={} 已尝试至={}", id, candidateBridge);
                break;
            }
            PlayInfo info = resolveFromLxScript(source, id, candidateBridge, song, musicId, budget);
            if (info != null) {
                if (!candidateBridge.equals(bridge)) {
                    log.info("音源脚本已降级 id={} 期望={} 实际={}", id, bridge, candidateBridge);
                }
                return info;
            }
            log.info("音源脚本 {} 未取到可用地址 id={}", candidateBridge, id);
        }
        return null;
    }

    /**
     * 音质降级链（越靠前越优先）
     */
    private List<String> lxBridges(String bridge) {
        String b = bridge == null ? "" : bridge.toLowerCase();
        List<String> list = new ArrayList<>();
        if (b.contains("flac") || b.contains("2000")) {
            list.add("2000kflac");
        } else if (b.contains("320")) {
            list.add("320kmp3");
        } else if (b.contains("192")) {
            list.add("192kmp3");
        } else {
            list.add("128kmp3");
        }
        if (!list.contains("320kmp3")) {
            list.add("320kmp3");
        }
        if (!list.contains("128kmp3")) {
            list.add("128kmp3");
        }
        return list;
    }

    /**
     * 用内置的洛雪音源脚本解析播放地址。
     *
     * <p>脚本拿到的歌曲信息里同时塞了 rid / id / songmid，
     * 这样不同脚本无论取哪个字段都能命中同一首歌。
     * 不塞空 hash，避免部分脚本把它当成有效值。</p>
     */
    private PlayInfo resolveFromLxScript(String source, String id, String bridge, SongInfo song,
                                         Long musicId, long budgetMs) {
        if (lxSourceService == null || !lxSourceService.isEnabled()) {
            return null;
        }
        String lxSource = lxSource(source);
        String musicInfo;
        try {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("rid", id);
            map.put("id", id);
            map.put("songmid", id);
            if ("kg".equalsIgnoreCase(source)) {
                // 酷狗脚本按 hash 取直链
                map.put("hash", id);
            }
            // 不要传空 hash：部分脚本用 `hash ?? songmid` 取值，空字符串会让它们误判为“缺少歌曲ID”
            map.put("name", song.name());
            map.put("singer", song.artist());
            map.put("albumName", song.album() == null ? "" : song.album());
            map.put("interval", song.duration() == null ? 0 : song.duration());
            musicInfo = mapper.writeValueAsString(map);
        } catch (Exception e) {
            return null;
        }

        LxSourceService.Result result = budgetMs > 0
                ? lxSourceService.resolve(lxSource, lxQuality(bridge), musicInfo, song.duration(),
                budgetMs)
                : lxSourceService.resolve(lxSource, lxQuality(bridge), musicInfo, song.duration());
        if (result == null) {
            return null;
        }

        PlayInfo info = new PlayInfo();
        info.setUrl(result.url());
        info.setFormat(guessFormat(result.url()));
        info.setBitrate(parseBitrate(bridge));
        info.setDuration(song.duration());
        info.setMusicId(musicId == null ? parseLong(id) : musicId);
        info.setSource("lx:" + result.scriptId());
        info.setType(1);
        return info;
    }

    /**
     * 客户端音源标识 -> 洛雪脚本音源标识（脚本里 QQ 音乐叫 tx）
     */
    private String lxSource(String source) {
        if ("qq".equalsIgnoreCase(source) || "tx".equalsIgnoreCase(source)) {
            return "tx";
        }
        return source;
    }

    /**
     * 客户端音质 -> 洛雪脚本音质
     */
    private String lxQuality(String bridge) {
        String b = bridge == null ? "" : bridge.toLowerCase();
        if (b.contains("flac")) {
            return "flac";
        }
        if (b.contains("320")) {
            return "320k";
        }
        return "128k";
    }

    // --------------------------------------------------------------- 备用音源

    private PlayInfo resolveFromFallback(SongInfo song, String id, String bridge, Long musicId,
                                         long deadline) {
        long budget = remaining(deadline, 0);
        if (budget <= 0) {
            log.info("第三方聚合接口跳过（总预算已用完） id={}", id);
            return null;
        }
        String keyword = song.name() + (song.artist().isEmpty() ? "" : " " + song.artist());
        String searchUrl = FALLBACK_API + "?types=search&source=netease&name=" + encode(keyword)
                + "&count=15&pages=1";
        JsonNode list = getJson(searchUrl, Map.of("User-Agent", USER_AGENT),
                remaining(deadline, REQUEST_TIMEOUT_MS));
        if (list == null || !list.isArray() || list.isEmpty()) {
            log.info("备用音源搜索无结果 keyword={}", keyword);
            return null;
        }

        // 歌手必须匹配，否则宁可不播放——网易云上很多同名翻唱（女声版/钢琴版/某翻唱歌手）
        // 时长与原曲几乎一致，只靠时长无法区分，必须用歌手作为硬条件。
        boolean artistRequired = !song.artist().isBlank();
        List<JsonNode> ranked = rank(list, song, artistRequired);
        if (ranked.isEmpty()) {
            log.info("备用音源无同名同歌手的结果，已阻止使用翻唱 keyword={} 候选数={}",
                    keyword, list.size());
            return null;
        }

        PlayInfo hit = tryCandidates(ranked, song, id, bridge, musicId, deadline);
        if (hit == null) {
            log.info("备用音源无合适匹配 keyword={} 候选数={}", keyword, list.size());
        }
        return hit;
    }

    /**
     * 依次尝试候选歌曲，逐个做时长校验后才返回。
     */
    private PlayInfo tryCandidates(List<JsonNode> candidates, SongInfo song, String id,
                                   String bridge, Long musicId, long deadline) {
        if (candidates.isEmpty()) {
            return null;
        }
        int attempts = 0;
        for (JsonNode candidate : candidates) {
            String cid = candidate.path("id").asText("");
            if (cid.isEmpty()) {
                continue;
            }
            for (Integer br : bitrateChain(bridge)) {
                if (attempts >= MAX_FALLBACK_ATTEMPTS) {
                    return null;
                }
                long budget = remaining(deadline, REQUEST_TIMEOUT_MS);
                if (budget <= 0) {
                    log.info("第三方聚合接口预算用完，停止尝试 id={}", id);
                    return null;
                }
                attempts++;
                String url = FALLBACK_API + "?types=url&source=netease&id=" + cid + "&br=" + br;
                JsonNode node = getJson(url, Map.of("User-Agent", USER_AGENT), budget);
                if (node == null) {
                    continue;
                }
                String audio = node.path("url").asText("");
                if (audio.isBlank() || !audio.startsWith("http")) {
                    continue;
                }
                if (!durationMatches(song.duration(), node)) {
                    log.info("时长不符已跳过 候选={} 期望={}s 实际={}s",
                            candidate.path("name").asText(""), song.duration(),
                            estimateSeconds(node));
                    continue;
                }
                PlayInfo info = new PlayInfo();
                info.setUrl(audio);
                info.setFormat(guessFormat(audio));
                info.setBitrate(br);
                info.setDuration(song.duration());
                info.setMusicId(musicId == null ? parseLong(id) : musicId);
                info.setSource("netease");
                info.setType(1);
                return info;
            }
        }
        return null;
    }

    /**
     * 用返回的 size 与 br 反推时长（实测与真实字节数一致），和歌曲时长比对
     */
    private boolean durationMatches(Integer expectedSeconds, JsonNode node) {
        if (expectedSeconds == null || expectedSeconds <= 0) {
            // 没给时长时无法校验，直接拒绝，避免放出错误音频
            return false;
        }
        int actual = estimateSeconds(node);
        if (actual <= 0) {
            return false;
        }
        int diff = Math.abs(actual - expectedSeconds);
        // 同一音源内的同一首歌，时长差异通常在几秒内；8% 且不低于 5 秒
        double tolerance = Math.max(5, expectedSeconds * 0.08);
        return diff <= tolerance;
    }

    private int estimateSeconds(JsonNode node) {
        long size = node.path("size").asLong(0);
        int br = node.path("br").asInt(0);
        if (size <= 0 || br <= 0) {
            return 0;
        }
        return (int) Math.round(size * 8.0 / (br * 1000.0));
    }

    /**
     * 给候选歌曲排序并过滤翻唱/伴奏等干扰项。
     */
    private List<JsonNode> rank(JsonNode list, SongInfo song, boolean requireArtist) {
        String name = normalize(song.name());
        String artist = normalize(song.artist());

        List<Map.Entry<Integer, JsonNode>> scored = new ArrayList<>();
        for (JsonNode item : list) {
            String rawName = item.path("name").asText("");
            if (isCoverMarked(rawName)) {
                continue;
            }
            String cName = normalize(rawName);
            if (cName.isEmpty()) {
                continue;
            }
            StringBuilder sb = new StringBuilder();
            for (JsonNode a : item.path("artist")) {
                sb.append(normalize(a.asText("")));
            }
            String cArtist = sb.toString();

            boolean artistMatched = !artist.isEmpty() && !cArtist.isEmpty()
                    && (cArtist.contains(artist) || artist.contains(cArtist));

            if (requireArtist && !artistMatched) {
                log.debug("歌手不符已跳过 候选={} 歌手={}", rawName, cArtist);
                continue;
            }

            int score;
            if (cName.equals(name)) {
                score = 100;
            } else if (!cName.isEmpty() && (cName.contains(name) || name.contains(cName))) {
                // 歌名带附加信息（如 Xxx（Live））时降权
                score = 50;
            } else {
                continue;
            }
            if (artistMatched) {
                score += 60;
            }
            scored.add(Map.entry(score, item));
        }

        scored.sort((a, b) -> Integer.compare(b.getKey(), a.getKey()));

        List<JsonNode> result = new ArrayList<>();
        for (Map.Entry<Integer, JsonNode> e : scored) {
            if (result.size() >= 4) {
                break;
            }
            result.add(e.getValue());
        }
        return result;
    }

    /**
     * 明显的翻唱 / 伴奏 / 片段，直接排除
     */
    private boolean isCoverMarked(String name) {
        return COVER_MARK.matcher(name).find();
    }

    // ------------------------------------------------------------------ 工具

    private Set<Integer> bitrateChain(String bridge) {
        Set<Integer> chain = new LinkedHashSet<>();
        String b = bridge == null ? "" : bridge.toLowerCase();
        if (b.contains("flac") || b.contains("2000")) {
            chain.add(999);
        } else if (b.contains("320")) {
            chain.add(320);
        } else if (b.contains("192")) {
            chain.add(192);
        } else {
            chain.add(128);
        }
        chain.add(320);
        chain.add(128);
        return chain;
    }

    private Integer parseBitrate(String bridge) {
        String b = bridge == null ? "" : bridge.toLowerCase();
        if (b.contains("flac") || b.contains("2000")) {
            return 2000;
        }
        if (b.contains("320")) {
            return 320;
        }
        if (b.contains("192")) {
            return 192;
        }
        return 128;
    }

    private String guessFormat(String url) {
        String lower = url.toLowerCase();
        // 去掉查询串再判断后缀
        int q = lower.indexOf('?');
        String path = q >= 0 ? lower.substring(0, q) : lower;
        if (path.endsWith(".flac")) {
            return "flac";
        }
        if (path.endsWith(".m4a") || path.endsWith(".mp4")) {
            return "m4a";
        }
        if (path.endsWith(".aac")) {
            return "aac";
        }
        if (path.endsWith(".ogg") || path.endsWith(".opus")) {
            return "ogg";
        }
        if (path.endsWith(".wav")) {
            return "wav";
        }
        return "mp3";
    }

    private Long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception e) {
            return 0L;
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (char c : value.toLowerCase().toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private JsonNode getJson(String url, Map<String, String> headers, long budgetMs) {
        int timeout = (int) Math.max(1, Math.min(budgetMs, REQUEST_TIMEOUT_MS * 4));
        try (cn.hutool.http.HttpResponse response = cn.hutool.http.HttpRequest.get(url)
                .addHeaders(headers)
                .timeout(timeout)
                .execute()) {
            if (!response.isOk()) {
                log.debug("请求失败 {} status={}", url, response.getStatus());
                return null;
            }
            String body = response.body();
            if (body == null || body.isBlank()) {
                return null;
            }
            String trimmed = body.trim();
            // 接口既可能返回对象，也可能返回数组（如搜索接口）
            if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
                return null;
            }
            return mapper.readTree(trimmed);
        } catch (Exception e) {
            log.debug("请求异常 {} : {}", url, e.getMessage());
            return null;
        }
    }

    /**
     * 清理过期缓存；条目过多时整体收缩，避免长时间运行后无限增长
     */
    private void evictIfNeeded() {
        if (cache.size() <= MAX_CACHE_SIZE) {
            return;
        }
        long now = System.currentTimeMillis();
        cache.entrySet().removeIf(e -> e.getValue().expireAt() <= now);
        // 仍然过多（大量未过期条目）则直接整体清空，宁可多查一次也不占内存
        if (cache.size() > MAX_CACHE_SIZE) {
            cache.clear();
        }
    }
}
