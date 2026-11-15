package com.qymusic.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.qymusic.entity.Music;
import com.qymusic.entity.PlayList;
import com.qymusic.result.Result;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 酷我登录用户数据（云端模块）。
 *
 * <p>酷我网页登录后 Cookie 里带 {@code userid} 与 {@code kw_token}，
 * 用户歌单接口走 nplserver，需要把 token 同时放在 {@code loginSid} 与 {@code csrf}。</p>
 */
@Slf4j
@Service
public class KuwoUserService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    public Result<Map<String, Object>> account(String cookie) {
        if (!StringUtils.hasText(cookie)) {
            return Result.error("请先登录酷我音乐");
        }
        String uid = cookieValue(cookie, "userid");
        if (uid.isBlank()) {
            uid = cookieValue(cookie, "uid");
        }
        if (uid.isBlank()) {
            return Result.error("酷我登录信息不完整，请重新登录");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        try {
            data.put("userId", Long.parseLong(uid));
        } catch (Exception e) {
            data.put("userId", 0L);
        }
        data.put("nickname", "酷我用户 " + uid);
        data.put("avatarUrl", "");
        return Result.success(data);
    }

    public Result<List<PlayList>> playlists(String cookie) {
        String uid = cookieValue(cookie, "userid");
        String token = cookieValue(cookie, "kw_token");
        if (uid.isBlank()) {
            return Result.error("请先登录酷我音乐");
        }
        String url = HttpUrl.parse("https://kuwo.cn/api/www/user/playList").newBuilder()
                .addQueryParameter("uid", uid)
                .addQueryParameter("pn", "1")
                .addQueryParameter("rn", "300")
                .addQueryParameter("httpsStatus", "1")
                .build().toString();
        JsonNode root = get(url, cookie);
        JsonNode data = root == null ? null : root.path("data");
        JsonNode array = data == null ? null : data.path("list");
        List<PlayList> list = new ArrayList<>();
        if (array != null && array.isArray()) {
            for (JsonNode node : array) {
                PlayList playlist = new PlayList();
                long id = node.path("pid").asLong(node.path("id").asLong(0));
                if (id <= 0) {
                    continue;
                }
                playlist.setId(id);
                playlist.setName(node.path("name").asText(""));
                String pic = node.path("img").asText(node.path("pic").asText(""));
                playlist.setImg(pic);
                playlist.setImg700(pic);
                playlist.setTotal(node.path("total").asInt(node.path("count").asInt(0)));
                playlist.setUname(node.path("username").asText(""));
                playlist.setDesc(node.path("desc").asText(""));
                list.add(playlist);
            }
        }
        return Result.success(list);
    }

    public Result<List<Music>> liked(String cookie, int limit) {
        Result<List<PlayList>> playlistsResult = playlists(cookie);
        if (playlistsResult.getData() == null) {
            return Result.error("请先登录酷我音乐");
        }
        PlayList liked = null;
        for (PlayList playlist : playlistsResult.getData()) {
            if (playlist.getName() != null
                    && (playlist.getName().contains("我喜欢") || playlist.getName().contains("喜欢"))) {
                liked = playlist;
                break;
            }
        }
        if (liked == null) {
            return Result.success(List.of());
        }
        String uid = cookieValue(cookie, "userid");
        String token = cookieValue(cookie, "kw_token");
        String url = HttpUrl.parse("http://nplserver.kuwo.cn/pl.svc").newBuilder()
                .addQueryParameter("op", "getlistinfo")
                .addQueryParameter("pid", String.valueOf(liked.getId()))
                .addQueryParameter("pn", "0")
                .addQueryParameter("rn", String.valueOf(Math.max(1, limit)))
                .addQueryParameter("encode", "utf8")
                .addQueryParameter("keyset", "pl2012")
                .addQueryParameter("identity", "kuwo")
                .addQueryParameter("pcmp4", "1")
                .addQueryParameter("vipver", "MUSIC_9.0.5.0_W1")
                .addQueryParameter("newver", "1")
                .build().toString();
        JsonNode root = get(url, cookie);
        JsonNode array = root == null ? null : root.path("musiclist");
        List<Music> list = new ArrayList<>();
        if (array != null && array.isArray()) {
            for (JsonNode node : array) {
                Music music = toMusic(node);
                if (music != null) {
                    list.add(music);
                }
            }
        }
        return Result.success(list);
    }

    private Music toMusic(JsonNode node) {
        long id = node.path("rid").asLong(node.path("musicrid").asLong(0));
        if (id <= 0) {
            String raw = node.path("musicrid").asText("").replace("MUSIC_", "");
            try {
                id = Long.parseLong(raw);
            } catch (Exception ignored) {
            }
        }
        if (id <= 0) {
            return null;
        }
        Music music = new Music();
        music.setId(id);
        music.setSource("kw");
        music.setName(node.path("name").asText(""));
        music.setArtist(node.path("artist").asText(""));
        String pic = node.path("albumpic").asText(node.path("pic").asText(""));
        music.setPic(pic);
        music.setPic120(pic);
        music.setAlbum(node.path("album").asText(""));
        music.setDuration(node.path("duration").asInt(0));
        music.setHasLossless(true);
        return music;
    }

    private JsonNode get(String url, String cookie) {
        try {
            Request.Builder builder = new Request.Builder()
                    .url(url)
                    .header("User-Agent", UA)
                    .header("Referer", "https://www.kuwo.cn/");
            if (cookie != null && !cookie.isBlank()) {
                builder.header("Cookie", cookie);
            }
            String token = cookieValue(cookie, "kw_token");
            if (!token.isBlank()) {
                builder.header("csrf", token);
            }
            try (Response response = CLIENT.newCall(builder.build()).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    return null;
                }
                String body = response.body().string();
                int start = body.indexOf('{');
                if (start > 0) {
                    body = body.substring(start);
                }
                return MAPPER.readTree(body);
            }
        } catch (Exception e) {
            log.warn("酷我用户接口失败: {}", e.getMessage());
            return null;
        }
    }

    private JsonNode findArray(JsonNode root, String... keys) {
        for (String key : keys) {
            JsonNode node = root.path(key);
            if (node.isArray()) {
                return node;
            }
        }
        return null;
    }

    private String cookieValue(String cookie, String name) {
        if (cookie == null) {
            return "";
        }
        for (String part : cookie.split(";")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2 && kv[0].trim().equalsIgnoreCase(name)) {
                return kv[1].trim();
            }
        }
        return "";
    }
}
