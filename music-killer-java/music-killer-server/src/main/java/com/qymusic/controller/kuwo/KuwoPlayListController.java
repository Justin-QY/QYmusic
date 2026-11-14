package com.qymusic.controller.kuwo;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.qymusic.api.PlayListService;
import com.qymusic.data.PageResponse;
import com.qymusic.dto.QueryPlaylistParams;
import com.qymusic.entity.PlayList;
import com.qymusic.entity.PlayListTag;
import com.qymusic.result.PageResult;
import com.qymusic.result.Result;

import java.util.List;

/**
 * 酷我歌单（设置页切换「酷我」曲库时使用）。
 */
@RestController
@RequestMapping("/kuwo/playlist")
@Tag(name = "歌单（酷我）")
public class KuwoPlayListController {

    @Resource
    private PlayListService playlistService;

    @Operation(summary = "获取推荐歌单")
    @GetMapping("/recommend")
    public Result<PageResponse<PlayList>> getRecommendPlaylistData() {
        return playlistService.getRecommendPlayList("rcm");
    }

    @Operation(summary = "获取分类歌单")
    @GetMapping("/getPlayListByTag")
    public Result<PageResult<PlayList>> getPlaylistDataByTag(
            @Parameter(description = "标签id", required = true) String id) {
        return playlistService.getPlayListByTag(id);
    }

    @Operation(summary = "获取首页歌单标签列表")
    @GetMapping("/getIndexPlayListTags")
    public Result<List<PlayListTag>> getIndexPlayListTags() {
        return playlistService.getIndexPlayListTags();
    }

    @Operation(summary = "获取歌单标签列表")
    @GetMapping("/getPlayListTags")
    public Result<List<PlayListTag>> getPlayListTags() {
        return playlistService.getPlayListTags();
    }

    @Operation(summary = "获取歌单分页列表")
    @GetMapping("/page")
    public Result<PageResult<PlayList>> getPlayListPage(QueryPlaylistParams queryPlaylistParams) {
        return playlistService.getRcmPlayList(queryPlaylistParams.getPageNo(),
                queryPlaylistParams.getPageSize(), queryPlaylistParams.getOrder());
    }

    @Operation(summary = "获取分类歌单列表")
    @GetMapping("/getTagPlaylist")
    public Result<PageResult<PlayList>> getTagPlayListPage(QueryPlaylistParams queryPlaylistParams) {
        return playlistService.getTagPlayList(queryPlaylistParams.getId(),
                queryPlaylistParams.getPageNo(), queryPlaylistParams.getPageSize(), queryPlaylistParams.getOrder());
    }

    @Operation(summary = "获取歌单信息")
    @GetMapping("/info")
    public Result<PlayList> getPlayListById(@ModelAttribute QueryPlaylistParams queryPlaylistParams) {
        return playlistService.getPlayListInfo(queryPlaylistParams.getPid(),
                queryPlaylistParams.getPageNo(), queryPlaylistParams.getPageSize());
    }
}
