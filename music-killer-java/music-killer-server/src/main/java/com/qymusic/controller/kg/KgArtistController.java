package com.qymusic.controller.kg;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.qymusic.data.PageResponse;
import com.qymusic.entity.Artist;
import com.qymusic.entity.Music;
import com.qymusic.result.Result;
import com.qymusic.service.KugouCatalogService;
import com.qymusic.vo.ArtistPageVO;

@RestController
@RequestMapping("/kg/artist")
@Tag(name = "歌手（酷狗音乐）")
public class KgArtistController {

    @Resource
    private KugouCatalogService kugouCatalogService;

    @Operation(summary = "歌手列表（酷狗音乐）")
    @GetMapping("/list")
    public Result<ArtistPageVO> list(
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "30") int pageSize) {
        return kugouCatalogService.artistList(pageNo, pageSize);
    }

    @Operation(summary = "歌手信息（酷狗音乐）")
    @GetMapping("/info")
    public Result<Artist> info(@RequestParam(required = false) String sourceId,
                               @RequestParam(required = false) String artistId,
                               @RequestParam(required = false) String id) {
        return kugouCatalogService.artistInfo(firstNonBlank(sourceId, artistId, id));
    }

    @Operation(summary = "歌手歌曲（酷狗音乐）")
    @GetMapping("/music")
    public Result<PageResponse<Music>> music(
            @RequestParam(required = false) String sourceId,
            @RequestParam(required = false) String artistId,
            @RequestParam(required = false) String id,
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "30") int pageSize) {
        return kugouCatalogService.artistMusic(firstNonBlank(sourceId, artistId, id),
                pageNo, pageSize);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
