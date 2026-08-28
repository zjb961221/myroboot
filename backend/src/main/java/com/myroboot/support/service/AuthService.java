package com.myroboot.support.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AuthService {
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
        Session session = fromUserRow(row);
        String token = UUID.randomUUID() + "." + UUID.randomUUID();
        jdbcTemplate.update("DELETE FROM support_session WHERE expires_time <= NOW()");
        jdbcTemplate.update(
                "INSERT INTO support_session(token_hash,user_id,expires_time) VALUES (?,?,DATE_ADD(NOW(), INTERVAL 7 DAY))",
                hashToken(token), session.userId()
        );
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
        String token = bearer(authorization);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT u.id,u.username,u.display_name,u.company_name,u.mine_name,u.phone,u.role,u.enabled " +
                        "FROM support_session s JOIN support_user u ON u.id=s.user_id " +
                        "WHERE s.token_hash=? AND s.expires_time>NOW() LIMIT 1",
                hashToken(token)
        );
        if (rows.isEmpty()) throw new SecurityException("登录已失效，请重新登录");
        Map<String, Object> row = rows.get(0);
        if (((Number) row.get("enabled")).intValue() != 1) throw new SecurityException("账号已停用，请联系管理员");
        return fromUserRow(row);
    }

    public Session requireAdmin(String authorization) {
        Session session = require(authorization);
        if (!"admin".equals(session.role())) throw new SecurityException("当前账号没有管理员权限");
        return session;
    }

    public void logout(String authorization) {
        String token = bearer(authorization);
        jdbcTemplate.update("DELETE FROM support_session WHERE token_hash=?", hashToken(token));
    }

    public String encodePassword(String rawPassword) {
        return passwordEncoder.encode(rawPassword);
    }

    private Session fromUserRow(Map<String, Object> row) {
        return new Session(
                ((Number) row.get("id")).longValue(),
                String.valueOf(row.get("username")),
                String.valueOf(row.get("role")),
                text(row.get("display_name")), text(row.get("company_name")), text(row.get("mine_name")), text(row.get("phone"))
        );
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
        String token = authorization.substring(7).trim();
        if (token.isEmpty()) throw new SecurityException("请先登录");
        return token;
    }

    private String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("无法初始化登录会话", e);
        }
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public record Session(Long userId, String username, String role, String displayName, String companyName, String mineName, String phone) {}
}
