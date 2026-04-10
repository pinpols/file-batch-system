package com.example.batch.console.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Comment;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.Drawing;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;

/**
 * 控制台 Excel 统一样式工具：所有 Excel 导出/模板共享同一视觉风格。
 * <p>
 * 普通表头：深蓝 #1F4E78；必填表头：橙色；只读表头：灰蓝；README 标题：14pt 加粗。
 */
public final class ConsoleExcelStyles {

    private static final byte[] HEADER_RGB = {0x1F, 0x4E, 0x78};
    private static final byte[] REQUIRED_HEADER_RGB = {(byte) 0xC6, 0x59, 0x11};
    private static final byte[] READ_ONLY_HEADER_RGB = {0x5B, 0x6B, 0x7A};

    private ConsoleExcelStyles() {
    }

    public record ColumnGuide(
            boolean required,
            boolean readOnly,
            String description,
            String formatHint,
            String example,
            List<String> allowedValues
    ) {
        public ColumnGuide {
            allowedValues = allowedValues == null ? List.of() : List.copyOf(allowedValues);
        }
    }

    public static ColumnGuide requiredColumn(String description, String formatHint, String example, String... allowedValues) {
        return new ColumnGuide(true, false, description, formatHint, example, List.of(allowedValues));
    }

    public static ColumnGuide optionalColumn(String description, String formatHint, String example, String... allowedValues) {
        return new ColumnGuide(false, false, description, formatHint, example, List.of(allowedValues));
    }

    public static ColumnGuide readOnlyColumn(String description, String formatHint, String example, String... allowedValues) {
        return new ColumnGuide(false, true, description, formatHint, example, List.of(allowedValues));
    }

    public static ColumnGuide requiredReadOnlyColumn(String description, String formatHint, String example, String... allowedValues) {
        return new ColumnGuide(true, true, description, formatHint, example, List.of(allowedValues));
    }

    /** 创建表头样式：深蓝底 + 白色加粗字 + 四边细线 + 居中。 */
    public static CellStyle createHeaderStyle(Workbook workbook) {
        return createHeaderStyle(workbook, HEADER_RGB);
    }

    /** 创建必填表头样式：橙底 + 白色加粗字。 */
    public static CellStyle createRequiredHeaderStyle(Workbook workbook) {
        return createHeaderStyle(workbook, REQUIRED_HEADER_RGB);
    }

    /** 创建只读表头样式：灰蓝底 + 白色加粗字。 */
    public static CellStyle createReadOnlyHeaderStyle(Workbook workbook) {
        return createHeaderStyle(workbook, READ_ONLY_HEADER_RGB);
    }

