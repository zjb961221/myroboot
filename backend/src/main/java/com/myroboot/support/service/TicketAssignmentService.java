package com.myroboot.support.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class TicketAssignmentService {
    private static final Logger log = LoggerFactory.getLogger(TicketAssignmentService.class);

    private final JdbcTemplate jdbcTemplate;
    private final JavaMailSender mailSender;

    @Value("${support.mail.from:}") private String from;
    @Value("${spring.mail.username:}") private String mailUsername;
    @Value("${support.public-base-url:}") private String publicBaseUrl;

    public TicketAssignmentService(JdbcTemplate jdbcTemplate, JavaMailSender mailSender) {
        this.jdbcTemplate = jdbcTemplate;
        this.mailSender = mailSender;
    }

    public List<Map<String, Object>> listProcessors() {
        return jdbcTemplate.queryForList(
                "SELECT id,username,email,display_name,phone,enabled FROM support_user WHERE role='processor' AND enabled=1 ORDER BY display_name,username");
    }

    @Transactional
    public Map<String, Object> assign(AuthService.Session admin, Long ticketId, Long processorUserId) {
        List<Map<String, Object>> tickets = jdbcTemplate.queryForList(
                "SELECT id,category,description,status,is_deleted FROM support_ticket WHERE id=? FOR UPDATE", ticketId);
        if (tickets.isEmpty() || asBoolean(tickets.get(0).get("is_deleted"))) {
            throw new IllegalArgumentException("工单不存在或已删除");
        }
        String status = text(tickets.get(0).get("status"));
        if ("cancelled".equals(status) || "resolved".equals(status)) {
            throw new IllegalArgumentException("已撤销或已解决的工单不能重新分配");
        }

        List<Map<String, Object>> users = jdbcTemplate.queryForList(
                "SELECT id,username,email,display_name,role,enabled FROM support_user WHERE id=? LIMIT 1", processorUserId);
        if (users.isEmpty()) throw new IllegalArgumentException("处理人员不存在");
        Map<String, Object> processor = users.get(0);
        if (!"processor".equals(text(processor.get("role"))) || !asBoolean(processor.get("enabled"))) {
            throw new IllegalArgumentException("只能分配给已启用的处理人员账号");
        }
        String email = text(processor.get("email"));
        if (email.isBlank()) throw new IllegalArgumentException("该处理人员未配置邮箱，无法分配工单");

        jdbcTemplate.update(
                "UPDATE support_ticket SET assigned_to=?,assigned_by=?,assigned_time=NOW(),status=CASE WHEN status='pending' THEN 'processing' ELSE status END WHERE id=? AND is_deleted=0",
                processorUserId, admin.userId(), ticketId);
        String displayName = text(processor.get("display_name"));
        if (displayName.isBlank()) displayName = text(processor.get("username"));
        jdbcTemplate.update(
                "INSERT INTO ticket_history(ticket_id,operator_user_id,operator_name,action_type,content,visible_to_customer) VALUES (?,?,?,?,?,1)",
                ticketId, admin.userId(), operatorName(admin), "assigned", "工单已分配给处理人员：" + displayName);
        log.info("TICKET_ASSIGNED ticketId={} processorUserId={} operatorUserId={}", ticketId, processorUserId, admin.userId());

        boolean mailSent = sendAssignmentMail(email, displayName, ticketId, text(tickets.get(0).get("category")), text(tickets.get(0).get("description")));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("mailSent", mailSent);
        result.put("processorUserId", processorUserId);
        result.put("processorName", displayName);
        return result;
    }

    private boolean sendAssignmentMail(String email, String displayName, Long ticketId, String category, String description) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            String sender = from == null || from.isBlank() ? mailUsername : from;
            if (sender != null && !sender.isBlank()) message.setFrom(sender);
            message.setTo(email);
            message.setSubject("[MYROBOOT] 新工单待处理 #" + ticketId);
            StringBuilder text = new StringBuilder();
            text.append(displayName).append("，您好：\n\n")
                    .append("管理员已将工单 #").append(ticketId).append(" 分配给您处理。\n")
                    .append("问题类型：").append(category).append("\n")
                    .append("问题描述：").append(shorten(description, 1000)).append("\n");
            if (publicBaseUrl != null && !publicBaseUrl.isBlank()) {
                text.append("处理地址：").append(publicBaseUrl.replaceAll("/$", ""))
                        .append("/processor/ticket-detail?id=").append(ticketId).append("\n");
            }
            text.append("\n请登录 MYROBOOT 技术支持平台及时处理。");
            message.setText(text.toString());
            mailSender.send(message);
            log.info("TICKET_ASSIGNMENT_MAIL_SENT ticketId={} emailDomain={}", ticketId, emailDomain(email));
            return true;
        } catch (Exception e) {
            log.warn("TICKET_ASSIGNMENT_MAIL_FAILED ticketId={} emailDomain={} reason={}", ticketId, emailDomain(email), rootMessage(e));
            return false;
        }
    }

    private String shorten(String value, int max) {
        if (value == null) return "";
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "…";
    }

    private String operatorName(AuthService.Session session) {
        return session.displayName() == null || session.displayName().isBlank() ? session.username() : session.displayName();
    }

    private boolean asBoolean(Object value) {
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.intValue() != 0;
        return "1".equals(String.valueOf(value)) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private String emailDomain(String email) { int at = email == null ? -1 : email.indexOf('@'); return at >= 0 ? email.substring(at + 1) : "unknown"; }
    private String rootMessage(Throwable error) { Throwable current = error; while (current.getCause() != null) current = current.getCause(); return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage(); }
}
