package com.qymusic.service;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.qymusic.api.QqMusicClient;
import com.qymusic.entity.Music;
import com.qymusic.entity.PlayList;
import com.qymusic.result.Result;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * QQ 音乐登录用户数据（云端模块）。
 *
 * <p>QQ 的播放 vkey 必须登录，因此云端登录同时用于曲库播放。
 * Cookie 由客户端保存在本机，随请求传入，服务端不落库。</p>
 */
@Slf4j
@Service
public class QqUserService {

    @Resource
    private QqMusicClient qqMusicClient;

    @Resource
    private QqCatalogService qqCatalogService;

    public Result<Map<String, Object>> account(String cookie) {
        if (!StringUtils.hasText(cookie)) {
            return Result.error("请先登录 QQ 音乐");
        }
        String uin = cookieValue(cookie, "uin").replaceAll("\\D", "");
        String musicKey = cookieValue(cookie, "qqmusic_key");
        if (musicKey.isBlank()) {
            musicKey = cookieValue(cookie, "qm_keyst");
        }
        if (uin.isBlank()) {
            return Result.error("QQ 登录信息不完整，请重新登录");
        }
        JsonNode root = qqMusicClient.profile(cookie, uin, musicKey);
        JsonNode creator = root == null ? null : root.path("data").path("creator");
        Map<String, Object> data = new LinkedHashMap<>();
        try {
            data.put("userId", Long.parseLong(uin));
        } catch (Exception e) {
            data.put("userId", 0L);
        }
        // 资料接口偶发失败时不要直接判定登录失效：只要 uin 在，登录态仍然有效，
        // 先返回兜底昵称，播放/歌单接口仍可继续使用
        if (creator == null || creator.isMissingNode() || creator.isNull()) {
            log.info("QQ 资料接口无 creator，使用兜底账号信息 uin={}", uin);
            data.put("nickname", "QQ用户");
            data.put("avatarUrl", "");
            return Result.success(data);
        }
        data.put("nickname", creator.path("nick").asText(
                creator.path("nickname").asText("QQ 用户")));
        data.put("avatarUrl", creator.path("headpic").asText(
                creator.path("hostpic").asText("")));
        return Result.success(data);
    }

    public Result<List<PlayList>> playlists(String cookie) {
        if (!StringUtils.hasText(cookie)) {
            return Result.error("请先登录 QQ 音乐");
        }
        String uin = cookieValue(cookie, "uin").replaceAll("\\D", "");
        if (uin.isBlank()) {
            return Result.error("QQ 登录信息不完整，请重新登录");
        }
        JsonNode data = qqMusicClient.userCreatedDiss(cookie, uin);
        JsonNode array = data == null ? null : data.path("data").path("disslist");
        List<PlayList> list = new ArrayList<>();
        if (array != null && array.isArray()) {
            for (JsonNode node : array) {
                long id = node.path("tid").asLong(node.path("dissid").asLong(0));
                String name = node.path("diss_name").asText(node.path("name").asText(""));
                if (id <= 0 || name.isBlank()) {
                    continue;
                }
                PlayList playlist = new PlayList();
                playlist.setId(id);
                playlist.setName(name);
                String cover = node.path("diss_cover").asText("");
                if (cover.startsWith("?")) {
                    cover = "";
                }
                playlist.setImg(cover);
                playlist.setImg700(cover);
                playlist.setTotal(node.path("song_cnt").asInt(node.path("trackCount").asInt(0)));
                playlist.setListencnt(node.path("listen_num").asLong(0));
                playlist.setUname("QQ音乐");
                list.add(playlist);
            }
        }
        return Result.success(list);
    }

    public Result<List<Music>> liked(String cookie, int limit) {
        if (!StringUtils.hasText(cookie)) {
            return Result.error("请先登录 QQ 音乐");
        }
        List<String> mids = qqMusicClient.favoriteMids(cookie);
        List<Music> list = new ArrayList<>();
        int max = Math.max(1, limit);
        for (String mid : mids) {
            if (list.size() >= max) {
                break;
            }
            JsonNode song = qqMusicClient.songByMidWeb(mid);
            if (song == null) {
                continue;
            }
            Music music = new Music();
            music.setSource("qq");
            music.setSourceId(mid);
            music.setId(QqCatalogService.hash64(mid));
            music.setName(song.path("name").asText(""));
            music.setArtist(joinSingers(song.path("singer")));
            String albumMid = song.path("album").path("mid").asText("");
            music.setAlbum(song.path("album").path("name").asText(""));
            music.setAlbumId(albumMid.isBlank() ? 0L : QqCatalogService.hash64(albumMid));
            String pic = albumMid.isBlank() ? ""
                    : "https://y.gtimg.cn/music/photo_new/T002R300x300M000" + albumMid + ".jpg";
            music.setPic(pic);
            music.setPic120(pic);
            music.setAlbumPic(pic);
            music.setDuration(song.path("interval").asInt(0));
            music.setHasLossless(true);
            if (!music.getName().isBlank()) {
                list.add(music);
            }
        }
        return Result.success(list);
    }

    private String joinSingers(JsonNode singers) {
        if (singers == null || !singers.isArray()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode singer : singers) {
            String name = singer.path("name").asText("");
            if (name.isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append(name);
        }
        return sb.toString();
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
