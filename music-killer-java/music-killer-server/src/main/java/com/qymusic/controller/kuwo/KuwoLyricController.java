package com.qymusic.controller.kuwo;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.qymusic.api.LyricService;
import com.qymusic.result.Result;

/**
 * 酷我歌词（设置页切换「酷我」曲库时使用）。
 */
@RestController
@RequestMapping("/kuwo/lyric")
@Tag(name = "歌词（酷我）")
public class KuwoLyricController {

    @Resource
    private LyricService lyricService;

    @Operation(summary = "获取歌词数据", description = "通过歌曲id获取到歌词数据")
    @GetMapping("/{id}")
    public Result<String> getLyric(@Parameter(description = "歌曲id", required = true, example = "1")
                                  @PathVariable Long id) {
        return Result.success(lyricService.getLrc(id));
    }
}
