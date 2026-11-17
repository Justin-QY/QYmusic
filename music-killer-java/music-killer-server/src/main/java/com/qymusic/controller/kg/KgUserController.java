package com.qymusic.controller.kg;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.qymusic.entity.Music;
import com.qymusic.entity.PlayList;
import com.qymusic.result.Result;
import com.qymusic.service.KugouUserService;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/kg/user")
@Tag(name = "酷狗音乐账号")
public class KgUserController {

    @Resource
    private KugouUserService kugouUserService;

    @Operation(summary = "酷狗账号信息")
    @GetMapping("/account")
    public Result<Map<String, Object>> account(@RequestParam String cookie) {
        return kugouUserService.account(cookie);
    }

    @Operation(summary = "酷狗收藏歌单")
    @GetMapping("/playlists")
    public Result<List<PlayList>> playlists(@RequestParam String cookie) {
        return kugouUserService.playlists(cookie);
    }

    @Operation(summary = "酷狗我喜欢的音乐（概念版暂无稳定接口，返回空列表）")
    @GetMapping("/liked")
    public Result<List<Music>> liked(@RequestParam String cookie,
                                     @RequestParam(defaultValue = "30") int limit) {
        return kugouUserService.liked(cookie, limit);
    }
}
