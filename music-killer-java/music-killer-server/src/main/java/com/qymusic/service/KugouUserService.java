package com.qymusic.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.qymusic.api.KugouClient;
import com.qymusic.entity.Music;
import com.qymusic.entity.PlayList;
import com.qymusic.result.Result;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 酷狗概念版登录用户数据。
 *
 * <p>Cookie 由客户端保存在本机、随请求传入，服务端不落库也不打印。</p>
 */
@Slf4j
@Service
public class KugouUserService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 每日畅听 VIP 活动来源 */
    private static final String VIP_SOURCE_ID = "90139";

    @Resource
    private KugouClient kugouClient;

    public Result<Map<String, Object>> account(String cookie) {
        String userId = userId(cookie);
        if (userId.isBlank()) {
            return Result.error("请先登录酷狗音乐");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("loggedIn", true);
        data.put("userId", parseLong(userId));
        data.put("nickname", firstNonBlank(
                KugouClient.cookieValue(cookie, "username"),
                KugouClient.cookieValue(cookie, "NickName"),
                "酷狗用户 " + userId));
        data.put("avatarUrl", KugouCatalogService.secureUrl(firstNonBlank(
                KugouClient.cookieValue(cookie, "pic"),
                KugouClient.cookieValue(cookie, "photo"))));
        Map<String, Object> vip = vipInfo(cookie);
        data.put("isVip", vip.get("isVip"));
        data.put("vipType", vip.get("vipType"));
        data.put("vipExpireTime", vip.get("expireTime"));
        return Result.success(data);
    }

    public Result<List<PlayList>> playlists(String cookie) {
        String userId = userId(cookie);
        String token = KugouClient.cookieValue(cookie, "token");
        if (userId.isBlank() || token.isBlank()) {
            return Result.error("请先登录酷狗音乐");
        }
        ObjectNode body = MAPPER.createObjectNode();
        body.put("userid", parseLong(userId));
        body.put("token", token);
        body.put("total_ver", 979);
        body.put("type", 2);
        body.put("page", 1);
        body.put("pagesize", 50);
        JsonNode root = kugouClient.gatewayPost("/v7/get_all_list", body, null, cookie,
                "cloudlist.service.kugou.com");
        JsonNode list = root == null ? null : root.path("data").path("info");
        List<PlayList> playlists = new ArrayList<>();
        if (list != null && list.isArray()) {
            for (JsonNode node : list) {
                PlayList playlist = toPlayList(node, userId);
                if (playlist != null) {
                    playlists.add(playlist);
                }
            }
        }
        return Result.success(playlists);
    }

    /**
     * 酷狗概念版没有稳定的「我喜欢」读取接口，按约定返回空列表而不是报错。
     */
    public Result<List<Music>> liked(String cookie, int limit) {
        if (userId(cookie).isBlank()) {
            return Result.error("请先登录酷狗音乐");
        }
        return Result.success(new ArrayList<>());
    }

    /**
     * 概念版会员状态：kugouvip get_union_vip，失败时用 Cookie 里的 vip_type 兜底。
     */
    public Result<Map<String, Object>> vipStatus(String cookie) {
        if (userId(cookie).isBlank()) {
            return Result.error("请先登录酷狗音乐");
        }
        return Result.success(vipInfo(cookie));
    }

    /**
     * 每日签到：先领畅听 VIP，再申请概念版 VIP 升级。任一步失败不影响另一步。
     */
    public Result<Map<String, Object>> checkin(String cookie) {
        String userId = userId(cookie);
        String token = KugouClient.cookieValue(cookie, "token");
        if (userId.isBlank() || token.isBlank()) {
            return Result.error("请先登录酷狗音乐");
        }
        String day = LocalDate.now().toString();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("date", day);

        Map<String, String> listenParams = new LinkedHashMap<>();
        listenParams.put("source_id", VIP_SOURCE_ID);
        listenParams.put("receive_day", day);
        JsonNode listen = kugouClient.gatewayPostForm(
                "/youth/v1/recharge/receive_vip_listen_song", listenParams, cookie, null);
        Map<String, Object> listenStep = describeStep(listen, "畅听 VIP");

        Map<String, String> upgradeParams = new LinkedHashMap<>();
        upgradeParams.put("kugouid", userId);
        upgradeParams.put("ad_type", "1");
        JsonNode upgrade = kugouClient.gatewayPostForm(
                "/youth/v1/listen_song/upgrade_vip_reward", upgradeParams, cookie, null);
        Map<String, Object> upgradeStep = describeStep(upgrade, "概念版 VIP 升级");

        data.put("listenVip", listenStep);
        data.put("upgradeVip", upgradeStep);
        boolean listenOk = Boolean.TRUE.equals(listenStep.get("success"));
        boolean upgradeOk = Boolean.TRUE.equals(upgradeStep.get("success"));
        data.put("success", listenOk || upgradeOk);
        if (listenOk && upgradeOk) {
            data.put("tips", "今日畅听 VIP 与概念版 VIP 升级奖励均已领取");
        } else if (listenOk) {
            data.put("tips", "畅听 VIP 已领取；概念版 VIP 升级未成功：" + upgradeStep.get("message"));
        } else if (upgradeOk) {
            data.put("tips", "概念版 VIP 升级已领取；畅听 VIP 未成功：" + listenStep.get("message"));
        } else {
            data.put("tips", "本次未领取到会员奖励：" + listenStep.get("message"));
        }
        return Result.success(data);
    }

    // ------------------------------------------------------------------ 工具

    private Map<String, Object> vipInfo(String cookie) {
        Map<String, Object> data = new LinkedHashMap<>();
        int vipType = parseInt(KugouClient.cookieValue(cookie, "vip_type"));
        long expireSeconds = 0L;
        boolean vipFlag = false;
        boolean superVip = false;
        boolean freeListen = false;

        JsonNode root = kugouClient.signedGet("https://kugouvip.kugou.com", "/v1/get_union_vip",
                null, cookie, null);
        if (root != null) {
            Iterator<JsonNode> iterator = flatten(root).iterator();
            while (iterator.hasNext()) {
                JsonNode item = iterator.next();
                vipType = Math.max(vipType, item.path("vip_type").asInt(
                        item.path("vipType").asInt(0)));
                long expiry = item.path("vip_end_time").asLong(
                        item.path("vip_expire_time").asLong(
                                item.path("end_time").asLong(0)));
                if (expiry > 10_000_000_000L) {
                    expiry = expiry / 1000L;
                }
                expireSeconds = Math.max(expireSeconds, expiry);
                vipFlag = vipFlag || truthy(item.path("is_vip"))
                        || truthy(item.path("vip")) || truthy(item.path("vip_status"));
                superVip = superVip || truthy(item.path("super_vip"))
                        || truthy(item.path("is_super_vip")) || truthy(item.path("svip"));
                freeListen = freeListen || truthy(item.path("free_listen"))
                        || truthy(item.path("can_free_listen"));
            }
        }
        long now = System.currentTimeMillis() / 1000L;
        boolean isVip = vipFlag || vipType > 0 || expireSeconds > now;
        data.put("isVip", isVip);
        data.put("superVip", superVip || vipType == 6);
        data.put("vipType", vipType);
        data.put("expireTime", expireSeconds);
        data.put("freeListen", freeListen);
        return data;
    }

    /** 递归展开响应对象，兼容不同层级的会员字段。 */
    private List<JsonNode> flatten(JsonNode node) {
        List<JsonNode> result = new ArrayList<>();
        if (node == null) {
            return result;
        }
        if (node.isObject()) {
            result.add(node);
            node.forEach(child -> result.addAll(flatten(child)));
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                result.addAll(flatten(child));
            }
        }
        return result;
    }

    private Map<String, Object> describeStep(JsonNode node, String name) {
        Map<String, Object> step = new LinkedHashMap<>();
        int status = node == null ? 0 : node.path("status").asInt(0);
        int code = node == null ? 0 : node.path("error_code").asInt(
                node.path("errcode").asInt(0));
        String message = node == null ? "接口无响应"
                : firstNonBlank(node.path("error_msg").asText(""),
                node.path("errmsg").asText(""),
                node.path("error").asText(""),
                node.path("message").asText(""));
        boolean success = (status == 1 && code == 0) || code == 131001;
        boolean alreadyClaimed = code == 131001;
        if (!success && message.isBlank()) {
            if (code == 20028) {
                message = "当前账号暂时无法领取该奖励";
            } else if (code == 20006) {
                message = "签名校验失败，请稍后重试";
            } else {
                message = "领取失败（错误码 " + (code > 0 ? code : status) + "）";
            }
        }
        if (alreadyClaimed && message.isBlank()) {
            message = "今日已领取，无需重复签到";
        }
        step.put("name", name);
        step.put("success", success);
        step.put("alreadyClaimed", alreadyClaimed);
        step.put("code", code > 0 ? code : status);
        step.put("message", message);
        return step;
    }

    private PlayList toPlayList(JsonNode node, String userId) {
        long listId = node.path("listid").asLong(node.path("specialid").asLong(0));
        if (listId <= 0) {
            return null;
        }
        PlayList playlist = new PlayList();
        playlist.setId(listId);
        playlist.setName(clean(firstNonBlank(node.path("name").asText(""),
                node.path("specialname").asText(""))));
        String pic = KugouCatalogService.secureUrl(firstNonBlank(
                node.path("pic").asText(""), node.path("imgurl").asText("")));
        playlist.setImg(pic);
        playlist.setImg700(pic);
        playlist.setDesc(clean(node.path("intro").asText("")));
        playlist.setTotal(node.path("count").asInt(node.path("m_count").asInt(
                node.path("songcount").asInt(0))));
        playlist.setUname(clean(node.path("list_create_username").asText("")));
        playlist.setListencnt(node.path("playcount").asLong(0));
        String owner = node.path("list_create_userid").asText("");
        playlist.setTag(owner.isBlank() || owner.equals(userId) ? "mine" : "collect");
        return playlist;
    }

    private String clean(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("<[^>]*>", "")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .trim();
    }

    private boolean truthy(JsonNode node) {        if (node == null || node.isMissingNode() || node.isNull()) {
            return false;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isNumber()) {
            return node.asLong() != 0;
        }
        String value = node.asText("");
        return "1".equals(value) || "true".equalsIgnoreCase(value);
    }

    private int parseInt(String value) {
        try {
            return Integer.parseInt(value == null ? "" : value.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private long parseLong(String value) {
        try {
            return Long.parseLong(value == null ? "" : value.trim());
        } catch (Exception e) {
            return 0L;
        }
    }

    private String userId(String cookie) {
        if (!StringUtils.hasText(cookie)) {
            return "";
        }
        return firstNonBlank(KugouClient.cookieValue(cookie, "userid"),
                KugouClient.cookieValue(cookie, "KugooID"));
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
