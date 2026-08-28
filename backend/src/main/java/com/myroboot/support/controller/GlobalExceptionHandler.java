package com.myroboot.support.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", friendly(e.getMessage(), "提交的信息不完整，请检查后重试"));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleStatus(ResponseStatusException e) {
        HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
        if (status == null) status = HttpStatus.BAD_REQUEST;
        String fallback = status == HttpStatus.UNAUTHORIZED ? "登录已失效，请重新登录" :
                status == HttpStatus.FORBIDDEN ? "当前账号没有执行此操作的权限" : "操作失败，请稍后重试";
        return build(status, status.name(), friendly(e.getReason(), fallback));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnknown(Exception e) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "SERVER_ERROR", "系统暂时无法完成这个操作，请稍后重试；如果持续出现，请联系技术支持");
    }

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", code);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }

    private String friendly(String message, String fallback) {
        if (message == null || message.isBlank()) return fallback;
        return message.replaceAll("[\\r\\n]+", " ").trim();
    }
}
