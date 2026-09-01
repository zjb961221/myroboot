package com.myroboot.support.remote;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

public class RemoteBrowserTerminalWebSocketHandler extends TextWebSocketHandler {
    private static final String ATTR_SESSION_ID = "terminalSessionId";
    private final RemoteTerminalBroker broker;
    private final RemoteTerminalService terminalService;
    private final ObjectMapper mapper = new ObjectMapper();

    public RemoteBrowserTerminalWebSocketHandler(RemoteTerminalBroker broker, RemoteTerminalService terminalService) {
        this.broker = broker;
        this.terminalService = terminalService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String ticket = UriComponentsBuilder.fromUri(session.getUri()).build().getQueryParams().getFirst("ticket");
        try {
            RemoteTerminalService.TicketGrant grant = terminalService.consume(ticket);
            session.getAttributes().put(ATTR_SESSION_ID, grant.sessionId());
            if (!broker.openTerminal(grant.sessionId(), grant.agentId(), session, 120, 32)) {
                terminalService.markClosed(grant.sessionId(), "Agent 实时通道不可用");
                session.close(CloseStatus.SERVICE_OVERLOAD.withReason("agent offline"));
                return;
            }
            terminalService.markActive(grant.sessionId());
        } catch (SecurityException e) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("invalid terminal ticket"));
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String sessionId = String.valueOf(session.getAttributes().getOrDefault(ATTR_SESSION_ID, ""));
        if (sessionId.isBlank()) return;
        Map<String,Object> payload = mapper.readValue(message.getPayload(), new TypeReference<>() {});
        String type = String.valueOf(payload.getOrDefault("type", ""));
        switch (type) {
            case "input" -> broker.input(sessionId, String.valueOf(payload.getOrDefault("data", "")));
            case "resize" -> broker.resize(sessionId, intValue(payload.get("cols"), 120), intValue(payload.get("rows"), 32));
            case "close" -> {
                broker.closeTerminal(sessionId);
                terminalService.markClosed(sessionId, "管理员主动关闭终端");
                session.close(CloseStatus.NORMAL);
            }
            default -> { }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String sessionId = String.valueOf(session.getAttributes().getOrDefault(ATTR_SESSION_ID, ""));
        if (!sessionId.isBlank()) {
            broker.detachBrowser(sessionId, session);
            terminalService.markClosed(sessionId, "浏览器终端连接断开");
        }
    }

    private int intValue(Object value, int fallback) {
        try {
            int n = Integer.parseInt(String.valueOf(value));
            return Math.max(2, Math.min(n, 500));
        } catch (Exception e) {
            return fallback;
        }
    }
}
