package com.qymusic.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.qymusic.result.Result;
import com.qymusic.service.QrLoginService;

import java.util.Map;

/**
 * 扫码登录：网易云 / QQ 音乐（酷我官方无扫码）
 */
@RestController
@RequestMapping("/login/qr")
@Tag(name = "扫码登录")
public class QrLoginController {

    @Resource
    private QrLoginService qrLoginService;

    @Operation(summary = "创建网易云登录二维码")
    @GetMapping("/netease")
    public Result<Map<String, Object>> netease() {
        try {
            return Result.success(qrLoginService.neteaseCreate());
        } catch (Exception e) {
            return Result.error(e.getMessage() == null ? "获取二维码失败" : e.getMessage());
        }
    }

    @Operation(summary = "轮询网易云扫码状态")
    @GetMapping("/netease/poll")
    public Result<Map<String, Object>> neteasePoll(@RequestParam String key) {
        try {
            return Result.success(qrLoginService.neteasePoll(key));
        } catch (Exception e) {
            return Result.error("轮询失败");
        }
    }

    @Operation(summary = "创建 QQ 音乐登录二维码")
    @GetMapping("/qq")
    public Result<Map<String, Object>> qq() {
        try {
            return Result.success(qrLoginService.qqCreate());
        } catch (Exception e) {
            return Result.error(e.getMessage() == null ? "获取二维码失败" : e.getMessage());
        }
    }

    @Operation(summary = "轮询 QQ 扫码状态")
    @GetMapping("/qq/poll")
    public Result<Map<String, Object>> qqPoll(@RequestParam String qrsig) {
        try {
            return Result.success(qrLoginService.qqPoll(qrsig));
        } catch (Exception e) {
            return Result.error("轮询失败");
        }
    }
}
