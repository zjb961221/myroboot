package com.myroboot.support.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class UploadCleanupService {
    private static final Logger log = LoggerFactory.getLogger(UploadCleanupService.class);

    private final JdbcTemplate jdbcTemplate;
    private final Path uploadDir;
    private final Duration gracePeriod;

    public UploadCleanupService(JdbcTemplate jdbcTemplate,
                                @Value("${support.upload-dir:/app/uploads}") String uploadDir,
                                @Value("${support.upload-cleanup-grace-hours:24}") long graceHours) {
        this.jdbcTemplate = jdbcTemplate;
        this.uploadDir = Paths.get(uploadDir).toAbsolutePath().normalize();
        this.gracePeriod = Duration.ofHours(Math.max(1, graceHours));
    }

    @Scheduled(
            initialDelayString = "${support.upload-cleanup-initial-delay-ms:600000}",
            fixedDelayString = "${support.upload-cleanup-delay-ms:86400000}"
    )
    public void cleanupOrphanUploads() {
        if (!Files.isDirectory(uploadDir)) return;
        Set<String> referenced = loadReferencedUrls();
        Instant threshold = Instant.now().minus(gracePeriod);
        int deleted = 0;
        int kept = 0;
        try (var stream = Files.list(uploadDir)) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                try {
                    String url = "/api/uploads/" + file.getFileName();
                    if (referenced.contains(url)) {
                        kept++;
                        continue;
                    }
                    Instant modified = Files.getLastModifiedTime(file).toInstant();
                    if (modified.isAfter(threshold)) {
                        kept++;
                        continue;
                    }
                    Files.deleteIfExists(file);
                    deleted++;
                } catch (Exception e) {
                    log.warn("UPLOAD_CLEANUP failed file={}: {}", file.getFileName(), rootMessage(e));
                }
            }
            int expiredStaging = expireStagingRecords();
            if (deleted > 0 || expiredStaging > 0) {
                log.info("UPLOAD_CLEANUP deletedFiles={} expiredStaging={} kept={} graceHours={}",
                        deleted, expiredStaging, kept, gracePeriod.toHours());
            } else {
                log.debug("UPLOAD_CLEANUP no orphan files found; kept={}", kept);
            }
        } catch (Exception e) {
            log.warn("UPLOAD_CLEANUP scan failed: {}", rootMessage(e));
        }
    }

    private int expireStagingRecords() {
        try {
            return jdbcTemplate.update("DELETE FROM upload_staging WHERE expires_time <= NOW()");
        } catch (Exception e) {
            log.warn("UPLOAD_CLEANUP staging cleanup failed: {}", rootMessage(e));
            return 0;
        }
    }

    private Set<String> loadReferencedUrls() {
        String sql = "SELECT screenshot_url AS url FROM support_ticket WHERE screenshot_url IS NOT NULL AND screenshot_url<>'' " +
                "UNION SELECT file_url FROM ticket_attachment " +
                "UNION SELECT image_url FROM faq_image " +
                "UNION SELECT file_url FROM faq_attachment " +
                "UNION SELECT file_url FROM ticket_history_attachment";
        try {
            List<String> urls = jdbcTemplate.queryForList(sql, String.class);
            return new HashSet<>(urls);
        } catch (Exception e) {
            // 如果数据库引用关系读取失败，宁可不删文件，也不要冒险误删。
            log.warn("UPLOAD_CLEANUP skipped because references could not be loaded: {}", rootMessage(e));
            return new ProtectiveSet();
        }
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    /** 数据库异常时 contains 永远返回 true，确保清理任务进入 fail-safe 模式。 */
    private static class ProtectiveSet extends HashSet<String> {
        @Override
        public boolean contains(Object o) {
            return true;
        }
    }
}
