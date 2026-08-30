package com.myroboot.support.remote;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myroboot.support.service.RemoteAgentService;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import java.util.Map;

public class RemoteAgentWebSocketHandler extends TextWebSocketHandler {
    private static final String ATTR_AGENT_ID="remoteAgentId";
    private final RemoteAgentService agentService; private final RemoteTerminalBroker broker; private final RemoteTerminalService terminalService; private final ObjectMapper mapper=new ObjectMapper();
    public RemoteAgentWebSocketHandler(RemoteAgentService a,RemoteTerminalBroker b,RemoteTerminalService t){agentService=a;broker=b;terminalService=t;}
    @Override public void afterConnectionEstablished(WebSocketSession s)throws Exception{String id=s.getHandshakeHeaders().getFirst("X-Agent-Id"),token=s.getHandshakeHeaders().getFirst("X-Agent-Token");try{var i=agentService.authenticateAgent(id,token);s.getAttributes().put(ATTR_AGENT_ID,i.agentId());broker.registerAgent(i.agentId(),s);}catch(SecurityException e){s.close(CloseStatus.POLICY_VIOLATION.withReason("agent auth failed"));}}
    @Override protected void handleTextMessage(WebSocketSession s,TextMessage m)throws Exception{Map<String,Object> p=mapper.readValue(m.getPayload(),new TypeReference<>(){});String type=String.valueOf(p.getOrDefault("type",""));String sid=String.valueOf(p.getOrDefault("sessionId",""));if(!sid.isBlank()&&(type.equals("terminal_output")||type.equals("terminal_error")||type.equals("terminal_closed"))){broker.routeAgentMessage(sid,p);if(type.equals("terminal_closed"))terminalService.markClosed(sid,"Agent 关闭终端会话");return;}String rid=String.valueOf(p.getOrDefault("requestId",""));if(!rid.isBlank()&&type.startsWith("file_"))RemoteFileService.complete(rid,p);}
    @Override public void afterConnectionClosed(WebSocketSession s,CloseStatus status){Object id=s.getAttributes().get(ATTR_AGENT_ID);if(id!=null)broker.unregisterAgent(String.valueOf(id),s);}
}
