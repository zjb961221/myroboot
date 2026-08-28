package com.myroboot.support.controller;

import com.myroboot.support.service.AuthService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class SupportController {

    private final JdbcTemplate jdbcTemplate;
    private final AuthService authService;

    public SupportController(JdbcTemplate jdbcTemplate, AuthService authService) {
        this.jdbcTemplate = jdbcTemplate;
        this.authService = authService;
    }

    @GetMapping("/faq")
    public List<Map<String, Object>> listFaq(@RequestHeader(value = "Authorization", required = false) String authorization) {
        requireUser(authorization);
        return enrichImages(jdbcTemplate.queryForList(
                "SELECT id, category, question, answer, keywords FROM faq WHERE enabled = 1 ORDER BY id DESC"
        ));
    }

    @GetMapping("/faq/search")
    public List<Map<String, Object>> searchFaq(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(defaultValue = "") String q) {
        requireUser(authorization);
        if (q.isBlank()) return listFaq(authorization);
        return enrichImages(searchFaqRows(q.trim(), 30));
    }

    @GetMapping("/faq/suggest")
    public List<Map<String, Object>> suggestFaq(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(defaultValue = "") String q) {
        requireUser(authorization);
        String keyword = q.trim();
        if (keyword.isEmpty()) return List.of();
        return searchFaqRows(keyword, 8).stream().map(row -> Map.<String, Object>of(
                "id", row.get("id"),
                "category", row.get("category"),
                "question", row.get("question")
        )).toList();
    }

    @GetMapping("/ticket/similar")
    public List<Map<String, Object>> similarBeforeTicket(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(defaultValue = "") String q) {
        requireUser(authorization);
        String keyword = q.trim();
        if (keyword.length() < 2) return List.of();
        return enrichImages(searchFaqRows(keyword, 5));
    }

    @PostMapping("/ticket")
    public Map<String, Object> createTicket(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> body) {
        AuthService.Session session = requireUser(authorization);
        String category = required(body, "category", "请选择或填写问题类型");
        String description = required(body, "description", "请填写问题描述后再提交");
        String customerName = valueOr(body.get("customerName"), session.companyName());
        String mineName = valueOr(body.get("mineName"), session.mineName());
        jdbcTemplate.update(
                "INSERT INTO support_ticket(user_id, customer_name, mine_name, category, description, screenshot_url) VALUES (?, ?, ?, ?, ?, ?)",
                session.userId(), customerName, mineName, category, description, body.get("screenshotUrl")
        );
        Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        saveTicketAttachments(id, body.get("attachments"));
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
    public Map<String, Object> updateTicketStatus(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        requireAdmin(authorization);
        String status = String.valueOf(body.getOrDefault("status", "")).trim();
        if (!List.of("pending", "processing", "resolved").contains(status)) throw new IllegalArgumentException("不支持的工单状态");
        if ("resolved".equals(status)) {
            String reason = required(body, "resolutionReason", "标记已解决时必须填写具体原因");
            String result = required(body, "resolutionResult", "标记已解决时必须填写处理回执");
            int updated = jdbcTemplate.update(
                    "UPDATE support_ticket SET status='resolved', resolution_reason=?, resolution_result=?, resolved_time=NOW() WHERE id=?",
                    reason, result, id
            );
            return Map.of("success", updated > 0);
        }
        int updated = jdbcTemplate.update("UPDATE support_ticket SET status=?, resolved_time=NULL WHERE id=?", status, id);
        return Map.of("success", updated > 0);
    }

    @GetMapping("/admin/faqs")
    public List<Map<String, Object>> listAdminFaqs(@RequestHeader(value = "Authorization", required = false) String authorization) {
        requireAdmin(authorization);
        return enrichImages(jdbcTemplate.queryForList(
                "SELECT id, category, question, answer, keywords, enabled, create_time, update_time FROM faq ORDER BY id DESC"
        ));
    }

    @PostMapping("/admin/faqs")
    public Map<String, Object> createFaq(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> body) {
        requireAdmin(authorization);
        String category = required(body, "category", "分类不能为空");
        String question = required(body, "question", "问题标题不能为空");
        String answer = required(body, "answer", "解决方案不能为空");
        jdbcTemplate.update(
                "INSERT INTO faq(category, question, answer, keywords, enabled) VALUES (?, ?, ?, ?, ?)",
                category, question, answer, body.get("keywords"), asEnabled(body.get("enabled"))
        );
        Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        replaceFaqImages(id, body.get("images"));
        return Map.of("success", true, "id", id);
    }

    @PutMapping("/admin/faqs/{id}")
    public Map<String, Object> updateFaq(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        requireAdmin(authorization);
        String category = required(body, "category", "分类不能为空");
        String question = required(body, "question", "问题标题不能为空");
        String answer = required(body, "answer", "解决方案不能为空");
        int updated = jdbcTemplate.update(
                "UPDATE faq SET category=?, question=?, answer=?, keywords=?, enabled=? WHERE id=?",
                category, question, answer, body.get("keywords"), asEnabled(body.get("enabled")), id
        );
        replaceFaqImages(id, body.get("images"));
        return Map.of("success", updated > 0);
    }

    @DeleteMapping("/admin/faqs/{id}")
    public Map<String, Object> deleteFaq(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id) {
        requireAdmin(authorization);
        jdbcTemplate.update("DELETE FROM faq_image WHERE faq_id=?", id);
        int updated = jdbcTemplate.update("DELETE FROM faq WHERE id=?", id);
        return Map.of("success", updated > 0);
    }

    private List<Map<String, Object>> searchFaqRows(String keyword, int limit) {
        String like = "%" + keyword + "%";
        try {
            return jdbcTemplate.queryForList(
                    "SELECT id,category,question,answer,keywords," +
                            "MATCH(category,question,answer,keywords) AGAINST (? IN NATURAL LANGUAGE MODE) AS relevance " +
                            "FROM faq WHERE enabled=1 AND (" +
                            "MATCH(category,question,answer,keywords) AGAINST (? IN NATURAL LANGUAGE MODE) > 0 " +
                            "OR category LIKE ? OR question LIKE ? OR answer LIKE ? OR keywords LIKE ?) " +
                            "ORDER BY relevance DESC, CASE WHEN question LIKE ? THEN 0 ELSE 1 END, id DESC LIMIT ?",
                    keyword, keyword, like, like, like, like, like, limit
            );
        } catch (Exception ignored) {
            return jdbcTemplate.queryForList(
                    "SELECT id,category,question,answer,keywords FROM faq WHERE enabled=1 " +
                            "AND (category LIKE ? OR question LIKE ? OR answer LIKE ? OR keywords LIKE ?) " +
                            "ORDER BY CASE WHEN question LIKE ? THEN 0 ELSE 1 END,id DESC LIMIT ?",
                    like, like, like, like, like, limit
            );
        }
    }

    private List<Map<String, Object>> enrichImages(List<Map<String, Object>> rows) {
        for (Map<String, Object> row : rows) {
            Long id = ((Number) row.get("id")).longValue();
            row.put("images", jdbcTemplate.queryForList(
                    "SELECT image_url FROM faq_image WHERE faq_id=? ORDER BY sort_no,id", String.class, id));
        }
        return rows;
    }

    private List<Map<String, Object>> enrichTicketAttachments(List<Map<String, Object>> rows) {
        for (Map<String, Object> row : rows) {
            Long ticketId = ((Number) row.get("id")).longValue();
            row.put("attachments", jdbcTemplate.queryForList(
                    "SELECT id,file_url,original_name,content_type,file_size,create_time FROM ticket_attachment WHERE ticket_id=? ORDER BY id",
                    ticketId
            ));
        }
        return rows;
    }

    private void saveTicketAttachments(Long ticketId, Object rawAttachments) {
        if (!(rawAttachments instanceof List<?> attachments)) return;
        int count = 0;
        for (Object raw : attachments) {
            if (count >= 10) break;
            if (!(raw instanceof Map<?, ?> item)) continue;
            String url = text(item.get("url"));
            String name = text(item.get("name"));
            String contentType = text(item.get("contentType"));
            long size = number(item.get("size"));
            if (url.isBlank() || name.isBlank() || !url.startsWith("/api/uploads/")) continue;
            jdbcTemplate.update(
                    "INSERT INTO ticket_attachment(ticket_id,file_url,original_name,content_type,file_size) VALUES (?,?,?,?,?)",
                    ticketId, url, name, contentType, size
            );
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

    private AuthService.Session requireUser(String authorization) {
        try {
            return authService.require(authorization);
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, e.getMessage());
        }
    }

    private void requireAdmin(String authorization) {
        try {
            authService.requireAdmin(authorization);
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        }
    }

    private String required(Map<String, Object> body, String key, String message) {
        String value = String.valueOf(body.getOrDefault(key, "")).trim();
        if (value.isEmpty() || "null".equals(value)) throw new IllegalArgumentException(message);
        return value;
    }

    private String valueOr(Object value, String fallback) {
        String text = value == null ? "" : String.valueOf(value).trim();
        return text.isEmpty() ? fallback : text;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

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
