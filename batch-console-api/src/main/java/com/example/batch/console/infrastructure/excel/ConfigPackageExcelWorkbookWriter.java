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

import com.example.batch.common.enums.DictEnum;
import com.example.batch.common.enums.FileChecksumType;
import com.example.batch.common.enums.FileCompressType;
import com.example.batch.common.enums.FileEncryptType;
import com.example.batch.common.enums.FileTemplateFormat;
import com.example.batch.common.enums.FileTemplateType;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.console.infrastructure.excel.ConfigPackageExcelValidator.PackageValidationResult;
import com.example.batch.console.infrastructure.excel.ConfigPackageExcelValidator.SheetResult;
import com.example.batch.console.support.excel.ConsoleExcelPreviewWorkbookSupport;
import com.example.batch.console.support.excel.ConsoleExcelPreviewWorkbookSupport.WorkbookIssue;
import com.example.batch.console.support.excel.ConsoleExcelStyles;
import com.example.batch.console.support.excel.TenantConfigPackageExcelImportStore.PackageExcelSession;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
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
 * Generates Excel workbooks (export, template, preview) for the tenant config package. Extracted
 * from DefaultConsoleTenantConfigPackageExcelApplicationService to reduce class size.
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
  static final String[] JOB_TYPE_DROPDOWN =
      com.example.batch.common.enums.DictEnum.codeList(com.example.batch.common.enums.JobType.class)
          .toArray(String[]::new);

  static final String[] SCHEDULE_TYPE_DROPDOWN =
      com.example.batch.common.enums.DictEnum.codeList(
              com.example.batch.common.enums.ScheduleType.class)
          .toArray(String[]::new);

  static final String[] PIPELINE_TYPE_DROPDOWN =
      com.example.batch.common.enums.DictEnum.codeList(
              com.example.batch.common.enums.PipelineType.class)
          .toArray(String[]::new);

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
  private static final String GUIDE_BOOL_HINT = "请填写 TRUE 或 FALSE";
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
  private static final int[] FILE_TEMPLATE_BOOLEAN_COLUMNS = {8, 27, 31, 32, 33, 34, 36, 38};

  public static final List<String> JOB_COLUMNS =
      List.of(
          COL_TENANT_ID,
          COL_JOB_CODE,
          COL_JOB_NAME,
          COL_JOB_TYPE,
          COL_BIZ_TYPE,
          COL_QUEUE_CODE,
          COL_WORKER_GROUP,
          COL_SCHEDULE_TYPE,
          COL_SCHEDULE_EXPR,
          COL_CALENDAR_CODE,
          COL_WINDOW_CODE,
          COL_RETRY_POLICY,
          COL_RETRY_MAX_COUNT,
          COL_TIMEOUT_SECONDS,
          COL_SHARD_STRATEGY,
          COL_EXECUTION_HANDLER,
          COL_PARAM_SCHEMA,
          COL_DEFAULT_PARAMS,
          COL_ENABLED,
          COL_DESCRIPTION);

  public static final List<String> CHANNEL_COLUMNS =
      List.of(
          COL_TENANT_ID,
          COL_CHANNEL_CODE,
          COL_CHANNEL_NAME,
          COL_CHANNEL_TYPE,
          "target_endpoint",
          COL_AUTH_TYPE,
          COL_CONFIG_JSON,
          COL_RECEIPT_POLICY,
          COL_TIMEOUT_SECONDS,
          COL_ENABLED);

  public static final List<String> FILE_TEMPLATE_COLUMNS =
      List.of(
          COL_TENANT_ID,
          "template_code",
          "template_name",
          "template_type",
          COL_BIZ_TYPE,
          "file_format_type",
          "charset",
          "target_charset",
          "with_bom",
          "line_separator",
          "delimiter",
          "quote_char",
          "escape_char",
          "record_length",
          "header_rows",
          "footer_rows",
          "header_template",
          "trailer_template",
          "checksum_type",
          "compress_type",
          "encrypt_type",
          "naming_rule",
          "field_mappings",
          "validation_rule_set",
          "default_query_code",
          "default_query_sql",
          "query_param_schema",
          "streaming_enabled",
          "page_size",
          "fetch_size",
          "chunk_size",
          "preview_masking_enabled",
          "error_line_masking_enabled",
          "log_masking_enabled",
          "content_encryption_enabled",
          "encryption_key_ref",
          "download_requires_approval",
          "masking_rule_set",
          COL_ENABLED,
          COL_VERSION,
          COL_DESCRIPTION);

  public static final List<String> PIPELINE_COLUMNS =
      List.of(
          COL_TENANT_ID,
          COL_JOB_CODE,
          COL_PIPELINE_NAME,
          COL_PIPELINE_TYPE,
          COL_BIZ_TYPE,
          COL_WORKER_GROUP,
          COL_VERSION,
          COL_ENABLED,
          COL_DESCRIPTION);

  public static final List<String> STEP_COLUMNS =
      List.of(
          COL_JOB_CODE,
          COL_VERSION,
          COL_STEP_CODE,
          COL_STEP_NAME,
          COL_STAGE_CODE,
          "step_order",
          "impl_code",
          "step_params",
          COL_TIMEOUT_SECONDS,
          COL_RETRY_POLICY,
          COL_RETRY_MAX_COUNT,
          COL_ENABLED);

  public static final List<String> WF_DEF_COLUMNS =
      List.of(
          COL_TENANT_ID,
          COL_WORKFLOW_CODE,
          COL_WORKFLOW_NAME,
          COL_WORKFLOW_TYPE,
          COL_VERSION,
          COL_ENABLED,
          COL_DESCRIPTION);

  public static final List<String> WF_NODE_COLUMNS =
      List.of(
          COL_TENANT_ID,
          COL_WORKFLOW_CODE,
          COL_WORKFLOW_VERSION,
          COL_NODE_CODE,
          COL_NODE_NAME,
          COL_NODE_TYPE,
          COL_RELATED_JOB_CODE,
          COL_RELATED_PIPELINE_CODE,
          COL_WORKER_GROUP,
          COL_WINDOW_CODE,
          COL_NODE_ORDER,
          COL_RETRY_POLICY,
          COL_RETRY_MAX_COUNT,
          COL_TIMEOUT_SECONDS,
          COL_NODE_PARAMS,
          COL_ENABLED);

  public static final List<String> WF_EDGE_COLUMNS =
      List.of(
          COL_TENANT_ID,
          COL_WORKFLOW_CODE,
          COL_WORKFLOW_VERSION,
          COL_FROM_NODE_CODE,
          COL_TO_NODE_CODE,
          COL_EDGE_TYPE,
          COL_CONDITION_EXPR,
          COL_ENABLED);

  private record SheetDef(
      String name,
      List<String> columns,
      Map<String, ConsoleExcelStyles.ColumnGuide> guides,
      BiConsumer<Sheet, Locale> validationApplier) {}

  private final List<SheetDef> sheetDefs;
  private final MessageSource messageSource;
  // module → 该模块的 bean name 列表；applyStepValidations 会拼成 MODULE:beanName 格式的下拉项。
  // 由调用方（DefaultConsoleTenantConfigPackageExcelApplicationService）在每次 buildXxx 前从
  // batch.step_registry 查并 set；空/null 时不加 impl_code 下拉（避免 worker 未启动时模板不可下载）。
  private Map<String, List<String>> registeredImplCodesByModule;

  /** 设置本次导出用的 (module → impl_code 列表)；由调用方在 build* 前从 step_registry 查出。 */
  public void setRegisteredImplCodesByModule(
      Map<String, List<String>> registeredImplCodesByModule) {
    this.registeredImplCodesByModule = registeredImplCodesByModule;
  }

  public ConfigPackageExcelWorkbookWriter(MessageSource messageSource) {
    this.messageSource = messageSource;
    this.sheetDefs =
        List.of(
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
    Locale locale = LocaleContextHolder.getLocale();
    try (SXSSFWorkbook wb = new SXSSFWorkbook(50);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      for (int i = 0; i < sheetDefs.size(); i++) {
        SheetDef def = sheetDefs.get(i);
        writeDataSheet(wb, def, sheetDataList.get(i), locale);
      }
      createReadmeSheet(wb, locale);
      createFieldGuideSheet(wb);
      ConsoleExcelStyles.createValidationSheet(wb);
      wb.write(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw BizException.of(ResultCode.SYSTEM_ERROR, "error.excel.export_workbook_failed");
    }
  }

  public byte[] buildTemplateWorkbook() {
    Locale locale = LocaleContextHolder.getLocale();
    try (SXSSFWorkbook wb = new SXSSFWorkbook(50);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      for (SheetDef def : sheetDefs) {
        writeDataSheet(wb, def, List.of(), locale);
      }
      createReadmeSheet(wb, locale);
      createFieldGuideSheet(wb);
      ConsoleExcelStyles.createValidationSheet(wb);
      wb.write(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw BizException.of(ResultCode.SYSTEM_ERROR, "error.excel.template_workbook_failed");
    }
  }

  public byte[] buildPreviewWorkbook(PackageExcelSession session, PackageValidationResult result) {
    Locale locale = LocaleContextHolder.getLocale();
    List<List<Map<String, String>>> sessionData =
        List.of(
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
        writePreviewSheet(wb, def, sessionData.get(i), results.get(i).issues(), locale);
      }
      ConsoleExcelPreviewWorkbookSupport.populateValidationSheet(wb, result.allIssues());
      return ConsoleExcelPreviewWorkbookSupport.toBytes(wb);
    } catch (IOException e) {
      throw BizException.of(ResultCode.SYSTEM_ERROR, "error.excel.preview_failed");
    }
  }

  private void writeDataSheet(
      Workbook wb, SheetDef def, List<Map<String, Object>> dataRows, Locale locale) {
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
    def.validationApplier().accept(sheet, locale);
    setWidths(sheet, def.columns());
  }

  private void writePreviewSheet(
      Workbook wb,
      SheetDef def,
      List<Map<String, String>> dataRows,
      List<WorkbookIssue> sheetIssues,
      Locale locale) {
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
    def.validationApplier().accept(sheet, locale);
    ConsoleExcelPreviewWorkbookSupport.addIssueComments(sheet, def.columns(), sheetIssues, 0);
    setWidths(sheet, def.columns());
  }

  private void createReadmeSheet(Workbook wb, Locale locale) {
    Sheet sheet = wb.createSheet(ConsoleExcelStyles.SHEET_NAME_README);
    sheet.setColumnWidth(0, 18000);
    CellStyle title = createReadmeTitleStyle(wb);
    String[] keys = {
      "excel.package.readme.title",
      "excel.package.readme.line1",
      "excel.package.readme.line2",
      "excel.package.readme.line3",
      "excel.package.readme.line4",
      "excel.package.readme.line5",
      "excel.package.readme.line6",
      "excel.package.readme.line7",
      "excel.package.readme.line8",
      "excel.package.readme.line9"
    };
    for (int i = 0; i < keys.length; i++) {
      Row row = sheet.createRow(i);
      Cell cell = row.createCell(0);
      cell.setCellValue(messageSource.getMessage(keys[i], null, keys[i], locale));
      if (i == 0) {
        cell.setCellStyle(title);
      }
    }
  }

  private void createFieldGuideSheet(Workbook wb) {
    Sheet sheet = wb.createSheet(ConsoleExcelStyles.SHEET_NAME_GUIDE);
    setGuideColumnWidths(sheet);
    GuideStyles styles = buildGuideStyles(wb);
    writeGuideHeader(sheet, styles.head());
    int rowIdx = 1;
    for (SheetDef spec : sheetDefs) {
      for (int ci = 0; ci < spec.columns().size(); ci++) {
        String colName = spec.columns().get(ci);
        Row row = sheet.createRow(rowIdx++);
        row.setHeightInPoints(18);
        writeGuideRow(
            row, ci == 0 ? spec.name() : EMPTY, colName, spec.guides().get(colName), styles);
      }
    }
  }

  private static void setGuideColumnWidths(Sheet sheet) {
    sheet.setColumnWidth(0, 7000);
    sheet.setColumnWidth(1, 7000);
    sheet.setColumnWidth(2, 3500);
    sheet.setColumnWidth(3, 3500);
    sheet.setColumnWidth(4, 14000);
    sheet.setColumnWidth(5, 18000);
    sheet.setColumnWidth(6, 7000);
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
    String[] headers = {"所属 Sheet", "列名", "必填", "类型", "可选值", "说明", "示例"};
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
  }

  private static String guideOrEmpty(
      ConsoleExcelStyles.ColumnGuide guide,
      Function<ConsoleExcelStyles.ColumnGuide, String> getter) {
    return guide == null ? EMPTY : getter.apply(guide);
  }

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
    boolDropdown(sheet, 18, locale);
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
