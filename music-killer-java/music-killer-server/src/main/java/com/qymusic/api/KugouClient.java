package com.qymusic.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.zip.Inflater;

/**
 * 酷狗概念版（Kugou Lite）接口客户端。
 *
 * <p>协议要点（参考 QPlayer 酷狗插件与 KuGouMusicApi 的实测结论）：</p>
 * <ul>
 *   <li>概念版 appid = 3116，clientver = 11440，Android 签名盐
 *       {@code LnT6xpN3khm36zse0QzvmgTZ3waWdRSA}；</li>
 *   <li>网页版接口签名盐 {@code NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt}；</li>
 *   <li>曲库（搜索/榜单/歌单/歌手）走 mobilecdn 的公开接口，无需签名；</li>
 *   <li>播放地址优先 gateway {@code /v5/url}（登录态/免费资源），
 *       未登录时回退 {@code m.kugou.com} 的 getSongInfo 与 trackercdn；</li>
 *   <li>歌词走 krcs 搜索 + 下载，KRC 需要 16 字节循环 XOR 后 zlib 解压再转 LRC。</li>
 * </ul>
 *
 * <p>所有请求都设置了连接/读取超时；日志不输出 Cookie 与 token。</p>
 */
@Slf4j
@Component
public class KugouClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private static final String GATEWAY = "https://gateway.kugou.com";
    private static final String MOBILE_CDN = "http://mobilecdn.kugou.com/api/v3";
    private static final String SONG_SEARCH = "https://songsearch.kugou.com/song_search_v2";
    private static final String WWW_API = "https://wwwapi.kugou.com";
    private static final String KRCS = "https://krcs.kugou.com";
    private static final String M_KUGOU = "http://m.kugou.com";

    private static final String UA_ANDROID =
            "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi";
    private static final String UA_MOBILE =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 13_2_3 like Mac OS X) AppleWebKit/605.1.15 "
                    + "(KHTML, like Gecko) Version/13.0.3 Mobile/15E148 Safari/604.1";
    private static final String UA_PC =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36";

    /** 概念版 Android 签名盐 */
    private static final String LITE_SIGN = "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA";
    /** 网页/小程序签名盐（songinfo v2、扫码登录） */
    private static final String SIGN_KEY = "NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt";
    private static final String APP_ID = "3116";
    private static final String CLIENT_VER = "11440";

    /** KRC 解密密钥 */
    private static final int[] KRC_KEY = {
            64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45, 206, 210, 110, 105
    };

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(14, TimeUnit.SECONDS)
            .build();

    /** 本机设备 mid：首次使用时随机生成并缓存，作为匿名请求的设备身份。 */
    private volatile String deviceMid;

    // ------------------------------------------------------------------ 曲库

    /**
     * 搜索歌曲。返回 data.lists 的原始节点（字段见 {@code song_search_v2}）。
     */
    public JsonNode searchSong(String keyword, int page, int size) {
        String url = SONG_SEARCH
                + "?keyword=" + encode(keyword == null ? "" : keyword)
                + "&platform=WebFilter&format=json"
                + "&page=" + Math.max(1, page)
                + "&pagesize=" + Math.min(100, Math.max(1, size))
                + "&userid=-1&clientver=&tag=em&filter=2&iscorrection=1&privilege_filter=0";
        return getJson(url, UA_MOBILE, null, null);
    }

    public JsonNode searchAlbum(String keyword, int page, int size) {
        String url = MOBILE_CDN + "/search/album?keyword=" + encode(keyword == null ? "" : keyword)
                + "&format=json&page=" + Math.max(1, page)
                + "&pagesize=" + Math.min(100, Math.max(1, size));
        return getJson(url, UA_MOBILE, null, null);
    }

    public JsonNode searchSpecial(String keyword, int page, int size) {
        String url = MOBILE_CDN + "/search/special?keyword=" + encode(keyword == null ? "" : keyword)
                + "&format=json&page=" + Math.max(1, page)
                + "&pagesize=" + Math.min(100, Math.max(1, size))
                + "&filter=0&highlight=em";
        return getJson(url, UA_MOBILE, null, null);
    }

    public JsonNode searchSinger(String keyword, int page, int size) {
        String url = MOBILE_CDN + "/search/singer?keyword=" + encode(keyword == null ? "" : keyword)
                + "&format=json&page=" + Math.max(1, page)
                + "&pagesize=" + Math.min(100, Math.max(1, size))
                + "&showtype=14&highlight=em&plat=0&sver=5";
        return getJson(url, UA_MOBILE, null, null);
    }

    /** 排行榜列表（含 TOP500 / 飙升榜 等）。 */
    public JsonNode rankList() {
        return getJson(MOBILE_CDN + "/rank/list?parentid=0&apiver=6&plat=0&withsong=1&area_code=1",
                UA_MOBILE, null, null);
    }

    public JsonNode rankSongs(long rankId, int page, int size) {
        return getJson(MOBILE_CDN + "/rank/song?rankid=" + rankId
                        + "&page=" + Math.max(1, page)
                        + "&pagesize=" + Math.min(100, Math.max(1, size))
                        + "&version=9108&area_code=1",
                UA_MOBILE, null, null);
    }

    public JsonNode specialInfo(long specialId) {
        return getJson(MOBILE_CDN + "/special/info?specialid=" + specialId + "&version=9108",
                UA_MOBILE, null, null);
    }

    public JsonNode specialSongs(long specialId, int page, int size) {
        return getJson(MOBILE_CDN + "/special/song?specialid=" + specialId
                        + "&page=" + Math.max(1, page)
                        + "&pagesize=" + Math.min(300, Math.max(1, size))
                        + "&version=9108&area_code=1",
                UA_MOBILE, null, null);
    }

    public JsonNode singerInfo(long singerId) {
        return getJson(MOBILE_CDN + "/singer/info?singerid=" + singerId + "&version=9108",
                UA_MOBILE, null, null);
    }

    public JsonNode singerSongs(long singerId, int page, int size) {
        return getJson(MOBILE_CDN + "/singer/song?singerid=" + singerId
                        + "&page=" + Math.max(1, page)
                        + "&pagesize=" + Math.min(100, Math.max(1, size))
                        + "&version=9108",
                UA_MOBILE, null, null);
    }

    public JsonNode singerList(int page, int size, int sex, int classId) {
        return getJson(MOBILE_CDN + "/singer/list?sex=" + sex
                        + "&classid=" + classId
                        + "&page=" + Math.max(1, page)
                        + "&pagesize=" + Math.min(100, Math.max(1, size))
                        + "&version=9108",
                UA_MOBILE, null, null);
    }

    // ------------------------------------------------------------------ 播放

    /**
     * gateway {@code /v5/url}：概念版 Android 签名。
     * 未登录时通常返回 20028（需要认证），由上层继续降级。
     */
    public JsonNode songUrlV5(String hash, String quality, String albumAudioId, String cookie) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("hash", hash);
        params.put("quality", quality == null || quality.isBlank() ? "128" : quality);
        params.put("album_audio_id", albumAudioId == null || albumAudioId.isBlank() ? "0" : albumAudioId);
        params.put("area_code", "1");
        params.put("behavior", "play");
        params.put("pid", "411");
        params.put("cmd", "26");
        params.put("pidversion", "3001");
        params.put("clientver", "11430");
        params.put("ssa_flag", "is_fromtrack");
        params.put("version", "11430");
        params.put("page_id", "967177915");
        params.put("cdnBackup", "1");
        params.put("module", "");
        params.put("album_id", "0");
        params.put("ppage_id", "356753938,823673182,967485191");
        String mid = firstNonBlank(cookieValue(cookie, "KUGOU_API_MID"),
                cookieValue(cookie, "mid"), deviceMid());
        String userId = firstNonBlank(cookieValue(cookie, "userid"),
                cookieValue(cookie, "KugooID"), "0");
        params.put("key", md5(hash + "185672dd44712f60bb1736df5a377e82"
                + APP_ID + mid + userId));
        return gatewayGet("/v5/url", params, cookie, "trackercdn.kugou.com");
    }

    /**
     * 网页版 songinfo v2（需要登录 Cookie）：hash → encode_album_audio_id → 播放地址。
     */
    public JsonNode songinfoV2(String hash, String cookie) {
        String token = cookieValue(cookie, "token");
        String userId = firstNonBlank(cookieValue(cookie, "userid"), cookieValue(cookie, "KugooID"));
        if (token.isBlank() || userId.isBlank() || "0".equals(userId)) {
            return null;
        }
        String mid = firstNonBlank(cookieValue(cookie, "mid"),
                cookieValue(cookie, "kg_mid"), cookieValue(cookie, "KUGOU_API_MID"), deviceMid());
        String uuid = firstNonBlank(cookieValue(cookie, "uuid"), mid);
        String dfid = firstNonBlank(cookieValue(cookie, "dfid"), "-");

        Map<String, String> step1 = new LinkedHashMap<>();
        step1.put("srcappid", "2919");
        step1.put("clientver", "20000");
        step1.put("clienttime", String.valueOf(System.currentTimeMillis()));
        step1.put("mid", mid);
        step1.put("uuid", uuid);
        step1.put("dfid", dfid);
        step1.put("appid", "1014");
        step1.put("platid", "4");
        step1.put("token", token);
        step1.put("userid", userId);
        step1.put("hash", hash);
        JsonNode first = webSignedGet("/play/songinfo", step1, cookie);
        String encodeId = first == null ? "" : first.path("data").path("encode_album_audio_id").asText("");
        if (encodeId.isBlank()) {
            return null;
        }
        Map<String, String> step2 = new LinkedHashMap<>();
        step2.put("srcappid", "2919");
        step2.put("clientver", "20000");
        step2.put("clienttime", String.valueOf(System.currentTimeMillis()));
        step2.put("mid", mid);
        step2.put("uuid", uuid);
        step2.put("dfid", dfid);
        step2.put("appid", "1014");
        step2.put("platid", "4");
        step2.put("token", token);
        step2.put("userid", userId);
        step2.put("encode_album_audio_id", encodeId);
        return webSignedGet("/play/songinfo", step2, cookie);
    }

    /**
     * m.kugou.com 的播放信息接口：免费曲目直接返回 url，付费曲目 url 为空。
     */
    public JsonNode playInfo(String hash) {
        return getJson(M_KUGOU + "/app/i/getSongInfo.php?cmd=playInfo&hash=" + encode(hash),
                UA_MOBILE, null, null);
    }

    /**
     * trackercdn 兜底：md5(hash + kgcloudv2) 作为 key。
     */
    public JsonNode tracker(String hash) {
        String key = md5(hash + "kgcloudv2");
        String url = "https://trackercdn.kugou.com/i/v2/?cdnBackup=1&behavior=download"
                + "&pid=1&cmd=21&appid=1001&hash=" + encode(hash) + "&key=" + key;
        return getJson(url, UA_PC, null, null);
    }

    // ------------------------------------------------------------------ 歌词

    /**
     * 获取歌词并转换为逐行 LRC（KRC 会解密后只保留时间轴行）。
     *
     * @return LRC 文本；没有歌词时返回空串
     */
    public String lyric(String hash) {
        if (hash == null || hash.isBlank()) {
            return "";
        }
        JsonNode search = getJson(KRCS + "/search?ver=1&client=mobi&duration=0&hash="
                + encode(hash) + "&album_audio_id=", UA_MOBILE, null, null);
        JsonNode candidates = search == null ? null : search.path("candidates");
        if (candidates == null || !candidates.isArray() || candidates.isEmpty()) {
            return "";
        }
        JsonNode candidate = candidates.get(0);
        String id = candidate.path("id").asText("");
        String accessKey = candidate.path("accesskey").asText("");
        if (id.isBlank() || accessKey.isBlank()) {
            return "";
        }
        String query = "ver=1&id=" + encode(id) + "&accesskey=" + encode(accessKey)
                + "&fmt=krc&charset=utf8";
        JsonNode download = getJson(KRCS + "/download?" + query + "&client=pc", UA_MOBILE, null, null);
        if (download == null) {
            download = getJson(KRCS + "/download?" + query + "&client=mobi", UA_MOBILE, null, null);
        }
        if (download == null) {
            return "";
        }
        String content = download.path("content").asText("");
        if (content.isBlank()) {
            return "";
        }
        String text;
        try {
            text = decodeLyric(content, download.path("fmt").asText(""),
                    download.path("contenttype").asInt(-1));
        } catch (Exception e) {
            log.debug("酷狗歌词解码失败: {}", e.getMessage());
            return "";
        }
        if (text.isBlank()) {
            return "";
        }
        return text.contains("[") ? krcToLrc(text) : "";
    }

    /**
     * KRC / LRC 内容解码：base64 → 判断格式 → 去 4 字节头、XOR、zlib。
     */
    String decodeLyric(String content, String fmt, int contentType) throws Exception {
        byte[] bytes = java.util.Base64.getDecoder().decode(
                content.replaceAll("[^A-Za-z0-9+/=]", ""));
        boolean isLrc = contentType == 2 || "lrc".equalsIgnoreCase(fmt)
                || (bytes.length > 0 && bytes[0] == '[');
        if (isLrc) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        if (bytes.length < 8) {
            return "";
        }
        byte[] xored = new byte[bytes.length - 4];
        for (int i = 4; i < bytes.length; i++) {
            xored[i - 4] = (byte) (bytes[i] ^ KRC_KEY[(i - 4) % 16]);
        }
        boolean zlib = (xored[0] & 0xFF) == 0x78;
        int offset = zlib ? 2 : 0;
        Inflater inflater = new Inflater(true);
        inflater.setInput(xored, offset, xored.length - offset);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        try {
            while (!inflater.finished()) {
                int read = inflater.inflate(buffer);
                if (read == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        break;
                    }
                } else {
                    out.write(buffer, 0, read);
                }
            }
        } finally {
            inflater.end();
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    /**
     * KRC → 逐行 LRC：{@code [start,duration]text<offset,dur,0>} 只保留行时间轴。
     */
    static String krcToLrc(String text) {
        int offsetMs = 0;
        java.util.regex.Matcher offsetMatcher =
                java.util.regex.Pattern.compile("\\[offset:\\s*([+-]?\\d+)\\s*]").matcher(text);
        if (offsetMatcher.find()) {
            offsetMs = Integer.parseInt(offsetMatcher.group(1));
        }
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\\r?\\n")) {
            java.util.regex.Matcher matcher =
                    java.util.regex.Pattern.compile("^\\[(\\d+),(\\d+)](.*)$").matcher(line);
            if (!matcher.find()) {
                continue;
            }
            long start = Long.parseLong(matcher.group(1)) + offsetMs;
            String body = matcher.group(3).replaceAll("<\\d+,\\d+,\\d+>", "").trim();
            if (body.isEmpty() || "//".equals(body)) {
                continue;
            }
            sb.append('[').append(clock(start)).append(']').append(body).append('\n');
        }
        return sb.toString().trim();
    }

    private static String clock(long ms) {
        long value = Math.max(0, ms);
        long milli = value % 1000;
        long seconds = value / 1000;
        return String.format("%02d:%02d.%03d", seconds / 60, seconds % 60, milli);
    }

    // ------------------------------------------------------------------ 签名请求

    /**
     * 概念版 Android 签名 GET（gateway.kugou.com）。
     */
    public JsonNode gatewayGet(String path, Map<String, String> extra, String cookie, String router) {
        return signedGet(GATEWAY, path, extra, cookie, router);
    }

    /**
     * 概念版 Android 签名的通用 GET（可指定域名，例如 kugouvip.kugou.com）。
     */
    public JsonNode signedGet(String baseUrl, String path, Map<String, String> extra,
                              String cookie, String router) {
        Map<String, String> params = signedParams(cookie);
        if (extra != null) {
            params.putAll(extra);
        }
        String signature = md5(LITE_SIGN + sortedKv(params) + LITE_SIGN);
        params.put("signature", signature);
        String url = (baseUrl == null || baseUrl.isBlank() ? GATEWAY : baseUrl)
                + path + "?" + query(params);
        Map<String, String> headers = androidHeaders(params, cookie);
        if (router != null && !router.isBlank()) {
            headers.put("x-router", router);
        }
        return getJson(url, UA_ANDROID, headers, cookie);
    }

    /**
     * 概念版 Android 签名的无 body POST（签到类接口把参数放在 query 上）。
     */
    public JsonNode gatewayPostForm(String path, Map<String, String> extra, String cookie,
                                    String router) {
        Map<String, String> params = signedParams(cookie);
        if (extra != null) {
            params.putAll(extra);
        }
        String signature = md5(LITE_SIGN + sortedKv(params) + LITE_SIGN);
        params.put("signature", signature);
        String url = GATEWAY + path + "?" + query(params);
        Map<String, String> headers = androidHeaders(params, cookie);
        headers.put("Content-Type", "application/x-www-form-urlencoded");
        if (router != null && !router.isBlank()) {
            headers.put("x-router", router);
        }
        return postJson(url, "", headers, cookie);
    }

    /**
     * 概念版 Android 签名 POST（gateway.kugou.com），body 参与签名。
     */
    public JsonNode gatewayPost(String path, ObjectNode body, Map<String, String> extra,
                                String cookie, String router) {
        Map<String, String> params = signedParams(cookie);
        if (extra != null) {
            params.putAll(extra);
        }
        String data = body == null ? "" : body.toString();
        String signature = md5(LITE_SIGN + sortedKv(params) + data + LITE_SIGN);
        params.put("signature", signature);
        String url = GATEWAY + path + "?" + query(params);
        Map<String, String> headers = androidHeaders(params, cookie);
        if (router != null && !router.isBlank()) {
            headers.put("x-router", router);
        }
        headers.put("Content-Type", "application/json");
        return postJson(url, data, headers, cookie);
    }

    /**
     * 网页版签名 GET（wwwapi.kugou.com），盐为 SIGN_KEY。
     */
    public JsonNode webSignedGet(String path, Map<String, String> params, String cookie) {
        String signature = md5(SIGN_KEY + sortedKv(params) + SIGN_KEY);
        params.put("signature", signature);
        String url = WWW_API + path + "?" + query(params);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Referer", "https://www.kugou.com/");
        return getJson(url, UA_PC, headers, cookie);
    }

    /**
     * 匿名概念版请求参数：dfid/mid/uuid/appid/clientver/clienttime，登录后追加 token/userid。
     */
    private Map<String, String> signedParams(String cookie) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("dfid", firstNonBlank(cookieValue(cookie, "dfid"), "-"));
        params.put("mid", firstNonBlank(cookieValue(cookie, "KUGOU_API_MID"),
                cookieValue(cookie, "mid"), deviceMid()));
        params.put("uuid", "-");
        params.put("appid", APP_ID);
        params.put("clientver", CLIENT_VER);
        params.put("clienttime", String.valueOf(System.currentTimeMillis() / 1000));
        String token = cookieValue(cookie, "token");
        String userId = firstNonBlank(cookieValue(cookie, "userid"), cookieValue(cookie, "KugooID"));
        if (!token.isBlank()) {
            params.put("token", token);
        }
        if (!userId.isBlank() && !"0".equals(userId)) {
            params.put("userid", userId);
        }
        return params;
    }

    private Map<String, String> androidHeaders(Map<String, String> params, String cookie) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("dfid", params.getOrDefault("dfid", "-"));
        headers.put("mid", params.getOrDefault("mid", "-"));
        headers.put("clienttime", params.getOrDefault("clienttime", ""));
        headers.put("kg-rc", "1");
        headers.put("kg-thash", "5d816a0");
        headers.put("kg-rec", "1");
        headers.put("kg-rf", "B9EDA08A64250DEFFBCADDEE00F8F25F");
        String token = cookieValue(cookie, "token");
        String userId = firstNonBlank(cookieValue(cookie, "userid"), cookieValue(cookie, "KugooID"));
        if (!token.isBlank() && !userId.isBlank()) {
            headers.put("Cookie", "token=" + token + "; userid=" + userId);
        }
        return headers;
    }

    /** 参数按 key 排序后拼成 {@code k=v} 串（签名输入）。 */
    static String sortedKv(Map<String, String> params) {
        List<String> keys = new ArrayList<>(params.keySet());
        keys.sort(String::compareTo);
        StringBuilder sb = new StringBuilder();
        for (String key : keys) {
            String value = params.get(key);
            sb.append(key).append('=').append(value == null ? "" : value);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ HTTP

    private JsonNode getJson(String url, String ua, Map<String, String> extraHeaders, String cookie) {
        try {
            Request.Builder builder = new Request.Builder().url(url).get()
                    .header("User-Agent", ua);
            if (extraHeaders != null) {
                extraHeaders.forEach(builder::header);
            }
            if (cookie != null && !cookie.isBlank() && extraHeaders != null
                    && !extraHeaders.containsKey("Cookie")) {
                builder.header("Cookie", cookie);
            }
            return execute(builder.build(), url);
        } catch (Exception e) {
            log.debug("酷狗接口请求失败 {} : {}", safeUrl(url), e.getMessage());
            return null;
        }
    }

    private JsonNode postJson(String url, String body, Map<String, String> headers, String cookie) {
        try {
            Request.Builder builder = new Request.Builder().url(url)
                    .post(RequestBody.create(body == null ? "" : body, JSON));
            if (headers != null) {
                headers.forEach(builder::header);
            }
            if (cookie != null && !cookie.isBlank() && headers != null
                    && !headers.containsKey("Cookie")) {
                builder.header("Cookie", cookie);
            }
            return execute(builder.build(), url);
        } catch (Exception e) {
            log.debug("酷狗接口请求失败 {} : {}", safeUrl(url), e.getMessage());
            return null;
        }
    }

    private JsonNode execute(Request request, String url) throws Exception {
        try (Response response = HTTP.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                log.debug("酷狗接口 HTTP {} {}", response.code(), safeUrl(url));
                return null;
            }
            String text = response.body().string();
            if (text == null || text.isBlank()) {
                return null;
            }
            String trimmed = text.trim();
            int objectStart = trimmed.indexOf('{');
            int arrayStart = trimmed.indexOf('[');
            if (objectStart < 0 && arrayStart < 0) {
                return null;
            }
            if (objectStart < 0 || (arrayStart >= 0 && arrayStart < objectStart)) {
                trimmed = trimmed.substring(arrayStart);
            } else if (objectStart > 0) {
                trimmed = trimmed.substring(objectStart);
            }
            return MAPPER.readTree(trimmed);
        }
    }

    // ------------------------------------------------------------------ 工具

    public static String md5(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("MD5 不可用", e);
        }
    }

    private String deviceMid() {
        String cached = deviceMid;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (deviceMid == null) {
                StringBuilder guid = new StringBuilder();
                for (int i = 0; i < 32; i++) {
                    guid.append(Integer.toHexString(
                            ThreadLocalRandom.current().nextInt(16)));
                }
                // 酷狗 mid 是 md5(guid) 的十进制表示
                deviceMid = new BigInteger(md5(guid.toString()), 16).toString();
            }
            return deviceMid;
        }
    }

    /** Cookie 中取某个键（大小写不敏感）。 */
    public static String cookieValue(String cookie, String name) {
        if (cookie == null || cookie.isBlank() || name == null) {
            return "";
        }
        for (String part : cookie.split(";")) {
            int index = part.indexOf('=');
            if (index <= 0) {
                continue;
            }
            if (part.substring(0, index).trim().equalsIgnoreCase(name)) {
                return part.substring(index + 1).trim();
            }
        }
        return "";
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String query(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(encode(entry.getKey())).append('=')
                    .append(encode(entry.getValue() == null ? "" : entry.getValue()));
        }
        return sb.toString();
    }

    /** 日志里去掉查询串，避免把 token/signature 打出去。 */
    private static String safeUrl(String url) {
        int index = url == null ? -1 : url.indexOf('?');
        return index > 0 ? url.substring(0, index) : String.valueOf(url);
    }
}
