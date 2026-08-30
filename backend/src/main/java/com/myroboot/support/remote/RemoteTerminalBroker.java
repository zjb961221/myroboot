package com.myroboot.support.remote;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RemoteTerminalBroker {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, WebSocketSession> agents = new ConcurrentHashMap<>();
    private final Map<String, WebSocketSession> browsers = new ConcurrentHashMap<>();
    private final Map<String, String> sessionAgents = new ConcurrentHashMap<>();

    public void registerAgent(String agentId, WebSocketSession session) {
        WebSocketSession old = agents.put(agentId, session);
        if (old != null && old.isOpen() && old != session) closeQuietly(old, CloseStatus.NORMAL);
    }

    public void unregisterAgent(String agentId, WebSocketSession session) {
        agents.remove(agentId, session);
    }

    public boolean isAgentConnected(String agentId) {
        WebSocketSession session = agents.get(agentId);
        return session != null && session.isOpen();
    }

    public boolean openTerminal(String terminalSessionId, String agentId, WebSocketSession browser, int cols, int rows) throws IOException {
        WebSocketSession agent = agents.get(agentId);
        if (agent == null || !agent.isOpen()) return false;
        browsers.put(terminalSessionId, browser);
        sessionAgents.put(terminalSessionId, agentId);
        send(agent, Map.of("type", "terminal_open", "sessionId", terminalSessionId, "cols", cols, "rows", rows));
        return true;
    }

    public void input(String terminalSessionId, String data) throws IOException {
        WebSocketSession agent = agentFor(terminalSessionId);
        if (agent != null) send(agent, Map.of("type", "terminal_input", "sessionId", terminalSessionId, "data", data));
    }

    public void resize(String terminalSessionId, int cols, int rows) throws IOException {
        WebSocketSession agent = agentFor(terminalSessionId);
        if (agent != null) send(agent, Map.of("type", "terminal_resize", "sessionId", terminalSessionId, "cols", cols, "rows", rows));
    }

    public void closeTerminal(String terminalSessionId) {
        WebSocketSession agent = agentFor(terminalSessionId);
        if (agent != null) {
            try { send(agent, Map.of("type", "terminal_close", "sessionId", terminalSessionId)); } catch (Exception ignored) {}
        }
        browsers.remove(terminalSessionId);
        sessionAgents.remove(terminalSessionId);
    }

    public void routeAgentMessage(String terminalSessionId, Map<String,Object> payload) throws IOException {
        WebSocketSession browser = browsers.get(terminalSessionId);
        if (browser != null && browser.isOpen()) send(browser, payload);
    }

    public void detachBrowser(String terminalSessionId, WebSocketSession browser) {
        browsers.remove(terminalSessionId, browser);
        closeTerminal(terminalSessionId);
    }

    private WebSocketSession agentFor(String terminalSessionId) {
        String agentId = sessionAgents.get(terminalSessionId);
        return agentId == null ? null : agents.get(agentId);
    }

    private void send(WebSocketSession session, Map<String,Object> payload) throws IOException {
        String json = mapper.writeValueAsString(payload);
        synchronized (session) {
            if (session.isOpen()) session.sendMessage(new TextMessage(json));
        }
    }

    private void closeQuietly(WebSocketSession session, CloseStatus status) {
        try { session.close(status); } catch (Exception ignored) {}
    }
}
