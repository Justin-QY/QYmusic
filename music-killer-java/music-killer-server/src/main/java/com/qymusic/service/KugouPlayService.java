package com.qymusic.service;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import com.qymusic.api.KugouClient;
import com.qymusic.entity.PlayInfo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 酷狗（概念版）播放地址解析。
 *
 * <p>解析顺序：</p>
 * <ol>
 *   <li>gateway {@code /v5/url}（概念版 Android 签名，登录态/免费资源优先）；</li>
 *   <li>网页 songinfo v2（仅登录时可用）；</li>
 *   <li>{@code m.kugou.com} getSongInfo（免费曲目的匿名直链）；</li>
 *   <li>trackercdn 兜底；</li>
 *   <li>前两步按音质逐级降级，免费直链与 tracker 只请求一次。</li>
 * </ol>
 *
 * <p>拿不到地址时返回 null，由上层给出明确提示。缓存 key 不含 Cookie 明文。</p>
 */
@Slf4j
@Service
public class KugouPlayService {

    private static final long TTL_OK = 60L * 60L * 1000L;
    private static final long TTL_FAIL = 3L * 60L * 1000L;
    private static final int MAX_CACHE_SIZE = 3000;

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    @Resource
    private KugouClient kugouClient;

    private record CacheEntry(PlayInfo info, long expireAt) {
    }

    /**
     * 解析酷狗播放地址。
     *
     * @param hash    32 位歌曲 hash（sourceId）
     * @param bridge  客户端音质，如 128kmp3 / 320kmp3 / 2000kflac
     * @param kgCookie 酷狗概念版 Cookie（可空）
     */
    public PlayInfo resolve(String hash, String bridge, String kgCookie) {
        if (hash == null || !hash.trim().matches("(?i)[0-9a-f]{32}")) {
            return null;
        }
        String normalized = hash.trim().toLowerCase();
        String cookie = kgCookie == null ? "" : kgCookie.trim();
        String cacheKey = normalized + "|" + bridge + "|" + cookieDigest(cookie);
        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && cached.expireAt() > System.currentTimeMillis()) {
            return cached.info();
        }

        PlayInfo info = null;
        try {
            // 1. 按音质降级尝试 gateway /v5/url 与登录态 songinfo v2
            for (String quality : qualityChain(bridge)) {
                info = fromUrl(firstUrl(kugouClient.songUrlV5(normalized, quality, "0", cookie)),
                        quality, normalized, null);
                if (info != null) {
                    break;
                }
                if (!cookie.isBlank()) {
                    JsonNode songinfo = kugouClient.songinfoV2(normalized, cookie);
                    info = fromUrl(songinfo == null ? "" : firstNonBlank(
                            songinfo.path("data").path("play_url").asText(""),
                            songinfo.path("data").path("play_backup_url").asText("")),
                            quality, normalized, songinfo);
                    if (info != null) {
                        break;
                    }
                }
            }
            // 2. 免费曲目的匿名直链（与音质无关，只请求一次）
            if (info == null) {
                JsonNode playInfo = kugouClient.playInfo(normalized);
                info = fromUrl(playInfo == null ? "" : playInfo.path("url").asText(""),
                        lowestQuality(bridge), normalized, playInfo);
            }
            // 3. trackercdn 兜底
            if (info == null) {
                JsonNode tracker = kugouClient.tracker(normalized);
                info = fromUrl(tracker == null ? "" : firstNonBlank(
                        tracker.path("url").asText(""),
                        tracker.path("backup_url").asText("")), lowestQuality(bridge),
                        normalized, tracker);
            }
        } catch (Exception e) {
            log.warn("酷狗播放解析异常 hash={} : {}", normalized, e.getMessage());
        }

