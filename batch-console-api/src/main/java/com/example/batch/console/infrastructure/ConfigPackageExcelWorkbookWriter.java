package com.example.batch.console.infrastructure;

import static com.example.batch.console.infrastructure.ConfigPackageExcelValidator.*;
import static com.example.batch.console.support.ConsoleExcelStyles.addBooleanValidation;
import static com.example.batch.console.support.ConsoleExcelStyles.addDropdownValidation;
import static com.example.batch.console.support.ConsoleExcelStyles.createOptionalMarkStyle;
import static com.example.batch.console.support.ConsoleExcelStyles.createReadmeTitleStyle;
import static com.example.batch.console.support.ConsoleExcelStyles.createRequiredMarkStyle;
import static com.example.batch.console.support.ConsoleExcelStyles.optionalColumn;
import static com.example.batch.console.support.ConsoleExcelStyles.requiredColumn;
import static com.example.batch.console.support.ConsoleExcelStyles.setWidths;
import static com.example.batch.console.support.ConsoleExcelStyles.writeHeaders;
import static com.example.batch.console.support.ConsoleExcelStyles.writeTemplateHeaders;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.console.infrastructure.ConfigPackageExcelValidator.PackageValidationResult;
import com.example.batch.console.infrastructure.ConfigPackageExcelValidator.SheetResult;
import com.example.batch.console.support.ConsoleExcelPreviewWorkbookSupport;
import com.example.batch.console.support.ConsoleExcelPreviewWorkbookSupport.WorkbookIssue;
import com.example.batch.console.support.ConsoleExcelStyles;
import com.example.batch.console.support.TenantConfigPackageExcelImportStore.PackageExcelSession;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Generates Excel workbooks (export, template, preview) for the tenant config package. Extracted
 * from DefaultConsoleTenantConfigPackageExcelApplicationService to reduce class size.
 */
class ConfigPackageExcelWorkbookWriter {

  private static final String EMPTY = "";

  private static final String GUIDE_IMPORT = "IMPORT";
  private static final String GUIDE_TIMEOUT_DESC = "超时秒数。";
  private static final String GUIDE_DESC_DESC = "描述。";
  private static final String GUIDE_STR = "字符串";
  private static final String GUIDE_ENUM = "枚举";
  private static final String GUIDE_INT = "整数";
  private static final String GUIDE_BOOL = "布尔值";
  private static final String GUIDE_JSON = "JSON";
  private static final String GUIDE_TRUE = "TRUE";
  private static final String GUIDE_FALSE = "FALSE";
  private static final String GUIDE_NONE = "NONE";
  private static final String GUIDE_BOOL_HINT = "请填写 TRUE 或 FALSE";
  private static final String GUIDE_ENABLED_DESC = "是否启用。";
  private static final String GUIDE_TENANT_DESC = "所属租户。";
  private static final String GUIDE_TENANT_EXAMPLE = "tenant-a";
  private static final String GUIDE_JOB_EXAMPLE = "JOB_IMPORT_CUSTOMER";
  private static final String GUIDE_DISPATCH = "DISPATCH";
  private static final String GUIDE_EMPTY_JSON = "{}";
  private static final String GUIDE_VERSION_ONE = "1";

  static final List<String> JOB_COLUMNS =
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

  static final List<String> CHANNEL_COLUMNS =
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

  static final List<String> ROUTING_COLUMNS =
      List.of(
          COL_TENANT_ID,
          COL_ROUTE_CODE,
          COL_ROUTE_NAME,
          COL_TEAM,
          COL_ALERT_GROUP,
          COL_SEVERITY,
          COL_RECEIVER,
          "group_by",
          "group_wait_seconds",
          "group_interval_seconds",
          "repeat_interval_seconds",
          COL_ENABLED,
          COL_DESCRIPTION);

  static final List<String> PIPELINE_COLUMNS =
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

  static final List<String> STEP_COLUMNS =
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

  static final List<String> WF_DEF_COLUMNS =
      List.of(
          COL_TENANT_ID,
          COL_WORKFLOW_CODE,
          COL_WORKFLOW_NAME,
          COL_WORKFLOW_TYPE,
          COL_VERSION,
          COL_ENABLED,
          COL_DESCRIPTION);

