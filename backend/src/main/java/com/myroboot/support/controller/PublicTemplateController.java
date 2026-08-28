package com.myroboot.support.controller;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/templates")
public class PublicTemplateController {

    @GetMapping("/mine-users.xlsx")
    public ResponseEntity<byte[]> mineUserTemplate() throws Exception {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("煤矿用户导入");

            String[] headers = {"煤矿名称", "姓名", "手机", "邮箱", "账号", "密码"};
            Row header = sheet.createRow(0);
            CellStyle style = workbook.createCellStyle();
            Font font = workbook.createFont();
            font.setBold(true);
            style.setFont(font);
            style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            for (int i = 0; i < headers.length; i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(headers[i] + "（必填）");
                cell.setCellStyle(style);
                sheet.setColumnWidth(i, i == 0 || i == 3 ? 6500 : 4300);
            }

            Row sample = sheet.createRow(1);
            String[] values = {"XX煤矿", "张三", "13800000000", "zhangsan@example.com", "zhangsan", "12345678"};
            for (int i = 0; i < values.length; i++) sample.createCell(i).setCellValue(values[i]);

            Row note = sheet.createRow(3);
            note.createCell(0).setCellValue("说明：六项全部必填；密码至少 8 位；请勿修改表头。导入用户默认启用并使用客户角色。");
            sheet.addMergedRegion(new CellRangeAddress(3, 3, 0, 5));

            workbook.write(out);
            byte[] bytes = out.toByteArray();
            String encoded = URLEncoder.encode("煤矿用户导入模板.xlsx", StandardCharsets.UTF_8).replace("+", "%20");

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=mine-user-template.xlsx; filename*=UTF-8''" + encoded)
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .header("X-Content-Type-Options", "nosniff")
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .contentLength(bytes.length)
                    .body(bytes);
        }
    }
}
