package io.github.pinpols.batch.console.infrastructure.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.pinpols.batch.console.domain.file.mapper.FileTemplateConfigMapper;
import io.github.pinpols.batch.console.domain.job.mapper.BatchWindowMapper;
import io.github.pinpols.batch.console.domain.job.mapper.BusinessCalendarMapper;
import io.github.pinpols.batch.console.domain.job.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.console.domain.job.mapper.StepRegistryQueryMapper;
import io.github.pinpols.batch.console.domain.ops.mapper.ResourceQueueMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.PipelineDefinitionMapper;
import io.github.pinpols.batch.console.support.excel.TenantConfigPackageExcelImportStore.PackageExcelSession;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("配置包 Excel 校验器:模板取值、跨表引用与字段规则校验")
class ConfigPackageExcelValidatorTest {

  @Test
  @DisplayName("PROCESS 流水线:允许准备、计算、校验、提交、反馈五类阶段")
  void shouldAllowProcessStages_whenPipelineTypeIsProcess() {
    assertThat(ConfigPackageExcelValidator.STAGES_BY_TYPE).containsKey("PROCESS");
    assertThat(ConfigPackageExcelValidator.STAGES_BY_TYPE.get("PROCESS"))
        .containsExactlyInAnyOrder("PREPARE", "COMPUTE", "VALIDATE", "COMMIT", "FEEDBACK");
    assertThat(ConfigPackageExcelValidator.STAGE_CODES).contains("COMPUTE", "COMMIT");
  }

  @Test
  @DisplayName("文件模板 sheet:同编码同版本重复行被判定非法并给出提示")
  void shouldReportDuplicates_whenTemplateCodeAndVersionRepeat() {
    ConfigPackageExcelValidator validator = validator();
    PackageExcelSession session = session(List.of(
        fileTemplateRow("TPL_IMPORT_CUSTOMER", "1"), fileTemplateRow("TPL_IMPORT_CUSTOMER", "1")));

    ConfigPackageExcelValidator.PackageValidationResult result = validator.validate(session);

    assertThat(result.fileTemplates().sheetName())
        .isEqualTo(ConfigPackageExcelValidator.FILE_TEMPLATE_SHEET);
    assertThat(result.fileTemplates().valid()).isEqualTo(1);
    assertThat(result.fileTemplates().invalid()).isEqualTo(1);
    assertThat(result.allIssues())
        .anySatisfy(issue ->
            assertThat(issue.message()).contains("duplicate template_code + version in excel"));
  }

  @Test
  @DisplayName("作业监控策略独立 sheet:识别作业并校验阈值和告警级别")
  void shouldValidateDedicatedJobMonitoringPolicySheet() {
    Map<String, String> policy = new LinkedHashMap<>();
    policy.put("tenant_id", "t1");
    policy.put("job_code", "JOB_IMPORT_CUSTOMER");
    policy.put("soft_runtime_seconds", "900");
    policy.put("soft_runtime_severity", "ERROR");
    policy.put("start_grace_seconds", "60");
    policy.put("start_grace_severity", "WARN");
    policy.put("completion_deadline_local_time", "04:00");
    policy.put("completion_deadline_day_offset", "1");
    policy.put("completion_deadline_severity", "CRITICAL");
    Map<String, String> job = new LinkedHashMap<>(jobRow("", "", ""));
    job.put("job_code", "JOB_IMPORT_CUSTOMER");
    job.put("job_name", "客户导入");
    job.put("schedule_type", "CRON");
    job.put("schedule_expr", "0 0 2 * * ?");
    PackageExcelSession session = new PackageExcelSession(
        "config.xlsx",
        "t1",
        Instant.parse("2026-10-09T00:00:00Z"),
        List.of(),
        List.of(),
        List.of(),
        List.of(job),
        List.of(policy),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of());

    ConfigPackageExcelValidator.PackageValidationResult result = validator().validate(session);

    assertThat(result.jobMonitoringPolicies().sheetName())
        .isEqualTo(ConfigPackageExcelValidator.JOB_MONITORING_POLICY_SHEET);
    assertThat(result.validJobMonitoringPolicies()).hasSize(1);
    assertThat(result.allIssues()).isEmpty();
  }

