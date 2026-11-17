package com.qymusic.controller.kg;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.qymusic.entity.MusicRank;
import com.qymusic.entity.RankMenu;
import com.qymusic.result.Result;
import com.qymusic.service.KugouCatalogService;

import java.util.List;

@RestController
@RequestMapping("/kg/rank")
@Tag(name = "排行榜（酷狗音乐）")
public class KgRankController {

    @Resource
    private KugouCatalogService kugouCatalogService;

    @Operation(summary = "排行榜菜单（酷狗音乐）")
    @GetMapping("/menu")
    public Result<List<RankMenu>> menu() {
        return kugouCatalogService.rankMenu();
    }

    @Operation(summary = "首页排行榜（酷狗音乐）")
    @GetMapping("/index")
    public Result<List<MusicRank>> index() {
        return kugouCatalogService.indexRank();
    }

    @Operation(summary = "排行榜歌曲（酷狗音乐）")
    @GetMapping("/getMusicList")
    public Result<MusicRank> getMusicList(
            @RequestParam(required = false) String rankId,
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "20") int pageSize) {
        long id = 0L;
        try {
            id = Long.parseLong(rankId == null ? "" : rankId.trim());
        } catch (Exception ignored) {
        }
        return kugouCatalogService.rankMusicList(id, pageNo, pageSize);
    }
}
