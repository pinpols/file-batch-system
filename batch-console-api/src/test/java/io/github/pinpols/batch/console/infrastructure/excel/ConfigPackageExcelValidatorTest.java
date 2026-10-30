package io.github.pinpols.batch.console.infrastructure.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.console.domain.file.mapper.FileTemplateConfigMapper;
import io.github.pinpols.batch.console.domain.job.mapper.BatchWindowMapper;
import io.github.pinpols.batch.console.domain.job.mapper.BusinessCalendarMapper;
import io.github.pinpols.batch.console.domain.job.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.console.domain.job.view.ConsoleJobConfigViews.BatchWindowRow;
import io.github.pinpols.batch.console.domain.ops.mapper.ResourceQueueMapper;
import io.github.pinpols.batch.console.support.excel.TenantConfigPackageExcelImportStore.PackageExcelSession;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("配置包 Excel 校验器:模板取值、跨表引用与字段规则校验")
class ConfigPackageExcelValidatorTest {

  @Test
  @DisplayName("配置包作业编码遵循普通创建接口的字符和长度约束")
  void shouldValidateJobCodeAgainstResourceCodeContract() {
    for (String code : List.of("A:B", "1JOB", "A".repeat(129))) {
      List<String> issues = new ArrayList<>();
      Map<String, String> row = Map.of(
          "job_code", code, "job_name", "test", "job_type", "IMPORT", "schedule_type", "MANUAL");
      ConfigPackageExcelRowValidators.validateJobRow("t1", row, new HashSet<>(), issues);
      assertThat(issues).as("非法作业编码 %s", code).anyMatch(issue -> issue.contains("job_code"));
    }
    for (String code : List.of("A-b_9", "A".repeat(128))) {
      List<String> issues = new ArrayList<>();
      Map<String, String> row = Map.of(
          "job_code", code, "job_name", "test", "job_type", "IMPORT", "schedule_type", "MANUAL");
      ConfigPackageExcelRowValidators.validateJobRow("t1", row, new HashSet<>(), issues);
      assertThat(issues).as("合法作业编码 %s", code).isEmpty();
    }
  }

  @Test
  @DisplayName("配置包校验将非法作业编码排除在可应用行之外")
  void shouldExcludeInvalidJobCodeFromApplicableRows() {
    Map<String, String> invalid = Map.of(
        "tenant_id",
        "t1",
        "job_code",
        "A:B",
        "job_name",
        "invalid",
        "job_type",
        "IMPORT",
        "schedule_type",
        "MANUAL");
    Map<String, String> valid = Map.of(
        "tenant_id",
        "t1",
        "job_code",
        "A_B",
        "job_name",
        "valid",
        "job_type",
        "IMPORT",
        "schedule_type",
        "MANUAL");
    PackageExcelSession session = new PackageExcelSession(
        "package.xlsx",
        "t1",
        Instant.EPOCH,
        List.of(),
        List.of(),
        List.of(),
        List.of(invalid, valid),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of());

    var result = validator().validate(session);

    assertThat(result.jobs().invalid()).isEqualTo(1);
    assertThat(result.validJobs()).extracting(row -> row.get("job_code")).containsExactly("A_B");
  }

  @Test
  @DisplayName("PROCESS 流水线:允许准备、计算、校验、提交、反馈五类阶段")
  void shouldAllowProcessStages_whenPipelineTypeIsProcess() {
    assertThat(ConfigPackageExcelValidator.STAGES_BY_TYPE).containsKey("PROCESS");
    assertThat(ConfigPackageExcelValidator.STAGES_BY_TYPE.get("PROCESS"))
        .containsExactlyInAnyOrder("PREPARE", "COMPUTE", "VALIDATE", "COMMIT", "FEEDBACK");
    assertThat(ConfigPackageExcelValidator.STAGE_CODES).contains("COMPUTE", "COMMIT");
  }

