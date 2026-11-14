package com.qymusic.controller.kuwo;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.qymusic.api.RankService;
import com.qymusic.dto.QueryRankMusicParams;
import com.qymusic.entity.MusicRank;
import com.qymusic.entity.RankMenu;
import com.qymusic.result.Result;

import java.util.List;

/**
 * 酷我排行榜（设置页切换「酷我」曲库时使用）。
 *
 * <p>与网易云版本并存：网易云走 /rank，酷我走 /kuwo/rank。</p>
 */
@RestController
@RequestMapping("/kuwo/rank")
@Tag(name = "排行榜（酷我）")
@Slf4j
public class KuwoRankController {

    @Resource
    private RankService rankService;

    @Operation(summary = "获取首页音乐排行榜")
    @GetMapping("/index")
    public Result<List<MusicRank>> getIndexRankList() {
        return rankService.getIndexRankList();
    }

    @Operation(summary = "获取排行榜菜单")
    @GetMapping("/menu")
    public Result<List<RankMenu>> getRankMenu() {
        Result<List<RankMenu>> result = rankService.getRankMenu();
        // 酷我偶发返回空数组，重试一次再交给客户端，避免排行榜页直接空白
        if (result == null || result.getData() == null || result.getData().isEmpty()) {
            result = rankService.getRankMenu();
        }
        return result;
    }

    @Operation(summary = "获取排行榜歌曲列表")
    @GetMapping("/getMusicList")
    public Result<MusicRank> getRankMusicList(@Validated QueryRankMusicParams params) {
        return rankService.getRankMusicList(params.getRankId(), params.getPageNo(), params.getPageSize());
    }
}
