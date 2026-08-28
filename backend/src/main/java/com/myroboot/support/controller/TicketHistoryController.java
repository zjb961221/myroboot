package com.myroboot.support.controller;

import com.myroboot.support.service.AuthService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class TicketHistoryController {
    private final JdbcTemplate jdbcTemplate;
    private final AuthService authService;

    public TicketHistoryController(JdbcTemplate jdbcTemplate, AuthService authService) {
        this.jdbcTemplate = jdbcTemplate;
        this.authService = authService;
    }

    @GetMapping("/tickets/{id}/history")
    public List<Map<String,Object>> history(@RequestHeader(value="Authorization",required=false) String authorization,@PathVariable Long id) {
        AuthService.Session session = requireUser(authorization);
        if (!"admin".equals(session.role())) {
            Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM support_ticket WHERE id=? AND user_id=?",Integer.class,id,session.userId());
            if (count == null || count == 0) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"无权查看该工单");
            return jdbcTemplate.queryForList("SELECT id,action_type,content,operator_name,create_time FROM ticket_history WHERE ticket_id=? AND visible_to_customer=1 ORDER BY id",id);
        }
        return jdbcTemplate.queryForList("SELECT id,action_type,content,operator_name,visible_to_customer,create_time FROM ticket_history WHERE ticket_id=? ORDER BY id",id);
    }

    @PostMapping("/admin/tickets/{id}/history")
    public Map<String,Object> addHistory(@RequestHeader(value="Authorization",required=false) String authorization,@PathVariable Long id,@RequestBody Map<String,Object> body) {
        AuthService.Session admin = requireAdmin(authorization);
        String content = String.valueOf(body.getOrDefault("content","")).trim();
        if (content.isEmpty()) throw new IllegalArgumentException("处理记录不能为空");
        boolean visible = !body.containsKey("visibleToCustomer") || Boolean.parseBoolean(String.valueOf(body.get("visibleToCustomer")));
        jdbcTemplate.update("INSERT INTO ticket_history(ticket_id,operator_user_id,operator_name,action_type,content,visible_to_customer) VALUES (?,?,?,?,?,?)",
                id,admin.userId(),admin.displayName().isBlank()?admin.username():admin.displayName(),"progress",content,visible?1:0);
        jdbcTemplate.update("UPDATE support_ticket SET status=CASE WHEN status='pending' THEN 'processing' ELSE status END WHERE id=?",id);
        return Map.of("success",true);
    }

    @PostMapping("/admin/tickets/{id}/resolve")
    public Map<String,Object> resolve(@RequestHeader(value="Authorization",required=false) String authorization,@PathVariable Long id,@RequestBody Map<String,Object> body) {
        AuthService.Session admin = requireAdmin(authorization);
        String reason = required(body,"resolutionReason","具体原因不能为空");
        String result = required(body,"resolutionResult","处理结果不能为空");
        int updated = jdbcTemplate.update("UPDATE support_ticket SET status='resolved',resolution_reason=?,resolution_result=?,resolved_time=NOW() WHERE id=?",reason,result,id);
        if (updated > 0) {
            String content = "问题原因：" + reason + "\n处理回执：" + result;
            jdbcTemplate.update("INSERT INTO ticket_history(ticket_id,operator_user_id,operator_name,action_type,content,visible_to_customer) VALUES (?,?,?,?,?,1)",
                    id,admin.userId(),admin.displayName().isBlank()?admin.username():admin.displayName(),"resolved",content);
        }
        return Map.of("success",updated>0);
    }

    private String required(Map<String,Object> body,String key,String msg){ String v=String.valueOf(body.getOrDefault(key,"")).trim(); if(v.isEmpty()) throw new IllegalArgumentException(msg); return v; }
    private AuthService.Session requireUser(String authorization){ try{return authService.require(authorization);}catch(SecurityException e){throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,e.getMessage());} }
    private AuthService.Session requireAdmin(String authorization){ try{return authService.requireAdmin(authorization);}catch(SecurityException e){throw new ResponseStatusException(HttpStatus.FORBIDDEN,e.getMessage());} }
}
