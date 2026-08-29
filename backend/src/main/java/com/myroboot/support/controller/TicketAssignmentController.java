package com.myroboot.support.controller;

import com.myroboot.support.service.AuthService;
import com.myroboot.support.service.TicketAssignmentService;
import com.myroboot.support.service.TicketNotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
public class TicketAssignmentController {
    private static final Logger log = LoggerFactory.getLogger(TicketAssignmentController.class);

    private final AuthService authService;
    private final TicketAssignmentService assignmentService;
    private final TicketNotificationService notificationService;
    private final JdbcTemplate jdbcTemplate;

    public TicketAssignmentController(AuthService authService,
                                      TicketAssignmentService assignmentService,
                                      TicketNotificationService notificationService,
                                      JdbcTemplate jdbcTemplate) {
        this.authService = authService;
        this.assignmentService = assignmentService;
        this.notificationService = notificationService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/admin/processors")
    public List<Map<String, Object>> processors(@RequestHeader(value = "Authorization", required = false) String authorization) {
        requireAdmin(authorization);
        return assignmentService.listProcessors();
    }

    @PutMapping("/admin/tickets/{ticketId}/assignment")
    public Map<String, Object> assign(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @PathVariable Long ticketId,
                                      @RequestBody Map<String, Object> body) {
        AuthService.Session admin = requireAdmin(authorization);
        Long processorUserId = longValue(body.get("processorUserId"));
        if (processorUserId == null) throw new IllegalArgumentException("请选择处理人员");
        return assignmentService.assign(admin, ticketId, processorUserId);
    }

    @GetMapping("/processor/tickets")
    public List<Map<String, Object>> myAssignedTickets(@RequestHeader(value = "Authorization", required = false) String authorization) {
        AuthService.Session processor = requireProcessor(authorization);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT t.id,t.customer_name,t.mine_name,t.category,t.description,t.screenshot_url,t.status,t.resolution_reason,t.resolution_result,t.resolved_time," +
                        "t.cancel_reason,t.cancelled_time,t.assigned_time,t.create_time,u.display_name AS assigned_name " +
                        "FROM support_ticket t LEFT JOIN support_user u ON u.id=t.assigned_to " +
                        "WHERE t.assigned_to=? AND t.is_deleted=0 ORDER BY CASE WHEN t.status='processing' THEN 0 WHEN t.status='pending' THEN 1 ELSE 2 END,t.id DESC",
                processor.userId());
        enrichTicketAttachments(rows);
        return rows;
    }

    @GetMapping("/processor/tickets/{id}/history")
    public List<Map<String, Object>> history(@RequestHeader(value = "Authorization", required = false) String authorization,
                                             @PathVariable Long id) {
        AuthService.Session processor = requireProcessor(authorization);
        ensureAssigned(processor, id, false);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id,action_type,content,operator_name,visible_to_customer,create_time FROM ticket_history WHERE ticket_id=? ORDER BY id", id);
        enrichHistoryAttachments(rows);
        return rows;
    }

    @PostMapping("/processor/tickets/{id}/history")
    @Transactional
    public Map<String, Object> addHistory(@RequestHeader(value = "Authorization", required = false) String authorization,
                                          @PathVariable Long id,
                                          @RequestBody Map<String, Object> body) {
        AuthService.Session processor = requireProcessor(authorization);
        ensureAssigned(processor, id, true);
        String content = required(body, "content", "处理记录不能为空");
        if (content.length() > 20000) throw new IllegalArgumentException("处理记录过长，请将详细资料改为附件上传");
        boolean visible = !body.containsKey("visibleToCustomer") || Boolean.parseBoolean(String.valueOf(body.get("visibleToCustomer")));
        jdbcTemplate.update("INSERT INTO ticket_history(ticket_id,operator_user_id,operator_name,action_type,content,visible_to_customer) VALUES (?,?,?,?,?,?)",
                id, processor.userId(), operatorName(processor), "progress", content, visible ? 1 : 0);
        Long historyId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        if (historyId == null) throw new IllegalStateException("处理记录保存失败");
        saveHistoryAttachments(historyId, id, body.get("attachments"));
        jdbcTemplate.update("UPDATE support_ticket SET status=CASE WHEN status='pending' THEN 'processing' ELSE status END WHERE id=? AND assigned_to=? AND is_deleted=0",
                id, processor.userId());
        log.info("PROCESSOR_TICKET_PROGRESS ticketId={} processorUserId={} historyId={}", id, processor.userId(), historyId);
        return Map.of("success", true, "historyId", historyId);
    }

