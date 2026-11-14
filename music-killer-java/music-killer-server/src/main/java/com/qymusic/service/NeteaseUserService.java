package com.qymusic.service;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.qymusic.api.NetEaseService;
import com.qymusic.entity.Artist;
import com.qymusic.entity.Music;
import com.qymusic.entity.PlayList;
import com.qymusic.result.Result;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 网易云登录用户数据：账号信息、收藏歌单、收藏歌手、我喜欢的音乐。
 *
 * <p>Cookie 由客户端登录后本地保存，每次请求随参数传入，服务端不落地保存。</p>
 */
@Slf4j
@Service
public class NeteaseUserService {

    @Resource
    private NetEaseService netEaseService;

    @Resource
    private NeteaseCatalogService catalogService;

    /**
     * 当前账号信息
     */
    public Result<Map<String, Object>> account(String cookie) {
        if (!StringUtils.hasText(cookie)) {
            return Result.error("请先登录网易云账号");
        }
        try {
            JsonNode root = netEaseService.account(cookie);
            JsonNode profile = root == null ? null : root.path("profile");
            if (profile == null || profile.isMissingNode() || profile.isNull()) {
                return Result.error("登录已失效，请重新登录");
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("userId", profile.path("userId").asLong(0));
            data.put("nickname", profile.path("nickname").asText(""));
            data.put("avatarUrl", profile.path("avatarUrl").asText(""));
            data.put("signature", profile.path("signature").asText(""));
            return Result.success(data);
        } catch (Exception e) {
            log.warn("获取网易云账号失败: {}", e.getMessage());
            return Result.error("登录已失效，请重新登录");
        }
    }

    /**
     * 收藏的歌单
     */
    public Result<List<PlayList>> playlists(String cookie, long uid) {
        if (!StringUtils.hasText(cookie) || uid <= 0) {
            return Result.error("请先登录网易云账号");
        }
        try {
            JsonNode root = netEaseService.userPlaylists(cookie, uid, 1000, 0);
            JsonNode array = root == null ? null : root.path("playlist");
            List<PlayList> list = new ArrayList<>();
            if (array != null && array.isArray()) {
                for (JsonNode node : array) {
                    list.add(toPlayList(node));
                }
            }
            return Result.success(list);
        } catch (Exception e) {
            log.warn("获取收藏歌单失败: {}", e.getMessage());
            return Result.error("获取歌单失败，请稍后重试");
        }
    }

    /**
     * 收藏的歌手
     */
    public Result<List<Artist>> artists(String cookie) {
        if (!StringUtils.hasText(cookie)) {
            return Result.error("请先登录网易云账号");
        }
        try {
            JsonNode root = netEaseService.artistSublist(cookie);
            JsonNode array = root == null ? null : root.path("data");
            List<Artist> list = new ArrayList<>();
            if (array != null && array.isArray()) {
                for (JsonNode node : array) {
                    list.add(toArtist(node));
                }
            }
            return Result.success(list);
        } catch (Exception e) {
            log.warn("获取收藏歌手失败: {}", e.getMessage());
            return Result.error("获取歌手失败，请稍后重试");
        }
    }

    /**
     * 我喜欢的音乐（分页）
     */
    public Result<List<Music>> liked(String cookie, long uid, int pageNo, int pageSize) {
        if (!StringUtils.hasText(cookie) || uid <= 0) {
            return Result.error("请先登录网易云账号");
        }
        try {
            JsonNode root = netEaseService.likedIds(cookie, uid);
            JsonNode idsNode = root == null ? null : root.path("ids");
            if (idsNode == null || !idsNode.isArray()) {
                return Result.success(List.of());
            }
            List<Long> all = new ArrayList<>();
            for (JsonNode id : idsNode) {
                all.add(id.asLong(0));
            }
            int from = Math.max(0, (pageNo - 1) * pageSize);
            int to = Math.min(all.size(), from + pageSize);
            if (from >= to) {
                return Result.success(List.of());
            }
            return Result.success(catalogService.songsByIds(all.subList(from, to)));
        } catch (Exception e) {
            log.warn("获取喜欢的音乐失败: {}", e.getMessage());
            return Result.error("获取喜欢的音乐失败，请稍后重试");
        }
    }

    private PlayList toPlayList(JsonNode node) {
        PlayList playlist = new PlayList();
        playlist.setId(node.path("id").asLong(0));
        playlist.setName(node.path("name").asText(""));
        String cover = node.path("coverImgUrl").asText("");
        playlist.setImg(cover);
        playlist.setImg700(cover);
        playlist.setTotal(node.path("trackCount").asInt(0));
        playlist.setListencnt(node.path("playCount").asLong(0));
        playlist.setUname(node.path("creator").path("nickname").asText(""));
        playlist.setDesc(node.path("description").asText(""));
        playlist.setTag("");
        return playlist;
    }

    private Artist toArtist(JsonNode node) {
        Artist artist = new Artist();
        long id = node.path("id").asLong(0);
        artist.setId((int) Math.min(id, Integer.MAX_VALUE));
        artist.setName(node.path("name").asText(""));
        String pic = node.path("picUrl").asText("");
        artist.setPic(pic);
        artist.setPic120(pic);
        artist.setPic300(pic);
        artist.setAlbumNum(node.path("albumSize").asInt(0));
        artist.setMusicNum(node.path("musicSize").asInt(0));
        artist.setArtistFans(node.path("fansCount").asInt(0));
        return artist;
    }
}
