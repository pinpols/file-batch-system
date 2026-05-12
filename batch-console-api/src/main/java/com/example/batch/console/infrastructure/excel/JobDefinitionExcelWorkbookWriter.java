package com.example.batch.console.infrastructure.excel;

import static com.example.batch.console.support.excel.ConsoleExcelStyles.addDropdownValidation;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.createReadmeTitleStyle;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.optionalColumn;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.readOnlyColumn;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.requiredColumn;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.requiredReadOnlyColumn;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.setGuideColumnWidths;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.setReadmeColumnWidth;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.setWidths;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.writeCell;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.writeHeaders;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.writeTemplateHeaders;

import com.example.batch.common.enums.DictEnum;
import com.example.batch.common.enums.JobType;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.enums.RetryPolicyType;
import com.example.batch.common.enums.ShardStrategy;
import com.example.batch.common.exception.BizException;
import com.example.batch.console.domain.entity.JobDefinitionEntity;
import com.example.batch.console.support.excel.ConsoleExcelStyles;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

/**
 * P2-3 god-class-decomposition extract: Job 定义 Excel 模板/导出/预览 workbook writer。
 *
 * <p>覆盖原 service ~165 行写盘逻辑(单 sheet + 19 列 + 校验下拉 + README/字典/校验 sheet),自带列名/列说明/枚举集常量。
 */
@Component
@RequiredArgsConstructor
public class JobDefinitionExcelWorkbookWriter {

