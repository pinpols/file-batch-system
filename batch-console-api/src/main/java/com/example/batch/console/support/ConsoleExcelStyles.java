package com.example.batch.console.support;

import java.util.List;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;

/**
 * 控制台 Excel 统一样式工具：所有 Excel 导出/模板共享同一视觉风格。
 * <p>
 * 表头：深蓝 #1F4E78 + 白色加粗 + 四边细线 + 居中<br>
 * 数据单元格：无背景 + 四边细线<br>
 * README 标题：14pt 加粗
 */
public final class ConsoleExcelStyles {

    private static final byte[] HEADER_RGB = {0x1F, 0x4E, 0x78};

    private ConsoleExcelStyles() {
    }

    /** 创建表头样式：深蓝底 + 白色加粗字 + 四边细线 + 居中。 */
    public static CellStyle createHeaderStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        if (style instanceof XSSFCellStyle xssfStyle) {
            xssfStyle.setFillForegroundColor(new XSSFColor(HEADER_RGB, null));
        } else {
            style.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        }
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        Font font = workbook.createFont();
        font.setBold(true);
        if (font instanceof XSSFFont xssfFont) {
            xssfFont.setColor(new XSSFColor(new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF}, null));
        } else {
            font.setColor(IndexedColors.WHITE.getIndex());
        }
        style.setFont(font);
        return style;
    }

    /** 创建数据单元格样式：四边细线。 */
    public static CellStyle createDataStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        return style;
    }

    /** 写表头行。 */
    public static void writeHeaders(Sheet sheet, List<String> columns, CellStyle headerStyle) {
        Row headerRow = sheet.createRow(0);
        headerRow.setHeightInPoints(22);
        for (int i = 0; i < columns.size(); i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(columns.get(i));
            cell.setCellStyle(headerStyle);
        }
    }

    /** 写单个单元格。 */
    public static void writeCell(Row row, int columnIndex, Object value) {
        writeCell(row, columnIndex, value, null);
    }

    /** 写单个单元格（带样式）。 */
    public static void writeCell(Row row, int columnIndex, Object value, CellStyle style) {
        Cell cell = row.createCell(columnIndex);
        if (value == null) {
            cell.setCellValue("");
        } else if (value instanceof Number number) {
            cell.setCellValue(number.doubleValue());
        } else if (value instanceof Boolean bool) {
            cell.setCellValue(bool);
        } else {
            cell.setCellValue(String.valueOf(value));
        }
        if (style != null) {
            cell.setCellStyle(style);
        }
    }

    /** 自适应列宽（基于表头长度）。 */
    public static void setWidths(Sheet sheet, List<String> columns) {
        for (int i = 0; i < columns.size(); i++) {
            sheet.setColumnWidth(i, Math.min(12000, Math.max(18, columns.get(i).length() + 4) * 256));
        }
    }

    /** 创建 README 标题行样式：14pt 加粗。 */
    public static CellStyle createReadmeTitleStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 14);
        style.setFont(font);
        return style;
    }

    /** 创建 VALIDATION sheet 标准结构。 */
    public static void createValidationSheet(Workbook workbook) {
        Sheet sheet = workbook.createSheet("VALIDATION");
        sheet.createFreezePane(0, 1);
        CellStyle headerStyle = createHeaderStyle(workbook);
        Row header = sheet.createRow(0);
        header.setHeightInPoints(22);
        String[] cols = {"sheet_name", "row_no", "column_name", "error_reason"};
        for (int i = 0; i < cols.length; i++) {
            Cell cell = header.createCell(i);
            cell.setCellValue(cols[i]);
            cell.setCellStyle(headerStyle);
        }
        sheet.setColumnWidth(0, 20 * 256);
        sheet.setColumnWidth(1, 12 * 256);
        sheet.setColumnWidth(2, 32 * 256);
        sheet.setColumnWidth(3, 50 * 256);
    }
}
