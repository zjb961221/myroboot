package com.myroboot.support.controller;

import com.myroboot.support.service.AuthService;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/faq/attachments")
@CrossOrigin(origins = "*")
public class FaqAttachmentPreviewController {
    private final JdbcTemplate jdbcTemplate;
    private final AuthService authService;
    private final Path uploadDir;

    public FaqAttachmentPreviewController(JdbcTemplate jdbcTemplate,
                                          AuthService authService,
                                          @Value("${support.upload-dir:/app/uploads}") String uploadDir) {
        this.jdbcTemplate = jdbcTemplate;
        this.authService = authService;
        this.uploadDir = Paths.get(uploadDir).toAbsolutePath().normalize();
    }

    @GetMapping("/{id}/preview")
    public Map<String, Object> preview(@RequestHeader(value = "Authorization", required = false) String authorization,
                                       @PathVariable Long id) {
        requireUser(authorization);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT a.file_url,a.original_name,a.content_type,a.file_size FROM faq_attachment a " +
                        "JOIN faq f ON f.id=a.faq_id WHERE a.id=? AND f.enabled=1 LIMIT 1", id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "附件不存在");

        Map<String, Object> file = rows.get(0);
        String url = String.valueOf(file.get("file_url"));
        String name = String.valueOf(file.get("original_name"));
        Path path = localPath(url);
        if (!Files.exists(path)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "附件文件不存在");

        String lower = name.toLowerCase(Locale.ROOT);
        try {
            if (lower.endsWith(".xls") || lower.endsWith(".xlsx")) return previewExcel(path, name);
            if (lower.endsWith(".txt") || lower.endsWith(".log") || lower.endsWith(".json") || lower.endsWith(".csv")) {
                return previewText(path, name);
            }
            return Map.of("type", "download", "name", name);
        } catch (Exception e) {
            throw new IllegalArgumentException("该附件暂时无法生成预览，可直接下载查看");
        }
    }

    private Map<String, Object> previewExcel(Path path, String name) throws Exception {
        List<List<String>> data = new ArrayList<>();
        DataFormatter formatter = new DataFormatter();
        try (Workbook workbook = WorkbookFactory.create(path.toFile())) {
            if (workbook.getNumberOfSheets() == 0) return Map.of("type", "excel", "name", name, "rows", data);
            Sheet sheet = workbook.getSheetAt(0);
            int lastRow = Math.min(sheet.getLastRowNum(), 19);
            for (int r = 0; r <= lastRow; r++) {
                Row row = sheet.getRow(r);
                List<String> values = new ArrayList<>();
                if (row != null) {
                    int lastCell = Math.min(Math.max(row.getLastCellNum(), 0), 12);
                    for (int c = 0; c < lastCell; c++) {
                        Cell cell = row.getCell(c);
                        values.add(cell == null ? "" : formatter.formatCellValue(cell));
                    }
                }
                data.add(values);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "excel");
        result.put("name", name);
        result.put("rows", data);
        result.put("note", "仅预览第 1 个工作表前 20 行、前 12 列");
        return result;
    }

    private Map<String, Object> previewText(Path path, String name) throws Exception {
        byte[] bytes = Files.readAllBytes(path);
        int limit = Math.min(bytes.length, 30 * 1024);
        String content = new String(bytes, 0, limit, StandardCharsets.UTF_8);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "text");
        result.put("name", name);
        result.put("content", content);
        result.put("truncated", bytes.length > limit);
        return result;
    }

    private Path localPath(String url) {
        String prefix = "/api/uploads/";
        if (!url.startsWith(prefix)) throw new IllegalArgumentException("附件地址不合法");
        String filename = url.substring(prefix.length());
        Path path = uploadDir.resolve(filename).normalize();
        if (!path.startsWith(uploadDir)) throw new IllegalArgumentException("附件地址不合法");
        return path;
    }

    private void requireUser(String authorization) {
        try { authService.require(authorization); }
        catch (SecurityException e) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, e.getMessage()); }
    }
}
