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
        if (creator == null || creator.isMissingNode() || creator.isNull()) {
            return Result.error("QQ 登录已失效，请重新登录");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        try {
            data.put("userId", Long.parseLong(uin));
        } catch (Exception e) {
            data.put("userId", 0L);
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
        JsonNode data = qqMusicClient.userPlaylists(cookie, uin);
        JsonNode array = data == null ? null : data.path("v_playlist");
        List<PlayList> list = new ArrayList<>();
        if (array != null && array.isArray()) {
            for (JsonNode node : array) {
                PlayList playlist = qqCatalogService.mapPlaylist(node);
                if (playlist.getId() != null && playlist.getId() > 0) {
                    list.add(playlist);
                }
            }
        }
        return Result.success(list);
    }

    public Result<List<Music>> liked(String cookie, int limit) {
        if (!StringUtils.hasText(cookie)) {
            return Result.error("请先登录 QQ 音乐");
        }
        String uin = cookieValue(cookie, "uin").replaceAll("\\D", "");
        JsonNode data = qqMusicClient.userPlaylists(cookie, uin);
        JsonNode array = data == null ? null : data.path("v_playlist");
        long likedId = 0L;
        if (array != null && array.isArray()) {
            for (JsonNode node : array) {
                int dirId = node.path("dirId").asInt(node.path("dirid").asInt(0));
                if (dirId == 201) {
                    likedId = node.path("dissid").asLong(node.path("tid").asLong(0));
                    break;
                }
            }
        }
        if (likedId <= 0) {
            return Result.success(List.of());
        }
        JsonNode detail = qqMusicClient.playlistDetail(likedId, 0, Math.max(1, limit), cookie);
        JsonNode songs = detail == null ? null : detail.path("songlist");
        List<Music> list = new ArrayList<>();
        if (songs != null && songs.isArray()) {
            for (JsonNode node : songs) {
                Music music = qqCatalogService.mapSong(node);
                if (music != null) {
                    list.add(music);
                }
            }
        }
        return Result.success(list);
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
