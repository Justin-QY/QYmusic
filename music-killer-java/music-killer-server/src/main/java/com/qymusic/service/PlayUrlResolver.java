package com.qymusic.service;

import cn.hutool.core.io.IoUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.springframework.stereotype.Service;
import com.qymusic.entity.PlayInfo;
import com.qymusic.api.SearchService;
import com.qymusic.entity.Music;
import com.qymusic.lx.LxSourceService;
import com.qymusic.lx.LxUrlValidator;
import com.qymusic.properties.KuwoProperties;
import com.qymusic.vo.SearchMusicResultVO;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 播放地址解析。
 *
 * <p>酷我官方接口 {@code mobi.s?type=convert_url_with_sign} 在没有有效签名时，
 * 对任何歌曲都会返回同一段 11 秒的“正版”提示音，这就是客户端里所有歌曲都提示正版的原因。</p>
 *
 * <p>这里的解析顺序：</p>
 * <ol>
 *   <li>酷我网页版播放接口（带 Secret/reqId 签名），免费歌曲可直接拿到完整音频；</li>
 *   <li>若该歌曲为酷我付费内容，则按「歌名 + 歌手」到备用音源（网易云）匹配同曲，取完整音频；</li>
 *   <li>都拿不到时返回 null，由上层返回明确的错误信息。</li>
 * </ol>
 */
@Slf4j
@Service
public class PlayUrlResolver {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120.0.0.0 Safari/537.36";

    private static final String KUWO_API = "https://www.kuwo.cn";
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

    /** 多路并行解析的总预算 */
    private static final long PARALLEL_BUDGET_MS = 20_000L;

    /** 音源脚本单级音质的预算（拿不到就降一级，避免一直等） */
    private static final long LX_STEP_BUDGET_MS = 7_000L;

    private final ObjectMapper mapper = new ObjectMapper();

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    /** kuwo.js 内容，只读一次 */
    private volatile String kuwoScriptCache;

    @Resource
    private KuwoProperties kuwoProperties;

    @Resource
    private LxSourceService lxSourceService;

    @Resource
    private SearchService searchService;

    /** 多路并行解析用的线程池（LX 脚本 / 酷我同名 / 备用源同时跑，谁先可用用谁） */
    private final ExecutorService racePool = Executors.newFixedThreadPool(16, r -> {
        Thread t = new Thread(r, "play-race");
        t.setDaemon(true);
        return t;
    });

    private record CacheEntry(PlayInfo info, long expireAt) {
    }

    private record KuwoSign(String secret, String reqId) {
    }

    private record SongInfo(String name, String artist, String album, Integer duration) {
    }

    /**
     * 解析歌曲播放地址（酷我）。
     *
     * @param id     酷我歌曲 id
     * @param bridge 音质（形如 128kmp3 / 320kmp3 / 2000kflac）
     * @return 播放信息，解析不到时返回 null
     */
    public PlayInfo resolve(String id, String bridge) {
        return resolve(id, bridge, "kw", null, null, null, null);
    }

