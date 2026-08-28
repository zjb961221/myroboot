package com.myroboot.support.controller;

import com.myroboot.support.service.AuthService;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/api/admin/users")
@CrossOrigin(origins = "*")
public class UserAdminController {
    private final JdbcTemplate jdbcTemplate;
    private final AuthService authService;

    public UserAdminController(JdbcTemplate jdbcTemplate, AuthService authService) {
        this.jdbcTemplate = jdbcTemplate;
        this.authService = authService;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestHeader(value = "Authorization", required = false) String authorization) {
        requireAdmin(authorization);
        return jdbcTemplate.queryForList(
                "SELECT id, username, display_name, company_name, mine_name, phone, role, enabled, create_time, update_time " +
                        "FROM support_user ORDER BY id DESC"
        );
    }

    @GetMapping("/template")
    public ResponseEntity<byte[]> template(@RequestHeader(value = "Authorization", required = false) String authorization) throws Exception {
        requireAdmin(authorization);
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("用户导入");
            String[] headers = {"用户名", "姓名", "单位", "矿井", "手机号", "初始密码", "角色", "启用"};
            Row header = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                header.createCell(i).setCellValue(headers[i]);
                sheet.setColumnWidth(i, i == 2 || i == 3 ? 5200 : 3600);
            }
            Row sample = sheet.createRow(1);
            String[] values = {"nx001", "张三", "XX公司", "XX煤矿", "13800000000", "123456", "customer", "是"};
            for (int i = 0; i < values.length; i++) sample.createCell(i).setCellValue(values[i]);
            workbook.write(out);
            String name = URLEncoder.encode("用户导入模板.xlsx", StandardCharsets.UTF_8).replace("+", "%20");
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + name)
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(out.toByteArray());
        }
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> importUsers(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestPart("file") MultipartFile file) throws Exception {
        requireAdmin(authorization);
        if (file.isEmpty()) throw new IllegalArgumentException("请选择 Excel 文件");
        DataFormatter formatter = new DataFormatter();
        int created = 0;
        int updated = 0;
        List<String> errors = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                String username = cell(formatter, row, 0);
                if (username.isBlank()) continue;
                String displayName = cell(formatter, row, 1);
                String company = cell(formatter, row, 2);
                String mine = cell(formatter, row, 3);
                String phone = cell(formatter, row, 4);
                String password = cell(formatter, row, 5);
                String role = cell(formatter, row, 6);
                String enabledText = cell(formatter, row, 7);
                if (role.isBlank()) role = "customer";
                if (!List.of("customer", "admin").contains(role)) {
                    errors.add("第" + (i + 1) + "行：角色只能是 customer 或 admin");
                    continue;
                }
                int enabled = enabledText.equals("否") || enabledText.equals("0") || enabledText.equalsIgnoreCase("false") ? 0 : 1;
                Integer exists = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM support_user WHERE username = ?", Integer.class, username);
                if (exists != null && exists > 0) {
                    if (password.isBlank()) {
                        jdbcTemplate.update("UPDATE support_user SET display_name=?, company_name=?, mine_name=?, phone=?, role=?, enabled=? WHERE username=?",
                                displayName, company, mine, phone, role, enabled, username);
                    } else {
                        jdbcTemplate.update("UPDATE support_user SET password_hash=?, display_name=?, company_name=?, mine_name=?, phone=?, role=?, enabled=? WHERE username=?",
                                authService.encodePassword(password), displayName, company, mine, phone, role, enabled, username);
                    }
                    updated++;
                } else {
                    if (password.isBlank()) {
                        errors.add("第" + (i + 1) + "行：新用户必须填写初始密码");
                        continue;
                    }
                    jdbcTemplate.update("INSERT INTO support_user(username,password_hash,display_name,company_name,mine_name,phone,role,enabled) VALUES (?,?,?,?,?,?,?,?)",
                            username, authService.encodePassword(password), displayName, company, mine, phone, role, enabled);
                    created++;
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", errors.isEmpty());
        result.put("created", created);
        result.put("updated", updated);
        result.put("errors", errors);
        return result;
    }

    @PutMapping("/{id}/enabled")
    public Map<String, Object> setEnabled(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        requireAdmin(authorization);
        boolean enabled = Boolean.parseBoolean(String.valueOf(body.getOrDefault("enabled", true)));
        int updated = jdbcTemplate.update("UPDATE support_user SET enabled=? WHERE id=?", enabled ? 1 : 0, id);
        return Map.of("success", updated > 0);
    }

    private String cell(DataFormatter formatter, Row row, int index) {
        Cell cell = row.getCell(index);
        return cell == null ? "" : formatter.formatCellValue(cell).trim();
    }

    private void requireAdmin(String authorization) {
        try {
            authService.requireAdmin(authorization);
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        }
    }
}
