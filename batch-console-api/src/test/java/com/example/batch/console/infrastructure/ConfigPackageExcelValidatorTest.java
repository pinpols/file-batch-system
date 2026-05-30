package com.example.batch.console.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.example.batch.console.domain.job.mapper.BatchWindowMapper;
import com.example.batch.console.domain.job.mapper.BusinessCalendarMapper;
import com.example.batch.console.domain.job.mapper.JobDefinitionMapper;
import com.example.batch.console.domain.job.mapper.StepRegistryQueryMapper;
import com.example.batch.console.domain.ops.mapper.ResourceQueueMapper;
import com.example.batch.console.domain.workflow.mapper.PipelineDefinitionMapper;
import com.example.batch.console.infrastructure.excel.ConfigPackageExcelValidator;
import com.example.batch.console.mapper.FileTemplateConfigMapper;
import com.example.batch.console.mapper.JobDefinitionMapper;
import com.example.batch.console.mapper.PipelineDefinitionMapper;
import com.example.batch.console.mapper.ResourceQueueMapper;
import com.example.batch.console.mapper.StepRegistryQueryMapper;
import com.example.batch.console.support.excel.TenantConfigPackageExcelImportStore.PackageExcelSession;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConfigPackageExcelValidatorTest {

  @Test
  void processPipelineTypeAllowsProcessStages() {
    assertThat(ConfigPackageExcelValidator.STAGES_BY_TYPE).containsKey("PROCESS");
    assertThat(ConfigPackageExcelValidator.STAGES_BY_TYPE.get("PROCESS"))
        .containsExactlyInAnyOrder("PREPARE", "COMPUTE", "VALIDATE", "COMMIT", "FEEDBACK");
    assertThat(ConfigPackageExcelValidator.STAGE_CODES).contains("COMPUTE", "COMMIT");
  }

  @Test
  void validatesFileTemplateConfigSheetRows() {
    ConfigPackageExcelValidator validator = validator();
    PackageExcelSession session =
        session(
            List.of(
                fileTemplateRow("TPL_IMPORT_CUSTOMER", "1"),
                fileTemplateRow("TPL_IMPORT_CUSTOMER", "1")));

    ConfigPackageExcelValidator.PackageValidationResult result = validator.validate(session);

    assertThat(result.fileTemplates().sheetName())
        .isEqualTo(ConfigPackageExcelValidator.FILE_TEMPLATE_SHEET);
    assertThat(result.fileTemplates().valid()).isEqualTo(1);
    assertThat(result.fileTemplates().invalid()).isEqualTo(1);
    assertThat(result.allIssues())
        .anySatisfy(
            issue ->
                assertThat(issue.message()).contains("duplicate template_code + version in excel"));
  }

  @Test
  void reportsUnknownTemplateCodeReferencesFromJobDefaultParams() {
    ConfigPackageExcelValidator validator = validator();
    PackageExcelSession session =
        session(
            List.of(),
            List.of(
                Map.of(
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

    assertThat(result.allIssues())
        .anySatisfy(
            issue -> {
              assertThat(issue.sheetName()).isEqualTo(ConfigPackageExcelValidator.JOB_SHEET);
              assertThat(issue.columnName())
                  .isEqualTo(ConfigPackageExcelValidator.COL_DEFAULT_PARAMS);
              assertThat(issue.message()).contains("TPL_MISSING");
            });
  }

  @Test
  void validatesOptionalDependencySheetsAndCrossReferences() {
    ConfigPackageExcelValidator validator = validator();
    PackageExcelSession session =
        sessionWithDependencies(
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
  void crossReferenceIssuesKeepOriginalExcelRowNumberAfterInvalidRowsAreFiltered() {
    ConfigPackageExcelValidator validator = validator();
    Map<String, String> invalidRow = new LinkedHashMap<>(jobRow("missing-queue", "", ""));
    invalidRow.put("job_code", "BROKEN_JOB");
    invalidRow.remove("job_name");
    PackageExcelSession session =
        sessionWithDependencies(
            List.of(), List.of(), List.of(), List.of(invalidRow, jobRow("missing-queue", "", "")));

    ConfigPackageExcelValidator.PackageValidationResult result = validator.validate(session);

    assertThat(result.crossRefIssues())
        .anySatisfy(
            issue -> {
              assertThat(issue.sheetName()).isEqualTo(ConfigPackageExcelValidator.JOB_SHEET);
              assertThat(issue.columnName()).isEqualTo(ConfigPackageExcelValidator.COL_QUEUE_CODE);
              assertThat(issue.rowNo()).isEqualTo(3);
            });
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
