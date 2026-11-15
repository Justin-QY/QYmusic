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

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * QQ 音乐匿名接口客户端。
 *
 * <p>关键点（参考 MeloX-Android 的实测结论）：
 * <ul>
 *   <li>搜索必须用 {@code comm.ct = 11}，{@code ct = 24} 会返回空壳</li>
 *   <li>节点固定为 {@code req_0}</li>
 *   <li>{@code searchid} 每次必须唯一，否则网关返回 2001 + 空列表</li>
 *   <li>搜索参数需要 {@code grp = true}</li>
 *   <li>请求头需要浏览器 UA 与 {@code Referer: https://y.qq.com/}</li>
 * </ul>
 * 曲库（搜索/榜单/歌单/歌手/歌词）全部可匿名访问；播放 vkey 需要登录 Cookie。</p>
 */
@Slf4j
@Component
public class QqMusicClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final String HOST = "https://u.y.qq.com/cgi-bin/musicu.fcg";
    private static final Random RANDOM = new Random();

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    /**
     * 通用 POST，返回 req_0.data；失败返回 null。
     *
     * @param ct 搜索用 11，其余用 24
     */
    public JsonNode post(int ct, long cv, String module, String method, ObjectNode param, String cookie) {
        try {
            ObjectNode payload = MAPPER.createObjectNode();
            ObjectNode comm = payload.putObject("comm");
            comm.put("ct", ct);
            comm.put("cv", cv);
            comm.put("platform", "yqq.json");
            comm.put("chid", "0");
            comm.put("uin", 0);
            comm.put("g_tk", 0);
            comm.put("g_tk_new_20200303", 0);
            comm.put("format", "json");
            comm.put("inCharset", "utf-8");
            comm.put("outCharset", "utf-8");
            comm.put("notice", 0);
            comm.put("need_new_code", 1);

            ObjectNode req = payload.putObject("req_0");
            req.put("module", module);
            req.put("method", method);
            req.set("param", param);

            Request.Builder builder = new Request.Builder()
                    .url(HOST)
                    .header("User-Agent", UA)
                    .header("Referer", "https://y.qq.com/")
                    .post(RequestBody.create(payload.toString(), JSON));
            if (cookie != null && !cookie.isBlank()) {
                builder.header("Cookie", cookie);
            }
            try (Response response = CLIENT.newCall(builder.build()).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    return null;
                }
                JsonNode root = MAPPER.readTree(response.body().string());
                JsonNode reqNode = root.path("req_0");
                if (reqNode.path("code").asInt(-1) != 0) {
                    log.warn("QQ接口错误 {} {} root={} req={} msg={}", module, method,
                            root.path("code").asInt(), reqNode.path("code").asInt(),
                            reqNode.path("message").asText(""));
                    return null;
                }
                return reqNode.path("data");
            }
        } catch (Exception e) {
            log.warn("QQ接口请求失败 {} {}: {}", module, method, e.getMessage());
            return null;
        }
    }

    private String newSearchId() {
        // QQ 网关会拒绝以时间戳开头的 searchid（返回 2001 + 空列表），
        // 使用 17 位随机数字即可稳定通过。
        long value = 80_000_000_000_000_000L + (long) (RANDOM.nextDouble() * 10_000_000_000_000_000L);
        return String.valueOf(value);
    }

    /**
     * 搜索 type：0 歌曲，1 歌手，2 专辑，3 歌单
     */
    public JsonNode search(String keyword, int type, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(50, Math.max(1, size));
        ObjectNode param = MAPPER.createObjectNode();
        param.put("searchid", newSearchId());
        param.put("query", keyword == null ? "" : keyword.trim());
        param.put("search_type", type);
        param.put("num_per_page", safeSize);
        param.put("page_num", safePage);
        param.put("highlight", false);
        param.put("grp", true);
        param.set("selectors", MAPPER.createObjectNode());
        param.set("vec_selectors", MAPPER.createArrayNode());

        JsonNode data = post(11, 0, "music.search.SearchCgiService",
                "DoSearchForQQMusicDesktop", param, null);
        log.info("QQ搜索 keyword={} searchid={} first={}", keyword,
                param.path("searchid").asText(), data == null ? "null" : "ok");
        // 搜索偶发返回空壳，重试一次
        if (data == null || isEmptySearch(data)) {
            param.put("searchid", newSearchId());
            data = post(11, 0, "music.search.SearchCgiService",
                    "DoSearchForQQMusicDesktop", param, null);
            log.info("QQ搜索重试 keyword={} searchid={} second={}", keyword,
                    param.path("searchid").asText(), data == null ? "null" : "ok");
        }
        return data;
    }

    private boolean isEmptySearch(JsonNode data) {
        JsonNode body = data.path("body");
        int total = 0;
        for (String key : new String[]{"song", "singer", "album", "songlist"}) {
            total += body.path(key).path("list").size();
            total += body.path(key).path("itemlist").size();
        }
        return total == 0;
    }

    /**
     * 搜索兜底：smartbox 联想接口。musicu 搜索被限流时会返回空列表。
     */
    public JsonNode smartSearch(String keyword) {
        try {
            String url = "https://c.y.qq.com/splcloud/fcgi-bin/smartbox_new.fcg?key="
                    + java.net.URLEncoder.encode(keyword == null ? "" : keyword,
                    java.nio.charset.StandardCharsets.UTF_8)
                    + "&format=json&inCharset=utf8&outCharset=utf-8";
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", UA)
                    .header("Referer", "https://y.qq.com/")
                    .build();
            try (Response response = CLIENT.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    return null;
                }
                JsonNode root = MAPPER.readTree(response.body().string());
                return root.path("data").path("song").path("itemlist");
            }
        } catch (Exception e) {
            log.warn("QQ联想搜索失败: {}", e.getMessage());
            return null;
        }
    }

    public JsonNode toplist(long topId, int offset, int num) {
        ObjectNode param = MAPPER.createObjectNode();
        param.put("topId", topId);
        param.put("offset", Math.max(0, offset));
        param.put("num", num);
        param.put("withTags", true);
        return post(24, 4_747_474, "music.musicToplist.Toplist", "GetDetail", param, null);
    }

    public JsonNode playlistDetail(long disstid, int offset, int num) {
        return playlistDetail(disstid, offset, num, null);
    }

    public JsonNode playlistDetail(long disstid, int offset, int num, String cookie) {
        ObjectNode param = MAPPER.createObjectNode();
        param.put("disstid", disstid);
        param.put("dirid", 0);
        param.put("tag", 1);
        param.put("song_begin", Math.max(0, offset));
        param.put("song_num", num);
        param.put("userinfo", 1);
        return post(24, 4_747_474, "music.srfDissInfo.DissInfo", "CgiGetDiss", param, cookie);
    }

    /**
     * 当前登录用户的歌单（含「我喜欢」）
     */
    public JsonNode userPlaylists(String cookie, String uin) {
        ObjectNode param = MAPPER.createObjectNode();
        param.put("uin", uin);
        return post(24, 4_747_474, "music.musicasset.PlaylistBaseRead",
                "GetPlaylistByUin", param, cookie);
    }

    /**
     * 登录用户资料
     */
    public JsonNode profile(String cookie, String uin, String musicKey) {
        try {
            long gtk = hash33(musicKey == null ? "" : musicKey);
            String url = "https://c6.y.qq.com/rsc/fcgi-bin/fcg_get_profile_homepage.fcg"
                    + "?g_tk=" + gtk + "&format=json&uin=" + uin;
            Request.Builder builder = new Request.Builder()
                    .url(url)
                    .header("User-Agent", UA)
                    .header("Referer", "https://y.qq.com/");
            if (cookie != null && !cookie.isBlank()) {
                builder.header("Cookie", cookie);
            }
            try (Response response = CLIENT.newCall(builder.build()).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    return null;
                }
                return MAPPER.readTree(response.body().string());
            }
        } catch (Exception e) {
            log.warn("QQ资料接口失败: {}", e.getMessage());
            return null;
        }
    }

    /** QQ 的 g_tk 算法 */
    public static long hash33(String value) {
        long hash = 5381L;
        for (char c : value.toCharArray()) {
            hash += (hash << 5) + c;
            hash &= 0xFFFFFFFFL;
        }
        return hash & 0x7FFFFFFFL;
    }

    public JsonNode singerSongs(String singerMid, int offset, int num) {
        ObjectNode param = MAPPER.createObjectNode();
        param.put("singerMid", singerMid);
        param.put("begin", Math.max(0, offset));
        param.put("num", num);
        param.put("order", 1);
        return post(24, 4_747_474, "musichall.song_list_server", "GetSingerSongList", param, null);
    }

    /**
     * 歌词返回 base64 编码的 LRC，调用方自行解码。
     */
    public String lyric(String songMid, long songId) {
        ObjectNode param = MAPPER.createObjectNode();
        param.put("songMID", songMid);
        param.put("songID", songId);
        JsonNode data = post(24, 4_747_474, "music.musichallSong.PlayLyricInfo",
                "GetPlayLyricInfo", param, null);
        String lyric = data == null ? "" : data.path("lyric").asText("");
        if (lyric.isBlank()) {
            return "";
        }
        try {
            return new String(Base64.getDecoder().decode(lyric), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return lyric;
        }
    }

    /**
     * 取播放地址；匿名时 purl 为空，需要传入已登录 Cookie。
     */
    public String vkey(String songMid, String cookie, String quality) {
        String prefix = quality != null && quality.contains("flac") ? "F000" : "M500";
        String ext = prefix.startsWith("F") ? ".flac" : ".mp3";
        ObjectNode param = MAPPER.createObjectNode();
        param.put("guid", "10000");
        param.putArray("songmid").add(songMid);
        param.putArray("songtype").add(0);
        param.put("uin", "0");
        param.put("loginflag", 1);
        param.put("platform", "20");
        param.putArray("filename").add(prefix + songMid + ext);
        JsonNode data = post(24, 4_747_474, "music.vkey.GetVkey", "UrlGetVkey", param, cookie);
        if (data == null) {
            return "";
        }
        JsonNode info = data.path("midurlinfo").path(0);
        String purl = info.path("purl").asText("");
        if (purl.isBlank()) {
            return "";
        }
        String sip = data.path("sip").path(0).asText("http://aqqmusic.tc.qq.com/");
        return sip + purl;
    }
}
