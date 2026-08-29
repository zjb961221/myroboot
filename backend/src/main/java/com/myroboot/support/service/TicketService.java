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
        String customerName = required(body, "customerName", "请填写客户名称");
        String mineName = required(body, "mineName", "请填写矿井名称");
        String category = required(body, "category", "请选择或填写问题类型");
        String screenshotUrl = required(body, "screenshotUrl", "请上传故障截图");
        String description = required(body, "description", "请填写问题描述后再提交");
        validateRequiredAttachments(body.get("attachments"));

        if (customerName.length() > 200) throw new IllegalArgumentException("客户名称不能超过 200 个字符");
        if (mineName.length() > 200) throw new IllegalArgumentException("矿井名称不能超过 200 个字符");
        if (category.length() > 100) throw new IllegalArgumentException("问题类型不能超过 100 个字符");
        if (description.length() < 5) throw new IllegalArgumentException("问题描述太短，请至少写清楚现象或报错信息");
        if (description.length() > 20000) throw new IllegalArgumentException("问题描述过长，请精简后再提交，详细内容可放在附件中");
        if (!screenshotUrl.startsWith("/api/uploads/")) throw new IllegalArgumentException("故障截图地址无效，请重新上传");

        jdbcTemplate.update(
                "INSERT INTO support_ticket(user_id,customer_name,mine_name,category,description,screenshot_url) VALUES (?,?,?,?,?,?)",
                session.userId(), customerName, mineName, category, description, screenshotUrl);
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
                "SELECT id,customer_name,mine_name,category,description,screenshot_url,status,resolution_reason,resolution_result,resolved_time," +
                        "cancel_reason,cancelled_time,create_time FROM support_ticket WHERE user_id=? AND is_deleted=0 ORDER BY id DESC LIMIT 100",
                session.userId()));
    }

    public List<Map<String, Object>> listAdmin() {
        return enrichAttachments(jdbcTemplate.queryForList(
                "SELECT t.id,t.user_id,t.customer_name,t.mine_name,t.category,t.description,t.screenshot_url,t.status," +
                        "t.resolution_reason,t.resolution_result,t.resolved_time,t.cancel_reason,t.cancelled_time,t.create_time,u.username,u.display_name " +
                        "FROM support_ticket t LEFT JOIN support_user u ON u.id=t.user_id WHERE t.is_deleted=0 ORDER BY t.id DESC LIMIT 300"));
    }

    @Transactional
    public boolean cancel(AuthService.Session customer, Long id, Map<String, Object> body) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id,user_id,status,is_deleted FROM support_ticket WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) throw new IllegalArgumentException("工单不存在");
        Map<String, Object> row = rows.get(0);
        Long ownerId = row.get("user_id") instanceof Number n ? n.longValue() : null;
        if (ownerId == null || !ownerId.equals(customer.userId())) throw new SecurityException("只能撤销自己提交的工单");
        if (((Number) row.get("is_deleted")).intValue() == 1) throw new IllegalArgumentException("工单已删除，无法撤销");
        String status = text(row.get("status"));
        if ("resolved".equals(status)) throw new IllegalArgumentException("已解决的工单不能撤销");
        if ("cancelled".equals(status)) throw new IllegalArgumentException("该工单已经撤销");
        if (!List.of("pending", "processing").contains(status)) throw new IllegalArgumentException("当前工单状态不能撤销");

        String reason = text(body == null ? null : body.get("reason"));
        if (reason.isBlank()) reason = "客户主动撤销工单";
        if (reason.length() > 500) throw new IllegalArgumentException("撤销原因不能超过 500 个字符");

        int updated = jdbcTemplate.update(
                "UPDATE support_ticket SET status='cancelled',cancel_reason=?,cancelled_time=NOW() WHERE id=? AND user_id=? AND is_deleted=0",
                reason, id, customer.userId());
        if (updated > 0) {
            jdbcTemplate.update(
                    "INSERT INTO ticket_history(ticket_id,operator_user_id,operator_name,action_type,content,visible_to_customer) VALUES (?,?,?,?,?,1)",
                    id, customer.userId(), operatorName(customer), "cancelled", "客户撤销工单：" + reason);
            log.info("TICKET_CANCELLED ticketId={} userId={}", id, customer.userId());
        }
        return updated > 0;
    }

    @Transactional
    public boolean delete(AuthService.Session admin, Long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id,is_deleted FROM support_ticket WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) throw new IllegalArgumentException("工单不存在");
        if (((Number) rows.get(0).get("is_deleted")).intValue() == 1) throw new IllegalArgumentException("该工单已经删除");

        int updated = jdbcTemplate.update(
                "UPDATE support_ticket SET is_deleted=1,deleted_time=NOW(),deleted_by=? WHERE id=? AND is_deleted=0",
                admin.userId(), id);
        if (updated > 0) {
            jdbcTemplate.update(
                    "INSERT INTO ticket_history(ticket_id,operator_user_id,operator_name,action_type,content,visible_to_customer) VALUES (?,?,?,?,?,0)",
                    id, admin.userId(), operatorName(admin), "deleted", "管理员已将该工单移出正常列表");
            log.info("TICKET_DELETED ticketId={} operatorUserId={}", id, admin.userId());
        }
        return updated > 0;
    }

    @Transactional
    public boolean updateStatus(AuthService.Session admin, Long id, Map<String, Object> body) {
        String status = text(body.get("status"));
        if (!List.of("pending", "processing", "resolved").contains(status)) {
            throw new IllegalArgumentException("不支持的工单状态");
        }
        Integer active = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM support_ticket WHERE id=? AND is_deleted=0", Integer.class, id);
        if (active == null || active == 0) throw new IllegalArgumentException("工单不存在或已删除");
        String current = jdbcTemplate.queryForObject("SELECT status FROM support_ticket WHERE id=?", String.class, id);
        if ("cancelled".equals(current)) throw new IllegalArgumentException("已撤销工单不能继续处理");

        if ("resolved".equals(status)) {
            String reason = required(body, "resolutionReason", "标记已解决时必须填写具体原因");
            String result = required(body, "resolutionResult", "标记已解决时必须填写处理回执");
            int updated = jdbcTemplate.update(
                    "UPDATE support_ticket SET status='resolved',resolution_reason=?,resolution_result=?,resolved_time=NOW() WHERE id=? AND is_deleted=0",
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
                "UPDATE support_ticket SET status=?,resolution_reason=NULL,resolution_result=NULL,resolved_time=NULL WHERE id=? AND is_deleted=0",
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

    private void validateRequiredAttachments(Object rawAttachments) {
        if (!(rawAttachments instanceof List<?> attachments) || attachments.isEmpty()) {
            throw new IllegalArgumentException("请至少上传 1 个工单附件");
        }
        if (attachments.size() > 10) throw new IllegalArgumentException("一个工单最多上传 10 个附件");
        for (Object raw : attachments) {
            if (!(raw instanceof Map<?, ?> item)) throw new IllegalArgumentException("工单附件信息无效，请重新上传");
            String url = text(item.get("url"));
            String name = text(item.get("name"));
            if (url.isBlank() || name.isBlank() || !url.startsWith("/api/uploads/")) {
                throw new IllegalArgumentException("工单附件信息无效，请重新上传");
            }
        }
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
    private String operatorName(AuthService.Session session) {
        return session.displayName() == null || session.displayName().isBlank() ? session.username() : session.displayName();
    }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private long number(Object value) {
        if (value instanceof Number n) return n.longValue();
        try { return Long.parseLong(text(value)); } catch (Exception ignored) { return 0L; }
    }
}
