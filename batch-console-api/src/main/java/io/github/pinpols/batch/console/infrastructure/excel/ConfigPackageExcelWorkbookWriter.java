package io.github.pinpols.batch.console.infrastructure.excel;

import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.*;
import static io.github.pinpols.batch.console.support.excel.ConsoleExcelStyles.createOptionalMarkStyle;
import static io.github.pinpols.batch.console.support.excel.ConsoleExcelStyles.createRequiredMarkStyle;
import static io.github.pinpols.batch.console.support.excel.ConsoleExcelStyles.setWidths;
import static io.github.pinpols.batch.console.support.excel.ConsoleExcelStyles.writeHeaders;
import static io.github.pinpols.batch.console.support.excel.ConsoleExcelStyles.writeTemplateHeaders;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.PackageValidationResult;
import io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.SheetResult;
import io.github.pinpols.batch.console.support.excel.ConsoleExcelPreviewWorkbookSupport;
import io.github.pinpols.batch.console.support.excel.ConsoleExcelPreviewWorkbookSupport.WorkbookIssue;
import io.github.pinpols.batch.console.support.excel.ConsoleExcelStyles;
import io.github.pinpols.batch.console.support.excel.TenantConfigPackageExcelImportStore.PackageExcelSession;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

/**
 * 为租户配置包生成 Excel 工作簿(导出、模板、预览)。从 DefaultConsoleTenantConfigPackageExcelApplicationService 抽出以缩减类体积。
 */
public class ConfigPackageExcelWorkbookWriter {

  private static final String EMPTY = "";
  private static final String GUIDE_LEVEL_REQUIRED = "必填";
  private static final String GUIDE_LEVEL_COMMON = "常用";
  private static final String GUIDE_LEVEL_ADVANCED = "高级";
  private static final String GUIDE_LEVEL_OPTIONAL = "可选";

  private static final Set<String> FILE_TEMPLATE_COMMON_OPTIONAL_COLUMNS = Set.of(
      COL_TENANT_ID,
      ConfigPackageExcelSchema.COL_BIZ_TYPE,
      ConfigPackageExcelSchema.FileTemplate.COL_CHARSET,
      ConfigPackageExcelSchema.FileTemplate.COL_LINE_SEPARATOR,
      ConfigPackageExcelSchema.FileTemplate.COL_DELIMITER,
      ConfigPackageExcelSchema.FileTemplate.COL_QUOTE_CHAR,
      ConfigPackageExcelSchema.FileTemplate.COL_ESCAPE_CHAR,
      ConfigPackageExcelSchema.FileTemplate.COL_HEADER_ROWS,
      ConfigPackageExcelSchema.FileTemplate.COL_FOOTER_ROWS,
      ConfigPackageExcelSchema.FileTemplate.COL_FIELD_MAPPINGS,
      ConfigPackageExcelSchema.FileTemplate.COL_VALIDATION_RULE_SET,
      ConfigPackageExcelSchema.FileTemplate.COL_QUERY_PARAM_SCHEMA,
      ConfigPackageExcelSchema.FileTemplate.COL_STREAMING_ENABLED,
      ConfigPackageExcelSchema.FileTemplate.COL_PAGE_SIZE,
      ConfigPackageExcelSchema.FileTemplate.COL_FETCH_SIZE,
      ConfigPackageExcelSchema.FileTemplate.COL_CHUNK_SIZE,
      ConfigPackageExcelSchema.COL_ENABLED,
      ConfigPackageExcelSchema.COL_VERSION,
      ConfigPackageExcelSchema.COL_DESCRIPTION);

  /** 兼容转发：dropdown 数组权威源已移到 {@link ConfigPackageSheetSpecs}，测试仍引用 writer 入口。 */
  static final String[] JOB_TYPE_DROPDOWN = ConfigPackageSheetSpecs.JOB_TYPE_DROPDOWN;

