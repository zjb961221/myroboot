package com.myroboot.support.controller;

import com.myroboot.support.service.AuthService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class TicketMetadataController {
    private final AuthService authService;
    private final JdbcTemplate jdbcTemplate;

    public TicketMetadataController(AuthService authService, JdbcTemplate jdbcTemplate) {
        this.authService = authService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/tickets/{ticketId}/metadata")
    public Map<String, Object> metadata(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long ticketId) {
        AuthService.Session session = requireUser(authorization);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT t.id,t.user_id,t.assigned_to,t.customer_name,t.mine_name,t.status,t.create_time," +
                        "COALESCE(NULLIF(customer.display_name,''),customer.username) AS submitter_name," +
                        "COALESCE(NULLIF(processor.display_name,''),processor.username) AS processor_name " +
                        "FROM support_ticket t " +
                        "LEFT JOIN support_user customer ON customer.id=t.user_id " +
                        "LEFT JOIN support_user processor ON processor.id=t.assigned_to " +
                        "WHERE t.id=? AND t.is_deleted=0 LIMIT 1",
                ticketId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "工单不存在");

        Map<String, Object> row = rows.get(0);
        long ownerId = number(row.get("user_id"));
        Long assignedTo = nullableNumber(row.get("assigned_to"));
        boolean allowed = "admin".equals(session.role())
                || ("customer".equals(session.role()) && ownerId == session.userId())
                || ("processor".equals(session.role()) && assignedTo != null && assignedTo.equals(session.userId()));
        if (!allowed) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权查看该工单");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", row.get("id"));
        result.put("customer_name", row.get("customer_name"));
        result.put("mine_name", row.get("mine_name"));
        result.put("submitter_name", fallback(row.get("submitter_name"), "未知提交人"));
        result.put("processor_name", fallback(row.get("processor_name"), "暂未分配"));
        result.put("status", row.get("status"));
        result.put("create_time", row.get("create_time"));
        return result;
    }

    private AuthService.Session requireUser(String authorization) {
        try {
            return authService.require(authorization);
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, e.getMessage());
        }
    }

    private long number(Object value) {
        return value instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(value));
    }

    private Long nullableNumber(Object value) {
        if (value == null) return null;
        return number(value);
    }

    private String fallback(Object value, String fallback) {
        if (value == null) return fallback;
        String text = String.valueOf(value).trim();
        return text.isEmpty() || "null".equalsIgnoreCase(text) ? fallback : text;
    }
}
