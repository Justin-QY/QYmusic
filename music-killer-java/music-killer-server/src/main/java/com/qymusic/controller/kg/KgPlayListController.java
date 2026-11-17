package com.qymusic.controller.kg;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.qymusic.data.PageResponse;
import com.qymusic.entity.PlayList;
import com.qymusic.entity.PlayListTag;
import com.qymusic.result.PageResult;
import com.qymusic.result.Result;
import com.qymusic.service.KugouCatalogService;

import java.util.List;

@RestController
@RequestMapping("/kg/playlist")
@Tag(name = "歌单（酷狗音乐）")
public class KgPlayListController {

    @Resource
    private KugouCatalogService kugouCatalogService;

    @Operation(summary = "推荐歌单（酷狗音乐）")
    @GetMapping("/recommend")
    public Result<PageResponse<PlayList>> recommend() {
        return kugouCatalogService.recommendPlaylist();
    }

    @Operation(summary = "按标签获取歌单（酷狗音乐）")
    @GetMapping("/getPlayListByTag")
    public Result<PageResult<PlayList>> getPlayListByTag(
            @RequestParam(required = false) String id,
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "20") int pageSize) {
        return kugouCatalogService.playlistByTag(id, pageNo, pageSize);
    }

    @Operation(summary = "首页歌单标签（酷狗音乐）")
    @GetMapping("/getIndexPlayListTags")
    public Result<List<PlayListTag>> indexTags() {
        return kugouCatalogService.indexTags();
    }

    @Operation(summary = "歌单标签（酷狗音乐）")
    @GetMapping("/getPlayListTags")
    public Result<List<PlayListTag>> tags() {
        return kugouCatalogService.playlistTags();
    }

    @Operation(summary = "歌单分页（酷狗音乐）")
    @GetMapping("/page")
    public Result<PageResult<PlayList>> page(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "20") int pageSize) {
        return kugouCatalogService.playlistPage(keyword, pageNo, pageSize);
    }

    @Operation(summary = "分类歌单（酷狗音乐）")
    @GetMapping("/getTagPlaylist")
    public Result<PageResult<PlayList>> getTagPlaylist(
            @RequestParam(required = false) String id,
            @RequestParam(required = false) String tag,
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "20") int pageSize) {
        String key = tag == null || tag.isBlank() ? id : tag;
        return kugouCatalogService.playlistByTag(key, pageNo, pageSize);
    }

    @Operation(summary = "歌单详情（酷狗音乐）")
    @GetMapping("/info")
    public Result<PlayList> info(
            @RequestParam(required = false) String pid,
            @RequestParam(required = false) String id,
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "300") int pageSize) {
        String raw = pid == null || pid.isBlank() ? id : pid;
        long playlistId = 0L;
        try {
            playlistId = Long.parseLong(raw == null ? "" : raw.trim());
        } catch (Exception ignored) {
        }
        return kugouCatalogService.playlistInfo(playlistId, pageNo, pageSize);
    }
}
