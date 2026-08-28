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
                "SELECT * FROM support_ticket ORDER BY id DESC LIMIT 100"
        );
    }
}
