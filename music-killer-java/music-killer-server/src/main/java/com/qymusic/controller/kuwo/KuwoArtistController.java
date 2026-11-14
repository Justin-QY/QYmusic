package com.qymusic.controller.kuwo;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.qymusic.api.ArtistService;
import com.qymusic.data.PageResponse;
import com.qymusic.dto.QueryArtistListParams;
import com.qymusic.dto.QueryArtistMusicParams;
import com.qymusic.entity.Artist;
import com.qymusic.entity.Music;
import com.qymusic.result.Result;
import com.qymusic.vo.ArtistPageVO;

/**
 * 酷我歌手（设置页切换「酷我」曲库时使用）。
 */
@RestController
@RequestMapping("/kuwo/artist")
@Tag(name = "歌手（酷我）")
public class KuwoArtistController {

    @Resource
    private ArtistService artistService;

    @GetMapping("/list")
    @Operation(summary = "获取歌手列表")
    public Result<ArtistPageVO> getArtistList(@Validated QueryArtistListParams params) {
        Result<ArtistPageVO> result = artistService.getArtistList(params.getCategory(), params.getPrefix(),
                params.getPageNo(), params.getPageSize());
        // 上游偶发返回空列表，重试一次，避免歌手页空白
        if (result == null || result.getData() == null
                || result.getData().getData() == null || result.getData().getData().isEmpty()) {
            result = artistService.getArtistList(params.getCategory(), params.getPrefix(),
                    params.getPageNo(), params.getPageSize());
        }
        return result;
    }

    @GetMapping("/info")
    @Operation(summary = "获取歌手信息")
    public Result<Artist> getArtistInfo(Long artistId) {
        return artistService.getArtistInfo(artistId);
    }

    @GetMapping("/music")
    @Operation(summary = "获取歌手歌曲列表")
    public Result<PageResponse<Music>> getMusicList(@Validated QueryArtistMusicParams params) {
        return artistService.getArtistMusicList(params.getArtistId(), params.getPageNo(), params.getPageSize());
    }
}
