package com.qymusic.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.qymusic.data.PageResponse;
import com.qymusic.dto.QueryPlaylistParams;
import com.qymusic.entity.PlayList;
import com.qymusic.entity.PlayListTag;
import com.qymusic.result.PageResult;
import com.qymusic.result.Result;
import com.qymusic.service.NeteaseCatalogService;

import java.util.List;

/**
 * 歌单（网易云）。
 */
@RestController
@RequestMapping("/playlist")
@Tag(name = "歌单")
public class PlayListController {

    @Resource
    private NeteaseCatalogService neteaseCatalogService;

    @Operation(summary = "获取推荐歌单")
    @GetMapping("/recommend")
    public Result<PageResponse<PlayList>> getRecommendPlaylistData() {
        return neteaseCatalogService.recommendPlaylists();
    }

    @Operation(summary = "获取分类歌单")
    @GetMapping("/getPlayListByTag")
    public Result<PageResult<PlayList>> getPlaylistDataByTag(
            @Parameter(description = "标签", required = true) String id) {
        return neteaseCatalogService.playListByTag(id);
    }

    @Operation(summary = "获取首页歌单标签列表")
    @GetMapping("/getIndexPlayListTags")
    public Result<List<PlayListTag>> getIndexPlayListTags() {
        return neteaseCatalogService.indexPlayListTags();
    }

    @Operation(summary = "获取歌单标签列表")
    @GetMapping("/getPlayListTags")
    public Result<List<PlayListTag>> getPlayListTags() {
        return neteaseCatalogService.playListTags();
    }

    @Operation(summary = "获取歌单首页列表")
    @GetMapping("/page")
    public Result<PageResult<PlayList>> getPlayListPage(QueryPlaylistParams queryPlaylistParams) {
        return neteaseCatalogService.playListPage(queryPlaylistParams.getPageNo(),
                queryPlaylistParams.getPageSize(), queryPlaylistParams.getOrder());
    }

    @Operation(summary = "获取分类歌单列表")
    @GetMapping("/getTagPlaylist")
    public Result<PageResult<PlayList>> getTagPlayListPage(QueryPlaylistParams queryPlaylistParams) {
        return neteaseCatalogService.playListPage(queryPlaylistParams.getId(),
                queryPlaylistParams.getPageNo(), queryPlaylistParams.getPageSize(),
                queryPlaylistParams.getOrder());
    }

    @Operation(summary = "获取歌单信息")
    @GetMapping("/info")
    public Result<PlayList> getPlayListById(@ModelAttribute QueryPlaylistParams queryPlaylistParams) {
        return neteaseCatalogService.playListInfo(queryPlaylistParams.getPid(),
                queryPlaylistParams.getPageNo(), queryPlaylistParams.getPageSize());
    }
}
