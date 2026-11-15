package com.qymusic.controller.qq;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.qymusic.dto.QueryRankMusicParams;
import com.qymusic.entity.MusicRank;
import com.qymusic.entity.RankMenu;
import com.qymusic.result.Result;
import com.qymusic.service.QqCatalogService;

import java.util.List;

@RestController
@RequestMapping("/qq/rank")
@Tag(name = "排行榜（QQ音乐）")
public class QqRankController {

    @Resource
    private QqCatalogService qqCatalogService;

    @Operation(summary = "排行榜菜单（QQ音乐）")
    @GetMapping("/menu")
    public Result<List<RankMenu>> menu() {
        return qqCatalogService.rankMenu();
    }

    @Operation(summary = "首页排行榜（QQ音乐）")
    @GetMapping("/index")
    public Result<List<MusicRank>> index() {
        return qqCatalogService.indexRank();
    }

    @Operation(summary = "排行榜歌曲（QQ音乐）")
    @GetMapping("/getMusicList")
    public Result<MusicRank> getMusicList(@Validated QueryRankMusicParams params) {
        long rankId = 26L;
        try {
            rankId = Long.parseLong(params.getRankId());
        } catch (Exception ignored) {
        }
        return qqCatalogService.rankMusicList(rankId, params.getPageNo(), params.getPageSize());
    }
}
