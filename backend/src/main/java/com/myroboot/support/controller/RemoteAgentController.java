package com.myroboot.support.controller;

import com.myroboot.support.service.AuthService;
import com.myroboot.support.service.RemoteAgentService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
public class RemoteAgentController {
    private final AuthService authService;
    private final RemoteAgentService remoteAgentService;

    public RemoteAgentController(AuthService authService, RemoteAgentService remoteAgentService) {
        this.authService = authService;
        this.remoteAgentService = remoteAgentService;
    }

    @GetMapping("/api/admin/remote/agents")
    public List<Map<String,Object>> list(@RequestHeader(value="Authorization", required=false) String authorization) {
        return remoteAgentService.list(requireAdmin(authorization));
    }

    @PostMapping("/api/admin/remote/agents")
    public Map<String,Object> create(@RequestHeader(value="Authorization", required=false) String authorization,
                                     @RequestBody Map<String,Object> body,
                                     HttpServletRequest request) {
        return remoteAgentService.create(requireAdmin(authorization), body, clientIp(request));
    }

    @PostMapping("/api/admin/remote/agents/{id}/rotate-token")
    public Map<String,Object> rotate(@RequestHeader(value="Authorization", required=false) String authorization,
                                     @PathVariable Long id,
                                     HttpServletRequest request) {
        return remoteAgentService.rotateToken(requireAdmin(authorization), id, clientIp(request));
    }

    @PutMapping("/api/admin/remote/agents/{id}/enabled")
    public Map<String,Object> enabled(@RequestHeader(value="Authorization", required=false) String authorization,
                                      @PathVariable Long id,
                                      @RequestBody Map<String,Object> body,
                                      HttpServletRequest request) {
        boolean enabled = Boolean.TRUE.equals(body.get("enabled")) || "true".equalsIgnoreCase(String.valueOf(body.get("enabled"))) || "1".equals(String.valueOf(body.get("enabled")));
        remoteAgentService.setEnabled(requireAdmin(authorization), id, enabled, clientIp(request));
        return Map.of("ok", true);
    }

    @GetMapping("/api/admin/remote/audit")
    public List<Map<String,Object>> audit(@RequestHeader(value="Authorization", required=false) String authorization,
                                          @RequestParam(defaultValue="100") int limit) {
        return remoteAgentService.auditLogs(requireAdmin(authorization), limit);
    }

    @PostMapping("/api/remote/agent/heartbeat")
    public Map<String,Object> heartbeat(@RequestHeader(value="X-Agent-Id", required=false) String agentId,
                                        @RequestHeader(value="X-Agent-Token", required=false) String token,
                                        @RequestBody(required=false) Map<String,Object> body) {
        try {
            return remoteAgentService.heartbeat(agentId, token, body == null ? Map.of() : body);
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, e.getMessage());
        }
    }

    private AuthService.Session requireAdmin(String authorization) {
        try {
            return authService.requireAdmin(authorization);
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        }
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) return forwarded.split(",")[0].trim();
        return request.getRemoteAddr();
    }
}
