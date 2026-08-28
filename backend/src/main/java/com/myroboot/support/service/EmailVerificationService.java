package com.myroboot.support.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Service
public class EmailVerificationService {
    private final JdbcTemplate jdbcTemplate;
    private final JavaMailSender mailSender;
    private final SecureRandom random = new SecureRandom();

    @Value("${support.mail.from:}")
    private String from;

    public EmailVerificationService(JdbcTemplate jdbcTemplate, JavaMailSender mailSender) {
        this.jdbcTemplate = jdbcTemplate;
        this.mailSender = mailSender;
    }

    public void sendRegisterCode(String email) {
        if (email == null || !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new IllegalArgumentException("邮箱格式不正确");
        }
        Integer exists = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM support_user WHERE email=?", Integer.class, email);
        if (exists != null && exists > 0) throw new IllegalArgumentException("该邮箱已注册");

        List<Map<String, Object>> recent = jdbcTemplate.queryForList(
                "SELECT create_time FROM email_verification WHERE email=? AND purpose='register' ORDER BY id DESC LIMIT 1", email);
        if (!recent.isEmpty()) {
            LocalDateTime created = ((java.sql.Timestamp) recent.get(0).get("create_time")).toLocalDateTime();
            if (created.plusSeconds(60).isAfter(LocalDateTime.now())) throw new IllegalArgumentException("验证码发送过于频繁，请稍后再试");
        }

        String code = String.format("%06d", random.nextInt(1_000_000));
        jdbcTemplate.update("UPDATE email_verification SET used=1 WHERE email=? AND purpose='register' AND used=0", email);
        jdbcTemplate.update("INSERT INTO email_verification(email,code_hash,purpose,expires_time) VALUES (?,?,'register',DATE_ADD(NOW(), INTERVAL 5 MINUTE))",
                email, sha256(code));

        SimpleMailMessage message = new SimpleMailMessage();
        if (from != null && !from.isBlank()) message.setFrom(from);
        message.setTo(email);
        message.setSubject("MYROBOOT 技术支持平台注册验证码");
        message.setText("您的注册验证码是：" + code + "\n验证码 5 分钟内有效，请勿转发给他人。");
        mailSender.send(message);
    }

    public void verifyRegisterCode(String email, String code) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, code_hash, expires_time FROM email_verification WHERE email=? AND purpose='register' AND used=0 ORDER BY id DESC LIMIT 1", email);
        if (rows.isEmpty()) throw new IllegalArgumentException("请先获取邮箱验证码");
        Map<String, Object> row = rows.get(0);
        LocalDateTime expires = ((java.sql.Timestamp) row.get("expires_time")).toLocalDateTime();
        if (expires.isBefore(LocalDateTime.now())) throw new IllegalArgumentException("验证码已过期，请重新获取");
        if (!sha256(code == null ? "" : code.trim()).equals(String.valueOf(row.get("code_hash")))) {
            throw new IllegalArgumentException("验证码错误");
        }
        jdbcTemplate.update("UPDATE email_verification SET used=1 WHERE id=?", row.get("id"));
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("验证码处理失败", e);
        }
    }
}
