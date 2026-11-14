package com.qymusic.lx;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 音源脚本调度：按优先级依次询问脚本，拿到第一个可用地址就返回。
 */
@Slf4j
@Service
public class LxSourceService {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, LxEngine> engines = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(16, r -> {
        Thread t = new Thread(r, "lx-resolve");
        t.setDaemon(true);
        return t;
    });

    @Value("${lx.enabled:true}")
    private boolean enabled;

    @Value("${lx.timeout-ms:8000}")
    private long timeoutMs;

    @Value("${lx.total-timeout-ms:15000}")
    private long totalTimeoutMs;

    @jakarta.annotation.Resource
    private LxScriptRegistry registry;

    private volatile List<LxEngine> ordered = List.of();

    @PostConstruct
    public void init() {
        if (!enabled) {
            log.info("音源脚本引擎已关闭（lx.enabled=false）");
            return;
        }
        rebuild();
        // 预热放到后台线程：首次播放不必等脚本编译
        Thread warmup = new Thread(this::warmup, "lx-warmup");
        warmup.setDaemon(true);
        warmup.start();
    }

    public synchronized void rebuild() {
        for (LxEngine engine : engines.values()) {
            engine.close();
        }
        engines.clear();
        List<LxEngine> list = new ArrayList<>();
        for (LxScript script : registry.ordered()) {
            LxEngine engine = new LxEngine(script, timeoutMs);
            engines.put(script.id(), engine);
            list.add(engine);
        }
        ordered = Collections.unmodifiableList(list);
        log.info("音源脚本引擎已就绪: {}", engines.keySet());
    }

    private void warmup() {
        long start = System.currentTimeMillis();
        for (LxEngine engine : ordered) {
            try {
                engine.capabilities();
            } catch (Exception e) {
                log.warn("音源脚本 {} 预热失败: {}", engine.id(), e.getMessage());
            }
        }
        log.info("音源脚本预热完成，耗时 {} ms", System.currentTimeMillis() - start);
    }

    /**
     * 依次尝试各脚本，返回第一个可解析且通过校验的播放地址。
     *
     * @param source          音源标识（kw / kg / tx / wy）
     * @param quality         音质（128k / 320k / flac）
     * @param musicInfoJson   歌曲信息
     * @param expectedSeconds 歌曲时长（秒），用于校验拿到的文件是不是同一首
     * @return 播放地址；全部失败时返回 null
     */
    public Result resolve(String source, String quality, String musicInfoJson, Integer expectedSeconds) {
        return resolve(source, quality, musicInfoJson, expectedSeconds, totalTimeoutMs);
    }