  static final String[] SCHEDULE_TYPE_DROPDOWN = ConfigPackageSheetSpecs.SCHEDULE_TYPE_DROPDOWN;

  static final String[] PIPELINE_TYPE_DROPDOWN = ConfigPackageSheetSpecs.PIPELINE_TYPE_DROPDOWN;

  static final String[] STAGE_CODE_DROPDOWN = ConfigPackageSheetSpecs.STAGE_CODE_DROPDOWN;

  public static final List<String> RESOURCE_QUEUE_COLUMNS =
      ConfigPackageExcelSchema.ResourceQueue.COLUMNS;

  public static final List<String> BUSINESS_CALENDAR_COLUMNS =
      ConfigPackageExcelSchema.BusinessCalendar.COLUMNS;

  public static final List<String> BATCH_WINDOW_COLUMNS =
      ConfigPackageExcelSchema.BatchWindow.COLUMNS;

  public static final List<String> JOB_COLUMNS = ConfigPackageExcelSchema.JobDefinition.COLUMNS;

  public static final List<String> CHANNEL_COLUMNS = ConfigPackageExcelSchema.FileChannel.COLUMNS;

  public static final List<String> FILE_TEMPLATE_COLUMNS =
      ConfigPackageExcelSchema.FileTemplate.COLUMNS;

  public static final List<String> PIPELINE_COLUMNS =
      ConfigPackageExcelSchema.PipelineDefinition.COLUMNS;

  public static final List<String> STEP_COLUMNS = ConfigPackageExcelSchema.PipelineStep.COLUMNS;

  public static final List<String> WF_DEF_COLUMNS =
      ConfigPackageExcelSchema.WorkflowDefinition.COLUMNS;

  public static final List<String> WF_NODE_COLUMNS = ConfigPackageExcelSchema.WorkflowNode.COLUMNS;

  public static final List<String> WF_EDGE_COLUMNS = ConfigPackageExcelSchema.WorkflowEdge.COLUMNS;

  private final List<ConfigPackageSheetSpecs.SheetDef> sheetDefs;
  private final MessageSource messageSource;
  private final ConfigPackageWorkbookSupplementWriter supplementWriter;
  private final ConfigPackageSheetSpecs sheetSpecs;

  public ConfigPackageExcelWorkbookWriter(MessageSource messageSource) {
    this.messageSource = messageSource;
    this.supplementWriter = new ConfigPackageWorkbookSupplementWriter(messageSource);
    this.sheetSpecs = new ConfigPackageSheetSpecs(messageSource);
    this.sheetDefs = sheetSpecs.build();
  }

  public byte[] buildExportWorkbook(List<List<Map<String, Object>>> sheetDataList) {
    return buildExportWorkbook(sheetDataList, Map.of());
  }

