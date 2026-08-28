package com.myroboot.support.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthService {
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final JdbcTemplate jdbcTemplate;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Value("${support.auth.customer-username:customer}") private String customerUsername;
    @Value("${support.auth.customer-password:customer123}") private String customerPassword;
    @Value("${support.auth.admin-username:admin}") private String adminUsername;
    @Value("${support.auth.admin-password:admin123}") private String adminPassword;

    public AuthService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void ensureDefaultUsers() {
        ensureUser(adminUsername, adminPassword, "系统管理员", "", "", "", "admin");
        ensureUser(customerUsername, customerPassword, "测试客户", "测试单位", "测试矿井", "", "customer");
    }

    public Map<String, Object> login(String username, String password) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, username, password_hash, display_name, company_name, mine_name, phone, role, enabled " +
                        "FROM support_user WHERE username = ? LIMIT 1", username
        );
        if (rows.isEmpty()) throw new IllegalArgumentException("用户名或密码错误");
        Map<String, Object> row = rows.get(0);
        if (((Number) row.get("enabled")).intValue() != 1 || !passwordEncoder.matches(password, String.valueOf(row.get("password_hash")))) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        Session session = new Session(
                ((Number) row.get("id")).longValue(),
                String.valueOf(row.get("username")),
                String.valueOf(row.get("role")),
                text(row.get("display_name")), text(row.get("company_name")), text(row.get("mine_name")), text(row.get("phone"))
        );
        String token = UUID.randomUUID().toString();
        sessions.put(token, session);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", token);
        result.put("userId", session.userId());
        result.put("username", session.username());
        result.put("role", session.role());
        result.put("displayName", session.displayName());
        result.put("companyName", session.companyName());
        result.put("mineName", session.mineName());
        result.put("phone", session.phone());
        return result;
    }

    public Session require(String authorization) {
        Session session = sessions.get(bearer(authorization));
        if (session == null) throw new SecurityException("登录已失效，请重新登录");
        return session;
    }

    public Session requireAdmin(String authorization) {
        Session session = require(authorization);
        if (!"admin".equals(session.role())) throw new SecurityException("无管理员权限");
        return session;
    }

    public void logout(String authorization) {
        sessions.remove(bearer(authorization));
    }

    public String encodePassword(String rawPassword) {
        return passwordEncoder.encode(rawPassword);
    }

    private void ensureUser(String username, String password, String displayName, String companyName, String mineName, String phone, String role) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM support_user WHERE username = ?", Integer.class, username);
        if (count != null && count == 0) {
            jdbcTemplate.update(
                    "INSERT INTO support_user(username, password_hash, display_name, company_name, mine_name, phone, role, enabled) VALUES (?, ?, ?, ?, ?, ?, ?, 1)",
                    username, passwordEncoder.encode(password), displayName, companyName, mineName, phone, role
            );
        }
    }

    private String bearer(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) throw new SecurityException("请先登录");
        return authorization.substring(7).trim();
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public record Session(Long userId, String username, String role, String displayName, String companyName, String mineName, String phone) {}
}
