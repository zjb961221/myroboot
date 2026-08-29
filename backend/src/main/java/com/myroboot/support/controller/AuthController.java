package com.myroboot.support.controller;

import com.myroboot.support.config.SessionCookieFilter;
import com.myroboot.support.service.AuthRateLimitService;
import com.myroboot.support.service.AuthService;
import com.myroboot.support.service.EmailVerificationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthService authService;
    private final EmailVerificationService emailVerificationService;
    private final AuthRateLimitService rateLimitService;
    private final SessionCookieFilter sessionCookieFilter;
    private final JdbcTemplate jdbcTemplate;

    public AuthController(AuthService authService, EmailVerificationService emailVerificationService,
                          AuthRateLimitService rateLimitService, SessionCookieFilter sessionCookieFilter,
                          JdbcTemplate jdbcTemplate) {
        this.authService = authService;
        this.emailVerificationService = emailVerificationService;
        this.rateLimitService = rateLimitService;
        this.sessionCookieFilter = sessionCookieFilter;
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, Object> body, HttpServletRequest request,
                                     HttpServletResponse response) {
        String username = String.valueOf(body.getOrDefault("username", "")).trim();
        String password = String.valueOf(body.getOrDefault("password", ""));
        String ip = clientIp(request);
        String key = ip + "|" + username.toLowerCase();
        rateLimitService.checkLoginAllowed(key);
        try {
            Map<String, Object> result = authService.login(username, password);
            rateLimitService.recordLoginSuccess(key);
            String token = String.valueOf(result.get("token"));
            long expires = result.get("expiresInSeconds") instanceof Number n ? n.longValue() : 7 * 24 * 3600L;
            sessionCookieFilter.addSessionCookie(response, token, (int) Math.min(Integer.MAX_VALUE, expires));
            log.info("LOGIN_SUCCESS username={} ip={}", username, ip);
            return result;
        } catch (IllegalArgumentException e) {
            rateLimitService.recordLoginFailure(key);
            log.warn("LOGIN_FAILED username={} ip={}", username, ip);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, e.getMessage());
        }
    }

    @PostMapping("/register/code")
    public Map<String, Object> sendRegisterCode(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String ip = clientIp(request);
        rateLimitService.checkAndRecordCodeRequest(ip);
        try {
            emailVerificationService.sendRegisterCode(String.valueOf(body.getOrDefault("email", "")).trim());
            return Map.of("success", true);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping("/register")
    @Transactional
    public Map<String, Object> register(@RequestBody Map<String, Object> body) {
        try {
            String username = required(body, "username", "用户名不能为空");
            String displayName = required(body, "displayName", "姓名不能为空");
            String email = required(body, "email", "邮箱不能为空");
            String code = required(body, "code", "验证码不能为空");
            String companyName = required(body, "companyName", "单位不能为空");
            String mineName = required(body, "mineName", "矿井不能为空");
            String phone = required(body, "phone", "手机号不能为空");
            String password = required(body, "password", "密码不能为空");

            if (username.length() < 3 || username.length() > 50) throw new IllegalArgumentException("用户名长度需为 3-50 个字符");
            if (displayName.length() > 100) throw new IllegalArgumentException("姓名不能超过 100 个字符");
            if (companyName.length() > 200) throw new IllegalArgumentException("单位名称不能超过 200 个字符");
            if (mineName.length() > 200) throw new IllegalArgumentException("矿井名称不能超过 200 个字符");
            if (phone.length() > 50) throw new IllegalArgumentException("手机号格式不正确");
            if (password.length() < 8) throw new IllegalArgumentException("密码至少 8 位");

            Integer usernameExists = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM support_user WHERE username=?", Integer.class, username);
            if (usernameExists != null && usernameExists > 0) throw new IllegalArgumentException("用户名已存在");
            Integer emailExists = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM support_user WHERE email=?", Integer.class, email);
            if (emailExists != null && emailExists > 0) throw new IllegalArgumentException("邮箱已注册");

            emailVerificationService.verifyRegisterCode(email, code);
            jdbcTemplate.update("INSERT INTO support_user(username,email,password_hash,display_name,company_name,mine_name,phone,role,enabled) VALUES (?,?,?,?,?,?,?,'customer',1)",
                    username, email, authService.encodePassword(password), displayName, companyName, mineName, phone);
            log.info("USER_REGISTERED username={} emailDomain={}", username, emailDomain(email));
            return Map.of("success", true, "message", "注册成功，请登录");
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      HttpServletResponse response) {
        try {
            authService.logout(authorization);
            return Map.of("success", true);
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, e.getMessage());
        } finally {
            sessionCookieFilter.clearSessionCookie(response);
        }
    }

    @GetMapping("/me")
    public Map<String, Object> me(@RequestHeader(value = "Authorization", required = false) String authorization) {
        try {
            AuthService.Session session = authService.require(authorization);
            return Map.of("userId", session.userId(), "username", session.username(), "role", session.role(),
                    "displayName", session.displayName(), "companyName", session.companyName(),
                    "mineName", session.mineName(), "phone", session.phone());
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, e.getMessage());
        }
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",", 2)[0].trim();
            if (!first.isEmpty() && first.length() <= 64) return first;
        }
        return request.getRemoteAddr();
    }

    private String emailDomain(String email) {
        int at = email.indexOf('@');
        return at >= 0 ? email.substring(at + 1).toLowerCase() : "unknown";
    }

    private String required(Map<String, Object> body, String key, String message) {
        String value = String.valueOf(body.getOrDefault(key, "")).trim();
        if (value.isEmpty()) throw new IllegalArgumentException(message);
        return value;
    }
}
