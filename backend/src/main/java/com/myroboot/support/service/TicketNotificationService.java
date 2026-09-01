package com.myroboot.support.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;

@Service
public class TicketNotificationService {
    private static final Logger log = LoggerFactory.getLogger(TicketNotificationService.class);
    private static final String RESOLVED = "resolved";

    private final JdbcTemplate jdbcTemplate;
    private final JavaMailSender mailSender;

    @Value("${support.mail.from:}") private String from;
    @Value("${spring.mail.username:}") private String mailUsername;
    @Value("${support.public-base-url:}") private String publicBaseUrl;

    public TicketNotificationService(JdbcTemplate jdbcTemplate, JavaMailSender mailSender) {
        this.jdbcTemplate = jdbcTemplate;
        this.mailSender = mailSender;
    }

    public void notifyResolvedAfterCommit(Long ticketId) {
        if (ticketId == null) return;
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sendResolved(ticketId);
                }
            });
            return;
        }
        sendResolved(ticketId);
    }

    public boolean sendResolved(Long ticketId) {
        jdbcTemplate.update(
                "INSERT IGNORE INTO ticket_notification(ticket_id,notification_type,status,attempt_count,create_time,update_time) " +
                        "VALUES (?,?,'pending',0,NOW(),NOW())",
                ticketId, RESOLVED);
        int claimed = jdbcTemplate.update(
                "UPDATE ticket_notification SET status='sending',attempt_count=attempt_count+1,last_error=NULL,update_time=NOW() " +
                        "WHERE ticket_id=? AND notification_type=? AND status IN ('pending','failed')",
                ticketId, RESOLVED);
        if (claimed == 0) {
            log.info("TICKET_RESOLVED_MAIL_SKIPPED ticketId={} reason=already_sent_or_sending", ticketId);
            return false;
        }

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT t.id,t.category,t.description,t.resolution_reason,t.resolution_result,t.resolved_time," +
                        "u.email,u.display_name,u.username " +
                        "FROM support_ticket t JOIN support_user u ON u.id=t.user_id " +
                        "WHERE t.id=? AND t.is_deleted=0 AND t.status='resolved' LIMIT 1",
                ticketId);
        if (rows.isEmpty()) {
            fail(ticketId, "工单不存在、已删除或尚未解决");
            return false;
        }

        Map<String, Object> row = rows.get(0);
        String email = text(row.get("email"));
        if (email.isBlank()) {
            fail(ticketId, "提出人未配置邮箱");
            log.warn("TICKET_RESOLVED_MAIL_FAILED ticketId={} reason=no_customer_email", ticketId);
            return false;
        }

        String displayName = text(row.get("display_name"));
        if (displayName.isBlank()) displayName = text(row.get("username"));
        String category = text(row.get("category"));
        String reason = text(row.get("resolution_reason"));
        String result = text(row.get("resolution_result"));

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            String sender = from == null || from.isBlank() ? mailUsername : from;
            if (sender != null && !sender.isBlank()) message.setFrom(sender);
            message.setTo(email);
            message.setSubject("[MYROBOOT] 工单已处理完成，请验收 #" + ticketId);

            StringBuilder body = new StringBuilder();
            body.append(displayName).append("，您好：\n\n")
                    .append("您提交的技术支持工单 #").append(ticketId).append(" 已处理完成，请登录平台查看处理结果并验收。\n\n")
                    .append("问题类型：").append(category).append("\n")
                    .append("问题原因：").append(shorten(reason, 1200)).append("\n")
                    .append("处理结果：").append(shorten(result, 2000)).append("\n");
            if (publicBaseUrl != null && !publicBaseUrl.isBlank()) {
                body.append("查看工单：")
                        .append(publicBaseUrl.replaceAll("/$", ""))
                        .append("/ticket-detail?id=").append(ticketId).append("\n");
            }
            body.append("\n如处理结果仍存在问题，请联系技术支持人员继续确认。\n")
                    .append("MYROBOOT 技术支持平台");
            message.setText(body.toString());
            mailSender.send(message);

            jdbcTemplate.update(
                    "UPDATE ticket_notification SET status='sent',recipient_email=?,sent_time=NOW(),last_error=NULL,update_time=NOW() " +
                            "WHERE ticket_id=? AND notification_type=?",
                    email, ticketId, RESOLVED);
            log.info("TICKET_RESOLVED_MAIL_SENT ticketId={} emailDomain={}", ticketId, emailDomain(email));
            return true;
        } catch (Exception e) {
            String error = shorten(rootMessage(e), 1000);
            fail(ticketId, error);
            log.warn("TICKET_RESOLVED_MAIL_FAILED ticketId={} emailDomain={} reason={}", ticketId, emailDomain(email), error);
            return false;
        }
    }

    private void fail(Long ticketId, String error) {
        jdbcTemplate.update(
                "UPDATE ticket_notification SET status='failed',last_error=?,update_time=NOW() WHERE ticket_id=? AND notification_type=?",
                shorten(error, 1000), ticketId, RESOLVED);
    }

    private String shorten(String value, int max) {
        if (value == null) return "";
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "…";
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String emailDomain(String email) {
        int at = email == null ? -1 : email.indexOf('@');
        return at >= 0 ? email.substring(at + 1) : "unknown";
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