  /**
   * R2-P1-9 兼容入口：保留 byte[] 签名给老调用方；底层走 streaming 路径，但仍在内存里 buffer 一次。 新代码请改用 {@link
   * #writeExportWorkbook(OutputStream, List, Map)} 避免 double-copy。
   */
  public byte[] buildExportWorkbook(
      List<List<Map<String, Object>>> sheetDataList,
      Map<String, List<String>> registeredImplCodesByModule) {
    try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      writeExportWorkbook(out, sheetDataList, registeredImplCodesByModule);
      return out.toByteArray();
    } catch (IOException e) {
      throw BizException.of(ResultCode.SYSTEM_ERROR, "error.excel.export_workbook_failed");
    }
  }

  /**
   * R2-P1-9 流式导出：直接把 SXSSF workbook 写到调用方的 {@link OutputStream}（通常是 HTTP response）， 不在堆里缓
   * byte[]。SXSSF 自己用 50 行窗口 + 临时磁盘 spool，内存压力恒定。
   */
  public void writeExportWorkbook(
      OutputStream out,
      List<List<Map<String, Object>>> sheetDataList,
      Map<String, List<String>> registeredImplCodesByModule)
      throws IOException {
    Locale locale = LocaleContextHolder.getLocale();
    Map<String, List<String>> implRegistry = copyImplRegistry(registeredImplCodesByModule);
    try (SXSSFWorkbook wb = new SXSSFWorkbook(50)) {
      for (int i = 0; i < sheetDefs.size(); i++) {
        ConfigPackageSheetSpecs.SheetDef def = sheetDefs.get(i);
        writeDataSheet(wb, def, sheetDataList.get(i), locale, implRegistry);
      }
      supplementWriter.createReadmeSheet(wb, locale);
      supplementWriter.createDependencyGuideSheet(wb);
      supplementWriter.createFourWorkerExampleSheet(wb);
      supplementWriter.createBundleExampleSheet(wb);
      createFieldGuideSheet(wb);
      ConsoleExcelStyles.createValidationSheet(wb);
      wb.write(out);
    }
  }

  public byte[] buildTemplateWorkbook() {
    return buildTemplateWorkbook(Map.of());
  }

  /** R2-P1-9 兼容入口：底层走 streaming。新代码请改用 {@link #writeTemplateWorkbook(OutputStream, Map)}。 */
  public byte[] buildTemplateWorkbook(Map<String, List<String>> registeredImplCodesByModule) {
    try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      writeTemplateWorkbook(out, registeredImplCodesByModule);
      return out.toByteArray();
    } catch (IOException e) {
      throw BizException.of(ResultCode.SYSTEM_ERROR, "error.excel.template_workbook_failed");
    }
  }

  /** R2-P1-9 流式模板导出，直接把 workbook 写到调用方 stream。 */
  public void writeTemplateWorkbook(
      OutputStream out, Map<String, List<String>> registeredImplCodesByModule) throws IOException {
    Locale locale = LocaleContextHolder.getLocale();
    Map<String, List<String>> implRegistry = copyImplRegistry(registeredImplCodesByModule);
    try (SXSSFWorkbook wb = new SXSSFWorkbook(50)) {
      for (ConfigPackageSheetSpecs.SheetDef def : sheetDefs) {
        writeDataSheet(wb, def, List.of(), locale, implRegistry);
      }
      supplementWriter.createReadmeSheet(wb, locale);
      supplementWriter.createDependencyGuideSheet(wb);
      supplementWriter.createFourWorkerExampleSheet(wb);
      supplementWriter.createBundleExampleSheet(wb);
      createFieldGuideSheet(wb);
      ConsoleExcelStyles.createValidationSheet(wb);
      wb.write(out);
    }
  }

  public byte[] buildPreviewWorkbook(PackageExcelSession session, PackageValidationResult result) {
    return buildPreviewWorkbook(session, result, Map.of());
  }

  /** R2-P1-9 兼容入口：底层走 streaming。新代码请改用 {@link #writePreviewWorkbook}。 */
  public byte[] buildPreviewWorkbook(
      PackageExcelSession session,
      PackageValidationResult result,
      Map<String, List<String>> registeredImplCodesByModule) {
    try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      writePreviewWorkbook(out, session, result, registeredImplCodesByModule);
      return out.toByteArray();
    } catch (IOException e) {
      throw BizException.of(ResultCode.SYSTEM_ERROR, "error.excel.preview_failed");
    }
  }

  /**
   * R2-P1-9 流式预览导出。XSSFWorkbook 内部仍 buffer（预览需 cell comment，不能用 SXSSF）， 但消除外层 byte[] 拷贝；workbook 直接
   * write 到响应流。
   */
  public void writePreviewWorkbook(
      OutputStream out,
      PackageExcelSession session,
      PackageValidationResult result,
      Map<String, List<String>> registeredImplCodesByModule)
      throws IOException {
    Locale locale = LocaleContextHolder.getLocale();
    Map<String, List<String>> implRegistry = copyImplRegistry(registeredImplCodesByModule);
    List<List<Map<String, String>>> sessionData = List.of(
        session.resourceQueueRows(),
        session.businessCalendarRows(),
        session.batchWindowRows(),
        session.jobRows(),
        session.fileChannelRows(),
        session.fileTemplateRows(),
        session.pipelineRows(),
        session.pipelineStepRows(),
        session.workflowDefinitionRows(),
        session.workflowNodeRows(),
        session.workflowEdgeRows());
    List<SheetResult> results = List.of(
        result.resourceQueues(),
        result.businessCalendars(),
        result.batchWindows(),
        result.jobs(),
        result.channels(),
        result.fileTemplates(),
        result.pipelines(),
        result.steps(),
        result.wfDefs(),
        result.wfNodes(),
        result.wfEdges());
    try (XSSFWorkbook wb = new XSSFWorkbook()) {
      for (int i = 0; i < sheetDefs.size(); i++) {
        ConfigPackageSheetSpecs.SheetDef def = sheetDefs.get(i);
        writePreviewSheet(
            wb, def, sessionData.get(i), results.get(i).issues(), locale, implRegistry);
      }
      ConsoleExcelPreviewWorkbookSupport.populateValidationSheet(wb, result.allIssues());
      wb.write(out);
    }
  }

  private void writeDataSheet(
      Workbook wb,
      ConfigPackageSheetSpecs.SheetDef def,
      List<Map<String, Object>> dataRows,
      Locale locale,
      Map<String, List<String>> registeredImplCodesByModule) {
    Sheet sheet = wb.createSheet(def.name());
    sheet.createFreezePane(0, 1, 0, 1);
    writeTemplateHeaders(sheet, def.columns(), def.guides(), wb, messageSource, locale);
    int idx = 1;
    for (Map<String, Object> row : dataRows) {
      Row dataRow = sheet.createRow(idx++);
      for (int c = 0; c < def.columns().size(); c++) {
        Object val = row.get(def.columns().get(c));
        dataRow
            .createCell(c)
            .setCellValue(
                val == null ? EMPTY : ConsoleExcelStyles.escapeFormula(String.valueOf(val)));
      }
    }
    applyValidations(def, sheet, locale, registeredImplCodesByModule);
    setWidths(sheet, def.columns());
  }

  private void writePreviewSheet(
      Workbook wb,
      ConfigPackageSheetSpecs.SheetDef def,
      List<Map<String, String>> dataRows,
      List<WorkbookIssue> sheetIssues,
      Locale locale,
      Map<String, List<String>> registeredImplCodesByModule) {
    Sheet sheet = wb.createSheet(def.name());
    sheet.createFreezePane(0, 1, 0, 1);
    CellStyle headerStyle = ConsoleExcelStyles.createHeaderStyle(wb);
    writeHeaders(sheet, def.columns(), headerStyle);
    int idx = 1;
    for (Map<String, String> row : dataRows) {
      Row dataRow = sheet.createRow(idx++);
      for (int c = 0; c < def.columns().size(); c++) {
        String val = row.get(def.columns().get(c));
        dataRow.createCell(c).setCellValue(val == null ? EMPTY : val);
      }
    }
    applyValidations(def, sheet, locale, registeredImplCodesByModule);
    ConsoleExcelPreviewWorkbookSupport.addIssueComments(sheet, def.columns(), sheetIssues, 0);
    setWidths(sheet, def.columns());
  }

  private void applyValidations(
      ConfigPackageSheetSpecs.SheetDef def,
      Sheet sheet,
      Locale locale,
      Map<String, List<String>> registeredImplCodesByModule) {
    if (STEP_SHEET.equals(def.name())) {
      sheetSpecs.applyStepValidations(sheet, locale, registeredImplCodesByModule);
      return;
    }
    def.validationApplier().accept(sheet, locale);
  }

  private static Map<String, List<String>> copyImplRegistry(
      Map<String, List<String>> registeredImplCodesByModule) {
    if (registeredImplCodesByModule == null || registeredImplCodesByModule.isEmpty()) {
      return Map.of();
    }
    Map<String, List<String>> copied = new LinkedHashMap<>();
    registeredImplCodesByModule.forEach((module, beans) -> {
      if (module == null || beans == null || beans.isEmpty()) {
        return;
      }
      copied.put(module, List.copyOf(beans));
    });
    return copied.isEmpty() ? Map.of() : Collections.unmodifiableMap(copied);
  }

  private void createFieldGuideSheet(Workbook wb) {
    Sheet sheet = wb.createSheet(ConsoleExcelStyles.SHEET_NAME_GUIDE);
    setGuideColumnWidths(sheet);
    GuideStyles styles = buildGuideStyles(wb);
    writeGuideHeader(sheet, styles.head());
    int rowIdx = 1;
    // 记录每个 sheet 的起止行号，最后做第一列合并（per-sheet 一段，垂直居中），更美观、可读
    for (ConfigPackageSheetSpecs.SheetDef spec : sheetDefs) {
      int sectionStart = rowIdx;
      for (int ci = 0; ci < spec.columns().size(); ci++) {
        String colName = spec.columns().get(ci);
        Row row = sheet.createRow(rowIdx++);
        row.setHeightInPoints(18);
        writeGuideRow(
            row,
            spec.name(),
            ci == 0 ? spec.name() : EMPTY,
            colName,
            spec.guides().get(colName),
            appliesToFor(spec.name(), colName),
            fillExampleFor(spec.name(), colName, spec.guides().get(colName)),
            styles);
      }
      int sectionEnd = rowIdx - 1;
      // 单 sheet 多列时合并第一列；单列 sheet 无需合并
      if (sectionEnd > sectionStart) {
        sheet.addMergedRegion(
            new org.apache.poi.ss.util.CellRangeAddress(sectionStart, sectionEnd, 0, 0));
      }
    }
  }

  private static void setGuideColumnWidths(Sheet sheet) {
    sheet.setColumnWidth(0, 6500); // 所属 Sheet
    sheet.setColumnWidth(1, 7000); // 列名
    sheet.setColumnWidth(2, 3500); // 必填
    sheet.setColumnWidth(3, 3500); // 填写层级
    sheet.setColumnWidth(4, 3500); // 类型
    sheet.setColumnWidth(5, 14000); // 可选值
    sheet.setColumnWidth(6, 18000); // 说明
    sheet.setColumnWidth(7, 7000); // 示例
    sheet.setColumnWidth(8, 8000); // 适用 Worker
    sheet.setColumnWidth(9, 20000); // 填写示例（完整可抄片段）
  }

  private GuideStyles buildGuideStyles(Workbook wb) {
    CellStyle bodyStyle = ConsoleExcelStyles.createDataStyle(wb);
    bodyStyle.setWrapText(true);
    CellStyle requiredStyle = createRequiredMarkStyle(wb);
    requiredStyle.setWrapText(true);
    CellStyle optionalStyle = createOptionalMarkStyle(wb);
    optionalStyle.setWrapText(true);
    return new GuideStyles(
        ConsoleExcelStyles.createHeaderStyle(wb), bodyStyle, requiredStyle, optionalStyle);
  }

  private static void writeGuideHeader(Sheet sheet, CellStyle headStyle) {
    Row header = sheet.createRow(0);
    header.setHeightInPoints(22);
    String[] headers = {"所属 Sheet", "列名", "必填", "填写层级", "类型", "可选值", "说明", "示例", "适用 Worker", "填写示例"
    };
    for (int i = 0; i < headers.length; i++) {
      Cell c = header.createCell(i);
      c.setCellValue(headers[i]);
      c.setCellStyle(headStyle);
    }
  }

  private void writeGuideRow(
      Row row,
      String sheetName,
      String sectionLabel,
      String colName,
      ConsoleExcelStyles.ColumnGuide guide,
      String appliesTo,
      String fillExample,
      GuideStyles styles) {
    boolean isRequired = guide != null && guide.required();
    writeGuideCell(row, 0, sectionLabel, styles.body());
    writeGuideCell(row, 1, colName, styles.body());
    writeGuideCell(
        row, 2, isRequired ? "★ 必填" : "选填", isRequired ? styles.required() : styles.optional());
    writeGuideCell(row, 3, guideLevelFor(sheetName, colName, isRequired), styles.body());
    writeGuideCell(
        row, 4, guideOrEmpty(guide, ConsoleExcelStyles.ColumnGuide::formatHint), styles.body());
    writeGuideCell(row, 5, joinAllowedValues(guide), styles.body());
    writeGuideCell(
        row, 6, guideOrEmpty(guide, ConsoleExcelStyles.ColumnGuide::description), styles.body());
    writeGuideCell(
        row, 7, guideOrEmpty(guide, ConsoleExcelStyles.ColumnGuide::example), styles.body());
    writeGuideCell(row, 8, appliesTo == null ? EMPTY : appliesTo, styles.body());
    writeGuideCell(row, 9, fillExample == null ? EMPTY : fillExample, styles.body());
  }

  private static String guideLevelFor(String sheetName, String colName, boolean required) {
    if (required) {
      return GUIDE_LEVEL_REQUIRED;
    }
    if (!FILE_TEMPLATE_SHEET.equals(sheetName)) {
      return GUIDE_LEVEL_OPTIONAL;
    }
    return FILE_TEMPLATE_COMMON_OPTIONAL_COLUMNS.contains(colName)
        ? GUIDE_LEVEL_COMMON
        : GUIDE_LEVEL_ADVANCED;
  }

  /**
   * 「填写示例」列：完整可直接复制改写的片段，区别于第 7 列短「示例」。 对最难填的 JSON/SQL 字段给真实非空结构（提取自 e2e fixture），import vs export
   * 两套结构都覆盖；其他字段回退到短示例。
   */
  private static String fillExampleFor(
      String sheetName, String colName, ConsoleExcelStyles.ColumnGuide guide) {
    String override = ConfigPackageSheetSpecs.FILL_EXAMPLE_OVERRIDE
        .getOrDefault(sheetName, Map.of())
        .get(colName);
    if (override != null) {
      return override;
    }
    return guideOrEmpty(guide, ConsoleExcelStyles.ColumnGuide::example);
  }

  private static String guideOrEmpty(
      ConsoleExcelStyles.ColumnGuide guide,
      Function<ConsoleExcelStyles.ColumnGuide, String> getter) {
    return guide == null ? EMPTY : getter.apply(guide);
  }

  /**
   * 字段说明 sheet「适用 Worker」列。先查 per-column 覆盖（少数 worker-specific 字段），未命中走 per-sheet 默认。
   *
   * <p>Worker 缩写：I=IMPORT / E=EXPORT / P=PROCESS / D=DISPATCH / G=GENERAL / W=WORKFLOW；ALL = 全部。
   */
  private static String appliesToFor(String sheetName, String colName) {
    String override = APPLIES_TO_OVERRIDE.getOrDefault(sheetName, Map.of()).get(colName);
    if (override != null) return override;
    return APPLIES_TO_SHEET_DEFAULT.getOrDefault(sheetName, "ALL");
  }

  /** Per-sheet 默认「适用 Worker」（覆盖大多数列）。 */
  private static final Map<String, String> APPLIES_TO_SHEET_DEFAULT = Map.ofEntries(
      Map.entry(RESOURCE_QUEUE_SHEET, "ALL（任意 Job 引用时必填）"),
      Map.entry(BUSINESS_CALENDAR_SHEET, "ALL（任意 Job 引用时必填）"),
      Map.entry(BATCH_WINDOW_SHEET, "ALL（任意 Job/Node 引用时必填）"),
      Map.entry(JOB_SHEET, "ALL（5 类 Worker + WORKFLOW 共用）"),
      Map.entry(CHANNEL_SHEET, "DISPATCH 主；IMPORT.RECEIVE 次"),
      Map.entry(FILE_TEMPLATE_SHEET, "IMPORT / EXPORT（DISPATCH 引用上游产物时间接用）"),
      Map.entry(PIPELINE_SHEET, "IMPORT / EXPORT / PROCESS / DISPATCH（按 pipeline_type）"),
      Map.entry(STEP_SHEET, "IMPORT / EXPORT / PROCESS / DISPATCH（按 pipeline_type 收窄 stage_code）"),
      Map.entry(WF_DEF_SHEET, "WORKFLOW（编排层，可组合其他 4 类 Job）"),
      Map.entry(WF_NODE_SHEET, "WORKFLOW"),
      Map.entry(WF_EDGE_SHEET, "WORKFLOW"));

  /**
   * Per-column 覆盖（少数 worker-specific 字段，比 sheet 默认更精确）。
   *
   * <p>没有列在这里的字段一律走 {@link #APPLIES_TO_SHEET_DEFAULT}。
   */
  private static final Map<String, Map<String, String>> APPLIES_TO_OVERRIDE = Map.ofEntries(
      Map.entry(
          JOB_SHEET,
          Map.of(
              COL_JOB_TYPE,
              "决定本作业 Worker：GENERAL/IMPORT/EXPORT/PROCESS/DISPATCH/WORKFLOW",
              COL_EXECUTION_HANDLER,
              "GENERAL（普通任务）执行 bean 名；其他 worker 不用",
              COL_DEFAULT_PARAMS,
              "IMPORT/EXPORT：用 templateCode 引用 file_template_config")),
      Map.entry(
          PIPELINE_SHEET,
          Map.of(COL_PIPELINE_TYPE, "决定 Worker 类型和 stage 候选集（IMPORT/EXPORT/PROCESS/DISPATCH）")),
      Map.entry(STEP_SHEET, Map.of(COL_STAGE_CODE, "按 pipeline_type 收窄；填非法 stage preview 报错")),
      Map.entry(
          FILE_TEMPLATE_SHEET,
          Map.of(
              "default_query_sql", "EXPORT only（单条 SELECT）",
              "query_param_schema", "IMPORT 用 jdbcMappedImport / EXPORT 用 jdbcMappedExport",
              "field_mappings", "IMPORT 用",
              "naming_rule", "EXPORT 用",
              "header_template", "EXPORT 用",
              "trailer_template", "EXPORT 用")),
      Map.entry(
          CHANNEL_SHEET, Map.of("config_json", "DISPATCH 用（endpoint + 凭据）；IMPORT.RECEIVE 用源凭据")),
      Map.entry(
          WF_NODE_SHEET,
          Map.of(
              "node_type", "WORKFLOW 内部分类：START/END/TASK/GATEWAY/FILE_STEP/JOB",
              "related_job_code", "WORKFLOW 节点引用的其他 Job（任意 worker 类型）",
              "related_pipeline_code", "WORKFLOW FILE_STEP 节点引用的 pipeline")));

  private static String joinAllowedValues(ConsoleExcelStyles.ColumnGuide guide) {
    if (guide == null || guide.allowedValues().isEmpty()) {
      return EMPTY;
    }
    return String.join(" / ", guide.allowedValues());
  }

  private record GuideStyles(
      CellStyle head, CellStyle body, CellStyle required, CellStyle optional) {}

  private void writeGuideCell(Row row, int col, String value, CellStyle style) {
    Cell cell = row.createCell(col);
    cell.setCellValue(value);
    cell.setCellStyle(style);
  }
}
