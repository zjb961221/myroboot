package com.myroboot.support.controller;

import com.myroboot.support.service.AuthService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
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
        if (q.isBlank()) {
            return listFaq(authorization);
        }
        String like = "%" + q.trim() + "%";
        return enrichImages(jdbcTemplate.queryForList(
                "SELECT id, category, question, answer, keywords FROM faq " +
                        "WHERE enabled = 1 AND (question LIKE ? OR answer LIKE ? OR keywords LIKE ?) " +
                        "ORDER BY id DESC LIMIT 20",
                like, like, like
        ));
    }

    @PostMapping("/ticket")
    public Map<String, Object> createTicket(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> body) {
        requireUser(authorization);
        String description = String.valueOf(body.getOrDefault("description", "")).trim();
        if (description.isEmpty()) {
            throw new IllegalArgumentException("问题描述不能为空");
        }

        jdbcTemplate.update(
                "INSERT INTO support_ticket(customer_name, mine_name, category, description, screenshot_url) VALUES (?, ?, ?, ?, ?)",
                body.get("customerName"), body.get("mineName"), body.get("category"), description, body.get("screenshotUrl")
        );
        Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return Map.of("success", true, "ticketId", id);
    }

    @GetMapping("/admin/tickets")
    public List<Map<String, Object>> listTickets(@RequestHeader(value = "Authorization", required = false) String authorization) {
        requireAdmin(authorization);
        return jdbcTemplate.queryForList(
                "SELECT id, customer_name, mine_name, category, description, screenshot_url, status, create_time " +
                        "FROM support_ticket ORDER BY id DESC LIMIT 200"
        );
    }

    @PutMapping("/admin/tickets/{id}/status")
    public Map<String, Object> updateTicketStatus(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        requireAdmin(authorization);
        String status = String.valueOf(body.getOrDefault("status", "")).trim();
        if (!List.of("pending", "processing", "resolved").contains(status)) {
            throw new IllegalArgumentException("不支持的工单状态");
        }
        int updated = jdbcTemplate.update("UPDATE support_ticket SET status = ? WHERE id = ?", status, id);
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
        String question = required(body, "question", "问题不能为空");
        String answer = required(body, "answer", "答案不能为空");
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
        String question = required(body, "question", "问题不能为空");
        String answer = required(body, "answer", "答案不能为空");
        int updated = jdbcTemplate.update(
                "UPDATE faq SET category = ?, question = ?, answer = ?, keywords = ?, enabled = ? WHERE id = ?",
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
        jdbcTemplate.update("DELETE FROM faq_image WHERE faq_id = ?", id);
        int updated = jdbcTemplate.update("DELETE FROM faq WHERE id = ?", id);
        return Map.of("success", updated > 0);
    }

    private List<Map<String, Object>> enrichImages(List<Map<String, Object>> rows) {
        for (Map<String, Object> row : rows) {
            Long id = ((Number) row.get("id")).longValue();
            List<String> images = jdbcTemplate.queryForList(
                    "SELECT image_url FROM faq_image WHERE faq_id = ? ORDER BY sort_no, id", String.class, id
            );
            row.put("images", images);
        }
        return rows;
    }

    private void replaceFaqImages(Long faqId, Object rawImages) {
        jdbcTemplate.update("DELETE FROM faq_image WHERE faq_id = ?", faqId);
        if (!(rawImages instanceof List<?> images)) return;
        int sort = 0;
        for (Object image : new ArrayList<>(images)) {
            String url = String.valueOf(image).trim();
            if (!url.isEmpty()) {
                jdbcTemplate.update("INSERT INTO faq_image(faq_id, image_url, sort_no) VALUES (?, ?, ?)", faqId, url, sort++);
            }
        }
    }

    private void requireUser(String authorization) {
        try {
            authService.require(authorization);
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
        if (value.isEmpty()) throw new IllegalArgumentException(message);
        return value;
    }

    private int asEnabled(Object value) {
        if (value == null) return 1;
        if (value instanceof Boolean b) return b ? 1 : 0;
        String text = String.valueOf(value);
        return ("1".equals(text) || "true".equalsIgnoreCase(text)) ? 1 : 0;
    }
}
