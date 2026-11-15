package com.qymusic.controller.qq;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.qymusic.data.PageResponse;
import com.qymusic.dto.QueryArtistListParams;
import com.qymusic.dto.QueryArtistMusicParams;
import com.qymusic.entity.Artist;
import com.qymusic.entity.Music;
import com.qymusic.result.Result;
import com.qymusic.service.QqCatalogService;
import com.qymusic.vo.ArtistPageVO;

@RestController
@RequestMapping("/qq/artist")
@Tag(name = "歌手（QQ音乐）")
public class QqArtistController {

    @Resource
    private QqCatalogService qqCatalogService;

    @Operation(summary = "歌手列表（QQ音乐）")
    @GetMapping("/list")
    public Result<ArtistPageVO> list(@Validated QueryArtistListParams params) {
        return qqCatalogService.artistList(params.getPageNo(), params.getPageSize());
    }

    @Operation(summary = "歌手歌曲（QQ音乐）")
    @GetMapping("/music")
    public Result<PageResponse<Music>> music(@Validated QueryArtistMusicParams params,
                                             @RequestParam(required = false) String sourceId) {
        return qqCatalogService.artistMusic(sourceId, params.getPageNo(), params.getPageSize());
    }

    @Operation(summary = "歌手信息（QQ音乐）")
    @GetMapping("/info")
    public Result<Artist> info(@RequestParam(required = false) String sourceId) {
        return qqCatalogService.artistInfo(sourceId);
    }
}