    /**
     * 同上，可指定本次解析的总预算（毫秒）。
     *
     * <p>音质降级时每一级都要单独限时，否则一级卡住就会吃掉整次播放的时间。</p>
     */
    public Result resolve(String source, String quality, String musicInfoJson,
                          Integer expectedSeconds, long budgetMs) {
        if (!enabled || ordered.isEmpty()) {
            return null;
        }
        List<LxEngine> candidates = new ArrayList<>();
        for (LxEngine engine : ordered) {
            if (engine.supports(source, quality)) {
                candidates.add(engine);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }

        // 多个脚本并行跑，谁先给出可用地址就用谁：
        // 串行会让「前几个脚本失败」的歌曲等到 10 秒以上，客户端容易超时。
        long budget = budgetMs > 0 ? Math.min(budgetMs, totalTimeoutMs) : totalTimeoutMs;
        long deadline = System.currentTimeMillis() + budget;
        ExecutorCompletionService<Result> completion = new ExecutorCompletionService<>(pool);
        Map<Future<Result>, LxEngine> futures = new LinkedHashMap<>();
        for (LxEngine engine : candidates) {
            Future<Result> future = completion.submit(() -> {
                try {
                    String url = engine.resolveUrl(source, quality, musicInfoJson, timeoutMs);
                    if (url == null || url.isBlank()) {
                        return null;
                    }
                    if (!LxUrlValidator.isPlayable(url, expectedSeconds)) {
                        log.info("音源脚本 {} 返回的地址不可用 id={}", engine.id(), readId(musicInfoJson));
                        return null;
                    }
                    return new Result(url, engine.id());
                } catch (Exception e) {
                    log.debug("音源脚本 {} 解析失败: {}", engine.id(), e.getMessage());
                    return null;
                }
            });
            futures.put(future, engine);
        }

        try {
            for (int i = 0; i < futures.size(); i++) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    break;
                }
                Future<Result> done = completion.poll(remaining, TimeUnit.MILLISECONDS);
                if (done == null) {
                    break;
                }
                Result result = done.get();
                if (result != null) {
                    log.info("音源脚本命中 id={} script={}", readId(musicInfoJson), result.scriptId());
                    return result;
                }
            }
        } catch (Exception e) {
            log.debug("音源脚本调度异常: {}", e.getMessage());
        } finally {
            // 没跑完的脚本直接取消，避免占着执行器
            futures.keySet().forEach(f -> {
                if (!f.isDone()) {
                    f.cancel(true);
                }
            });
        }
        log.info("音源脚本全部未命中 id={}", readId(musicInfoJson));
        return null;
    }

    private String readId(String musicInfoJson) {
        try {
            return mapper.readTree(musicInfoJson).path("rid").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    public List<Map<String, Object>> describe() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (LxEngine engine : ordered) {
            list.add(engine.describe());
        }
        return list;
    }

    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", enabled);
        status.put("scripts", ordered.size());
        status.put("timeoutMs", timeoutMs);
        status.put("totalTimeoutMs", totalTimeoutMs);
        return status;
    }

    /**
     * 重新扫描外部脚本目录并重建引擎（设置页「刷新音源」调用）。
     */
    public synchronized Map<String, Object> reloadScripts() {
        registry.reload();
        rebuild();
        return status();
    }

    // ------------------------------------------------------------------ 脚本导入 / 删除

    /** 单个脚本最大 1MB（脚本本身通常几十 KB） */
    private static final int MAX_SCRIPT_CHARS = 1_000_000;
    /** 脚本数量上限，避免外部目录被塞爆 */
    private static final int MAX_SCRIPTS = 50;
    /** 导入时的校验超时：GraalJS 冷启动较慢，留足时间 */
    private static final long PROBE_TIMEOUT_MS = 20_000L;

    /**
     * 导入（或覆盖）一个脚本。
     *
     * <p>流程：写入外部目录 → 重新扫描 → 在沙箱里真正跑一遍确认是有效的 lx v4 脚本 →
     * 重建引擎。校验失败会回滚文件，不影响正在使用的脚本。</p>
     */
    public synchronized Map<String, Object> importScript(String filename, String code) {
        if (!enabled) {
            throw new IllegalStateException("音源脚本引擎已关闭");
        }
        String id = normalizeId(filename);
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("脚本内容为空");
        }
        if (code.length() > MAX_SCRIPT_CHARS) {
            throw new IllegalArgumentException("脚本过大，最大支持 1MB");
        }
        if (engines.size() >= MAX_SCRIPTS && registry.get(id) == null) {
            throw new IllegalArgumentException("脚本数量已达上限（" + MAX_SCRIPTS + " 个），请先删除不用的脚本");
        }

        // 覆盖同名脚本时先备份，校验失败可以还原
        String backup = null;
        File dir = registry.externalDirectory();
        File existing = dir == null ? null : new File(dir, id + ".js");
        if (existing != null && existing.isFile()) {
            try {
                backup = Files.readString(existing.toPath(), StandardCharsets.UTF_8);
            } catch (IOException ignored) {
                // 备份失败不阻断导入
            }
        }

        try {
            registry.saveExternal(id, code);
        } catch (IOException e) {
            throw new IllegalStateException("写入脚本失败：" + e.getMessage());
        }
        registry.reload();

        LxScript saved = registry.get(id);
        if (saved == null) {
            throw new IllegalStateException("保存后未找到脚本 " + id);
        }

        LxEngine probe = new LxEngine(saved, PROBE_TIMEOUT_MS);
        try {
            Map<String, Set<String>> caps = probe.capabilities();
            if (caps.isEmpty()) {
                throw new IllegalStateException("脚本没有声明任何音源，不是有效的 lx v4 音源脚本");
            }
        } catch (Exception e) {
            probe.close();
            rollback(id, backup);
            throw new IllegalStateException("脚本校验失败：" + e.getMessage());
        }
        probe.close();

        rebuild();
        LxEngine engine = engines.get(id);
        Map<String, Object> info = engine == null ? new LinkedHashMap<>() : engine.describe();
        log.info("导入音源脚本成功 id={} name={} version={}", id, saved.name(), saved.version());
        return info;
    }

    /**
     * 删除用户导入的脚本。内置脚本不能删除；删除同名覆盖脚本后会恢复成内置版本。
     */
    public synchronized Map<String, Object> deleteScript(String id) {
        String scriptId = id == null ? "" : id.trim();
        LxScript script = registry.get(scriptId);
        if (script == null) {
            throw new IllegalArgumentException("脚本不存在：" + scriptId);
        }
        if (!script.external()) {
            throw new IllegalArgumentException("内置脚本不能删除");
        }
        try {
            registry.deleteExternal(scriptId);
        } catch (IOException e) {
            throw new IllegalStateException("删除脚本失败：" + e.getMessage());
        }
        registry.reload();
        rebuild();
        log.info("已删除音源脚本 id={}", scriptId);
        return status();
    }

    private void rollback(String id, String backup) {
        try {
            if (backup == null) {
                registry.deleteExternal(id);
            } else {
                registry.saveExternal(id, backup);
            }
        } catch (IOException e) {
            log.warn("回滚脚本 {} 失败: {}", id, e.getMessage());
        }
        registry.reload();
        rebuild();
    }

    private static String normalizeId(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("缺少脚本文件名");
        }
        String name = filename.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.replaceAll("(?i)\\.js$", "");
        // 去掉可能在 Windows 上非法的字符与控制字符，同时阻止 .. 之类的路径片段
        name = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            throw new IllegalArgumentException("非法的脚本文件名");
        }
        if (name.length() > 64) {
            name = name.substring(0, 64);
        }
        if (name.equalsIgnoreCase("prelude")) {
            throw new IllegalArgumentException("prelude.js 是运行时保留名，请换一个文件名");
        }
        return name;
    }

    public boolean isEnabled() {
        return enabled;
    }

    @PreDestroy
    public void destroy() {
        pool.shutdownNow();
        for (LxEngine engine : engines.values()) {
            engine.close();
        }
    }

    public record Result(String url, String scriptId) {
    }
}
