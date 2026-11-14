package com.qymusic.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * App 在线更新。
 *
 * <p>更新目录由 {@code app.update.dir} 指定（默认 /opt/music-killer/update），
 * 里面放两样东西：</p>
 * <ul>
 *   <li>{@code update.json}：版本号、安装包地址、大小、MD5、更新说明；</li>
 *   <li>{@code qy-music-x.y.apk}：安装包本体，由静态资源映射对外提供。</li>
 * </ul>
 *
 * <p>这个接口不需要签名，方便浏览器直接下载，也避免更新逻辑被签名时效拖累。</p>
 */
@RestController
@RequestMapping("/app")
@Tag(name = "应用更新")
public class AppUpdateController {

    @Value("${app.update.dir:/opt/music-killer/update}")
    private String updateDir;

    @GetMapping("/update.json")
    @Operation(summary = "获取最新版本信息")
    public ResponseEntity<byte[]> updateInfo() throws IOException {
        File file = new File(updateDir, "update.json");
        if (!file.isFile()) {
            return ResponseEntity.notFound().build();
        }
        // 直接返回字节数组：项目里 Jackson 转换器注册在最前面，返回 Resource 会被它误接管
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_JSON)
                .contentLength(file.length())
                .body(Files.readAllBytes(file.toPath()));
    }
}
