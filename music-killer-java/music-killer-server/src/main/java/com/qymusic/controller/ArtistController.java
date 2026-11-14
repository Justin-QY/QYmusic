package com.qymusic.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.qymusic.data.PageResponse;
import com.qymusic.dto.QueryArtistListParams;
import com.qymusic.dto.QueryArtistMusicParams;
import com.qymusic.entity.Artist;
import com.qymusic.entity.Music;
import com.qymusic.result.Result;
import com.qymusic.service.NeteaseCatalogService;
import com.qymusic.vo.ArtistPageVO;

/**
 * 歌手（网易云）。
 */
@RestController
@RequestMapping("/artist")
@Tag(name = "歌手")
public class ArtistController {

    @Resource
    private NeteaseCatalogService neteaseCatalogService;

    @GetMapping("/list")
    @Operation(summary = "获取歌手列表")
    public Result<ArtistPageVO> getArtistList(@Validated QueryArtistListParams params) {
        return neteaseCatalogService.artistList(params.getCategory(), params.getPrefix(),
                params.getPageNo(), params.getPageSize());
    }

    @GetMapping("/info")
    @Operation(summary = "获取歌手信息")
    public Result<Artist> getArtistInfo(Long artistId) {
        return neteaseCatalogService.artistInfo(artistId);
    }

    @GetMapping("/music")
    @Operation(summary = "获取歌手歌曲列表")
    public Result<PageResponse<Music>> getMusicList(@Validated QueryArtistMusicParams params) {
        return neteaseCatalogService.artistMusicList(params.getArtistId(),
                params.getPageNo(), params.getPageSize());
    }
}
