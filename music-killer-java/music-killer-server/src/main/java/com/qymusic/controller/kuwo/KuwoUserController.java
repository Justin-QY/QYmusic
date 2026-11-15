package com.qymusic.controller.kuwo;

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
import com.qymusic.service.KuwoUserService;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/kuwo/user")
@Tag(name = "酷我账号")
public class KuwoUserController {

    @Resource
    private KuwoUserService kuwoUserService;

    @Operation(summary = "酷我账号信息")
    @GetMapping("/account")
    public Result<Map<String, Object>> account(@RequestParam String cookie) {
        return kuwoUserService.account(cookie);
    }

    @Operation(summary = "酷我收藏歌单")
    @GetMapping("/playlists")
    public Result<List<PlayList>> playlists(@RequestParam String cookie) {
        return kuwoUserService.playlists(cookie);
    }

    @Operation(summary = "酷我我喜欢的音乐")
    @GetMapping("/liked")
    public Result<List<Music>> liked(@RequestParam String cookie,
                                     @RequestParam(defaultValue = "30") int limit) {
        return kuwoUserService.liked(cookie, limit);
    }
}
