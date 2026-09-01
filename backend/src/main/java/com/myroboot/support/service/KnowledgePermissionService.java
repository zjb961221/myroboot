package com.myroboot.support.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class KnowledgePermissionService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgePermissionService.class);
    private final JdbcTemplate jdbc;
    private final JavaMailSender mailSender;

    @Value("${support.admin-notification-email:}") private String adminEmail;
    @Value("${support.public-base-url:}") private String publicBaseUrl;
    @Value("${support.mail.from:}") private String from;
    @Value("${spring.mail.username:}") private String mailUsername;

    public KnowledgePermissionService(JdbcTemplate jdbc, JavaMailSender mailSender) {
        this.jdbc = jdbc;
        this.mailSender = mailSender;
    }

    public Map<String,Object> status(AuthService.Session processor) {
        requireProcessor(processor);
        boolean granted = hasPermission(processor.userId());
        List<Map<String,Object>> pending = jdbc.queryForList("SELECT id,status,create_time,review_time FROM knowledge_permission_request WHERE user_id=? ORDER BY id DESC LIMIT 1", processor.userId());
        return Map.of("granted", granted, "latestRequest", pending.isEmpty() ? Map.of() : pending.get(0));
    }

    public boolean hasPermission(Long userId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_permission_request WHERE user_id=? AND status='approved'", Integer.class, userId);
        return count != null && count > 0;
    }

    @Transactional
    public Long request(AuthService.Session processor, String reason) {
        requireProcessor(processor);
        if (hasPermission(processor.userId())) throw new IllegalArgumentException("你已经拥有问题库录入权限");
        Integer pending = jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_permission_request WHERE user_id=? AND status='pending'", Integer.class, processor.userId());
        if (pending != null && pending > 0) throw new IllegalArgumentException("已有待审批申请，请等待管理员处理");
        String cleanReason = reason == null ? "" : reason.trim();
        if (cleanReason.length() > 500) throw new IllegalArgumentException("申请说明不能超过 500 个字符");
        jdbc.update("INSERT INTO knowledge_permission_request(user_id,reason,status) VALUES (?,?,'pending')", processor.userId(), cleanReason);
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        if (id == null) throw new IllegalStateException("权限申请创建失败");
        log.info("KNOWLEDGE_PERMISSION_REQUESTED requestId={} userId={}", id, processor.userId());
        try { sendAdminMail(id, processor, cleanReason); }
        catch (Exception e) { log.error("KNOWLEDGE_PERMISSION_MAIL_FAILED requestId={} error={}", id, e.getMessage()); }
        return id;
    }

    public List<Map<String,Object>> listForAdmin() {
        return jdbc.queryForList("SELECT r.id,r.user_id,r.reason,r.status,r.create_time,r.review_time,r.reviewed_by,u.username,u.display_name,u.company_name,u.email FROM knowledge_permission_request r JOIN support_user u ON u.id=r.user_id ORDER BY CASE r.status WHEN 'pending' THEN 0 ELSE 1 END,r.id DESC");
    }

    @Transactional
    public void review(AuthService.Session admin, Long requestId, boolean approve) {
        List<Map<String,Object>> rows = jdbc.queryForList("SELECT id,user_id,status FROM knowledge_permission_request WHERE id=? FOR UPDATE", requestId);
        if (rows.isEmpty()) throw new IllegalArgumentException("权限申请不存在");
        if (!"pending".equals(String.valueOf(rows.get(0).get("status")))) throw new IllegalArgumentException("该申请已经处理过");
        jdbc.update("UPDATE knowledge_permission_request SET status=?,reviewed_by=?,review_time=NOW() WHERE id=?", approve ? "approved" : "rejected", admin.userId(), requestId);
        log.info("KNOWLEDGE_PERMISSION_REVIEWED requestId={} adminUserId={} decision={}", requestId, admin.userId(), approve ? "approved" : "rejected");
    }

    public void requireGranted(AuthService.Session processor) {
        requireProcessor(processor);
        if (!hasPermission(processor.userId())) throw new SecurityException("你还没有问题库录入权限，请先向管理员申请");
    }

    private void sendAdminMail(Long requestId, AuthService.Session processor, String reason) {
        if (adminEmail == null || adminEmail.isBlank()) {
            log.warn("KNOWLEDGE_PERMISSION_MAIL_SKIPPED requestId={} reason=ADMIN_NOTIFICATION_EMAIL_NOT_CONFIGURED", requestId);
            return;
        }
        String base = publicBaseUrl == null ? "" : publicBaseUrl.replaceAll("/+$", "");
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom((from == null || from.isBlank()) ? mailUsername : from);
        message.setTo(adminEmail);
        message.setSubject("[MYROBOOT] 问题库录入权限申请 #" + requestId);
        message.setText("处理人员 " + processor.displayName() + "（" + processor.username() + "）申请问题库录入权限。\n\n单位：" + processor.companyName() + "\n申请说明：" + (reason.isBlank() ? "未填写" : reason) + "\n\n请登录管理员后台审批：\n" + base + "/admin/knowledge-permissions?requestId=" + requestId + "\n\n邮件链接只负责进入审批页面，不会自动授权；必须由已登录管理员明确点击同意。 ");
        mailSender.send(message);
        log.info("KNOWLEDGE_PERMISSION_MAIL_SENT requestId={}", requestId);
    }

    private void requireProcessor(AuthService.Session session) {
        if (session == null || !"processor".equals(session.role())) throw new SecurityException("仅处理人员可以申请该权限");
    }
}
