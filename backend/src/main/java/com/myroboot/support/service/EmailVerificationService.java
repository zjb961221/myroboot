package com.myroboot.support.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
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
    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);
    private static final int EMAIL_HOURLY_LIMIT = 10;

    private final JdbcTemplate jdbcTemplate;
    private final JavaMailSender mailSender;
    private final SecureRandom random = new SecureRandom();

    @Value("${spring.mail.host:}") private String mailHost;
    @Value("${spring.mail.username:}") private String mailUsername;
    @Value("${support.mail.from:}") private String from;

    public EmailVerificationService(JdbcTemplate jdbcTemplate, JavaMailSender mailSender) {
        this.jdbcTemplate = jdbcTemplate;
        this.mailSender = mailSender;
    }

    public void sendRegisterCode(String email) {
        if (email == null || !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new IllegalArgumentException("邮箱格式不正确");
        }
        validateMailConfig();

        Integer exists = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM support_user WHERE email=?", Integer.class, email);
        if (exists != null && exists > 0) throw new IllegalArgumentException("该邮箱已注册");

        Integer sentLastHour = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM email_verification WHERE email=? AND purpose='register' AND create_time >= DATE_SUB(NOW(), INTERVAL 1 HOUR)",
                Integer.class, email);
        if (sentLastHour != null && sentLastHour >= EMAIL_HOURLY_LIMIT) {
            throw new AuthRateLimitService.RateLimitException("该邮箱验证码请求次数过多，请一小时后再试");
        }

        List<Map<String, Object>> recent = jdbcTemplate.queryForList(
                "SELECT create_time FROM email_verification WHERE email=? AND purpose='register' ORDER BY id DESC LIMIT 1", email);
        if (!recent.isEmpty()) {
            LocalDateTime created = ((java.sql.Timestamp) recent.get(0).get("create_time")).toLocalDateTime();
            if (created.plusSeconds(60).isAfter(LocalDateTime.now())) {
                throw new AuthRateLimitService.RateLimitException("验证码发送过于频繁，请 60 秒后再试");
            }
        }

        String code = String.format("%06d", random.nextInt(1_000_000));
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom((from == null || from.isBlank()) ? mailUsername : from);
        message.setTo(email);
        message.setSubject("MYROBOOT 技术支持平台注册验证码");
        message.setText("您的注册验证码是：" + code + "\n验证码 5 分钟内有效，请勿转发给他人。");

        try {
            mailSender.send(message);
            log.info("MAIL_CODE_SENT emailDomain={}", emailDomain(email));
        } catch (MailAuthenticationException e) {
            log.warn("MAIL_CODE_FAILED reason=authentication emailDomain={}", emailDomain(email));
            throw new IllegalArgumentException("验证码发送失败：邮箱认证失败，请检查 SMTP 授权码是否正确");
        } catch (MailException e) {
            String detail = rootMessage(e).toLowerCase();
            log.warn("MAIL_CODE_FAILED reason={} emailDomain={}", classifyMailFailure(detail), emailDomain(email));
            if (detail.contains("connection") || detail.contains("connect") || detail.contains("timed out") || detail.contains("timeout")) {
                throw new IllegalArgumentException("验证码发送失败：无法连接邮件服务器，请检查 SMTP 地址、端口和服务器网络");
            }
            if (detail.contains("authentication") || detail.contains("535") || detail.contains("password")) {
                throw new IllegalArgumentException("验证码发送失败：SMTP 授权码或邮箱账号不正确");
            }
            throw new IllegalArgumentException("验证码发送失败：邮件服务器拒绝发送，请检查 SMTP 配置");
        }

        // 发送成功后才启用新验证码，避免 SMTP 失败造成无效的 60 秒等待。
        jdbcTemplate.update("UPDATE email_verification SET used=1 WHERE email=? AND purpose='register' AND used=0", email);
        jdbcTemplate.update("INSERT INTO email_verification(email,code_hash,purpose,expires_time) VALUES (?,?,'register',DATE_ADD(NOW(), INTERVAL 5 MINUTE))",
                email, sha256(code));
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

    private void validateMailConfig() {
        if (mailHost == null || mailHost.isBlank()) {
            throw new IllegalArgumentException("验证码发送失败：服务器尚未配置 SMTP 邮箱");
        }
        if (mailHost.toLowerCase().contains("imap")) {
            throw new IllegalArgumentException("验证码发送失败：当前配置的是 IMAP 收件服务器，请改成 SMTP 发件服务器；QQ 邮箱应使用 smtp.qq.com");
        }
        if (mailUsername == null || mailUsername.isBlank()) {
            throw new IllegalArgumentException("验证码发送失败：服务器尚未配置发件邮箱账号");
        }
    }

    private String classifyMailFailure(String detail) {
        if (detail.contains("authentication") || detail.contains("535") || detail.contains("password")) return "authentication";
        if (detail.contains("connection") || detail.contains("connect") || detail.contains("timeout")) return "connection";
        return "provider";
    }

    private String emailDomain(String email) {
        int at = email.indexOf('@');
        return at >= 0 ? email.substring(at + 1).toLowerCase() : "unknown";
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? "" : current.getMessage();
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
