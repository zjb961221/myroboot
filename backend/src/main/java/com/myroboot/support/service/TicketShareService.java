package com.myroboot.support.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class TicketShareService {
    private static final Logger log = LoggerFactory.getLogger(TicketShareService.class);
    private static final int DEFAULT_HOURS = 24;
    private static final int MAX_HOURS = 24 * 30;

    private final JdbcTemplate jdbcTemplate;
    private final SecureRandom secureRandom = new SecureRandom();

    public TicketShareService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public Map<String, Object> create(AuthService.Session session, Long ticketId, Integer requestedHours) {
        Map<String, Object> ticket = requireTicketAccess(session, ticketId);
        int hours = requestedHours == null ? DEFAULT_HOURS : requestedHours;
        if (hours < 1 || hours > MAX_HOURS) {
            throw new IllegalArgumentException("分享有效期需在 1 小时到 30 天之间");
        }

        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        LocalDateTime expires = LocalDateTime.now().plusHours(hours);

        jdbcTemplate.update(
                "INSERT INTO ticket_share(ticket_id,token_hash,created_by,expires_time) VALUES (?,?,?,?)",
                ticketId, sha256(token), session.userId(), Timestamp.valueOf(expires)
        );
        Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        log.info("TICKET_SHARE_CREATED ticketId={} shareId={} userId={} expiresTime={}", ticketId, id, session.userId(), expires);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("ticketId", ticketId);
        result.put("token", token);
        result.put("expiresTime", expires);
        return result;
    }

    public List<Map<String, Object>> list(AuthService.Session session, Long ticketId) {
        requireTicketAccess(session, ticketId);
        if ("admin".equals(session.role())) {
            return jdbcTemplate.queryForList(
                    "SELECT id,ticket_id,created_by,expires_time,revoked,revoked_time,access_count,last_access_time,create_time " +
                            "FROM ticket_share WHERE ticket_id=? ORDER BY id DESC", ticketId);
        }
        return jdbcTemplate.queryForList(
                "SELECT id,ticket_id,created_by,expires_time,revoked,revoked_time,access_count,last_access_time,create_time " +
                        "FROM ticket_share WHERE ticket_id=? AND created_by=? ORDER BY id DESC",
                ticketId, session.userId());
    }

    @Transactional
    public boolean revoke(AuthService.Session session, Long ticketId, Long shareId) {
        requireTicketAccess(session, ticketId);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id,created_by,revoked FROM ticket_share WHERE id=? AND ticket_id=? FOR UPDATE", shareId, ticketId);
        if (rows.isEmpty()) throw new IllegalArgumentException("分享记录不存在");
        Map<String, Object> row = rows.get(0);
        long creator = ((Number) row.get("created_by")).longValue();
        if (!"admin".equals(session.role()) && creator != session.userId()) {
            throw new SecurityException("无权撤销该分享链接");
        }
        if (asBoolean(row.get("revoked"))) return true;
        jdbcTemplate.update("UPDATE ticket_share SET revoked=1,revoked_time=NOW() WHERE id=?", shareId);
        log.info("TICKET_SHARE_REVOKED ticketId={} shareId={} userId={}", ticketId, shareId, session.userId());
        return true;
    }

    @Transactional
    public ShareAccess open(String rawToken) {
        ShareAccess access = requireValid(rawToken);
        jdbcTemplate.update("UPDATE ticket_share SET access_count=access_count+1,last_access_time=NOW() WHERE id=?", access.shareId());
        return access;
    }

    public ShareAccess requireValid(String rawToken) {
        if (rawToken == null || rawToken.isBlank() || rawToken.length() > 200) {
            throw new SecurityException("分享链接无效");
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT s.id,s.ticket_id,s.expires_time FROM ticket_share s " +
                        "JOIN support_ticket t ON t.id=s.ticket_id " +
                        "WHERE s.token_hash=? AND s.revoked=0 AND s.expires_time>NOW() AND t.is_deleted=0 LIMIT 1",
                sha256(rawToken.trim()));
        if (rows.isEmpty()) throw new SecurityException("分享链接无效或已失效");
        Map<String, Object> row = rows.get(0);
        return new ShareAccess(
                ((Number) row.get("id")).longValue(),
                ((Number) row.get("ticket_id")).longValue(),
                toLocalDateTime(row.get("expires_time"))
        );
    }

    public Map<String, Object> sharedTicket(ShareAccess access) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id,category,description,screenshot_url,create_time FROM support_ticket WHERE id=? AND is_deleted=0",
                access.ticketId());
        if (rows.isEmpty()) throw new SecurityException("分享内容不存在");
        Map<String, Object> source = rows.get(0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", source.get("id"));
        result.put("category", source.get("category"));
        result.put("description", source.get("description"));
        result.put("create_time", source.get("create_time"));
        result.put("expires_time", access.expiresTime());
        String screenshot = String.valueOf(source.getOrDefault("screenshot_url", ""));
        result.put("has_screenshot", screenshot != null && !screenshot.isBlank() && !"null".equals(screenshot));

        List<Map<String, Object>> attachments = jdbcTemplate.queryForList(
                "SELECT id,original_name,content_type,file_size FROM ticket_attachment WHERE ticket_id=? ORDER BY id",
                access.ticketId());
        for (Map<String, Object> attachment : attachments) {
            attachment.put("file_url", "/api/public/ticket-share/attachments/" + attachment.get("id"));
        }
        result.put("attachments", attachments);
        return result;
    }

    public Map<String, Object> attachment(ShareAccess access, Long attachmentId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id,file_url,original_name,content_type,file_size FROM ticket_attachment WHERE id=? AND ticket_id=?",
                attachmentId, access.ticketId());
        if (rows.isEmpty()) throw new SecurityException("附件不存在或无权访问");
        return rows.get(0);
    }

    public String screenshotUrl(ShareAccess access) {
        List<String> rows = jdbcTemplate.query(
                "SELECT screenshot_url FROM support_ticket WHERE id=? AND is_deleted=0",
                (rs, rowNum) -> rs.getString(1), access.ticketId());
        if (rows.isEmpty() || rows.get(0) == null || rows.get(0).isBlank()) {
            throw new SecurityException("截图不存在");
        }
        return rows.get(0);
    }

    private Map<String, Object> requireTicketAccess(AuthService.Session session, Long ticketId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id,user_id,is_deleted FROM support_ticket WHERE id=?", ticketId);
        if (rows.isEmpty() || asBoolean(rows.get(0).get("is_deleted"))) {
            throw new IllegalArgumentException("工单不存在");
        }
        Map<String, Object> row = rows.get(0);
        if (!"admin".equals(session.role())) {
            Object owner = row.get("user_id");
            if (!(owner instanceof Number) || ((Number) owner).longValue() != session.userId()) {
                throw new SecurityException("无权分享该工单");
            }
        }
        return row;
    }

    private boolean asBoolean(Object value) {
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.intValue() != 0;
        return "1".equals(String.valueOf(value)) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    private LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof LocalDateTime localDateTime) return localDateTime;
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toLocalDateTime();
        if (value instanceof java.util.Date date) return new java.sql.Timestamp(date.getTime()).toLocalDateTime();
        return LocalDateTime.parse(String.valueOf(value).replace(' ', 'T'));
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("分享令牌处理失败", e);
        }
    }

    public record ShareAccess(long shareId, long ticketId, LocalDateTime expiresTime) {}
}
