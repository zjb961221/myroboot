package com.myroboot.support.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthService {
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    @Value("${support.auth.customer-username:customer}")
    private String customerUsername;

    @Value("${support.auth.customer-password:customer123}")
    private String customerPassword;

    @Value("${support.auth.admin-username:admin}")
    private String adminUsername;

    @Value("${support.auth.admin-password:admin123}")
    private String adminPassword;

    public Map<String, Object> login(String username, String password) {
        String role;
        if (adminUsername.equals(username) && adminPassword.equals(password)) {
            role = "admin";
        } else if (customerUsername.equals(username) && customerPassword.equals(password)) {
            role = "customer";
        } else {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        String token = UUID.randomUUID().toString();
        sessions.put(token, new Session(username, role));
        return Map.of("token", token, "username", username, "role", role);
    }

    public Session require(String authorization) {
        String token = bearer(authorization);
        Session session = sessions.get(token);
        if (session == null) throw new SecurityException("登录已失效，请重新登录");
        return session;
    }

    public Session requireAdmin(String authorization) {
        Session session = require(authorization);
        if (!"admin".equals(session.role())) throw new SecurityException("无管理员权限");
        return session;
    }

    public void logout(String authorization) {
        String token = bearer(authorization);
        sessions.remove(token);
    }

    private String bearer(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new SecurityException("请先登录");
        }
        return authorization.substring(7).trim();
    }

    public record Session(String username, String role) {}
}
