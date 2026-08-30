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
    public void unregisterAgent(String agentId, WebSocketSession session) { agents.remove(agentId, session); }
    public boolean isAgentConnected(String agentId) { WebSocketSession s=agents.get(agentId); return s!=null&&s.isOpen(); }

    public boolean openTerminal(String id,String agentId,WebSocketSession browser,int cols,int rows)throws IOException{
        WebSocketSession agent=agents.get(agentId); if(agent==null||!agent.isOpen())return false;
        browsers.put(id,browser);sessionAgents.put(id,agentId);send(agent,Map.of("type","terminal_open","sessionId",id,"cols",cols,"rows",rows));return true;
    }
    public void input(String id,String data)throws IOException{WebSocketSession a=agentFor(id);if(a!=null)send(a,Map.of("type","terminal_input","sessionId",id,"data",data));}
    public void resize(String id,int cols,int rows)throws IOException{WebSocketSession a=agentFor(id);if(a!=null)send(a,Map.of("type","terminal_resize","sessionId",id,"cols",cols,"rows",rows));}
    public void closeTerminal(String id){WebSocketSession a=agentFor(id);if(a!=null)try{send(a,Map.of("type","terminal_close","sessionId",id));}catch(Exception ignored){}browsers.remove(id);sessionAgents.remove(id);}
    public void routeAgentMessage(String id,Map<String,Object> payload)throws IOException{WebSocketSession b=browsers.get(id);if(b!=null&&b.isOpen())send(b,payload);}
    public void detachBrowser(String id,WebSocketSession browser){browsers.remove(id,browser);closeTerminal(id);}

    public boolean requestFileList(String requestId,String agentId,String path)throws IOException{return request(agentId,Map.of("type","file_list","requestId",requestId,"path",path));}
    public boolean requestFileDownload(String requestId,String agentId,String path)throws IOException{return request(agentId,Map.of("type","file_download","requestId",requestId,"path",path));}
    public boolean requestFileUpload(String requestId,String agentId,String path,String data,boolean overwrite)throws IOException{return request(agentId,Map.of("type","file_upload","requestId",requestId,"path",path,"data",data,"overwrite",overwrite));}
    public boolean requestFileMkdir(String requestId,String agentId,String path)throws IOException{return request(agentId,Map.of("type","file_mkdir","requestId",requestId,"path",path));}
    private boolean request(String agentId,Map<String,Object> payload)throws IOException{WebSocketSession a=agents.get(agentId);if(a==null||!a.isOpen())return false;send(a,payload);return true;}

    private WebSocketSession agentFor(String id){String a=sessionAgents.get(id);return a==null?null:agents.get(a);}
    private void send(WebSocketSession session,Map<String,Object> payload)throws IOException{String json=mapper.writeValueAsString(payload);synchronized(session){if(session.isOpen())session.sendMessage(new TextMessage(json));}}
    private void closeQuietly(WebSocketSession session,CloseStatus status){try{session.close(status);}catch(Exception ignored){}}
}
