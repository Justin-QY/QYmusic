package com.qymusic.controller.kg;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.qymusic.entity.PlayList;
import com.qymusic.result.Result;
import com.qymusic.service.KugouCatalogService;
import com.qymusic.vo.ArtistPageVO;
import com.qymusic.vo.SearchAlbumResultVO;
import com.qymusic.vo.SearchMusicResultVO;

import java.util.List;

/**
 * 酷狗音乐搜索（概念版）
 */
@RestController
@RequestMapping("/kg/search")
@Tag(name = "搜索（酷狗音乐）")
public class KgSearchController {

    @Resource
    private KugouCatalogService kugouCatalogService;

    @Operation(summary = "搜索歌曲（酷狗音乐）")
    @GetMapping
    public Result<SearchMusicResultVO> searchMusic(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String key,
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "20") int pageSize) {
        return kugouCatalogService.searchMusic(keyword == null || keyword.isBlank() ? key : keyword,
                pageNo, pageSize);
    }

    @Operation(summary = "搜索专辑（酷狗音乐）")
    @GetMapping("/album")
    public Result<SearchAlbumResultVO> searchAlbum(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String key,
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "20") int pageSize) {
        return kugouCatalogService.searchAlbum(keyword == null || keyword.isBlank() ? key : keyword,
                pageNo, pageSize);
    }

    @Operation(summary = "搜索歌单（酷狗音乐）")
    @GetMapping("/playlist")
    public Result<List<PlayList>> searchPlaylist(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String key,
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "20") int pageSize) {
        return kugouCatalogService.searchPlaylist(keyword == null || keyword.isBlank() ? key : keyword,
                pageNo, pageSize);
    }

    @Operation(summary = "搜索歌手（酷狗音乐）")
    @GetMapping("/artist")
    public Result<ArtistPageVO> searchArtist(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String key,
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "20") int pageSize) {
        return kugouCatalogService.searchArtist(keyword == null || keyword.isBlank() ? key : keyword,
                pageNo, pageSize);
    }

    @Operation(summary = "搜索联想（酷狗音乐）")
    @GetMapping("/key")
    public Result<String[]> searchKey(@RequestParam(required = false) String key) {
        return kugouCatalogService.searchKey(key);
    }
}
