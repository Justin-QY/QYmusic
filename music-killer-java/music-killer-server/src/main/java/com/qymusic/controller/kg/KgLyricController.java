package com.qymusic.controller.kg;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.qymusic.result.Result;
import com.qymusic.service.KugouCatalogService;

@RestController
@RequestMapping("/kg/lyric")
@Tag(name = "歌词（酷狗音乐）")
public class KgLyricController {

    @Resource
    private KugouCatalogService kugouCatalogService;

    @Operation(summary = "歌词（酷狗音乐，KRC 解密后返回 LRC）")
    @GetMapping("/{id}")
    public Result<String> lyric(@PathVariable Long id,
                                @RequestParam(required = false) String sourceId) {
        return kugouCatalogService.lyric(sourceId);
    }
}