  @Test
  @DisplayName("配置包 Pipeline 与步骤 sheet 已退役且不能转换为 Job 参数")
  void shouldRejectRetiredPipelineSheetsWithoutConvertingJobParameters() {
    Map<String, String> job = new LinkedHashMap<>();
    job.put("tenant_id", "t1");
    job.put("job_code", "JOB_IMPORT");
    job.put("job_name", "导入任务");
    job.put("job_type", "IMPORT");
    job.put("schedule_type", "MANUAL");
    job.put("default_params", "{\"existing\":\"value\"}");
    Map<String, String> pipeline = new LinkedHashMap<>();
    pipeline.put("tenant_id", "t1");
    pipeline.put("job_code", "JOB_IMPORT");
    pipeline.put("pipeline_name", "旧导入流水线");
    pipeline.put("pipeline_type", "IMPORT");
    pipeline.put("version", "1");
    Map<String, String> step = new LinkedHashMap<>();
    step.put("job_code", "JOB_IMPORT");
    step.put("version", "1");
    step.put("step_code", "RECEIVE");
    step.put("step_name", "接收");
    step.put("stage_code", "RECEIVE");
    step.put("step_order", "1");
    step.put("impl_code", "IMPORT_RECEIVE");
    step.put("step_params", "{\"secret\":\"should-not-import\"}");
    PackageExcelSession session = new PackageExcelSession(
        "package.xlsx",
        "t1",
        Instant.EPOCH,
        List.of(),
        List.of(),
        List.of(),
        List.of(job),
        List.of(),
        List.of(),
        List.of(),
        List.of(pipeline),
        List.of(step),
        List.of(),
        List.of(),
        List.of());

    ConfigPackageExcelValidator.PackageValidationResult result = validator().validate(session);

    assertThat(result.pipelines().invalid()).isEqualTo(1);
    assertThat(result.steps().invalid()).isEqualTo(1);
    assertThat(result.allIssues()).anySatisfy(issue -> {
      assertThat(issue.message()).contains("retired");
    });
    assertThat(result.validJobs())
        .singleElement()
        .satisfies(validJob ->
            assertThat(validJob.get("default_params")).isEqualTo("{\"existing\":\"value\"}"));
  }

