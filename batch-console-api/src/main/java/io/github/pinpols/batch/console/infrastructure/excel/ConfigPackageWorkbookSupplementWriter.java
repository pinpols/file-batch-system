package io.github.pinpols.batch.console.infrastructure.excel;

import static io.github.pinpols.batch.console.support.excel.ConsoleExcelStyles.createReadmeTitleStyle;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.support.excel.ConsoleExcelStyles;
import java.util.List;
import java.util.Locale;
import org.apache.poi.common.usermodel.HyperlinkType;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Hyperlink;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.springframework.context.MessageSource;

/** Writes read-only supplementary sheets for tenant configuration package workbooks. */
class ConfigPackageWorkbookSupplementWriter {

  private static final String EMPTY = "";
  private static final ConfigPackageGuidanceContent GUIDANCE = ConfigPackageGuidanceContent.load();

  private static final int README_LINE_COUNT = 86;
  static final String SHEET_NAME_FILL_ORDER = "填写顺序";
  static final String SHEET_NAME_DEPENDENCY = "依赖说明";
  static final String SHEET_NAME_FIVE_WORKER = "五类Worker示例";
  static final String SHEET_NAME_BUNDLE = "文件束示例";
  private static final String READONLY_SHEET_HINT =
      "本 sheet 为只读说明，不参与导入解析与 apply；请复制片段到对应数据 sheet 后修改。";

  static final String[] DEPENDENCY_HEADERS = GUIDANCE.sheet("dependency").headers();
  static final List<String[]> DEPENDENCY_ROWS = GUIDANCE.sheet("dependency").rows();
  static final String[] FIVE_WORKER_HEADERS = GUIDANCE.sheet("fiveWorker").headers();
  static final List<String[]> FIVE_WORKER_ROWS = GUIDANCE.sheet("fiveWorker").rows();
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

  void createFillOrderSheet(Workbook wb, List<ConfigPackageSheetSpecs.SheetDef> sheetDefs) {
    Sheet sheet = wb.createSheet(SHEET_NAME_FILL_ORDER);
    CreationHelper creationHelper = wb.getCreationHelper();
    Row hintRow = sheet.createRow(0);
    hintRow.createCell(0).setCellValue("建议先按本 sheet 从上到下填写；只读说明 sheet 不参与导入解析与 apply。");
    CellStyle headerStyle = ConsoleExcelStyles.createHeaderStyle(wb);
    CellStyle bodyStyle = ConsoleExcelStyles.createDataStyle(wb);
    bodyStyle.setWrapText(true);
    String[] headers = {"顺序", "Sheet", "填写场景", "为什么先填", "关键必填列", "适用范围", "留空/默认提示"};
    Row headerRow = sheet.createRow(1);
    for (int c = 0; c < headers.length; c++) {
      Cell cell = headerRow.createCell(c);
      cell.setCellValue(headers[c]);
      cell.setCellStyle(headerStyle);
    }
    int rowIdx = 2;
    for (int i = 0; i < sheetDefs.size(); i++) {
      ConfigPackageSheetSpecs.SheetDef def = sheetDefs.get(i);
      Row row = sheet.createRow(rowIdx++);
      writeBodyCell(row, 0, String.valueOf(i + 1), bodyStyle);
      writeBodyCell(row, 1, def.name(), bodyStyle);
      addSheetLink(row.getCell(1), def.name(), creationHelper);
      writeBodyCell(row, 2, fillScenarioFor(def.name()), bodyStyle);
      writeBodyCell(row, 3, fillReasonFor(def.name()), bodyStyle);
      writeBodyCell(row, 4, requiredColumns(def), bodyStyle);
      writeBodyCell(
          row, 5, ConfigPackageExcelWorkbookWriter.appliesToFor(def.name(), ""), bodyStyle);
      writeBodyCell(row, 6, defaultHintFor(def), bodyStyle);
    }
    sheet.createFreezePane(0, 2);
    sheet.setAutoFilter(new CellRangeAddress(1, Math.max(1, rowIdx - 1), 0, headers.length - 1));
    int[] widths = {10, 30, 22, 42, 50, 42, 46};
    for (int c = 0; c < widths.length; c++) {
      sheet.setColumnWidth(c, widths[c] * 256);
    }
  }

  private static void addSheetLink(
      Cell cell, String targetSheetName, CreationHelper creationHelper) {
    Hyperlink link = creationHelper.createHyperlink(HyperlinkType.DOCUMENT);
    link.setAddress("'" + targetSheetName.replace("'", "''") + "'!A1");
    cell.setHyperlink(link);
  }

