package com.qymusic.service;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import com.qymusic.api.KugouClient;
import com.qymusic.data.PageResponse;
import com.qymusic.entity.Album;
import com.qymusic.entity.Artist;
import com.qymusic.entity.Music;
import com.qymusic.entity.MusicRank;
import com.qymusic.entity.PlayList;
import com.qymusic.entity.PlayListTag;
import com.qymusic.entity.RankMenu;
import com.qymusic.result.PageResult;
import com.qymusic.result.Result;
import com.qymusic.vo.ArtistPageVO;
import com.qymusic.vo.SearchAlbumResultVO;
import com.qymusic.vo.SearchMusicResultVO;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 酷狗概念版曲库。
 *
 * <p>酷狗歌曲 ID 是 32 位 hash，客户端全链路使用数字 id：
 * 这里用 hash 的 SHA-256 前 8 字节生成稳定 Long，并把原始 hash 放到 sourceId。</p>
 */
@Slf4j
@Service
public class KugouCatalogService {

    /** 首页排行榜展示数量 */
    private static final int INDEX_RANK_SIZE = 4;
    /** 首页排行榜每个榜单的歌曲数 */
    private static final int INDEX_RANK_SONGS = 5;
    /** 默认榜单（TOP500 / 飙升榜 / 新歌榜 / 热歌榜） */
    private static final long[] DEFAULT_RANK_IDS = {8888L, 6666L, 31308L, 74534L};
    private static final String[] DEFAULT_RANK_NAMES = {"TOP500", "飙升榜", "新歌榜", "热歌榜"};

    /** 歌单标签（酷狗公开接口没有稳定的标签列表，使用固定分类，点击后按关键词搜索歌单） */
    private static final String[] PLAYLIST_TAGS = {
            "热门", "华语", "欧美", "日韩", "粤语", "轻音乐", "电子", "民谣", "摇滚", "古风"
    };

    @Resource
    private KugouClient kugouClient;

    // ------------------------------------------------------------------ 搜索

    public Result<SearchMusicResultVO> searchMusic(String keyword, int pageNo, int pageSize) {
        JsonNode root = kugouClient.searchSong(keyword, pageNo, pageSize);
        JsonNode data = root == null ? null : root.path("data");
        JsonNode list = data == null ? null : data.path("lists");
        if (list == null || !list.isArray()) {
            list = data == null ? null : data.path("info");
        }
        List<Music> musics = new ArrayList<>();
        if (list != null && list.isArray()) {
            for (JsonNode node : list) {
                Music music = toMusic(node);
                if (music != null) {
                    musics.add(music);
                }
            }
        }
        SearchMusicResultVO vo = new SearchMusicResultVO();
        vo.setData(musics);
        vo.setTotal(data == null ? musics.size() : Math.max(musics.size(),
                data.path("total").asInt(0)));
        return Result.success(vo);
    }

    public Result<SearchAlbumResultVO> searchAlbum(String keyword, int pageNo, int pageSize) {
        JsonNode root = kugouClient.searchAlbum(keyword, pageNo, pageSize);
        JsonNode data = root == null ? null : root.path("data");
        JsonNode list = data == null ? null : data.path("info");
        List<Album> albums = new ArrayList<>();
        if (list != null && list.isArray()) {
            for (JsonNode node : list) {
                Album album = new Album();
                album.setAlbumId(node.path("albumid").asLong(0));
                album.setAlbum(node.path("albumname").asText(""));
                album.setArtist(node.path("singername").asText(""));
                album.setPic(secureUrl(node.path("imgurl").asText("")));
                album.setReleaseDate(node.path("publishtime").asText(""));
                album.setAlbumInfo(node.path("intro").asText(""));
                albums.add(album);
            }
        }
        SearchAlbumResultVO vo = new SearchAlbumResultVO();
        vo.setData(albums);
        vo.setTotal(data == null ? albums.size() : data.path("total").asInt(albums.size()));
        return Result.success(vo);
    }

