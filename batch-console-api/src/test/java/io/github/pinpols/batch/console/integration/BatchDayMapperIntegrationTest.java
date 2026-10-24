package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.domain.job.mapper.BatchDayMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@DisplayName("BatchDayMapper 批次日汇总查询: 排除 dry-run 实例,暂停态计入在途")
class BatchDayMapperIntegrationTest extends AbstractIntegrationTest {

  private final JdbcTemplate jdbcTemplate;
  private final BatchDayMapper mapper;

  @Autowired
  BatchDayMapperIntegrationTest(JdbcTemplate jdbcTemplate, BatchDayMapper mapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.mapper = mapper;
  }

  @Test
  @DisplayName("汇总只统计真实实例,成功 / 失败 / 在途三个计数各归各口")
  void shouldExcludeDryRunAndCountPausedAsInFlight_whenSummarisingBatchDay() {
    String tenantId = unique("tenant");
    String calendarCode = unique("calendar");
    String jobCode = unique("job");
    LocalDate bizDate = LocalDate.of(2026, 9, 8);
    Long definitionId = insertJobDefinition(tenantId, calendarCode, jobCode);

    insertJobInstance(tenantId, definitionId, jobCode, bizDate, "SUCCESS", false);
    insertJobInstance(tenantId, definitionId, jobCode, bizDate, "FAILED", false);
    insertJobInstance(tenantId, definitionId, jobCode, bizDate, "PAUSED", false);
    insertJobInstance(tenantId, definitionId, jobCode, bizDate, "SUCCESS_DRY_RUN", true);
    insertJobInstance(tenantId, definitionId, jobCode, bizDate, "FAILED_DRY_RUN", true);

    List<Map<String, Object>> rows = mapper.selectJobSummaries(tenantId, calendarCode, bizDate);

    assertThat(rows).hasSize(1);
    Map<String, Object> summary = rows.getFirst();
    assertThat(number(summary, "totalJobCount")).isEqualTo(3);
    assertThat(number(summary, "successJobCount")).isEqualTo(1);
    assertThat(number(summary, "failedJobCount")).isEqualTo(1);
    assertThat(number(summary, "inFlightJobCount")).isEqualTo(1);
  }

  @Test
  @DisplayName("日窗口 occurrence 只返回当前租户、日历和业务日，并保留关闭日拒绝原因")
  void shouldListScheduledOccurrencesForRequestedTenantCalendarAndDate() {
    String tenantId = unique("tenant");
    String otherTenantId = unique("tenant");
    String calendarCode = unique("calendar");
    LocalDate bizDate = LocalDate.of(2026, 10, 15);
    Instant fireTime = Instant.parse("2026-10-15T02:00:00Z");

    insertScheduledOccurrence(
        tenantId,
        calendarCode,
        "workflow-closed-fire",
        fireTime,
        bizDate,
        "CLOSED_DAY_REJECTED",
        "BATCH_DAY_CLOSED: explicit CATCH_UP or RERUN request required");
    insertScheduledOccurrence(
        tenantId,
        unique("calendar"),
        "workflow-other-calendar",
        fireTime.plusSeconds(60),
        bizDate,
        "REQUESTED",
        null);
    insertScheduledOccurrence(
        otherTenantId,
        calendarCode,
        "workflow-other-tenant",
        fireTime.plusSeconds(120),
        bizDate,
        "REQUESTED",
        null);
    insertScheduledOccurrence(
        tenantId,
        calendarCode,
        "workflow-other-date",
        fireTime.plusSeconds(180),
        bizDate.plusDays(1),
        "REQUESTED",
        null);

    List<Map<String, Object>> occurrences =
        mapper.selectScheduledOccurrences(tenantId, calendarCode, bizDate);

    assertThat(occurrences).hasSize(1);
    Map<String, Object> rejected = occurrences.getFirst();
    assertThat(rejected.get("workflowCode")).isEqualTo("workflow-closed-fire");
    assertThat(rejected.get("occurrenceStatus")).isEqualTo("CLOSED_DAY_REJECTED");
    assertThat(rejected.get("requestId")).isNull();
    assertThat(rejected.get("lastRejectionReason").toString())
        .contains("explicit CATCH_UP or RERUN request required");
  }

  private void insertScheduledOccurrence(
      String tenantId,
      String calendarCode,
      String workflowCode,
      Instant fireTime,
      LocalDate bizDate,
      String status,
      String rejectionReason) {
    final String insertWorkflowDefinitionSql = """
    insert into batch.workflow_definition
    (tenant_id, workflow_code, workflow_name, workflow_type, version, enabled, calendar_code)
    values (?, ?, ?, 'DAG', 1, true, ?) returning id
    """;
    Long workflowId = jdbcTemplate.queryForObject(
        insertWorkflowDefinitionSql,
        Long.class,
        tenantId,
        workflowCode,
        workflowCode,
        calendarCode);
    final String insertScheduledOccurrenceSql = """
    insert into batch.scheduled_occurrence (
    tenant_id, workflow_definition_id, workflow_code, schedule_version, calendar_code,
    scheduled_fire_time, biz_date, occurrence_status, last_rejection_reason
    ) values (?, ?, ?, 1, ?, ?, ?, ?, ?)
    """;
    Timestamp scheduledFireTimestamp = Timestamp.from(fireTime);
    jdbcTemplate.update(
        insertScheduledOccurrenceSql,
        tenantId,
        workflowId,
        workflowCode,
        calendarCode,
        scheduledFireTimestamp,
        bizDate,
        status,
        rejectionReason);
  }

  private Long insertJobDefinition(String tenantId, String calendarCode, String jobCode) {
    String insertJobDefinitionSql = """
        insert into batch.job_definition(
          tenant_id, job_code, job_name, job_type, schedule_type, timezone, calendar_code
        ) values (?, ?, 'Batch Day Console Test', 'GENERAL', 'MANUAL', 'Asia/Shanghai', ?)
        returning id
        """;
    return jdbcTemplate.queryForObject(
        insertJobDefinitionSql, Long.class, tenantId, jobCode, calendarCode);
  }

  private void insertJobInstance(
      String tenantId,
      Long definitionId,
      String jobCode,
      LocalDate bizDate,
      String status,
      boolean dryRun) {
    String suffix = UUID.randomUUID().toString();
    final String insertJobInstanceSql = """
    insert into batch.job_instance(
    tenant_id, job_definition_id, job_code, instance_no, biz_date, trigger_type,
    instance_status, priority, dedup_key, expected_partition_count,
    success_partition_count, failed_partition_count, trace_id, dry_run
    ) values (?, ?, ?, ?, ?, 'MANUAL', ?, 5, ?, 0, 0, 0, ?, ?)
    """;
    String instanceNumber = "INST-" + suffix;
    String dedupKey = "DEDUP-" + suffix;
    String traceId = "TRACE-" + suffix;
    jdbcTemplate.update(
        insertJobInstanceSql,
        tenantId,
        definitionId,
        jobCode,
        instanceNumber,
        bizDate,
        status,
        dedupKey,
        traceId,
        dryRun);
  }

  private static long number(Map<String, Object> row, String key) {
    return ((Number) row.get(key)).longValue();
  }

  private static String unique(String prefix) {
    return prefix + "-" + UUID.randomUUID();
  }
}