  static final List<String> WF_NODE_COLUMNS =
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

  static final List<String> WF_EDGE_COLUMNS =
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
      Consumer<Sheet> validationApplier) {}

  private final List<SheetDef> sheetDefs;
  // module → 该模块的 bean name 列表；applyStepValidations 会拼成 MODULE:beanName 格式的下拉项。
  // 由调用方（DefaultConsoleTenantConfigPackageExcelApplicationService）在每次 buildXxx 前从
  // batch.step_registry 查并 set；空/null 时不加 impl_code 下拉（避免 worker 未启动时模板不可下载）。
  private Map<String, List<String>> registeredImplCodesByModule;

  /** 设置本次导出用的 (module → impl_code 列表)；由调用方在 build* 前从 step_registry 查出。 */
  void setRegisteredImplCodesByModule(Map<String, List<String>> registeredImplCodesByModule) {
    this.registeredImplCodesByModule = registeredImplCodesByModule;
  }

  ConfigPackageExcelWorkbookWriter() {
    this.sheetDefs =
        List.of(
            new SheetDef(JOB_SHEET, JOB_COLUMNS, buildJobGuides(), this::applyJobValidations),
            new SheetDef(
                CHANNEL_SHEET,
                CHANNEL_COLUMNS,
                buildChannelGuides(),
                this::applyChannelValidations),
            new SheetDef(
                ROUTING_SHEET,
                ROUTING_COLUMNS,
                buildRoutingGuides(),
                this::applyRoutingValidations),
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

  byte[] buildExportWorkbook(List<List<Map<String, Object>>> sheetDataList) {
    try (SXSSFWorkbook wb = new SXSSFWorkbook(50);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      for (int i = 0; i < sheetDefs.size(); i++) {
        SheetDef def = sheetDefs.get(i);
        writeDataSheet(wb, def, sheetDataList.get(i));
      }
      createReadmeSheet(wb);
      createFieldGuideSheet(wb);
      ConsoleExcelStyles.createValidationSheet(wb);
      wb.write(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new BizException(ResultCode.SYSTEM_ERROR, "failed to generate export workbook");
    }
  }

  byte[] buildTemplateWorkbook() {
    try (SXSSFWorkbook wb = new SXSSFWorkbook(50);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      for (SheetDef def : sheetDefs) {
        writeDataSheet(wb, def, List.of());
      }
      createReadmeSheet(wb);
      createFieldGuideSheet(wb);
      ConsoleExcelStyles.createValidationSheet(wb);
      wb.write(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new BizException(ResultCode.SYSTEM_ERROR, "failed to generate template workbook");
    }
  }

  byte[] buildPreviewWorkbook(PackageExcelSession session, PackageValidationResult result) {
    List<List<Map<String, String>>> sessionData =
        List.of(
            session.jobRows(),
            session.fileChannelRows(),
            session.alertRoutingRows(),
            session.pipelineRows(),
            session.pipelineStepRows(),
            session.workflowDefinitionRows(),
            session.workflowNodeRows(),
            session.workflowEdgeRows());
    List<SheetResult> results =
        List.of(
            result.jobs(),
            result.channels(),
            result.routings(),
            result.pipelines(),
            result.steps(),
            result.wfDefs(),
            result.wfNodes(),
            result.wfEdges());
    try (XSSFWorkbook wb = new XSSFWorkbook()) {
      for (int i = 0; i < sheetDefs.size(); i++) {
        SheetDef def = sheetDefs.get(i);
        writePreviewSheet(wb, def, sessionData.get(i), results.get(i).issues());
      }
      ConsoleExcelPreviewWorkbookSupport.populateValidationSheet(wb, result.allIssues());
      return ConsoleExcelPreviewWorkbookSupport.toBytes(wb);
    } catch (IOException e) {
      throw new BizException(ResultCode.SYSTEM_ERROR, "failed to generate preview workbook");
    }
  }

  private void writeDataSheet(Workbook wb, SheetDef def, List<Map<String, Object>> dataRows) {
    Sheet sheet = wb.createSheet(def.name());
    sheet.createFreezePane(0, 1, 0, 1);
    writeTemplateHeaders(sheet, def.columns(), def.guides(), wb);
    int idx = 1;
    for (Map<String, Object> row : dataRows) {
      Row dataRow = sheet.createRow(idx++);
      for (int c = 0; c < def.columns().size(); c++) {
        Object val = row.get(def.columns().get(c));
        dataRow.createCell(c).setCellValue(val == null ? EMPTY : String.valueOf(val));
      }
    }
    def.validationApplier().accept(sheet);
    setWidths(sheet, def.columns());
  }

  private void writePreviewSheet(
      Workbook wb,
      SheetDef def,
      List<Map<String, String>> dataRows,
      List<WorkbookIssue> sheetIssues) {
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
    def.validationApplier().accept(sheet);
    ConsoleExcelPreviewWorkbookSupport.addIssueComments(sheet, def.columns(), sheetIssues, 0);
    setWidths(sheet, def.columns());
  }

  private void createReadmeSheet(Workbook wb) {
    Sheet sheet = wb.createSheet("README");
    sheet.setColumnWidth(0, 18000);
    CellStyle title = createReadmeTitleStyle(wb);
    String[] lines = {
      "Tenant Config Package Import Template",
      "Contains 8 sheets: job_definition / file_channel_config / alert_routing_config",
      "  / pipeline_definition / pipeline_step_definition",
      "  / workflow_definition / workflow_node / workflow_edge",
      "Import flow: upload -> preview -> apply (single transaction)",
      "Cross-sheet references validated: pipeline.job_code -> job_definition,",
      "  wf_node.related_job_code -> job_definition,",
      "  wf_node.related_pipeline_code -> pipeline_definition",
      "Job definitions: INSERT if not found, UPDATE mutable fields if found.",
      "All other types: UPSERT by unique key."
    };
    for (int i = 0; i < lines.length; i++) {
      Row row = sheet.createRow(i);
      Cell cell = row.createCell(0);
      cell.setCellValue(lines[i]);
      if (i == 0) {
        cell.setCellStyle(title);
      }
    }
  }

  private void createFieldGuideSheet(Workbook wb) {
    Sheet sheet = wb.createSheet("填写说明");
    sheet.setColumnWidth(0, 7000);
    sheet.setColumnWidth(1, 7000);
    sheet.setColumnWidth(2, 3500);
    sheet.setColumnWidth(3, 3500);
    sheet.setColumnWidth(4, 14000);
    sheet.setColumnWidth(5, 18000);
    sheet.setColumnWidth(6, 7000);

    CellStyle headStyle = ConsoleExcelStyles.createHeaderStyle(wb);
    CellStyle bodyStyle = ConsoleExcelStyles.createDataStyle(wb);
    bodyStyle.setWrapText(true);
    CellStyle requiredStyle = createRequiredMarkStyle(wb);
    requiredStyle.setWrapText(true);
    CellStyle optionalStyle = createOptionalMarkStyle(wb);
    optionalStyle.setWrapText(true);

    Row header = sheet.createRow(0);
    header.setHeightInPoints(22);
    String[] headers = {"所属 Sheet", "列名", "必填", "类型", "可选值", "说明", "示例"};
    for (int i = 0; i < headers.length; i++) {
      Cell c = header.createCell(i);
      c.setCellValue(headers[i]);
      c.setCellStyle(headStyle);
    }

    int rowIdx = 1;
    for (SheetDef spec : sheetDefs) {
      for (int ci = 0; ci < spec.columns().size(); ci++) {
        String colName = spec.columns().get(ci);
        ConsoleExcelStyles.ColumnGuide guide = spec.guides().get(colName);
        Row row = sheet.createRow(rowIdx++);
        row.setHeightInPoints(18);

        writeGuideCell(row, 0, ci == 0 ? spec.name() : EMPTY, bodyStyle);
        writeGuideCell(row, 1, colName, bodyStyle);
        boolean isRequired = guide != null && guide.required();
        writeGuideCell(
            row, 2, isRequired ? "★ 必填" : "选填", isRequired ? requiredStyle : optionalStyle);
        writeGuideCell(row, 3, guide != null ? guide.formatHint() : EMPTY, bodyStyle);
        String values =
            guide != null && !guide.allowedValues().isEmpty()
                ? String.join(" / ", guide.allowedValues())
                : EMPTY;
        writeGuideCell(row, 4, values, bodyStyle);
        writeGuideCell(row, 5, guide != null ? guide.description() : EMPTY, bodyStyle);
        writeGuideCell(row, 6, guide != null ? guide.example() : EMPTY, bodyStyle);
      }
    }
  }

  private void writeGuideCell(Row row, int col, String value, CellStyle style) {
    Cell cell = row.createCell(col);
    cell.setCellValue(value);
    cell.setCellStyle(style);
  }

  private void applyJobValidations(Sheet sheet) {
    addDropdownValidation(sheet, 3, JOB_TYPES.toArray(String[]::new), COL_JOB_TYPE, "请选择作业类型");
    addDropdownValidation(
        sheet, 7, SCHEDULE_TYPES.toArray(String[]::new), COL_SCHEDULE_TYPE, "请选择调度类型");
    addDropdownValidation(
        sheet, 11, RETRY_POLICIES.toArray(String[]::new), COL_RETRY_POLICY, "请选择重试策略");
    addDropdownValidation(
        sheet, 14, SHARD_STRATEGIES.toArray(String[]::new), COL_SHARD_STRATEGY, "请选择分片策略");
    addBooleanValidation(sheet, new int[] {18}, COL_ENABLED, GUIDE_BOOL_HINT);
  }

  private void applyChannelValidations(Sheet sheet) {
    addDropdownValidation(
        sheet, 3, CHANNEL_TYPES.toArray(String[]::new), COL_CHANNEL_TYPE, "请选择通道类型");
    addDropdownValidation(sheet, 5, AUTH_TYPES.toArray(String[]::new), COL_AUTH_TYPE, "请选择认证类型");
    addDropdownValidation(
        sheet, 7, RECEIPT_POLICIES.toArray(String[]::new), COL_RECEIPT_POLICY, "请选择回执策略");
    addBooleanValidation(sheet, new int[] {9}, COL_ENABLED, GUIDE_BOOL_HINT);
  }

  private void applyRoutingValidations(Sheet sheet) {
    addDropdownValidation(sheet, 5, SEVERITIES.toArray(String[]::new), COL_SEVERITY, "请选择告警级别");
    addBooleanValidation(sheet, new int[] {11}, COL_ENABLED, GUIDE_BOOL_HINT);
  }

  private void applyPipelineValidations(Sheet sheet) {
    addDropdownValidation(
        sheet, 3, PIPELINE_TYPES.toArray(String[]::new), COL_PIPELINE_TYPE, "请选择流水线类型");
    addBooleanValidation(sheet, new int[] {7}, COL_ENABLED, GUIDE_BOOL_HINT);
  }

  private void applyStepValidations(Sheet sheet) {
    addDropdownValidation(sheet, 4, STAGE_CODES.toArray(String[]::new), COL_STAGE_CODE, "请选择阶段");
    addDropdownValidation(
        sheet, 9, RETRY_POLICIES.toArray(String[]::new), COL_RETRY_POLICY, "请选择重试策略");
    // impl_code（第 7 列 index=6）：动态下拉，格式 MODULE:beanName 让用户一眼看出模块归属。
    // 上传时 Validator 会剥掉前缀比对 pipeline_type 与 module 是否匹配，不匹配会报错。
    // registry 空时不加下拉（首次部署没 worker 启动过也能下载模板）。
    if (registeredImplCodesByModule != null && !registeredImplCodesByModule.isEmpty()) {
      List<String> options = new java.util.ArrayList<>();
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
            "impl_code",
            "格式 MODULE:beanName（MODULE 来自 pipeline_type，beanName 来自 worker 启动时上报的 step_registry）");
      }
    }
    addBooleanValidation(sheet, new int[] {11}, COL_ENABLED, GUIDE_BOOL_HINT);
  }

  private void applyWfDefValidations(Sheet sheet) {
    addDropdownValidation(
        sheet, 3, WORKFLOW_TYPES.toArray(String[]::new), COL_WORKFLOW_TYPE, "请选择工作流类型");
    addBooleanValidation(sheet, new int[] {5}, COL_ENABLED, GUIDE_BOOL_HINT);
  }

  private void applyWfNodeValidations(Sheet sheet) {
    addDropdownValidation(sheet, 5, NODE_TYPES.toArray(String[]::new), COL_NODE_TYPE, "请选择节点类型");
    addDropdownValidation(
        sheet, 11, RETRY_POLICIES.toArray(String[]::new), COL_RETRY_POLICY, "请选择重试策略");
    addBooleanValidation(sheet, new int[] {15}, COL_ENABLED, GUIDE_BOOL_HINT);
  }

  private void applyWfEdgeValidations(Sheet sheet) {
    addDropdownValidation(sheet, 5, EDGE_TYPES.toArray(String[]::new), COL_EDGE_TYPE, "请选择边类型");
    addBooleanValidation(sheet, new int[] {7}, COL_ENABLED, GUIDE_BOOL_HINT);
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
                "GENERAL",
                GUIDE_IMPORT,
                "EXPORT",
                GUIDE_DISPATCH,
                "WORKFLOW")),
        Map.entry(COL_BIZ_TYPE, optionalColumn("业务类型标识。", GUIDE_STR, "CUSTOMER")),
        Map.entry(COL_QUEUE_CODE, optionalColumn("资源队列编码。", GUIDE_STR, "import-queue")),
        Map.entry(COL_WORKER_GROUP, optionalColumn("Worker 分组。", GUIDE_STR, "import")),
        Map.entry(
            COL_SCHEDULE_TYPE,
            requiredColumn(
                "调度类型。",
                GUIDE_ENUM,
                "MANUAL",
                "CRON",
                "FIXED_RATE",
                "MANUAL",
                "EVENT",
                "ONE_TIME")),
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

  private Map<String, ConsoleExcelStyles.ColumnGuide> buildRoutingGuides() {
    return Map.ofEntries(
        Map.entry(
            COL_TENANT_ID, optionalColumn(GUIDE_TENANT_DESC, GUIDE_STR, GUIDE_TENANT_EXAMPLE)),
        Map.entry(COL_ROUTE_CODE, requiredColumn("路由唯一编码。", GUIDE_STR, "RT_BATCH_ERROR")),
        Map.entry(COL_ROUTE_NAME, requiredColumn("路由名称。", GUIDE_STR, "批处理异常路由")),
        Map.entry(COL_TEAM, requiredColumn("负责团队。", GUIDE_STR, "ops")),
        Map.entry(COL_ALERT_GROUP, requiredColumn("告警分组。", GUIDE_STR, "batch")),
        Map.entry(
            COL_SEVERITY,
            requiredColumn("告警级别。", GUIDE_ENUM, "ERROR", "INFO", "WARN", "ERROR", "CRITICAL")),
        Map.entry(COL_RECEIVER, requiredColumn("接收方。", GUIDE_STR, "slack-ops")),
        Map.entry("group_by", optionalColumn("聚合分组键。", GUIDE_STR, COL_JOB_CODE)),
        Map.entry("group_wait_seconds", optionalColumn("聚合等待秒数。", GUIDE_INT, "30")),
        Map.entry("group_interval_seconds", optionalColumn("聚合间隔秒数。", GUIDE_INT, "300")),
        Map.entry("repeat_interval_seconds", optionalColumn("重复通知间隔秒数。", GUIDE_INT, "3600")),
        Map.entry(
            COL_ENABLED,
            optionalColumn(GUIDE_ENABLED_DESC, GUIDE_BOOL, GUIDE_TRUE, GUIDE_TRUE, GUIDE_FALSE)),
        Map.entry(COL_DESCRIPTION, optionalColumn(GUIDE_DESC_DESC, GUIDE_STR, "批处理失败默认路由")));
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
                "流水线类型。", GUIDE_ENUM, GUIDE_IMPORT, GUIDE_IMPORT, "EXPORT", GUIDE_DISPATCH)),
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
                "阶段。",
                GUIDE_ENUM,
                "PARSE",
                "RECEIVE",
                "PREPROCESS",
                "PARSE",
                "VALIDATE",
                "LOAD",
                "GENERATE",
                "TRANSFER",
                GUIDE_DISPATCH,
                "ACK")),
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
