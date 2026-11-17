package com.qymusic.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.qymusic.annotation.RateLimit;
import com.qymusic.dto.QueryMusicParams;
import com.qymusic.enums.RateLimitType;
import com.qymusic.result.Result;
import com.qymusic.service.NeteaseCatalogService;
import com.qymusic.vo.SearchAlbumResultVO;
import com.qymusic.vo.SearchArtistResultVO;
import com.qymusic.vo.SearchMusicResultVO;
import com.qymusic.vo.SearchPlaylistResultVO;

import java.util.List;

/**
 * 搜索。
 *
 * <p>默认走网易云（App 的「网易云搜索」）；酷狗搜索见 {@code /kg/search}。</p>
 */
@Slf4j
@RestController
@RequestMapping("/search")
@Tag(name = "搜索")
public class SearchController {

    @Resource
    private NeteaseCatalogService neteaseCatalogService;

    @GetMapping("/key")
    @Operation(summary = "获取搜索关键字列表", description = "60秒只能调用120次")
    @RateLimit(limitType = RateLimitType.IP, count = 180)
    public Result<List<String>> getKeyList(@Parameter(description = "关键字", example = "hello")
                                           @RequestParam(required = false) String key) {
        return neteaseCatalogService.searchKey(key);
    }

    @GetMapping
    @Operation(summary = "根据关键字搜索歌曲（网易云）")
    @RateLimit(limitType = RateLimitType.IP, count = 30)
    public Result<SearchMusicResultVO> searchMusicByKeyword(@Validated QueryMusicParams params) {
        return neteaseCatalogService.searchMusic(params.getKeyword(),
                Math.max(1, params.getPageNo()), clampPageSize(params.getPageSize()));
    }

    @GetMapping("/netease")
    @Operation(summary = "网易云搜索歌曲")
    @RateLimit(limitType = RateLimitType.IP, count = 30)
    public Result<SearchMusicResultVO> searchNetease(@Validated QueryMusicParams params) {
        return neteaseCatalogService.searchMusic(params.getKeyword(),
                Math.max(1, params.getPageNo()), clampPageSize(params.getPageSize()));
    }

    /**
     * 酷我搜索已下线，酷狗搜索见 /kg/search。
     */

    @Operation(summary = "搜索专辑（网易云）")
    @RateLimit(limitType = RateLimitType.IP, count = 30)
    @GetMapping("/album")
    public Result<SearchAlbumResultVO> searchAlbumByKeyword(@Validated QueryMusicParams params) {
        return neteaseCatalogService.searchAlbum(params.getKeyword(),
                Math.max(1, params.getPageNo()), clampPageSize(params.getPageSize()));
    }

    @Operation(summary = "搜索歌单（网易云）")
    @RateLimit(limitType = RateLimitType.IP, count = 30)
    @GetMapping("/playlist")
    public Result<SearchPlaylistResultVO> searchPlaylistByKeyword(@Validated QueryMusicParams params) {
        return neteaseCatalogService.searchPlaylist(params.getKeyword(),
                Math.max(1, params.getPageNo()), clampPageSize(params.getPageSize()));
    }

    @Operation(summary = "搜索歌手（网易云）")
    @RateLimit(limitType = RateLimitType.IP, count = 30)
    @GetMapping("/artist")
    public Result<SearchArtistResultVO> searchArtistByKeyword(@Validated QueryMusicParams params) {
        return neteaseCatalogService.searchArtist(params.getKeyword(),
                Math.max(1, params.getPageNo()), clampPageSize(params.getPageSize()));
    }

    private int clampPageSize(Integer pageSize) {
        if (pageSize == null || pageSize <= 0) {
            return 20;
        }
        return Math.min(pageSize, 100);
    }
}
