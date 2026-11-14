package com.qymusic.filter;

import ch.qos.logback.core.util.MD5Util;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.server.WebFilter;

import java.io.IOException;

/**
 * Api 认证
 */
@Slf4j
@Component
public class ApiAuthFilter implements Filter {

    @Value("${api.auth.salt}")
    private String SALT;

    /** 允许使用的最低客户端版本号（versionCode），0 表示不限制 */
    @Value("${app.min-version-code:0}")
    private long minVersionCode;

    @Value("${spring.profiles.active}")
    private String profile;

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) throws IOException, ServletException {

        if (profile.equals("dev")) {
            filterChain.doFilter(servletRequest, servletResponse);
            return;
        }

        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse  response = (HttpServletResponse) servletResponse;

        // 应用更新接口不需要签名：浏览器也能直接下载安装包
        String uri = request.getRequestURI();
        if (uri != null && (uri.startsWith("/app/") || uri.equals("/app"))) {
            filterChain.doFilter(servletRequest, servletResponse);
            return;
        }

        String sign = request.getHeader("Sign");
        if (!StringUtils.hasText(sign)) {
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return;
        }

        String timestamp = request.getHeader("Timestamp");
        long timestampLong = Long.parseLong(timestamp);
        if (System.currentTimeMillis() - timestampLong > 10 * 1000L) {
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.setContentType("text/html;charset=utf-8");
            response.getWriter().write("<h1 style='font-size: 100px;'>来呀来呀充八万</h1>");
            return;
        }
        String version = request.getHeader("Version");
        String currentSign = DigestUtils.md5DigestAsHex((SALT + timestamp + version).getBytes());

        if (!currentSign.equalsIgnoreCase(sign)) {
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.setContentType("text/html;charset=utf-8");
            response.getWriter().write("<h1 style='font-size: 100px;'>来呀来呀充八万</h1>");
            return;
        }

        // 强制更新：低于最低版本的客户端不再提供任何业务数据
        long clientVersion;
        try {
            clientVersion = Long.parseLong(version);
        } catch (Exception e) {
            clientVersion = 0;
        }
        if (minVersionCode > 0 && clientVersion < minVersionCode) {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("application/json;charset=utf-8");
            response.getWriter().write("{\"code\":426,\"msg\":\"当前版本过低，请更新到最新版本后使用\",\"data\":null}");
            return;
        }

        filterChain.doFilter(servletRequest, servletResponse);
     }
}
