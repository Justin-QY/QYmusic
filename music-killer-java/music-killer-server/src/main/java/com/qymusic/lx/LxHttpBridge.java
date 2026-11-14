package com.qymusic.lx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * 音源脚本的 HTTP 出口。
 *
 * <p>脚本本身运行在无 IO 权限的沙箱里，所有网络请求都必须走这里，因此这里承担安全边界：</p>
 * <ul>
 *   <li>只允许 http/https；</li>
 *   <li>拒绝解析到内网 / 回环 / 链路本地地址的域名（防 SSRF 打到数据库、Redis、内网服务）；</li>
 *   <li>限制响应体积与超时，重定向逐跳校验。</li>
 * </ul>
 */
@Slf4j
public final class LxHttpBridge {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_BODY = 4 * 1024 * 1024;
    private static final int MAX_REDIRECT = 4;
    private static final int DEFAULT_TIMEOUT_MS = 12_000;

    private LxHttpBridge() {
    }

    /**
     * @param payloadJson {url, method, headers, body, form, formData, timeout, binary}
     * @return {statusCode, statusMessage, headers, body, bytes?, error?}
     */
    public static String request(String payloadJson) {
        ObjectNode result = MAPPER.createObjectNode();
        try {
            JsonNode payload = MAPPER.readTree(payloadJson);
            String url = payload.path("url").asText("");
            if (url.isBlank()) {
                return error(result, "缺少 url");
            }

            String method = payload.path("method").asText("get").toUpperCase();
            Map<String, String> headers = readHeaders(payload.path("headers"));
            // 主动要求明文，避免脚本拿到 gzip 二进制后解析失败
            headers.remove("Accept-Encoding");
            headers.remove("accept-encoding");

            byte[] body = buildBody(payload);
            if (body != null && !headers.containsKey("Content-Type") && !headers.containsKey("content-type")) {
                headers.put("Content-Type", payload.hasNonNull("form") || payload.hasNonNull("formData")
                        ? "application/x-www-form-urlencoded"
                        : "application/json");
            }

            int timeout = payload.path("timeout").asInt(0);
            if (timeout <= 0 || timeout > 20_000) {
                timeout = DEFAULT_TIMEOUT_MS;
            }

            Response response = execute(url, method, headers, body, timeout, 0);
            result.put("statusCode", response.status());
            result.put("statusMessage", response.message());
            ObjectNode headerNode = result.putObject("headers");
            response.headers().forEach(headerNode::put);

            if (payload.path("binary").asBoolean(false)) {
                result.put("body", new String(response.body(), StandardCharsets.ISO_8859_1));
                var bytes = result.putArray("bytes");
                for (byte b : response.body()) {
                    bytes.add(b & 0xFF);
                }
            } else {
                result.put("body", new String(response.body(), response.charset()));
            }
            return MAPPER.writeValueAsString(result);
        } catch (Exception e) {
            return error(result, e.getMessage());
        }
    }

    private static String error(ObjectNode node, String message) {
        node.put("error", message == null ? "请求失败" : message);
        try {
            return MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            return "{\"error\":\"请求失败\"}";
        }
    }

    private record Response(int status, String message, Map<String, String> headers, byte[] body, Charset charset) {
    }

