package com.qymusic.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.qymusic.entity.PlayInfo;
import com.qymusic.result.Result;
import com.qymusic.service.PlayUrlResolver;

/**
 * 播放相关
 */
@RestController
@RequestMapping("/play")
@Tag(name = "播放")
public class PlayController {

    @Resource
    private PlayUrlResolver playUrlResolver;

    @GetMapping("/info")
    @Operation(summary = "获取音乐播放信息")
    public Result<PlayInfo> getPlayInfo(
            @Parameter(description = "音乐id", example = "1", required = true) @RequestParam String id,
            @Parameter(description = "码率", example = "2000kflac", required = true) @RequestParam String bridge,
            @Parameter(description = "音源平台：kw=酷我，wy=网易云") @RequestParam(required = false) String source,
            @Parameter(description = "歌名（非酷我歌曲需要）") @RequestParam(required = false) String name,
            @Parameter(description = "歌手（非酷我歌曲需要）") @RequestParam(required = false) String singer,
            @Parameter(description = "专辑（非酷我歌曲需要）") @RequestParam(required = false) String album,
            @Parameter(description = "时长秒（非酷我歌曲需要）") @RequestParam(required = false) Integer duration) {
        PlayInfo info = playUrlResolver.resolve(id, bridge, source, name, singer, album, duration);
        if (info == null || info.getUrl() == null || info.getUrl().isBlank()) {
            // 宁可提示无音源，也不返回错误的音频（翻唱 / 试听片段）
            return Result.error("该歌曲暂无完整音源（付费或版权限制），请换一首试试");
        }
        return Result.success(info);
    }

}
