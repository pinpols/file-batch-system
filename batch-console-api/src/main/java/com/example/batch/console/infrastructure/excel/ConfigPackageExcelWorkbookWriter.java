package com.example.batch.console.infrastructure.excel;

import static com.example.batch.console.infrastructure.excel.ConfigPackageExcelValidator.*;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.addDropdownValidation;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.createOptionalMarkStyle;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.createReadmeTitleStyle;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.createRequiredMarkStyle;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.optionalColumn;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.requiredColumn;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.setWidths;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.writeHeaders;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.writeTemplateHeaders;

import com.example.batch.common.enums.BatchWindowEndStrategy;
import com.example.batch.common.enums.CatchUpPolicyType;
import com.example.batch.common.enums.DictEnum;
import com.example.batch.common.enums.FileChecksumType;
import com.example.batch.common.enums.FileCompressType;
import com.example.batch.common.enums.FileEncryptType;
import com.example.batch.common.enums.FileTemplateFormat;
import com.example.batch.common.enums.FileTemplateType;
import com.example.batch.common.enums.HolidayRollRule;
import com.example.batch.common.enums.JobType;
import com.example.batch.common.enums.OutOfWindowAction;
import com.example.batch.common.enums.PipelineType;
import com.example.batch.common.enums.QueuePriorityPolicy;
import com.example.batch.common.enums.ResourceQueueType;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.enums.ScheduleType;
import com.example.batch.common.exception.BizException;
import com.example.batch.console.infrastructure.excel.ConfigPackageExcelValidator.PackageValidationResult;
import com.example.batch.console.infrastructure.excel.ConfigPackageExcelValidator.SheetResult;
import com.example.batch.console.support.excel.ConsoleExcelPreviewWorkbookSupport;
import com.example.batch.console.support.excel.ConsoleExcelPreviewWorkbookSupport.WorkbookIssue;
import com.example.batch.console.support.excel.ConsoleExcelStyles;
import com.example.batch.console.support.excel.TenantConfigPackageExcelImportStore.PackageExcelSession;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
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

  /**
   * 枚举下拉数组 — 单一权威源，全部从 enum 声明顺序 + ConfigPackageExcelValidator 集合派生。
   *
   * <p>下拉项展示顺序 = enum 声明顺序（业务认知顺序），不直接用 Set#toArray 的 unordered 输出。 CI 单测 {@code
   * ConfigPackageEnumDropdownConsistencyTest} 锁定这 4 个数组的内容 == validator 集合， 防止文档侧漂移（job_type 漏
   * PROCESS / schedule_type 多 EVENT/ONE_TIME / pipeline_type 漏 PROCESS / stage_code 含 TRANSFER
   * 等历史漂移问题已修复，CI 守护后续不再退步）。
   */
  static final String[] JOB_TYPE_DROPDOWN = DictEnum.codeList(JobType.class).toArray(String[]::new);

  static final String[] SCHEDULE_TYPE_DROPDOWN =
      DictEnum.codeList(ScheduleType.class).toArray(String[]::new);

  static final String[] PIPELINE_TYPE_DROPDOWN =
      DictEnum.codeList(PipelineType.class).toArray(String[]::new);

  /**
   * stage_code 在 worker 侧拆 3 enum（ImportStage / ExportStage / DispatchStage / ProcessStage 等模块自管），
   * 这里只取 validator 的 union 集合，并按业务流转顺序固定列出，避免 Set 转 array 出现非预期顺序。
   */
  static final String[] STAGE_CODE_DROPDOWN = {
    "RECEIVE",
    "PREPROCESS",
    "PARSE",
    "VALIDATE",
    "LOAD",
    "FEEDBACK",
    "PREPARE",
    "COMPUTE",
    "GENERATE",
    "STORE",
    "REGISTER",
    "COMPLETE",
    "COMMIT",
    "DISPATCH",
    "ACK",
    "RETRY",
    "COMPENSATE"
  };

  private static final String GUIDE_IMPORT = "IMPORT";
  private static final String GUIDE_TIMEOUT_DESC = "超时秒数。";
  private static final String GUIDE_DESC_DESC = "描述。";
  private static final String GUIDE_STR = "字符串";
  private static final String GUIDE_ENUM = "枚举";
  private static final String GUIDE_INT = "整数";
  private static final String GUIDE_BOOL = "布尔值";
  private static final String GUIDE_JSON = "JSON";
  private static final String GUIDE_SQL = "SQL";
  private static final String GUIDE_TRUE = "TRUE";
  private static final String GUIDE_FALSE = "FALSE";
  private static final String GUIDE_NONE = "NONE";
  private static final String GUIDE_ENABLED_DESC = "是否启用。";
  private static final String GUIDE_TENANT_DESC = "所属租户。";
  private static final String GUIDE_TENANT_EXAMPLE = "tenant-a";
  private static final String GUIDE_JOB_EXAMPLE = "JOB_IMPORT_CUSTOMER";
  private static final String GUIDE_EMPTY_JSON = "{}";
  private static final String GUIDE_VERSION_ONE = "1";
  private static final Set<String> FILE_TEMPLATE_TYPES = DictEnum.codes(FileTemplateType.class);
  private static final Set<String> FILE_FORMAT_TYPES = DictEnum.codes(FileTemplateFormat.class);
  private static final Set<String> CHECKSUM_TYPES = DictEnum.codes(FileChecksumType.class);
  private static final Set<String> COMPRESS_TYPES = DictEnum.codes(FileCompressType.class);
  private static final Set<String> ENCRYPT_TYPES = DictEnum.codes(FileEncryptType.class);
  private static final Set<String> QUEUE_TYPES = DictEnum.codes(ResourceQueueType.class);
  private static final Set<String> PRIORITY_POLICIES = DictEnum.codes(QueuePriorityPolicy.class);
  private static final Set<String> HOLIDAY_ROLL_RULES = DictEnum.codes(HolidayRollRule.class);
  private static final Set<String> CATCH_UP_POLICIES = DictEnum.codes(CatchUpPolicyType.class);
  private static final Set<String> END_STRATEGIES = DictEnum.codes(BatchWindowEndStrategy.class);
  private static final Set<String> OUT_OF_WINDOW_ACTIONS = DictEnum.codes(OutOfWindowAction.class);
  private static final int[] FILE_TEMPLATE_BOOLEAN_COLUMNS = {8, 27, 31, 32, 33, 34, 36, 38};

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

  private record SheetDef(
      String name,
      List<String> columns,
      Map<String, ConsoleExcelStyles.ColumnGuide> guides,
      BiConsumer<Sheet, Locale> validationApplier) {}

  private final List<SheetDef> sheetDefs;
  private final MessageSource messageSource;

  public ConfigPackageExcelWorkbookWriter(MessageSource messageSource) {
    this.messageSource = messageSource;
    this.sheetDefs =
        List.of(
            new SheetDef(
                RESOURCE_QUEUE_SHEET,
                RESOURCE_QUEUE_COLUMNS,
                buildResourceQueueGuides(),
                this::applyResourceQueueValidations),
            new SheetDef(
                BUSINESS_CALENDAR_SHEET,
                BUSINESS_CALENDAR_COLUMNS,
                buildBusinessCalendarGuides(),
                this::applyBusinessCalendarValidations),
            new SheetDef(
                BATCH_WINDOW_SHEET,
                BATCH_WINDOW_COLUMNS,
                buildBatchWindowGuides(),
                this::applyBatchWindowValidations),
            new SheetDef(JOB_SHEET, JOB_COLUMNS, buildJobGuides(), this::applyJobValidations),
            new SheetDef(
                CHANNEL_SHEET,
                CHANNEL_COLUMNS,
                buildChannelGuides(),
                this::applyChannelValidations),
            new SheetDef(
                FILE_TEMPLATE_SHEET,
                FILE_TEMPLATE_COLUMNS,
                buildFileTemplateGuides(),
                this::applyFileTemplateValidations),
            new SheetDef(
                PIPELINE_SHEET,
                PIPELINE_COLUMNS,
                buildPipelineGuides(),
                this::applyPipelineValidations),
            new SheetDef(STEP_SHEET, STEP_COLUMNS, buildStepGuides(), this::applyStepValidations),
            new SheetDef(
                WF_DEF_SHEET, WF_DEF_COLUMNS, buildWfDefGuides(), this::applyWfDefValidations),
            new SheetDef(
                WF_NODE_SHEET, WF_NODE_COLUMNS, buildWfNodeGuides(), this::applyWfNodeValidations),
            new SheetDef(
                WF_EDGE_SHEET, WF_EDGE_COLUMNS, buildWfEdgeGuides(), this::applyWfEdgeValidations));
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
        SheetDef def = sheetDefs.get(i);
        writeDataSheet(wb, def, sheetDataList.get(i), locale, implRegistry);
      }
      createReadmeSheet(wb, locale);
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
      for (SheetDef def : sheetDefs) {
        writeDataSheet(wb, def, List.of(), locale, implRegistry);
      }
      createReadmeSheet(wb, locale);
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
    List<List<Map<String, String>>> sessionData =
        List.of(
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
    List<SheetResult> results =
        List.of(
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
        SheetDef def = sheetDefs.get(i);
        writePreviewSheet(
            wb, def, sessionData.get(i), results.get(i).issues(), locale, implRegistry);
      }
      ConsoleExcelPreviewWorkbookSupport.populateValidationSheet(wb, result.allIssues());
      wb.write(out);
    }
  }

  private void writeDataSheet(
      Workbook wb,
      SheetDef def,
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
      SheetDef def,
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
      SheetDef def,
      Sheet sheet,
      Locale locale,
      Map<String, List<String>> registeredImplCodesByModule) {
    if (STEP_SHEET.equals(def.name())) {
      applyStepValidations(sheet, locale, registeredImplCodesByModule);
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
    registeredImplCodesByModule.forEach(
        (module, beans) -> {
          if (module == null || beans == null || beans.isEmpty()) {
            return;
          }
          copied.put(module, List.copyOf(beans));
        });
    return copied.isEmpty() ? Map.of() : Collections.unmodifiableMap(copied);
  }

  /**
   * 填写说明 sheet 内容总行数（标题 1 行 + line1..lineN）。详见 messages.properties。 内容含：sheet 范围 / 跨 sheet 依赖 / 5 类
   * Worker (IMPORT/EXPORT/PROCESS/DISPATCH/GENERAL) + WORKFLOW 完整配置 / Apply 流程 / UPSERT 行为。
   */
  private static final int README_LINE_COUNT = 86;

  private void createReadmeSheet(Workbook wb, Locale locale) {
    Sheet sheet = wb.createSheet(ConsoleExcelStyles.SHEET_NAME_README);
    sheet.setColumnWidth(0, 28000);
    CellStyle title = createReadmeTitleStyle(wb);
    // 标题 + line1..line46（v3 9+2 完整说明：sheet 范围 / 跨 sheet 依赖 /
    // 四类 Worker 填哪些 sheet / Apply 流程 / UPSERT 行为）
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

  private void createFieldGuideSheet(Workbook wb) {
    Sheet sheet = wb.createSheet(ConsoleExcelStyles.SHEET_NAME_GUIDE);
    setGuideColumnWidths(sheet);
    GuideStyles styles = buildGuideStyles(wb);
    writeGuideHeader(sheet, styles.head());
    int rowIdx = 1;
    // 记录每个 sheet 的起止行号，最后做第一列合并（per-sheet 一段，垂直居中），更美观、可读
    for (SheetDef spec : sheetDefs) {
      int sectionStart = rowIdx;
      for (int ci = 0; ci < spec.columns().size(); ci++) {
        String colName = spec.columns().get(ci);
        Row row = sheet.createRow(rowIdx++);
        row.setHeightInPoints(18);
        writeGuideRow(
            row,
            ci == 0 ? spec.name() : EMPTY,
            colName,
            spec.guides().get(colName),
            appliesToFor(spec.name(), colName),
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
    sheet.setColumnWidth(3, 3500); // 类型
    sheet.setColumnWidth(4, 14000); // 可选值
    sheet.setColumnWidth(5, 18000); // 说明
    sheet.setColumnWidth(6, 7000); // 示例
    sheet.setColumnWidth(7, 8000); // 适用 Worker
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
    String[] headers = {"所属 Sheet", "列名", "必填", "类型", "可选值", "说明", "示例", "适用 Worker"};
    for (int i = 0; i < headers.length; i++) {
      Cell c = header.createCell(i);
      c.setCellValue(headers[i]);
      c.setCellStyle(headStyle);
    }
  }

  private void writeGuideRow(
      Row row,
      String sectionLabel,
      String colName,
      ConsoleExcelStyles.ColumnGuide guide,
      String appliesTo,
      GuideStyles styles) {
    boolean isRequired = guide != null && guide.required();
    writeGuideCell(row, 0, sectionLabel, styles.body());
    writeGuideCell(row, 1, colName, styles.body());
    writeGuideCell(
        row, 2, isRequired ? "★ 必填" : "选填", isRequired ? styles.required() : styles.optional());
    writeGuideCell(
        row, 3, guideOrEmpty(guide, ConsoleExcelStyles.ColumnGuide::formatHint), styles.body());
    writeGuideCell(row, 4, joinAllowedValues(guide), styles.body());
    writeGuideCell(
        row, 5, guideOrEmpty(guide, ConsoleExcelStyles.ColumnGuide::description), styles.body());
    writeGuideCell(
        row, 6, guideOrEmpty(guide, ConsoleExcelStyles.ColumnGuide::example), styles.body());
    writeGuideCell(row, 7, appliesTo == null ? EMPTY : appliesTo, styles.body());
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
  private static final Map<String, String> APPLIES_TO_SHEET_DEFAULT =
      Map.ofEntries(
          Map.entry(RESOURCE_QUEUE_SHEET, "ALL（任意 Job 引用时必填）"),
          Map.entry(BUSINESS_CALENDAR_SHEET, "ALL（任意 Job 引用时必填）"),
          Map.entry(BATCH_WINDOW_SHEET, "ALL（任意 Job/Node 引用时必填）"),
          Map.entry(JOB_SHEET, "ALL（5 类 Worker + WORKFLOW 共用）"),
          Map.entry(CHANNEL_SHEET, "DISPATCH 主；IMPORT.RECEIVE 次"),
          Map.entry(FILE_TEMPLATE_SHEET, "IMPORT / EXPORT（DISPATCH 引用上游产物时间接用）"),
          Map.entry(PIPELINE_SHEET, "IMPORT / EXPORT / PROCESS / DISPATCH（按 pipeline_type）"),
          Map.entry(
              STEP_SHEET, "IMPORT / EXPORT / PROCESS / DISPATCH（按 pipeline_type 收窄 stage_code）"),
          Map.entry(WF_DEF_SHEET, "WORKFLOW（编排层，可组合其他 4 类 Job）"),
          Map.entry(WF_NODE_SHEET, "WORKFLOW"),
          Map.entry(WF_EDGE_SHEET, "WORKFLOW"));

  /**
   * Per-column 覆盖（少数 worker-specific 字段，比 sheet 默认更精确）。
   *
   * <p>没有列在这里的字段一律走 {@link #APPLIES_TO_SHEET_DEFAULT}。
   */
  private static final Map<String, Map<String, String>> APPLIES_TO_OVERRIDE =
      Map.ofEntries(
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
              CHANNEL_SHEET,
              Map.of("config_json", "DISPATCH 用（endpoint + 凭据）；IMPORT.RECEIVE 用源凭据")),
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

  private Map<String, ConsoleExcelStyles.ColumnGuide> buildResourceQueueGuides() {
    return Map.ofEntries(
        Map.entry(
            COL_TENANT_ID, optionalColumn(GUIDE_TENANT_DESC, GUIDE_STR, GUIDE_TENANT_EXAMPLE)),
        Map.entry("queue_code", requiredColumn("资源队列编码。", GUIDE_STR, "import-queue")),
        Map.entry("queue_name", requiredColumn("资源队列名称。", GUIDE_STR, "导入主队列")),
        Map.entry(
            "queue_type",
            requiredColumn(
                "队列类型。", GUIDE_ENUM, GUIDE_IMPORT, "IMPORT", "EXPORT", "DISPATCH", "MIXED")),
        Map.entry("max_running_jobs", requiredColumn("最大并发作业数。", GUIDE_INT, "10")),
        Map.entry("max_running_partitions", requiredColumn("最大并发分区数。", GUIDE_INT, "20")),
        Map.entry("max_qps", requiredColumn("最大派发 QPS。", GUIDE_INT, "100")),
        Map.entry(COL_WORKER_GROUP, optionalColumn("Worker 分组。", GUIDE_STR, "import")),
        Map.entry("resource_tag", optionalColumn("资源标签。", GUIDE_STR, "standard")),
        Map.entry(
            "priority_policy",
            requiredColumn("优先级策略。", GUIDE_ENUM, "FIFO", "FIFO", "PRIORITY", "FAIR_SHARE")),
        Map.entry("fair_share_weight", requiredColumn("公平调度权重。", GUIDE_INT, "1")),
        Map.entry(
            COL_ENABLED,
            optionalColumn(GUIDE_ENABLED_DESC, GUIDE_BOOL, GUIDE_TRUE, GUIDE_TRUE, GUIDE_FALSE)),
        Map.entry(COL_DESCRIPTION, optionalColumn(GUIDE_DESC_DESC, GUIDE_STR, "导入任务默认资源队列")));
  }

  private Map<String, ConsoleExcelStyles.ColumnGuide> buildBusinessCalendarGuides() {
    return Map.ofEntries(
        Map.entry(
            COL_TENANT_ID, optionalColumn(GUIDE_TENANT_DESC, GUIDE_STR, GUIDE_TENANT_EXAMPLE)),
        Map.entry(
            ConfigPackageExcelSchema.BusinessCalendar.COL_CALENDAR_CODE,
            requiredColumn("业务日历编码。", GUIDE_STR, "default-calendar")),
        Map.entry(
            ConfigPackageExcelSchema.BusinessCalendar.COL_CALENDAR_NAME,
            requiredColumn("业务日历名称。", GUIDE_STR, "默认业务日历")),
        Map.entry(
            ConfigPackageExcelSchema.BusinessCalendar.COL_TIMEZONE,
            requiredColumn("时区 ID。", GUIDE_STR, "Asia/Shanghai")),
        Map.entry(
            ConfigPackageExcelSchema.BusinessCalendar.COL_HOLIDAY_ROLL_RULE,
            requiredColumn("节假日顺延规则。", GUIDE_ENUM, "SKIP", "SKIP", "NEXT_WORKDAY", "PREV_WORKDAY")),
        Map.entry(
            ConfigPackageExcelSchema.BusinessCalendar.COL_CATCH_UP_POLICY,
            requiredColumn("补跑策略。", GUIDE_ENUM, GUIDE_NONE, GUIDE_NONE, "AUTO", "MANUAL_APPROVAL")),
        Map.entry(
            ConfigPackageExcelSchema.BusinessCalendar.COL_CATCH_UP_MAX_DAYS,
            requiredColumn("最大补跑天数。", GUIDE_INT, "0")),
        Map.entry(
            ConfigPackageExcelSchema.BusinessCalendar.COL_HOLIDAYS,
            optionalColumn("节假日，逗号分隔 yyyy-MM-dd。", GUIDE_STR, "2026-01-01,2026-10-01")),
        Map.entry(
            COL_ENABLED,
            optionalColumn(GUIDE_ENABLED_DESC, GUIDE_BOOL, GUIDE_TRUE, GUIDE_TRUE, GUIDE_FALSE)),
        Map.entry(COL_DESCRIPTION, optionalColumn(GUIDE_DESC_DESC, GUIDE_STR, "默认业务日历")));
  }

  private Map<String, ConsoleExcelStyles.ColumnGuide> buildBatchWindowGuides() {
    return Map.ofEntries(
        Map.entry(
            COL_TENANT_ID, optionalColumn(GUIDE_TENANT_DESC, GUIDE_STR, GUIDE_TENANT_EXAMPLE)),
        Map.entry("window_code", requiredColumn("批次窗口编码。", GUIDE_STR, "always-open")),
        Map.entry("window_name", requiredColumn("批次窗口名称。", GUIDE_STR, "全天窗口")),
        Map.entry("timezone", requiredColumn("时区 ID。", GUIDE_STR, "Asia/Shanghai")),
        Map.entry("start_time", requiredColumn("开始时间 HH:mm 或 HH:mm:ss。", "时间", "00:00")),
        Map.entry("end_time", requiredColumn("结束时间 HH:mm 或 HH:mm:ss。", "时间", "23:59")),
        Map.entry(
            "end_strategy",
            requiredColumn(
                "窗口结束策略。", GUIDE_ENUM, "FINISH_RUNNING", "STOP", "FINISH_RUNNING", "CONTINUE")),
        Map.entry(
            "out_of_window_action", requiredColumn("窗口外动作。", GUIDE_ENUM, "WAIT", "WAIT", "FAIL")),
        Map.entry(
            "allow_cross_day",
            optionalColumn("是否允许跨日。", GUIDE_BOOL, GUIDE_FALSE, GUIDE_TRUE, GUIDE_FALSE)),
        Map.entry(
            COL_ENABLED,
            optionalColumn(GUIDE_ENABLED_DESC, GUIDE_BOOL, GUIDE_TRUE, GUIDE_TRUE, GUIDE_FALSE)),
        Map.entry(COL_DESCRIPTION, optionalColumn(GUIDE_DESC_DESC, GUIDE_STR, "默认批次窗口")));
  }

  private void applyJobValidations(Sheet sheet, Locale locale) {
    addDropdownValidation(
        sheet,
        3,
        JOB_TYPES.toArray(String[]::new),
        "excel.job.def.job_type.prompt_title",
        "excel.job.def.job_type.prompt_box",
        messageSource,
        locale);
    addDropdownValidation(
        sheet,
        7,
        SCHEDULE_TYPES.toArray(String[]::new),
        "excel.job.def.schedule_type.prompt_title",
        "excel.job.def.schedule_type.prompt_box",
        messageSource,
        locale);
    addDropdownValidation(
        sheet,
        11,
        RETRY_POLICIES.toArray(String[]::new),
        "excel.job.def.retry_policy.prompt_title",
        "excel.job.def.retry_policy.prompt_box",
        messageSource,
        locale);
    addDropdownValidation(
        sheet,
        14,
        SHARD_STRATEGIES.toArray(String[]::new),
        "excel.job.def.shard_strategy.prompt_title",
        "excel.job.def.shard_strategy.prompt_box",
        messageSource,
        locale);
    addDropdownValidation(
        sheet,
        15,
        EXECUTION_MODES.toArray(String[]::new),
        "excel.job.def.execution_mode.prompt_title",
        "excel.job.def.execution_mode.prompt_box",
        messageSource,
        locale);
    boolDropdown(sheet, 20, locale);
  }

  private void applyChannelValidations(Sheet sheet, Locale locale) {
    addDropdownValidation(
        sheet,
        3,
        CHANNEL_TYPES.toArray(String[]::new),
        "excel.channel.channel_type.prompt_title",
        "excel.channel.channel_type.prompt_box",
        messageSource,
        locale);
    addDropdownValidation(
        sheet,
        5,
        AUTH_TYPES.toArray(String[]::new),
        "excel.channel.auth_type.prompt_title",
        "excel.channel.auth_type.prompt_box",
        messageSource,
        locale);
    addDropdownValidation(
        sheet,
        7,
        RECEIPT_POLICIES.toArray(String[]::new),
        "excel.channel.receipt_policy.prompt_title",
        "excel.channel.receipt_policy.prompt_box",
        messageSource,
        locale);
    boolDropdown(sheet, 9, locale);
  }

  private void applyResourceQueueValidations(Sheet sheet, Locale locale) {
    addDropdownValidation(
        sheet,
        3,
        QUEUE_TYPES.toArray(String[]::new),
        "excel.queue.queue_type.prompt_title",
        "excel.queue.queue_type.prompt_box",
        messageSource,
        locale);
    addDropdownValidation(
        sheet,
        9,
        PRIORITY_POLICIES.toArray(String[]::new),
        "excel.queue.priority_policy.prompt_title",
        "excel.queue.priority_policy.prompt_box",
        messageSource,
        locale);
    boolDropdown(sheet, 11, locale);
  }

  private void applyBusinessCalendarValidations(Sheet sheet, Locale locale) {
    addDropdownValidation(
        sheet,
        4,
        HOLIDAY_ROLL_RULES.toArray(String[]::new),
        "excel.calendar.holiday_roll_rule.prompt_title",
        "excel.calendar.holiday_roll_rule.prompt_box",
        messageSource,
        locale);
    addDropdownValidation(
        sheet,
        5,
        CATCH_UP_POLICIES.toArray(String[]::new),
        "excel.calendar.catch_up_policy.prompt_title",
        "excel.calendar.catch_up_policy.prompt_box",
        messageSource,
        locale);
    boolDropdown(sheet, 8, locale);
  }

  private void applyBatchWindowValidations(Sheet sheet, Locale locale) {
    addDropdownValidation(
        sheet,
        6,
        END_STRATEGIES.toArray(String[]::new),
        "excel.window.end_strategy.prompt_title",
        "excel.window.end_strategy.prompt_box",
        messageSource,
        locale);
    addDropdownValidation(
        sheet,
        7,
        OUT_OF_WINDOW_ACTIONS.toArray(String[]::new),
        "excel.window.out_of_window_action.prompt_title",
        "excel.window.out_of_window_action.prompt_box",
        messageSource,
        locale);
    boolDropdown(sheet, 8, locale);
    boolDropdown(sheet, 9, locale);
  }

  private void applyFileTemplateValidations(Sheet sheet, Locale locale) {
    addDropdownValidation(
        sheet,
        3,
        FILE_TEMPLATE_TYPES.toArray(String[]::new),
        "excel.template.template_type.prompt_title",
        "excel.template.template_type.prompt_box",
        messageSource,
        locale);
    addDropdownValidation(
        sheet,
        5,
        FILE_FORMAT_TYPES.toArray(String[]::new),
        "excel.template.file_format_type.prompt_title",
        "excel.template.file_format_type.prompt_box",
        messageSource,
        locale);
    addDropdownValidation(
        sheet,
        18,
        CHECKSUM_TYPES.toArray(String[]::new),
        "excel.template.checksum_type.prompt_title",
        "excel.template.checksum_type.prompt_box",
        messageSource,
        locale);
    addDropdownValidation(
        sheet,
        19,
        COMPRESS_TYPES.toArray(String[]::new),
        "excel.template.compress_type.prompt_title",
        "excel.template.compress_type.prompt_box",
        messageSource,
        locale);
    addDropdownValidation(
        sheet,
        20,
        ENCRYPT_TYPES.toArray(String[]::new),
        "excel.template.encrypt_type.prompt_title",
        "excel.template.encrypt_type.prompt_box",
        messageSource,
        locale);
    for (int col : FILE_TEMPLATE_BOOLEAN_COLUMNS) {
      boolDropdown(sheet, col, locale);
    }
  }

  private void applyPipelineValidations(Sheet sheet, Locale locale) {
    addDropdownValidation(
        sheet,
        3,
        PIPELINE_TYPES.toArray(String[]::new),
        "excel.pipeline.def.pipeline_type.prompt_title",
        "excel.pipeline.def.pipeline_type.prompt_box",
        messageSource,
        locale);
    boolDropdown(sheet, 7, locale);
  }

  private void applyStepValidations(Sheet sheet, Locale locale) {
    applyStepValidations(sheet, locale, Map.of());
  }

  private void applyStepValidations(
      Sheet sheet, Locale locale, Map<String, List<String>> registeredImplCodesByModule) {
    addDropdownValidation(
        sheet,
        4,
        STAGE_CODES.toArray(String[]::new),
        "excel.pipeline.step.stage_code.prompt_title",
        "excel.pipeline.step.stage_code.prompt_box",
        messageSource,
        locale);
    addDropdownValidation(
        sheet,
        9,
        RETRY_POLICIES.toArray(String[]::new),
        "excel.pipeline.step.retry_policy.prompt_title",
        "excel.pipeline.step.retry_policy.prompt_box",
        messageSource,
        locale);
    // impl_code(第 7 列 index=6):动态下拉,格式 MODULE:beanName 让用户一眼看出模块归属。
    // registry 空时不加下拉(首次部署没 worker 启动过也能下载模板)。
    if (registeredImplCodesByModule != null && !registeredImplCodesByModule.isEmpty()) {
      List<String> options = new ArrayList<>();
      registeredImplCodesByModule.forEach(
          (module, beans) -> {
            for (String bean : beans) {
              options.add(module + ":" + bean);
            }
          });
      if (!options.isEmpty()) {
        addDropdownValidation(
            sheet,
            6,
            options.toArray(String[]::new),
            "excel.package.impl_code.prompt_title",
            "excel.package.impl_code.prompt_box",
            messageSource,
            locale);
      }
    }
    boolDropdown(sheet, 11, locale);
  }

  private void applyWfDefValidations(Sheet sheet, Locale locale) {
    addDropdownValidation(
        sheet,
        3,
        WORKFLOW_TYPES.toArray(String[]::new),
        "excel.workflow.def.workflow_type.prompt_title",
        "excel.workflow.def.workflow_type.prompt_box",
        messageSource,
        locale);
    boolDropdown(sheet, 5, locale);
  }

  private void applyWfNodeValidations(Sheet sheet, Locale locale) {
    addDropdownValidation(
        sheet,
        5,
        NODE_TYPES.toArray(String[]::new),
        "excel.workflow.node.node_type.prompt_title",
        "excel.workflow.node.node_type.prompt_box",
        messageSource,
        locale);
    addDropdownValidation(
        sheet,
        11,
        RETRY_POLICIES.toArray(String[]::new),
        "excel.workflow.node.retry_policy.prompt_title",
        "excel.workflow.node.retry_policy.prompt_box",
        messageSource,
        locale);
    boolDropdown(sheet, 15, locale);
  }

  private void applyWfEdgeValidations(Sheet sheet, Locale locale) {
    addDropdownValidation(
        sheet,
        5,
        EDGE_TYPES.toArray(String[]::new),
        "excel.workflow.edge.edge_type.prompt_title",
        "excel.workflow.edge.edge_type.prompt_box",
        messageSource,
        locale);
    boolDropdown(sheet, 7, locale);
  }

  private void boolDropdown(Sheet sheet, int columnIndex, Locale locale) {
    addDropdownValidation(
        sheet,
        columnIndex,
        new String[] {"TRUE", "FALSE"},
        "excel.common.enabled.prompt_title",
        "excel.common.enabled.prompt_box",
        messageSource,
        locale);
  }

  private Map<String, ConsoleExcelStyles.ColumnGuide> buildJobGuides() {
    return Map.ofEntries(
        Map.entry(COL_TENANT_ID, optionalColumn("所属租户，留空使用当前租户。", GUIDE_STR, GUIDE_TENANT_EXAMPLE)),
        Map.entry(COL_JOB_CODE, requiredColumn("作业唯一编码。", GUIDE_STR, GUIDE_JOB_EXAMPLE)),
        Map.entry(COL_JOB_NAME, requiredColumn("作业名称。", GUIDE_STR, "客户导入作业")),
        Map.entry(
            COL_JOB_TYPE,
            requiredColumn(
                "作业类型。",
                GUIDE_ENUM,
                GUIDE_IMPORT,
                // 与 JobType enum / ConfigPackageExcelValidator.JOB_TYPES 对齐：
                // GENERAL / IMPORT / EXPORT / PROCESS / DISPATCH / WORKFLOW
                JOB_TYPE_DROPDOWN)),
        Map.entry(COL_BIZ_TYPE, optionalColumn("业务类型标识。", GUIDE_STR, "CUSTOMER")),
        Map.entry(COL_QUEUE_CODE, optionalColumn("资源队列编码。", GUIDE_STR, "import-queue")),
        Map.entry(COL_WORKER_GROUP, optionalColumn("Worker 分组。", GUIDE_STR, "import")),
        Map.entry(
            COL_SCHEDULE_TYPE,
            requiredColumn(
                "调度类型。",
                GUIDE_ENUM,
                "MANUAL",
                // 与 ScheduleType enum / ConfigPackageExcelValidator.SCHEDULE_TYPES 对齐：
                // CRON / FIXED_RATE / MANUAL（不再包含历史值 EVENT / ONE_TIME，validator 已拒收）
                SCHEDULE_TYPE_DROPDOWN)),
        Map.entry(COL_SCHEDULE_EXPR, optionalColumn("调度表达式，CRON 时填写。", GUIDE_STR, "0 2 * * *")),
        Map.entry(COL_CALENDAR_CODE, optionalColumn("业务日历编码。", GUIDE_STR, "default-calendar")),
        Map.entry(COL_WINDOW_CODE, optionalColumn("批量窗口编码。", GUIDE_STR, "always-open")),
        Map.entry(
            COL_RETRY_POLICY,
            optionalColumn("重试策略。", GUIDE_ENUM, GUIDE_NONE, GUIDE_NONE, "FIXED", "EXPONENTIAL")),
        Map.entry(COL_RETRY_MAX_COUNT, optionalColumn("最大重试次数。", GUIDE_INT, "3")),
        Map.entry(COL_TIMEOUT_SECONDS, optionalColumn(GUIDE_TIMEOUT_DESC, GUIDE_INT, "3600")),
        Map.entry(
            COL_SHARD_STRATEGY,
            optionalColumn(
                "分片策略。", GUIDE_ENUM, GUIDE_NONE, GUIDE_NONE, "STATIC", "DYNAMIC", "AUTO")),
        Map.entry(
            COL_EXECUTION_MODE,
            optionalColumn(
                "执行模式。INCREMENTAL 需要同时填写 watermark_field。",
                GUIDE_ENUM,
                "FULL",
                "FULL",
                "INCREMENTAL",
                "CDC")),
        Map.entry(
            COL_WATERMARK_FIELD,
            optionalColumn("增量水位字段名；execution_mode=INCREMENTAL 时填写。", GUIDE_STR, "updated_at")),
        Map.entry(
            COL_EXECUTION_HANDLER,
            optionalColumn("执行处理器 Bean 名称（新建时设置，更新时忽略）。", GUIDE_STR, "importJobHandler")),
        Map.entry(
            COL_PARAM_SCHEMA,
            optionalColumn("参数 JSON Schema（新建时设置，更新时忽略）。", GUIDE_JSON, GUIDE_EMPTY_JSON)),
        Map.entry(
            COL_DEFAULT_PARAMS,
            optionalColumn("默认参数 JSON（新建时设置，更新时忽略）。", GUIDE_JSON, GUIDE_EMPTY_JSON)),
        Map.entry(
            COL_ENABLED,
            optionalColumn(GUIDE_ENABLED_DESC, GUIDE_BOOL, GUIDE_TRUE, GUIDE_TRUE, GUIDE_FALSE)),
        Map.entry(COL_DESCRIPTION, optionalColumn(GUIDE_DESC_DESC, GUIDE_STR, "客户文件导入作业")));
  }

  private Map<String, ConsoleExcelStyles.ColumnGuide> buildChannelGuides() {
    return Map.ofEntries(
        Map.entry(
            COL_TENANT_ID, optionalColumn(GUIDE_TENANT_DESC, GUIDE_STR, GUIDE_TENANT_EXAMPLE)),
        Map.entry(COL_CHANNEL_CODE, requiredColumn("通道唯一编码。", GUIDE_STR, "sftp_inbound")),
        Map.entry(COL_CHANNEL_NAME, requiredColumn("通道名称。", GUIDE_STR, "SFTP 入站通道")),
        Map.entry(
            COL_CHANNEL_TYPE,
            requiredColumn(
                "通道类型。",
                GUIDE_ENUM,
                "SFTP",
                "SFTP",
                "API",
                "EMAIL",
                "NAS",
                "OSS",
                "LOCAL",
                "API_PUSH")),
        Map.entry("target_endpoint", optionalColumn("目标地址。", GUIDE_STR, "sftp.example.com")),
        Map.entry(
            COL_AUTH_TYPE,
            requiredColumn(
                "认证类型。",
                GUIDE_ENUM,
                "PASSWORD",
                GUIDE_NONE,
                "PASSWORD",
                "KEY_PAIR",
                "TOKEN",
                "OAUTH2",
                "CUSTOM")),
        Map.entry(COL_CONFIG_JSON, requiredColumn("通道配置 JSON。", GUIDE_JSON, GUIDE_EMPTY_JSON)),
        Map.entry(
            COL_RECEIPT_POLICY,
            requiredColumn(
                "回执策略。", GUIDE_ENUM, GUIDE_NONE, GUIDE_NONE, "SYNC", "ASYNC", "POLLING")),
        Map.entry(COL_TIMEOUT_SECONDS, optionalColumn(GUIDE_TIMEOUT_DESC, GUIDE_INT, "30")),
        Map.entry(
            COL_ENABLED,
            optionalColumn(GUIDE_ENABLED_DESC, GUIDE_BOOL, GUIDE_TRUE, GUIDE_TRUE, GUIDE_FALSE)));
  }

  private Map<String, ConsoleExcelStyles.ColumnGuide> buildFileTemplateGuides() {
    return Map.ofEntries(
        Map.entry(
            COL_TENANT_ID, optionalColumn(GUIDE_TENANT_DESC, GUIDE_STR, GUIDE_TENANT_EXAMPLE)),
        Map.entry("template_code", requiredColumn("文件模板唯一编码。", GUIDE_STR, "TPL_IMPORT_CUSTOMER")),
        Map.entry("template_name", requiredColumn("文件模板名称。", GUIDE_STR, "客户导入模板")),
        Map.entry(
            "template_type",
            requiredColumn("模板类型。", GUIDE_ENUM, "IMPORT", "IMPORT", "EXPORT", "SHARED")),
        Map.entry(COL_BIZ_TYPE, optionalColumn("业务类型。", GUIDE_STR, "CUSTOMER")),
        Map.entry(
            "file_format_type",
            requiredColumn(
                "文件格式。",
                GUIDE_ENUM,
                "DELIMITED",
                "DELIMITED",
                "FIXED_WIDTH",
                "EXCEL",
                "XML",
                "JSON",
                "BINARY")),
        Map.entry("charset", optionalColumn("源文件字符集。", GUIDE_STR, "UTF-8")),
        Map.entry("target_charset", optionalColumn("导出目标字符集。", GUIDE_STR, "UTF-8")),
        Map.entry(
            "with_bom",
            optionalColumn("是否带 BOM。", GUIDE_BOOL, GUIDE_FALSE, GUIDE_TRUE, GUIDE_FALSE)),
        Map.entry("line_separator", optionalColumn("换行符。", GUIDE_STR, "\\n")),
        Map.entry("delimiter", optionalColumn("分隔符。", GUIDE_STR, ",")),
        Map.entry("quote_char", optionalColumn("引用符。", GUIDE_STR, "\"")),
        Map.entry("escape_char", optionalColumn("转义符。", GUIDE_STR, "\\")),
        Map.entry("record_length", optionalColumn("定长文件记录长度。", GUIDE_INT, "0")),
        Map.entry("header_rows", optionalColumn("导入跳过头部行数。", GUIDE_INT, "1")),
        Map.entry("footer_rows", optionalColumn("导入跳过尾部行数。", GUIDE_INT, "0")),
        Map.entry("header_template", optionalColumn("导出头部模板 JSON。", GUIDE_JSON, GUIDE_EMPTY_JSON)),
        Map.entry("trailer_template", optionalColumn("导出尾部模板 JSON。", GUIDE_JSON, GUIDE_EMPTY_JSON)),
        Map.entry(
            "checksum_type",
            requiredColumn("校验类型。", GUIDE_ENUM, GUIDE_NONE, GUIDE_NONE, "MD5", "SHA256")),
        Map.entry(
            "compress_type",
            requiredColumn("压缩类型。", GUIDE_ENUM, GUIDE_NONE, GUIDE_NONE, "ZIP", "GZIP")),
        Map.entry(
            "encrypt_type",
            requiredColumn("加密类型。", GUIDE_ENUM, GUIDE_NONE, GUIDE_NONE, "PGP", "AES")),
        Map.entry("naming_rule", optionalColumn("文件命名规则。", GUIDE_STR, "customer_${batchDate}.csv")),
        Map.entry(
            "field_mappings",
            optionalColumn(
                "字段映射 JSON。", GUIDE_JSON, "[{\"source\":\"name\",\"target\":\"NAME\"}]")),
        Map.entry(
            "validation_rule_set",
            optionalColumn(
                "校验规则 JSON。", GUIDE_JSON, "[{\"field\":\"name\",\"rule\":\"required\"}]")),
        Map.entry(
            "default_query_code", optionalColumn("默认查询编码。", GUIDE_STR, "QRY_CUSTOMER_EXPORT")),
        Map.entry(
            "default_query_sql", optionalColumn("默认导出 SQL。", GUIDE_SQL, "select * from customer")),
        Map.entry(
            "query_param_schema",
            optionalColumn("查询参数 JSON Schema。", GUIDE_JSON, GUIDE_EMPTY_JSON)),
        Map.entry(
            "streaming_enabled",
            optionalColumn("是否流式处理。", GUIDE_BOOL, GUIDE_TRUE, GUIDE_TRUE, GUIDE_FALSE)),
        Map.entry("page_size", optionalColumn("分页大小。", GUIDE_INT, "1000")),
        Map.entry("fetch_size", optionalColumn("JDBC fetch size。", GUIDE_INT, "1000")),
        Map.entry("chunk_size", optionalColumn("分块大小。", GUIDE_INT, "500")),
        Map.entry(
            "preview_masking_enabled",
            optionalColumn("预览是否脱敏。", GUIDE_BOOL, GUIDE_FALSE, GUIDE_TRUE, GUIDE_FALSE)),
        Map.entry(
            "error_line_masking_enabled",
            optionalColumn("错误行是否脱敏。", GUIDE_BOOL, GUIDE_FALSE, GUIDE_TRUE, GUIDE_FALSE)),
        Map.entry(
            "log_masking_enabled",
            optionalColumn("日志是否脱敏。", GUIDE_BOOL, GUIDE_TRUE, GUIDE_TRUE, GUIDE_FALSE)),
        Map.entry(
            "content_encryption_enabled",
            optionalColumn("内容是否加密。", GUIDE_BOOL, GUIDE_FALSE, GUIDE_TRUE, GUIDE_FALSE)),
        Map.entry(
            "encryption_key_ref",
            optionalColumn("密钥引用。", GUIDE_STR, "kms://file-template/customer")),
        Map.entry(
            "download_requires_approval",
            optionalColumn("下载是否需要审批。", GUIDE_BOOL, GUIDE_FALSE, GUIDE_TRUE, GUIDE_FALSE)),
        Map.entry("masking_rule_set", optionalColumn("脱敏规则集编码。", GUIDE_STR, "MASK_CUSTOMER")),
        Map.entry(
            COL_ENABLED,
            optionalColumn(GUIDE_ENABLED_DESC, GUIDE_BOOL, GUIDE_TRUE, GUIDE_TRUE, GUIDE_FALSE)),
        Map.entry(COL_VERSION, optionalColumn("版本号。", GUIDE_INT, GUIDE_VERSION_ONE)),
        Map.entry(COL_DESCRIPTION, optionalColumn(GUIDE_DESC_DESC, GUIDE_STR, "客户导入文件模板")));
  }

  private Map<String, ConsoleExcelStyles.ColumnGuide> buildPipelineGuides() {
    return Map.ofEntries(
        Map.entry(
            COL_TENANT_ID, optionalColumn(GUIDE_TENANT_DESC, GUIDE_STR, GUIDE_TENANT_EXAMPLE)),
        Map.entry(
            COL_JOB_CODE, requiredColumn("关联作业编码，与 version 组成联合键。", GUIDE_STR, GUIDE_JOB_EXAMPLE)),
        Map.entry(COL_PIPELINE_NAME, requiredColumn("流水线名称。", GUIDE_STR, "客户导入流水线")),
        Map.entry(
            COL_PIPELINE_TYPE,
            requiredColumn(
                "流水线类型。",
                GUIDE_ENUM,
                GUIDE_IMPORT,
                // 与 PipelineType enum / ConfigPackageExcelValidator.PIPELINE_TYPES 对齐：
                // IMPORT / EXPORT / PROCESS / DISPATCH
                PIPELINE_TYPE_DROPDOWN)),
        Map.entry(COL_BIZ_TYPE, optionalColumn("业务类型。", GUIDE_STR, "CUSTOMER")),
        Map.entry(COL_WORKER_GROUP, optionalColumn("Worker 分组。", GUIDE_STR, "import")),
        Map.entry(
            COL_VERSION, requiredColumn("版本号，与 job_code 组成联合键。", GUIDE_INT, GUIDE_VERSION_ONE)),
        Map.entry(
            COL_ENABLED,
            optionalColumn(GUIDE_ENABLED_DESC, GUIDE_BOOL, GUIDE_TRUE, GUIDE_TRUE, GUIDE_FALSE)),
        Map.entry(COL_DESCRIPTION, optionalColumn(GUIDE_DESC_DESC, GUIDE_STR, "客户文件导入流水线")));
  }

  private Map<String, ConsoleExcelStyles.ColumnGuide> buildStepGuides() {
    return Map.ofEntries(
        Map.entry(COL_JOB_CODE, requiredColumn("关联流水线的 job_code。", GUIDE_STR, GUIDE_JOB_EXAMPLE)),
        Map.entry(COL_VERSION, requiredColumn("关联流水线的版本号。", GUIDE_INT, GUIDE_VERSION_ONE)),
        Map.entry(COL_STEP_CODE, requiredColumn("步骤唯一编码。", GUIDE_STR, "STEP_PARSE")),
        Map.entry(COL_STEP_NAME, requiredColumn("步骤名称。", GUIDE_STR, "解析文件")),
        Map.entry(
            COL_STAGE_CODE,
            requiredColumn(
                "阶段。按 pipeline_type 选对应"
                    + " stage：IMPORT[RECEIVE,PREPROCESS,PARSE,VALIDATE,LOAD,FEEDBACK]"
                    + "；EXPORT[PREPARE,GENERATE,STORE,REGISTER,COMPLETE]"
                    + "；PROCESS[PREPARE,COMPUTE,VALIDATE,COMMIT,FEEDBACK]"
                    + "；DISPATCH[PREPARE,DISPATCH,ACK,RETRY,COMPENSATE,COMPLETE]。",
                GUIDE_ENUM,
                "PARSE",
                // 17 个 stage 的 union，validator 按 pipeline_type 进一步收窄（STAGES_BY_TYPE）；
                // 旧值 TRANSFER 已删除（validator 不接受），文档侧不再出现
                STAGE_CODE_DROPDOWN)),
        Map.entry("step_order", optionalColumn("步骤顺序号。", GUIDE_INT, GUIDE_VERSION_ONE)),
        Map.entry("impl_code", optionalColumn("实现插件编码。", GUIDE_STR, "csvParser")),
        Map.entry("step_params", optionalColumn("步骤参数 JSON。", GUIDE_JSON, GUIDE_EMPTY_JSON)),
        Map.entry(COL_TIMEOUT_SECONDS, optionalColumn(GUIDE_TIMEOUT_DESC, GUIDE_INT, "300")),
        Map.entry(
            COL_RETRY_POLICY,
            optionalColumn("重试策略。", GUIDE_ENUM, GUIDE_NONE, GUIDE_NONE, "FIXED", "EXPONENTIAL")),
        Map.entry(COL_RETRY_MAX_COUNT, optionalColumn("最大重试次数。", GUIDE_INT, "0")),
        Map.entry(
            COL_ENABLED,
            optionalColumn(GUIDE_ENABLED_DESC, GUIDE_BOOL, GUIDE_TRUE, GUIDE_TRUE, GUIDE_FALSE)));
  }

  private Map<String, ConsoleExcelStyles.ColumnGuide> buildWfDefGuides() {
    return Map.ofEntries(
        Map.entry(
            COL_TENANT_ID, optionalColumn(GUIDE_TENANT_DESC, GUIDE_STR, GUIDE_TENANT_EXAMPLE)),
        Map.entry(
            COL_WORKFLOW_CODE,
            requiredColumn("工作流唯一编码，三个工作流 sheet 共用此键。", GUIDE_STR, "WF_SETTLEMENT")),
        Map.entry(COL_WORKFLOW_NAME, requiredColumn("工作流名称。", GUIDE_STR, "清算工作流")),
        Map.entry(
            COL_WORKFLOW_TYPE,
            requiredColumn("工作流拓扑类型。", GUIDE_ENUM, "DAG", "DAG", "PIPELINE", "MIXED")),
        Map.entry(COL_VERSION, requiredColumn("版本号。", GUIDE_INT, GUIDE_VERSION_ONE)),
        Map.entry(
            COL_ENABLED,
            optionalColumn(GUIDE_ENABLED_DESC, GUIDE_BOOL, GUIDE_TRUE, GUIDE_TRUE, GUIDE_FALSE)),
        Map.entry(COL_DESCRIPTION, optionalColumn(GUIDE_DESC_DESC, GUIDE_STR, "清算批量工作流")));
  }

  private Map<String, ConsoleExcelStyles.ColumnGuide> buildWfNodeGuides() {
    return Map.ofEntries(
        Map.entry(
            COL_TENANT_ID, optionalColumn(GUIDE_TENANT_DESC, GUIDE_STR, GUIDE_TENANT_EXAMPLE)),
        Map.entry(COL_WORKFLOW_CODE, requiredColumn("所属工作流编码。", GUIDE_STR, "WF_SETTLEMENT")),
        Map.entry(COL_WORKFLOW_VERSION, requiredColumn("所属工作流版本号。", GUIDE_INT, GUIDE_VERSION_ONE)),
        Map.entry(COL_NODE_CODE, requiredColumn("节点唯一编码。", GUIDE_STR, "NODE_IMPORT")),
        Map.entry(COL_NODE_NAME, requiredColumn("节点名称。", GUIDE_STR, "导入节点")),
        Map.entry(
            COL_NODE_TYPE,
            requiredColumn(
                "节点类型。", GUIDE_ENUM, "JOB", "TASK", "GATEWAY", "FILE_STEP", "START", "END", "JOB")),
        Map.entry(
            COL_RELATED_JOB_CODE,
            optionalColumn(
                "关联的作业编码，需在本包 job_definition sheet 或库中存在。", GUIDE_STR, GUIDE_JOB_EXAMPLE)),
        Map.entry(
            COL_RELATED_PIPELINE_CODE,
            optionalColumn(
                "关联的流水线 job_code，需在本包 pipeline_definition sheet 或库中存在。",
                GUIDE_STR,
                GUIDE_JOB_EXAMPLE)),
        Map.entry(COL_WORKER_GROUP, optionalColumn("Worker 分组。", GUIDE_STR, "import")),
        Map.entry(COL_WINDOW_CODE, optionalColumn("批量窗口编码。", GUIDE_STR, "always-open")),
        Map.entry(COL_NODE_ORDER, optionalColumn("节点顺序号。", GUIDE_INT, GUIDE_VERSION_ONE)),
        Map.entry(
            COL_RETRY_POLICY,
            optionalColumn("重试策略。", GUIDE_ENUM, GUIDE_NONE, GUIDE_NONE, "FIXED", "EXPONENTIAL")),
        Map.entry(COL_RETRY_MAX_COUNT, optionalColumn("最大重试次数。", GUIDE_INT, "0")),
        Map.entry(COL_TIMEOUT_SECONDS, optionalColumn(GUIDE_TIMEOUT_DESC, GUIDE_INT, "3600")),
        Map.entry(COL_NODE_PARAMS, optionalColumn("节点参数 JSON。", GUIDE_JSON, GUIDE_EMPTY_JSON)),
        Map.entry(
            COL_ENABLED,
            optionalColumn(GUIDE_ENABLED_DESC, GUIDE_BOOL, GUIDE_TRUE, GUIDE_TRUE, GUIDE_FALSE)));
  }

  private Map<String, ConsoleExcelStyles.ColumnGuide> buildWfEdgeGuides() {
    return Map.ofEntries(
        Map.entry(
            COL_TENANT_ID, optionalColumn(GUIDE_TENANT_DESC, GUIDE_STR, GUIDE_TENANT_EXAMPLE)),
        Map.entry(COL_WORKFLOW_CODE, requiredColumn("所属工作流编码。", GUIDE_STR, "WF_SETTLEMENT")),
        Map.entry(COL_WORKFLOW_VERSION, requiredColumn("所属工作流版本号。", GUIDE_INT, GUIDE_VERSION_ONE)),
        Map.entry(COL_FROM_NODE_CODE, requiredColumn("源节点编码。", GUIDE_STR, "NODE_IMPORT")),
        Map.entry(COL_TO_NODE_CODE, requiredColumn("目标节点编码。", GUIDE_STR, "NODE_EXPORT")),
        Map.entry(
            COL_EDGE_TYPE,
            requiredColumn(
                "边类型。", GUIDE_ENUM, "SUCCESS", "SUCCESS", "FAILURE", "CONDITION", "ALWAYS")),
        Map.entry(COL_CONDITION_EXPR, optionalColumn("CONDITION 类型的条件表达式。", GUIDE_STR, EMPTY)),
        Map.entry(
            COL_ENABLED,
            optionalColumn(GUIDE_ENABLED_DESC, GUIDE_BOOL, GUIDE_TRUE, GUIDE_TRUE, GUIDE_FALSE)));
  }
}
