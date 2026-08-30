package com.myroboot.support.remote;

import com.myroboot.support.service.AuthService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.Map;import java.util.UUID;import java.util.concurrent.*;

@Service
public class RemoteFileService {
 private static final ConcurrentHashMap<String,CompletableFuture<Map<String,Object>>> WAIT=new ConcurrentHashMap<>();
 private final RemoteTerminalBroker broker;private final JdbcTemplate jdbc;
 public RemoteFileService(RemoteTerminalBroker b,JdbcTemplate j){broker=b;jdbc=j;}
 public Map<String,Object> list(AuthService.Session a,Long id,String path,String ip){return call(a,id,"file_list",path,null,false,ip);}
 public Map<String,Object> download(AuthService.Session a,Long id,String path,String ip){return call(a,id,"file_download",path,null,false,ip);}
 public Map<String,Object> upload(AuthService.Session a,Long id,String path,String data,boolean overwrite,String ip){if(data==null||data.length()>70_000_000)throw new IllegalArgumentException("单文件暂限制 50MB");return call(a,id,"file_upload",path,data,overwrite,ip);}
 public Map<String,Object> mkdir(AuthService.Session a,Long id,String path,String ip){return call(a,id,"file_mkdir",path,null,false,ip);}
 private Map<String,Object> call(AuthService.Session admin,Long id,String action,String path,String data,boolean overwrite,String ip){if(admin==null||!"admin".equals(admin.role()))throw new SecurityException("只有管理员可以远程管理文件");Map<String,Object> row=jdbc.queryForMap("SELECT agent_id,name FROM remote_agent WHERE id=? AND enabled=1",id);String agentId=String.valueOf(row.get("agent_id"));String rid=UUID.randomUUID().toString();CompletableFuture<Map<String,Object>> f=new CompletableFuture<>();WAIT.put(rid,f);try{boolean ok=switch(action){case"file_list"->broker.requestFileList(rid,agentId,path);case"file_download"->broker.requestFileDownload(rid,agentId,path);case"file_upload"->broker.requestFileUpload(rid,agentId,path,data,overwrite);case"file_mkdir"->broker.requestFileMkdir(rid,agentId,path);default->false;};if(!ok)throw new IllegalStateException("Agent 实时通道未连接");Map<String,Object> result=f.get(30,TimeUnit.SECONDS);if(Boolean.FALSE.equals(result.get("ok")))throw new IllegalArgumentException(String.valueOf(result.getOrDefault("message","文件操作失败")));String detail=action+" path="+path;jdbc.update("INSERT INTO remote_audit_log(agent_id,user_id,action_type,detail,client_ip) VALUES (?,?,?,?,?)",id,admin.userId(),action,detail.length()>1000?detail.substring(0,1000):detail,ip);return result;}catch(TimeoutException e){throw new IllegalStateException("Agent 文件操作超时");}catch(ExecutionException e){throw new IllegalStateException("文件操作失败",e.getCause());}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("文件操作被中断");}catch(java.io.IOException e){throw new IllegalStateException("发送 Agent 指令失败",e);}finally{WAIT.remove(rid);}}
 public static void complete(String id,Map<String,Object> payload){CompletableFuture<Map<String,Object>> f=WAIT.get(id);if(f!=null)f.complete(payload);}
}