  @Test
  @DisplayName("固定频率独立作业仅支持运行耗时告警")
  void shouldRejectCompletionDeadlineForStandaloneFixedRateJob() {
    Map<String, String> policy = new LinkedHashMap<>();
    policy.put("tenant_id", "t1");
    policy.put("job_code", "JOB_FIXED_RATE");
    policy.put("dependency_completion_window_seconds", "1200");
    policy.put("completion_deadline_severity", "ERROR");
    Map<String, String> job = new LinkedHashMap<>(jobRow("", "", ""));
    job.put("job_code", "JOB_FIXED_RATE");
    job.put("job_name", "固定频率作业");
    job.put("schedule_type", "FIXED_RATE");
    job.put("schedule_expr", "PT1M");
    List<Map<String, String>> jobs = new ArrayList<>(List.of(job));
    PackageExcelSession session = new PackageExcelSession(
        "config.xlsx",
        "t1",
        Instant.parse("2026-10-09T00:00:00Z"),
        List.of(),
        List.of(),
        List.of(),
        jobs,
        List.of(policy),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of());

    ConfigPackageExcelValidator.PackageValidationResult result = validator().validate(session);

    assertThat(result.validJobMonitoringPolicies()).isEmpty();
    assertThat(result.allIssues())
        .anySatisfy(
            issue -> assertThat(issue.message()).contains("only supported for dependent jobs"));

    job.put("depends_on_job_code", "UPSTREAM_JOB");
    Map<String, String> upstreamJob = new LinkedHashMap<>(jobRow("", "", ""));
    upstreamJob.put("job_code", "UPSTREAM_JOB");
    jobs.add(upstreamJob);
    ConfigPackageExcelValidator.PackageValidationResult dependent = validator().validate(session);
    assertThat(dependent.validJobMonitoringPolicies()).hasSize(1);
    assertThat(dependent.allIssues()).isEmpty();
  }

  @Test
  @DisplayName("作业默认参数:引用不存在的模板编码时上报校验问题")
  void shouldReportUnknownTemplate_whenDefaultParamsReferenceMissingCode() {
    ConfigPackageExcelValidator validator = validator();
    PackageExcelSession session = session(
        List.of(),
        List.of(Map.of(
            "tenant_id",
            "t1",
            "job_code",
            "JOB_IMPORT_CUSTOMER",
            "job_name",
            "导入客户",
            "job_type",
            "IMPORT",
            "schedule_type",
            "MANUAL",
            "default_params",
            "{\"templateCode\":\"TPL_MISSING\"}")));

    ConfigPackageExcelValidator.PackageValidationResult result = validator.validate(session);

    assertThat(result.allIssues()).anySatisfy(issue -> {
      assertThat(issue.sheetName()).isEqualTo(ConfigPackageExcelValidator.JOB_SHEET);
      assertThat(issue.columnName()).isEqualTo(ConfigPackageExcelValidator.COL_DEFAULT_PARAMS);
      assertThat(issue.message()).contains("TPL_MISSING");
    });
  }

  @Test
  @DisplayName("依赖 sheet 与跨表引用:队列、日历、窗口均可解析时校验通过")
  void shouldAcceptDependencySheets_whenCrossReferencesResolve() {
    ConfigPackageExcelValidator validator = validator();
    PackageExcelSession session = sessionWithDependencies(
        List.of(resourceQueueRow("import-queue")),
        List.of(calendarRow("default-calendar")),
        List.of(windowRow("always-open")),
        List.of(jobRow("import-queue", "default-calendar", "always-open")));

    ConfigPackageExcelValidator.PackageValidationResult result = validator.validate(session);

    assertThat(result.resourceQueues().valid()).isEqualTo(1);
    assertThat(result.businessCalendars().valid()).isEqualTo(1);
    assertThat(result.batchWindows().valid()).isEqualTo(1);
    assertThat(result.allIssues()).isEmpty();
  }

