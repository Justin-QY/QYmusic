package com.qymusic.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.qymusic.lx.LxSourceService;
import com.qymusic.result.Result;

import java.util.List;
import java.util.Map;

/**
 * 音源脚本管理（需要与其它接口一致的签名头）
 */
@RestController
@RequestMapping("/lx")
@Tag(name = "音源脚本")
public class LxController {

    @Resource
    private LxSourceService lxSourceService;

    @GetMapping("/status")
    @Operation(summary = "音源脚本引擎状态")
    public Result<Map<String, Object>> status() {
        return Result.success(lxSourceService.status());
    }

    @GetMapping("/scripts")
    @Operation(summary = "已加载的音源脚本及支持的音源")
    public Result<List<Map<String, Object>>> scripts() {
        return Result.success(lxSourceService.describe());
    }

    @PostMapping("/reload")
    @Operation(summary = "重新加载脚本（外部目录热更新）")
    public Result<String> reload() {
        lxSourceService.reloadScripts();
        return Result.success("ok");
    }

    @PostMapping("/import")
    @Operation(summary = "导入音源脚本（写入外部目录并热加载）")
    public Result<Map<String, Object>> importScript(@RequestBody ImportRequest request) {
        try {
            return Result.success(lxSourceService.importScript(request.filename(), request.code()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Result.error(e.getMessage());
        }
    }

    @PostMapping("/delete")
    @Operation(summary = "删除用户导入的音源脚本")
    public Result<String> deleteScript(@RequestBody DeleteRequest request) {
        try {
            lxSourceService.deleteScript(request.id());
            return Result.success("ok");
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * @param filename 脚本文件名（可以带 .js，后端会补全；同名脚本会被覆盖）
     * @param code     脚本源码
     */
    public record ImportRequest(String filename, String code) {
    }

    /**
     * @param id 脚本 id（文件名去掉 .js）
     */
    public record DeleteRequest(String id) {
    }
}
