package com.qymusic.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import com.qymusic.api.QqMusicClient;
import com.qymusic.data.PageResponse;
import com.qymusic.entity.Artist;
import com.qymusic.entity.Music;
import com.qymusic.entity.MusicRank;
import com.qymusic.entity.PlayList;
import com.qymusic.entity.PlayListTag;
import com.qymusic.entity.RankMenu;
import com.qymusic.result.PageResult;
import com.qymusic.result.Result;
import com.qymusic.vo.ArtistPageVO;
import com.qymusic.vo.SearchMusicResultVO;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * QQ 音乐曲库（匿名）。
 *
 * <p>QQ 的歌曲 ID 是字符串 songmid，客户端全链路使用数字 id：
 * 这里用 songmid 的 SHA-256 前 8 字节生成稳定 Long，并把原始 mid 放到 sourceId。</p>
 */
@Slf4j
@Service
public class QqCatalogService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 固定榜单：热歌 / 新歌 / 飙升 / 经典 */
    private static final long[] TOP_IDS = {26L, 4L, 62L, 27L};
    private static final String[] TOP_NAMES = {"热歌榜", "新歌榜", "飙升榜", "经典老歌榜"};

    @Resource
    private QqMusicClient qqMusicClient;

    // ------------------------------------------------------------------ 搜索

    public Result<SearchMusicResultVO> searchMusic(String keyword, int pageNo, int pageSize) {
        JsonNode data = qqMusicClient.search(keyword, 0, pageNo, pageSize);
        JsonNode list = data == null ? null : data.path("body").path("song").path("list");
        SearchMusicResultVO vo = new SearchMusicResultVO();
        List<Music> musics = new ArrayList<>();
        if (list != null && list.isArray()) {
            for (JsonNode node : list) {
                Music music = toMusic(node);
                if (music != null) {
                    musics.add(music);
                }
            }
        }
        // musicu 搜索被限流时用联想接口兜底（数量较少，但至少能搜到并播放）
        if (musics.isEmpty()) {
            JsonNode smart = qqMusicClient.smartSearch(keyword);
            if (smart != null && smart.isArray()) {
                for (JsonNode node : smart) {
                    Music music = toMusicFromSmart(node);
                    if (music != null) {
                        musics.add(music);
                    }
                }
            }
        }
        vo.setData(musics);
        vo.setTotal(data == null ? musics.size() : Math.max(musics.size(),
                data.path("meta").path("sum").asInt(0)));
        return Result.success(vo);
    }

    public Result<ArtistPageVO> searchArtist(String keyword, int pageNo, int pageSize) {
        JsonNode data = qqMusicClient.search(keyword, 1, pageNo, pageSize);
        JsonNode list = data == null ? null : data.path("body").path("singer").path("list");
        List<Artist> artists = new ArrayList<>();
        if (list != null && list.isArray()) {
            for (JsonNode node : list) {
                artists.add(toArtist(node));
            }
        }
        ArtistPageVO vo = new ArtistPageVO();
        vo.setData(artists);
        vo.setTotal((long) artists.size());
        return Result.success(vo);
    }

    public Result<List<PlayList>> searchPlaylist(String keyword, int pageNo, int pageSize) {
        JsonNode data = qqMusicClient.search(keyword, 3, pageNo, pageSize);
        JsonNode list = data == null ? null : data.path("body").path("songlist").path("list");
        List<PlayList> playlists = new ArrayList<>();
        if (list != null && list.isArray()) {
            for (JsonNode node : list) {
                playlists.add(toPlayList(node));
            }
        }
        return Result.success(playlists);
    }

    // ------------------------------------------------------------------ 榜单

    public Result<List<RankMenu>> rankMenu() {
        List<RankMenu.Item> items = new ArrayList<>();
        for (int i = 0; i < TOP_IDS.length; i++) {
            items.add(new RankMenu.Item()
                    .setSourceId(TOP_IDS[i])
                    .setId(String.valueOf(TOP_IDS[i]))
                    .setName(TOP_NAMES[i])
                    .setIntro("QQ音乐" + TOP_NAMES[i])
                    .setSource("qq"));
        }
        RankMenu menu = new RankMenu().setName("QQ音乐").setList(items);
        return Result.success(List.of(menu));
    }

    public Result<List<MusicRank>> indexRank() {
        List<MusicRank> ranks = new ArrayList<>();
        for (int i = 0; i < TOP_IDS.length; i++) {
            MusicRank rank = buildRank(TOP_IDS[i], TOP_NAMES[i], 1, 5);
            if (rank != null) {
                ranks.add(rank);
            }
        }
        return Result.success(ranks);
    }

    public Result<MusicRank> rankMusicList(long topId, int pageNo, int pageSize) {
        String name = "QQ音乐榜单";
        for (int i = 0; i < TOP_IDS.length; i++) {
            if (TOP_IDS[i] == topId) {
                name = TOP_NAMES[i];
                break;
            }
        }
        MusicRank rank = buildRank(topId, name, pageNo, pageSize);
        if (rank == null) {
            return Result.error("榜单不存在");
        }
        return Result.success(rank);
    }

    private MusicRank buildRank(long topId, String name, int pageNo, int pageSize) {
        JsonNode data = qqMusicClient.toplist(topId, Math.max(0, pageNo - 1) * pageSize, pageSize);
        if (data == null) {
            return null;
        }
        JsonNode list = data.path("songInfoList");
        if (!list.isArray()) {
            list = data.path("song");
        }
        List<Music> musics = new ArrayList<>();
        if (list.isArray()) {
            for (JsonNode node : list) {
                Music music = toMusic(node);
                if (music != null) {
                    musics.add(music);
                }
            }
        }
        MusicRank rank = new MusicRank();
        rank.setId(topId);
        rank.setName(name);
        rank.setNum((long) musics.size());
        rank.setLeader(musics.isEmpty() ? "" : musics.get(0).getName());
        rank.setMusicList(musics);
        return rank;
    }

    // ------------------------------------------------------------------ 歌单

    public Result<PageResponse<PlayList>> recommendPlaylist() {
        JsonNode data = qqMusicClient.search("热门歌单", 3, 1, 12);
        JsonNode list = data == null ? null : data.path("body").path("songlist").path("list");
        List<PlayList> playlists = new ArrayList<>();
        if (list != null && list.isArray()) {
            for (JsonNode node : list) {
                playlists.add(toPlayList(node));
            }
        }
        PageResponse<PlayList> page = new PageResponse<>();
        page.setData(playlists);
        page.setTotal((long) playlists.size());
        return Result.success(page);
    }

    public Result<PageResult<PlayList>> playlistPage(String keyword, int pageNo, int pageSize) {
        JsonNode data = qqMusicClient.search(
                keyword == null || keyword.isBlank() ? "热门歌单" : keyword,
                3, pageNo, pageSize);
        JsonNode list = data == null ? null : data.path("body").path("songlist").path("list");
        List<PlayList> playlists = new ArrayList<>();
        if (list != null && list.isArray()) {
            for (JsonNode node : list) {
                playlists.add(toPlayList(node));
            }
        }
        return Result.success(new PageResult<>((long) playlists.size(), playlists));
    }

    public Result<List<PlayListTag>> indexTags() {
        List<PlayListTag> tags = new ArrayList<>();
        for (String name : new String[]{"热门歌单", "华语", "欧美", "日韩"}) {
            PlayListTag tag = new PlayListTag();
            tag.setId(name);
            tag.setName(name);
            tags.add(tag);
        }
        return Result.success(tags);
    }

    public Result<List<PlayListTag>> playlistTags() {
        return Result.success(new ArrayList<>());
    }

    public Result<PlayList> playlistInfo(long disstid, int pageNo, int pageSize) {
        JsonNode data = qqMusicClient.playlistDetail(disstid,
                Math.max(0, pageNo - 1) * pageSize, pageSize);
        if (data == null) {
            return Result.error("歌单不存在");
        }
        JsonNode dirinfo = data.path("dirinfo");
        PlayList playlist = new PlayList();
        playlist.setId(disstid);
        playlist.setName(dirinfo.path("dissname").asText(""));
        String img = dirinfo.path("imgurl").asText("");
        playlist.setImg(img);
        playlist.setImg700(img);
        playlist.setListencnt(dirinfo.path("listennum").asLong(0));
        playlist.setUname(dirinfo.path("creator").path("nick").asText(""));
        playlist.setDesc(dirinfo.path("introduction").asText(""));

        JsonNode songs = data.path("songlist");
        List<Music> musics = new ArrayList<>();
        if (songs.isArray()) {
            for (JsonNode node : songs) {
                Music music = toMusic(node);
                if (music != null) {
                    musics.add(music);
                }
            }
        }
        playlist.setMusicList(musics);
        playlist.setTotal(musics.size());
        return Result.success(playlist);
    }

    // ------------------------------------------------------------------ 歌手

    public Result<ArtistPageVO> artistList(int pageNo, int pageSize) {
        ObjectNode param = MAPPER.createObjectNode();
        param.put("area", -100);
        param.put("sex", -100);
        param.put("genre", -100);
        param.put("index", Math.max(0, pageNo - 1) * pageSize);
        param.put("page", pageNo);
        param.put("num", pageSize);
        JsonNode data = qqMusicClient.post(24, 4_747_474,
                "music.musichallSinger.SingerList", "GetSingerList", param, null);
        JsonNode list = data == null ? null : data.path("singerlist");
        List<Artist> artists = new ArrayList<>();
        if (list != null && list.isArray()) {
            for (JsonNode node : list) {
                artists.add(toArtist(node));
            }
        }
        ArtistPageVO vo = new ArtistPageVO();
        vo.setData(artists);
        vo.setTotal((long) artists.size());
        return Result.success(vo);
    }

    public Result<PageResponse<Music>> artistMusic(String singerMid, int pageNo, int pageSize) {
        if (singerMid == null || singerMid.isBlank()) {
            return Result.error("缺少歌手 ID");
        }
        JsonNode data = qqMusicClient.singerSongs(singerMid,
                Math.max(0, pageNo - 1) * pageSize, pageSize);
        JsonNode list = data == null ? null : data.path("songList");
        List<Music> musics = new ArrayList<>();
        if (list != null && list.isArray()) {
            for (JsonNode raw : list) {
                JsonNode node = raw.path("songInfo");
                if (node.isMissingNode() || node.isNull()) {
                    node = raw;
                }
                Music music = toMusic(node);
                if (music != null) {
                    musics.add(music);
                }
            }
        }
        PageResponse<Music> page = new PageResponse<>();
        page.setData(musics);
        page.setTotal((long) musics.size());
        return Result.success(page);
    }

    public Result<Artist> artistInfo(String singerMid) {
        Artist artist = new Artist();
        artist.setSourceId(singerMid);
        artist.setName("");
        return Result.success(artist);
    }

    // ------------------------------------------------------------------ 歌词

    public Result<String> lyric(String songMid, long songId) {
        if (songMid == null || songMid.isBlank()) {
            return Result.error("暂无歌词");
        }
        String lyric = qqMusicClient.lyric(songMid, songId);
        if (lyric == null || lyric.isBlank()) {
            return Result.error("暂无歌词");
        }
        return Result.success(lyric);
    }

    // ------------------------------------------------------------------ 转换

    /** 供用户歌单/喜欢列表复用 */
    public Music mapSong(JsonNode node) {
        return toMusic(node);
    }

    public PlayList mapPlaylist(JsonNode node) {
        return toPlayList(node);
    }

    private Music toMusicFromSmart(JsonNode node) {
        String mid = node.path("mid").asText("");
        if (mid.isBlank()) {
            return null;
        }
        Music music = new Music();
        music.setSource("qq");
        music.setSourceId(mid);
        music.setId(hash64(mid));
        music.setName(node.path("name").asText(""));
        music.setArtist(node.path("singer").asText(""));
        music.setHasLossless(true);
        return music;
    }

    private Music toMusic(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String mid = node.path("mid").asText("");
        if (mid.isBlank()) {
            mid = node.path("songmid").asText("");
        }
        if (mid.isBlank()) {
            return null;
        }
        Music music = new Music();
        music.setSource("qq");
        music.setSourceId(mid);
        music.setId(hash64(mid));
        music.setName(node.path("name").asText(""));
        music.setArtist(joinSingers(node.path("singer")));
        music.setAlbum(node.path("album").path("name").asText(""));
        String albumMid = node.path("album").path("mid").asText("");
        music.setAlbumId(albumMid.isBlank() ? 0L : hash64(albumMid));
        music.setAlbumPic(albumPic(albumMid));
        music.setPic(albumPic(albumMid));
        music.setPic120(albumPic(albumMid));
        int interval = node.path("interval").asInt(0);
        music.setDuration(interval);
        music.setHasLossless(true);
        return music;
    }

    private String joinSingers(JsonNode singers) {
        List<String> names = new ArrayList<>();
        if (singers != null && singers.isArray()) {
            for (JsonNode singer : singers) {
                String name = singer.path("name").asText("");
                if (!name.isBlank()) {
                    names.add(name);
                }
            }
        }
        return String.join("/", names);
    }

    private String albumPic(String albumMid) {
        if (albumMid == null || albumMid.isBlank()) {
            return "";
        }
        return "https://y.qq.com/music/photo_new/T002R300x300M000" + albumMid + ".jpg";
    }

    private Artist toArtist(JsonNode node) {
        Artist artist = new Artist();
        String mid = node.path("singerMID").asText("");
        artist.setSourceId(mid);
        artist.setId(mid.isBlank() ? 0 : (int) (hash64(mid) & 0x7FFFFFFF));
        artist.setName(node.path("singerName").asText(""));
        String pic = node.path("singerPic").asText("");
        artist.setPic(pic);
        artist.setPic120(pic);
        artist.setPic300(pic);
        artist.setMusicNum(node.path("songNum").asInt(0));
        artist.setAlbumNum(node.path("albumNum").asInt(0));
        return artist;
    }

    private PlayList toPlayList(JsonNode node) {
        PlayList playlist = new PlayList();
        playlist.setId(node.path("dissid").asLong(0));
        playlist.setName(node.path("dissname").asText(""));
        String img = node.path("imgurl").asText("");
        playlist.setImg(img);
        playlist.setImg700(img);
        playlist.setListencnt(node.path("listennum").asLong(0));
        playlist.setUname(node.path("creator").path("nick").asText(
                node.path("creator").path("name").asText("")));
        playlist.setDesc(node.path("introduction").asText(""));
        playlist.setTotal(node.path("songnum").asInt(0));
        return playlist;
    }

    /** 稳定 64 位哈希（正数） */
    public static long hash64(String value) {        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            long hash = 0;
            for (int i = 0; i < 8; i++) {
                hash = (hash << 8) | (bytes[i] & 0xFF);
            }
            return hash & 0x7FFFFFFFFFFFFFFFL;
        } catch (Exception e) {
            return Math.abs(value.hashCode());
        }
    }
}
