package io.github.pinpols.batch.console.infrastructure.excel;

import static io.github.pinpols.batch.console.support.excel.ConsoleExcelStyles.createReadmeTitleStyle;

import io.github.pinpols.batch.console.support.excel.ConsoleExcelStyles;
import java.util.List;
import java.util.Locale;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.springframework.context.MessageSource;

/** Writes read-only supplementary sheets for tenant configuration package workbooks. */
class ConfigPackageWorkbookSupplementWriter {

  private static final String EMPTY = "";
  private static final ConfigPackageGuidanceContent GUIDANCE = ConfigPackageGuidanceContent.load();

  private static final int README_LINE_COUNT = 86;
  static final String SHEET_NAME_DEPENDENCY = "依赖说明";
  static final String SHEET_NAME_FOUR_WORKER = "四类Worker示例";
  static final String SHEET_NAME_BUNDLE = "文件束示例";
  private static final String READONLY_SHEET_HINT =
      "本 sheet 为只读说明，不参与导入解析与 apply；请复制片段到对应数据 sheet 后修改。";

  static final String[] DEPENDENCY_HEADERS = GUIDANCE.sheet("dependency").headers();
  static final List<String[]> DEPENDENCY_ROWS = GUIDANCE.sheet("dependency").rows();
  static final String[] FOUR_WORKER_HEADERS = GUIDANCE.sheet("fourWorker").headers();
  static final List<String[]> FOUR_WORKER_ROWS = GUIDANCE.sheet("fourWorker").rows();
  static final String[] BUNDLE_HEADERS = GUIDANCE.sheet("bundle").headers();
  static final List<String[]> BUNDLE_ROWS = GUIDANCE.sheet("bundle").rows();

  private final MessageSource messageSource;

  ConfigPackageWorkbookSupplementWriter(MessageSource messageSource) {
    this.messageSource = messageSource;
  }

  void createReadmeSheet(Workbook wb, Locale locale) {
    Sheet sheet = wb.createSheet(ConsoleExcelStyles.SHEET_NAME_README);
    sheet.setColumnWidth(0, 28000);
    CellStyle title = createReadmeTitleStyle(wb);
    Row titleRow = sheet.createRow(0);
    Cell titleCell = titleRow.createCell(0);
    String titleKey = "excel.package.readme.title";
    titleCell.setCellValue(messageSource.getMessage(titleKey, null, titleKey, locale));
    titleCell.setCellStyle(title);
    for (int i = 1; i <= README_LINE_COUNT; i++) {
      String key = "excel.package.readme.line" + i;
      Row row = sheet.createRow(i);
      Cell cell = row.createCell(0);
      cell.setCellValue(messageSource.getMessage(key, null, key, locale));
    }
  }

  void createDependencyGuideSheet(Workbook wb) {
    createReadOnlyTableSheet(wb, SHEET_NAME_DEPENDENCY, DEPENDENCY_HEADERS, DEPENDENCY_ROWS);
  }

  void createFourWorkerExampleSheet(Workbook wb) {
    createReadOnlyTableSheet(wb, SHEET_NAME_FOUR_WORKER, FOUR_WORKER_HEADERS, FOUR_WORKER_ROWS);
  }

  void createBundleExampleSheet(Workbook wb) {
    createReadOnlyTableSheet(wb, SHEET_NAME_BUNDLE, BUNDLE_HEADERS, BUNDLE_ROWS);
  }

  private void createReadOnlyTableSheet(
      Workbook wb, String sheetName, String[] headers, List<String[]> rows) {
    Sheet sheet = wb.createSheet(sheetName);
    Row hintRow = sheet.createRow(0);
    hintRow.createCell(0).setCellValue(READONLY_SHEET_HINT);
    CellStyle headerStyle = ConsoleExcelStyles.createHeaderStyle(wb);
    CellStyle bodyStyle = ConsoleExcelStyles.createDataStyle(wb);
    bodyStyle.setWrapText(true);
    Row headerRow = sheet.createRow(1);
    for (int c = 0; c < headers.length; c++) {
      Cell cell = headerRow.createCell(c);
      cell.setCellValue(headers[c]);
      cell.setCellStyle(headerStyle);
    }
    int rowIdx = 2;
    for (String[] data : rows) {
      Row row = sheet.createRow(rowIdx++);
      for (int c = 0; c < headers.length; c++) {
        Cell cell = row.createCell(c);
        cell.setCellValue(c < data.length && data[c] != null ? data[c] : EMPTY);
        cell.setCellStyle(bodyStyle);
      }
    }
    sheet.createFreezePane(0, 2);
    for (int c = 0; c < headers.length; c++) {
      sheet.setColumnWidth(c, 12000);
    }
  }
}
