package io.github.pinpols.batch.worker.exports.stage.format;

import io.github.pinpols.batch.worker.exports.stage.format.AbstractExportFormat.ColumnLayout;
import io.github.pinpols.batch.worker.exports.stage.format.AbstractExportFormat.ColumnType;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

/**
 * 封装 Excel 数据导出的 sheet 生命周期:表头写入、按列缓存 {@link CellStyle}、类型化 cell 写入、按 {@code rowsPerSheet} 自动滚动到下一个
 * sheet。<b>样式对象只造一次按列 / 按角色缓存复用</b>,SXSSF 下 cell style 总数有上限(64k),绝不每 cell 新建。
 *
 * <p>合并分组表头只在第 0 行表头区做({@code addMergedRegion} 在 SXSSF 下要求合并行仍在当前滑动窗口内,表头是最先写的几行故安全); 不支持跨数据行合并 ——
 * 数据行随写随被 SXSSF flush 出窗口,事后合并会抛 {@code IllegalStateException},这是 SXSSF 的硬约束。
 */
final class ExcelSheetWriter {

  /** .xlsx 单 sheet 1048576 行硬限,留 1 行给表头(可能含分组表头时更多,写入时再校验)。 */
  static final int MAX_DATA_ROWS_PER_SHEET = 1_048_575;

  private static final DateTimeFormatter ISO_DATE_TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

  private final SXSSFWorkbook workbook;
  private final List<ColumnLayout> columns;
  private final String baseSheetName;
  private final int headerRows;
  private final int rowsPerSheet;
  private final ExcelStyleOptions styleOptions;
  private final AbstractExportFormat helper;

  private final CellStyle headerStyle;
  private final DataFormat dataFormat;
  // 按列下标缓存的数据 cell 样式(NUMBER / DATE 带格式串时才建,STRING / BOOL 无样式不建)。
  private final Map<Integer, CellStyle> columnStyleCache = new HashMap<>();

  private Sheet currentSheet;
  private int sheetSeq;
  private int rowNoInSheet;
  private long rowsInCurrentSheet;

  ExcelSheetWriter(
      SXSSFWorkbook workbook,
      List<ColumnLayout> columns,
      String baseSheetName,
      int headerRows,
      int rowsPerSheet,
      ExcelStyleOptions styleOptions,
      AbstractExportFormat helper) {
    this.workbook = workbook;
    this.columns = columns;
    this.baseSheetName = baseSheetName;
    this.headerRows = headerRows;
    this.rowsPerSheet = rowsPerSheet;
    this.styleOptions = styleOptions;
    this.helper = helper;
    this.headerStyle =
        styleOptions.styled() && (styleOptions.bold() || styleOptions.backgroundRgb() != null)
            ? ExcelStyleSupport.createHeaderStyle(workbook, styleOptions.backgroundRgb())
            : null;
    this.dataFormat = workbook.createDataFormat();
    startNewSheet();
  }

  /** 写一行数据。达 {@code rowsPerSheet} 阈值时先滚动到下一个 sheet(复制表头)再写。 */
  void writeDataRow(Map<String, Object> batch, Map<String, Object> detail) {
    if (rowsPerSheet > 0 && rowsInCurrentSheet >= rowsPerSheet) {
      startNewSheet();
    }
    Row row = currentSheet.createRow(rowNoInSheet++);
    for (int i = 0; i < columns.size(); i++) {
      Cell cell = row.createCell(i);
      writeTypedCell(
          cell,
          columns.get(i),
          helper.resolveDelimitedValue(batch, detail, columns.get(i).source()),
          i);
    }
    rowsInCurrentSheet++;
  }

  private void startNewSheet() {
    sheetSeq++;
    String name =
        sheetSeq == 1 ? baseSheetName : helper.sanitizeSheetName(baseSheetName + "_" + sheetSeq);
    currentSheet = workbook.createSheet(name);
    rowNoInSheet = 0;
    rowsInCurrentSheet = 0;
    writeHeaderBlock();
  }

  private void writeHeaderBlock() {
    // 分组表头(可选):占第 0 行,合并各分组列区间;之后写常规表头行。
    if (styleOptions.hasHeaderGroups()) {
      writeGroupedHeaderRow();
    }
    for (int r = 0; r < headerRows; r++) {
      Row row = currentSheet.createRow(rowNoInSheet++);
      for (int c = 0; c < columns.size(); c++) {
        Cell cell = row.createCell(c);
        cell.setCellValue(ExcelStyleSupport.escapeFormula(columns.get(c).header()));
        if (headerStyle != null) {
          cell.setCellStyle(headerStyle);
        }
      }
    }
    if (styleOptions.autoWidth()) {
      for (int c = 0; c < columns.size(); c++) {
        currentSheet.setColumnWidth(c, ExcelStyleSupport.autoColumnWidth(columns.get(c).header()));
      }
    }
    if (styleOptions.freezeHeader()) {
      // 冻结到最后一行表头之下(含可能的分组表头行)。
      currentSheet.createFreezePane(0, rowNoInSheet, 0, rowNoInSheet);
    }
  }

