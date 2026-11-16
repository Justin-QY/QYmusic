package com.qymusic.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import lombok.extern.slf4j.Slf4j;
import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 扫码登录：网易云 / QQ 音乐。
 *
 * <p>协议参考 QPlayer 的 provider 插件：
 * 网易云 {@code /api/login/qrcode/unikey} + {@code client/login}；
 * QQ {@code ptqrshow → ptqrlogin → check_sig → oauth2/authorize → QQLogin}。
 * 酷我官方插件只有 Cookie 登录，没有扫码。</p>
 */
@Slf4j
@Service
public class QrLoginService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final MediaType FORM = MediaType.parse("application/x-www-form-urlencoded; charset=utf-8");

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .build();

    private static final OkHttpClient NO_REDIRECT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .followRedirects(false)
            .build();

    // QQ 网页登录固定参数（与 QPlayer 插件一致）
    private static final String QQ_APPID = "716027609";
    private static final String QQ_DAID = "383";
    private static final String QQ_PT_3RD_AID = "100497308";
    private static final String QQ_REDIRECT_URI =
            "https://y.qq.com/portal/wx_redirect.html?login_type=1&surl=https://y.qq.com/";
    private static final String CHECK_SIG = "https://ssl.ptlogin2.graph.qq.com/check_sig";
    private static final String OAUTH_AUTHORIZE = "https://graph.qq.com/oauth2.0/authorize";
    private static final String MUSICU = "https://u.y.qq.com/cgi-bin/musicu.fcg";
    private static final String REF_XUI = "https://xui.ptlogin2.qq.com/";

    // ------------------------------------------------------------------ 网易云

    public Map<String, Object> neteaseCreate() throws Exception {
        String url = "https://music.163.com/api/login/qrcode/unikey?type=1";
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Referer", "https://music.163.com/")
                .build();
        try (Response response = CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IllegalStateException("获取网易云二维码失败");
            }
            JsonNode root = MAPPER.readTree(response.body().string());
            String key = root.path("unikey").asText("");
            if (key.isBlank()) {
                throw new IllegalStateException("网易云未返回登录 key");
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("key", key);
            data.put("image", qrImage("https://music.163.com/login?codekey=" + key));
            return data;
        }
    }

    public Map<String, Object> neteasePoll(String key) throws Exception {
        String url = "https://music.163.com/api/login/qrcode/client/login?key="
                + java.net.URLEncoder.encode(key, StandardCharsets.UTF_8) + "&type=1";
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Referer", "https://music.163.com/")
                .build();
        Map<String, Object> data = new LinkedHashMap<>();
        try (Response response = CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                data.put("status", "waiting");
                return data;
            }
            JsonNode root = MAPPER.readTree(response.body().string());
            int code = root.path("code").asInt(801);
            if (code == 803) {
                String cookie = cookieHeader(response.headers("Set-Cookie"));
                data.put("status", "success");
                data.put("cookie", cookie);
            } else if (code == 802) {
                data.put("status", "scanned");
            } else if (code == 800) {
                data.put("status", "expired");
            } else {
                data.put("status", "waiting");
            }
            return data;
        }
    }

    // ------------------------------------------------------------------ QQ

    public Map<String, Object> qqCreate() throws Exception {
        String query = "appid=" + QQ_APPID + "&e=2&l=M&s=3&d=72&v=4&t=" + Math.random()
                + "&daid=" + QQ_DAID + "&pt_3rd_aid=" + QQ_PT_3RD_AID;
        Request request = new Request.Builder()
                .url("https://ssl.ptlogin2.qq.com/ptqrshow?" + query)
                .header("User-Agent", UA)
                .header("Referer", REF_XUI)
                .build();
        try (Response response = CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IllegalStateException("获取 QQ 二维码失败");
            }
            String qrsig = cookieValue(response.headers("Set-Cookie"), "qrsig");
            if (qrsig.isBlank()) {
                throw new IllegalStateException("QQ 未返回 qrsig");
            }
            byte[] png = response.body().bytes();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("qrsig", qrsig);
            data.put("image", Base64.getEncoder().encodeToString(png));
            return data;
        }
    }

    public Map<String, Object> qqPoll(String qrsig) throws Exception {
        String params = "u1=https%3A%2F%2Fgraph.qq.com%2Foauth2.0%2Flogin_jump"
                + "&ptqrtoken=" + hash33(qrsig)
                + "&ptredirect=0&h=1&t=1&g=1&from_ui=1&ptlang=2052"
                + "&action=0-0-" + System.currentTimeMillis()
                + "&js_ver=20102616&js_type=1&pt_uistyle=40"
                + "&aid=" + QQ_APPID + "&daid=" + QQ_DAID + "&pt_3rd_aid=" + QQ_PT_3RD_AID
                + "&has_onekey=1";
        Request request = new Request.Builder()
                .url("https://ssl.ptlogin2.qq.com/ptqrlogin?" + params)
                .header("User-Agent", UA)
                .header("Referer", REF_XUI)
                .header("Cookie", "qrsig=" + qrsig)
                .build();
        Map<String, Object> data = new LinkedHashMap<>();
        try (Response response = CLIENT.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            String[] args = jsonpArgs(body);
            if (args.length < 3) {
                data.put("status", "waiting");
                return data;
            }
            switch (args[0]) {
                case "0" -> {
                    String url = args[2];
                    String cookie = qqAuthorize(url);
                    if (cookie == null || cookie.isBlank()) {
                        data.put("status", "failed");
                    } else {
                        data.put("status", "success");
                        data.put("cookie", cookie);
                    }
                }
                case "67" -> data.put("status", "scanned");
                case "65" -> data.put("status", "expired");
                case "68" -> data.put("status", "failed");
                default -> data.put("status", "waiting");
            }
            return data;
        }
    }

    /**
     * 扫码确认后：check_sig 建立 graph 会话 → oauth 拿 code → QQLogin 换 musickey。
     */
    private String qqAuthorize(String successUrl) {
        try {
            String uin = queryParam(successUrl, "uin");
            String ptsigx = queryParam(successUrl, "ptsigx");
            if (uin.isBlank() || ptsigx.isBlank()) {
                return "";
            }
            String checkSigParams = "uin=" + uin + "&pttype=1&service=ptqrlogin&nodirect=0&ptsigx=" + ptsigx
                    + "&s_url=https%3A%2F%2Fgraph.qq.com%2Foauth2.0%2Flogin_jump"
                    + "&ptlang=2052&ptredirect=100&aid=" + QQ_APPID + "&daid=" + QQ_DAID
                    + "&j_later=0&low_login_hour=0&regmaster=0&pt_login_type=3&pt_aid=0"
                    + "&pt_aaid=16&pt_light=0&pt_3rd_aid=" + QQ_PT_3RD_AID;
            Request checkSig = new Request.Builder()
                    .url(CHECK_SIG + "?" + checkSigParams)
                    .header("User-Agent", UA)
                    .header("Referer", REF_XUI)
                    .build();
            String pSkey;
            String checkCookies;
            try (Response response = NO_REDIRECT.newCall(checkSig).execute()) {
                checkCookies = cookieHeader(response.headers("Set-Cookie"));
                pSkey = cookieValue(response.headers("Set-Cookie"), "p_skey");
            }
            if (pSkey.isBlank()) {
                return "";
            }
            uin = uin.replaceFirst("^[oO]0*", "");
            String authorizeBody = "response_type=code"
                    + "&client_id=" + QQ_PT_3RD_AID
                    + "&redirect_uri=" + java.net.URLEncoder.encode(QQ_REDIRECT_URI, StandardCharsets.UTF_8)
                    + "&scope=get_user_info%2Cget_app_friends&state=state&switch=&from_ptlogin=1"
                    + "&src=1&update_auth=1&openapi=1010_1030&g_tk=" + gtk(pSkey)
                    + "&auth_time=" + System.currentTimeMillis()
                    + "&ui=" + java.util.UUID.randomUUID();
            Request authorize = new Request.Builder()
                    .url(OAUTH_AUTHORIZE)
                    .header("User-Agent", UA)
                    .header("Referer", REF_XUI)
                    .header("Cookie", checkCookies)
                    .post(RequestBody.create(authorizeBody, FORM))
                    .build();
            String location;
            try (Response response = NO_REDIRECT.newCall(authorize).execute()) {
                location = response.header("Location", "");
            }
            String code = queryParam(location, "code");
            if (code.isBlank()) {
                return "";
            }
            ObjectNode payload = MAPPER.createObjectNode();
            ObjectNode comm = payload.putObject("comm");
            comm.put("tmeLoginType", 2);
            comm.put("format", "json");
            comm.put("inCharset", "utf-8");
            comm.put("outCharset", "utf-8");
            ObjectNode req = payload.putObject("req_1");
            req.put("module", "QQConnectLogin.LoginServer");
            req.put("method", "QQLogin");
            req.putObject("param").put("code", code);
            Request login = new Request.Builder()
                    .url(MUSICU)
                    .header("User-Agent", UA)
                    .header("Referer", "https://y.qq.com/")
                    .post(RequestBody.create(payload.toString(), JSON))
                    .build();
            try (Response response = CLIENT.newCall(login).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    return "";
                }
                JsonNode root = MAPPER.readTree(response.body().string());
                JsonNode result = root.path("req_1").path("data");
                String musicKey = result.path("musickey").asText("");
                String musicId = result.path("musicid").asText(result.path("str_musicid").asText(""));
                if (musicKey.isBlank() || musicId.isBlank()) {
                    return "";
                }
                String id = musicId.replaceFirst("^[oO]0*", "");
                return "uin=" + id + "; musicid=" + id + "; musickey=" + musicKey
                        + "; qqmusic_key=" + musicKey + "; qm_keyst=" + musicKey;
            }
        } catch (Exception e) {
            log.warn("QQ 扫码授权失败: {}", e.getMessage());
            return "";
        }
    }

    // ------------------------------------------------------------------ 工具

    private String qrImage(String content) throws Exception {
        Map<EncodeHintType, Object> hints = new HashMap<>();
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, 1);
        var matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 600, 600, hints);
        BufferedImage image = new BufferedImage(matrix.getWidth(), matrix.getHeight(), BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < matrix.getWidth(); x++) {
            for (int y = 0; y < matrix.getHeight(); y++) {
                image.setRGB(x, y, matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    private String[] jsonpArgs(String body) {
        java.util.List<String> args = new java.util.ArrayList<>();
        Matcher matcher = Pattern.compile("'((?:\\\\.|[^'])*)'").matcher(body == null ? "" : body);
        while (matcher.find()) {
            args.add(matcher.group(1));
        }
        return args.toArray(new String[0]);
    }

    private String cookieHeader(List<String> setCookies) {
        if (setCookies == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : setCookies) {
            String pair = line.split(";", 2)[0];
            if (pair.contains("=")) {
                if (sb.length() > 0) {
                    sb.append("; ");
                }
                sb.append(pair);
            }
        }
        return sb.toString();
    }

    private String cookieValue(List<String> setCookies, String name) {
        if (setCookies == null) {
            return "";
        }
        for (String line : setCookies) {
            String pair = line.split(";", 2)[0];
            int index = pair.indexOf('=');
            if (index > 0 && pair.substring(0, index).trim().equals(name)) {
                return pair.substring(index + 1).trim();
            }
        }
        return "";
    }

    private String queryParam(String url, String name) {
        if (url == null) {
            return "";
        }
        Matcher matcher = Pattern.compile("[?&]" + Pattern.quote(name) + "=([^&#]+)").matcher(url);
        return matcher.find() ? java.net.URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8) : "";
    }

    private long hash33(String value) {
        long hash = 0;
        for (char c : value.toCharArray()) {
            hash = (hash + ((hash << 5) + c)) & 0x7FFFFFFFL;
        }
        return hash;
    }

    private long gtk(String key) {
        long hash = 5381;
        for (char c : key.toCharArray()) {
            hash = (hash + ((hash << 5) + c)) & 0x7FFFFFFFL;
        }
        return hash;
    }
}