    private static CellStyle createHeaderStyle(Workbook workbook, byte[] rgb) {
        CellStyle style = workbook.createCellStyle();
        if (style instanceof XSSFCellStyle xssfStyle) {
            xssfStyle.setFillForegroundColor(new XSSFColor(rgb, null));
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

    /** 写通用表头行。 */
    public static void writeHeaders(Sheet sheet, List<String> columns, CellStyle headerStyle) {
        Row headerRow = sheet.createRow(0);
        headerRow.setHeightInPoints(22);
        for (int i = 0; i < columns.size(); i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(columns.get(i));
            cell.setCellStyle(headerStyle);
        }
    }

    /**
     * 写面向填写的模板表头：支持自动筛选、必填/只读样式以及表头批注提示。
     */
    public static void writeTemplateHeaders(Sheet sheet,
                                            List<String> columns,
                                            Map<String, ColumnGuide> guides,
                                            Workbook workbook) {
        Row headerRow = sheet.createRow(0);
        headerRow.setHeightInPoints(28);
        Map<String, ColumnGuide> safeGuides = guides == null ? Map.of() : guides;
        CellStyle defaultStyle = createHeaderStyle(workbook);
        CellStyle requiredStyle = createRequiredHeaderStyle(workbook);
        CellStyle readOnlyStyle = createReadOnlyHeaderStyle(workbook);
        CreationHelper creationHelper = workbook.getCreationHelper();
        Drawing<?> drawing = sheet.createDrawingPatriarch();

        for (int i = 0; i < columns.size(); i++) {
            String columnName = columns.get(i);
            ColumnGuide guide = safeGuides.get(columnName);
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(columnName);
            cell.setCellStyle(resolveTemplateHeaderStyle(guide, defaultStyle, requiredStyle, readOnlyStyle));
            addGuideCommentIfPresent(cell, guide, creationHelper, drawing);
        }
        if (!columns.isEmpty()) {
            sheet.setAutoFilter(new CellRangeAddress(0, 0, 0, columns.size() - 1));
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

    /**
     * 添加显式下拉校验，并在 Excel 中显示填写提示。
     */
    public static void addDropdownValidation(Sheet sheet,
                                             int columnIndex,
                                             String[] values,
                                             String promptTitle,
                                             String promptText) {
        DataValidationHelper helper = sheet.getDataValidationHelper();
        DataValidationConstraint constraint = helper.createExplicitListConstraint(values);
        CellRangeAddressList addressList = new CellRangeAddressList(1, 5000, columnIndex, columnIndex);
        DataValidation validation = helper.createValidation(constraint, addressList);
        validation.setSuppressDropDownArrow(false);
        validation.setShowErrorBox(true);
        validation.createErrorBox("输入不合法", "请从下拉列表中选择有效值。");
        if (hasText(promptTitle) || hasText(promptText)) {
            validation.createPromptBox(
                    hasText(promptTitle) ? promptTitle : "填写提示",
                    hasText(promptText) ? promptText : "请使用下拉列表中的可选值。");
            validation.setShowPromptBox(true);
        }
        sheet.addValidationData(validation);
    }

    public static void addBooleanValidation(Sheet sheet,
                                            int[] columns,
                                            String promptTitle,
                                            String promptText) {
        for (int columnIndex : columns) {
            addDropdownValidation(sheet, columnIndex, new String[]{"TRUE", "FALSE"}, promptTitle, promptText);
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

    private static CellStyle resolveTemplateHeaderStyle(ColumnGuide guide,
                                                        CellStyle defaultStyle,
                                                        CellStyle requiredStyle,
                                                        CellStyle readOnlyStyle) {
        if (guide == null) {
            return defaultStyle;
        }
        if (guide.readOnly()) {
            return readOnlyStyle;
        }
        if (guide.required()) {
            return requiredStyle;
        }
        return defaultStyle;
    }

    private static void addGuideCommentIfPresent(Cell cell,
                                                 ColumnGuide guide,
                                                 CreationHelper creationHelper,
                                                 Drawing<?> drawing) {
        String commentText = buildGuideCommentText(guide);
        if (!hasText(commentText)) {
            return;
        }
        ClientAnchor anchor = creationHelper.createClientAnchor();
        anchor.setCol1(cell.getColumnIndex());
        anchor.setCol2(cell.getColumnIndex() + 4);
        anchor.setRow1(cell.getRowIndex());
        anchor.setRow2(cell.getRowIndex() + 7);
        Comment comment = drawing.createCellComment(anchor);
        comment.setString(creationHelper.createRichTextString(commentText));
        comment.setAuthor("batch-console");
        cell.setCellComment(comment);
    }

    private static String buildGuideCommentText(ColumnGuide guide) {
        if (guide == null) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        if (hasText(guide.description())) {
            lines.add(guide.description().trim());
        }
        lines.add(guide.required() ? "是否必填：是" : "是否必填：否");
        lines.add(guide.readOnly() ? "是否可编辑：否，请保持导出值不变" : "是否可编辑：是");
        if (hasText(guide.formatHint())) {
            lines.add("格式：" + guide.formatHint().trim());
        }
        if (!guide.allowedValues().isEmpty()) {
            lines.add("下拉值：" + String.join(" / ", guide.allowedValues()));
        }
        if (hasText(guide.example())) {
            lines.add("示例：" + guide.example().trim());
        }
        return String.join("\n", lines);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
