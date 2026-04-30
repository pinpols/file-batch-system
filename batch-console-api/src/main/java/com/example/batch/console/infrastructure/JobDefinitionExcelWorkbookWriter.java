package com.example.batch.console.infrastructure;

import static com.example.batch.console.support.ConsoleExcelStyles.addDropdownValidation;
import static com.example.batch.console.support.ConsoleExcelStyles.createReadmeTitleStyle;
import static com.example.batch.console.support.ConsoleExcelStyles.optionalColumn;
import static com.example.batch.console.support.ConsoleExcelStyles.readOnlyColumn;
import static com.example.batch.console.support.ConsoleExcelStyles.requiredColumn;
import static com.example.batch.console.support.ConsoleExcelStyles.requiredReadOnlyColumn;
import static com.example.batch.console.support.ConsoleExcelStyles.setGuideColumnWidths;
import static com.example.batch.console.support.ConsoleExcelStyles.setReadmeColumnWidth;
import static com.example.batch.console.support.ConsoleExcelStyles.setWidths;
import static com.example.batch.console.support.ConsoleExcelStyles.writeCell;
import static com.example.batch.console.support.ConsoleExcelStyles.writeHeaders;
import static com.example.batch.console.support.ConsoleExcelStyles.writeTemplateHeaders;

import com.example.batch.common.enums.DictEnum;
import com.example.batch.common.enums.JobType;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.enums.RetryPolicyType;
import com.example.batch.common.enums.ShardStrategy;
import com.example.batch.common.exception.BizException;
import com.example.batch.console.domain.entity.JobDefinitionEntity;
import com.example.batch.console.support.ConsoleExcelPreviewWorkbookSupport;
import com.example.batch.console.support.ConsoleExcelPreviewWorkbookSupport.WorkbookIssue;
import com.example.batch.console.support.ConsoleExcelStyles;
import com.example.batch.console.support.JobDefinitionExcelImportStore.JobDefinitionRow;
import com.example.batch.console.web.response.ConsoleJobDefinitionExcelRowIssueResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Component;

/**
 * P2-3 god-class-decomposition extract: Job 定义 Excel 模板/导出/预览 workbook writer。
 *
 * <p>覆盖原 service ~165 行写盘逻辑(单 sheet + 19 列 + 校验下拉 + README/字典/校验 sheet),自带列名/列说明/枚举集常量。
 */
@Component
class JobDefinitionExcelWorkbookWriter {

  static final String SHEET = "job_definition";

  private static final String COL_JOB_TYPE = "job_type";
  private static final String COL_SCHEDULE_TYPE = "schedule_type";
  private static final String COL_RETRY_POLICY = "retry_policy";
  private static final String COL_SHARD_STRATEGY = "shard_strategy";
  private static final String COL_EXECUTION_HANDLER = "execution_handler";
  private static final String COL_PARAM_SCHEMA = "param_schema";
  private static final String COL_DEFAULT_PARAMS = "default_params";
  private static final String COL_ENABLED = "enabled";
  private static final String COL_DESCRIPTION = "description";
  private static final String GUIDE_NONE = "NONE";
  private static final String GUIDE_TRUE = "TRUE";
  private static final String GUIDE_FALSE = "FALSE";
  private static final String GUIDE_STR = "字符串";

  static final List<String> COLUMNS =
      List.of(
          "tenant_id",
          "job_code",
          "job_name",
          COL_JOB_TYPE,
          "queue_code",
          "worker_group",
          COL_SCHEDULE_TYPE,
          "schedule_expr",
          "calendar_code",
          "window_code",
          COL_RETRY_POLICY,
          "retry_max_count",
          "timeout_seconds",
          COL_SHARD_STRATEGY,
          COL_EXECUTION_HANDLER,
          COL_PARAM_SCHEMA,
          COL_DEFAULT_PARAMS,
          COL_ENABLED,
          COL_DESCRIPTION);
  static final Set<String> HEADERS = Set.copyOf(COLUMNS);