    public Result<List<PlayList>> searchPlaylist(String keyword, int pageNo, int pageSize) {
        JsonNode root = kugouClient.searchSpecial(keyword, pageNo, pageSize);
        JsonNode data = root == null ? null : root.path("data");
        JsonNode list = data == null ? null : data.path("info");
        List<PlayList> playlists = new ArrayList<>();
        if (list != null && list.isArray()) {
            for (JsonNode node : list) {
                PlayList playlist = toPlayList(node);
                if (playlist.getId() != null && playlist.getId() > 0) {
                    playlists.add(playlist);
                }
            }
        }
        return Result.success(playlists);
    }

    public Result<ArtistPageVO> searchArtist(String keyword, int pageNo, int pageSize) {
        JsonNode root = kugouClient.searchSinger(keyword, pageNo, pageSize);
        JsonNode data = root == null ? null : root.path("data");
        JsonNode list = data == null ? null : data.path("info");
        if (list == null || !list.isArray()) {
            // 歌手的搜索接口少数情况下直接返回数组
            list = data != null && data.isArray() ? data : null;
        }
        List<Artist> artists = new ArrayList<>();
        if (list != null && list.isArray()) {
            for (JsonNode node : list) {
                Artist artist = toArtist(node);
                if (artist.getSourceId() != null && !artist.getSourceId().isBlank()) {
                    artists.add(artist);
                }
            }
        }
        ArtistPageVO vo = new ArtistPageVO();
        vo.setData(artists);
        vo.setTotal((long) artists.size());
        return Result.success(vo);
    }

