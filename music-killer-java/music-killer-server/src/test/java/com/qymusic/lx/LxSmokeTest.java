package com.qymusic.lx;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 本地冒烟测试：直接跑脚本引擎，验证沙箱桥接与真实解析是否可用。
 * 需要联网，用 -Dtest=LxSmokeTest 单独执行。
 */
public class LxSmokeTest {

    private String load(String scriptId) throws Exception {
        try (InputStream in = LxSmokeTest.class.getResourceAsStream("/lx/" + scriptId + ".js")) {
            assertNotNull(in, "缺少脚本 " + scriptId);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    public void resolveJayChou() throws Exception {
        String scriptId = System.getProperty("lx.script", "hyw");
        String rid = System.getProperty("lx.rid", "324949");
        String name = System.getProperty("lx.name", "轨迹");
        String singer = System.getProperty("lx.singer", "周杰伦");

        LxScript script = new LxScript(scriptId, scriptId, "", "", "builtin", load(scriptId));
        LxEngine engine = new LxEngine(script, 12000L);
        System.out.println("capabilities = " + engine.capabilities());

        String musicInfo = "{\"rid\":\"" + rid + "\",\"id\":\"" + rid + "\",\"songmid\":\"" + rid
                + "\",\"hash\":\"\",\"name\":\"" + name + "\",\"singer\":\"" + singer
                + "\",\"albumName\":\"\",\"interval\":300}";

        long start = System.currentTimeMillis();
        String url = engine.resolveUrl("kw", "320k", musicInfo, 15000L);
        System.out.println("url = " + url);
        System.out.println("cost = " + (System.currentTimeMillis() - start) + "ms");
        System.out.println("playable = " + LxUrlValidator.isPlayable(url, 300));
        engine.close();

        assertNotNull(url);
    }
}
