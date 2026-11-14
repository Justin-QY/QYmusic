package com.qymusic.lx;

import lombok.extern.slf4j.Slf4j;

import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 播放地址校验。
 *
 * <p>第三方音源脚本有时会返回 410（链接过期）、404 或一段 HTML 错误页，
 * 直接交给播放器会表现成「点了没反应」或「放了一半断掉」。
 * 这里在返回给客户端之前先做一次极轻量的探测：</p>
 * <ul>
 *   <li>HTTP 状态必须是 200 / 206；</li>
 *   <li>如果是文本类型（HTML/JSON）直接判为无效；</li>
 *   <li>有歌曲时长时，用文件大小反推时长做交叉校验，避免拿到片段或别的歌。</li>
 * </ul>
 */
@Slf4j
public final class LxUrlValidator {

    private static final long MIN_BYTES = 400 * 1024L;
    /** 允许的“反推码率”区间：低于这个范围基本是片段/提示音，高于则是异常文件 */
    private static final double MIN_KBPS = 80;
    private static final double MAX_KBPS = 1500;

    private LxUrlValidator() {
    }

    public static boolean isPlayable(String url, Integer expectedSeconds) {
        if (url == null || url.isBlank()) {
            return false;
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(6_000);
            conn.setReadTimeout(6_000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Range", "bytes=0-1");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13)");
            int status = conn.getResponseCode();
            if (status != 200 && status != 206) {
                log.info("音源校验失败 status={} url={}", status, shortUrl(url));
                return false;
            }
            String contentType = conn.getContentType();
            if (contentType != null) {
                String lower = contentType.toLowerCase();
                if (lower.contains("text/html") || lower.contains("application/json") || lower.contains("text/plain")) {
                    log.info("音源校验失败 返回了非音频内容 contentType={} url={}", contentType, shortUrl(url));
                    return false;
                }
            }
            long total = parseTotal(conn);
            if (total > 0 && total < MIN_BYTES) {
                log.info("音源校验失败 文件过小 {}B url={}", total, shortUrl(url));
                return false;
            }
            if (total > 0 && expectedSeconds != null && expectedSeconds > 0) {
                // 用「文件大小 / 时长」反推码率，比假设固定码率更稳：
                // 片段、提示音或别的歌都会得到明显偏离的码率。
                double kbps = total * 8.0 / 1000.0 / expectedSeconds;
                if (kbps < MIN_KBPS || kbps > MAX_KBPS) {
                    log.info("音源校验失败 码率异常 {}kbps 期望时长={}s 文件大小={}B url={}",
                            String.format("%.0f", kbps), expectedSeconds, total, shortUrl(url));
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            log.info("音源校验异常 {} url={}", e.getMessage(), shortUrl(url));
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static long parseTotal(HttpURLConnection conn) {
        String range = conn.getHeaderField("Content-Range");
        if (range != null) {
            int slash = range.lastIndexOf('/');
            if (slash >= 0) {
                try {
                    return Long.parseLong(range.substring(slash + 1).trim());
                } catch (NumberFormatException ignored) {
                    // ignore
                }
            }
        }
        long length = conn.getContentLengthLong();
        return Math.max(length, 0);
    }

    private static String shortUrl(String url) {
        return url.length() > 120 ? url.substring(0, 120) + "..." : url;
    }
}
