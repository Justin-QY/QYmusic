package com.qymusic.controller.qq;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.qymusic.dto.QueryMusicParams;
import com.qymusic.entity.Artist;
import com.qymusic.entity.PlayList;
import com.qymusic.result.Result;
import com.qymusic.service.QqCatalogService;
import com.qymusic.vo.ArtistPageVO;
import com.qymusic.vo.SearchMusicResultVO;

import java.util.List;

/**
 * QQ 音乐搜索（匿名）
 */
@RestController
@RequestMapping("/qq/search")
@Tag(name = "搜索（QQ音乐）")
public class QqSearchController {

    @Resource
    private QqCatalogService qqCatalogService;

    @Operation(summary = "搜索歌曲（QQ音乐）")
    @GetMapping
    public Result<SearchMusicResultVO> searchMusic(@Validated QueryMusicParams params) {
        return qqCatalogService.searchMusic(params.getKeyword(), params.getPageNo(), params.getPageSize());
    }

    @Operation(summary = "搜索歌手（QQ音乐）")
    @GetMapping("/artist")
    public Result<ArtistPageVO> searchArtist(@Validated QueryMusicParams params) {
        return qqCatalogService.searchArtist(params.getKeyword(), params.getPageNo(), params.getPageSize());
    }

    @Operation(summary = "搜索歌单（QQ音乐）")
    @GetMapping("/playlist")
    public Result<List<PlayList>> searchPlaylist(@Validated QueryMusicParams params) {
        return qqCatalogService.searchPlaylist(params.getKeyword(), params.getPageNo(), params.getPageSize());
    }

    @Operation(summary = "搜索专辑（QQ音乐，暂未开放）")
    @GetMapping("/album")
    public Result<com.qymusic.vo.SearchAlbumResultVO> searchAlbum(@Validated QueryMusicParams params) {
        com.qymusic.vo.SearchAlbumResultVO vo = new com.qymusic.vo.SearchAlbumResultVO();
        vo.setData(new java.util.ArrayList<>());
        vo.setTotal(0);
        return Result.success(vo);
    }

    @Operation(summary = "搜索联想（QQ音乐，暂不支持）")
    @GetMapping("/key")
    public Result<String[]> searchKey(@RequestParam(required = false) String key) {
        return Result.success(new String[0]);
    }
}