  private static final Set<String> JOB_TYPES = DictEnum.codes(JobType.class);
  private static final Set<String> SCHEDULE_TYPES = Set.of("CRON", "FIXED_RATE", "MANUAL");
  private static final Set<String> RETRY_POLICIES = DictEnum.codes(RetryPolicyType.class);
  private static final Set<String> SHARD_STRATEGIES = DictEnum.codes(ShardStrategy.class);
  private static final Set<String> ENABLED_VALUES = Set.of(GUIDE_TRUE, GUIDE_FALSE);

  private static final Map<String, ConsoleExcelStyles.ColumnGuide> COLUMN_GUIDES =
      Map.ofEntries(
          Map.entry("tenant_id", optionalColumn("当前行所属租户。留空时，上传时自动使用当前租户。", GUIDE_STR, "tenant-a")),
          Map.entry(
              "job_code", requiredColumn("作业唯一编码，用于匹配已有作业定义。", GUIDE_STR, "JOB_SETTLEMENT_001")),
          Map.entry("job_name", optionalColumn("控制台展示的作业名称。", GUIDE_STR, "清算作业")),
          Map.entry(
              COL_JOB_TYPE,
              requiredReadOnlyColumn(
                  "作业执行类型。该维护模板中为只读字段，仅校验是否与导出值一致。",
                  "枚举",
                  "GENERAL",
                  "GENERAL",
                  "IMPORT",
                  "EXPORT",
                  "DISPATCH",
                  "WORKFLOW")),
          Map.entry("queue_code", optionalColumn("资源队列编码。留空表示保持当前值。", "编码", "queue-default")),
          Map.entry("worker_group", optionalColumn("目标执行器分组。留空表示保持当前值。", "编码", "worker-general")),
          Map.entry(
              COL_SCHEDULE_TYPE,
              requiredReadOnlyColumn(
                  "调度类型在维护模板中为只读字段，必须与导出值一致。",
                  "枚举",
                  "CRON",
                  "CRON",
                  "FIXED_RATE",
                  "MANUAL",
                  "EVENT",
                  "ONE_TIME")),
          Map.entry(
              "schedule_expr",
              optionalColumn(
                  "调度表达式，具体格式取决于 schedule_type。",
                  "Cron / ISO-8601 时长 / 时间戳 / 事件主题",
                  "0 0/30 * * * ?")),
          Map.entry("calendar_code", optionalColumn("业务日历编码，系统中必须已存在。", "编码", "BIZ_CALENDAR")),
          Map.entry("window_code", optionalColumn("批量窗口编码，系统中必须已存在。", "编码", "WINDOW_NIGHT")),
          Map.entry(
              COL_RETRY_POLICY,
              optionalColumn("执行失败后的重试策略。", "枚举", "FIXED", GUIDE_NONE, "FIXED", "EXPONENTIAL")),
          Map.entry("retry_max_count", optionalColumn("最大重试次数，必须大于等于 0。", "整数", "3")),
          Map.entry("timeout_seconds", optionalColumn("超时时间（秒），必须大于等于 0。", "整数", "1800")),
          Map.entry(
              COL_SHARD_STRATEGY,
              optionalColumn("编排器使用的分片策略。", "枚举", "AUTO", GUIDE_NONE, "STATIC", "DYNAMIC", "AUTO")),
          Map.entry(
              COL_EXECUTION_HANDLER,
              readOnlyColumn(
                  "运行时处理器 Bean/Class。该维护模板中为只读字段。",
                  GUIDE_STR,
                  "com.example.batch.worker.general.GenericTaskHandler")),
          Map.entry(
              COL_PARAM_SCHEMA,
              readOnlyColumn("启动参数 Schema。该维护模板中请保持导出的 JSON 不变。", "JSON", "{\"type\":\"object\"}")),
          Map.entry(
              COL_DEFAULT_PARAMS,
              readOnlyColumn("默认启动参数。该维护模板中请保持导出的 JSON 不变。", "JSON", "{\"batchSize\":1000}")),
          Map.entry(
              COL_ENABLED, optionalColumn("作业定义是否启用。", "布尔值", GUIDE_TRUE, GUIDE_TRUE, GUIDE_FALSE)),
          Map.entry(COL_DESCRIPTION, optionalColumn("面向运维人员的说明信息。", GUIDE_STR, "夜间清算处理链路")));

