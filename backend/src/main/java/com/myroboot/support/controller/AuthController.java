package com.myroboot.support.controller;

import com.myroboot.support.service.AuthService;
import com.myroboot.support.service.EmailVerificationService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "*")
public class AuthController {
    private final AuthService authService;
    private final EmailVerificationService emailVerificationService;
    private final JdbcTemplate jdbcTemplate;

    public AuthController(AuthService authService, EmailVerificationService emailVerificationService, JdbcTemplate jdbcTemplate) {
        this.authService = authService;
        this.emailVerificationService = emailVerificationService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, Object> body) {
        try {
            return authService.login(String.valueOf(body.getOrDefault("username", "")).trim(), String.valueOf(body.getOrDefault("password", "")));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, e.getMessage());
        }
    }

    @PostMapping("/register/code")
    public Map<String, Object> sendRegisterCode(@RequestBody Map<String, Object> body) {
        try {
            emailVerificationService.sendRegisterCode(String.valueOf(body.getOrDefault("email", "")).trim());
            return Map.of("success", true);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody Map<String, Object> body) {
        try {
            String username = required(body, "username", "用户名不能为空");
            String email = required(body, "email", "邮箱不能为空");
            String password = required(body, "password", "密码不能为空");
            String code = required(body, "code", "验证码不能为空");
            String displayName = String.valueOf(body.getOrDefault("displayName", "")).trim();
            String companyName = String.valueOf(body.getOrDefault("companyName", "")).trim();
            String mineName = String.valueOf(body.getOrDefault("mineName", "")).trim();
            String phone = String.valueOf(body.getOrDefault("phone", "")).trim();
            if (username.length() < 3 || username.length() > 50) throw new IllegalArgumentException("用户名长度需为 3-50 个字符");
            if (password.length() < 6) throw new IllegalArgumentException("密码至少 6 位");
            Integer usernameExists = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM support_user WHERE username=?", Integer.class, username);
            if (usernameExists != null && usernameExists > 0) throw new IllegalArgumentException("用户名已存在");
            Integer emailExists = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM support_user WHERE email=?", Integer.class, email);
            if (emailExists != null && emailExists > 0) throw new IllegalArgumentException("邮箱已注册");
            emailVerificationService.verifyRegisterCode(email, code);
            jdbcTemplate.update("INSERT INTO support_user(username,email,password_hash,display_name,company_name,mine_name,phone,role,enabled) VALUES (?,?,?,?,?,?,?,'customer',1)",
                    username, email, authService.encodePassword(password), displayName, companyName, mineName, phone);
            return Map.of("success", true, "message", "注册成功，请登录");
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        try {
            authService.logout(authorization);
            return Map.of("success", true);
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, e.getMessage());
        }
    }

    @GetMapping("/me")
    public Map<String, Object> me(@RequestHeader(value = "Authorization", required = false) String authorization) {
        try {
            AuthService.Session session = authService.require(authorization);
            return Map.of("username", session.username(), "role", session.role());
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, e.getMessage());
        }
    }

    private String required(Map<String, Object> body, String key, String message) {
        String value = String.valueOf(body.getOrDefault(key, "")).trim();
        if (value.isEmpty()) throw new IllegalArgumentException(message);
        return value;
    }
}
