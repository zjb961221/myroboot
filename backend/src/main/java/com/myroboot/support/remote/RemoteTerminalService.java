package com.myroboot.support.remote;

import com.myroboot.support.service.AuthService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RemoteTerminalService {
    private final JdbcTemplate jdbcTemplate;
    private final RemoteTerminalBroker broker;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, TicketGrant> tickets = new ConcurrentHashMap<>();

    public RemoteTerminalService(JdbcTemplate jdbcTemplate, RemoteTerminalBroker broker) {
        this.jdbcTemplate = jdbcTemplate;
        this.broker = broker;
    }

    @Transactional
    public Map<String,Object> createTicket(AuthService.Session admin, Long agentDbId, String clientIp) {
        if (admin == null || !"admin".equals(admin.role())) throw new SecurityException("只有管理员可以打开远程终端");
        List<Map<String,Object>> rows = jdbcTemplate.queryForList("""
                SELECT id,agent_id,name,enabled,last_seen
                FROM remote_agent WHERE id=? LIMIT 1
                """, agentDbId);
        if (rows.isEmpty()) throw new IllegalArgumentException("服务器不存在");
        Map<String,Object> row = rows.get(0);
        if (((Number) row.get("enabled")).intValue() != 1) throw new IllegalArgumentException("服务器已停用");
        String agentId = String.valueOf(row.get("agent_id"));
        if (!broker.isAgentConnected(agentId)) throw new IllegalArgumentException("Agent 尚未建立实时通道，请先更新并重启被控端 Agent");

        String sessionId = UUID.randomUUID().toString();
        String ticket = randomToken();
        jdbcTemplate.update("INSERT INTO remote_session(session_id,agent_id,user_id,session_type,status,client_ip) VALUES (?,?,?,?,?,?)",
                sessionId, agentDbId, admin.userId(), "terminal", "opening", clientIp);
        jdbcTemplate.update("INSERT INTO remote_audit_log(agent_id,user_id,action_type,detail,client_ip) VALUES (?,?,?,?,?)",
                agentDbId, admin.userId(), "terminal_open_requested", "申请打开 Web Terminal，会话 " + sessionId, clientIp);
        tickets.put(ticket, new TicketGrant(sessionId, agentId, agentDbId, admin.userId(), Instant.now().plusSeconds(60)));
        cleanupExpired();
        return Map.of("ticket", ticket, "sessionId", sessionId, "expiresInSeconds", 60);
    }

    public TicketGrant consume(String ticket) {
        if (ticket == null || ticket.isBlank()) throw new SecurityException("终端连接票据缺失");
        TicketGrant grant = tickets.remove(ticket);
        if (grant == null || Instant.now().isAfter(grant.expiresAt())) throw new SecurityException("终端连接票据无效或已过期");
        return grant;
    }

    public void markActive(String sessionId) {
        jdbcTemplate.update("UPDATE remote_session SET status='active' WHERE session_id=? AND status='opening'", sessionId);
    }

    public void markClosed(String sessionId, String detail) {
        List<Map<String,Object>> rows = jdbcTemplate.queryForList("SELECT agent_id,user_id,status FROM remote_session WHERE session_id=? LIMIT 1", sessionId);
        if (rows.isEmpty()) return;
        Map<String,Object> row = rows.get(0);
        jdbcTemplate.update("UPDATE remote_session SET status='closed',end_time=COALESCE(end_time,NOW()) WHERE session_id=?", sessionId);
        jdbcTemplate.update("INSERT INTO remote_audit_log(agent_id,user_id,action_type,detail) VALUES (?,?,?,?)",
                ((Number)row.get("agent_id")).longValue(), ((Number)row.get("user_id")).longValue(), "terminal_closed", limit(detail, 1000));
    }

    private void cleanupExpired() {
        Instant now = Instant.now();
        tickets.entrySet().removeIf(e -> now.isAfter(e.getValue().expiresAt()));
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String limit(String value, int max) {
        if (value == null) return "";
        return value.substring(0, Math.min(value.length(), max));
    }

    public record TicketGrant(String sessionId, String agentId, Long agentDbId, Long userId, Instant expiresAt) {}
}
