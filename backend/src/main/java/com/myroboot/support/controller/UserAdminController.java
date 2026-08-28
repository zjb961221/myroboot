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
        return jdbcTemplate.queryForList("SELECT id,username,email,display_name,company_name,mine_name,phone,role,enabled,create_time,update_time FROM support_user ORDER BY id DESC");
    }

    @PostMapping
    public Map<String, Object> create(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @RequestBody Map<String, Object> body) {
        requireAdmin(authorization);
        String username = required(body, "username", "用户名不能为空");
        String password = required(body, "password", "初始密码不能为空");
        String role = text(body.get("role"), "customer");
        validateRole(role);
        ensureUnique(username, text(body.get("email"), ""), null);
        jdbcTemplate.update("INSERT INTO support_user(username,email,password_hash,display_name,company_name,mine_name,phone,role,enabled) VALUES (?,?,?,?,?,?,?,?,?)",
                username, nullable(body.get("email")), authService.encodePassword(password), body.get("displayName"), body.get("companyName"), body.get("mineName"), body.get("phone"), role, enabled(body.get("enabled")));
        return Map.of("success", true, "id", Objects.requireNonNull(jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class)));
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @PathVariable Long id, @RequestBody Map<String, Object> body) {
        requireAdmin(authorization);
        String username = required(body, "username", "用户名不能为空");
        String role = text(body.get("role"), "customer");
        validateRole(role);
        ensureUnique(username, text(body.get("email"), ""), id);
        String password = text(body.get("password"), "");
        int count;
        if (password.isBlank()) {
            count = jdbcTemplate.update("UPDATE support_user SET username=?,email=?,display_name=?,company_name=?,mine_name=?,phone=?,role=?,enabled=? WHERE id=?",
                    username, nullable(body.get("email")), body.get("displayName"), body.get("companyName"), body.get("mineName"), body.get("phone"), role, enabled(body.get("enabled")), id);
        } else {
            count = jdbcTemplate.update("UPDATE support_user SET username=?,email=?,password_hash=?,display_name=?,company_name=?,mine_name=?,phone=?,role=?,enabled=? WHERE id=?",
                    username, nullable(body.get("email")), authService.encodePassword(password), body.get("displayName"), body.get("companyName"), body.get("mineName"), body.get("phone"), role, enabled(body.get("enabled")), id);
        }
        return Map.of("success", count > 0);
    }

    @GetMapping("/template")
    public ResponseEntity<byte[]> template(@RequestHeader(value = "Authorization", required = false) String authorization) throws Exception {
        requireAdmin(authorization);
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("用户导入");
            String[] headers = {"用户名", "邮箱", "姓名", "单位", "矿井", "手机号", "初始密码", "角色", "启用"};
            Row header = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) { header.createCell(i).setCellValue(headers[i]); sheet.setColumnWidth(i, i == 3 || i == 4 ? 5200 : 3600); }
            Row sample = sheet.createRow(1);
            String[] values = {"nx001", "user@example.com", "张三", "XX公司", "XX煤矿", "13800000000", "123456", "customer", "是"};
            for (int i = 0; i < values.length; i++) sample.createCell(i).setCellValue(values[i]);
            workbook.write(out);
            String name = URLEncoder.encode("用户导入模板.xlsx", StandardCharsets.UTF_8).replace("+", "%20");
            return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + name)
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).body(out.toByteArray());
        }
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> importUsers(@RequestHeader(value = "Authorization", required = false) String authorization,
                                           @RequestPart("file") MultipartFile file) throws Exception {
        requireAdmin(authorization);
        if (file.isEmpty()) throw new IllegalArgumentException("请选择 Excel 文件");
        DataFormatter formatter = new DataFormatter(); int created = 0; int updated = 0; List<String> errors = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i); if (row == null) continue;
                String username = cell(formatter,row,0); if (username.isBlank()) continue;
                String email = cell(formatter,row,1), displayName = cell(formatter,row,2), company = cell(formatter,row,3), mine = cell(formatter,row,4), phone = cell(formatter,row,5), password = cell(formatter,row,6), role = cell(formatter,row,7), enabledText = cell(formatter,row,8);
                if (role.isBlank()) role = "customer";
                if (!List.of("customer","admin").contains(role)) { errors.add("第" + (i+1) + "行：角色只能是 customer 或 admin"); continue; }
                int enabled = enabledText.equals("否") || enabledText.equals("0") || enabledText.equalsIgnoreCase("false") ? 0 : 1;
                List<Map<String,Object>> existing = jdbcTemplate.queryForList("SELECT id FROM support_user WHERE username=?", username);
                try {
                    if (!existing.isEmpty()) {
                        Long id = ((Number)existing.get(0).get("id")).longValue(); ensureUnique(username,email,id);
                        if (password.isBlank()) jdbcTemplate.update("UPDATE support_user SET email=?,display_name=?,company_name=?,mine_name=?,phone=?,role=?,enabled=? WHERE id=?", nullable(email),displayName,company,mine,phone,role,enabled,id);
                        else jdbcTemplate.update("UPDATE support_user SET email=?,password_hash=?,display_name=?,company_name=?,mine_name=?,phone=?,role=?,enabled=? WHERE id=?", nullable(email),authService.encodePassword(password),displayName,company,mine,phone,role,enabled,id);
                        updated++;
                    } else {
                        if (password.isBlank()) { errors.add("第" + (i+1) + "行：新用户必须填写初始密码"); continue; }
                        ensureUnique(username,email,null);
                        jdbcTemplate.update("INSERT INTO support_user(username,email,password_hash,display_name,company_name,mine_name,phone,role,enabled) VALUES (?,?,?,?,?,?,?,?,?)", username,nullable(email),authService.encodePassword(password),displayName,company,mine,phone,role,enabled); created++;
                    }
                } catch (IllegalArgumentException e) { errors.add("第" + (i+1) + "行：" + e.getMessage()); }
            }
        }
        Map<String,Object> result = new LinkedHashMap<>(); result.put("success",errors.isEmpty()); result.put("created",created); result.put("updated",updated); result.put("errors",errors); return result;
    }

    @PutMapping("/{id}/enabled")
    public Map<String,Object> setEnabled(@RequestHeader(value="Authorization",required=false) String authorization,@PathVariable Long id,@RequestBody Map<String,Object> body) {
        requireAdmin(authorization); boolean value = Boolean.parseBoolean(String.valueOf(body.getOrDefault("enabled",true)));
        return Map.of("success", jdbcTemplate.update("UPDATE support_user SET enabled=? WHERE id=?", value ? 1 : 0, id) > 0);
    }

    private void ensureUnique(String username,String email,Long excludeId) {
        String suffix = excludeId == null ? "" : " AND id<>?";
        Integer uc = excludeId == null ? jdbcTemplate.queryForObject("SELECT COUNT(*) FROM support_user WHERE username=?",Integer.class,username) : jdbcTemplate.queryForObject("SELECT COUNT(*) FROM support_user WHERE username=?"+suffix,Integer.class,username,excludeId);
        if (uc != null && uc > 0) throw new IllegalArgumentException("用户名已存在");
        if (email != null && !email.isBlank()) {
            Integer ec = excludeId == null ? jdbcTemplate.queryForObject("SELECT COUNT(*) FROM support_user WHERE email=?",Integer.class,email) : jdbcTemplate.queryForObject("SELECT COUNT(*) FROM support_user WHERE email=?"+suffix,Integer.class,email,excludeId);
            if (ec != null && ec > 0) throw new IllegalArgumentException("邮箱已存在");
        }
    }
    private void validateRole(String role) { if (!List.of("customer","admin").contains(role)) throw new IllegalArgumentException("角色只能是 customer 或 admin"); }
    private int enabled(Object value) { return value == null || Boolean.parseBoolean(String.valueOf(value)) ? 1 : 0; }
    private String required(Map<String,Object> body,String key,String msg){ String v=text(body.get(key),""); if(v.isBlank()) throw new IllegalArgumentException(msg); return v; }
    private String text(Object v,String d){ return v==null?d:String.valueOf(v).trim(); }
    private Object nullable(Object v){ String s=text(v,""); return s.isBlank()?null:s; }
    private String cell(DataFormatter f,Row r,int i){ Cell c=r.getCell(i); return c==null?"":f.formatCellValue(c).trim(); }
    private void requireAdmin(String authorization){ try{authService.requireAdmin(authorization);}catch(SecurityException e){throw new ResponseStatusException(HttpStatus.FORBIDDEN,e.getMessage());} }
}
