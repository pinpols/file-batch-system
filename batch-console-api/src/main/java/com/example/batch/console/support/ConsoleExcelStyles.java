package com.example.batch.console.support;

import java.io.ByteArrayInputStream;
import java.time.Instant;
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
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** 普通表头深蓝 #1F4E78；必填橙色；只读灰蓝；README 标题 14pt 加粗。 */
public final class ConsoleExcelStyles {

  public static final MediaType XLSX_MEDIA_TYPE =
      MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

  private static final byte[] HEADER_RGB = {0x1F, 0x4E, 0x78};
  private static final byte[] REQUIRED_HEADER_RGB = {(byte) 0xC6, 0x59, 0x11};
  private static final byte[] READ_ONLY_HEADER_RGB = {0x5B, 0x6B, 0x7A};

  /** 模板/导出 workbook 4 类辅助 sheet 的统一中文名,所有写入器走这里,避免英中混用。 */
  public static final String SHEET_NAME_README = "说明";

  public static final String SHEET_NAME_GUIDE = "填写说明";
  public static final String SHEET_NAME_DICT = "字典";
  public static final String SHEET_NAME_VALIDATION = "校验";

  private ConsoleExcelStyles() {}

  public static ResponseEntity<InputStreamResource> excelResponse(
      byte[] workbookBytes, String fileNamePrefix, String tenantId) {
    String fileName =
        fileNamePrefix + "-" + tenantId + "-" + Instant.now().toEpochMilli() + ".xlsx";
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(fileName).build().toString())
        .contentType(XLSX_MEDIA_TYPE)
        .body(new InputStreamResource(new ByteArrayInputStream(workbookBytes)));
  }

  public static ResponseEntity<InputStreamResource> templateResponse(
      byte[] workbookBytes, String templateFileName) {
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(templateFileName).build().toString())
        .contentType(XLSX_MEDIA_TYPE)
        .body(new InputStreamResource(new ByteArrayInputStream(workbookBytes)));
  }

  public record ColumnGuide(
      boolean required,
      boolean readOnly,
      String description,
      String formatHint,
      String example,
      List<String> allowedValues) {
    public ColumnGuide {
      allowedValues = allowedValues == null ? List.of() : List.copyOf(allowedValues);
    }
  }

  public static ColumnGuide requiredColumn(
      String description, String formatHint, String example, String... allowedValues) {
    return new ColumnGuide(true, false, description, formatHint, example, List.of(allowedValues));
  }

  public static ColumnGuide optionalColumn(
      String description, String formatHint, String example, String... allowedValues) {
    return new ColumnGuide(false, false, description, formatHint, example, List.of(allowedValues));
  }

  public static ColumnGuide readOnlyColumn(
      String description, String formatHint, String example, String... allowedValues) {
    return new ColumnGuide(false, true, description, formatHint, example, List.of(allowedValues));
  }

  public static ColumnGuide requiredReadOnlyColumn(
      String description, String formatHint, String example, String... allowedValues) {
    return new ColumnGuide(true, true, description, formatHint, example, List.of(allowedValues));
  }

  public static CellStyle createHeaderStyle(Workbook workbook) {
    return createHeaderStyle(workbook, HEADER_RGB);
  }

  public static CellStyle createRequiredHeaderStyle(Workbook workbook) {
    return createHeaderStyle(workbook, REQUIRED_HEADER_RGB);
  }

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
      xssfFont.setColor(new XSSFColor(new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF}, null));
    } else {
      font.setColor(IndexedColors.WHITE.getIndex());
    }
    style.setFont(font);
    return style;
  }

  public static CellStyle createDataStyle(Workbook workbook) {
    CellStyle style = workbook.createCellStyle();
    style.setBorderTop(BorderStyle.THIN);
    style.setBorderBottom(BorderStyle.THIN);
    style.setBorderLeft(BorderStyle.THIN);
    style.setBorderRight(BorderStyle.THIN);
    style.setVerticalAlignment(VerticalAlignment.CENTER);
    return style;
  }

  public static CellStyle createRequiredMarkStyle(Workbook workbook) {
    return createFilledDataStyle(
        workbook,
        new byte[] {(byte) 0xFF, (byte) 0xE0, (byte) 0xB2},
        IndexedColors.LIGHT_ORANGE.getIndex());
  }

  public static CellStyle createOptionalMarkStyle(Workbook workbook) {
    return createFilledDataStyle(
        workbook,
        new byte[] {(byte) 0xE8, (byte) 0xF5, (byte) 0xE9},
        IndexedColors.LIGHT_GREEN.getIndex());
  }

  private static CellStyle createFilledDataStyle(
      Workbook workbook, byte[] rgb, short fallbackIndex) {
    CellStyle style = createDataStyle(workbook);
    if (style instanceof XSSFCellStyle xssfStyle) {
      xssfStyle.setFillForegroundColor(new XSSFColor(rgb, null));
    } else {
      style.setFillForegroundColor(fallbackIndex);
    }
    style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
    return style;
  }

  public static void writeHeaders(Sheet sheet, List<String> columns, CellStyle headerStyle) {
    Row headerRow = sheet.createRow(0);
    headerRow.setHeightInPoints(22);
    for (int i = 0; i < columns.size(); i++) {
      Cell cell = headerRow.createCell(i);
      cell.setCellValue(columns.get(i));
      cell.setCellStyle(headerStyle);
    }
  }

  public static void writeTemplateHeaders(
      Sheet sheet, List<String> columns, Map<String, ColumnGuide> guides, Workbook workbook) {
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
      cell.setCellStyle(
          resolveTemplateHeaderStyle(guide, defaultStyle, requiredStyle, readOnlyStyle));
      addGuideCommentIfPresent(cell, guide, creationHelper, drawing);
    }
    if (!columns.isEmpty()) {
      sheet.setAutoFilter(new CellRangeAddress(0, 0, 0, columns.size() - 1));
    }
  }

  public static void writeCell(Row row, int columnIndex, Object value) {
    writeCell(row, columnIndex, value, null);
  }

  public static void writeCell(Row row, int columnIndex, Object value, CellStyle style) {
    Cell cell = row.createCell(columnIndex);
    if (value == null) {
      cell.setCellValue("");
    } else if (value instanceof Number number) {
      cell.setCellValue(number.doubleValue());
    } else if (value instanceof Boolean bool) {
      cell.setCellValue(bool);
    } else {
      cell.setCellValue(escapeFormula(String.valueOf(value)));
    }
    if (style != null) {
      cell.setCellStyle(style);
    }
  }

  /**
   * 防止 Excel 公式注入（CSV Injection / Formula Injection）。
   *
   * <p>以 {@code = + - @} 开头的单元格值会被 Excel 解析为公式；加前缀 {@code '} 强制按文本处理。所有写 Cell 字符串的入口都应过这层。
   */
  public static String escapeFormula(String value) {
    if (value == null || value.isEmpty()) {
      return value;
    }
    char first = value.charAt(0);
    if (first == '=' || first == '+' || first == '-' || first == '@') {
      return "'" + value;
    }
    return value;
  }

  public static void setWidths(Sheet sheet, List<String> columns) {
    for (int i = 0; i < columns.size(); i++) {
      sheet.setColumnWidth(i, Math.min(12000, Math.max(18, columns.get(i).length() + 4) * 256));
    }
  }

  /**
   * 设置 README / 说明类单列 sheet 的标准列宽（约 65 个字符宽度）。
   *
   * <p>替代散落各处的硬编码 {@code sheet.setColumnWidth(0, 16000)} / {@code 18000}。
   */
  public static void setReadmeColumnWidth(Sheet sheet) {
    sheet.setColumnWidth(0, 16000);
  }

  /**
   * 设置 GUIDE / 填写说明类三列 sheet 的标准列宽:列名 / 类型 / 说明。
   *
   * <p>替代散落各处的硬编码 {@code 24*256 / 20*256 / 36*256} 三件套。
   */
  public static void setGuideColumnWidths(Sheet sheet) {
    sheet.setColumnWidth(0, 24 * 256);
    sheet.setColumnWidth(1, 20 * 256);
    sheet.setColumnWidth(2, 36 * 256);
  }

  /** 数据校验下拉默认作用行数:从第 2 行(数据区起点)到第 5001 行;批量导入超 5000 行需手动扩。 */
  public static final int DEFAULT_DROPDOWN_MAX_ROW = 5000;

  public static void addDropdownValidation(
      Sheet sheet, int columnIndex, String[] values, String promptTitle, String promptText) {
    addDropdownValidation(
        sheet, columnIndex, values, promptTitle, promptText, DEFAULT_DROPDOWN_MAX_ROW);
  }

  /**
   * 加下拉数据校验,作用范围 = 第 2 行 ~ 第 {@code maxRow} 行(包含)。
   *
   * <p>单 sheet 数据量超 5000 行时调用方应显式传 {@code maxRow},否则尾部行不会触发校验。
   */
  public static void addDropdownValidation(
      Sheet sheet,
      int columnIndex,
      String[] values,
      String promptTitle,
      String promptText,
      int maxRow) {
    DataValidationHelper helper = sheet.getDataValidationHelper();
    DataValidationConstraint constraint = helper.createExplicitListConstraint(values);
    CellRangeAddressList addressList =
        new CellRangeAddressList(1, Math.max(1, maxRow), columnIndex, columnIndex);
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

  public static void addBooleanValidation(
      Sheet sheet, int[] columns, String promptTitle, String promptText) {
    for (int columnIndex : columns) {
      addDropdownValidation(
          sheet, columnIndex, new String[] {"TRUE", "FALSE"}, promptTitle, promptText);
    }
  }

  public static CellStyle createReadmeTitleStyle(Workbook workbook) {
    CellStyle style = workbook.createCellStyle();
    Font font = workbook.createFont();
    font.setBold(true);
    font.setFontHeightInPoints((short) 14);
    style.setFont(font);
    return style;
  }

  public static void createValidationSheet(Workbook workbook) {
    Sheet sheet = workbook.createSheet(SHEET_NAME_VALIDATION);
    sheet.createFreezePane(0, 1, 0, 1);
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

  private static CellStyle resolveTemplateHeaderStyle(
      ColumnGuide guide, CellStyle defaultStyle, CellStyle requiredStyle, CellStyle readOnlyStyle) {
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

  private static void addGuideCommentIfPresent(
      Cell cell, ColumnGuide guide, CreationHelper creationHelper, Drawing<?> drawing) {
    String commentText = buildGuideCommentText(guide);
    if (!hasText(commentText)) {
      return;
    }
    attachStandardComment(cell, commentText, creationHelper, drawing);
  }

  /**
   * 标准 cell comment anchor:从单元格自身开始,4 列 × 7 行的弹出框。够装 6-8 行中文 + 列表说明而不被裁。所有 createCellComment /
   * setCellComment 入口都应过这层,避免不同 sheet 出现尺寸不一的悬浮框。
   */
  public static void attachStandardComment(
      Cell cell, String commentText, CreationHelper creationHelper, Drawing<?> drawing) {
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
