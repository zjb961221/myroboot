package com.myroboot.support.controller;

import com.myroboot.support.service.AuthService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/admin/logs")
public class AdminLogController {
    private static final int MAX_READ_BYTES = 1024 * 1024;
    private static final Pattern SECRET = Pattern.compile("(?i)(password|authorization|token|secret|app[-_ ]?password)(\\s*[=:]\\s*)([^\\s,;]+)");

    private final AuthService authService;
    private final Path logFile;

    public AdminLogController(AuthService authService, @Value("${logging.file.name:/app/logs/support.log}") String logFile) {
        this.authService = authService;
        this.logFile = Paths.get(logFile).toAbsolutePath().normalize();
    }

    @GetMapping
    public Map<String, Object> tail(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(defaultValue = "300") int lines) {
        requireAdmin(authorization);
        int limit = Math.max(50, Math.min(lines, 1000));
        List<String> result = readTail(limit);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("file", logFile.toString());
        response.put("exists", Files.exists(logFile));
        response.put("lines", result);
        response.put("count", result.size());
        response.put("readTime", java.time.LocalDateTime.now().toString());
        return response;
    }

    private List<String> readTail(int maxLines) {
        if (!Files.exists(logFile)) return List.of("日志文件尚未生成。请确认 backend 已按最新配置重新构建并启动。");
        try (RandomAccessFile raf = new RandomAccessFile(logFile.toFile(), "r")) {
            long length = raf.length();
            int bytes = (int) Math.min(length, MAX_READ_BYTES);
            byte[] buffer = new byte[bytes];
            raf.seek(length - bytes);
            raf.readFully(buffer);
            String text = new String(buffer, StandardCharsets.UTF_8);
            String[] all = text.split("\\R");
            int start = Math.max(0, all.length - maxLines);
            List<String> result = new ArrayList<>();
            for (int i = start; i < all.length; i++) result.add(redact(all[i]));
            return result;
        } catch (Exception e) {
            return List.of("读取日志失败：" + e.getMessage());
        }
    }

    private String redact(String line) {
        return SECRET.matcher(line).replaceAll("$1$2***");
    }

    private void requireAdmin(String authorization) {
        try {
            authService.requireAdmin(authorization);
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        }
    }
}
