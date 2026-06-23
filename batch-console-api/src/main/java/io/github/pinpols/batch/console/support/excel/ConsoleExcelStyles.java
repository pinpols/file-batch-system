package io.github.pinpols.batch.console.support.excel;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
import org.springframework.context.MessageSource;
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
  public static final String SHEET_NAME_README = "填写说明";

  public static final String SHEET_NAME_GUIDE = "字段说明";
  public static final String SHEET_NAME_FIELD_GUIDE = "字段说明";
  public static final String SHEET_NAME_DICT = "字典";
  public static final String SHEET_NAME_VALIDATION = "校验";

  private ConsoleExcelStyles() {}

  public static ResponseEntity<InputStreamResource> excelResponse(
      byte[] workbookBytes, String fileNamePrefix, String tenantId, String timestamp) {
    String fileName = fileNamePrefix + "-" + tenantId + "-" + timestamp + ".xlsx";
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
    writeTemplateHeaders(sheet, columns, guides, workbook, null, null);
  }

  /**
   * 多语言版本:guide 的 description / formatHint / example / allowedValues 中以 {@code "excel."} 开头的值会被当做
   * i18n key,通过 {@code messageSource} + {@code locale} 解析;其他值按字面量保留。{@code messageSource} 为 null
   * 时退化为 老行为(无 i18n)。
   */
  public static void writeTemplateHeaders(
      Sheet sheet,
      List<String> columns,
      Map<String, ColumnGuide> guides,
      Workbook workbook,
      MessageSource messageSource,
      Locale locale) {
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
      ColumnGuide guide = resolveGuide(safeGuides.get(columnName), messageSource, locale);
      Cell cell = headerRow.createCell(i);
      cell.setCellValue(columnName);
      cell.setCellStyle(
          resolveTemplateHeaderStyle(guide, defaultStyle, requiredStyle, readOnlyStyle));
      addGuideCommentIfPresent(cell, guide, creationHelper, drawing, messageSource, locale);
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
    DropdownValidationSpec spec =
        DropdownValidationSpec.builder()
            .values(values)
            .promptTitle(promptTitle)
            .promptText(promptText)
            .maxRow(DEFAULT_DROPDOWN_MAX_ROW)
            .build();
    addDropdownValidation(sheet, columnIndex, spec);
  }

  public static void addDropdownValidation(
      Sheet sheet,
      int columnIndex,
      String[] values,
      String promptTitle,
      String promptText,
      MessageSource messageSource,
      Locale locale) {
    DropdownValidationSpec spec =
        DropdownValidationSpec.builder()
            .values(values)
            .promptTitle(promptTitle)
            .promptText(promptText)
            .maxRow(DEFAULT_DROPDOWN_MAX_ROW)
            .messageSource(messageSource)
            .locale(locale)
            .build();
    addDropdownValidation(sheet, columnIndex, spec);
  }

  /**
   * 加下拉数据校验,作用范围 = 第 2 行 ~ 第 {@code maxRow} 行(包含)。
   *
   * <p>单 sheet 数据量超 5000 行时调用方应显式传 {@code maxRow},否则尾部行不会触发校验。{@code promptTitle / promptText} 以
   * {@code "excel."} 开头会被当 i18n key 解析;内置的"输入不合法/请从下拉列表中选择有效值"等默认提示在 {@code messageSource} 非 null
   * 时按 locale 翻译。
   */
  public static void addDropdownValidation(
      Sheet sheet,
      int columnIndex,
      String[] values,
      String promptTitle,
      String promptText,
      int maxRow) {
    DropdownValidationSpec spec =
        DropdownValidationSpec.builder()
            .values(values)
            .promptTitle(promptTitle)
            .promptText(promptText)
            .maxRow(maxRow)
            .build();
    addDropdownValidation(sheet, columnIndex, spec);
  }

  public static void addDropdownValidation(
      Sheet sheet, int columnIndex, DropdownValidationSpec spec) {
    DataValidationHelper helper = sheet.getDataValidationHelper();
    DataValidationConstraint constraint = helper.createExplicitListConstraint(spec.values());
    CellRangeAddressList addressList =
        new CellRangeAddressList(1, Math.max(1, spec.maxRow()), columnIndex, columnIndex);
    DataValidation validation = helper.createValidation(constraint, addressList);
    // POI 5.x 的 setSuppressDropDownArrow 实现与方法名相反:传 false 会输出
    // showDropDown="true"(OOXML spec 该值 = 隐藏下拉箭头) → Excel 不显示下拉。
    // 必须传 true 才会 setShowDropDown(false) → 箭头显示。POI bug 54440 至今未修。
    validation.setSuppressDropDownArrow(true);
    validation.setShowErrorBox(true);
    MessageSource ms = spec.messageSource();
    Locale loc = spec.locale();
    validation.createErrorBox(
        localize(ms, loc, "excel.dropdown.error_title", "输入不合法"),
        localize(ms, loc, "excel.dropdown.error_box", "请从下拉列表中选择有效值。"));
    String resolvedTitle = localizeIfKey(ms, loc, spec.promptTitle());
    String resolvedText = localizeIfKey(ms, loc, spec.promptText());
    if (hasText(resolvedTitle) || hasText(resolvedText)) {
      validation.createPromptBox(
          hasText(resolvedTitle)
              ? resolvedTitle
              : localize(ms, loc, "excel.dropdown.prompt_title", "填写提示"),
          hasText(resolvedText)
              ? resolvedText
              : localize(ms, loc, "excel.dropdown.prompt_box", "请使用下拉列表中的可选值。"));
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

  public static void createFieldGuideSheet(
      Workbook workbook,
      String dataSheetName,
      List<String> columns,
      Map<String, ColumnGuide> guides) {
    createFieldGuideSheet(workbook, Map.of(dataSheetName, columns), Map.of(dataSheetName, guides));
  }

  public static void createFieldGuideSheet(
      Workbook workbook,
      Map<String, List<String>> sheetColumns,
      Map<String, Map<String, ColumnGuide>> sheetGuides) {
    Sheet sheet = workbook.createSheet(SHEET_NAME_FIELD_GUIDE);
    sheet.createFreezePane(0, 1, 0, 1);
    CellStyle headerStyle = createHeaderStyle(workbook);
    List<String> headers =
        List.of(
            "sheet_name",
            "field_name",
            "required",
            "editable",
            "format",
            "example",
            "allowed_values",
            "default_value",
            "description");
    writeHeaders(sheet, headers, headerStyle);
    int rowIndex = 1;
    for (Map.Entry<String, List<String>> entry : sheetColumns.entrySet()) {
      String sheetName = entry.getKey();
      Map<String, ColumnGuide> guides = sheetGuides.getOrDefault(sheetName, Map.of());
      for (String column : entry.getValue()) {
        ColumnGuide guide = guides.get(column);
        Row row = sheet.createRow(rowIndex++);
        writeCell(row, 0, sheetName);
        writeCell(row, 1, column);
        writeCell(row, 2, guide != null && guide.required() ? "是" : "否");
        writeCell(row, 3, guide != null && guide.readOnly() ? "否" : "是");
        writeCell(row, 4, guide == null ? "" : guide.formatHint());
        writeCell(row, 5, guide == null ? "" : guide.example());
        writeCell(row, 6, guide == null ? "" : String.join(" / ", guide.allowedValues()));
        writeCell(row, 7, "");
        writeCell(row, 8, guide == null ? "" : guide.description());
      }
    }
    int[] widths = {20, 32, 12, 12, 18, 36, 44, 18, 60};
    for (int i = 0; i < widths.length; i++) {
      sheet.setColumnWidth(i, widths[i] * 256);
    }
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
      Cell cell,
      ColumnGuide guide,
      CreationHelper creationHelper,
      Drawing<?> drawing,
      MessageSource messageSource,
      Locale locale) {
    String commentText = buildGuideCommentText(guide, messageSource, locale);
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

  private static String buildGuideCommentText(
      ColumnGuide guide, MessageSource messageSource, Locale locale) {
    if (guide == null) {
      return null;
    }
    List<String> lines = new ArrayList<>();
    if (hasText(guide.description())) {
      lines.add(guide.description().trim());
    }
    lines.add(
        guide.required()
            ? localize(messageSource, locale, "excel.guide.required.yes", "是否必填：是")
            : localize(messageSource, locale, "excel.guide.required.no", "是否必填：否"));
    lines.add(
        guide.readOnly()
            ? localize(messageSource, locale, "excel.guide.editable.no", "是否可编辑：否，请保持导出值不变")
            : localize(messageSource, locale, "excel.guide.editable.yes", "是否可编辑：是"));
    if (hasText(guide.formatHint())) {
      lines.add(
          localize(messageSource, locale, "excel.guide.format_prefix", "格式：")
              + guide.formatHint().trim());
    }
    if (!guide.allowedValues().isEmpty()) {
      lines.add(
          localize(messageSource, locale, "excel.guide.allowed_prefix", "下拉值：")
              + String.join(" / ", guide.allowedValues()));
    }
    if (hasText(guide.example())) {
      lines.add(
          localize(messageSource, locale, "excel.guide.example_prefix", "示例：")
              + guide.example().trim());
    }
    return String.join("\n", lines);
  }

  /**
   * 如果 {@code value} 以 {@code "excel."} 开头则视为 i18n key,通过 messageSource 按 locale 解析;否则按字面量返回。{@code
   * messageSource} 为 null 时不解析,直接返回原值。
   */
  private static String localizeIfKey(MessageSource messageSource, Locale locale, String value) {
    if (value == null || messageSource == null || locale == null || !value.startsWith("excel.")) {
      return value;
    }
    return messageSource.getMessage(value, null, value, locale);
  }

  /** 解析固定 i18n key + fallback;messageSource / locale 缺一即用 fallback。 */
  private static String localize(
      MessageSource messageSource, Locale locale, String key, String fallback) {
    if (messageSource == null || locale == null) {
      return fallback;
    }
    return messageSource.getMessage(key, null, fallback, locale);
  }

  /**
   * 把 guide 内所有可能是 i18n key 的字段(description / formatHint / example / allowedValues)按 locale 解析后返回新
   * guide。
   */
  private static ColumnGuide resolveGuide(
      ColumnGuide guide, MessageSource messageSource, Locale locale) {
    if (guide == null || messageSource == null || locale == null) {
      return guide;
    }
    List<String> resolvedAllowed =
        guide.allowedValues().stream().map(v -> localizeIfKey(messageSource, locale, v)).toList();
    return new ColumnGuide(
        guide.required(),
        guide.readOnly(),
        localizeIfKey(messageSource, locale, guide.description()),
        localizeIfKey(messageSource, locale, guide.formatHint()),
        localizeIfKey(messageSource, locale, guide.example()),
        resolvedAllowed);
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
