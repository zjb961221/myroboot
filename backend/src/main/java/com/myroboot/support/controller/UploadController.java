package com.myroboot.support.controller;

import com.myroboot.support.service.AuthService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class UploadController {
    private static final Set<String> ATTACHMENT_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "webp", "bmp",
            "pdf", "txt", "log", "csv", "json",
            "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "zip", "rar", "7z",
            "mp4", "mov", "avi", "mkv", "webm"
    );
    private static final Set<String> VIDEO_EXTENSIONS = Set.of("mp4", "mov", "avi", "mkv", "webm");

    private final AuthService authService;
    private final Path uploadDir;

    public UploadController(AuthService authService, @Value("${support.upload-dir:/app/uploads}") String uploadDir) throws IOException {
        this.authService = authService;
        this.uploadDir = Paths.get(uploadDir).toAbsolutePath().normalize();
        Files.createDirectories(this.uploadDir);
    }

    @PostMapping(value = "/upload/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> uploadImage(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam("file") MultipartFile file) throws IOException {
        authService.require(authorization);
        if (file.isEmpty()) throw new IllegalArgumentException("请选择要上传的图片");
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) throw new IllegalArgumentException("这里只能上传图片文件");
        return save(file, 10 * 1024 * 1024L);
    }

    @PostMapping(value = "/upload/attachment", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> uploadAttachment(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam("file") MultipartFile file) throws IOException {
        authService.require(authorization);
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
        return save(file, maxSize);
    }

    private Map<String, Object> save(MultipartFile file, long maxSize) throws IOException {
        if (file.getSize() > maxSize) throw new IllegalArgumentException("文件大小超过限制");
        String original = file.getOriginalFilename() == null ? "file" : file.getOriginalFilename();
        String ext = extension(original);
        String filename = UUID.randomUUID() + (ext.isEmpty() ? "" : "." + ext);
        Path target = uploadDir.resolve(filename).normalize();
        if (!target.startsWith(uploadDir)) throw new IllegalArgumentException("文件名不合法");
        file.transferTo(target);
        return Map.of(
                "url", "/api/uploads/" + filename,
                "name", original,
                "contentType", file.getContentType() == null ? "application/octet-stream" : file.getContentType(),
                "size", file.getSize(),
                "video", VIDEO_EXTENSIONS.contains(ext)
        );
    }

    private String extension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    @GetMapping("/uploads/{filename:.+}")
    public ResponseEntity<Resource> serve(@PathVariable String filename) throws IOException {
        Path file = uploadDir.resolve(filename).normalize();
        if (!file.startsWith(uploadDir) || !Files.exists(file)) return ResponseEntity.notFound().build();
        Resource resource = new UrlResource(file.toUri());
        String contentType = Files.probeContentType(file);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .contentType(contentType == null ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(contentType))
                .body(resource);
    }
}