  @Test
  @DisplayName("跨表引用问题:过滤非法行后仍保留原始 Excel 行号")
  void shouldKeepOriginalRowNumber_whenInvalidRowsFilteredBeforeCrossRef() {
    ConfigPackageExcelValidator validator = validator();
    Map<String, String> invalidRow = new LinkedHashMap<>(jobRow("missing-queue", "", ""));
    invalidRow.put("job_code", "BROKEN_JOB");
    invalidRow.remove("job_name");
    PackageExcelSession session = sessionWithDependencies(
        List.of(), List.of(), List.of(), List.of(invalidRow, jobRow("missing-queue", "", "")));

    ConfigPackageExcelValidator.PackageValidationResult result = validator.validate(session);

    assertThat(result.crossRefIssues()).anySatisfy(issue -> {
      assertThat(issue.sheetName()).isEqualTo(ConfigPackageExcelValidator.JOB_SHEET);
      assertThat(issue.columnName()).isEqualTo(ConfigPackageExcelValidator.COL_QUEUE_CODE);
      assertThat(issue.rowNo()).isEqualTo(3);
    });
  }

  @Test
  @DisplayName("CRON 调度:缺少调度表达式时被标记为问题")
  void shouldFlagMissingExpr_whenScheduleTypeIsCron() {
    PackageExcelSession session = session(
        List.of(),
        List.of(Map.of(
            "tenant_id", "t1",
            "job_code", "J1",
            "job_name", "n",
            "job_type", "IMPORT",
            "schedule_type", "CRON")));

    ConfigPackageExcelValidator.PackageValidationResult result = validator().validate(session);

    assertThat(result.allIssues())
        .anySatisfy(issue -> assertThat(issue.message())
            .contains("schedule_expr is required when schedule_type=CRON"));
  }

  @Test
  @DisplayName("CRON 调度:五段式 Linux 表达式被判定为非法格式")
  void shouldFlagFiveFieldExpr_whenScheduleTypeIsCron() {
    PackageExcelSession session = session(
        List.of(),
        List.of(Map.of(
            "tenant_id", "t1",
            "job_code", "J1",
            "job_name", "n",
            "job_type", "IMPORT",
            "schedule_type", "CRON",
            "schedule_expr", "0 2 * * *")));

    ConfigPackageExcelValidator.PackageValidationResult result = validator().validate(session);

    assertThat(result.allIssues())
        .anySatisfy(issue -> assertThat(issue.message()).contains("Quartz 6 or 7-field cron"));
  }

  @Test
  @DisplayName("分隔符格式:未填写分隔符时被标记为问题")
  void shouldFlagMissingDelimiter_whenFileFormatIsDelimited() {
    Map<String, String> row = new LinkedHashMap<>(fileTemplateRow("TPL", "1"));
    row.remove("delimiter");

    ConfigPackageExcelValidator.PackageValidationResult result =
        validator().validate(session(List.of(row)));

    assertThat(result.allIssues())
        .anySatisfy(issue -> assertThat(issue.message())
            .contains("delimiter is required when file_format_type=DELIMITED"));
  }

  @Test
  @DisplayName("JDBC 映射导入:缺少目标表配置时被标记为问题")
  void shouldFlagMissingTable_whenJdbcMappedImportConfigured() {
    Map<String, String> row = new LinkedHashMap<>(fileTemplateRow("TPL", "1"));
    row.put("query_param_schema", "{\"jdbcMappedImport\":{\"tenantColumn\":\"tenant_id\"}}");

    ConfigPackageExcelValidator.PackageValidationResult result =
        validator().validate(session(List.of(row)));

    assertThat(result.allIssues())
        .anySatisfy(issue -> assertThat(issue.message())
            .contains("query_param_schema.jdbcMappedImport.table is required"));
  }

  @Test
  @DisplayName("字段映射:条目缺少映射名称时被标记为问题")
  void shouldFlagEntryWithoutName_whenFieldMappingsEntryMissesName() {
    Map<String, String> row = new LinkedHashMap<>(fileTemplateRow("TPL", "1"));
    row.put("field_mappings", "[{\"targetColumn\":\"c\"}]");

    ConfigPackageExcelValidator.PackageValidationResult result =
        validator().validate(session(List.of(row)));

    assertThat(result.allIssues())
        .anySatisfy(issue -> assertThat(issue.message())
            .contains("field_mappings entries must each have a non-blank 'name'"));
  }

