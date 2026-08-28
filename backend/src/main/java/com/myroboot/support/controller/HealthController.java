package com.myroboot.support.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class HealthController {
    private final JdbcTemplate jdbcTemplate;
    private final Path uploadDir;

    public HealthController(JdbcTemplate jdbcTemplate, @Value("${support.upload-dir:/app/uploads}") String uploadDir) {
        this.jdbcTemplate = jdbcTemplate;
        this.uploadDir = Paths.get(uploadDir).toAbsolutePath().normalize();
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        boolean database = false;
        boolean storage = false;
        try {
            Integer value = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            database = value != null && value == 1;
        } catch (Exception ignored) {
        }
        try {
            Files.createDirectories(uploadDir);
            storage = Files.isDirectory(uploadDir) && Files.isWritable(uploadDir);
        } catch (Exception ignored) {
        }

        boolean up = database && storage;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", up ? "UP" : "DOWN");
        body.put("database", database ? "UP" : "DOWN");
        body.put("storage", storage ? "UP" : "DOWN");
        body.put("time", OffsetDateTime.now().toString());
        return ResponseEntity.status(up ? 200 : 503).body(body);
    }
}
