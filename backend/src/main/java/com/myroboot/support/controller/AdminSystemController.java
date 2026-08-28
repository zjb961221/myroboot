package com.myroboot.support.controller;

import com.myroboot.support.service.AuthService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.lang.management.ManagementFactory;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/system")
public class AdminSystemController {
    private final AuthService authService;
    private final JdbcTemplate jdbcTemplate;
    private final Path uploadDir;

    @Value("${spring.mail.host:}") private String mailHost;
    @Value("${spring.mail.port:0}") private int mailPort;
    @Value("${spring.mail.username:}") private String mailUsername;

    public AdminSystemController(AuthService authService, JdbcTemplate jdbcTemplate,
                                 @Value("${support.upload-dir:/app/uploads}") String uploadDir) {
        this.authService = authService;
        this.jdbcTemplate = jdbcTemplate;
        this.uploadDir = Paths.get(uploadDir).toAbsolutePath().normalize();
    }

    @GetMapping
    public Map<String, Object> status(@RequestHeader(value = "Authorization", required = false) String authorization) {
        requireAdmin(authorization);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("database", databaseStatus());
        result.put("storage", storageStatus());
        result.put("mail", mailStatus());
        result.put("jvm", jvmStatus());
        return result;
    }

    private Map<String, Object> databaseStatus() {
        Map<String, Object> value = new LinkedHashMap<>();
        long start = System.nanoTime();
        try {
            Integer ping = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            value.put("status", ping != null && ping == 1 ? "UP" : "DOWN");
            value.put("latencyMs", Duration.ofNanos(System.nanoTime() - start).toMillis());
            value.put("version", jdbcTemplate.queryForObject("SELECT VERSION()", String.class));
        } catch (Exception e) {
            value.put("status", "DOWN");
            value.put("message", "数据库连接失败");
        }
        return value;
    }

    private Map<String, Object> storageStatus() {
        Map<String, Object> value = new LinkedHashMap<>();
        try {
            Files.createDirectories(uploadDir);
            FileStore store = Files.getFileStore(uploadDir);
            value.put("status", Files.isWritable(uploadDir) ? "UP" : "READ_ONLY");
            value.put("path", uploadDir.toString());
            value.put("usableBytes", store.getUsableSpace());
            value.put("totalBytes", store.getTotalSpace());
        } catch (Exception e) {
            value.put("status", "DOWN");
            value.put("path", uploadDir.toString());
        }
        return value;
    }

    private Map<String, Object> mailStatus() {
        Map<String, Object> value = new LinkedHashMap<>();
        boolean configured = mailHost != null && !mailHost.isBlank() && mailUsername != null && !mailUsername.isBlank();
        value.put("configured", configured);
        value.put("host", mailHost == null ? "" : mailHost);
        value.put("port", mailPort);
        value.put("username", maskEmail(mailUsername));
        return value;
    }

    private Map<String, Object> jvmStatus() {
        Runtime runtime = Runtime.getRuntime();
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("javaVersion", System.getProperty("java.version"));
        value.put("uptimeSeconds", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
        value.put("processors", runtime.availableProcessors());
        value.put("maxMemoryBytes", runtime.maxMemory());
        value.put("totalMemoryBytes", runtime.totalMemory());
        value.put("freeMemoryBytes", runtime.freeMemory());
        return value;
    }

    private String maskEmail(String email) {
        if (email == null || email.isBlank()) return "";
        int at = email.indexOf('@');
        if (at <= 1) return "***" + (at >= 0 ? email.substring(at) : "");
        return email.charAt(0) + "***" + email.substring(at);
    }

    private void requireAdmin(String authorization) {
        try {
            authService.requireAdmin(authorization);
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        }
    }
}
