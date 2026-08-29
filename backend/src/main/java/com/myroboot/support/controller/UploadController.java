package com.myroboot.support.controller;

import com.myroboot.support.service.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class UploadController {
    private static final Logger log = LoggerFactory.getLogger(UploadController.class);
    private static final Set<String> ATTACHMENT_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "webp", "bmp",
            "pdf", "txt", "log", "csv", "json",
            "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "zip", "rar", "7z",
            "mp4", "mov", "avi", "mkv", "webm"
    );
    private static final Set<String> VIDEO_EXTENSIONS = Set.of("mp4", "mov", "avi", "mkv", "webm");

    private final AuthService authService;
    private final JdbcTemplate jdbcTemplate;
    private final Path uploadDir;

    public UploadController(AuthService authService, JdbcTemplate jdbcTemplate,
                            @Value("${support.upload-dir:/app/uploads}") String uploadDir) throws IOException {
        this.authService = authService;
        this.jdbcTemplate = jdbcTemplate;
        this.uploadDir = Paths.get(uploadDir).toAbsolutePath().normalize();
        Files.createDirectories(this.uploadDir);
    }

    @PostMapping(value = "/upload/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> uploadImage(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam("file") MultipartFile file) throws IOException {
        AuthService.Session session = requireUser(authorization);
        if (file.isEmpty()) throw new IllegalArgumentException("请选择要上传的图片");
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) throw new IllegalArgumentException("这里只能上传图片文件");
        return save(session, file, 10 * 1024 * 1024L);
    }

    @PostMapping(value = "/upload/attachment", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> uploadAttachment(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam("file") MultipartFile file) throws IOException {
        AuthService.Session session = requireUser(authorization);
        if (file.isEmpty()) throw new IllegalArgumentException("请选择要上传的附件");
        String original = file.getOriginalFilename() == null ? "attachment" : file.getOriginalFilename();
        String ext = extension(original);
        if (ext.isEmpty() || !ATTACHMENT_EXTENSIONS.contains(ext)) {
            throw new IllegalArgumentException("不支持该文件格式，可上传图片、Office、PDF、日志、压缩包和常见视频文件");
        }
        long maxSize = VIDEO_EXTENSIONS.contains(ext) ? 200 * 1024 * 1024L : 30 * 1024 * 1024L;
        if (file.getSize() > maxSize) {
            throw new IllegalArgumentException(VIDEO_EXTENSIONS.contains(ext) ? "单个视频不能超过 200MB" : "单个附件不能超过 30MB");
        }
        return save(session, file, maxSize);
    }

    private Map<String, Object> save(AuthService.Session session, MultipartFile file, long maxSize) throws IOException {
        if (file.getSize() > maxSize) throw new IllegalArgumentException("文件大小超过限制");
        String original = file.getOriginalFilename() == null ? "file" : file.getOriginalFilename();
        String ext = extension(original);
        String filename = UUID.randomUUID() + (ext.isEmpty() ? "" : "." + ext);
        Path target = uploadDir.resolve(filename).normalize();
        if (!target.startsWith(uploadDir)) throw new IllegalArgumentException("文件名不合法");
        file.transferTo(target);
        jdbcTemplate.update(
                "INSERT INTO upload_staging(storage_name,user_id,expires_time) VALUES (?,?,?) " +
                        "ON DUPLICATE KEY UPDATE user_id=VALUES(user_id),expires_time=VALUES(expires_time)",
                filename, session.userId(), Timestamp.valueOf(LocalDateTime.now().plusHours(24))
        );
        log.info("FILE_UPLOADED storageName={} userId={} size={} extension={}", filename, session.userId(), file.getSize(), ext);
        return Map.of(
                "url", "/api/uploads/" + filename,
                "name", original,
                "contentType", file.getContentType() == null ? "application/octet-stream" : file.getContentType(),
                "size", file.getSize(),
                "video", VIDEO_EXTENSIONS.contains(ext)
        );
    }

    @GetMapping("/uploads/{filename:.+}")
    public ResponseEntity<Resource> serve(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String filename) throws IOException {
        AuthService.Session session = requireUser(authorization);
        Path file = uploadDir.resolve(filename).normalize();
        if (!file.startsWith(uploadDir) || !Files.exists(file) || !Files.isRegularFile(file)) {
            return ResponseEntity.notFound().build();
        }
        if (!canRead(session, filename)) {
            log.warn("FILE_ACCESS_DENIED storageName={} userId={}", filename, session.userId());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前账号无权查看该附件");
        }
        Resource resource = new UrlResource(file.toUri());
        String contentType = Files.probeContentType(file);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .contentType(contentType == null ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(contentType))
                .body(resource);
    }

    private boolean canRead(AuthService.Session session, String filename) {
        if ("admin".equals(session.role())) return true;
        String url = "/api/uploads/" + filename;

        if (exists("SELECT COUNT(*) FROM upload_staging WHERE storage_name=? AND user_id=? AND expires_time>NOW()",
                filename, session.userId())) return true;

        if (exists("SELECT COUNT(*) FROM faq_attachment a JOIN faq f ON f.id=a.faq_id WHERE a.file_url=? AND f.enabled=1", url)) return true;
        if (exists("SELECT COUNT(*) FROM faq_image i JOIN faq f ON f.id=i.faq_id WHERE i.image_url=? AND f.enabled=1", url)) return true;

        if (exists("SELECT COUNT(*) FROM support_ticket WHERE screenshot_url=? AND user_id=? AND is_deleted=0", url, session.userId())) return true;
        if (exists("SELECT COUNT(*) FROM ticket_attachment a JOIN support_ticket t ON t.id=a.ticket_id WHERE a.file_url=? AND t.user_id=? AND t.is_deleted=0",
                url, session.userId())) return true;
        return exists("SELECT COUNT(*) FROM ticket_history_attachment a " +
                        "JOIN support_ticket t ON t.id=a.ticket_id " +
                        "JOIN ticket_history h ON h.id=a.history_id " +
                        "WHERE a.file_url=? AND t.user_id=? AND t.is_deleted=0 AND h.visible_to_customer=1",
                url, session.userId());
    }

    private boolean exists(String sql, Object... args) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return count != null && count > 0;
    }

    private AuthService.Session requireUser(String authorization) {
        try {
            return authService.require(authorization);
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, e.getMessage());
        }
    }

    private String extension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
