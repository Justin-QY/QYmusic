package com.qymusic.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.qymusic.dto.QueryRankMusicParams;
import com.qymusic.entity.MusicRank;
import com.qymusic.entity.RankMenu;
import com.qymusic.result.Result;
import com.qymusic.service.NeteaseCatalogService;

import java.util.List;

/**
 * 排行榜（网易云）。
 */
@RestController
@RequestMapping("/rank")
@Tag(name = "排行榜")
@Slf4j
public class RankController {

    @Resource
    private NeteaseCatalogService neteaseCatalogService;

    @Operation(summary = "获取首页的排行榜")
    @GetMapping("/index")
    public Result<List<MusicRank>> getIndexRankList() {
        return neteaseCatalogService.indexRank();
    }

    @Operation(summary = "获取排行榜菜单")
    @GetMapping("/menu")
    public Result<List<RankMenu>> getRankMenu() {
        return neteaseCatalogService.rankMenu();
    }

    @Operation(summary = "获取排行榜歌曲列表")
    @GetMapping("/getMusicList")
    public Result<MusicRank> getRankMusicList(@Validated QueryRankMusicParams params) {
        return neteaseCatalogService.rankMusicList(params.getRankId(),
                params.getPageNo(), params.getPageSize());
    }
}