    /**
     * 搜索联想：用歌曲搜索结果的歌名去重作为热词。
     */
    public Result<String[]> searchKey(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return Result.success(new String[]{"周杰伦", "林俊杰", "陈奕迅", "邓紫棋",
                    "薛之谦", "晴天", "稻香", "告白气球"});
        }
        JsonNode root = kugouClient.searchSong(keyword, 1, 10);
        JsonNode list = root == null ? null : root.path("data").path("lists");
        Set<String> keys = new LinkedHashSet<>();
        if (list != null && list.isArray()) {
            for (JsonNode node : list) {
                String name = songName(node);
                if (!name.isBlank()) {
                    keys.add(name);
                }
                if (keys.size() >= 10) {
                    break;
                }
            }
        }
        if (keys.isEmpty()) {
            keys.add(keyword);
        }
        return Result.success(keys.toArray(new String[0]));
    }

    // ------------------------------------------------------------------ 榜单

    public Result<List<RankMenu>> rankMenu() {
        List<RankMenu.Item> items = new ArrayList<>();
        List<JsonNode> ranks = rankInfo();
        for (JsonNode node : ranks) {
            long id = node.path("rankid").asLong(0);
            String name = node.path("rankname").asText("");
            if (id <= 0 || name.isBlank()) {
                continue;
            }
            items.add(new RankMenu.Item()
                    .setSourceId(id)
                    .setId(String.valueOf(id))
                    .setName(name)
                    .setIntro(name)
                    .setPic(secureUrl(node.path("imgurl").asText("")))
                    .setSource("kg"));
        }
        if (items.isEmpty()) {
            items = defaultRankItems();
        }
        RankMenu menu = new RankMenu().setName("酷狗音乐").setList(items);
        return Result.success(List.of(menu));
    }

    public Result<List<MusicRank>> indexRank() {
        List<MusicRank> ranks = new ArrayList<>();
        for (JsonNode node : rankInfo()) {
            if (ranks.size() >= INDEX_RANK_SIZE) {
                break;
            }
            long id = node.path("rankid").asLong(0);
            String name = node.path("rankname").asText("");
            if (id <= 0 || name.isBlank()) {
                continue;
            }
            MusicRank rank = buildRank(id, name, 1, INDEX_RANK_SONGS,
                    secureUrl(node.path("imgurl").asText("")));
            if (rank != null) {
                ranks.add(rank);
            }
        }
        if (ranks.isEmpty()) {
            for (int i = 0; i < DEFAULT_RANK_IDS.length; i++) {
                MusicRank rank = buildRank(DEFAULT_RANK_IDS[i], DEFAULT_RANK_NAMES[i], 1,
                        INDEX_RANK_SONGS, "");
                if (rank != null) {
                    ranks.add(rank);
                }
            }
        }
        return Result.success(ranks);
    }

    public Result<MusicRank> rankMusicList(long rankId, int pageNo, int pageSize) {
        if (rankId <= 0) {
            return Result.error("榜单不存在");
        }
        String name = "酷狗榜单";
        String pic = "";
        for (JsonNode node : rankInfo()) {
            if (node.path("rankid").asLong(0) == rankId) {
                name = node.path("rankname").asText(name);
                pic = secureUrl(node.path("imgurl").asText(""));
                break;
            }
        }
        MusicRank rank = buildRank(rankId, name, pageNo, pageSize, pic);
        if (rank == null) {
            return Result.error("榜单数据获取失败，请稍后重试");
        }
        return Result.success(rank);
    }

    private MusicRank buildRank(long rankId, String name, int pageNo, int pageSize, String pic) {
        JsonNode root = kugouClient.rankSongs(rankId, pageNo, pageSize);
        JsonNode data = root == null ? null : root.path("data");
        JsonNode list = data == null ? null : data.path("info");
        if (list == null || !list.isArray()) {
            return null;
        }
        List<Music> musics = new ArrayList<>();
        for (JsonNode node : list) {
            Music music = toMusic(node);
            if (music != null) {
                musics.add(music);
            }
        }
        MusicRank rank = new MusicRank();
        rank.setId(rankId);
        rank.setName(name);
        rank.setPic(pic);
        rank.setNum(data.path("total").asLong((long) musics.size()));
        rank.setLeader(musics.isEmpty() ? "" : musics.get(0).getName());
        rank.setMusicList(musics);
        return rank;
    }

    private List<JsonNode> rankInfo() {
        JsonNode root = kugouClient.rankList();
        JsonNode data = root == null ? null : root.path("data");
        JsonNode list = data == null ? null : data.path("info");
        List<JsonNode> result = new ArrayList<>();
        if (list != null && list.isArray()) {
            for (JsonNode node : list) {
                long id = node.path("rankid").asLong(0);
                if (id > 0) {
                    result.add(node);
                }
            }
        }
        if (result.isEmpty() && list != null && list.isArray() && !list.isEmpty()) {
            for (JsonNode node : list) {
                result.add(node);
            }
        }
        return result;
    }

    private List<RankMenu.Item> defaultRankItems() {
        List<RankMenu.Item> items = new ArrayList<>();
        for (int i = 0; i < DEFAULT_RANK_IDS.length; i++) {
            items.add(new RankMenu.Item()
                    .setSourceId(DEFAULT_RANK_IDS[i])
                    .setId(String.valueOf(DEFAULT_RANK_IDS[i]))
                    .setName(DEFAULT_RANK_NAMES[i])
                    .setIntro(DEFAULT_RANK_NAMES[i])
                    .setSource("kg"));
        }
        return items;
    }

    // ------------------------------------------------------------------ 歌单

    public Result<PageResponse<PlayList>> recommendPlaylist() {
        return Result.success(pageOfPlaylists("热门", 1, 12));
    }

    public Result<PageResult<PlayList>> playlistPage(String keyword, int pageNo, int pageSize) {
        String key = keyword == null || keyword.isBlank() ? "热门" : keyword;
        JsonNode root = kugouClient.searchSpecial(key, pageNo, pageSize);
        JsonNode data = root == null ? null : root.path("data");
        JsonNode list = data == null ? null : data.path("info");
        List<PlayList> playlists = new ArrayList<>();
        if (list != null && list.isArray()) {
            for (JsonNode node : list) {
                PlayList playlist = toPlayList(node);
                if (playlist.getId() != null && playlist.getId() > 0) {
                    playlists.add(playlist);
                }
            }
        }
        long total = data == null ? playlists.size() : data.path("total").asLong(playlists.size());
        return Result.success(new PageResult<>(total, playlists));
    }

    public Result<PageResult<PlayList>> playlistByTag(String tag, int pageNo, int pageSize) {
        return playlistPage(tag == null || tag.isBlank() ? "热门" : tag, pageNo, pageSize);
    }

    public Result<List<PlayListTag>> indexTags() {
        List<PlayListTag> tags = new ArrayList<>();
        for (int i = 0; i < Math.min(4, PLAYLIST_TAGS.length); i++) {
            tags.add(tag(PLAYLIST_TAGS[i]));
        }
        return Result.success(tags);
    }

    public Result<List<PlayListTag>> playlistTags() {
        List<PlayListTag> tags = new ArrayList<>();
        for (String name : PLAYLIST_TAGS) {
            tags.add(tag(name));
        }
        return Result.success(tags);
    }

    public Result<PlayList> playlistInfo(long specialId, int pageNo, int pageSize) {
        if (specialId <= 0) {
            return Result.error("歌单不存在");
        }
        JsonNode infoRoot = kugouClient.specialInfo(specialId);
        JsonNode info = infoRoot == null ? null : infoRoot.path("data");
        JsonNode songRoot = kugouClient.specialSongs(specialId, Math.max(1, pageNo),
                Math.max(1, pageSize));
        JsonNode songs = songRoot == null ? null : songRoot.path("data").path("info");
        List<Music> musics = new ArrayList<>();
        if (songs != null && songs.isArray()) {
            for (JsonNode node : songs) {
                Music music = toMusic(node);
                if (music != null) {
                    musics.add(music);
                }
            }
        }
        if ((info == null || info.isMissingNode()) && musics.isEmpty()) {
            return Result.error("歌单不存在或已下架");
        }
        PlayList playlist = new PlayList();
        playlist.setId(specialId);
        String name = info == null ? "" : clean(info.path("specialname").asText(""));
        playlist.setName(name);
        String pic = secureUrl(info == null ? "" : info.path("imgurl").asText(""));
        playlist.setImg(pic);
        playlist.setImg700(pic);
        playlist.setUname(info == null ? "" : clean(info.path("nickname").asText(
                info.path("username").asText(""))));
        playlist.setDesc(info == null ? "" : clean(info.path("intro").asText("")));
        playlist.setListencnt(info == null ? 0L : info.path("playcount").asLong(0));
        playlist.setTotal(info == null ? musics.size() : info.path("songcount").asInt(musics.size()));
        playlist.setMusicList(musics);
        return Result.success(playlist);
    }

    private PageResponse<PlayList> pageOfPlaylists(String keyword, int pageNo, int pageSize) {
        JsonNode root = kugouClient.searchSpecial(keyword, pageNo, pageSize);
        JsonNode data = root == null ? null : root.path("data");
        JsonNode list = data == null ? null : data.path("info");
        List<PlayList> playlists = new ArrayList<>();
        if (list != null && list.isArray()) {
            for (JsonNode node : list) {
                PlayList playlist = toPlayList(node);
                if (playlist.getId() != null && playlist.getId() > 0) {
                    playlists.add(playlist);
                }
            }
        }
        PageResponse<PlayList> page = new PageResponse<>();
        page.setData(playlists);
        page.setTotal(data == null ? (long) playlists.size()
                : data.path("total").asLong(playlists.size()));
        return page;
    }

    private PlayListTag tag(String name) {
        PlayListTag tag = new PlayListTag();
        tag.setId(name);
        tag.setName(name);
        return tag;
    }

    // ------------------------------------------------------------------ 歌手

    public Result<ArtistPageVO> artistList(int pageNo, int pageSize) {
        JsonNode root = kugouClient.singerList(pageNo, pageSize, 0, 1);
        JsonNode data = root == null ? null : root.path("data");
        JsonNode list = data == null ? null : data.path("info");
        List<Artist> artists = new ArrayList<>();
        if (list != null && list.isArray()) {
            for (JsonNode node : list) {
                Artist artist = toArtist(node);
                if (artist.getSourceId() != null && !artist.getSourceId().isBlank()) {
                    artists.add(artist);
                }
            }
        }
        ArtistPageVO vo = new ArtistPageVO();
        vo.setData(artists);
        vo.setTotal(data == null ? (long) artists.size()
                : data.path("total").asLong(artists.size()));
        return Result.success(vo);
    }

    public Result<Artist> artistInfo(String sourceId) {
        long singerId = parseLong(sourceId);
        if (singerId <= 0) {
            return Result.error("缺少歌手 ID");
        }
        JsonNode root = kugouClient.singerInfo(singerId);
        JsonNode data = root == null ? null : root.path("data");
        if (data == null || data.isMissingNode() || data.isNull()) {
            return Result.error("歌手信息获取失败");
        }
        Artist artist = toArtist(data);
        artist.setSourceId(String.valueOf(singerId));
        artist.setId((int) (hash64(String.valueOf(singerId)) & 0x7FFFFFFF));
        artist.setAartist(data.path("alias").asText(""));
        return Result.success(artist);
    }

    public Result<PageResponse<Music>> artistMusic(String sourceId, int pageNo, int pageSize) {
        long singerId = parseLong(sourceId);
        if (singerId <= 0) {
            return Result.error("缺少歌手 ID");
        }
        JsonNode root = kugouClient.singerSongs(singerId, pageNo, pageSize);
        JsonNode data = root == null ? null : root.path("data");
        JsonNode list = data == null ? null : data.path("info");
        List<Music> musics = new ArrayList<>();
        if (list != null && list.isArray()) {
            for (JsonNode node : list) {
                Music music = toMusic(node);
                if (music != null) {
                    musics.add(music);
                }
            }
        }
        PageResponse<Music> page = new PageResponse<>();
        page.setData(musics);
        page.setTotal(data == null ? (long) musics.size()
                : data.path("total").asLong(musics.size()));
        return Result.success(page);
    }

    // ------------------------------------------------------------------ 歌词

    public Result<String> lyric(String hash) {
        if (hash == null || hash.isBlank()) {
            return Result.error("暂无歌词");
        }
        String lyric = kugouClient.lyric(hash);
        if (lyric == null || lyric.isBlank()) {
            return Result.error("暂无歌词");
        }
        return Result.success(lyric);
    }

    // ------------------------------------------------------------------ 转换

    /** 供用户歌单复用 */
    public Music mapSong(JsonNode node) {
        return toMusic(node);
    }

    public Music toMusic(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String hash = firstNonBlank(
                node.path("FileHash").asText(""),
                node.path("hash").asText(""),
                node.path("hash_128").asText(""),
                node.path("128hash").asText(""),
                node.path("HQFileHash").asText(""),
                node.path("320hash").asText(""));
        hash = hash.toLowerCase();
        if (!hash.matches("[0-9a-f]{32}")) {
            return null;
        }
        Music music = new Music();
        music.setSource("kg");
        music.setSourceId(hash);
        music.setId(hash64(hash));
        music.setName(songName(node));
        music.setFullName(node.path("remark").asText(""));
        music.setArtist(artistName(node));
        music.setAlbum(clean(firstNonBlank(node.path("AlbumName").asText(""),
                node.path("album_name").asText(""),
                node.path("albumname").asText(""))));
        long albumId = node.path("AlbumID").asLong(node.path("album_id").asLong(0));
        music.setAlbumId(albumId > 0 ? albumId : 0L);
        long artistId = node.path("SingerId").asLong(node.path("singerid").asLong(0));
        if (artistId <= 0) {
            JsonNode authors = node.path("authors");
            if (authors.isArray() && !authors.isEmpty()) {
                artistId = authors.get(0).path("author_id").asLong(0);
            }
        }
        music.setArtistId(artistId > 0 ? artistId : 0L);
        String pic = firstNonBlank(
                node.path("Image").asText(""),
                node.path("image").asText(""),
                node.path("img").asText(""),
                node.path("cover").asText(""),
                node.path("album_sizable_cover").asText(""),
                node.path("trans_param").path("union_cover").asText(""),
                node.path("sizable_cover").asText(""));
        music.setPic(secureUrl(pic));
        music.setPic120(secureUrl(pic));
        music.setAlbumPic(secureUrl(firstNonBlank(
                node.path("album_sizable_cover").asText(""), pic)));
        int duration = node.path("Duration").asInt(node.path("duration").asInt(0));
        if (duration > 10000) {
            duration = duration / 1000;
        }
        music.setDuration(duration);
        boolean lossless = !node.path("SQFileHash").asText("").isBlank()
                || !node.path("sqhash").asText("").isBlank()
                || !node.path("hash_flac").asText("").isBlank()
                || !node.path("hash_high").asText("").isBlank();
        music.setHasLossless(lossless);
        String release = node.path("publish_date").asText("");
        if (!release.isBlank()) {
            music.setReleaseDate(release);
        }
        return music;
    }

    private String songName(JsonNode node) {
        String name = clean(firstNonBlank(node.path("SongName").asText(""),
                node.path("songname").asText("")));
        if (!name.isBlank()) {
            return name;
        }
        String fileName = clean(firstNonBlank(node.path("FileName").asText(""),
                node.path("filename").asText("")));
        int index = fileName.indexOf(" - ");
        return index > 0 ? fileName.substring(index + 3).trim() : fileName.trim();
    }

    private String artistName(JsonNode node) {
        String artist = clean(firstNonBlank(node.path("SingerName").asText(""),
                node.path("singername").asText(""),
                node.path("author_name").asText("")));
        if (!artist.isBlank()) {
            return artist;
        }
        StringBuilder sb = new StringBuilder();
        JsonNode authors = node.path("authors");
        if (authors.isArray()) {
            for (JsonNode author : authors) {
                String name = clean(author.path("author_name").asText(""));
                if (!name.isBlank()) {
                    if (sb.length() > 0) {
                        sb.append('/');
                    }
                    sb.append(name);
                }
            }
        }
        if (sb.length() == 0) {
            String fileName = clean(firstNonBlank(node.path("filename").asText(""),
                    node.path("FileName").asText("")));
            int index = fileName.indexOf(" - ");
            if (index > 0) {
                sb.append(fileName, 0, index);
            }
        }
        return sb.toString();
    }

    /** 去掉搜索高亮标签（&lt;em&gt;）并还原常见实体。 */
    private String clean(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("<[^>]*>", "")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .trim();
    }

    private Artist toArtist(JsonNode node) {
        Artist artist = new Artist();
        long singerId = node.path("singerid").asLong(node.path("author_id").asLong(0));
        String id = singerId > 0 ? String.valueOf(singerId)
                : node.path("singerid").asText("");
        artist.setSourceId(id);
        artist.setId(id.isBlank() ? 0 : (int) (hash64(id) & 0x7FFFFFFF));
        artist.setName(clean(firstNonBlank(node.path("singername").asText(""),
                node.path("author_name").asText(""))));
        String pic = secureUrl(node.path("imgurl").asText(
                node.path("sizable_avatar").asText(
                        node.path("avatar").asText(""))));
        artist.setPic(pic);
        artist.setPic120(pic);
        artist.setPic300(pic);
        artist.setMusicNum(node.path("songcount").asInt(0));
        artist.setAlbumNum(node.path("albumcount").asInt(0));
        artist.setMvNum(node.path("mvcount").asInt(0));
        artist.setArtistFans(node.path("fanscount").asInt(0));
        return artist;
    }

    private PlayList toPlayList(JsonNode node) {
        PlayList playlist = new PlayList();
        playlist.setId(node.path("specialid").asLong(0));
        playlist.setName(clean(node.path("specialname").asText("")));
        String pic = secureUrl(node.path("imgurl").asText(""));
        playlist.setImg(pic);
        playlist.setImg700(pic);
        playlist.setListencnt(node.path("playcount").asLong(0));
        playlist.setUname(clean(node.path("nickname").asText(
                node.path("username").asText(""))));
        playlist.setDesc(clean(node.path("intro").asText("")));
        playlist.setTotal(node.path("songcount").asInt(0));
        return playlist;
    }

    /**
     * 封面地址 https 化，并把 {size} 占位替换为 400。
     */
    public static String secureUrl(String value) {
        if (value == null) {
            return "";
        }
        String url = value.trim().replace("{size}", "400");
        if (url.isEmpty()) {
            return "";
        }
        if (url.startsWith("//")) {
            return "https:" + url;
        }
        if (url.startsWith("http://")) {
            return "https://" + url.substring(7);
        }
        return url;
    }

    /** 稳定 64 位哈希（正数） */
    public static long hash64(String value) {
        try {
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

    private long parseLong(String value) {
        try {
            return Long.parseLong(value == null ? "" : value.trim());
        } catch (Exception e) {
            return 0L;
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
}