  private void writeGroupedHeaderRow() {
    Row groupRow = currentSheet.createRow(rowNoInSheet++);
    int lastColumn = columns.isEmpty() ? 0 : columns.size() - 1;
    for (ExcelStyleOptions.HeaderGroup group : styleOptions.headerGroups()) {
      int from = Math.max(0, group.fromColumn());
      int to = Math.min(lastColumn, group.toColumn());
      if (from > to) {
        continue;
      }
      Cell cell = groupRow.createCell(from);
      cell.setCellValue(ExcelStyleSupport.escapeFormula(group.title()));
      if (headerStyle != null) {
        cell.setCellStyle(headerStyle);
      }
      if (to > from) {
        // 合并仅在第 0 行表头区,仍处于 SXSSF 当前窗口,安全;跨数据行合并不支持(见类注释)。
        currentSheet.addMergedRegion(
            new CellRangeAddress(groupRow.getRowNum(), groupRow.getRowNum(), from, to));
      }
    }
  }

  /**
   * 按列类型写 cell。NUMBER → double、DATE → 真日期 + format、BOOL → boolean;null / 解析失败回退 {@code
   * setCellValue(String)} 文本(escapeFormula 防注入),绝不因类型转换异常中断导出。
   */
  private void writeTypedCell(Cell cell, ColumnLayout column, Object value, int columnIndex) {
    if (value == null) {
      cell.setCellValue("");
      return;
    }
    ColumnType type = column.type();
    if (type == ColumnType.NUMBER) {
      Double number = toDouble(value);
      if (number != null) {
        cell.setCellValue(number);
        applyColumnStyle(cell, column, columnIndex);
        return;
      }
    } else if (type == ColumnType.BOOL) {
      Boolean bool = toBoolean(value);
      if (bool != null) {
        cell.setCellValue(bool);
        return;
      }
    } else if (type == ColumnType.DATE) {
      Date date = toDate(value);
      if (date != null) {
        cell.setCellValue(date);
        applyColumnStyle(cell, column, columnIndex);
        return;
      }
    }
    // STRING 或上述类型解析失败 → 文本回退。
    String text = helper.textValue(value);
    cell.setCellValue(ExcelStyleSupport.escapeFormula(text == null ? String.valueOf(value) : text));
  }

  /** 取(或建)该列的数字 / 日期格式样式并缓存复用;未声明 format 串则不套样式(默认通用格式)。 */
  private void applyColumnStyle(Cell cell, ColumnLayout column, int columnIndex) {
    String pattern =
        column.type() == ColumnType.NUMBER ? column.numberFormat() : column.dateFormat();
    if (pattern == null || pattern.isBlank()) {
      return;
    }
    CellStyle style =
        columnStyleCache.computeIfAbsent(
            columnIndex,
            idx -> {
              CellStyle s = workbook.createCellStyle();
              s.setDataFormat(dataFormat.getFormat(pattern));
              return s;
            });
    cell.setCellStyle(style);
  }

  private Double toDouble(Object value) {
    if (value instanceof Number number) {
      return number.doubleValue();
    }
    try {
      String text = String.valueOf(value).trim();
      return text.isEmpty() ? null : Double.valueOf(text);
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private Boolean toBoolean(Object value) {
    if (value instanceof Boolean bool) {
      return bool;
    }
    String text = String.valueOf(value).trim();
    if (text.equalsIgnoreCase("true") || text.equals("1")) {
      return Boolean.TRUE;
    }
    if (text.equalsIgnoreCase("false") || text.equals("0")) {
      return Boolean.FALSE;
    }
    return null;
  }

  private Date toDate(Object value) {
    if (value instanceof Date date) {
      return date;
    }
    if (value instanceof Instant instant) {
      return Date.from(instant);
    }
    if (value instanceof LocalDateTime ldt) {
      return Date.from(ldt.toInstant(ZoneOffset.UTC));
    }
    if (value instanceof LocalDate ld) {
      return Date.from(ld.atStartOfDay().toInstant(ZoneOffset.UTC));
    }
    String text = String.valueOf(value).trim();
    if (text.isEmpty()) {
      return null;
    }
    try {
      return Date.from(LocalDateTime.parse(text, ISO_DATE_TIME).toInstant(ZoneOffset.UTC));
    } catch (Exception ignoredDateTime) {
      try {
        return Date.from(LocalDate.parse(text).atStartOfDay().toInstant(ZoneOffset.UTC));
      } catch (Exception ignoredDate) {
        return null;
      }
    }
  }
}
