package com.qymusic.lx;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 音源脚本注册表。
 *
 * <p>脚本来源有两处，后者覆盖前者：</p>
 * <ol>
 *   <li>jar 内置的 {@code classpath:lx/*.js}（随版本发布，开箱可用）；</li>
 *   <li>外部目录 {@code lx.scripts-dir}（默认 /opt/music-killer/lx），
 *       放进去同名文件即可热替换，不用重新发版。</li>
 * </ol>
 */
@Slf4j
@Component
public class LxScriptRegistry {

    private static final Pattern META = Pattern.compile("@(name|version|author)\\s+(.+)");

    /** 内置脚本顺序：优先级从高到低 */
    private static final List<String> DEFAULT_ORDER = List.of("stellarwave", "quandou");

    private final Map<String, LxScript> scripts = new LinkedHashMap<>();

    @Value("${lx.scripts-dir:}")
    private String externalDir;

    @Value("${lx.order:}")
    private String orderConfig;

    @Value("${lx.disabled:}")
    private String disabledConfig;

    @PostConstruct
    public synchronized void reload() {
        scripts.clear();

        // 1. jar 内置脚本
        try {
            for (Resource resource : new PathMatchingResourcePatternResolver().getResources("classpath*:lx/*.js")) {
                String filename = resource.getFilename();
                if (filename == null || filename.equals("prelude.js")) {
                    continue;
                }
                try (var in = resource.getInputStream()) {
                    String code = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    LxScript script = build(filename, code, "builtin");
                    scripts.put(script.id(), script);
                }
            }
        } catch (Exception e) {
            log.warn("读取内置音源脚本失败: {}", e.getMessage());
        }

        // 2. 外部目录（覆盖同名内置脚本，方便热换）
        if (externalDir != null && !externalDir.isBlank()) {
            File dir = new File(externalDir);
            File[] files = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".js"));
            if (files != null) {
                for (File file : files) {
                    if (!file.isFile() || file.getName().equalsIgnoreCase("prelude.js")) {
                        continue;
                    }
                    try {
                        String code = Files.readString(file.toPath(), StandardCharsets.UTF_8);
                        LxScript script = build(file.getName(), code, "external");
                        scripts.put(script.id(), script);
                        log.info("加载外部音源脚本 {} ({})", script.id(), script.name());
                    } catch (IOException e) {
                        log.warn("读取外部音源脚本 {} 失败: {}", file.getName(), e.getMessage());
                    }
                }
            }
        }

        log.info("音源脚本已加载 {} 个: {}", scripts.size(), scripts.keySet());
    }

    private LxScript build(String filename, String code, String origin) {
        String id = filename.replaceAll("(?i)\\.js$", "");
        String name = id;
        String version = "";
        String author = "";

        String[] lines = code.split("\\r?\\n", 40);
        for (String line : lines) {
            if (!line.contains("@")) {
                continue;
            }
            Matcher m = META.matcher(line);
            if (!m.find()) {
                continue;
            }
            String value = m.group(2).trim();
            switch (m.group(1)) {
                case "name" -> name = value;
                case "version" -> version = value;
                case "author" -> author = value;
                default -> {
                }
            }
        }
        return new LxScript(id, name, version, author, origin, code);
    }

    /**
     * 按配置顺序返回脚本（顺序即解析优先级）
     */
    public synchronized List<LxScript> ordered() {
        List<String> order = new ArrayList<>();
        if (orderConfig != null && !orderConfig.isBlank()) {
            for (String s : orderConfig.split(",")) {
                if (!s.isBlank()) {
                    order.add(s.trim());
                }
            }
        }
        if (order.isEmpty()) {
            order.addAll(DEFAULT_ORDER);
        }

        Set<String> disabled = new LinkedHashSet<>();
        if (disabledConfig != null && !disabledConfig.isBlank()) {
            for (String s : disabledConfig.split(",")) {
                if (!s.isBlank()) {
                    disabled.add(s.trim());
                }
            }
        }

        List<LxScript> result = new ArrayList<>();
        for (String id : order) {
            LxScript script = scripts.get(id);
            if (script != null && !disabled.contains(id)) {
                result.add(script);
            }
        }
        // 配置里没列到的脚本按 id 排在后面，保证新增脚本不用改配置也能跑
        scripts.values().stream()
                .filter(s -> !order.contains(s.id()))
                .filter(s -> !disabled.contains(s.id()))
                .sorted(Comparator.comparing(LxScript::id))
                .forEach(result::add);
        return result;
    }

    public synchronized LxScript get(String id) {
        return scripts.get(id);
    }

    public synchronized int size() {
        return scripts.size();
    }

    /**
     * 外部脚本目录（可通过 lx.scripts-dir 配置，默认 /opt/music-killer/lx）
     */
    public File externalDirectory() {
        if (externalDir == null || externalDir.isBlank()) {
            return null;
        }
        return new File(externalDir);
    }

    /**
     * 把一个脚本写入外部目录，文件名即脚本 id。
     *
     * <p>只允许普通字符作为 id，且写入前会校验目标文件的真实路径必须落在外部目录内，
     * 避免通过 {@code ../} 之类的文件名写到别处。</p>
     */
    public synchronized void saveExternal(String id, String code) throws IOException {
        File dir = externalDirectory();
        if (dir == null) {
            throw new IOException("服务端未配置外部脚本目录");
        }
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建脚本目录: " + dir.getAbsolutePath());
        }
        File target = new File(dir, id + ".js");
        String dirPath = dir.getCanonicalPath() + File.separator;
        if (!target.getCanonicalPath().startsWith(dirPath)) {
            throw new IOException("非法的脚本名: " + id);
        }
        Files.writeString(target.toPath(), code, StandardCharsets.UTF_8);
    }

    /**
     * 删除外部目录里的脚本。内置脚本没有对应文件，调用后什么都不会发生。
     */
    public synchronized void deleteExternal(String id) throws IOException {
        File dir = externalDirectory();
        if (dir == null) {
            return;
        }
        File target = new File(dir, id + ".js");
        String dirPath = dir.getCanonicalPath() + File.separator;
        if (!target.getCanonicalPath().startsWith(dirPath)) {
            throw new IOException("非法的脚本名: " + id);
        }
        Files.deleteIfExists(target.toPath());
    }
}
