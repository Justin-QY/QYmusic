package com.qymusic.controller;

import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.qymusic.entity.LiveItem;
import com.qymusic.result.Result;
import com.qymusic.service.LiveService;

import java.util.List;

@RestController
@RequestMapping("/live")
public class LiveController {

    @Resource
    private LiveService liveService;

    @GetMapping("/list")
    public Result<List<LiveItem>> getLiveItems() {
        return Result.success(liveService.getItems());
    }

}