        cache.put(cacheKey, new CacheEntry(info,
                System.currentTimeMillis() + (info == null ? TTL_FAIL : TTL_OK)));
        evictIfNeeded();
        if (info == null) {
            log.info("酷狗播放解析无结果 hash={} bridge={}", normalized, bridge);
        } else {
            log.info("酷狗播放解析成功 hash={} source={} bitrate={}",
                    normalized, info.getSource(), info.getBitrate());
        }
        return info;
    }

    /**
     * 从响应里取 url：兼容 {@code data.url} / {@code data[0].url} / 顶层 url。
     */
    private String firstUrl(JsonNode node) {
        if (node == null) {
            return "";
        }
        String url = node.path("url").asText("");
        if (url.isBlank()) {
            url = node.path("data").path("url").asText("");
        }
        if (url.isBlank()) {
            JsonNode data = node.path("data");
            if (data.isArray() && !data.isEmpty()) {
                url = data.get(0).path("url").asText("");
            }
        }
        return url;
    }

    private PlayInfo fromUrl(String url, String quality, String hash, JsonNode meta) {
        if (url == null || url.isBlank() || !url.startsWith("http")) {
            return null;
        }
        String secure = url.startsWith("http://") ? "https://" + url.substring(7) : url;
        PlayInfo info = new PlayInfo();
        info.setUrl(secure);
        String format = guessFormat(firstNonBlank(
                meta == null ? "" : meta.path("extName").asText(""),
                meta == null ? "" : meta.path("extname").asText(""),
                meta == null ? "" : meta.path("data").path("extname").asText(""),
                secure));
        info.setFormat(format);
        info.setBitrate(actualBitrate(meta, quality, format));
        int duration = meta == null ? 0 : meta.path("timeLength").asInt(
                meta.path("timelength").asInt(meta.path("data").path("timelength").asInt(0)));
        if (duration > 0) {
            info.setDuration(duration);
        }
        info.setMusicId(KugouCatalogService.hash64(hash));
        info.setSource("kg");
        info.setType(1);
        return info;
    }

    /**
     * 音质降级链（越靠前越优先）。
     */
    private List<String> qualityChain(String bridge) {
        String b = bridge == null ? "" : bridge.toLowerCase();
        List<String> chain = new ArrayList<>();
        if (b.contains("flac") || b.contains("2000") || b.contains("high")) {
            chain.add("flac");
        }
        if (b.contains("320") || b.contains("flac") || b.contains("2000")
                || b.contains("high")) {
            chain.add("320");
        }
        chain.add("128");
        return chain;
    }

    /** 免费直链接口不带音质参数，用最低档位描述即可。 */
    private String lowestQuality(String bridge) {
        List<String> chain = qualityChain(bridge);
        return chain.isEmpty() ? "128" : chain.get(chain.size() - 1);
    }

    private Integer bitrateOf(String quality) {
        if ("flac".equals(quality)) {
            return 2000;
        }
        if ("320".equals(quality)) {
            return 320;
        }
        return 128;
    }

    /**
     * 实际码率：优先取上游返回的 bitRate（bps），否则按格式和请求音质推断。
     */
    private Integer actualBitrate(JsonNode meta, String quality, String format) {
        int raw = meta == null ? 0 : meta.path("bitRate").asInt(
                meta.path("bitrate").asInt(
                        meta.path("data").path("bitrate").asInt(0)));
        if (raw > 1000) {
            raw = raw / 1000;
        }
        if (raw > 0) {
            return Math.min(raw, 2000);
        }
        if ("flac".equals(format)) {
            return bitrateOf("flac");
        }
        return Math.min(bitrateOf(quality), 320);
    }

    private String guessFormat(String extOrUrl) {
        String value = extOrUrl == null ? "" : extOrUrl.toLowerCase();
        int index = value.indexOf('?');
        if (index > 0) {
            value = value.substring(0, index);
        }
        if (value.contains("flac")) {
            return "flac";
        }
        if (value.contains("ogg") || value.contains("opus")) {
            return "ogg";
        }
        if (value.contains("m4a") || value.contains("aac") || value.contains("mp4")) {
            return "m4a";
        }
        if (value.contains("wav")) {
            return "wav";
        }
        return "mp3";
    }

    private String cookieDigest(String cookie) {
        if (cookie.isBlank()) {
            return "-";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(cookie.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", bytes[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return "x";
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private void evictIfNeeded() {
        if (cache.size() <= MAX_CACHE_SIZE) {
            return;
        }
        long now = System.currentTimeMillis();
        cache.entrySet().removeIf(entry -> entry.getValue().expireAt() <= now);
        if (cache.size() > MAX_CACHE_SIZE) {
            cache.clear();
        }
    }
}
