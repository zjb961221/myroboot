package com.myroboot.support.controller;

import com.myroboot.support.service.AuthService;
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
public class TicketHistoryController {
    private static final Logger log = LoggerFactory.getLogger(TicketHistoryController.class);

    private final JdbcTemplate jdbcTemplate;
    private final AuthService authService;

    public TicketHistoryController(JdbcTemplate jdbcTemplate, AuthService authService) {
        this.jdbcTemplate = jdbcTemplate;
        this.authService = authService;
    }

    @GetMapping("/tickets/{id}/history")
    public List<Map<String,Object>> history(@RequestHeader(value="Authorization",required=false) String authorization,@PathVariable Long id) {
        AuthService.Session session = requireUser(authorization);
        List<Map<String,Object>> rows;
        if (!"admin".equals(session.role())) {
            Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM support_ticket WHERE id=? AND user_id=?",Integer.class,id,session.userId());
            if (count == null || count == 0) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"无权查看该工单");
            rows = jdbcTemplate.queryForList("SELECT id,action_type,content,operator_name,create_time FROM ticket_history WHERE ticket_id=? AND visible_to_customer=1 ORDER BY id",id);
        } else {
            rows = jdbcTemplate.queryForList("SELECT id,action_type,content,operator_name,visible_to_customer,create_time FROM ticket_history WHERE ticket_id=? ORDER BY id",id);
        }
        enrichHistoryAttachments(rows);
        return rows;
    }

    @PostMapping("/admin/tickets/{id}/history")
    @Transactional
    public Map<String,Object> addHistory(@RequestHeader(value="Authorization",required=false) String authorization,@PathVariable Long id,@RequestBody Map<String,Object> body) {
        AuthService.Session admin = requireAdmin(authorization);
        ensureTicketExists(id);
        String content = required(body,"content","处理记录不能为空");
        if (content.length() > 20000) throw new IllegalArgumentException("处理记录过长，请将详细资料改为附件上传");
        boolean visible = !body.containsKey("visibleToCustomer") || Boolean.parseBoolean(String.valueOf(body.get("visibleToCustomer")));
        jdbcTemplate.update("INSERT INTO ticket_history(ticket_id,operator_user_id,operator_name,action_type,content,visible_to_customer) VALUES (?,?,?,?,?,?)",
                id,admin.userId(),operatorName(admin),"progress",content,visible?1:0);
        Long historyId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        if (historyId == null) throw new IllegalStateException("处理记录保存失败");
        saveAttachments(historyId, id, body.get("attachments"));
        jdbcTemplate.update("UPDATE support_ticket SET status=CASE WHEN status='pending' THEN 'processing' ELSE status END WHERE id=?",id);
        log.info("TICKET_PROGRESS_ADDED ticketId={} historyId={} operatorUserId={} visible={}", id, historyId, admin.userId(), visible);
        return Map.of("success",true,"historyId",historyId);
    }

    @PostMapping("/admin/tickets/{id}/resolve")
    @Transactional
    public Map<String,Object> resolve(@RequestHeader(value="Authorization",required=false) String authorization,@PathVariable Long id,@RequestBody Map<String,Object> body) {
        AuthService.Session admin = requireAdmin(authorization);
        ensureTicketExists(id);
        String reason = required(body,"resolutionReason","具体原因不能为空");
        String result = required(body,"resolutionResult","处理结果不能为空");
        if (reason.length() > 20000 || result.length() > 100000) throw new IllegalArgumentException("回执内容过长，请将详细资料改为附件上传");
        int updated = jdbcTemplate.update("UPDATE support_ticket SET status='resolved',resolution_reason=?,resolution_result=?,resolved_time=NOW() WHERE id=?",reason,result,id);
        if (updated > 0) {
            String content = "问题原因：" + reason + "\n处理回执：" + result;
            jdbcTemplate.update("INSERT INTO ticket_history(ticket_id,operator_user_id,operator_name,action_type,content,visible_to_customer) VALUES (?,?,?,?,?,1)",
                    id,admin.userId(),operatorName(admin),"resolved",content);
            Long historyId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            if (historyId == null) throw new IllegalStateException("解决回执保存失败");
            saveAttachments(historyId, id, body.get("attachments"));
            log.info("TICKET_RESOLVED ticketId={} historyId={} operatorUserId={}", id, historyId, admin.userId());
            return Map.of("success",true,"historyId",historyId);
        }
        return Map.of("success",false);
    }

    private void enrichHistoryAttachments(List<Map<String,Object>> rows) {
        if (rows.isEmpty()) return;
        List<Long> ids = rows.stream().map(row -> ((Number) row.get("id")).longValue()).toList();
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        Map<Long, List<Map<String,Object>>> byHistory = new HashMap<>();
        List<Map<String,Object>> attachments = jdbcTemplate.queryForList(
                "SELECT id,history_id,file_url,original_name,content_type,file_size,create_time FROM ticket_history_attachment WHERE history_id IN (" + placeholders + ") ORDER BY history_id,id",
                ids.toArray());
        for (Map<String,Object> attachment : attachments) {
            Long historyId = ((Number) attachment.get("history_id")).longValue();
            byHistory.computeIfAbsent(historyId, ignored -> new ArrayList<>()).add(attachment);
        }
        for (Map<String,Object> row : rows) {
            Long historyId = ((Number) row.get("id")).longValue();
            row.put("attachments", byHistory.getOrDefault(historyId, List.of()));
        }
    }

    private void saveAttachments(Long historyId, Long ticketId, Object rawAttachments) {
        if (!(rawAttachments instanceof List<?> attachments)) return;
        int count = 0;
        for (Object raw : attachments) {
            if (count >= 10) break;
            if (!(raw instanceof Map<?,?> item)) continue;
            String url = text(item.get("url"));
            String name = text(item.get("name"));
            String type = text(item.get("contentType"));
            long size = number(item.get("size"));
            if (url.isBlank() || name.isBlank() || !url.startsWith("/api/uploads/")) continue;
            jdbcTemplate.update("INSERT INTO ticket_history_attachment(history_id,ticket_id,file_url,original_name,content_type,file_size) VALUES (?,?,?,?,?,?)",
                    historyId,ticketId,url,name,type,size);
            count++;
        }
    }

    private void ensureTicketExists(Long id) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM support_ticket WHERE id=?", Integer.class, id);
        if (count == null || count == 0) throw new IllegalArgumentException("工单不存在或已被删除");
    }

    private String operatorName(AuthService.Session session) {
        return session.displayName() == null || session.displayName().isBlank() ? session.username() : session.displayName();
    }

    private String required(Map<String,Object> body,String key,String msg){ String v=String.valueOf(body.getOrDefault(key,"")).trim(); if(v.isEmpty()) throw new IllegalArgumentException(msg); return v; }
    private String text(Object value){ return value==null?"":String.valueOf(value).trim(); }
    private long number(Object value){ if(value instanceof Number n)return n.longValue(); try{return Long.parseLong(text(value));}catch(Exception e){return 0L;} }
    private AuthService.Session requireUser(String authorization){ try{return authService.require(authorization);}catch(SecurityException e){throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,e.getMessage());} }
    private AuthService.Session requireAdmin(String authorization){ try{return authService.requireAdmin(authorization);}catch(SecurityException e){throw new ResponseStatusException(HttpStatus.FORBIDDEN,e.getMessage());} }
}
