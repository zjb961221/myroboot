package com.myroboot.support.controller;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class SupportController {

    private final JdbcTemplate jdbcTemplate;

    public SupportController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/faq")
    public List<Map<String, Object>> listFaq() {
        return jdbcTemplate.queryForList(
                "SELECT id, category, question, answer, keywords FROM faq WHERE enabled = 1 ORDER BY id DESC"
        );
    }

    @GetMapping("/faq/search")
    public List<Map<String, Object>> searchFaq(@RequestParam(defaultValue = "") String q) {
        if (q.isBlank()) {
            return listFaq();
        }
        String like = "%" + q.trim() + "%";
        return jdbcTemplate.queryForList(
                "SELECT id, category, question, answer, keywords FROM faq " +
                        "WHERE enabled = 1 AND (question LIKE ? OR answer LIKE ? OR keywords LIKE ?) " +
                        "ORDER BY id DESC LIMIT 20",
                like, like, like
        );
    }

    @PostMapping("/ticket")
    public Map<String, Object> createTicket(@RequestBody Map<String, Object> body) {
        String description = String.valueOf(body.getOrDefault("description", "")).trim();
        if (description.isEmpty()) {
            throw new IllegalArgumentException("问题描述不能为空");
        }

        jdbcTemplate.update(
                "INSERT INTO support_ticket(customer_name, mine_name, category, description, screenshot_url) VALUES (?, ?, ?, ?, ?)",
                body.get("customerName"),
                body.get("mineName"),
                body.get("category"),
                description,
                body.get("screenshotUrl")
        );
        Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return Map.of("success", true, "ticketId", id);
    }

    @GetMapping("/admin/tickets")
    public List<Map<String, Object>> listTickets() {
        return jdbcTemplate.queryForList(
                "SELECT id, customer_name, mine_name, category, description, screenshot_url, status, create_time " +
                        "FROM support_ticket ORDER BY id DESC LIMIT 200"
        );
    }

    @PutMapping("/admin/tickets/{id}/status")
    public Map<String, Object> updateTicketStatus(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String status = String.valueOf(body.getOrDefault("status", "")).trim();
        if (!List.of("pending", "processing", "resolved").contains(status)) {
            throw new IllegalArgumentException("不支持的工单状态");
        }
        int updated = jdbcTemplate.update("UPDATE support_ticket SET status = ? WHERE id = ?", status, id);
        return Map.of("success", updated > 0);
    }

    @GetMapping("/admin/faqs")
    public List<Map<String, Object>> listAdminFaqs() {
        return jdbcTemplate.queryForList(
                "SELECT id, category, question, answer, keywords, enabled, create_time, update_time FROM faq ORDER BY id DESC"
        );
    }

    @PostMapping("/admin/faqs")
    public Map<String, Object> createFaq(@RequestBody Map<String, Object> body) {
        String category = required(body, "category", "分类不能为空");
        String question = required(body, "question", "问题不能为空");
        String answer = required(body, "answer", "答案不能为空");
        jdbcTemplate.update(
                "INSERT INTO faq(category, question, answer, keywords, enabled) VALUES (?, ?, ?, ?, ?)",
                category, question, answer, body.get("keywords"), asEnabled(body.get("enabled"))
        );
        Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return Map.of("success", true, "id", id);
    }

    @PutMapping("/admin/faqs/{id}")
    public Map<String, Object> updateFaq(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String category = required(body, "category", "分类不能为空");
        String question = required(body, "question", "问题不能为空");
        String answer = required(body, "answer", "答案不能为空");
        int updated = jdbcTemplate.update(
                "UPDATE faq SET category = ?, question = ?, answer = ?, keywords = ?, enabled = ? WHERE id = ?",
                category, question, answer, body.get("keywords"), asEnabled(body.get("enabled")), id
        );
        return Map.of("success", updated > 0);
    }

    @DeleteMapping("/admin/faqs/{id}")
    public Map<String, Object> deleteFaq(@PathVariable Long id) {
        int updated = jdbcTemplate.update("DELETE FROM faq WHERE id = ?", id);
        return Map.of("success", updated > 0);
    }

    private String required(Map<String, Object> body, String key, String message) {
        String value = String.valueOf(body.getOrDefault(key, "")).trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private int asEnabled(Object value) {
        if (value == null) return 1;
        if (value instanceof Boolean b) return b ? 1 : 0;
        String text = String.valueOf(value);
        return ("1".equals(text) || "true".equalsIgnoreCase(text)) ? 1 : 0;
    }
}
