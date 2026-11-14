package com.qymusic.service;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import com.qymusic.api.NetEaseService;
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
import com.qymusic.vo.SearchArtistResultVO;
import com.qymusic.vo.SearchMusicResultVO;
import com.qymusic.vo.SearchPlaylistResultVO;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 网易云目录服务。
 *
 * <p>把网易云网页版接口的数据转换成 App 原有的实体结构（Music / Artist / PlayList /
 * MusicRank / RankMenu），这样客户端不需要改导航和数据模型，整个 App 就变成「网易云版本」。</p>
 *
 * <p>播放地址不在这里解析，仍然由 {@link PlayUrlResolver} 交给洛雪音源脚本。</p>
 */
@Service
@Slf4j
public class NeteaseCatalogService {

    /** LRC 行：[mm:ss.xx] 或 [mm:ss.xxx] */
    private static final Pattern LRC_LINE = Pattern.compile("^\\[(\\d{2}:\\d{2}\\.\\d{2,3})](.*)$");

    /** 官方榜（与网易云网页版一致） */
    private static final long[] OFFICIAL_RANK_IDS = {
            19723756L, // 飙升榜
            3779629L,  // 新歌榜
            2884035L,  // 原创榜
            3778678L   // 热歌榜
    };

    /** 首页排行榜展示数量 */
    private static final int INDEX_RANK_SIZE = 4;

    /** 单次 song/detail 最多请求的歌曲 id 数 */
    private static final int SONG_DETAIL_BATCH = 100;

    private static final long CACHE_TTL = 5 * 60 * 1000L;

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    @Resource
    private NetEaseService netEaseService;

    private record CacheEntry(JsonNode node, long expireAt) {
    }

    // ------------------------------------------------------------------ 搜索

    public Result<SearchMusicResultVO> searchMusic(String keyword, int pageNo, int pageSize) {
        int offset = Math.max(0, pageNo - 1) * pageSize;
        JsonNode root = netEaseService.search(keyword, 1, offset, pageSize);
        JsonNode result = root == null ? null : root.path("result");
        int total = result == null ? 0 : result.path("songCount").asInt(0);

        List<Music> list = new ArrayList<>();
        JsonNode songs = result == null ? null : result.path("songs");
        if (songs != null && songs.isArray()) {
            List<Long> ids = new ArrayList<>();
            for (JsonNode song : songs) {
                long id = song.path("id").asLong(0);
                if (id > 0) {
                    ids.add(id);
                }
            }
            // 搜索接口只给了 picId，没有封面 URL；再查一次歌曲详情补齐封面、时长
            List<Music> details = songsByIds(ids);
            if (!details.isEmpty()) {
                list.addAll(details);
            } else {
                for (JsonNode song : songs) {
                    Music music = toMusicFromSearch(song);
                    if (music != null) {
                        list.add(music);
                    }
                }
            }
        }

        SearchMusicResultVO vo = new SearchMusicResultVO();
        vo.setTotal(total);
        sortByRelevance(list, keyword);
        vo.setData(list);
        return Result.success(vo);
    }