    /**
     * 解析歌曲播放地址。
     *
     * @param source   音源平台：kw=酷我，wy=网易云
     * @param name     歌名（非酷我歌曲必传）
     * @param singer   歌手
     * @param album    专辑
     * @param duration 时长（秒）
     */
    public PlayInfo resolve(String id, String bridge, String source, String name,
                            String singer, String album, Integer duration) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String src = (source == null || source.isBlank()) ? "kw" : source.trim().toLowerCase();
        String cacheKey = src + "|" + id + "|" + bridge;
        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && cached.expireAt() > System.currentTimeMillis()) {
            return cached.info();
        }

        PlayInfo info = null;
        try {
            if ("kw".equals(src)) {
                info = resolveFromKuwo(id, bridge);
                if (info == null) {
                    SongInfo song = fetchKuwoSongInfo(id);
                    if (song != null) {
                        // 酷我官方接口拿不到（付费/版权）时，脚本和备用源并行跑，谁先给可用地址用谁
                        info = resolveKwFallback(src, id, bridge, song);
                    }
                }
            } else {
                // 非酷我歌曲：客户端已经带上了搜索结果的元数据，直接交给对应平台的脚本解析
                SongInfo song = new SongInfo(
                        name == null ? "" : name,
                        singer == null ? "" : singer,
                        album == null ? "" : album,
                        duration == null ? 0 : duration);
                if (song.name().isBlank()) {
                    log.info("非酷我歌曲缺少歌名，无法解析 source={} id={}", src, id);
                    return null;
                }
                info = resolveNonKuwo(src, id, bridge, song);
            }
        } catch (Exception e) {
            log.warn("播放地址解析失败 source={} id={} bridge={} : {}", src, id, bridge, e.getMessage());
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
            log.info("播放地址解析成功 id={} source={} bitrate={}", id, info.getSource(), info.getBitrate());
        }
        return info;
    }

    // ------------------------------------------------------------------ 酷我

    private PlayInfo resolveFromKuwo(String id, String bridge) {
        if (!hasKuwoCredentials()) {
            return null;
        }
        String url = KUWO_API + "/api/v1/www/music/playUrl?mid=" + id + "&type=music&httpsStatus=1";
        JsonNode node = getJson(url, kuwoHeaders(id));
        if (node == null) {
            return null;
        }
        String audio = node.path("data").path("url").asText("");
        if (audio.isBlank() || !audio.startsWith("http")) {
            // 付费歌曲会走到这里：code=-1，msg=该歌曲为付费内容
            log.debug("酷我无可用直链 id={} msg={}", id, node.path("msg").asText(""));
            return null;
        }

        PlayInfo info = new PlayInfo();
        info.setUrl(audio);
        info.setFormat(guessFormat(audio));
        info.setBitrate(parseBitrate(bridge));
        info.setMusicId(parseLong(id));
        info.setSource("kuwo");
        info.setType(1);
        return info;
    }

    private SongInfo fetchKuwoSongInfo(String id) {
        if (!hasKuwoCredentials()) {
            return null;
        }
        JsonNode node = getJson(KUWO_API + "/api/www/music/musicInfo?mid=" + id + "&httpsStatus=1",
                kuwoHeaders(id));
        if (node == null) {
            return null;
        }
        // 该接口把歌曲信息放在 data 里；少数情况下可能是平铺结构，两种都兼容
        JsonNode data = node.hasNonNull("data") ? node.path("data") : node;
        String name = data.path("name").asText("").trim();
        String artist = data.path("artist").asText("").trim();
        String album = data.path("album").asText("").trim();
        if (name.isEmpty()) {
            log.debug("未取到歌曲信息 id={}", id);
            return null;
        }
        return new SongInfo(name, artist, album, data.path("duration").asInt(0));
    }

    private boolean hasKuwoCredentials() {
        return kuwoProperties.getValue() != null && !kuwoProperties.getValue().isBlank()
                && kuwoProperties.getKey() != null && !kuwoProperties.getKey().isBlank();
    }

    /**
     * 构造酷我网页版接口所需的请求头（Secret / reqId 由 kuwo.js 生成，与 SignInterceptor 一致）
     */
    private Map<String, String> kuwoHeaders(String id) {
        KuwoSign sign = sign();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", USER_AGENT);
        headers.put("Referer", KUWO_API + "/play_detail/" + id);
        headers.put("Accept", "application/json");
        headers.put("Cookie", kuwoProperties.getCookie());
        headers.put("Secret", sign.secret());
        headers.put("reqId", sign.reqId());
        headers.put("csrf", "abc123");
        return headers;
    }

    // ------------------------------------------------------------------ 音源脚本

    /**
     * 酷我付费歌曲：音源脚本与备用源并行解析。
     */
    private PlayInfo resolveKwFallback(String source, String id, String bridge, SongInfo song) {
        long deadline = System.currentTimeMillis() + PARALLEL_BUDGET_MS;
        ExecutorCompletionService<PlayInfo> completion = new ExecutorCompletionService<>(racePool);
        List<Future<PlayInfo>> futures = new ArrayList<>();
        futures.add(completion.submit(() -> resolveFromLxScriptDowngrade(source, id, bridge, song)));
        futures.add(completion.submit(() -> resolveFromFallback(song, id, bridge)));
        try {
            for (int i = 0; i < futures.size(); i++) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    break;
                }
                Future<PlayInfo> done = completion.poll(remaining, TimeUnit.MILLISECONDS);
                if (done == null) {
                    break;
                }
                PlayInfo info = done.get();
                if (info != null) {
                    return info;
                }
            }
        } catch (Exception e) {
            log.debug("酷我备用解析异常 id={} : {}", id, e.getMessage());
        } finally {
            futures.forEach(f -> {
                if (!f.isDone()) {
                    f.cancel(true);
                }
            });
        }
        return null;
    }

    /**
     * 非酷我歌曲（网易云等）的解析：三路并行，谁先给出通过校验的地址就用谁。
     *
     * <p>只串行依赖音源脚本时，某个脚本接口慢/挂了就要等满 15 秒，客户端表现为
     * 「点了没反应」或者干脆失败。这里同时开三条路：</p>
     * <ol>
     *   <li>内置洛雪脚本（按音质逐级降级：flac → 320k → 128k）；</li>
     *   <li>酷我同名同歌手（歌名、歌手、时长三重校验，命中后走酷我官方直链，速度快）；</li>
     *   <li>备用聚合接口（同样做歌手 + 时长校验）。</li>
     * </ol>
     */
    private PlayInfo resolveNonKuwo(String source, String id, String bridge, SongInfo song) {
        long deadline = System.currentTimeMillis() + PARALLEL_BUDGET_MS;
        ExecutorCompletionService<PlayInfo> completion = new ExecutorCompletionService<>(racePool);
        List<Future<PlayInfo>> futures = new ArrayList<>();
        futures.add(completion.submit(() -> resolveFromLxScriptDowngrade(source, id, bridge, song)));
        futures.add(completion.submit(() -> resolveFromKuwoBySong(song, id, bridge)));
        futures.add(completion.submit(() -> resolveFromFallback(song, id, bridge)));

        try {
            for (int i = 0; i < futures.size(); i++) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    break;
                }
                Future<PlayInfo> done = completion.poll(remaining, TimeUnit.MILLISECONDS);
                if (done == null) {
                    break;
                }
                PlayInfo info = done.get();
                if (info != null) {
                    return info;
                }
            }
        } catch (Exception e) {
            log.debug("并行解析异常 source={} id={} : {}", source, id, e.getMessage());
        } finally {
            futures.forEach(f -> {
                if (!f.isDone()) {
                    f.cancel(true);
                }
            });
        }
        return null;
    }

    /**
     * 按音质逐级降级尝试音源脚本：拿不到无损就退 320k，再退 128k。
     * 每一步单独限时，避免一直等到总超时。
     */
    private PlayInfo resolveFromLxScriptDowngrade(String source, String id, String bridge, SongInfo song) {
        for (String candidateBridge : lxBridges(bridge)) {
            PlayInfo info = resolveFromLxScript(source, id, candidateBridge, song, LX_STEP_BUDGET_MS);
            if (info != null) {
                if (!candidateBridge.equals(bridge)) {
                    log.info("音源脚本已降级 id={} 期望={} 实际={}", id, bridge, candidateBridge);
                }
                return info;
            }
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
     * 用「歌名 + 歌手」在酷我搜索同名歌曲，命中后取酷我官方直链。
     *
     * <p>酷我对大部分热门歌曲都有正版直链，速度也比第三方脚本稳定；
     * 歌名、歌手、时长三项都校验通过才使用，避免播到翻唱或别的歌。</p>
     */
    private PlayInfo resolveFromKuwoBySong(SongInfo song, String id, String bridge) {
        if (!hasKuwoCredentials() || searchService == null || song.name().isBlank()) {
            return null;
        }
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            String keyword = song.artist().isBlank() ? song.name() : song.name() + " " + song.artist();
            params.put("all", keyword);
            params.put("pn", 0);
            params.put("rn", 12);
            params.put("vipver", 1);
            params.put("client", "kt");
            params.put("ft", "music");
            params.put("cluster", 0);
            params.put("strategy", 2012);
            params.put("encoding", "utf8");
            params.put("rformat", "json");
            params.put("mobi", 1);
            params.put("issubtitle", 1);
            params.put("show_copyright_off", 1);

            SearchMusicResultVO vo = searchService.searchMusicByKeyWord(params);
            List<Music> list = vo == null ? null : vo.getData();
            if (list == null || list.isEmpty()) {
                return null;
            }
            String name = normalize(song.name());
            String artist = normalize(song.artist());
            for (Music m : list) {
                if (m.getId() == null || m.getId() <= 0) {
                    continue;
                }
                String rawName = m.getName() == null ? "" : m.getName();
                if (isCoverMarked(rawName) || !normalize(rawName).equals(name)) {
                    continue;
                }
                String cArtist = normalize(m.getArtist());
                boolean artistMatched = artist.isEmpty() || cArtist.isEmpty()
                        || cArtist.contains(artist) || artist.contains(cArtist);
                if (!artistMatched) {
                    continue;
                }
                if (!secondsMatches(song.duration(), m.getDuration())) {
                    continue;
                }
                PlayInfo info = resolveFromKuwo(String.valueOf(m.getId()), bridge);
                if (info != null) {
                    info.setMusicId(parseLong(id));
                    return info;
                }
            }
        } catch (Exception e) {
            log.debug("酷我同名解析失败 keyword={} : {}", song.name(), e.getMessage());
        }
        return null;
    }

    /**
     * 时长比对：差异不超过 10% 且不低于 6 秒
     */
    private boolean secondsMatches(Integer expected, Integer actual) {
        if (expected == null || expected <= 0 || actual == null || actual <= 0) {
            // 缺少时长时不做拦截（上游已校验歌名和歌手）
            return true;
        }
        return Math.abs(expected - actual) <= Math.max(6, expected * 0.1);
    }

    /**
     * 用内置的洛雪音源脚本解析播放地址。
     *
     * <p>脚本拿到的歌曲信息里同时塞了 rid / id / songmid，
     * 这样不同脚本无论取哪个字段都能命中同一首歌（酷我的 id 就是 rid）。
     * 不塞空 hash，避免部分脚本把它当成有效值。</p>
     */
    private PlayInfo resolveFromLxScript(String source, String id, String bridge, SongInfo song, long budgetMs) {
        if (lxSourceService == null || !lxSourceService.isEnabled()) {
            return null;
        }
        String musicInfo;
        try {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("rid", id);
            map.put("id", id);
            map.put("songmid", id);
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
                ? lxSourceService.resolve(source, lxQuality(bridge), musicInfo, song.duration(), budgetMs)
                : lxSourceService.resolve(source, lxQuality(bridge), musicInfo, song.duration());
        if (result == null) {
            return null;
        }

        PlayInfo info = new PlayInfo();
        info.setUrl(result.url());
        info.setFormat(guessFormat(result.url()));
        info.setBitrate(parseBitrate(bridge));
        info.setDuration(song.duration());
        info.setMusicId(parseLong(id));
        info.setSource("lx:" + result.scriptId());
        info.setType(1);
        return info;
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

    private PlayInfo resolveFromFallback(SongInfo song, String id, String bridge) {
        String keyword = song.name() + (song.artist().isEmpty() ? "" : " " + song.artist());
        String searchUrl = FALLBACK_API + "?types=search&source=netease&name=" + encode(keyword)
                + "&count=15&pages=1";
        JsonNode list = getJson(searchUrl, Map.of("User-Agent", USER_AGENT));
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

        PlayInfo hit = tryCandidates(ranked, song, id, bridge);
        if (hit == null) {
            log.info("备用音源无合适匹配 keyword={} 候选数={}", keyword, list.size());
        }
        return hit;
    }

    /**
     * 依次尝试候选歌曲，逐个做时长校验后才返回。
     *
     * <p>歌曲时长与文件大小可以互相换算，这里用酷我给出的时长做交叉校验，
     * 可以剔除同名但内容不同的片段、剪辑版。</p>
     */
    private PlayInfo tryCandidates(List<JsonNode> candidates, SongInfo song, String id, String bridge) {
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
                attempts++;
                String url = FALLBACK_API + "?types=url&source=netease&id=" + cid + "&br=" + br;
                JsonNode node = getJson(url, Map.of("User-Agent", USER_AGENT));
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
                info.setMusicId(parseLong(id));
                info.setSource("netease");
                info.setType(1);
                return info;
            }
        }
        return null;
    }

    /**
     * 用返回的 size 与 br 反推时长（实测与真实字节数一致），和酷我给出的时长比对
     */
    private boolean durationMatches(Integer expectedSeconds, JsonNode node) {
        if (expectedSeconds == null || expectedSeconds <= 0) {
            // 酷我没给时长时无法校验，直接拒绝，避免放出错误音频
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
     *
     * <p>歌手匹配是硬条件：网易云上常见「同名 + 时长几乎一致」的翻唱，
     * 只靠歌名和时长无法区分，必须校验歌手。</p>
     *
     * @param requireArtist 是否要求歌手必须匹配（原曲歌手未知时应传 false）
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

    private JsonNode getJson(String url, Map<String, String> headers) {
        try (HttpResponse response = HttpRequest.get(url)
                .addHeaders(headers)
                .timeout(8000)
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

    /**
     * 复用项目自带的 kuwo.js 生成 Secret / reqId（与 SignInterceptor 一致）
     *
     * <p>脚本内容只读一次并缓存；Context 每次新建，保证线程安全。</p>
     */
    private KuwoSign sign() {
        try (Context context = Context.newBuilder("js")
                .option("engine.WarnInterpreterOnly", "false")
                .allowAllAccess(true)
                .build()) {
            context.eval("js", kuwoScript());
            Value generateSecret = context.getBindings("js").getMember("generateSecret");
            Value generateReqId = context.getBindings("js").getMember("generateReqId");
            String secret = generateSecret.execute(kuwoProperties.getValue(), kuwoProperties.getKey()).asString();
            String reqId = generateReqId.execute().asString();
            return new KuwoSign(secret, reqId);
        }
    }

    private String kuwoScript() {
        String cached = kuwoScriptCache;
        if (cached != null) {
            return cached;
        }
        try (InputStream inputStream = this.getClass().getResourceAsStream("/static/js/kuwo.js")) {
            if (inputStream == null) {
                throw new IllegalStateException("缺少 kuwo.js，无法生成酷我签名");
            }
            kuwoScriptCache = IoUtil.read(inputStream, StandardCharsets.UTF_8);
            return kuwoScriptCache;
        } catch (Exception e) {
            throw new IllegalStateException("读取 kuwo.js 失败: " + e.getMessage(), e);
        }
    }
}