  private final MessageSource messageSource;

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
          Map.entry(
              "tenant_id",
              optionalColumn(
                  "excel.job.def.tenant_id.desc", "excel.guide.format.string", "tenant-a")),
          Map.entry(
              "job_code",
              requiredColumn(
                  "excel.job.def.job_code.desc",
                  "excel.guide.format.string",
                  "JOB_SETTLEMENT_001")),
          Map.entry(
              "job_name",
              optionalColumn("excel.job.def.job_name.desc", "excel.guide.format.string", "清算作业")),
          Map.entry(
              COL_JOB_TYPE,
              requiredReadOnlyColumn(
                  "excel.job.def.job_type.desc",
                  "excel.guide.format.enum",
                  "GENERAL",
                  "GENERAL",
                  "IMPORT",
                  "EXPORT",
                  "DISPATCH",
                  "WORKFLOW")),
          Map.entry(
              "queue_code",
              optionalColumn(
                  "excel.job.def.queue_code.desc", "excel.guide.format.code", "queue-default")),
          Map.entry(
              "worker_group",
              optionalColumn(
                  "excel.job.def.worker_group.desc", "excel.guide.format.code", "worker-general")),
          Map.entry(
              COL_SCHEDULE_TYPE,
              requiredReadOnlyColumn(
                  "excel.job.def.schedule_type.desc",
                  "excel.guide.format.enum",
                  "CRON",
                  "CRON",
                  "FIXED_RATE",
                  "MANUAL",
                  "EVENT",
                  "ONE_TIME")),
          Map.entry(
              "schedule_expr",
              optionalColumn(
                  "excel.job.def.schedule_expr.desc",
                  "excel.guide.format.schedule_expr",
                  "0 0/30 * * * ?")),
          Map.entry(
              "calendar_code",
              optionalColumn(
                  "excel.job.def.calendar_code.desc", "excel.guide.format.code", "BIZ_CALENDAR")),
          Map.entry(
              "window_code",
              optionalColumn(
                  "excel.job.def.window_code.desc", "excel.guide.format.code", "WINDOW_NIGHT")),
          Map.entry(
              COL_RETRY_POLICY,
              optionalColumn(
                  "excel.job.def.retry_policy.desc",
                  "excel.guide.format.enum",
                  "FIXED",
                  GUIDE_NONE,
                  "FIXED",
                  "EXPONENTIAL")),
          Map.entry(
              "retry_max_count",
              optionalColumn(
                  "excel.job.def.retry_max_count.desc", "excel.guide.format.integer", "3")),
          Map.entry(
              "timeout_seconds",
              optionalColumn(
                  "excel.job.def.timeout_seconds.desc", "excel.guide.format.integer", "1800")),
          Map.entry(
              COL_SHARD_STRATEGY,
              optionalColumn(
                  "excel.job.def.shard_strategy.desc",
                  "excel.guide.format.enum",
                  "AUTO",
                  GUIDE_NONE,
                  "STATIC",
                  "DYNAMIC",
                  "AUTO")),
          Map.entry(
              COL_EXECUTION_HANDLER,
              readOnlyColumn(
                  "excel.job.def.execution_handler.desc",
                  "excel.guide.format.string",
                  "com.example.batch.worker.general.GenericTaskHandler")),
          Map.entry(
              COL_PARAM_SCHEMA,
              readOnlyColumn(
                  "excel.job.def.param_schema.desc",
                  "excel.guide.format.json",
                  "{\"type\":\"object\"}")),
          Map.entry(
              COL_DEFAULT_PARAMS,
              readOnlyColumn(
                  "excel.job.def.default_params.desc",
                  "excel.guide.format.json",
                  "{\"batchSize\":1000}")),
          Map.entry(
              COL_ENABLED,
              optionalColumn(
                  "excel.job.def.enabled.desc",
                  "excel.guide.format.boolean",
                  GUIDE_TRUE,
                  GUIDE_TRUE,
                  GUIDE_FALSE)),
          Map.entry(
              COL_DESCRIPTION,
              optionalColumn(
                  "excel.job.def.description.desc", "excel.guide.format.string", "夜间清算处理链路")));

  public byte[] writeMaintenanceWorkbook(List<JobDefinitionEntity> rows) {
    Locale locale = LocaleContextHolder.getLocale();
    try (SXSSFWorkbook workbook = new SXSSFWorkbook(50);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Sheet dataSheet = workbook.createSheet(SHEET);
      dataSheet.createFreezePane(0, 1, 0, 1);
      writeTemplateHeaders(dataSheet, COLUMNS, COLUMN_GUIDES, workbook, messageSource, locale);
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
      applyValidations(dataSheet, locale);
      setWidths(dataSheet, COLUMNS);
      createReadmeSheet(workbook, locale);
      createDictSheet(workbook);
      createValidationSheet(workbook);
      workbook.write(out);
      return out.toByteArray();
    } catch (IOException exception) {
      throw BizException.of(ResultCode.SYSTEM_ERROR, "error.excel.generate_failed");
    }
  }

  private void applyValidations(Sheet sheet, Locale locale) {
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
        6,
        SCHEDULE_TYPES.toArray(String[]::new),
        "excel.job.def.schedule_type.prompt_title",
        "excel.job.def.schedule_type.prompt_box",
        messageSource,
        locale);
    addDropdownValidation(
        sheet,
        10,
        RETRY_POLICIES.toArray(String[]::new),
        "excel.job.def.retry_policy.prompt_title",
        "excel.job.def.retry_policy.prompt_box",
        messageSource,
        locale);
    addDropdownValidation(
        sheet,
        13,
        SHARD_STRATEGIES.toArray(String[]::new),
        "excel.job.def.shard_strategy.prompt_title",
        "excel.job.def.shard_strategy.prompt_box",
        messageSource,
        locale);
    addDropdownValidation(
        sheet,
        17,
        ENABLED_VALUES.toArray(String[]::new),
        "excel.common.enabled.prompt_title",
        "excel.common.enabled.prompt_box",
        messageSource,
        locale);
  }

  private void createReadmeSheet(Workbook workbook, Locale locale) {
    Sheet sheet = workbook.createSheet(ConsoleExcelStyles.SHEET_NAME_README);
    setReadmeColumnWidth(sheet);
    CellStyle titleStyle = createReadmeTitleStyle(workbook);
    String[] keys = {
      "excel.job.readme.title",
      "excel.job.readme.line1",
      "excel.job.readme.line2",
      "excel.job.readme.line3",
      "excel.job.readme.line4",
      "excel.job.readme.line5"
    };
    for (int i = 0; i < keys.length; i++) {
      Row row = sheet.createRow(i);
      row.createCell(0).setCellValue(messageSource.getMessage(keys[i], null, keys[i], locale));
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
