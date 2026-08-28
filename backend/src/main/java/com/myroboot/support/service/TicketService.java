package com.myroboot.support.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class TicketService {
    private static final Logger log = LoggerFactory.getLogger(TicketService.class);
    private final JdbcTemplate jdbcTemplate;

    public TicketService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public Long create(AuthService.Session session, Map<String, Object> body) {
        String category = required(body, "category", "请选择或填写问题类型");
        String description = required(body, "description", "请填写问题描述后再提交");
        if (category.length() > 100) throw new IllegalArgumentException("问题类型不能超过 100 个字符");
        if (description.length() > 20000) throw new IllegalArgumentException("问题描述过长，请精简后再提交，详细内容可放在附件中");
        String customerName = valueOr(body.get("customerName"), session.companyName());
        String mineName = valueOr(body.get("mineName"), session.mineName());
        jdbcTemplate.update(
                "INSERT INTO support_ticket(user_id,customer_name,mine_name,category,description,screenshot_url) VALUES (?,?,?,?,?,?)",
                session.userId(), customerName, mineName, category, description, body.get("screenshotUrl"));
        Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        if (id == null) throw new IllegalStateException("工单创建失败");
        saveAttachments(id, body.get("attachments"));
        jdbcTemplate.update(
                "INSERT INTO ticket_history(ticket_id,operator_user_id,operator_name,action_type,content,visible_to_customer) VALUES (?,?,?,?,?,1)",
                id, session.userId(), operatorName(session), "created", "客户已提交技术支持工单");
        log.info("TICKET_CREATED ticketId={} userId={} category={}", id, session.userId(), category);
        return id;
    }

    public List<Map<String, Object>> listMine(AuthService.Session session) {
        return enrichAttachments(jdbcTemplate.queryForList(
                "SELECT id,customer_name,mine_name,category,description,screenshot_url,status,resolution_reason,resolution_result,resolved_time,create_time " +
                        "FROM support_ticket WHERE user_id=? ORDER BY id DESC LIMIT 100", session.userId()));
    }

    public List<Map<String, Object>> listAdmin() {
        return enrichAttachments(jdbcTemplate.queryForList(
                "SELECT t.id,t.user_id,t.customer_name,t.mine_name,t.category,t.description,t.screenshot_url,t.status," +
                        "t.resolution_reason,t.resolution_result,t.resolved_time,t.create_time,u.username,u.display_name " +
                        "FROM support_ticket t LEFT JOIN support_user u ON u.id=t.user_id ORDER BY t.id DESC LIMIT 300"));
    }

    @Transactional
    public boolean updateStatus(AuthService.Session admin, Long id, Map<String, Object> body) {
        String status = text(body.get("status"));
        if (!List.of("pending", "processing", "resolved").contains(status)) {
            throw new IllegalArgumentException("不支持的工单状态");
        }
        if ("resolved".equals(status)) {
            String reason = required(body, "resolutionReason", "标记已解决时必须填写具体原因");
            String result = required(body, "resolutionResult", "标记已解决时必须填写处理回执");
            int updated = jdbcTemplate.update(
                    "UPDATE support_ticket SET status='resolved',resolution_reason=?,resolution_result=?,resolved_time=NOW() WHERE id=?",
                    reason, result, id);
            if (updated > 0) {
                jdbcTemplate.update(
                        "INSERT INTO ticket_history(ticket_id,operator_user_id,operator_name,action_type,content,visible_to_customer) VALUES (?,?,?,?,?,1)",
                        id, admin.userId(), operatorName(admin), "resolved", "问题原因：" + reason + "\n处理回执：" + result);
                log.info("TICKET_RESOLVED ticketId={} operatorUserId={}", id, admin.userId());
            }
            return updated > 0;
        }

        int updated = jdbcTemplate.update(
                "UPDATE support_ticket SET status=?,resolution_reason=NULL,resolution_result=NULL,resolved_time=NULL WHERE id=?",
                status, id);
        if (updated > 0) {
            String content = "processing".equals(status) ? "技术人员已开始处理" : "工单状态已调整为待处理";
            jdbcTemplate.update(
                    "INSERT INTO ticket_history(ticket_id,operator_user_id,operator_name,action_type,content,visible_to_customer) VALUES (?,?,?,?,?,1)",
                    id, admin.userId(), operatorName(admin), "progress", content);
            log.info("TICKET_STATUS_CHANGED ticketId={} status={} operatorUserId={}", id, status, admin.userId());
        }
        return updated > 0;
    }

    private List<Map<String, Object>> enrichAttachments(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return rows;
        List<Long> ids = rows.stream().map(row -> ((Number) row.get("id")).longValue()).toList();
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        Map<Long, List<Map<String, Object>>> byTicket = new HashMap<>();
        for (Map<String, Object> attachment : jdbcTemplate.queryForList(
                "SELECT id,ticket_id,file_url,original_name,content_type,file_size,create_time FROM ticket_attachment WHERE ticket_id IN (" + placeholders + ") ORDER BY ticket_id,id",
                ids.toArray())) {
            Long ticketId = ((Number) attachment.get("ticket_id")).longValue();
            byTicket.computeIfAbsent(ticketId, ignored -> new ArrayList<>()).add(attachment);
        }
        for (Map<String, Object> row : rows) {
            Long ticketId = ((Number) row.get("id")).longValue();
            row.put("attachments", byTicket.getOrDefault(ticketId, List.of()));
        }
        return rows;
    }

    private void saveAttachments(Long ticketId, Object rawAttachments) {
        if (!(rawAttachments instanceof List<?> attachments)) return;
        int count = 0;
        for (Object raw : attachments) {
            if (count >= 10) break;
            if (!(raw instanceof Map<?, ?> item)) continue;
            String url = text(item.get("url"));
            String name = text(item.get("name"));
            String type = text(item.get("contentType"));
            long size = number(item.get("size"));
            if (url.isBlank() || name.isBlank() || !url.startsWith("/api/uploads/")) continue;
            jdbcTemplate.update(
                    "INSERT INTO ticket_attachment(ticket_id,file_url,original_name,content_type,file_size) VALUES (?,?,?,?,?)",
                    ticketId, url, name, type, size);
            count++;
        }
    }

    private String required(Map<String, Object> body, String key, String message) {
        String value = text(body.get(key));
        if (value.isBlank() || "null".equals(value)) throw new IllegalArgumentException(message);
        return value;
    }
    private String valueOr(Object value, String fallback) {
        String text = text(value);
        return text.isBlank() ? fallback : text;
    }
    private String operatorName(AuthService.Session session) {
        return session.displayName() == null || session.displayName().isBlank() ? session.username() : session.displayName();
    }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private long number(Object value) {
        if (value instanceof Number n) return n.longValue();
        try { return Long.parseLong(text(value)); } catch (Exception ignored) { return 0L; }
    }
}