    @PostMapping("/processor/tickets/{id}/resolve")
    @Transactional
    public Map<String, Object> resolve(@RequestHeader(value = "Authorization", required = false) String authorization,
                                       @PathVariable Long id,
                                       @RequestBody Map<String, Object> body) {
        AuthService.Session processor = requireProcessor(authorization);
        ensureAssigned(processor, id, true);
        String reason = required(body, "resolutionReason", "具体原因不能为空");
        String result = required(body, "resolutionResult", "处理结果不能为空");
        if (reason.length() > 20000 || result.length() > 100000) throw new IllegalArgumentException("回执内容过长，请将详细资料改为附件上传");
        int updated = jdbcTemplate.update(
                "UPDATE support_ticket SET status='resolved',resolution_reason=?,resolution_result=?,resolved_time=NOW() WHERE id=? AND assigned_to=? AND is_deleted=0",
                reason, result, id, processor.userId());
        if (updated == 0) throw new IllegalArgumentException("工单不存在、已失效或已被重新分配");
        String content = "问题原因：" + reason + "\n处理回执：" + result;
        jdbcTemplate.update("INSERT INTO ticket_history(ticket_id,operator_user_id,operator_name,action_type,content,visible_to_customer) VALUES (?,?,?,?,?,1)",
                id, processor.userId(), operatorName(processor), "resolved", content);
        Long historyId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        if (historyId == null) throw new IllegalStateException("解决回执保存失败");
        saveHistoryAttachments(historyId, id, body.get("attachments"));
        notificationService.notifyResolvedAfterCommit(id);
        log.info("PROCESSOR_TICKET_RESOLVED ticketId={} processorUserId={} historyId={}", id, processor.userId(), historyId);
        return Map.of("success", true, "historyId", historyId, "customerNotificationScheduled", true);
    }

    private void ensureAssigned(AuthService.Session processor, Long ticketId, boolean processable) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT status,is_deleted FROM support_ticket WHERE id=? AND assigned_to=?", ticketId, processor.userId());
        if (rows.isEmpty() || ((Number) rows.get(0).get("is_deleted")).intValue() == 1) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "该工单未分配给您或已被删除");
        }
        if (processable) {
            String status = text(rows.get(0).get("status"));
            if ("cancelled".equals(status)) throw new IllegalArgumentException("已撤销工单不能继续处理");
            if ("resolved".equals(status)) throw new IllegalArgumentException("已解决工单不能继续修改");
        }
    }

    private void enrichTicketAttachments(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return;
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
    }

    private void enrichHistoryAttachments(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return;
        List<Long> ids = rows.stream().map(row -> ((Number) row.get("id")).longValue()).toList();
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        Map<Long, List<Map<String, Object>>> byHistory = new HashMap<>();
        for (Map<String, Object> attachment : jdbcTemplate.queryForList(
                "SELECT id,history_id,file_url,original_name,content_type,file_size,create_time FROM ticket_history_attachment WHERE history_id IN (" + placeholders + ") ORDER BY history_id,id",
                ids.toArray())) {
            Long historyId = ((Number) attachment.get("history_id")).longValue();
            byHistory.computeIfAbsent(historyId, ignored -> new ArrayList<>()).add(attachment);
        }
        for (Map<String, Object> row : rows) {
            Long historyId = ((Number) row.get("id")).longValue();
            row.put("attachments", byHistory.getOrDefault(historyId, List.of()));
        }
    }

    private void saveHistoryAttachments(Long historyId, Long ticketId, Object rawAttachments) {
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
            jdbcTemplate.update("INSERT INTO ticket_history_attachment(history_id,ticket_id,file_url,original_name,content_type,file_size) VALUES (?,?,?,?,?,?)",
                    historyId, ticketId, url, name, type, size);
            count++;
        }
    }

    private AuthService.Session requireAdmin(String authorization) {
        try { return authService.requireAdmin(authorization); }
        catch (SecurityException e) { throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage()); }
    }

    private AuthService.Session requireProcessor(String authorization) {
        AuthService.Session session;
        try { session = authService.require(authorization); }
        catch (SecurityException e) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, e.getMessage()); }
        if (!"processor".equals(session.role())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前账号不是处理人员");
        return session;
    }

    private Long longValue(Object value) { if (value instanceof Number n) return n.longValue(); try { return Long.parseLong(text(value)); } catch (Exception e) { return null; } }
    private String required(Map<String, Object> body, String key, String message) { String value = text(body.get(key)); if (value.isBlank()) throw new IllegalArgumentException(message); return value; }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private long number(Object value) { if (value instanceof Number n) return n.longValue(); try { return Long.parseLong(text(value)); } catch (Exception e) { return 0L; } }
    private String operatorName(AuthService.Session session) { return session.displayName() == null || session.displayName().isBlank() ? session.username() : session.displayName(); }
}
