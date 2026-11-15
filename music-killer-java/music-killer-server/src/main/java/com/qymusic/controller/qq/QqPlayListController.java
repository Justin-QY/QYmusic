package com.qymusic.controller.qq;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.qymusic.data.PageResponse;
import com.qymusic.dto.QueryPlaylistParams;
import com.qymusic.entity.PlayList;
import com.qymusic.entity.PlayListTag;
import com.qymusic.result.PageResult;
import com.qymusic.result.Result;
import com.qymusic.service.QqCatalogService;

import java.util.List;

@RestController
@RequestMapping("/qq/playlist")
@Tag(name = "歌单（QQ音乐）")
public class QqPlayListController {

    @Resource
    private QqCatalogService qqCatalogService;

    @Operation(summary = "推荐歌单（QQ音乐）")
    @GetMapping("/recommend")
    public Result<PageResponse<PlayList>> recommend() {
        return qqCatalogService.recommendPlaylist();
    }

    @Operation(summary = "首页歌单标签（QQ音乐）")
    @GetMapping("/getIndexPlayListTags")
    public Result<List<PlayListTag>> indexTags() {
        return qqCatalogService.indexTags();
    }

    @Operation(summary = "歌单标签（QQ音乐）")
    @GetMapping("/getPlayListTags")
    public Result<List<PlayListTag>> tags() {
        return qqCatalogService.playlistTags();
    }

    @Operation(summary = "歌单分页（QQ音乐）")
    @GetMapping("/page")
    public Result<PageResult<PlayList>> page(QueryPlaylistParams params) {
        return qqCatalogService.playlistPage("热门歌单", params.getPageNo(), params.getPageSize());
    }

    @Operation(summary = "分类歌单（QQ音乐）")
    @GetMapping("/getTagPlaylist")
    public Result<PageResult<PlayList>> byTag(QueryPlaylistParams params) {
        return qqCatalogService.playlistPage(params.getId(), params.getPageNo(), params.getPageSize());
    }

    @Operation(summary = "歌单详情（QQ音乐）")
    @GetMapping("/info")
    public Result<PlayList> info(QueryPlaylistParams params) {
        long pid = 0L;
        try {
            pid = Long.parseLong(params.getPid());
        } catch (Exception ignored) {
        }
        return qqCatalogService.playlistInfo(pid, params.getPageNo(), params.getPageSize());
    }
}
