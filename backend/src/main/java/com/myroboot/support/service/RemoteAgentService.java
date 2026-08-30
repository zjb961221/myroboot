package com.myroboot.support.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class RemoteAgentService {
    private final JdbcTemplate jdbcTemplate;
    private final SecureRandom secureRandom = new SecureRandom();

    public RemoteAgentService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Map<String,Object>> list(AuthService.Session admin) {
        requireAdmin(admin);
        List<Map<String,Object>> rows = jdbcTemplate.queryForList("""
                SELECT id,agent_id,name,mine_name,hostname,os_name,agent_version,private_ip,desktop_session,
                       enabled,last_seen,create_time,update_time,
                       CASE WHEN enabled=1 AND last_seen IS NOT NULL AND last_seen >= DATE_SUB(NOW(), INTERVAL 90 SECOND)
                            THEN 1 ELSE 0 END AS online
                FROM remote_agent
                ORDER BY online DESC, name, id
                """);
        return rows;
    }

    @Transactional
    public Map<String,Object> create(AuthService.Session admin, Map<String,Object> body, String clientIp) {
        requireAdmin(admin);
        String name = required(body, "name", "请填写服务器名称");
        String mineName = text(body.get("mineName"));
        if (name.length() > 200 || mineName.length() > 200) throw new IllegalArgumentException("名称长度不能超过 200 个字符");
        String agentId = UUID.randomUUID().toString();
        String token = newToken();
        jdbcTemplate.update("INSERT INTO remote_agent(agent_id,token_hash,name,mine_name) VALUES (?,?,?,?)",
                agentId, sha256(token), name, emptyToNull(mineName));
        Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        audit(id, admin.userId(), "agent_created", "创建远程 Agent：" + name, clientIp);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("agentId", agentId);
        result.put("token", token);
        result.put("name", name);
        result.put("mineName", mineName);
        return result;
    }

    @Transactional
    public Map<String,Object> rotateToken(AuthService.Session admin, Long id, String clientIp) {
        requireAdmin(admin);
        String token = newToken();
        int updated = jdbcTemplate.update("UPDATE remote_agent SET token_hash=? WHERE id=? AND enabled=1", sha256(token), id);
        if (updated == 0) throw new IllegalArgumentException("服务器不存在或已停用");
        audit(id, admin.userId(), "agent_token_rotated", "重新生成 Agent Token", clientIp);
        return Map.of("token", token);
    }

    @Transactional
    public void setEnabled(AuthService.Session admin, Long id, boolean enabled, String clientIp) {
        requireAdmin(admin);
        int updated = jdbcTemplate.update("UPDATE remote_agent SET enabled=? WHERE id=?", enabled ? 1 : 0, id);
        if (updated == 0) throw new IllegalArgumentException("服务器不存在");
        audit(id, admin.userId(), enabled ? "agent_enabled" : "agent_disabled", enabled ? "启用远程 Agent" : "停用远程 Agent", clientIp);
    }

    public Map<String,Object> heartbeat(String agentId, String token, Map<String,Object> body) {
        if (agentId == null || agentId.isBlank() || token == null || token.isBlank()) throw new SecurityException("Agent 身份信息缺失");
        List<Map<String,Object>> rows = jdbcTemplate.queryForList("SELECT id,token_hash,enabled FROM remote_agent WHERE agent_id=? LIMIT 1", agentId);
        if (rows.isEmpty()) throw new SecurityException("Agent 未注册");
        Map<String,Object> row = rows.get(0);
        if (((Number)row.get("enabled")).intValue() != 1) throw new SecurityException("Agent 已停用");
        if (!constantTimeEquals(String.valueOf(row.get("token_hash")), sha256(token))) throw new SecurityException("Agent Token 无效");

        String hostname = limit(text(body.get("hostname")), 200);
        String osName = limit(text(body.get("osName")), 200);
        String version = limit(text(body.get("agentVersion")), 50);
        String privateIp = limit(text(body.get("privateIp")), 100);
        String desktop = limit(text(body.get("desktopSession")), 100);
        jdbcTemplate.update("""
                UPDATE remote_agent
                SET hostname=?,os_name=?,agent_version=?,private_ip=?,desktop_session=?,last_seen=NOW()
                WHERE agent_id=?
                """, emptyToNull(hostname), emptyToNull(osName), emptyToNull(version), emptyToNull(privateIp), emptyToNull(desktop), agentId);
        return Map.of("ok", true, "serverTime", LocalDateTime.now().toString());
    }

    public List<Map<String,Object>> auditLogs(AuthService.Session admin, int limit) {
        requireAdmin(admin);
        int safe = Math.max(1, Math.min(limit, 500));
        return jdbcTemplate.queryForList("""
                SELECT l.id,l.action_type,l.detail,l.client_ip,l.create_time,
                       a.name AS agent_name,a.mine_name,
                       COALESCE(NULLIF(u.display_name,''),u.username) AS operator_name
                FROM remote_audit_log l
                LEFT JOIN remote_agent a ON a.id=l.agent_id
                LEFT JOIN support_user u ON u.id=l.user_id
                ORDER BY l.id DESC LIMIT ?
                """, safe);
    }

    private void audit(Long agentId, Long userId, String action, String detail, String clientIp) {
        jdbcTemplate.update("INSERT INTO remote_audit_log(agent_id,user_id,action_type,detail,client_ip) VALUES (?,?,?,?,?)",
                agentId, userId, action, limit(detail, 1000), limit(clientIp, 100));
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("无法生成安全摘要", e);
        }
    }

    private boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private void requireAdmin(AuthService.Session session) {
        if (session == null || !"admin".equals(session.role())) throw new SecurityException("只有管理员可以管理远程服务器");
    }

    private String required(Map<String,Object> body, String key, String message) {
        String value = text(body == null ? null : body.get(key));
        if (value.isBlank()) throw new IllegalArgumentException(message);
        return value;
    }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private String emptyToNull(String value) { return value == null || value.isBlank() ? null : value; }
    private String limit(String value, int max) { return value == null ? "" : value.substring(0, Math.min(value.length(), max)); }
}