    private static Response execute(String url, String method, Map<String, String> headers, byte[] body,
                                    int timeout, int depth) throws IOException {
        if (depth > MAX_REDIRECT) {
            throw new IOException("重定向次数过多");
        }
        URL target = new URL(url);
        String protocol = target.getProtocol();
        if (!"http".equalsIgnoreCase(protocol) && !"https".equalsIgnoreCase(protocol)) {
            throw new IOException("不支持的协议: " + protocol);
        }
        assertPublicHost(target.getHost());

        HttpURLConnection conn = (HttpURLConnection) target.openConnection();
        conn.setConnectTimeout(Math.min(timeout, 8_000));
        conn.setReadTimeout(timeout);
        conn.setInstanceFollowRedirects(false);
        conn.setRequestMethod(method);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
        headers.forEach(conn::setRequestProperty);

        if (body != null && !"GET".equals(method) && !"HEAD".equals(method)) {
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(body.length);
            try (var out = conn.getOutputStream()) {
                out.write(body);
            }
        }

        int status = conn.getResponseCode();
        if (status >= 300 && status < 400) {
            String location = conn.getHeaderField("Location");
            conn.disconnect();
            if (location == null || location.isBlank()) {
                throw new IOException("重定向缺少 Location");
            }
            String next = new URL(target, location).toString();
            return execute(next, "GET".equals(method) ? "GET" : method, headers, body, timeout, depth + 1);
        }

        Map<String, String> responseHeaders = new LinkedHashMap<>();
        for (Map.Entry<String, java.util.List<String>> e : conn.getHeaderFields().entrySet()) {
            if (e.getKey() != null && e.getValue() != null && !e.getValue().isEmpty()) {
                responseHeaders.put(e.getKey(), e.getValue().get(0));
            }
        }

        Charset charset = detectCharset(conn.getContentType());
        String responseMessage = conn.getResponseMessage();
        InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        byte[] data = new byte[0];
        if (in != null) {
            String encoding = conn.getContentEncoding();
            if (encoding != null && encoding.toLowerCase().contains("gzip")) {
                in = new GZIPInputStream(in);
            } else if (encoding != null && encoding.toLowerCase().contains("deflate")) {
                in = new InflaterInputStream(in);
            }
            data = readLimited(in);
            in.close();
        }
        conn.disconnect();
        return new Response(status, responseMessage, responseHeaders, data, charset);
    }

    private static byte[] readLimited(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        byte[] buffer = new byte[8192];
        int read;
        long total = 0;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
            total += read;
            if (total >= MAX_BODY) {
                break;
            }
        }
        return out.toByteArray();
    }

    private static Charset detectCharset(String contentType) {
        if (contentType != null) {
            for (String part : contentType.split(";")) {
                String p = part.trim();
                if (p.toLowerCase().startsWith("charset=")) {
                    try {
                        return Charset.forName(p.substring(8).trim().replace("\"", ""));
                    } catch (Exception ignored) {
                        return StandardCharsets.UTF_8;
                    }
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static Map<String, String> readHeaders(JsonNode node) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (node != null && node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                headers.put(e.getKey(), e.getValue().asText(""));
            }
        }
        return headers;
    }

    private static byte[] buildBody(JsonNode payload) throws IOException {
        if (payload.hasNonNull("body")) {
            JsonNode body = payload.get("body");
            if (body.isTextual()) {
                return body.asText().getBytes(StandardCharsets.UTF_8);
            }
            return MAPPER.writeValueAsBytes(body);
        }
        if (payload.hasNonNull("form") && payload.get("form").isObject()) {
            StringBuilder sb = new StringBuilder();
            Iterator<Map.Entry<String, JsonNode>> it = payload.get("form").fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if (sb.length() > 0) {
                    sb.append('&');
                }
                sb.append(java.net.URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                        .append('=')
                        .append(java.net.URLEncoder.encode(e.getValue().asText(""), StandardCharsets.UTF_8));
            }
            return sb.toString().getBytes(StandardCharsets.UTF_8);
        }
        if (payload.hasNonNull("formData") && payload.get("formData").isObject()) {
            StringBuilder sb = new StringBuilder();
            Iterator<Map.Entry<String, JsonNode>> it = payload.get("formData").fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if (sb.length() > 0) {
                    sb.append('&');
                }
                sb.append(java.net.URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                        .append('=')
                        .append(java.net.URLEncoder.encode(e.getValue().asText(""), StandardCharsets.UTF_8));
            }
            return sb.toString().getBytes(StandardCharsets.UTF_8);
        }
        return null;
    }

    /**
     * 阻断内网地址，避免脚本被投毒后用来探测 / 攻击服务器内网。
     */
    private static void assertPublicHost(String host) throws IOException {
        InetAddress[] addresses = InetAddress.getAllByName(host);
        for (InetAddress address : addresses) {
            if (address.isAnyLocalAddress()
                    || address.isLoopbackAddress()
                    || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress()
                    || address.isMulticastAddress()) {
                throw new IOException("禁止访问内网地址: " + host);
            }
            if (address instanceof Inet6Address) {
                byte[] raw = address.getAddress();
                if ((raw[0] & 0xFE) == 0xFC) {
                    throw new IOException("禁止访问内网地址: " + host);
                }
            }
        }
    }
}
