package com.myroboot.support.remote;

import com.myroboot.support.service.AuthService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

@Service
public class RemoteFileService {
    private static final int MAX_BASE64_CHARS = 70_000_000;
    private static final long TIMEOUT_SECONDS = 45;
    private static final List<Path> READ_ROOTS = List.of(Path.of("/home"), Path.of("/opt"), Path.of("/tmp"), Path.of("/var/log"));
    private static final List<Path> WRITE_ROOTS = List.of(Path.of("/home"), Path.of("/opt"), Path.of("/tmp"));
    private static final ConcurrentHashMap<String, CompletableFuture<Map<String,Object>>> WAIT = new ConcurrentHashMap<>();

    private final RemoteTerminalBroker broker;
    private final JdbcTemplate jdbc;

    public RemoteFileService(RemoteTerminalBroker broker, JdbcTemplate jdbc) {
        this.broker = broker;
        this.jdbc = jdbc;
    }

    public Map<String,Object> list(AuthService.Session admin, Long id, String path, String ip) {
        return call(admin, id, "file_list", normalize(path, false), null, false, ip);
    }
    public Map<String,Object> download(AuthService.Session admin, Long id, String path, String ip) {
        return call(admin, id, "file_download", normalize(path, false), null, false, ip);
    }
    public Map<String,Object> upload(AuthService.Session admin, Long id, String path, String data, boolean overwrite, String ip) {
        if (data == null || data.isBlank()) throw new IllegalArgumentException("上传内容不能为空");
        if (data.length() > MAX_BASE64_CHARS) throw new IllegalArgumentException("单文件暂限制 50MB");
        return call(admin, id, "file_upload", normalize(path, true), data, overwrite, ip);
    }
    public Map<String,Object> mkdir(AuthService.Session admin, Long id, String path, String ip) {
        return call(admin, id, "file_mkdir", normalize(path, true), null, false, ip);
    }

    private String normalize(String raw, boolean write) {
        if (raw == null || raw.isBlank()) raw = "/home";
        Path path;
        try { path = Path.of(raw).normalize(); }
        catch (Exception e) { throw new IllegalArgumentException("文件路径无效"); }
        if (!path.isAbsolute()) throw new IllegalArgumentException("必须使用绝对路径");
        List<Path> roots = write ? WRITE_ROOTS : READ_ROOTS;
        boolean allowed = roots.stream().anyMatch(path::startsWith);
        if (!allowed) {
            throw new IllegalArgumentException(write
                    ? "该路径禁止写入；仅允许写入 /home、/opt、/tmp"
                    : "该路径禁止远程访问；仅允许读取 /home、/opt、/tmp、/var/log");
        }
        return path.toString();
    }

    private Map<String,Object> call(AuthService.Session admin, Long id, String action, String path, String data, boolean overwrite, String ip) {
        if (admin == null || !"admin".equals(admin.role())) throw new SecurityException("只有管理员可以远程管理文件");
        List<Map<String,Object>> rows = jdbc.queryForList("SELECT agent_id,name,agent_version,enabled FROM remote_agent WHERE id=? LIMIT 1", id);
        if (rows.isEmpty()) throw new IllegalArgumentException("远程服务器不存在");
        Map<String,Object> row = rows.get(0);
        if (((Number)row.get("enabled")).intValue() != 1) throw new IllegalArgumentException("远程服务器已停用");
        String version = String.valueOf(row.getOrDefault("agent_version", ""));
        if (!supportsFiles(version)) throw new IllegalArgumentException("Agent 版本过低，请升级到 0.3.0 或更高版本后再使用文件管理");
        String agentId = String.valueOf(row.get("agent_id"));
        if (!broker.isAgentConnected(agentId)) throw new IllegalStateException("Agent 实时通道未连接，请检查 Agent 日志或等待重连");

        String requestId = UUID.randomUUID().toString();
        CompletableFuture<Map<String,Object>> future = new CompletableFuture<>();
        WAIT.put(requestId, future);
        try {
            boolean sent = switch (action) {
                case "file_list" -> broker.requestFileList(requestId, agentId, path);
                case "file_download" -> broker.requestFileDownload(requestId, agentId, path);
                case "file_upload" -> broker.requestFileUpload(requestId, agentId, path, data, overwrite);
                case "file_mkdir" -> broker.requestFileMkdir(requestId, agentId, path);
                default -> false;
            };
            if (!sent) throw new IllegalStateException("Agent 实时通道在操作过程中断开，请重试");
            Map<String,Object> result = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (Boolean.FALSE.equals(result.get("ok"))) throw new IllegalArgumentException(String.valueOf(result.getOrDefault("message", "文件操作失败")));
            audit(id, admin.userId(), action, path, ip);
            return result;
        } catch (TimeoutException e) {
            throw new IllegalStateException("Agent 在 45 秒内未返回文件操作结果；请检查 Agent 版本、实时通道和磁盘 I/O");
        } catch (ExecutionException e) {
            throw new IllegalStateException("文件操作失败", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("文件操作被中断");
        } catch (java.io.IOException e) {
            throw new IllegalStateException("向 Agent 发送文件指令失败", e);
        } finally {
            WAIT.remove(requestId);
        }
    }

    private void audit(Long agentId, Long userId, String action, String path, String ip) {
        String detail = action + " path=" + path;
        jdbc.update("INSERT INTO remote_audit_log(agent_id,user_id,action_type,detail,client_ip) VALUES (?,?,?,?,?)",
                agentId, userId, action, detail.substring(0, Math.min(detail.length(), 1000)), ip == null ? "" : ip.substring(0, Math.min(ip.length(), 100)));
    }

    private boolean supportsFiles(String version) {
        if (version == null) return false;
        String clean = version.trim().replaceFirst("^[vV]", "");
        String[] p = clean.split("\\.");
        try {
            int major = Integer.parseInt(p[0]);
            int minor = p.length > 1 ? Integer.parseInt(p[1]) : 0;
            return major > 0 || minor >= 3;
        } catch (Exception e) { return false; }
    }

    public static void complete(String requestId, Map<String,Object> payload) {
        CompletableFuture<Map<String,Object>> future = WAIT.get(requestId);
        if (future != null) future.complete(payload);
    }
}