  @Test
  @DisplayName("导出 SQL:使用全列通配查询时被标记为问题")
  void shouldFlagSelectStar_whenExportSqlQueries() {
    Map<String, String> row = new LinkedHashMap<>(fileTemplateRow("TPL", "1"));
    row.put("default_query_sql", "SELECT * FROM biz.customer_account WHERE tenant_id = :tenantId");

    ConfigPackageExcelValidator.PackageValidationResult result =
        validator().validate(session(List.of(row)));

    assertThat(result.allIssues())
        .anySatisfy(issue -> assertThat(issue.message()).contains("must not use SELECT *"));
  }

  private static ConfigPackageExcelValidator validator() {
    return new ConfigPackageExcelValidator(
        mock(JobDefinitionMapper.class),
        mock(PipelineDefinitionMapper.class),
        mock(StepRegistryQueryMapper.class),
        mock(FileTemplateConfigMapper.class),
        mock(ResourceQueueMapper.class),
        mock(BusinessCalendarMapper.class),
        mock(BatchWindowMapper.class));
  }

  private static PackageExcelSession session(List<Map<String, String>> fileTemplateRows) {
    return session(fileTemplateRows, List.of());
  }

  private static PackageExcelSession session(
      List<Map<String, String>> fileTemplateRows, List<Map<String, String>> jobRows) {
    return new PackageExcelSession(
        "package.xlsx",
        "t1",
        Instant.EPOCH,
        List.of(),
        List.of(),
        List.of(),
        jobRows,
        List.of(),
        fileTemplateRows,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of());
  }

  private static PackageExcelSession sessionWithDependencies(
      List<Map<String, String>> resourceQueues,
      List<Map<String, String>> businessCalendars,
      List<Map<String, String>> batchWindows,
      List<Map<String, String>> jobRows) {
    return new PackageExcelSession(
        "package.xlsx",
        "t1",
        Instant.EPOCH,
        resourceQueues,
        businessCalendars,
        batchWindows,
        jobRows,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of());
  }

  private static Map<String, String> fileTemplateRow(String templateCode, String version) {
    Map<String, String> row = new LinkedHashMap<>();
    row.put("tenant_id", "t1");
    row.put("template_code", templateCode);
    row.put("template_name", "客户导入模板");
    row.put("template_type", "IMPORT");
    row.put("file_format_type", "DELIMITED");
    row.put("delimiter", ",");
    row.put("checksum_type", "NONE");
    row.put("compress_type", "NONE");
    row.put("encrypt_type", "NONE");
    row.put("version", version);
    return row;
  }

  private static Map<String, String> resourceQueueRow(String queueCode) {
    Map<String, String> row = new LinkedHashMap<>();
    row.put("tenant_id", "t1");
    row.put("queue_code", queueCode);
    row.put("queue_name", "导入队列");
    row.put("queue_type", "IMPORT");
    row.put("max_running_jobs", "10");
    row.put("max_running_partitions", "20");
    row.put("max_qps", "100");
    row.put("priority_policy", "FIFO");
    row.put("fair_share_weight", "1");
    return row;
  }

  private static Map<String, String> calendarRow(String calendarCode) {
    Map<String, String> row = new LinkedHashMap<>();
    row.put("tenant_id", "t1");
    row.put("calendar_code", calendarCode);
    row.put("calendar_name", "默认日历");
    row.put("timezone", "Asia/Shanghai");
    row.put("holiday_roll_rule", "SKIP");
    row.put("catch_up_policy", "NONE");
    row.put("catch_up_max_days", "0");
    row.put("holidays", "2026-01-01");
    return row;
  }

  private static Map<String, String> windowRow(String windowCode) {
    Map<String, String> row = new LinkedHashMap<>();
    row.put("tenant_id", "t1");
    row.put("window_code", windowCode);
    row.put("window_name", "全天窗口");
    row.put("timezone", "Asia/Shanghai");
    row.put("start_time", "00:00");
    row.put("end_time", "23:59");
    row.put("end_strategy", "FINISH_RUNNING");
    row.put("out_of_window_action", "WAIT");
    return row;
  }

  private static Map<String, String> jobRow(
      String queueCode, String calendarCode, String windowCode) {
    return Map.of(
        "tenant_id", "t1",
        "job_code", "JOB_IMPORT_CUSTOMER",
        "job_name", "导入客户",
        "job_type", "IMPORT",
        "schedule_type", "MANUAL",
        "queue_code", queueCode,
        "calendar_code", calendarCode,
        "window_code", windowCode);
  }
}
