package com.qymusic.controller.qq;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.qymusic.result.Result;
import com.qymusic.service.QqCatalogService;

@RestController
@RequestMapping("/qq/lyric")
@Tag(name = "歌词（QQ音乐）")
public class QqLyricController {

    @Resource
    private QqCatalogService qqCatalogService;

    @Operation(summary = "歌词（QQ音乐）")
    @GetMapping("/{id}")
    public Result<String> lyric(@PathVariable Long id,
                                @RequestParam(required = false) String sourceId) {
        return qqCatalogService.lyric(sourceId, id);
    }
}
