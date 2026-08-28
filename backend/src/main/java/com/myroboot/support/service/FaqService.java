package com.myroboot.support.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class FaqService {
    private static final Logger log = LoggerFactory.getLogger(FaqService.class);
    private final JdbcTemplate jdbcTemplate;

    public FaqService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Map<String, Object>> listEnabled() {
        return enrichResources(jdbcTemplate.queryForList(
                "SELECT id,category,question,answer,keywords FROM faq WHERE enabled=1 ORDER BY id DESC"));
    }

    public List<Map<String, Object>> search(String keyword, int limit) {
        if (keyword == null || keyword.isBlank()) return listEnabled();
        return enrichResources(searchRows(keyword.trim(), limit));
    }

    public List<Map<String, Object>> suggest(String keyword, int limit) {
        if (keyword == null || keyword.isBlank()) return List.of();
        return searchRows(keyword.trim(), limit).stream().map(row -> Map.<String, Object>of(
                "id", row.get("id"),
                "category", row.get("category"),
                "question", row.get("question")
        )).toList();
    }

    public List<Map<String, Object>> listAdmin() {
        return enrichResources(jdbcTemplate.queryForList(
                "SELECT id,category,question,answer,keywords,enabled,create_time,update_time FROM faq ORDER BY id DESC"));
    }

    @Transactional
    public Long create(AuthService.Session admin, Map<String, Object> body) {
        String category = required(body, "category", "分类不能为空");
        String question = required(body, "question", "问题标题不能为空");
        String answer = required(body, "answer", "解决方案不能为空");
        validateLengths(category, question, answer, body.get("keywords"));
        jdbcTemplate.update("INSERT INTO faq(category,question,answer,keywords,enabled) VALUES (?,?,?,?,?)",
                category, question, answer, body.get("keywords"), asEnabled(body.get("enabled")));
        Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        if (id == null) throw new IllegalStateException("问题库保存失败");
        replaceImages(id, body.get("images"));
        replaceAttachments(id, body.get("attachments"));
        log.info("FAQ_CREATED faqId={} operatorUserId={}", id, admin.userId());
        return id;
    }

    @Transactional
    public void update(AuthService.Session admin, Long id, Map<String, Object> body) {
        String category = required(body, "category", "分类不能为空");
        String question = required(body, "question", "问题标题不能为空");
        String answer = required(body, "answer", "解决方案不能为空");
        validateLengths(category, question, answer, body.get("keywords"));
        int updated = jdbcTemplate.update("UPDATE faq SET category=?,question=?,answer=?,keywords=?,enabled=? WHERE id=?",
                category, question, answer, body.get("keywords"), asEnabled(body.get("enabled")), id);
        if (updated == 0) throw new IllegalArgumentException("问题不存在或已被删除");
        replaceImages(id, body.get("images"));
        replaceAttachments(id, body.get("attachments"));
        log.info("FAQ_UPDATED faqId={} operatorUserId={}", id, admin.userId());
    }

    @Transactional
    public boolean delete(AuthService.Session admin, Long id) {
        jdbcTemplate.update("DELETE FROM faq_image WHERE faq_id=?", id);
        jdbcTemplate.update("DELETE FROM faq_attachment WHERE faq_id=?", id);
        int updated = jdbcTemplate.update("DELETE FROM faq WHERE id=?", id);
        if (updated > 0) log.info("FAQ_DELETED faqId={} operatorUserId={}", id, admin.userId());
        return updated > 0;
    }

    private List<Map<String, Object>> searchRows(String keyword, int limit) {
        String like = "%" + keyword + "%";
        try {
            return jdbcTemplate.queryForList(
                    "SELECT id,category,question,answer,keywords,MATCH(category,question,answer,keywords) AGAINST (? IN NATURAL LANGUAGE MODE) AS relevance " +
                            "FROM faq WHERE enabled=1 AND (MATCH(category,question,answer,keywords) AGAINST (? IN NATURAL LANGUAGE MODE)>0 OR category LIKE ? OR question LIKE ? OR answer LIKE ? OR keywords LIKE ?) " +
                            "ORDER BY relevance DESC,CASE WHEN question LIKE ? THEN 0 ELSE 1 END,id DESC LIMIT ?",
                    keyword, keyword, like, like, like, like, like, limit);
        } catch (DataAccessException e) {
            log.debug("FAQ full-text query unavailable; using LIKE fallback: {}", e.getMostSpecificCause().getMessage());
            return jdbcTemplate.queryForList(
                    "SELECT id,category,question,answer,keywords FROM faq WHERE enabled=1 AND (category LIKE ? OR question LIKE ? OR answer LIKE ? OR keywords LIKE ?) ORDER BY CASE WHEN question LIKE ? THEN 0 ELSE 1 END,id DESC LIMIT ?",
                    like, like, like, like, like, limit);
        }
    }

    private List<Map<String, Object>> enrichResources(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return rows;
        List<Long> ids = rows.stream().map(row -> ((Number) row.get("id")).longValue()).toList();
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        Object[] args = ids.toArray();

        Map<Long, List<String>> imagesByFaq = new HashMap<>();
        for (Map<String, Object> image : jdbcTemplate.queryForList(
                "SELECT faq_id,image_url FROM faq_image WHERE faq_id IN (" + placeholders + ") ORDER BY faq_id,sort_no,id", args)) {
            Long faqId = ((Number) image.get("faq_id")).longValue();
            imagesByFaq.computeIfAbsent(faqId, ignored -> new ArrayList<>()).add(String.valueOf(image.get("image_url")));
        }

        Map<Long, List<Map<String, Object>>> attachmentsByFaq = new HashMap<>();
        for (Map<String, Object> attachment : jdbcTemplate.queryForList(
                "SELECT id,faq_id,file_url,original_name,content_type,file_size,create_time FROM faq_attachment WHERE faq_id IN (" + placeholders + ") ORDER BY faq_id,sort_no,id", args)) {
            Long faqId = ((Number) attachment.get("faq_id")).longValue();
            attachmentsByFaq.computeIfAbsent(faqId, ignored -> new ArrayList<>()).add(attachment);
        }

        for (Map<String, Object> row : rows) {
            Long faqId = ((Number) row.get("id")).longValue();
            row.put("images", imagesByFaq.getOrDefault(faqId, List.of()));
            row.put("attachments", attachmentsByFaq.getOrDefault(faqId, List.of()));
        }
        return rows;
    }

    private void replaceAttachments(Long faqId, Object rawAttachments) {
        jdbcTemplate.update("DELETE FROM faq_attachment WHERE faq_id=?", faqId);
        if (!(rawAttachments instanceof List<?> attachments)) return;
        int sort = 0;
        for (Object raw : attachments) {
            if (sort >= 20) break;
            if (!(raw instanceof Map<?, ?> item)) continue;
            String url = text(item.get("url"));
            String name = text(item.get("name"));
            String contentType = text(item.get("contentType"));
            long size = number(item.get("size"));
            if (url.isBlank() || name.isBlank() || !url.startsWith("/api/uploads/")) continue;
            jdbcTemplate.update("INSERT INTO faq_attachment(faq_id,file_url,original_name,content_type,file_size,sort_no) VALUES (?,?,?,?,?,?)",
                    faqId, url, name, contentType, size, sort++);
        }
    }

    private void replaceImages(Long faqId, Object rawImages) {
        jdbcTemplate.update("DELETE FROM faq_image WHERE faq_id=?", faqId);
        if (!(rawImages instanceof List<?> images)) return;
        int sort = 0;
        for (Object image : new ArrayList<>(images)) {
            String url = String.valueOf(image).trim();
            if (!url.isEmpty() && url.startsWith("/api/uploads/")) {
                jdbcTemplate.update("INSERT INTO faq_image(faq_id,image_url,sort_no) VALUES (?,?,?)", faqId, url, sort++);
            }
        }
    }

    private void validateLengths(String category, String question, String answer, Object keywords) {
        if (category.length() > 100) throw new IllegalArgumentException("分类不能超过 100 个字符");
        if (question.length() > 500) throw new IllegalArgumentException("问题标题不能超过 500 个字符");
        if (answer.length() > 200000) throw new IllegalArgumentException("解决方案内容过长，请将大段资料改为附件上传");
        if (text(keywords).length() > 1000) throw new IllegalArgumentException("搜索关键词不能超过 1000 个字符");
    }

    private String required(Map<String, Object> body, String key, String message) {
        String value = text(body.get(key));
        if (value.isBlank() || "null".equals(value)) throw new IllegalArgumentException(message);
        return value;
    }

    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private long number(Object value) {
        if (value instanceof Number n) return n.longValue();
        try { return Long.parseLong(text(value)); } catch (Exception ignored) { return 0L; }
    }
    private int asEnabled(Object value) {
        if (value == null) return 1;
        if (value instanceof Boolean b) return b ? 1 : 0;
        String text = String.valueOf(value);
        return ("1".equals(text) || "true".equalsIgnoreCase(text)) ? 1 : 0;
    }
}
