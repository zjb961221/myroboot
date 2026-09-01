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
public class FaqShareService {
    private static final Logger log = LoggerFactory.getLogger(FaqShareService.class);
    private static final int DEFAULT_HOURS = 24;
    private static final int MAX_HOURS = 24 * 30;

    private final JdbcTemplate jdbcTemplate;
    private final SecureRandom secureRandom = new SecureRandom();

    public FaqShareService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public Map<String, Object> create(AuthService.Session session, Long faqId, Integer requestedHours) {
        requireFaqAccess(session, faqId);
        int hours = requestedHours == null ? DEFAULT_HOURS : requestedHours;
        if (hours < 1 || hours > MAX_HOURS) throw new IllegalArgumentException("分享有效期需在 1 小时到 30 天之间");

        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        LocalDateTime expires = LocalDateTime.now().plusHours(hours);
        jdbcTemplate.update("INSERT INTO faq_share(faq_id,token_hash,created_by,expires_time) VALUES (?,?,?,?)",
                faqId, sha256(token), session.userId(), Timestamp.valueOf(expires));
        Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        log.info("FAQ_SHARE_CREATED faqId={} shareId={} userId={} expiresTime={}", faqId, id, session.userId(), expires);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("faqId", faqId);
        result.put("token", token);
        result.put("expiresTime", expires);
        return result;
    }

    public List<Map<String, Object>> list(AuthService.Session session, Long faqId) {
        requireFaqAccess(session, faqId);
        if ("admin".equals(session.role())) {
            return jdbcTemplate.queryForList("SELECT id,faq_id,created_by,expires_time,revoked,revoked_time,access_count,last_access_time,create_time FROM faq_share WHERE faq_id=? ORDER BY id DESC", faqId);
        }
        return jdbcTemplate.queryForList("SELECT id,faq_id,created_by,expires_time,revoked,revoked_time,access_count,last_access_time,create_time FROM faq_share WHERE faq_id=? AND created_by=? ORDER BY id DESC", faqId, session.userId());
    }

    @Transactional
    public boolean revoke(AuthService.Session session, Long faqId, Long shareId) {
        requireFaqAccess(session, faqId);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT id,created_by,revoked FROM faq_share WHERE id=? AND faq_id=? FOR UPDATE", shareId, faqId);
        if (rows.isEmpty()) throw new IllegalArgumentException("分享记录不存在");
        Map<String, Object> row = rows.get(0);
        long creator = ((Number) row.get("created_by")).longValue();
        if (!"admin".equals(session.role()) && creator != session.userId()) throw new SecurityException("无权撤销该分享链接");
        if (asBoolean(row.get("revoked"))) return true;
        jdbcTemplate.update("UPDATE faq_share SET revoked=1,revoked_time=NOW() WHERE id=?", shareId);
        log.info("FAQ_SHARE_REVOKED faqId={} shareId={} userId={}", faqId, shareId, session.userId());
        return true;
    }

    @Transactional
    public ShareAccess open(String rawToken) {
        ShareAccess access = requireValid(rawToken);
        jdbcTemplate.update("UPDATE faq_share SET access_count=access_count+1,last_access_time=NOW() WHERE id=?", access.shareId());
        return access;
    }

    public ShareAccess requireValid(String rawToken) {
        if (rawToken == null || rawToken.isBlank() || rawToken.length() > 200) throw new SecurityException("分享链接无效");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT s.id,s.faq_id,s.expires_time FROM faq_share s JOIN faq f ON f.id=s.faq_id WHERE s.token_hash=? AND s.revoked=0 AND s.expires_time>NOW() AND f.enabled=1 LIMIT 1",
                sha256(rawToken.trim()));
        if (rows.isEmpty()) throw new SecurityException("分享链接无效或已失效");
        Map<String, Object> row = rows.get(0);
        return new ShareAccess(((Number) row.get("id")).longValue(), ((Number) row.get("faq_id")).longValue(), toLocalDateTime(row.get("expires_time")));
    }

    public Map<String, Object> sharedFaq(ShareAccess access) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT id,category,question,answer,create_time,update_time FROM faq WHERE id=? AND enabled=1", access.faqId());
        if (rows.isEmpty()) throw new SecurityException("分享内容不存在");
        Map<String, Object> result = new LinkedHashMap<>(rows.get(0));
        result.put("expires_time", access.expiresTime());

        List<Map<String, Object>> images = jdbcTemplate.queryForList("SELECT id FROM faq_image WHERE faq_id=? ORDER BY sort_no,id", access.faqId());
        for (Map<String, Object> image : images) image.put("url", "/api/public/faq-share/images/" + image.get("id"));
        result.put("images", images);

        List<Map<String, Object>> attachments = jdbcTemplate.queryForList("SELECT id,original_name,content_type,file_size FROM faq_attachment WHERE faq_id=? ORDER BY sort_no,id", access.faqId());
        for (Map<String, Object> attachment : attachments) attachment.put("file_url", "/api/public/faq-share/attachments/" + attachment.get("id"));
        result.put("attachments", attachments);
        return result;
    }

    public Map<String, Object> attachment(ShareAccess access, Long attachmentId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT id,file_url,original_name,content_type,file_size FROM faq_attachment WHERE id=? AND faq_id=?", attachmentId, access.faqId());
        if (rows.isEmpty()) throw new SecurityException("附件不存在或无权访问");
        return rows.get(0);
    }

    public String imageUrl(ShareAccess access, Long imageId) {
        List<String> rows = jdbcTemplate.query("SELECT image_url FROM faq_image WHERE id=? AND faq_id=?", (rs, rowNum) -> rs.getString(1), imageId, access.faqId());
        if (rows.isEmpty() || rows.get(0) == null || rows.get(0).isBlank()) throw new SecurityException("图片不存在");
        return rows.get(0);
    }

    private void requireFaqAccess(AuthService.Session session, Long faqId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT id,enabled FROM faq WHERE id=?", faqId);
        if (rows.isEmpty()) throw new IllegalArgumentException("问题不存在");
        if (!"admin".equals(session.role()) && !asBoolean(rows.get(0).get("enabled"))) throw new SecurityException("无权分享该问题");
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

    public record ShareAccess(long shareId, long faqId, LocalDateTime expiresTime) {}
}
