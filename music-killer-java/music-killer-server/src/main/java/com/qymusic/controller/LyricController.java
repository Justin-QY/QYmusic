package com.qymusic.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.qymusic.result.Result;
import com.qymusic.service.NeteaseCatalogService;

/**
 * 歌词（网易云）。
 *
 * <p>酷我歌曲客户端仍然直连酷我歌词接口，不走这里。</p>
 */
@RestController
@RequestMapping("/lyric")
@Tag(name = "歌词")
public class LyricController {

    @Resource
    private NeteaseCatalogService neteaseCatalogService;

    @Operation(summary = "获取歌曲歌词", description = "通过歌曲 id 获取网易云 LRC 歌词")
    @GetMapping("/{id}")
    public Result<String> getLyric(@Parameter(description = "歌曲 id", required = true, example = "186016")
                                   @PathVariable Long id,
                                   @Parameter(description = "是否合并翻译歌词", example = "false")
                                   @RequestParam(defaultValue = "false") boolean translate) {
        return neteaseCatalogService.lyric(id, translate);
    }
}
