package com.myroboot.support.remote;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myroboot.support.service.AuthService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
public class RemoteDesktopService {
    private static final int CHUNK_SIZE = 16 * 1024;
    private static final long SESSION_TTL_MS = TimeUnit.HOURS.toMillis(4);
    private final JdbcTemplate jdbc;
    private final RemoteTerminalBroker broker;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, DesktopTunnel> tunnels = new ConcurrentHashMap<>();
    private final ExecutorService ioPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "remote-desktop-io");
        t.setDaemon(true);
        return t;
    });

    @Value("${support.remote.guacamole-json-secret:}") private String guacamoleJsonSecret;
    @Value("${support.remote.guacamole-path:/guacamole/}") private String guacamolePath;
    @Value("${support.remote.guacd-target-host:backend}") private String guacdTargetHost;

    public RemoteDesktopService(JdbcTemplate jdbc, RemoteTerminalBroker broker) {
        this.jdbc = jdbc;
        this.broker = broker;
    }

    public Map<String,Object> create(AuthService.Session admin, Long agentDbId, Map<String,Object> body, String clientIp) {
        requireAdmin(admin);
        if (guacamoleJsonSecret == null || !guacamoleJsonSecret.matches("(?i)^[0-9a-f]{32}$")) {
            throw new IllegalStateException("远程桌面网关未配置：请设置 32 位十六进制 GUAC_JSON_SECRET_KEY");
        }
        String username = text(body.get("username"));
        String password = text(body.get("password"));
        int rdpPort = intValue(body.get("port"), 3389);
        if (username.isBlank() || password.isBlank()) throw new IllegalArgumentException("请输入 GNOME 远程桌面的用户名和密码");
        if (username.length() > 128 || password.length() > 512) throw new IllegalArgumentException("远程桌面凭据长度不合法");
        if (rdpPort < 3389 || rdpPort > 3399) throw new IllegalArgumentException("为防止 TCP 隧道被滥用，当前仅允许 GNOME RDP 端口 3389-3399");

        List<Map<String,Object>> rows = jdbc.queryForList("SELECT id,agent_id,name,agent_version,enabled,last_seen FROM remote_agent WHERE id=? LIMIT 1", agentDbId);
        if (rows.isEmpty()) throw new IllegalArgumentException("服务器不存在");
        Map<String,Object> agent = rows.get(0);
        if (((Number)agent.get("enabled")).intValue() != 1) throw new IllegalArgumentException("服务器已停用");
        String agentId = text(agent.get("agent_id"));
        String version = text(agent.get("agent_version"));
        if (!versionAtLeast(version, 0, 4, 0)) throw new IllegalArgumentException("远程桌面要求 Agent 0.4.0+，当前版本：" + (version.isBlank()?"未知":version));
        if (!broker.isAgentConnected(agentId)) throw new IllegalStateException("Agent 实时通道未连接");

        String sessionId = UUID.randomUUID().toString();
        ServerSocket listener;
        try {
            listener = new ServerSocket(0, 1, InetAddress.getByName("0.0.0.0"));
            listener.setSoTimeout((int)Math.min(SESSION_TTL_MS, Integer.MAX_VALUE));
        } catch (Exception e) {
            throw new IllegalStateException("无法创建远程桌面内部隧道", e);
        }
        DesktopTunnel tunnel = new DesktopTunnel(sessionId, agentDbId, agentId, listener, System.currentTimeMillis());
        tunnels.put(sessionId, tunnel);
        try {
            if (!broker.openDesktopTunnel(sessionId, agentId, rdpPort)) throw new IllegalStateException("Agent 实时通道未连接");
            jdbc.update("INSERT INTO remote_session(session_id,agent_id,user_id,session_type,status,client_ip) VALUES (?,?,?,'desktop','opening',?)", sessionId, agentDbId, admin.userId(), limit(clientIp,100));
            audit(agentDbId, admin.userId(), "desktop_open", "打开 GNOME 远程桌面会话，RDP 端口=" + rdpPort, clientIp);
            ioPool.submit(() -> acceptGuacd(tunnel));

            String connectionName = "MYROBOOT-" + agentDbId + "-" + sessionId.substring(0, 8);
            Map<String,Object> params = new LinkedHashMap<>();
            params.put("hostname", guacdTargetHost);
            params.put("port", String.valueOf(listener.getLocalPort()));
            params.put("username", username);
            params.put("password", password);
            params.put("ignore-cert", "true");
            params.put("security", "any");
            params.put("resize-method", "display-update");
            params.put("enable-wallpaper", "true");
            params.put("enable-font-smoothing", "true");
            params.put("disable-audio", "false");
            // JSON auth connections are exposed by the unique key in the top-level
            // "connections" object. The optional "id" field is only needed for
            // sharing/shadowing and can interfere with normal ad-hoc connection lookup.
            Map<String,Object> connection = Map.of("protocol", "rdp", "parameters", params);
            Map<String,Object> auth = new LinkedHashMap<>();
            auth.put("username", "myroboot-admin-" + admin.userId());
            auth.put("expires", Instant.now().plusSeconds(90).toEpochMilli());
            auth.put("connections", Map.of(connectionName, connection));
            String data = encryptGuacamoleJson(mapper.writeValueAsBytes(auth));
            return Map.of("sessionId", sessionId, "path", guacamolePath, "data", data, "expiresInSeconds", 90);
        } catch (RuntimeException e) {
            closeInternal(sessionId, "desktop_open_failed", false);
            throw e;
        } catch (Exception e) {
            closeInternal(sessionId, "desktop_open_failed", false);
            throw new IllegalStateException("创建远程桌面授权失败", e);
        }
    }

    public void handleAgentMessage(String sessionId, Map<String,Object> payload) {
        DesktopTunnel tunnel = tunnels.get(sessionId);
        if (tunnel == null) return;
        String type = text(payload.get("type"));
        try {
            if ("desktop_tunnel_data".equals(type)) {
                Socket socket = tunnel.guacdSocket;
                if (socket == null || socket.isClosed()) return;
                byte[] bytes = Base64.getDecoder().decode(text(payload.get("data")));
                synchronized (socket) { socket.getOutputStream().write(bytes); socket.getOutputStream().flush(); }
            } else if ("desktop_tunnel_ready".equals(type)) {
                jdbc.update("UPDATE remote_session SET status='active' WHERE session_id=? AND status='opening'", sessionId);
            } else if ("desktop_tunnel_error".equals(type)) {
                closeInternal(sessionId, "agent_error:" + limit(text(payload.get("message")),300), true);
            } else if ("desktop_tunnel_closed".equals(type)) {
                closeInternal(sessionId, "agent_closed", true);
            }
        } catch (Exception e) {
            closeInternal(sessionId, "relay_error", true);
        }
    }

    public void close(AuthService.Session admin, String sessionId, String clientIp) {
        requireAdmin(admin);
        DesktopTunnel t = tunnels.get(sessionId);
        if (t != null) audit(t.agentDbId, admin.userId(), "desktop_close", "管理员关闭 GNOME 远程桌面会话", clientIp);
        closeInternal(sessionId, "admin_closed", true);
    }

    public void agentDisconnected(String agentId) {
        tunnels.values().stream().filter(t -> t.agentId.equals(agentId)).map(t -> t.sessionId).toList().forEach(id -> closeInternal(id, "agent_disconnected", false));
    }

    private void acceptGuacd(DesktopTunnel tunnel) {
        try (Socket socket = tunnel.listener.accept()) {
            tunnel.guacdSocket = socket;
            socket.setTcpNoDelay(true);
            jdbc.update("UPDATE remote_session SET status='active' WHERE session_id=? AND status='opening'", tunnel.sessionId);
            byte[] buf = new byte[CHUNK_SIZE];
            InputStream in = socket.getInputStream();
            while (!socket.isClosed() && System.currentTimeMillis() - tunnel.createdAt < SESSION_TTL_MS) {
                int n = in.read(buf);
                if (n < 0) break;
                if (n == 0) continue;
                byte[] chunk = java.util.Arrays.copyOf(buf, n);
                if (!broker.sendDesktopData(tunnel.sessionId, tunnel.agentId, Base64.getEncoder().encodeToString(chunk))) break;
            }
        } catch (Exception ignored) {
        } finally {
            closeInternal(tunnel.sessionId, "guacd_closed", true);
        }
    }

    private void closeInternal(String sessionId, String reason, boolean notifyAgent) {
        DesktopTunnel tunnel = tunnels.remove(sessionId);
        if (tunnel == null) return;
        if (notifyAgent) broker.closeDesktopTunnel(sessionId, tunnel.agentId);
        try { tunnel.listener.close(); } catch (Exception ignored) {}
        try { if (tunnel.guacdSocket != null) tunnel.guacdSocket.close(); } catch (Exception ignored) {}
        jdbc.update("UPDATE remote_session SET status='closed',end_time=NOW() WHERE session_id=? AND status<>'closed'", sessionId);
    }

    private String encryptGuacamoleJson(byte[] json) throws Exception {
        byte[] key = HexFormat.of().parseHex(guacamoleJsonSecret);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        byte[] sig = mac.doFinal(json);
        byte[] signed = new byte[sig.length + json.length];
        System.arraycopy(sig, 0, signed, 0, sig.length);
        System.arraycopy(json, 0, signed, sig.length, json.length);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(new byte[16]));
        return Base64.getEncoder().encodeToString(cipher.doFinal(signed));
    }

    private void audit(Long agentId, Long userId, String action, String detail, String clientIp) {
        jdbc.update("INSERT INTO remote_audit_log(agent_id,user_id,action_type,detail,client_ip) VALUES (?,?,?,?,?)", agentId,userId,action,limit(detail,1000),limit(clientIp,100));
    }
    private void requireAdmin(AuthService.Session s){if(s==null||!"admin".equals(s.role()))throw new SecurityException("只有管理员可以使用远程桌面");}
    private String text(Object v){return v==null?"":String.valueOf(v).trim();}
    private String limit(String v,int n){if(v==null)return"";return v.length()<=n?v:v.substring(0,n);}
    private int intValue(Object v,int d){try{return v==null?d:Integer.parseInt(String.valueOf(v));}catch(Exception e){return d;}}
    private boolean versionAtLeast(String v,int ma,int mi,int pa){try{String[] p=v.split("\\.");int a=Integer.parseInt(p[0]),b=p.length>1?Integer.parseInt(p[1]):0,c=p.length>2?Integer.parseInt(p[2].replaceAll("[^0-9].*$","")):0;return a>ma||(a==ma&&(b>mi||(b==mi&&c>=pa)));}catch(Exception e){return false;}}
    private static final class DesktopTunnel {final String sessionId;final Long agentDbId;final String agentId;final ServerSocket listener;final long createdAt;volatile Socket guacdSocket;DesktopTunnel(String s,Long d,String a,ServerSocket l,long c){sessionId=s;agentDbId=d;agentId=a;listener=l;createdAt=c;}}
}
