package com.qymusic.lx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 单个 lx 音源脚本的运行时。
 *
 * <p>GraalJS 上下文是单线程的，这里给每个脚本配一个单线程执行器：
 * 上下文只创建一次并复用（创建成本约几百毫秒，ARM 上更慢），
 * 每次解析结束后脚本的内部状态可能残留，但音源脚本都是无状态的纯函数式实现，
 * 复用比反复创建更划算。脚本出错或超时就直接销毁上下文，下次重建。</p>
 */
@Slf4j
public class LxEngine implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PRELUDE = loadPrelude();

    private final LxScript script;
    private final long timeoutMs;
    private final ExecutorService executor;

    private volatile Context context;
    private volatile boolean broken;
    private volatile Map<String, Set<String>> capabilities;
    private volatile boolean initializing;

    public LxEngine(LxScript script, long timeoutMs) {
        this.script = script;
        this.timeoutMs = timeoutMs;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "lx-" + script.id());
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY + 1);
            return t;
        });
    }

    public String id() {
        return script.id();
    }

    public String name() {
        return script.name();
    }

    /**
     * 脚本是否声明支持某个音源与音质。
     *
     * <p>只读缓存，不阻塞：脚本还没预热好就先跳过（并在后台开始初始化），
     * 否则首个请求会被 GraalJS 的冷启动拖到十几秒。</p>
     */
    public boolean supports(String source, String quality) {
        Map<String, Set<String>> caps = capabilities;
        if (caps == null) {
            ensureCapabilitiesAsync();
            return false;
        }
        Set<String> qualities = caps.get(source);
        return qualities != null && (qualities.isEmpty() || qualities.contains(quality));
    }

    private void ensureCapabilitiesAsync() {
        if (capabilities != null || initializing) {
            return;
        }
        initializing = true;
        executor.submit(() -> {
            try {
                capabilities = readCapabilities();
            } catch (Exception e) {
                log.warn("音源脚本 {} 初始化失败: {}", script.id(), e.getMessage());
                invalidate();
            } finally {
                initializing = false;
            }
        });
    }

    public Map<String, Set<String>> capabilities() throws Exception {
        Map<String, Set<String>> cached = capabilities;
        if (cached != null) {
            return cached;
        }
        Future<Map<String, Set<String>>> future = executor.submit(this::readCapabilities);
        try {
            Map<String, Set<String>> caps = future.get(60_000, TimeUnit.MILLISECONDS);
            capabilities = caps;
            return caps;
        } catch (TimeoutException e) {
            future.cancel(true);
            invalidate();
            throw new IllegalStateException("脚本初始化超时");
        } catch (ExecutionException e) {
            invalidate();
            throw new IllegalStateException("脚本初始化失败: " + e.getCause());
        }
    }

    /**
     * 解析播放地址
     *
     * @param source        音源标识，如 kw / kg / tx / wy
     * @param quality       音质，如 128k / 320k / flac
     * @param musicInfoJson 歌曲信息 {rid, name, singer, ...}
     * @return 播放地址
     */
    public String resolveUrl(String source, String quality, String musicInfoJson, long timeout) throws Exception {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("type", quality);
        info.put("musicInfo", MAPPER.readTree(musicInfoJson));
        String infoJson = MAPPER.writeValueAsString(info);

        Future<String> future = executor.submit(() -> doResolve(source, infoJson, timeout));
        try {
            return future.get(timeout, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            invalidate();
            throw new IllegalStateException("脚本解析超时");
        } catch (ExecutionException e) {
            invalidate();
            throw new IllegalStateException(String.valueOf(e.getCause() == null ? "脚本执行异常" : e.getCause().getMessage()));
        }
    }

    private Map<String, Set<String>> readCapabilities() throws Exception {
        Context ctx = context();
        ctx.eval("js", "0");
        Value sourcesJson = ctx.eval("js", "__lx_sources()");
        Map<String, Set<String>> caps = new LinkedHashMap<>();
        if (sourcesJson != null && !sourcesJson.isNull()) {
            JsonNode node = MAPPER.readTree(sourcesJson.asString());
            if (node != null && node.isObject()) {
                node.fields().forEachRemaining(entry -> {
                    JsonNode qualities = entry.getValue().path("qualitys");
                    Set<String> set = new LinkedHashSet<>();
                    if (qualities.isArray()) {
                        qualities.forEach(q -> set.add(q.asText()));
                    }
                    caps.put(entry.getKey(), set);
                });
            }
        }
        return caps;
    }

    private String doResolve(String source, String infoJson, long timeout) throws Exception {
        Context ctx = context();
        Value bindings = ctx.getBindings("js");
        bindings.putMember("__arg_source", source);
        bindings.putMember("__arg_info", infoJson);

        ctx.eval("js", "__lx_done = null");
        ctx.eval("js", "__lx_invoke(__arg_source, 'musicUrl', __arg_info)");

        long deadline = System.currentTimeMillis() + timeout;
        while (System.currentTimeMillis() < deadline) {
            // 让 Promise 微任务与脚本里的定时器有机会执行
            ctx.eval("js", "__lx_pump()");
            Value done = bindings.getMember("__lx_done");
            if (done != null && !done.isNull()) {
                JsonNode node = MAPPER.readTree(done.asString());
                if (node.hasNonNull("url")) {
                    return node.get("url").asText();
                }
                throw new IllegalStateException(node.path("error").asText("脚本未返回地址"));
            }
            Thread.sleep(15);
        }
        throw new IllegalStateException("脚本解析超时");
    }

    private Context context() throws Exception {
        Context ctx = context;
        if (ctx != null && !broken) {
            return ctx;
        }
        synchronized (this) {
            if (context != null && !broken) {
                return context;
            }
            Context built = Context.newBuilder("js")
                    // 只暴露下面这几个桥接函数：不开放 IO、线程、子进程，也不允许 Java.type 查类
                    .allowHostAccess(HostAccess.NONE)
                    .allowHostClassLookup(name -> false)
                    .allowIO(org.graalvm.polyglot.io.IOAccess.NONE)
                    .allowCreateThread(false)
                    .allowCreateProcess(false)
                    .allowNativeAccess(false)
                    .option("engine.WarnInterpreterOnly", "false")
                    .option("js.ecmascript-version", "2022")
                    .build();

            Value bindings = built.getBindings("js");
            bindings.putMember("__host_http", (ProxyExecutable) args ->
                    LxHttpBridge.request(args.length > 0 ? args[0].asString() : "{}"));
            bindings.putMember("__host_md5", (ProxyExecutable) args ->
                    LxCrypto.md5Hex(args[0].asString()));
            bindings.putMember("__host_b64_str", (ProxyExecutable) args ->
                    LxCrypto.base64(args[0].asString()));
            bindings.putMember("__host_b64_buf", (ProxyExecutable) args ->
                    LxCrypto.base64(toBytes(args[0])));
            bindings.putMember("__host_unb64", (ProxyExecutable) args ->
                    LxCrypto.base64ToJsonArray(args[0].asString()));
            bindings.putMember("__host_buf_str", (ProxyExecutable) args ->
                    LxCrypto.bufferToString(parseBytes(args[0].asString())));
            bindings.putMember("__host_aes", (ProxyExecutable) args ->
                    LxCrypto.aesEncrypt(args[0].asString(), args[1].asString(), args[2].asString(), args[3].asString()));
            bindings.putMember("__host_rsa", (ProxyExecutable) args ->
                    LxCrypto.rsaEncrypt(args[0].asString(), args[1].asString()));
            bindings.putMember("__host_script_name", (ProxyExecutable) args -> script.name());

            built.eval("js", PRELUDE);
            built.eval("js", script.code());

            context = built;
            broken = false;
            return built;
        }
    }

    private static byte[] toBytes(Value value) {
        if (value == null || value.isNull()) {
            return new byte[0];
        }
        if (value.hasArrayElements()) {
            int size = (int) value.getArraySize();
            byte[] bytes = new byte[size];
            for (int i = 0; i < size; i++) {
                bytes[i] = (byte) value.getArrayElement(i).asInt();
            }
            return bytes;
        }
        String text = value.asString();
        try {
            JsonNode node = MAPPER.readTree(text);
            if (node.isArray()) {
                byte[] bytes = new byte[node.size()];
                for (int i = 0; i < node.size(); i++) {
                    bytes[i] = (byte) node.get(i).asInt();
                }
                return bytes;
            }
        } catch (Exception ignored) {
            // 不是 JSON 数组就当普通字符串
        }
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] parseBytes(String json) {
        try {
            return toBytes(MAPPER.readTree(json));
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private static byte[] toBytes(JsonNode node) {
        if (node == null || !node.isArray()) {
            return new byte[0];
        }
        byte[] bytes = new byte[node.size()];
        for (int i = 0; i < node.size(); i++) {
            bytes[i] = (byte) node.get(i).asInt();
        }
        return bytes;
    }

    private void invalidate() {
        broken = true;
        Context ctx = context;
        context = null;
        if (ctx != null) {
            try {
                ctx.close(true);
            } catch (Exception ignored) {
                // ignore
            }
        }
        capabilities = null;
    }

    @Override
    public void close() {
        invalidate();
        executor.shutdownNow();
    }

    private static String loadPrelude() {
        try (InputStream in = LxEngine.class.getResourceAsStream("/lx/prelude.js")) {
            if (in == null) {
                throw new IllegalStateException("缺少 /lx/prelude.js");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("读取 lx/prelude.js 失败", e);
        }
    }

    /**
     * 供管理接口展示脚本能力
     */
    public Map<String, Object> describe() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("id", script.id());
        info.put("name", script.name());
        info.put("version", script.version());
        info.put("author", script.author());
        info.put("origin", script.origin());
        try {
            Map<String, Set<String>> caps = capabilities();
            List<Map<String, Object>> list = new ArrayList<>();
            caps.forEach((source, qualities) -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("source", source);
                item.put("qualitys", new ArrayList<>(qualities));
                list.add(item);
            });
            info.put("sources", list);
        } catch (Exception e) {
            info.put("error", e.getMessage());
        }
        return info;
    }
}
