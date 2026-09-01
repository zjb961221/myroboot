package com.myroboot.support.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;

@Component
public class SessionCookieFilter extends OncePerRequestFilter {
    public static final String COOKIE_NAME = "support_session";

    @Value("${support.auth.session-hours:168}")
    private int sessionHours;

    @Value("${support.auth.cookie-secure:false}")
    private boolean secure;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        String cookieToken = cookieToken(request);
        String path = request.getRequestURI();

        HttpServletRequest effectiveRequest = request;
        if ((authorization == null || authorization.isBlank()) && cookieToken != null) {
            String injected = "Bearer " + cookieToken;
            effectiveRequest = new HttpServletRequestWrapper(request) {
                @Override
                public String getHeader(String name) {
                    if ("Authorization".equalsIgnoreCase(name)) return injected;
                    return super.getHeader(name);
                }

                @Override
                public Enumeration<String> getHeaders(String name) {
                    if ("Authorization".equalsIgnoreCase(name)) return Collections.enumeration(java.util.List.of(injected));
                    return super.getHeaders(name);
                }
            };
        }

        // 兼容升级：老版本浏览器只有 localStorage Bearer Token。
        // 第一次正常 API 请求时同步成 HttpOnly Cookie，之后图片/视频/PDF 原生请求即可鉴权。
        if (cookieToken == null && authorization != null && authorization.startsWith("Bearer ")
                && !path.equals("/api/auth/logout")) {
            String token = authorization.substring(7).trim();
            if (!token.isBlank()) addSessionCookie(response, token, Math.max(1, sessionHours) * 3600);
        }

        filterChain.doFilter(effectiveRequest, response);
    }

    public void addSessionCookie(HttpServletResponse response, String token, int maxAgeSeconds) {
        StringBuilder value = new StringBuilder();
        value.append(COOKIE_NAME).append('=').append(token)
                .append("; Path=/; HttpOnly; SameSite=Lax; Max-Age=").append(maxAgeSeconds);
        if (secure) value.append("; Secure");
        response.addHeader("Set-Cookie", value.toString());
    }

    public void clearSessionCookie(HttpServletResponse response) {
        StringBuilder value = new StringBuilder();
        value.append(COOKIE_NAME).append("=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0");
        if (secure) value.append("; Secure");
        response.addHeader("Set-Cookie", value.toString());
    }

    private String cookieToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
