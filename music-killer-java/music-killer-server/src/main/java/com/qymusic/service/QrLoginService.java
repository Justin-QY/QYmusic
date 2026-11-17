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
 * 扫码登录：网易云 / QQ 音乐 / 酷狗概念版。
 *
 * <p>协议参考 QPlayer 的 provider 插件：
 * 网易云 {@code /api/login/qrcode/unikey} + {@code client/login}；
 * QQ {@code ptqrshow → ptqrlogin → check_sig → oauth2/authorize → QQLogin}；
 * 酷狗概念版 {@code login-user.kugou.com/v2/qrcode} + {@code get_userinfo_qrcode}。</p>
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

    // ------------------------------------------------------------------ 酷狗（概念版）

    private static final String KG_LOGIN = "https://login-user.kugou.com";
    private static final String KG_SIGN_KEY = "NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt";
    private static final String KG_LITE_SIGN = "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA";
    private static final String KG_UA = "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi";
    private static final String KG_RSA_N = "c40a2d0da76511f3bb1cc2bbd3afbd8bea83b4d6b05b6c13eb8920c53f1af7679b32ba0d0edb843240ef1b836efed3ee240734c14c1399fd6594d16af22f52525d14d72e0155c6dcc8638d4f7bb94f3a0b1f4c29f991972f2a160a25eb0a9e724336be7f69bbd319ffab1c6dd8470b021dc434f3faba89f4a2a01b33bdbdd08b";
    private static final String KG_RSA_E = "010001";

    /**
     * 创建酷狗概念版登录二维码。
     */
    public Map<String, Object> kugouCreate() throws Exception {
        Map<String, String> params = kgBaseParams();
        params.put("appid", "1001");
        params.put("type", "1");
        params.put("plat", "4");
        params.put("qrcode_txt",
                "https://h5.kugou.com/apps/loginQRCode/html/index.html?appid=3116&");
        params.put("srcappid", "2919");
        JsonNode root = kgSignedGet("/v2/qrcode", params);
        String key = root == null ? "" : root.path("data").path("qrcode").asText("");
        if (key.isBlank()) {
            throw new IllegalStateException("酷狗未返回登录二维码");
        }
        String url = "https://h5.kugou.com/apps/loginQRCode/html/index.html?qrcode="
                + java.net.URLEncoder.encode(key, StandardCharsets.UTF_8);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("key", key);
        data.put("url", url);
        data.put("image", qrImage(url));
        data.put("expiresIn", 300);
        return data;
    }

    /**
     * 轮询酷狗概念版扫码状态。
     *
     * <p>status：4 登录成功（带 token/userid）；2、3 已扫码；-1、5、6 已过期。</p>
     */
    public Map<String, Object> kugouPoll(String key) throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        if (key == null || key.isBlank()) {
            data.put("status", "failed");
            data.put("message", "缺少登录 key");
            return data;
        }
        Map<String, String> params = kgBaseParams();
        params.put("plat", "4");
        params.put("appid", "3116");
        params.put("srcappid", "2919");
        params.put("qrcode", key);
        JsonNode root = kgSignedGet("/v2/get_userinfo_qrcode", params);
        JsonNode payload = root == null ? null : root.path("data");
        int status = payload == null ? -1 : payload.path("status").asInt(-1);
        if (status == 4) {
            String token = payload.path("token").asText("");
            String userId = payload.path("userid").asText("");
            if (token.isBlank() || userId.isBlank() || "0".equals(userId)) {
                data.put("status", "failed");
                data.put("message", "登录成功但未返回 token，请重新扫码");
                return data;
            }
            String guid = randomGuid();
            String mid = new java.math.BigInteger(
                    md5Hex(guid), 16).toString();
            // 概念版云歌单/播放授权需要设备标识 dfid，扫码成功后顺手注册设备
            String dfid = registerDevice(token, userId, guid, mid);
            data.put("status", "success");
            data.put("userId", userId);
            data.put("cookie", "token=" + token
                    + "; userid=" + userId
                    + "; KugooID=" + userId
                    + "; KUGOU_API_GUID=" + guid
                    + "; KUGOU_API_MID=" + mid
                    + (dfid.isBlank() ? "" : "; dfid=" + dfid));
        } else if (status == 2 || status == 3) {
            data.put("status", "scanned");
            data.put("message", "已扫码，请在酷狗 App 中确认");
        } else if (status == -1 || status == 5 || status == 6) {
            data.put("status", "expired");
            data.put("message", "二维码已失效，请重新生成");
        } else {
            data.put("status", "waiting");
            data.put("message", "等待扫码");
        }
        return data;
    }

    /**
     * 酷狗概念版设备注册：AES 加密设备信息 + RSA 加密 token，换取 dfid。
     * 云歌单、登录态播放都依赖 dfid，缺失时列表会为空。
     */
    /**
     * 供用户歌单等场景补齐 dfid：老登录态没有 dfid 时也能用。
     */
    public String ensureDfid(String token, String userId) {
        if (token == null || token.isBlank() || userId == null || userId.isBlank()) {
            return "";
        }
        String guid = java.util.UUID.randomUUID().toString();
        String mid;
        try {
            mid = new java.math.BigInteger(md5Hex(guid), 16).toString();
        } catch (Exception e) {
            mid = "0";
        }
        return registerDevice(token, userId, guid, mid);
    }

    private String registerDevice(String token, String userId, String guid, String mid) {
        try {
            String seed = randomKugouString(6).toLowerCase();
            String digest = md5Hex(seed);
            byte[] key = digest.substring(0, 16).getBytes(StandardCharsets.UTF_8);
            byte[] iv = digest.substring(16, 32).getBytes(StandardCharsets.UTF_8);

            ObjectNode device = MAPPER.createObjectNode();
            device.put("availableRamSize", 4983533568L);
            device.put("availableRomSize", 48114719);
            device.put("availableSDSize", 48114717);
            device.put("basebandVer", "");
            device.put("batteryLevel", 100);
            device.put("batteryStatus", 3);
            device.put("brand", "Redmi");
            device.put("buildSerial", "unknown");
            device.put("device", "marble");
            device.put("imei", guid);
            device.put("imsi", "");
            device.put("manufacturer", "Xiaomi");
            device.put("uuid", guid);

            String data = aesEncrypt(device.toString(), key, iv);
            ObjectNode plain = MAPPER.createObjectNode();
            plain.put("aes", seed);
            plain.put("uid", userId);
            plain.put("token", token);
            String encryptedToken = rsaEncryptPkcs1(plain.toString());

            String clienttime = String.valueOf(System.currentTimeMillis() / 1000);
            Map<String, String> params = new LinkedHashMap<>();
            params.put("dfid", "-");
            params.put("mid", mid);
            params.put("uuid", "-");
            params.put("appid", "3116");
            params.put("clientver", "11440");
            params.put("clienttime", clienttime);
            params.put("token", token);
            params.put("userid", userId);
            params.put("part", "1");
            params.put("platid", "1");
            params.put("p", encryptedToken);

            List<String> keys = new java.util.ArrayList<>(params.keySet());
            keys.sort(String::compareTo);
            StringBuilder joined = new StringBuilder();
            for (String name : keys) {
                joined.append(name).append('=').append(params.get(name));
            }
            params.put("signature", md5Hex(KG_LITE_SIGN + joined + data + KG_LITE_SIGN));

            StringBuilder query = new StringBuilder();
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (query.length() > 0) {
                    query.append('&');
                }
                query.append(java.net.URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                        .append('=')
                        .append(java.net.URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
            }

            Request request = new Request.Builder()
                    .url("https://userservice.kugou.com/risk/v2/r_register_dev?" + query)
                    .header("User-Agent", KG_UA)
                    .header("dfid", "-")
                    .header("clienttime", clienttime)
                    .header("mid", mid)
                    .header("kg-rc", "1")
                    .header("kg-thash", "5d816a0")
                    .header("kg-rec", "1")
                    .header("kg-rf", "B9EDA08A64250DEFFBCADDEE00F8F25F")
                    .header("Cookie", "token=" + token + "; userid=" + userId)
                    .post(RequestBody.create(data, MediaType.parse("application/octet-stream")))
                    .build();
            try (Response response = CLIENT.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    return "";
                }
                String body = response.body().string().trim();
                if (!body.startsWith("{")) {
                    body = aesDecrypt(body, key, iv);
                }
                JsonNode root = MAPPER.readTree(body);
                String dfid = root.path("data").path("dfid").asText("");
                if (dfid.isBlank()) {
                    log.warn("酷狗设备注册未返回 dfid: {}", body.length() > 200 ? body.substring(0, 200) : body);
                }
                return dfid;
            }
        } catch (Exception e) {
            log.warn("酷狗设备注册失败: {}", e.getMessage());
            return "";
        }
    }

    private String aesEncrypt(String text, byte[] key, byte[] iv) throws Exception {
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,
                new javax.crypto.spec.SecretKeySpec(key, "AES"),
                new javax.crypto.spec.IvParameterSpec(iv));
        return Base64.getEncoder().encodeToString(
                cipher.doFinal(text.getBytes(StandardCharsets.UTF_8)));
    }

    private String aesDecrypt(String base64, byte[] key, byte[] iv) throws Exception {
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE,
                new javax.crypto.spec.SecretKeySpec(key, "AES"),
                new javax.crypto.spec.IvParameterSpec(iv));
        return new String(cipher.doFinal(Base64.getDecoder().decode(base64)), StandardCharsets.UTF_8);
    }

    /** RSA PKCS#1 v1.5 加密，输出 256 位十六进制（酷狗 1024 位公钥） */
    private String rsaEncryptPkcs1(String text) {
        byte[] message = text.getBytes(StandardCharsets.UTF_8);
        int blockSize = 128;
        int paddingLength = blockSize - 3 - message.length;
        if (paddingLength < 8) {
            throw new IllegalArgumentException("RSA 明文过长");
        }
        byte[] block = new byte[blockSize];
        block[0] = 0;
        block[1] = 2;
        java.security.SecureRandom random = new java.security.SecureRandom();
        for (int i = 0; i < paddingLength; i++) {
            int value;
            do {
                value = random.nextInt(256);
            } while (value == 0);
            block[2 + i] = (byte) value;
        }
        block[2 + paddingLength] = 0;
        System.arraycopy(message, 0, block, 3 + paddingLength, message.length);
        java.math.BigInteger modulus = new java.math.BigInteger(KG_RSA_N, 16);
        java.math.BigInteger exponent = new java.math.BigInteger(KG_RSA_E, 16);
        String hex = new java.math.BigInteger(1, block).modPow(exponent, modulus).toString(16);
        StringBuilder sb = new StringBuilder(hex);
        while (sb.length() < 256) {
            sb.insert(0, '0');
        }
        return sb.toString().toUpperCase();
    }

    private String randomKugouString(int length) {
        String chars = "1234567890ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        StringBuilder sb = new StringBuilder();
        java.util.Random random = new java.util.Random();
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }

    private Map<String, String> kgBaseParams() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("dfid", "-");
        params.put("mid", "-");
        params.put("uuid", "-");
        params.put("appid", "3116");
        params.put("clientver", "11440");
        params.put("clienttime", String.valueOf(System.currentTimeMillis() / 1000));
        return params;
    }

    private JsonNode kgSignedGet(String path, Map<String, String> params) throws Exception {
        List<String> keys = new java.util.ArrayList<>(params.keySet());
        keys.sort(String::compareTo);
        StringBuilder joined = new StringBuilder();
        for (String name : keys) {
            joined.append(name).append('=').append(params.get(name));
        }
        params.put("signature", md5Hex(KG_SIGN_KEY + joined + KG_SIGN_KEY));
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (query.length() > 0) {
                query.append('&');
            }
            query.append(java.net.URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(java.net.URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        Request request = new Request.Builder()
                .url(KG_LOGIN + path + "?" + query)
                .header("User-Agent", KG_UA)
                .header("dfid", params.getOrDefault("dfid", "-"))
                .header("mid", params.getOrDefault("mid", "-"))
                .header("clienttime", params.getOrDefault("clienttime", ""))
                .build();
        try (Response response = CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                return null;
            }
            String body = response.body().string();
            int index = body.indexOf('{');
            if (index < 0) {
                return null;
            }
            return MAPPER.readTree(index > 0 ? body.substring(index) : body);
        }
    }

    private String randomGuid() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 32; i++) {
            sb.append(Integer.toHexString(java.util.concurrent.ThreadLocalRandom.current().nextInt(16)));
        }
        return sb.toString();
    }

    private String md5Hex(String value) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("MD5");
        byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
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