  void createDependencyGuideSheet(Workbook wb) {
    createReadOnlyTableSheet(wb, SHEET_NAME_DEPENDENCY, DEPENDENCY_HEADERS, DEPENDENCY_ROWS);
  }

  void createFiveWorkerExampleSheet(Workbook wb) {
    createReadOnlyTableSheet(wb, SHEET_NAME_FIVE_WORKER, FIVE_WORKER_HEADERS, FIVE_WORKER_ROWS);
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

  private static void writeBodyCell(Row row, int columnIndex, String value, CellStyle style) {
    Cell cell = row.createCell(columnIndex);
    cell.setCellValue(EmptyChecks.isNull(value) ? EMPTY : value);
    cell.setCellStyle(style);
  }

  private static String requiredColumns(ConfigPackageSheetSpecs.SheetDef def) {
    List<String> required = def.columns().stream()
        .filter(column -> {
          ConsoleExcelStyles.ColumnGuide guide = def.guides().get(column);
          return EmptyChecks.isNotNull(guide) && guide.required();
        })
        .toList();
    return EmptyChecks.isEmpty(required) ? "无强制必填列，按业务需要填写。" : String.join(", ", required);
  }

  private static String defaultHintFor(ConfigPackageSheetSpecs.SheetDef def) {
    long optionalCount = def.columns().stream()
        .filter(column -> {
          ConsoleExcelStyles.ColumnGuide guide = def.guides().get(column);
          return EmptyChecks.isNull(guide) || !guide.required();
        })
        .count();
    return optionalCount == 0 ? "本 sheet 列均为必填，留空会在预览阶段报错。" : "选填列可先留空；具体默认值/留空行为见『字段说明』sheet。";
  }

  private static String fillScenarioFor(String sheetName) {
    return switch (sheetName) {
      case ConfigPackageExcelValidator.RESOURCE_QUEUE_SHEET -> "配置资源池/并发/QPS。";
      case ConfigPackageExcelValidator.BUSINESS_CALENDAR_SHEET -> "配置批量日历、时区、节假日。";
      case ConfigPackageExcelValidator.BATCH_WINDOW_SHEET -> "配置允许执行的时间窗口。";
      case ConfigPackageExcelValidator.JOB_SHEET -> "配置作业主定义，所有 Worker 共用。";
      case ConfigPackageExcelValidator.CHANNEL_SHEET -> "配置 SFTP/OSS/S3/API 等文件渠道。";
      case ConfigPackageExcelValidator.FILE_TEMPLATE_SHEET -> "配置导入/导出文件格式、字段映射、查询。";
      case ConfigPackageExcelValidator.PIPELINE_SHEET -> "配置 Worker 流水线主定义。";
      case ConfigPackageExcelValidator.STEP_SHEET -> "配置流水线步骤和参数。";
      case ConfigPackageExcelValidator.WF_DEF_SHEET -> "配置工作流主定义。";
      case ConfigPackageExcelValidator.WF_NODE_SHEET -> "配置工作流节点。";
      case ConfigPackageExcelValidator.WF_EDGE_SHEET -> "配置工作流节点依赖边。";
      default -> "按业务需要填写。";
    };
  }

  private static String fillReasonFor(String sheetName) {
    return switch (sheetName) {
      case ConfigPackageExcelValidator.RESOURCE_QUEUE_SHEET,
          ConfigPackageExcelValidator.BUSINESS_CALENDAR_SHEET,
          ConfigPackageExcelValidator.BATCH_WINDOW_SHEET -> "基础字典，后续 job / workflow node 会引用。";
      case ConfigPackageExcelValidator.FILE_TEMPLATE_SHEET,
          ConfigPackageExcelValidator.CHANNEL_SHEET -> "文件类作业和派发步骤会引用，建议先于 pipeline step 填。";
      case ConfigPackageExcelValidator.JOB_SHEET -> "pipeline 和 workflow node 都会引用 job_code。";
      case ConfigPackageExcelValidator.PIPELINE_SHEET ->
        "pipeline_step_definition 需要引用 job_code + version。";
      case ConfigPackageExcelValidator.STEP_SHEET -> "具体执行步骤，依赖 job/pipeline/template/channel。";
      case ConfigPackageExcelValidator.WF_DEF_SHEET ->
        "workflow_node / workflow_edge 共用 workflow_code + version。";
      case ConfigPackageExcelValidator.WF_NODE_SHEET -> "workflow_edge 需要引用 from/to node_code。";
      case ConfigPackageExcelValidator.WF_EDGE_SHEET -> "最后填写，依赖 workflow node 已存在。";
      default -> EMPTY;
    };
  }
}
