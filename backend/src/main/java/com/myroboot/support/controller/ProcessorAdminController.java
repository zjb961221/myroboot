package com.myroboot.support.controller;

import com.myroboot.support.service.AuthService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/admin/processors")
public class ProcessorAdminController {
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private final JdbcTemplate jdbcTemplate;
    private final AuthService authService;

    public ProcessorAdminController(JdbcTemplate jdbcTemplate, AuthService authService) {
        this.jdbcTemplate = jdbcTemplate;
        this.authService = authService;
    }

    @PostMapping
    public Map<String, Object> create(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @RequestBody Map<String, Object> body) {
        requireAdmin(authorization);
        String username = required(body, "username", "请填写账号");
        String email = required(body, "email", "请填写邮箱");
        String password = required(body, "password", "请填写初始密码");
        String displayName = required(body, "displayName", "请填写姓名");
        String phone = required(body, "phone", "请填写手机号");
        if (password.length() < 8) throw new IllegalArgumentException("密码至少需要 8 位");
        if (!EMAIL.matcher(email).matches()) throw new IllegalArgumentException("邮箱格式不正确");
        ensureUnique(username, email, null);
        jdbcTemplate.update("INSERT INTO support_user(username,email,password_hash,display_name,company_name,mine_name,phone,role,enabled) VALUES (?,?,?,?,?,?,?,?,?)",
                username, email, authService.encodePassword(password), displayName, "技术支持团队", "技术支持团队", phone, "processor", enabled(body.get("enabled")));
        return Map.of("success", true, "id", Objects.requireNonNull(jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class)));
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @PathVariable Long id,
                                      @RequestBody Map<String, Object> body) {
        requireAdmin(authorization);
        String username = required(body, "username", "请填写账号");
        String email = required(body, "email", "请填写邮箱");
        String displayName = required(body, "displayName", "请填写姓名");
        String phone = required(body, "phone", "请填写手机号");
        if (!EMAIL.matcher(email).matches()) throw new IllegalArgumentException("邮箱格式不正确");
        ensureUnique(username, email, id);
        String password = text(body.get("password"));
        int count;
        if (password.isBlank()) {
            count = jdbcTemplate.update("UPDATE support_user SET username=?,email=?,display_name=?,phone=?,role='processor',enabled=? WHERE id=?",
                    username, email, displayName, phone, enabled(body.get("enabled")), id);
        } else {
            if (password.length() < 8) throw new IllegalArgumentException("密码至少需要 8 位");
            count = jdbcTemplate.update("UPDATE support_user SET username=?,email=?,password_hash=?,display_name=?,phone=?,role='processor',enabled=? WHERE id=?",
                    username, email, authService.encodePassword(password), displayName, phone, enabled(body.get("enabled")), id);
        }
        return Map.of("success", count > 0);
    }

    private void ensureUnique(String username, String email, Long excludeId) {
        Integer usernameCount = excludeId == null
                ? jdbcTemplate.queryForObject("SELECT COUNT(*) FROM support_user WHERE username=?", Integer.class, username)
                : jdbcTemplate.queryForObject("SELECT COUNT(*) FROM support_user WHERE username=? AND id<>?", Integer.class, username, excludeId);
        if (usernameCount != null && usernameCount > 0) throw new IllegalArgumentException("账号已存在");
        Integer emailCount = excludeId == null
                ? jdbcTemplate.queryForObject("SELECT COUNT(*) FROM support_user WHERE email=?", Integer.class, email)
                : jdbcTemplate.queryForObject("SELECT COUNT(*) FROM support_user WHERE email=? AND id<>?", Integer.class, email, excludeId);
        if (emailCount != null && emailCount > 0) throw new IllegalArgumentException("邮箱已被其他账号使用");
    }

    private int enabled(Object value) { return value == null || Boolean.parseBoolean(String.valueOf(value)) ? 1 : 0; }
    private String required(Map<String, Object> body, String key, String message) { String value = text(body.get(key)); if (value.isBlank()) throw new IllegalArgumentException(message); return value; }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private void requireAdmin(String authorization) { try { authService.requireAdmin(authorization); } catch (SecurityException e) { throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage()); } }
}
