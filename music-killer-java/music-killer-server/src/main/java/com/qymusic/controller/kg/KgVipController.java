package com.qymusic.controller.kg;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.qymusic.result.Result;
import com.qymusic.service.KugouUserService;

import java.util.Map;

@RestController
@RequestMapping("/kg/vip")
@Tag(name = "酷狗会员（概念版）")
public class KgVipController {

    @Resource
    private KugouUserService kugouUserService;

    @Operation(summary = "酷狗概念版会员状态")
    @GetMapping("/status")
    public Result<Map<String, Object>> status(@RequestParam String cookie) {
        return kugouUserService.vipStatus(cookie);
    }

    @Operation(summary = "酷狗概念版每日签到（畅听 VIP + 升级奖励）")
    @GetMapping("/checkin")
    public Result<Map<String, Object>> checkin(@RequestParam String cookie) {
        return kugouUserService.checkin(cookie);
    }
}