  byte[] writeMaintenanceWorkbook(List<JobDefinitionEntity> rows) {
    try (SXSSFWorkbook workbook = new SXSSFWorkbook(50);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Sheet dataSheet = workbook.createSheet(SHEET);
      dataSheet.createFreezePane(0, 1, 0, 1);
      writeTemplateHeaders(dataSheet, COLUMNS, COLUMN_GUIDES, workbook);
      int rowIndex = 1;
      for (JobDefinitionEntity entity : rows) {
        Row row = dataSheet.createRow(rowIndex++);
        writeCell(row, 0, entity.getTenantId());
        writeCell(row, 1, entity.getJobCode());
        writeCell(row, 2, entity.getJobName());
        writeCell(row, 3, entity.getJobType());
        writeCell(row, 4, entity.getQueueCode());
        writeCell(row, 5, entity.getWorkerGroup());
        writeCell(row, 6, entity.getScheduleType());
        writeCell(row, 7, entity.getScheduleExpr());
        writeCell(row, 8, entity.getCalendarCode());
        writeCell(row, 9, entity.getWindowCode());
        writeCell(row, 10, entity.getRetryPolicy());
        writeCell(row, 11, entity.getRetryMaxCount());
        writeCell(row, 12, entity.getTimeoutSeconds());
        writeCell(row, 13, entity.getShardStrategy());
        writeCell(row, 14, entity.getExecutionHandler());
        writeCell(row, 15, entity.getParamSchema());
        writeCell(row, 16, entity.getDefaultParams());
        writeCell(row, 17, entity.getEnabled());
        writeCell(row, 18, entity.getDescription());
      }
      applyValidations(dataSheet);
      setWidths(dataSheet, COLUMNS);
      createReadmeSheet(workbook);
      createDictSheet(workbook);
      createValidationSheet(workbook);
      workbook.write(out);
      return out.toByteArray();
    } catch (IOException exception) {
      throw BizException.of(ResultCode.SYSTEM_ERROR, "error.excel.generate_failed");
    }
  }

  byte[] writePreviewWorkbook(
      List<JobDefinitionRow> sessionRows, List<ConsoleJobDefinitionExcelRowIssueResponse> issues) {
    try (Workbook workbook = ConsoleExcelPreviewWorkbookSupport.createWorkbook()) {
      Sheet dataSheet = workbook.createSheet(SHEET);
      dataSheet.createFreezePane(0, 1, 0, 1);
      writeTemplateHeaders(dataSheet, COLUMNS, COLUMN_GUIDES, workbook);
      int rowIndex = 1;
      for (JobDefinitionRow rowData : sessionRows) {
        Row row = dataSheet.createRow(rowIndex++);
        writeCell(row, 0, rowData.tenantId());
        writeCell(row, 1, rowData.jobCode());
        writeCell(row, 2, rowData.jobName());
        writeCell(row, 3, rowData.jobType());
        writeCell(row, 4, rowData.queueCode());
        writeCell(row, 5, rowData.workerGroup());
        writeCell(row, 6, rowData.scheduleType());
        writeCell(row, 7, rowData.scheduleExpr());
        writeCell(row, 8, rowData.calendarCode());
        writeCell(row, 9, rowData.windowCode());
        writeCell(row, 10, rowData.retryPolicy());
        writeCell(row, 11, rowData.retryMaxCount());
        writeCell(row, 12, rowData.timeoutSeconds());
        writeCell(row, 13, rowData.shardStrategy());
        writeCell(row, 14, rowData.executionHandler());
        writeCell(row, 15, rowData.paramSchema());
        writeCell(row, 16, rowData.defaultParams());
        writeCell(row, 17, rowData.enabled());
        writeCell(row, 18, rowData.description());
      }
      applyValidations(dataSheet);
      setWidths(dataSheet, COLUMNS);
      createReadmeSheet(workbook);
      createDictSheet(workbook);
      createValidationSheet(workbook);

      List<WorkbookIssue> workbookIssues =
          issues.stream()
              .flatMap(
                  issue ->
                      ConsoleExcelPreviewWorkbookSupport.expandIssues(
                          issue.sheetName(), issue.rowNo(), issue.messages(), COLUMNS)
                          .stream())
              .toList();
      ConsoleExcelPreviewWorkbookSupport.populateValidationSheet(workbook, workbookIssues);
      ConsoleExcelPreviewWorkbookSupport.addIssueComments(dataSheet, COLUMNS, workbookIssues, 1);
      return ConsoleExcelPreviewWorkbookSupport.toBytes(workbook);
    } catch (IOException exception) {
      throw BizException.of(ResultCode.SYSTEM_ERROR, "error.excel.preview_workbook_failed");
    }
  }

