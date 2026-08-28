package com.myroboot.support.controller;

import com.myroboot.support.service.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class SupportController {
    private static final Logger log = LoggerFactory.getLogger(SupportController.class);

    private final JdbcTemplate jdbcTemplate;
    private final AuthService authService;

    public SupportController(JdbcTemplate jdbcTemplate, AuthService authService) {
        this.jdbcTemplate = jdbcTemplate;
        this.authService = authService;
    }

    @GetMapping("/faq")
    public List<Map<String, Object>> listFaq(@RequestHeader(value = "Authorization", required = false) String authorization) {
        requireUser(authorization);
        return enrichFaqResources(jdbcTemplate.queryForList(
                "SELECT id, category, question, answer, keywords FROM faq WHERE enabled = 1 ORDER BY id DESC"
        ));
    }

    @GetMapping("/faq/search")
    public List<Map<String, Object>> searchFaq(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(defaultValue = "") String q) {
        requireUser(authorization);
        if (q.isBlank()) return listFaq(authorization);
        return enrichFaqResources(searchFaqRows(q.trim(), 30));
    }

    @GetMapping("/faq/suggest")
    public List<Map<String, Object>> suggestFaq(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(defaultValue = "") String q) {
        requireUser(authorization);
        String keyword = q.trim();
        if (keyword.isEmpty()) return List.of();
        return searchFaqRows(keyword, 8).stream().map(row -> Map.<String, Object>of(
                "id", row.get("id"), "category", row.get("category"), "question", row.get("question")
        )).toList();
    }

    @GetMapping("/ticket/similar")
    public List<Map<String, Object>> similarBeforeTicket(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(defaultValue = "") String q) {
        requireUser(authorization);
        String keyword = q.trim();
        if (keyword.length() < 2) return List.of();
        return enrichFaqResources(searchFaqRows(keyword, 5));
    }

    @PostMapping("/ticket")
    @Transactional
    public Map<String, Object> createTicket(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> body) {
        AuthService.Session session = requireUser(authorization);
        String category = required(body, "category", "请选择或填写问题类型");
        String description = required(body, "description", "请填写问题描述后再提交");
        if (category.length() > 100) throw new IllegalArgumentException("问题类型不能超过 100 个字符");
        if (description.length() > 20000) throw new IllegalArgumentException("问题描述过长，请精简后再提交，详细内容可放在附件中");
        String customerName = valueOr(body.get("customerName"), session.companyName());
        String mineName = valueOr(body.get("mineName"), session.mineName());
        jdbcTemplate.update(
                "INSERT INTO support_ticket(user_id, customer_name, mine_name, category, description, screenshot_url) VALUES (?, ?, ?, ?, ?, ?)",
                session.userId(), customerName, mineName, category, description, body.get("screenshotUrl")
        );
        Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        if (id == null) throw new IllegalStateException("工单创建失败");
        saveTicketAttachments(id, body.get("attachments"));
        jdbcTemplate.update(
                "INSERT INTO ticket_history(ticket_id,operator_user_id,operator_name,action_type,content,visible_to_customer) VALUES (?,?,?,?,?,1)",
                id, session.userId(), operatorName(session), "created", "客户已提交技术支持工单"
        );
        log.info("TICKET_CREATED ticketId={} userId={} category={}", id, session.userId(), category);
        return Map.of("success", true, "ticketId", id);
    }

    @GetMapping("/tickets/mine")
    public List<Map<String, Object>> myTickets(@RequestHeader(value = "Authorization", required = false) String authorization) {
        AuthService.Session session = requireUser(authorization);
        return enrichTicketAttachments(jdbcTemplate.queryForList(
                "SELECT id, customer_name, mine_name, category, description, screenshot_url, status, resolution_reason, resolution_result, resolved_time, create_time " +
                        "FROM support_ticket WHERE user_id = ? ORDER BY id DESC LIMIT 100", session.userId()
        ));
    }

    @GetMapping("/admin/tickets")
    public List<Map<String, Object>> listTickets(@RequestHeader(value = "Authorization", required = false) String authorization) {
        requireAdmin(authorization);
        return enrichTicketAttachments(jdbcTemplate.queryForList(
                "SELECT t.id, t.user_id, t.customer_name, t.mine_name, t.category, t.description, t.screenshot_url, t.status, " +
                        "t.resolution_reason, t.resolution_result, t.resolved_time, t.create_time, u.username, u.display_name " +
                        "FROM support_ticket t LEFT JOIN support_user u ON u.id=t.user_id ORDER BY t.id DESC LIMIT 300"
        ));
    }

    @PutMapping("/admin/tickets/{id}/status")
    @Transactional
    public Map<String, Object> updateTicketStatus(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        AuthService.Session admin = requireAdmin(authorization);
        String status = String.valueOf(body.getOrDefault("status", "")).trim();
        if (!List.of("pending", "processing", "resolved").contains(status)) throw new IllegalArgumentException("不支持的工单状态");
        if ("resolved".equals(status)) {
            String reason = required(body, "resolutionReason", "标记已解决时必须填写具体原因");
            String result = required(body, "resolutionResult", "标记已解决时必须填写处理回执");
            int updated = jdbcTemplate.update(
                    "UPDATE support_ticket SET status='resolved', resolution_reason=?, resolution_result=?, resolved_time=NOW() WHERE id=?",
                    reason, result, id
            );
            if (updated > 0) {
                jdbcTemplate.update(
                        "INSERT INTO ticket_history(ticket_id,operator_user_id,operator_name,action_type,content,visible_to_customer) VALUES (?,?,?,?,?,1)",
                        id, admin.userId(), operatorName(admin), "resolved", "问题原因：" + reason + "\n处理回执：" + result
                );
                log.info("TICKET_RESOLVED ticketId={} operatorUserId={}", id, admin.userId());
            }
            return Map.of("success", updated > 0);
        }
        int updated = jdbcTemplate.update(
                "UPDATE support_ticket SET status=?, resolution_reason=NULL, resolution_result=NULL, resolved_time=NULL WHERE id=?",
                status, id
        );
        if (updated > 0) {
            String content = "processing".equals(status) ? "技术人员已开始处理" : "工单状态已调整为待处理";
            jdbcTemplate.update(
                    "INSERT INTO ticket_history(ticket_id,operator_user_id,operator_name,action_type,content,visible_to_customer) VALUES (?,?,?,?,?,1)",
                    id, admin.userId(), operatorName(admin), "progress", content
            );
            log.info("TICKET_STATUS_CHANGED ticketId={} status={} operatorUserId={}", id, status, admin.userId());
        }
        return Map.of("success", updated > 0);
    }

    @GetMapping("/admin/faqs")
    public List<Map<String, Object>> listAdminFaqs(@RequestHeader(value = "Authorization", required = false) String authorization) {
        requireAdmin(authorization);
        return enrichFaqResources(jdbcTemplate.queryForList(
                "SELECT id, category, question, answer, keywords, enabled, create_time, update_time FROM faq ORDER BY id DESC"
        ));
    }

    @PostMapping("/admin/faqs")
    @Transactional
    public Map<String, Object> createFaq(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> body) {
        AuthService.Session admin = requireAdmin(authorization);
        String category = required(body, "category", "分类不能为空");
        String question = required(body, "question", "问题标题不能为空");
        String answer = required(body, "answer", "解决方案不能为空");
        validateFaqLengths(category, question, answer, body.get("keywords"));
        jdbcTemplate.update("INSERT INTO faq(category, question, answer, keywords, enabled) VALUES (?, ?, ?, ?, ?)",
                category, question, answer, body.get("keywords"), asEnabled(body.get("enabled")));
        Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        if (id == null) throw new IllegalStateException("问题库保存失败");
        replaceFaqImages(id, body.get("images"));
        replaceFaqAttachments(id, body.get("attachments"));
        log.info("FAQ_CREATED faqId={} operatorUserId={}", id, admin.userId());
        return Map.of("success", true, "id", id);
    }

    @PutMapping("/admin/faqs/{id}")
    @Transactional
    public Map<String, Object> updateFaq(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        AuthService.Session admin = requireAdmin(authorization);
        String category = required(body, "category", "分类不能为空");
        String question = required(body, "question", "问题标题不能为空");
        String answer = required(body, "answer", "解决方案不能为空");
        validateFaqLengths(category, question, answer, body.get("keywords"));
        int updated = jdbcTemplate.update("UPDATE faq SET category=?, question=?, answer=?, keywords=?, enabled=? WHERE id=?",
                category, question, answer, body.get("keywords"), asEnabled(body.get("enabled")), id);
        if (updated == 0) throw new IllegalArgumentException("问题不存在或已被删除");
        replaceFaqImages(id, body.get("images"));
        replaceFaqAttachments(id, body.get("attachments"));
        log.info("FAQ_UPDATED faqId={} operatorUserId={}", id, admin.userId());
        return Map.of("success", true);
    }

    @DeleteMapping("/admin/faqs/{id}")
    @Transactional
    public Map<String, Object> deleteFaq(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id) {
        AuthService.Session admin = requireAdmin(authorization);
        jdbcTemplate.update("DELETE FROM faq_image WHERE faq_id=?", id);
        jdbcTemplate.update("DELETE FROM faq_attachment WHERE faq_id=?", id);
        int updated = jdbcTemplate.update("DELETE FROM faq WHERE id=?", id);
        if (updated > 0) log.info("FAQ_DELETED faqId={} operatorUserId={}", id, admin.userId());
        return Map.of("success", updated > 0);
    }

    private List<Map<String, Object>> searchFaqRows(String keyword, int limit) {
        String like = "%" + keyword + "%";
        try {
            return jdbcTemplate.queryForList(
                    "SELECT id,category,question,answer,keywords,MATCH(category,question,answer,keywords) AGAINST (? IN NATURAL LANGUAGE MODE) AS relevance " +
                            "FROM faq WHERE enabled=1 AND (MATCH(category,question,answer,keywords) AGAINST (? IN NATURAL LANGUAGE MODE) > 0 OR category LIKE ? OR question LIKE ? OR answer LIKE ? OR keywords LIKE ?) " +
                            "ORDER BY relevance DESC, CASE WHEN question LIKE ? THEN 0 ELSE 1 END, id DESC LIMIT ?",
                    keyword, keyword, like, like, like, like, like, limit);
        } catch (DataAccessException e) {
            log.debug("FAQ full-text query unavailable; using LIKE fallback: {}", e.getMostSpecificCause().getMessage());
            return jdbcTemplate.queryForList(
                    "SELECT id,category,question,answer,keywords FROM faq WHERE enabled=1 AND (category LIKE ? OR question LIKE ? OR answer LIKE ? OR keywords LIKE ?) ORDER BY CASE WHEN question LIKE ? THEN 0 ELSE 1 END,id DESC LIMIT ?",
                    like, like, like, like, like, limit);
        }
    }

    private List<Map<String, Object>> enrichFaqResources(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return rows;
        List<Long> ids = rows.stream().map(row -> ((Number) row.get("id")).longValue()).toList();
        String placeholders = placeholders(ids.size());
        Object[] args = ids.toArray();

        Map<Long, List<String>> imagesByFaq = new HashMap<>();
        List<Map<String, Object>> imageRows = jdbcTemplate.queryForList(
                "SELECT faq_id,image_url FROM faq_image WHERE faq_id IN (" + placeholders + ") ORDER BY faq_id,sort_no,id", args);
        for (Map<String, Object> image : imageRows) {
            Long faqId = ((Number) image.get("faq_id")).longValue();
            imagesByFaq.computeIfAbsent(faqId, ignored -> new ArrayList<>()).add(String.valueOf(image.get("image_url")));
        }

        Map<Long, List<Map<String, Object>>> attachmentsByFaq = new HashMap<>();
        List<Map<String, Object>> attachmentRows = jdbcTemplate.queryForList(
                "SELECT id,faq_id,file_url,original_name,content_type,file_size,create_time FROM faq_attachment WHERE faq_id IN (" + placeholders + ") ORDER BY faq_id,sort_no,id", args);
        for (Map<String, Object> attachment : attachmentRows) {
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

    private List<Map<String, Object>> enrichTicketAttachments(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return rows;
        List<Long> ids = rows.stream().map(row -> ((Number) row.get("id")).longValue()).toList();
        String placeholders = placeholders(ids.size());
        Object[] args = ids.toArray();
        Map<Long, List<Map<String, Object>>> attachmentsByTicket = new HashMap<>();
        List<Map<String, Object>> attachmentRows = jdbcTemplate.queryForList(
                "SELECT id,ticket_id,file_url,original_name,content_type,file_size,create_time FROM ticket_attachment WHERE ticket_id IN (" + placeholders + ") ORDER BY ticket_id,id", args);
        for (Map<String, Object> attachment : attachmentRows) {
            Long ticketId = ((Number) attachment.get("ticket_id")).longValue();
            attachmentsByTicket.computeIfAbsent(ticketId, ignored -> new ArrayList<>()).add(attachment);
        }
        for (Map<String, Object> row : rows) {
            Long ticketId = ((Number) row.get("id")).longValue();
            row.put("attachments", attachmentsByTicket.getOrDefault(ticketId, List.of()));
        }
        return rows;
    }

    private void saveTicketAttachments(Long ticketId, Object rawAttachments) {
        saveAttachmentRows("ticket_attachment", "ticket_id", ticketId, rawAttachments, 10, false);
    }

    private void replaceFaqAttachments(Long faqId, Object rawAttachments) {
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

    private void saveAttachmentRows(String table, String idColumn, Long id, Object rawAttachments, int limit, boolean replace) {
        if (replace) jdbcTemplate.update("DELETE FROM " + table + " WHERE " + idColumn + "=?", id);
        if (!(rawAttachments instanceof List<?> attachments)) return;
        int count = 0;
        for (Object raw : attachments) {
            if (count >= limit) break;
            if (!(raw instanceof Map<?, ?> item)) continue;
            String url = text(item.get("url"));
            String name = text(item.get("name"));
            String contentType = text(item.get("contentType"));
            long size = number(item.get("size"));
            if (url.isBlank() || name.isBlank() || !url.startsWith("/api/uploads/")) continue;
            jdbcTemplate.update("INSERT INTO " + table + "(" + idColumn + ",file_url,original_name,content_type,file_size) VALUES (?,?,?,?,?)",
                    id, url, name, contentType, size);
            count++;
        }
    }

    private void replaceFaqImages(Long faqId, Object rawImages) {
        jdbcTemplate.update("DELETE FROM faq_image WHERE faq_id=?", faqId);
        if (!(rawImages instanceof List<?> images)) return;
        int sort = 0;
        for (Object image : new ArrayList<>(images)) {
            String url = String.valueOf(image).trim();
            if (!url.isEmpty()) jdbcTemplate.update("INSERT INTO faq_image(faq_id,image_url,sort_no) VALUES (?,?,?)", faqId, url, sort++);
        }
    }

    private void validateFaqLengths(String category, String question, String answer, Object keywords) {
        if (category.length() > 100) throw new IllegalArgumentException("分类不能超过 100 个字符");
        if (question.length() > 500) throw new IllegalArgumentException("问题标题不能超过 500 个字符");
        if (answer.length() > 200000) throw new IllegalArgumentException("解决方案内容过长，请将大段资料改为附件上传");
        String keywordText = text(keywords);
        if (keywordText.length() > 1000) throw new IllegalArgumentException("搜索关键词不能超过 1000 个字符");
    }

    private String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }

    private String operatorName(AuthService.Session session) {
        return session.displayName() == null || session.displayName().isBlank() ? session.username() : session.displayName();
    }

    private AuthService.Session requireUser(String authorization) {
        try { return authService.require(authorization); }
        catch (SecurityException e) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, e.getMessage()); }
    }

    private AuthService.Session requireAdmin(String authorization) {
        try { return authService.requireAdmin(authorization); }
        catch (SecurityException e) { throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage()); }
    }

    private String required(Map<String, Object> body, String key, String message) {
        String value = String.valueOf(body.getOrDefault(key, "")).trim();
        if (value.isEmpty() || "null".equals(value)) throw new IllegalArgumentException(message);
        return value;
    }

    private String valueOr(Object value, String fallback) {
        String t = value == null ? "" : String.valueOf(value).trim();
        return t.isEmpty() ? fallback : t;
    }

    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }

    private long number(Object value) {
        if (value instanceof Number n) return n.longValue();
        try { return Long.parseLong(text(value)); } catch (Exception ignored) { return 0L; }
    }

    private int asEnabled(Object value) {
        if (value == null) return 1;
        if (value instanceof Boolean b) return b ? 1 : 0;
        String t = String.valueOf(value);
        return ("1".equals(t) || "true".equalsIgnoreCase(t)) ? 1 : 0;
    }
}