    public Result<List<String>> searchKey(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            // 网易云没有公开的热搜词接口（/api/search/hot 已下线），这里给一组常用热词
            return Result.success(List.of("周杰伦", "林俊杰", "晴天", "稻香", "陈奕迅",
                    "薛之谦", "邓紫棋", "七里香", "夜曲", "告白气球"));
        }
        JsonNode root = netEaseService.suggest(keyword);
        JsonNode result = root == null ? null : root.path("result");
        List<String> keys = new ArrayList<>();
        if (result != null) {
            JsonNode allMatch = result.path("allMatch");
            if (allMatch.isArray()) {
                for (JsonNode item : allMatch) {
                    String value = item.path("keyword").asText("");
                    if (!value.isBlank()) {
                        keys.add(value);
                    }
                }
            }
        }
        if (keys.isEmpty()) {
            keys.add(keyword);
        }
        return Result.success(keys);
    }

    public Result<SearchAlbumResultVO> searchAlbum(String keyword, int pageNo, int pageSize) {
        int offset = Math.max(0, pageNo - 1) * pageSize;
        JsonNode root = netEaseService.search(keyword, 10, offset, pageSize);
        JsonNode result = root == null ? null : root.path("result");

        List<Album> albums = new ArrayList<>();
        JsonNode array = result == null ? null : result.path("albums");
        if (array != null && array.isArray()) {
            for (JsonNode node : array) {
                Album album = new Album();
                album.setAlbumId(node.path("id").asLong(0));
                album.setAlbum(node.path("name").asText(""));
                album.setArtist(joinNames(node.path("artists"), "name"));
                album.setPic(node.path("picUrl").asText(""));
                album.setReleaseDate(formatDate(node.path("publishTime").asLong(0)));
                album.setAlbumInfo(node.path("description").asText(""));
                album.setLang("");
                albums.add(album);
            }
        }

        SearchAlbumResultVO vo = new SearchAlbumResultVO();
        vo.setTotal(result == null ? 0 : result.path("albumCount").asInt(0));
        vo.setData(albums);
        return Result.success(vo);
    }

    public Result<SearchArtistResultVO> searchArtist(String keyword, int pageNo, int pageSize) {
        int offset = Math.max(0, pageNo - 1) * pageSize;
        JsonNode root = netEaseService.search(keyword, 100, offset, pageSize);
        JsonNode result = root == null ? null : root.path("result");

        List<Artist> artists = new ArrayList<>();
        JsonNode array = result == null ? null : result.path("artists");
        if (array != null && array.isArray()) {
            for (JsonNode node : array) {
                artists.add(toArtist(node));
            }
        }

        SearchArtistResultVO vo = new SearchArtistResultVO();
        vo.setTotal(result == null ? 0 : result.path("artistCount").asInt(0));
        vo.setData(artists);
        return Result.success(vo);
    }

    public Result<SearchPlaylistResultVO> searchPlaylist(String keyword, int pageNo, int pageSize) {
        int offset = Math.max(0, pageNo - 1) * pageSize;
        JsonNode root = netEaseService.search(keyword, 1000, offset, pageSize);
        JsonNode result = root == null ? null : root.path("result");

        List<PlayList> playlists = new ArrayList<>();
        JsonNode array = result == null ? null : result.path("playlists");
        if (array != null && array.isArray()) {
            for (JsonNode node : array) {
                playlists.add(toPlayList(node));
            }
        }

        SearchPlaylistResultVO vo = new SearchPlaylistResultVO();
        vo.setTotal(result == null ? 0 : result.path("playlistCount").asInt(0));
        vo.setData(playlists);
        return Result.success(vo);
    }

    // ------------------------------------------------------------------ 排行榜

    public Result<List<RankMenu>> rankMenu() {
        JsonNode root = cached("toplist", netEaseService::toplist);
        JsonNode list = root == null ? null : root.path("list");

        List<RankMenu.Item> official = new ArrayList<>();
        List<RankMenu.Item> featured = new ArrayList<>();
        if (list != null && list.isArray()) {
            int index = 0;
            for (JsonNode node : list) {
                long id = node.path("id").asLong(0);
                if (id <= 0) {
                    continue;
                }
                RankMenu.Item item = new RankMenu.Item();
                item.setId(String.valueOf(id));
                item.setSourceId(id);
                item.setSource("wy");
                item.setName(node.path("name").asText(""));
                item.setPic(node.path("coverImgUrl").asText(""));
                String update = node.path("updateFrequency").asText("");
                String intro = node.path("description").asText("");
                item.setPub(update);
                item.setIntro(intro.isBlank() ? "网易云音乐排行榜" : intro);
                if (isOfficialRank(id)) {
                    official.add(item);
                } else if (featured.size() < 20) {
                    featured.add(item);
                }
                index++;
                if (index >= 200) {
                    break;
                }
            }
        }

        List<RankMenu> menus = new ArrayList<>();
        if (!official.isEmpty()) {
            RankMenu menu = new RankMenu();
            menu.setName("官方榜");
            menu.setList(official);
            menus.add(menu);
        }
        if (!featured.isEmpty()) {
            RankMenu menu = new RankMenu();
            menu.setName("特色榜");
            menu.setList(featured);
            menus.add(menu);
        }
        return Result.success(menus);
    }

    public Result<List<MusicRank>> indexRank() {
        List<MusicRank> ranks = new ArrayList<>();
        for (long id : OFFICIAL_RANK_IDS) {
            MusicRank rank = buildRank(id);
            if (rank != null) {
                ranks.add(rank);
            }
        }
        // 官方榜拿不到时退化为榜单列表里的前几个
        if (ranks.isEmpty()) {
            JsonNode root = cached("toplist", netEaseService::toplist);
            JsonNode list = root == null ? null : root.path("list");
            if (list != null && list.isArray()) {
                for (int i = 0; i < Math.min(INDEX_RANK_SIZE, list.size()); i++) {
                    long id = list.get(i).path("id").asLong(0);
                    if (id > 0) {
                        MusicRank rank = buildRank(id);
                        if (rank != null) {
                            ranks.add(rank);
                        }
                    }
                }
            }
        }
        return Result.success(ranks);
    }

    public Result<MusicRank> rankMusicList(String rankId, int pageNo, int pageSize) {
        long id = parseLong(rankId, 0);
        if (id <= 0) {
            return Result.error("榜单不存在");
        }
        MusicRank rank = buildRank(id);
        if (rank == null) {
            return Result.error("榜单不存在");
        }
        List<Music> all = playlistTracks(id, pageNo, pageSize);
        rank.setMusicList(all);
        return Result.success(rank);
    }

    private MusicRank buildRank(long playlistId) {
        JsonNode detail = playlistDetail(playlistId, 3);
        if (detail == null) {
            return null;
        }
        JsonNode playlist = detail.path("playlist");
        if (playlist.isMissingNode() || playlist.isNull()) {
            return null;
        }
        MusicRank rank = new MusicRank();
        rank.setId(playlistId);
        rank.setName(playlist.path("name").asText(""));
        rank.setPic(playlist.path("coverImgUrl").asText(""));
        rank.setLeader(playlist.path("name").asText(""));
        rank.setPub(playlist.path("updateFrequency").asText(""));
        rank.setNum(playlist.path("trackCount").asLong(0));

        List<Music> tracks = new ArrayList<>();
        JsonNode array = playlist.path("tracks");
        if (array.isArray()) {
            for (JsonNode node : array) {
                if (tracks.size() >= 3) {
                    break;
                }
                Music music = toMusic(node);
                if (music != null) {
                    tracks.add(music);
                }
            }
        }
        rank.setMusicList(tracks);
        return rank;
    }

    // ------------------------------------------------------------------ 歌手

    public Result<ArtistPageVO> artistList(int category, String prefix, int pageNo, int pageSize) {
        int cat = toNeteaseArtistCat(category);
        int offset = Math.max(0, pageNo - 1) * pageSize;
        JsonNode root = netEaseService.artistList(cat, offset, pageSize);
        JsonNode array = root == null ? null : root.path("artists");

        List<Artist> artists = new ArrayList<>();
        if (array != null && array.isArray()) {
            for (JsonNode node : array) {
                artists.add(toArtist(node));
            }
        }

        ArtistPageVO vo = new ArtistPageVO();
        // 网易云不返回总数，用「是否还有下一页」推算一个足够大的总数给分页组件
        boolean more = root != null && root.path("more").asBoolean(false);
        vo.setTotal(more ? (long) pageNo * pageSize + 1 : (long) offset + artists.size());
        vo.setData(artists);
        return Result.success(vo);
    }

    public Result<Artist> artistInfo(Long artistId) {
        JsonNode root = artistDetail(artistId);
        if (root == null || !root.hasNonNull("artist")) {
            return Result.error("歌手不存在");
        }
        return Result.success(toArtist(root.path("artist")));
    }

    public Result<PageResponse<Music>> artistMusicList(Long artistId, int pageNo, int pageSize) {
        JsonNode root = artistDetail(artistId);
        JsonNode hotSongs = root == null ? null : root.path("hotSongs");
        List<Music> all = new ArrayList<>();
        if (hotSongs != null && hotSongs.isArray()) {
            for (JsonNode node : hotSongs) {
                Music music = toMusic(node);
                if (music != null) {
                    all.add(music);
                }
            }
        }

        int from = Math.max(0, pageNo - 1) * pageSize;
        int to = Math.min(all.size(), from + pageSize);
        PageResponse<Music> page = new PageResponse<>();
        page.setData(from >= all.size() ? Collections.emptyList() : all.subList(from, to));
        page.setTotal((long) all.size());
        return Result.success(page);
    }

    // ------------------------------------------------------------------ 歌单

    public Result<PageResponse<PlayList>> recommendPlaylists() {
        JsonNode root = netEaseService.personalizedPlaylist(12);
        JsonNode array = root == null ? null : root.path("result");
        List<PlayList> playlists = new ArrayList<>();
        if (array != null && array.isArray()) {
            for (JsonNode node : array) {
                playlists.add(toPlayList(node));
            }
        }
        PageResponse<PlayList> page = new PageResponse<>();
        page.setData(playlists);
        page.setTotal((long) playlists.size());
        return Result.success(page);
    }

    public Result<PageResult<PlayList>> playListByTag(String cat) {
        return playListPage(cat, 1, 30, "hot");
    }

    public Result<List<PlayListTag>> indexPlayListTags() {
        List<PlayListTag> tags = new ArrayList<>();
        tags.add(tag("华语", "华语"));
        tags.add(tag("流行", "流行"));
        tags.add(tag("摇滚", "摇滚"));
        tags.add(tag("民谣", "民谣"));
        tags.add(tag("电子", "电子"));
        tags.add(tag("说唱", "说唱"));
        tags.add(tag("轻音乐", "轻音乐"));
        tags.add(tag("怀旧", "怀旧"));
        return Result.success(tags);
    }

    public Result<List<PlayListTag>> playListTags() {
        List<PlayListTag> categories = new ArrayList<>();

        PlayListTag language = new PlayListTag();
        language.setId("语种");
        language.setName("语种");
        language.setData(tags("华语", "欧美", "日语", "韩语", "粤语"));
        categories.add(language);

        PlayListTag style = new PlayListTag();
        style.setId("风格");
        style.setName("风格");
        style.setData(tags("流行", "摇滚", "民谣", "电子", "说唱", "轻音乐", "爵士", "古典", "乡村", "金属"));
        categories.add(style);

        PlayListTag scene = new PlayListTag();
        scene.setId("场景");
        scene.setName("场景");
        scene.setData(tags("清晨", "夜晚", "学习", "工作", "午休", "下午茶", "地铁", "驾车", "运动", "旅行", "散步"));
        categories.add(scene);

        PlayListTag mood = new PlayListTag();
        mood.setId("情感");
        mood.setName("情感");
        mood.setData(tags("怀旧", "清新", "浪漫", "伤感", "治愈", "放松", "孤独", "感动", "兴奋", "快乐", "安静", "思念"));
        categories.add(mood);

        PlayListTag theme = new PlayListTag();
        theme.setId("主题");
        theme.setName("主题");
        theme.setData(tags("影视原声", "ACG", "校园", "游戏", "经典", "网络歌曲", "KTV", "背景音乐", "清晨", "夜晚"));
        categories.add(theme);

        return Result.success(categories);
    }

    public Result<PageResult<PlayList>> playListPage(int pageNo, int pageSize, String order) {
        String cat = "全部";
        // 网易云 /api/playlist/list 目前只支持 order=hot，order=new 会返回空列表
        String orderValue = "hot";
        int offset = Math.max(0, pageNo - 1) * pageSize;
        JsonNode root = netEaseService.playlistList(cat, orderValue, offset, pageSize);
        return Result.success(mappingPlaylistPage(root));
    }

    public Result<PageResult<PlayList>> playListPage(String cat, int pageNo, int pageSize, String order) {
        String catValue = (cat == null || cat.isBlank() || "推荐".equals(cat)) ? "全部" : cat;
        // 网易云 /api/playlist/list 目前只支持 order=hot，order=new 会返回空列表
        String orderValue = "hot";
        int offset = Math.max(0, pageNo - 1) * pageSize;
        JsonNode root = netEaseService.playlistList(catValue, orderValue, offset, pageSize);
        return Result.success(mappingPlaylistPage(root));
    }

    public Result<PlayList> playListInfo(String pid, int pageNo, int pageSize) {
        long id = parseLong(pid, 0);
        if (id <= 0) {
            return Result.error("歌单不存在");
        }
        JsonNode detail = playlistDetail(id, 0);
        if (detail == null || !detail.hasNonNull("playlist")) {
            return Result.error("歌单不存在");
        }
        JsonNode playlist = detail.path("playlist");
        PlayList model = toPlayList(playlist);
        model.setMusicList(playlistTracks(id, pageNo, pageSize));
        return Result.success(model);
    }

    // ------------------------------------------------------------------ 歌词

    public Result<String> lyric(long id) {
        return lyric(id, false);
    }

    /**
     * @param translate 是否把翻译歌词合并到原文下方
     */
    public Result<String> lyric(long id, boolean translate) {
        JsonNode root = netEaseService.lyric(id, 1, 1, -1);
        String lyric = root == null ? "" : root.path("lrc").path("lyric").asText("");
        if (lyric.isBlank()) {
            return Result.error("暂无歌词");
        }
        if (!translate) {
            return Result.success(lyric);
        }
        String translation = root.path("tlyric").path("lyric").asText("");
        return Result.success(mergeTranslation(lyric, translation));
    }

    /**
     * 把翻译按时间戳插到原文下一行，客户端歌词控件会显示为两行。
     */
    private String mergeTranslation(String lyric, String translation) {
        if (translation == null || translation.isBlank()) {
            return lyric;
        }
        Map<String, String> translationMap = new LinkedHashMap<>();
        for (String line : translation.split("\\r?\\n")) {
            Matcher matcher = LRC_LINE.matcher(line);
            if (matcher.matches()) {
                translationMap.put(matcher.group(1), matcher.group(2).trim());
            }
        }
        if (translationMap.isEmpty()) {
            return lyric;
        }
        StringBuilder merged = new StringBuilder();
        for (String line : lyric.split("\\r?\\n")) {
            merged.append(line).append('\n');
            Matcher matcher = LRC_LINE.matcher(line);
            if (matcher.matches()) {
                String text = translationMap.get(matcher.group(1));
                if (text != null && !text.isBlank()) {
                    merged.append('[').append(matcher.group(1)).append(']').append(text).append('\n');
                }
            }
        }
        return merged.toString().trim();
    }

    // ------------------------------------------------------------------ 数据转换

    public List<Music> songsByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, Music> mapped = new LinkedHashMap<>();
        for (int i = 0; i < ids.size(); i += SONG_DETAIL_BATCH) {
            List<Long> batch = ids.subList(i, Math.min(i + SONG_DETAIL_BATCH, ids.size()));
            String idsParam = batch.stream().map(String::valueOf).collect(Collectors.joining(",", "[", "]"));
            JsonNode root = netEaseService.songDetail(idsParam);
            JsonNode songs = root == null ? null : root.path("songs");
            if (songs == null || !songs.isArray()) {
                continue;
            }
            for (JsonNode node : songs) {
                Music music = toMusic(node);
                if (music != null) {
                    mapped.putIfAbsent(music.getId(), music);
                }
            }
        }
        List<Music> result = new ArrayList<>();
        for (Long id : ids) {
            Music music = mapped.get(id);
            if (music != null) {
                result.add(music);
            }
        }
        return result;
    }

    /**
     * 取歌单指定页的歌曲。
     *
     * <p>v6 详情只返回前 n 首的完整数据，但 trackIds 是全量的；
     * 分页时按 trackIds 切片再批量查详情，避免一次拉几百首。</p>
     */
    private List<Music> playlistTracks(long playlistId, int pageNo, int pageSize) {
        JsonNode detail = playlistDetail(playlistId, 0);
        if (detail == null || !detail.hasNonNull("playlist")) {
            return Collections.emptyList();
        }
        JsonNode playlist = detail.path("playlist");
        List<Long> ids = new ArrayList<>();
        JsonNode trackIds = playlist.path("trackIds");
        if (trackIds.isArray()) {
            for (JsonNode node : trackIds) {
                long id = node.path("id").asLong(0);
                if (id > 0) {
                    ids.add(id);
                }
            }
        }
        if (ids.isEmpty()) {
            // 部分歌单没有 trackIds，退回详情里带的 tracks
            List<Music> fallback = new ArrayList<>();
            JsonNode tracks = playlist.path("tracks");
            if (tracks.isArray()) {
                for (JsonNode node : tracks) {
                    Music music = toMusic(node);
                    if (music != null) {
                        fallback.add(music);
                    }
                }
            }
            return slice(fallback, pageNo, pageSize);
        }

        int from = Math.max(0, pageNo - 1) * pageSize;
        int to = Math.min(ids.size(), from + pageSize);
        if (from >= ids.size()) {
            return Collections.emptyList();
        }
        return songsByIds(new ArrayList<>(ids.subList(from, to)));
    }

    private JsonNode playlistDetail(long id, int trackLimit) {
        String key = "playlist:" + id + ":" + trackLimit;
        return cached(key, () -> netEaseService.playlistDetail(id, trackLimit, 8));
    }

    private JsonNode artistDetail(Long artistId) {
        long id = artistId == null ? 0 : artistId;
        if (id <= 0) {
            return null;
        }
        return cached("artist:" + id, () -> netEaseService.artistDetail(id));
    }

    private PageResult<PlayList> mappingPlaylistPage(JsonNode root) {
        List<PlayList> list = new ArrayList<>();
        long total = 0;
        if (root != null) {
            total = root.path("total").asLong(0);
            JsonNode array = root.path("playlists");
            if (array.isArray()) {
                for (JsonNode node : array) {
                    list.add(toPlayList(node));
                }
            }
        }
        PageResult<PlayList> page = new PageResult<>();
        page.setTotal(total);
        page.setData(list);
        return page;
    }

    private Music toMusic(JsonNode song) {
        if (song == null || song.isMissingNode() || song.isNull()) {
            return null;
        }
        long id = song.path("id").asLong(0);
        if (id <= 0) {
            return null;
        }
        JsonNode album = song.hasNonNull("al") ? song.path("al") : song.path("album");
        JsonNode artists = song.hasNonNull("ar") ? song.path("ar") : song.path("artists");

        Music music = new Music();
        music.setId(id);
        music.setSource("wy");
        music.setName(song.path("name").asText(""));
        music.setFullName(song.hasNonNull("tns") ? joinNames(song.path("tns"), "") : music.getName());

        music.setArtist(joinNames(artists, "name"));
        music.setArtistId(firstId(artists));

        music.setAlbum(album.path("name").asText(""));
        music.setAlbumId(album.path("id").asLong(0));

        String pic = album.path("picUrl").asText("");
        music.setPic(pic);
        music.setPic120(pic);
        music.setAlbumPic(pic);

        long duration = song.path("dt").asLong(0);
        if (duration <= 0) {
            duration = song.path("duration").asLong(0);
        }
        music.setDuration((int) Math.round(duration / 1000.0));
        music.setHasLossless(true);
        music.setHasMv(song.path("mv").asInt(0) > 0 ? 1 : 0);
        return music;
    }

    private Music toMusicFromSearch(JsonNode song) {
        // cloudsearch/pc 返回 al/ar/dt，旧 search/get 返回 album/artists/duration
        return toMusic(song);
    }

    private Artist toArtist(JsonNode node) {
        Artist artist = new Artist();
        long id = node.path("id").asLong(0);
        artist.setId((int) Math.min(id, Integer.MAX_VALUE));
        artist.setName(node.path("name").asText(""));
        String pic = node.path("picUrl").asText("");
        if (pic.isBlank()) {
            pic = node.path("img1v1Url").asText("");
        }
        artist.setPic(pic);
        artist.setPic120(pic);
        artist.setPic70(pic);
        artist.setPic300(pic);
        artist.setAartist(firstName(node.path("alias")));
        artist.setMusicNum(node.path("musicSize").asInt(0));
        artist.setAlbumNum(node.path("albumSize").asInt(0));
        artist.setMvNum(node.path("mvSize").asInt(0));
        artist.setArtistFans(node.path("fansSize").asInt(0));
        artist.setIsStar(0);
        artist.setContentType(0L);
        return artist;
    }

    private PlayList toPlayList(JsonNode node) {
        PlayList playlist = new PlayList();
        playlist.setId(node.path("id").asLong(0));
        playlist.setName(node.path("name").asText(""));
        String pic = node.path("coverImgUrl").asText("");
        if (pic.isBlank()) {
            pic = node.path("picUrl").asText("");
        }
        playlist.setImg(pic);
        playlist.setImg700(pic);
        playlist.setUname(node.path("creator").path("nickname").asText("网易云音乐"));
        playlist.setDesc(node.path("description").asText(""));
        playlist.setInfo(node.path("updateFrequency").asText(""));
        playlist.setListencnt(node.path("playCount").asLong(0));
        playlist.setTotal(node.path("trackCount").asInt(0));
        playlist.setTag(node.path("tags").isArray() ? joinNames(node.path("tags"), "") : node.path("tags").asText(""));
        return playlist;
    }

    private static boolean isOfficialRank(long id) {
        for (long official : OFFICIAL_RANK_IDS) {
            if (official == id) {
                return true;
            }
        }
        return false;
    }

    /**
     * 搜索结果的排序权重。
     *
     * <p>网易云的搜索会把翻唱（原唱 / 女声版 / 钢琴版 / Cover / DJ 版……）排在原曲前面，
     * 而这些翻唱会被播放解析器拒绝。这里把「名字完全匹配」的提到最前，
     * 翻唱类结果整体压到后面，减少误点。</p>
     */
    private static void sortByRelevance(List<Music> list, String keyword) {
        if (list == null || list.size() < 2 || keyword == null || keyword.isBlank()) {
            return;
        }
        String key = normalize(keyword);
        list.sort((a, b) -> Integer.compare(relevance(a, key), relevance(b, key)));
    }

    private static final String[] COVER_MARKERS = {
            "原唱", "翻唱", "翻自", "cover", "女声", "男声", "童声", "钢琴", "吉他",
            "纯音乐", "伴奏", "dj", "remix", "karaoke", "instrumental", "片段",
            "抖音", "铃声", "live", "演唱会"
    };

    private static int relevance(Music music, String key) {
        if (music == null || music.getName() == null) {
            return 99;
        }
        String name = normalize(music.getName());
        int score;
        if (name.equals(key)) {
            score = 0;
        } else if (name.startsWith(key)) {
            score = 1;
        } else if (name.contains(key)) {
            score = 2;
        } else {
            score = 3;
        }
        String lower = name.toLowerCase();
        for (String marker : COVER_MARKERS) {
            if (lower.contains(marker)) {
                score += 10;
                break;
            }
        }
        return score;
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("\\s+", "").toLowerCase();
    }

    private static int toNeteaseArtistCat(int category) {
        // 酷我分类（0 全部 / 1 男 / 2 女 / 3 组合）映射到网易云华语分类
        return switch (category) {
            case 1 -> 1001; // 华语男歌手
            case 2 -> 1002; // 华语女歌手
            case 3 -> 1003; // 华语组合
            default -> 1001; // 全部：网易云没有「全部」分类，先显示华语男歌手
        };
    }

    private static String joinNames(JsonNode array, String field) {
        if (array == null || !array.isArray() || array.isEmpty()) {
            if (array != null && array.isTextual()) {
                return array.asText("");
            }
            return "";
        }
        List<String> names = new ArrayList<>();
        for (JsonNode node : array) {
            String value = field.isBlank() ? node.asText("") : node.path(field).asText("");
            if (!value.isBlank()) {
                names.add(value);
            }
        }
        return String.join("、", names);
    }

    private static String firstName(JsonNode array) {
        if (array != null && array.isArray() && !array.isEmpty()) {
            return array.get(0).asText("");
        }
        return "";
    }

    private static Long firstId(JsonNode array) {
        if (array != null && array.isArray() && !array.isEmpty()) {
            return array.get(0).path("id").asLong(0);
        }
        return 0L;
    }

    private static String formatDate(long millis) {
        if (millis <= 0) {
            return "";
        }
        LocalDate date = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate();
        return date.toString();
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static <T> List<T> slice(List<T> list, int pageNo, int pageSize) {
        int from = Math.max(0, pageNo - 1) * pageSize;
        if (from >= list.size()) {
            return Collections.emptyList();
        }
        int to = Math.min(list.size(), from + pageSize);
        return list.subList(from, to);
    }

    private static PlayListTag tag(String id, String name) {
        PlayListTag tag = new PlayListTag();
        tag.setId(id);
        tag.setName(name);
        return tag;
    }

    private static List<PlayListTag> tags(String... names) {
        List<PlayListTag> list = new ArrayList<>();
        for (String name : names) {
            list.add(tag(name, name));
        }
        return list;
    }

    private JsonNode cached(String key, ThrowingSupplier<JsonNode> supplier) {
        CacheEntry entry = cache.get(key);
        long now = System.currentTimeMillis();
        if (entry != null && entry.expireAt() > now) {
            return entry.node();
        }
        try {
            JsonNode node = supplier.get();
            if (node != null && !node.isMissingNode()) {
                if (cache.size() > 500) {
                    cache.entrySet().removeIf(e -> e.getValue().expireAt() <= now);
                }
                cache.put(key, new CacheEntry(node, now + CACHE_TTL));
            }
            return node;
        } catch (Exception e) {
            log.warn("网易云接口请求失败 key={} : {}", key, e.getMessage());
            return entry == null ? null : entry.node();
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
