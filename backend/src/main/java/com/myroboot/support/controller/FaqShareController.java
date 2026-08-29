package com.myroboot.support.controller;

import com.myroboot.support.service.AuthService;
import com.myroboot.support.service.FaqShareService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class FaqShareController {
    private static final String SHARE_COOKIE = "support_faq_share";
    private static final String SHARE_COOKIE_PATH = "/api/public/faq-share";

    private final AuthService authService;
    private final FaqShareService shareService;
    private final Path uploadDir;
    private final boolean secureCookie;

    public FaqShareController(AuthService authService,
                              FaqShareService shareService,
                              @Value("${support.upload-dir:/app/uploads}") String uploadDir,
                              @Value("${support.auth.cookie-secure:false}") boolean secureCookie) throws IOException {
        this.authService = authService;
        this.shareService = shareService;
        this.uploadDir = Paths.get(uploadDir).toAbsolutePath().normalize();
        this.secureCookie = secureCookie;
        Files.createDirectories(this.uploadDir);
    }

    @PostMapping("/faqs/{faqId}/shares")
    public Map<String, Object> createShare(@RequestHeader(value = "Authorization", required = false) String authorization,
                                           @PathVariable Long faqId,
                                           @RequestBody(required = false) Map<String, Object> body) {
        Integer hours = null;
        if (body != null && body.get("hours") != null) {
            Object raw = body.get("hours");
            try { hours = raw instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(raw)); }
            catch (NumberFormatException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分享有效期格式不正确"); }
        }
        try { return shareService.create(requireUser(authorization), faqId, hours); }
        catch (SecurityException e) { throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage()); }
        catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage()); }
    }

    @GetMapping("/faqs/{faqId}/shares")
    public List<Map<String, Object>> listShares(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                 @PathVariable Long faqId) {
        try { return shareService.list(requireUser(authorization), faqId); }
        catch (SecurityException e) { throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage()); }
    }

    @DeleteMapping("/faqs/{faqId}/shares/{shareId}")
    public Map<String, Object> revokeShare(@RequestHeader(value = "Authorization", required = false) String authorization,
                                            @PathVariable Long faqId,
                                            @PathVariable Long shareId) {
        try { return Map.of("success", shareService.revoke(requireUser(authorization), faqId, shareId)); }
        catch (SecurityException e) { throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage()); }
    }

    @PostMapping("/public/faq-share/open")
    public Map<String, Object> openShare(@RequestHeader(value = "X-Share-Token", required = false) String shareToken,
                                         HttpServletResponse response) {
        try {
            FaqShareService.ShareAccess access = shareService.open(shareToken);
            long seconds = Math.max(1, ChronoUnit.SECONDS.between(LocalDateTime.now(), access.expiresTime()));
            ResponseCookie cookie = ResponseCookie.from(SHARE_COOKIE, shareToken)
                    .httpOnly(true).secure(secureCookie).sameSite("Lax").path(SHARE_COOKIE_PATH)
                    .maxAge(Duration.ofSeconds(seconds)).build();
            response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
            return Map.of("success", true, "expiresTime", access.expiresTime());
        } catch (SecurityException e) {
            clearShareCookie(response);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "分享链接不存在或已失效");
        }
    }

    @GetMapping("/public/faq-share")
    public Map<String, Object> sharedFaq(@CookieValue(value = SHARE_COOKIE, required = false) String shareToken) {
        try { return shareService.sharedFaq(shareService.requireValid(shareToken)); }
        catch (SecurityException e) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "分享链接不存在或已失效"); }
    }

    @GetMapping("/public/faq-share/images/{imageId}")
    public ResponseEntity<Resource> sharedImage(@CookieValue(value = SHARE_COOKIE, required = false) String shareToken,
                                                 @PathVariable Long imageId) throws IOException {
        try {
            FaqShareService.ShareAccess access = shareService.requireValid(shareToken);
            return serveFile(shareService.imageUrl(access, imageId), "image", null);
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "图片不存在或分享已失效");
        }
    }

    @GetMapping("/public/faq-share/attachments/{attachmentId}")
    public ResponseEntity<Resource> sharedAttachment(@CookieValue(value = SHARE_COOKIE, required = false) String shareToken,
                                                      @PathVariable Long attachmentId) throws IOException {
        try {
            FaqShareService.ShareAccess access = shareService.requireValid(shareToken);
            Map<String, Object> attachment = shareService.attachment(access, attachmentId);
            return serveFile(String.valueOf(attachment.get("file_url")),
                    String.valueOf(attachment.getOrDefault("original_name", "attachment")),
                    String.valueOf(attachment.getOrDefault("content_type", "")));
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "附件不存在或分享已失效");
        }
    }

    private ResponseEntity<Resource> serveFile(String storedUrl, String originalName, String recordedContentType) throws IOException {
        String filename = storageName(storedUrl);
        Path file = uploadDir.resolve(filename).normalize();
        if (!file.startsWith(uploadDir) || !Files.exists(file) || !Files.isRegularFile(file)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文件不存在");
        Resource resource = new UrlResource(file.toUri());
        String contentType = recordedContentType;
        if (contentType == null || contentType.isBlank() || "null".equals(contentType)) contentType = Files.probeContentType(file);
        MediaType mediaType;
        try { mediaType = contentType == null ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(contentType); }
        catch (Exception e) { mediaType = MediaType.APPLICATION_OCTET_STREAM; }
        String disposition = ContentDisposition.inline().filename(originalName, StandardCharsets.UTF_8).build().toString();
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes").contentType(mediaType).body(resource);
    }

    private String storageName(String url) {
        if (url == null || url.isBlank()) throw new SecurityException("文件路径无效");
        String normalized = url.trim();
        int slash = normalized.lastIndexOf('/');
        String filename = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        if (filename.isBlank() || filename.contains("..") || filename.contains("/") || filename.contains("\\")) throw new SecurityException("文件路径无效");
        return filename;
    }

    private AuthService.Session requireUser(String authorization) {
        try { return authService.require(authorization); }
        catch (SecurityException e) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, e.getMessage()); }
    }

    private void clearShareCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(SHARE_COOKIE, "").httpOnly(true).secure(secureCookie)
                .sameSite("Lax").path(SHARE_COOKIE_PATH).maxAge(Duration.ZERO).build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