  @Test
  @DisplayName("Workflow 日历引用:连字符规范化后仍必须引用配置包或租户中的有效日历")
  void shouldRejectWorkflowWithUnknownCalendar() {
    Map<String, String> workflow = new LinkedHashMap<>();
    workflow.put("tenant_id", "t1");
    workflow.put("workflow_code", "WF_SCHEDULED");
    workflow.put("workflow_name", "Scheduled workflow");
    workflow.put("workflow_type", "DAG");
    workflow.put("version", "1");
    workflow.put("enabled", "true");
    workflow.put("schedule_enabled", "true");
    workflow.put("schedule_type", "CRON");
    workflow.put("schedule_expr", "0 0 8 * * ?");
    workflow.put("timezone", "Asia/Shanghai");
    workflow.put("trigger_mode", "SCHEDULED");
    workflow.put("calendar_code", "missing-calendar");
    PackageExcelSession session = new PackageExcelSession(
        "package.xlsx",
        "t1",
        Instant.EPOCH,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(workflow),
        List.of(),
        List.of(),
        null,
        Set.of());

    ConfigPackageExcelValidator.PackageValidationResult result = validator().validate(session);

    assertThat(result.allIssues()).anySatisfy(issue -> {
      assertThat(issue.sheetName()).isEqualTo(ConfigPackageExcelValidator.WF_DEF_SHEET);
      assertThat(issue.columnName()).isEqualTo("calendar_code");
      assertThat(issue.message()).contains("missing_calendar");
    });
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
      assertThat(issue.message()).contains("tpl_missing");
    });
  }

  @Test
  @DisplayName("多上游作业依赖:配置包校验拒绝环并允许逗号分隔的多个上游")
  void shouldRejectCyclesInMultiJobDependencies() {
    Map<String, String> first = new LinkedHashMap<>(jobRow("", "", ""));
    first.put("job_code", "JOB_FIRST");
    first.put("job_name", "作业一");
    first.put("depends_on_job_codes", "JOB_SECOND, JOB_THIRD");
    Map<String, String> second = new LinkedHashMap<>(jobRow("", "", ""));
    second.put("job_code", "JOB_SECOND");
    second.put("job_name", "作业二");
    second.put("depends_on_job_codes", "JOB_FIRST");
    Map<String, String> third = new LinkedHashMap<>(jobRow("", "", ""));
    third.put("job_code", "JOB_THIRD");
    third.put("job_name", "作业三");

    ConfigPackageExcelValidator.PackageValidationResult result =
        validator().validate(session(List.of(), List.of(first, second, third)));

    assertThat(result.allIssues())
        .anySatisfy(
            issue -> assertThat(issue.message()).contains("dependency graph contains a cycle"));
  }

  @Test
  @DisplayName("逐边业务日对齐规则数量必须与上游作业编码一致")
  void shouldRejectMisalignedJobDependencyDateAlignments() {
    Map<String, String> downstream = new LinkedHashMap<>(jobRow("", "", ""));
    downstream.put("job_code", "JOB_DOWNSTREAM");
    downstream.put("job_name", "下游作业");
    downstream.put("depends_on_job_codes", "JOB_UPSTREAM_A,JOB_UPSTREAM_B");
    downstream.put("depends_on_biz_date_alignments", "PREVIOUS_BIZ_DAY");
    Map<String, String> upstreamA = new LinkedHashMap<>(jobRow("", "", ""));
    upstreamA.put("job_code", "JOB_UPSTREAM_A");
    upstreamA.put("job_name", "上游作业 A");
    Map<String, String> upstreamB = new LinkedHashMap<>(jobRow("", "", ""));
    upstreamB.put("job_code", "JOB_UPSTREAM_B");
    upstreamB.put("job_name", "上游作业 B");

    ConfigPackageExcelValidator.PackageValidationResult result =
        validator().validate(session(List.of(), List.of(downstream, upstreamA, upstreamB)));

    assertThat(result.allIssues())
        .anySatisfy(issue -> assertThat(issue.columnName())
            .isEqualTo(ConfigPackageExcelValidator.COL_DEPENDS_ON_BIZ_DATE_ALIGNMENTS));
  }

  @Test
  @DisplayName("多上游作业依赖:数量上限与 Console 契约保持一致")
  void shouldRejectMoreThanThirtyTwoJobDependencies() {
    Map<String, String> downstream = new LinkedHashMap<>(jobRow("", "", ""));
    downstream.put("job_code", "JOB_DOWNSTREAM");
    downstream.put("job_name", "下游作业");
    downstream.put(
        "depends_on_job_codes",
        IntStream.range(0, 33)
            .mapToObj(index -> "UPSTREAM_" + index)
            .collect(Collectors.joining(",")));

    ConfigPackageExcelValidator.PackageValidationResult result =
        validator().validate(session(List.of(), List.of(downstream)));

    assertThat(result.allIssues())
        .anySatisfy(issue -> assertThat(issue.message()).contains("at most 32 upstream jobs"));
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
  @DisplayName("固定宽度导入映射:使用 start/length/target 契约并接受合法字段")
  void shouldAcceptFixedWidthMappings_whenWorkerContractIsSatisfied() {
    Map<String, String> row = new LinkedHashMap<>(fileTemplateRow("TPL", "1"));
    row.put("file_format_type", "FIXED_WIDTH");
    row.put("field_mappings", "[{\"start\":0,\"length\":12,\"target\":\"customer_no\"}]");

    ConfigPackageExcelValidator.PackageValidationResult result =
        validator().validate(session(List.of(row), List.of()));

    assertThat(result.fileTemplates().invalid()).isZero();
    assertThat(result.allIssues()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"12", "true"})
  @DisplayName("固定宽度导入映射:目标字段必须是字符串")
  void shouldRejectNonTextFixedWidthTarget(String target) {
    Map<String, String> row = new LinkedHashMap<>(fileTemplateRow("TPL", "1"));
    row.put("file_format_type", "FIXED_WIDTH");
    row.put("field_mappings", "[{\"start\":0,\"length\":12,\"target\":" + target + "}]");

    ConfigPackageExcelValidator.PackageValidationResult result =
        validator().validate(session(List.of(row), List.of()));

    assertThat(result.fileTemplates().invalid()).isEqualTo(1);
  }

  @Test
  @DisplayName("固定宽度导入映射:缺少 Worker 必需字段时被标记为问题")
  void shouldFlagInvalidFixedWidthMapping_whenTargetOrBoundsAreMissing() {
    Map<String, String> row = new LinkedHashMap<>(fileTemplateRow("TPL", "1"));
    row.put("file_format_type", "FIXED_WIDTH");
    row.put("field_mappings", "[{\"start\":0,\"length\":12,\"target\":\" \"}]");

    ConfigPackageExcelValidator.PackageValidationResult result =
        validator().validate(session(List.of(row), List.of()));

    assertThat(result.fileTemplates().invalid()).isEqualTo(1);
    assertThat(result.allIssues())
        .anySatisfy(
            issue -> assertThat(issue.message())
                .contains(
                    "FIXED_WIDTH field mappings require non-blank target, non-negative start and positive length"));
  }

  @Test
  @DisplayName("固定宽度导入映射:未提供映射时在预览阶段被拒绝")
  void shouldRejectFixedWidthTemplate_whenFieldMappingsAreMissing() {
    Map<String, String> row = new LinkedHashMap<>(fileTemplateRow("TPL", "1"));
    row.put("file_format_type", "FIXED_WIDTH");

    ConfigPackageExcelValidator.PackageValidationResult result =
        validator().validate(session(List.of(row), List.of()));

    assertThat(result.fileTemplates().invalid()).isEqualTo(1);
    assertThat(result.allIssues())
        .anySatisfy(issue -> assertThat(issue.message())
            .contains("FIXED_WIDTH imports require at least one field mapping"));
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

  @Test
  @DisplayName("导出 SQL:注释和字符串常量中的写关键字不应误报")
  void shouldAllowWriteKeywordsInsideCommentsAndStringLiterals() {
    Map<String, String> row = new LinkedHashMap<>(fileTemplateRow("TPL", "1"));
    row.put(
        "default_query_sql",
        "SELECT 'INSERT UPDATE DELETE SELECT *' AS note FROM biz.customer_account "
            + "WHERE tenant_id = :tenantId /* DROP ALTER TRUNCATE */ -- GRANT EXEC");

    ConfigPackageExcelValidator.PackageValidationResult result =
        validator().validate(session(List.of(row)));

    assertThat(result.allIssues())
        .noneSatisfy(issue -> assertThat(issue.message()).contains("default_query_sql"));
  }

  @Test
  @DisplayName("导出 SQL:真正的写语句仍在预览阶段被拒绝")
  void shouldRejectWriteStatements_whenExportSqlIsNotSelect() {
    for (String sql : List.of(
        "INSERT INTO biz.customer_account (id) VALUES (1)",
        "UPDATE biz.customer_account SET status = 'CLOSED' WHERE id = 1",
        "DELETE FROM biz.customer_account WHERE id = 1",
        "DROP TABLE biz.customer_account",
        "SELECT id FROM biz.customer_account; DELETE FROM biz.customer_account")) {
      Map<String, String> row = new LinkedHashMap<>(fileTemplateRow("TPL", "1"));
      row.put("default_query_sql", sql);

      ConfigPackageExcelValidator.PackageValidationResult result =
          validator().validate(session(List.of(row)));

      assertThat(result.allIssues())
          .anySatisfy(issue -> assertThat(issue.message())
              .contains("default_query_sql must be a single SELECT/WITH query"));
    }
  }

  @Test
  @DisplayName("导出 SQL:拒绝把写入语句藏在 WITH 子句中")
  void shouldRejectDataModifyingCommonTableExpression() {
    Map<String, String> row = new LinkedHashMap<>(fileTemplateRow("TPL", "1"));
    row.put(
        "default_query_sql",
        "WITH changed AS (DELETE FROM biz.customer_account WHERE id = 1 RETURNING id) "
            + "SELECT id FROM changed");

    ConfigPackageExcelValidator.PackageValidationResult result =
        validator().validate(session(List.of(row)));

    assertThat(result.allIssues())
        .anySatisfy(issue -> assertThat(issue.message())
            .contains("default_query_sql must be a single SELECT/WITH query"));
  }

  @Test
  @DisplayName("导出 SQL:拒绝 SELECT INTO 和 FOR UPDATE 等非只读查询")
  void shouldRejectSelectWithWriteOrLockClause() {
    for (String sql : List.of(
        "SELECT id INTO biz.customer_copy FROM biz.customer_account",
        "SELECT id FROM biz.customer_account FOR UPDATE")) {
      Map<String, String> row = new LinkedHashMap<>(fileTemplateRow("TPL", "1"));
      row.put("default_query_sql", sql);

      ConfigPackageExcelValidator.PackageValidationResult result =
          validator().validate(session(List.of(row)));

      assertThat(result.allIssues())
          .anySatisfy(issue -> assertThat(issue.message())
              .contains("default_query_sql must be a single SELECT/WITH query"));
    }
  }

  @Test
  @DisplayName("省略跨日列的普通合法窗口不增加已有窗口查询")
  void shouldAvoidWindowLookup_whenOmittedCrossDayAlreadyValid() {
    BatchWindowMapper windows = mock(BatchWindowMapper.class);
    ConfigPackageExcelValidator validator = validatorWithWindows(windows);
    PackageExcelSession session = sessionWithDependencies(
        List.of(), List.of(), List.of(windowRow("ordinary_window")), List.of());
    assertThat(validator.validate(session).batchWindows().invalid()).isZero();
    verifyNoInteractions(windows);
  }

  @Test
  @DisplayName("省略跨日列的反向窗口仅回查同租户同编码，并允许目标已有true")
  void shouldUseExistingCrossDay_onlyForAmbiguousOmittedFlag() {
    BatchWindowMapper windows = mock(BatchWindowMapper.class);
    when(windows.selectByUniqueKey("t1", "overnight_window"))
        .thenReturn(new BatchWindowRow(
            1L,
            "t1",
            "overnight_window",
            "跨日窗口",
            "UTC",
            "22:00:00",
            "02:00:00",
            "FINISH_RUNNING",
            "WAIT",
            true,
            true,
            null,
            null,
            null));
    Map<String, String> row = windowRow("overnight_window");
    row.put("start_time", "22:00");
    row.put("end_time", "02:00");
    PackageExcelSession session =
        sessionWithDependencies(List.of(), List.of(), List.of(row), List.of());
    assertThat(validatorWithWindows(windows).validate(session).batchWindows().invalid())
        .isZero();
    verify(windows).selectByUniqueKey("t1", "overnight_window");
  }

  @Test
  @DisplayName("显式false的反向窗口直接拒绝，不查询旧策略来覆盖请求")
  void shouldRejectExplicitFalse_withoutLookingUpExistingPolicy() {
    BatchWindowMapper windows = mock(BatchWindowMapper.class);
    Map<String, String> row = windowRow("overnight_window");
    row.put("start_time", "22:00");
    row.put("end_time", "02:00");
    row.put("allow_cross_day", "false");
    PackageExcelSession session =
        sessionWithDependencies(List.of(), List.of(), List.of(row), List.of());
    assertThat(validatorWithWindows(windows).validate(session).batchWindows().invalid())
        .isEqualTo(1);
    verifyNoInteractions(windows);
  }

  private static ConfigPackageExcelValidator validatorWithWindows(BatchWindowMapper windows) {
    return new ConfigPackageExcelValidator(
        mock(JobDefinitionMapper.class),
        mock(FileTemplateConfigMapper.class),
        mock(ResourceQueueMapper.class),
        mock(BusinessCalendarMapper.class),
        windows);
  }

  private static ConfigPackageExcelValidator validator() {
    return new ConfigPackageExcelValidator(
        mock(JobDefinitionMapper.class),
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
