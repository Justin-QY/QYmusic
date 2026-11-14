package com.qymusic.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.qymusic.entity.Artist;
import com.qymusic.entity.Music;
import com.qymusic.entity.PlayList;
import com.qymusic.result.Result;
import com.qymusic.service.NeteaseUserService;

import java.util.List;
import java.util.Map;

/**
 * 网易云登录用户数据（云端模块）
 */
@RestController
@RequestMapping("/netease/user")
@Tag(name = "网易云账号")
public class NeteaseUserController {

    @Resource
    private NeteaseUserService neteaseUserService;

    @Operation(summary = "当前登录账号信息")
    @GetMapping("/account")
    public Result<Map<String, Object>> account(@RequestParam String cookie) {
        return neteaseUserService.account(cookie);
    }

    @Operation(summary = "收藏的歌单")
    @GetMapping("/playlists")
    public Result<List<PlayList>> playlists(@RequestParam String cookie,
                                            @RequestParam long uid) {
        return neteaseUserService.playlists(cookie, uid);
    }

    @Operation(summary = "收藏的歌手")
    @GetMapping("/artists")
    public Result<List<Artist>> artists(@RequestParam String cookie) {
        return neteaseUserService.artists(cookie);
    }

    @Operation(summary = "我喜欢的音乐")
    @GetMapping("/liked")
    public Result<List<Music>> liked(@RequestParam String cookie,
                                     @RequestParam long uid,
                                     @RequestParam(defaultValue = "1") int pageNo,
                                     @RequestParam(defaultValue = "30") int pageSize) {
        return neteaseUserService.liked(cookie, uid, pageNo, pageSize);
    }
}
