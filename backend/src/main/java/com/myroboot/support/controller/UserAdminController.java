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
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/admin/users")
@CrossOrigin(origins = "*")
public class UserAdminController {
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private final JdbcTemplate jdbcTemplate;
    private final AuthService authService;

    public UserAdminController(JdbcTemplate jdbcTemplate, AuthService authService) {
        this.jdbcTemplate = jdbcTemplate;
        this.authService = authService;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestHeader(value = "Authorization", required = false) String authorization) {
        requireAdmin(authorization);
        return jdbcTemplate.queryForList("SELECT id,username,email,display_name,company_name,mine_name,phone,role,enabled,create_time,update_time FROM support_user ORDER BY id DESC");
    }

    @PostMapping
    public Map<String, Object> create(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @RequestBody Map<String, Object> body) {
        requireAdmin(authorization);
        String mineName = required(body, "mineName", "请填写煤矿名称");
        String displayName = required(body, "displayName", "请填写姓名");
        String phone = required(body, "phone", "请填写手机号");
        String email = required(body, "email", "请填写邮箱");
        String username = required(body, "username", "请填写账号");
        String password = required(body, "password", "请填写初始密码");
        validateEmail(email);
        String role = text(body.get("role"), "customer");
        validateRole(role);
        ensureUnique(username, email, null);
        jdbcTemplate.update("INSERT INTO support_user(username,email,password_hash,display_name,company_name,mine_name,phone,role,enabled) VALUES (?,?,?,?,?,?,?,?,?)",
                username, email, authService.encodePassword(password), displayName, mineName, mineName, phone, role, enabled(body.get("enabled")));
        return Map.of("success", true, "id", Objects.requireNonNull(jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class)));
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @PathVariable Long id, @RequestBody Map<String, Object> body) {
        requireAdmin(authorization);
        String mineName = required(body, "mineName", "请填写煤矿名称");
        String displayName = required(body, "displayName", "请填写姓名");
        String phone = required(body, "phone", "请填写手机号");
        String email = required(body, "email", "请填写邮箱");
        String username = required(body, "username", "请填写账号");
        validateEmail(email);
        String role = text(body.get("role"), "customer");
        validateRole(role);
        ensureUnique(username, email, id);
        String password = text(body.get("password"), "");
        int count;
        if (password.isBlank()) {
            count = jdbcTemplate.update("UPDATE support_user SET username=?,email=?,display_name=?,company_name=?,mine_name=?,phone=?,role=?,enabled=? WHERE id=?",
                    username, email, displayName, mineName, mineName, phone, role, enabled(body.get("enabled")), id);
        } else {
            count = jdbcTemplate.update("UPDATE support_user SET username=?,email=?,password_hash=?,display_name=?,company_name=?,mine_name=?,phone=?,role=?,enabled=? WHERE id=?",
                    username, email, authService.encodePassword(password), displayName, mineName, mineName, phone, role, enabled(body.get("enabled")), id);
        }
        return Map.of("success", count > 0);
    }

    @GetMapping({"/template", "/template.xlsx"})
    public ResponseEntity<byte[]> template() throws Exception {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("客户用户导入");
            String[] headers = {"煤矿名称", "姓名", "手机", "邮箱", "账号", "密码"};
            Row header = sheet.createRow(0);
            CellStyle headerStyle = workbook.createCellStyle();
            Font font = workbook.createFont();
            font.setBold(true);
            headerStyle.setFont(font);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(headers[i] + "（必填）");
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(i, i == 0 || i == 3 ? 6200 : 4200);
            }
            Row sample = sheet.createRow(1);
            String[] values = {"XX煤矿", "张三", "13800000000", "zhangsan@example.com", "zhangsan", "123456"};
            for (int i = 0; i < values.length; i++) sample.createCell(i).setCellValue(values[i]);
            Row note = sheet.createRow(3);
            note.createCell(0).setCellValue("说明：煤矿名称、姓名、手机、邮箱、账号、密码六项全部必填。导入用户默认为客户角色并启用。");
            sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(3, 3, 0, 5));
            workbook.write(out);
            byte[] bytes = out.toByteArray();
            String encoded = URLEncoder.encode("煤矿用户导入模板.xlsx", StandardCharsets.UTF_8).replace("+", "%20");
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=mine-user-template.xlsx; filename*=UTF-8''" + encoded)
                    .header(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate")
                    .header("X-Content-Type-Options", "nosniff")
                    .contentLength(bytes.length)
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(bytes);
        }
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> importUsers(@RequestHeader(value = "Authorization", required = false) String authorization,
                                           @RequestPart("file") MultipartFile file) {
        requireAdmin(authorization);
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("请选择要导入的 Excel 文件");
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!filename.endsWith(".xlsx") && !filename.endsWith(".xls")) throw new IllegalArgumentException("文件格式不正确，请上传 .xlsx 或 .xls 文件");

        DataFormatter formatter = new DataFormatter();
        int created = 0;
        int updated = 0;
        List<String> errors = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null || rowIsBlank(formatter, row, 6)) continue;
                String mine = cell(formatter, row, 0);
                String displayName = cell(formatter, row, 1);
                String phone = cell(formatter, row, 2);
                String email = cell(formatter, row, 3);
                String username = cell(formatter, row, 4);
                String password = cell(formatter, row, 5);

                List<String> missing = new ArrayList<>();
                if (mine.isBlank()) missing.add("煤矿名称");
                if (displayName.isBlank()) missing.add("姓名");
                if (phone.isBlank()) missing.add("手机");
                if (email.isBlank()) missing.add("邮箱");
                if (username.isBlank()) missing.add("账号");
                if (password.isBlank()) missing.add("密码");
                if (!missing.isEmpty()) {
                    errors.add("第" + (i + 1) + "行：缺少" + String.join("、", missing));
                    continue;
                }
                if (!EMAIL.matcher(email).matches()) {
                    errors.add("第" + (i + 1) + "行：邮箱格式不正确");
                    continue;
                }

                try {
                    List<Map<String, Object>> existing = jdbcTemplate.queryForList("SELECT id FROM support_user WHERE username=?", username);
                    if (!existing.isEmpty()) {
                        Long id = ((Number) existing.get(0).get("id")).longValue();
                        ensureUnique(username, email, id);
                        jdbcTemplate.update("UPDATE support_user SET email=?,password_hash=?,display_name=?,company_name=?,mine_name=?,phone=?,role='customer',enabled=1 WHERE id=?",
                                email, authService.encodePassword(password), displayName, mine, mine, phone, id);
                        updated++;
                    } else {
                        ensureUnique(username, email, null);
                        jdbcTemplate.update("INSERT INTO support_user(username,email,password_hash,display_name,company_name,mine_name,phone,role,enabled) VALUES (?,?,?,?,?,?,?,'customer',1)",
                                username, email, authService.encodePassword(password), displayName, mine, mine, phone);
                        created++;
                    }
                } catch (IllegalArgumentException e) {
                    errors.add("第" + (i + 1) + "行：" + e.getMessage());
                }
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Excel 文件读取失败，请重新下载模板后填写并导入");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", errors.isEmpty());
        result.put("created", created);
        result.put("updated", updated);
        result.put("errors", errors);
        return result;
    }

    @PutMapping("/{id}/enabled")
    public Map<String,Object> setEnabled(@RequestHeader(value="Authorization",required=false) String authorization,@PathVariable Long id,@RequestBody Map<String,Object> body) {
        requireAdmin(authorization); boolean value = Boolean.parseBoolean(String.valueOf(body.getOrDefault("enabled",true)));
        return Map.of("success", jdbcTemplate.update("UPDATE support_user SET enabled=? WHERE id=?", value ? 1 : 0, id) > 0);
    }

    private void ensureUnique(String username,String email,Long excludeId) {
        String suffix = excludeId == null ? "" : " AND id<>?";
        Integer uc = excludeId == null ? jdbcTemplate.queryForObject("SELECT COUNT(*) FROM support_user WHERE username=?",Integer.class,username) : jdbcTemplate.queryForObject("SELECT COUNT(*) FROM support_user WHERE username=?"+suffix,Integer.class,username,excludeId);
        if (uc != null && uc > 0) throw new IllegalArgumentException("账号已存在");
        Integer ec = excludeId == null ? jdbcTemplate.queryForObject("SELECT COUNT(*) FROM support_user WHERE email=?",Integer.class,email) : jdbcTemplate.queryForObject("SELECT COUNT(*) FROM support_user WHERE email=?"+suffix,Integer.class,email,excludeId);
        if (ec != null && ec > 0) throw new IllegalArgumentException("邮箱已被其他账号使用");
    }
    private void validateRole(String role) { if (!List.of("customer","admin").contains(role)) throw new IllegalArgumentException("角色设置不正确"); }
    private void validateEmail(String email) { if (!EMAIL.matcher(email).matches()) throw new IllegalArgumentException("邮箱格式不正确"); }
    private int enabled(Object value) { return value == null || Boolean.parseBoolean(String.valueOf(value)) ? 1 : 0; }
    private String required(Map<String,Object> body,String key,String msg){ String v=text(body.get(key),""); if(v.isBlank()) throw new IllegalArgumentException(msg); return v; }
    private String text(Object v,String d){ return v==null?d:String.valueOf(v).trim(); }
    private String cell(DataFormatter f,Row r,int i){ Cell c=r.getCell(i); return c==null?"":f.formatCellValue(c).trim(); }
    private boolean rowIsBlank(DataFormatter f, Row row, int count) { for (int i=0;i<count;i++) if (!cell(f,row,i).isBlank()) return false; return true; }
    private void requireAdmin(String authorization){ try{authService.requireAdmin(authorization);}catch(SecurityException e){throw new ResponseStatusException(HttpStatus.FORBIDDEN,e.getMessage());} }
}
