package com.myroboot.support.remote;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myroboot.support.service.RemoteAgentService;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;

public class RemoteAgentWebSocketHandler extends TextWebSocketHandler {
    private static final String ATTR_AGENT_ID = "remoteAgentId";
    private final RemoteAgentService agentService;
    private final RemoteTerminalBroker broker;
    private final RemoteTerminalService terminalService;
    private final ObjectMapper mapper = new ObjectMapper();

    public RemoteAgentWebSocketHandler(RemoteAgentService agentService, RemoteTerminalBroker broker, RemoteTerminalService terminalService) {
        this.agentService = agentService;
        this.broker = broker;
        this.terminalService = terminalService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String agentId = session.getHandshakeHeaders().getFirst("X-Agent-Id");
        String token = session.getHandshakeHeaders().getFirst("X-Agent-Token");
        try {
            RemoteAgentService.AgentIdentity identity = agentService.authenticateAgent(agentId, token);
            session.getAttributes().put(ATTR_AGENT_ID, identity.agentId());
            broker.registerAgent(identity.agentId(), session);
        } catch (SecurityException e) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("agent auth failed"));
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Map<String,Object> payload = mapper.readValue(message.getPayload(), new TypeReference<>() {});
        String type = String.valueOf(payload.getOrDefault("type", ""));
        String terminalSessionId = String.valueOf(payload.getOrDefault("sessionId", ""));
        if (terminalSessionId.isBlank()) return;
        if ("terminal_output".equals(type) || "terminal_error".equals(type) || "terminal_closed".equals(type)) {
            broker.routeAgentMessage(terminalSessionId, payload);
            if ("terminal_closed".equals(type)) terminalService.markClosed(terminalSessionId, "Agent 关闭终端会话");
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Object agentId = session.getAttributes().get(ATTR_AGENT_ID);
        if (agentId != null) broker.unregisterAgent(String.valueOf(agentId), session);
    }
}