  private void applyValidations(Sheet sheet) {
    addDropdownValidation(
        sheet,
        3,
        JOB_TYPES.toArray(String[]::new),
        "job_type 填写提示",
        "该字段为只读字段，请保持导出的 job_type 不变。");
    addDropdownValidation(
        sheet,
        6,
        SCHEDULE_TYPES.toArray(String[]::new),
        "schedule_type 填写提示",
        "该字段为只读字段，请保持导出的 schedule_type 不变。");
    addDropdownValidation(
        sheet, 10, RETRY_POLICIES.toArray(String[]::new), "retry_policy 填写提示", "请从下拉列表中选择重试策略。");
    addDropdownValidation(
        sheet,
        13,
        SHARD_STRATEGIES.toArray(String[]::new),
        "shard_strategy 填写提示",
        "请从下拉列表中选择分片策略。");
    addDropdownValidation(
        sheet, 17, ENABLED_VALUES.toArray(String[]::new), "enabled 填写提示", "请填写 TRUE 或 FALSE。");
  }

  private void createReadmeSheet(Workbook workbook) {
    Sheet sheet = workbook.createSheet(ConsoleExcelStyles.SHEET_NAME_README);
    setReadmeColumnWidth(sheet);
    CellStyle titleStyle = createReadmeTitleStyle(workbook);
    String[] lines = {
      "Job 定义安全字段维护模板",
      "1. 橙色表头表示关键字段;灰蓝色表头表示只读一致性字段。",
      "2. 鼠标悬停表头单元格可查看字段用途、格式提示、下拉值与示例。",
      "3. 导入按 tenant_id + job_code 匹配。",
      "4. 可编辑单元格留空时保持当前导出值。",
      "5. 导入流程：上传 → 预览 → 应用。"
    };
    for (int i = 0; i < lines.length; i++) {
      Row row = sheet.createRow(i);
      row.createCell(0).setCellValue(lines[i]);
      if (i == 0) {
        row.getCell(0).setCellStyle(titleStyle);
      }
    }
  }

  private void createDictSheet(Workbook workbook) {
    Sheet sheet = workbook.createSheet(ConsoleExcelStyles.SHEET_NAME_DICT);
    sheet.createFreezePane(0, 1, 0, 1);
    CellStyle dictHeaderStyle = ConsoleExcelStyles.createHeaderStyle(workbook);
    writeHeaders(sheet, List.of("field", "value", COL_DESCRIPTION), dictHeaderStyle);
    String[][] rows = {
      {COL_RETRY_POLICY, GUIDE_NONE, "no retry"},
      {COL_RETRY_POLICY, "FIXED", "fixed retry"},
      {COL_RETRY_POLICY, "EXPONENTIAL", "exponential retry"},
      {COL_SHARD_STRATEGY, GUIDE_NONE, "no shard"},
      {COL_SHARD_STRATEGY, "STATIC", "static shard"},
      {COL_SHARD_STRATEGY, "DYNAMIC", "dynamic shard"},
      {COL_SHARD_STRATEGY, "AUTO", "auto shard"},
      {COL_ENABLED, GUIDE_TRUE, COL_ENABLED},
      {COL_ENABLED, GUIDE_FALSE, "disabled"}
    };
    for (int i = 0; i < rows.length; i++) {
      Row row = sheet.createRow(i + 1);
      row.createCell(0).setCellValue(rows[i][0]);
      row.createCell(1).setCellValue(rows[i][1]);
      row.createCell(2).setCellValue(rows[i][2]);
    }
    setGuideColumnWidths(sheet);
  }

  private void createValidationSheet(Workbook workbook) {
    ConsoleExcelStyles.createValidationSheet(workbook);
  }
}
