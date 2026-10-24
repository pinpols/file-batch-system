package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.model.PageRequest;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.domain.job.mapper.BatchDayMapper;
import io.github.pinpols.batch.console.domain.job.query.BatchDayQuery;
import io.github.pinpols.batch.console.domain.job.view.BatchDayJobSummaryView;
import io.github.pinpols.batch.console.domain.job.view.BatchDayScheduledOccurrenceView;
import io.github.pinpols.batch.console.domain.job.view.BatchDayView;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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
  @DisplayName("批次日列表与窗口映射固定统计视图")
  void shouldMapBatchDayListAndWindowToTypedView() {
    String tenantId = unique("tenant");
    String calendarCode = unique("calendar");
    String jobCode = unique("job");
    LocalDate bizDate = LocalDate.of(2026, 9, 8);
    Instant cutoffAt = Instant.parse("2026-09-08T06:00:00Z");
    Instant slaDeadlineAt = Instant.parse("2026-09-08T07:00:00Z");
    Long definitionId = insertJobDefinition(tenantId, calendarCode, jobCode);
    insertJobInstance(tenantId, definitionId, jobCode, bizDate, "SUCCESS", false);
    final String insertBatchDaySql = """
        insert into batch.batch_day_instance (
          tenant_id, calendar_code, biz_date, day_status, cutoff_at, settled_at,
          sla_deadline_at, late_count, catchup_count
        ) values (?, ?, ?, 'SETTLED', ?, ?, ?, 2, 3)
        """;
    jdbcTemplate.update(
        insertBatchDaySql,
        tenantId,
        calendarCode,
        bizDate,
        Timestamp.from(cutoffAt),
        Timestamp.from(slaDeadlineAt),
        Timestamp.from(slaDeadlineAt));

    BatchDayView window = mapper.selectWindow(tenantId, calendarCode, bizDate);
    List<BatchDayView> rows = mapper.selectByQuery(BatchDayQuery.builder()
        .tenantId(tenantId)
        .calendarCode(calendarCode)
        .fromBizDate(bizDate)
        .toBizDate(bizDate)
        .pageRequest(new PageRequest(1, 10))
        .build());

    assertThat(window.bizDate()).isEqualTo(bizDate);
    assertThat(window.dayStatus()).isEqualTo("SETTLED");
    assertThat(window.cutoffAt()).isEqualTo(cutoffAt);
    assertThat(window.slaDeadlineAt()).isEqualTo(slaDeadlineAt);
    assertThat(window.lateCount()).isEqualTo(2);
    assertThat(window.catchupCount()).isEqualTo(3);
    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst().totalJobCount()).isEqualTo(1L);
    assertThat(rows.getFirst().successJobCount()).isEqualTo(1L);
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

    List<BatchDayJobSummaryView> rows = mapper.selectJobSummaries(tenantId, calendarCode, bizDate);

    assertThat(rows).hasSize(1);
    BatchDayJobSummaryView summary = rows.getFirst();
    assertThat(summary.totalJobCount()).isEqualTo(3L);
    assertThat(summary.successJobCount()).isEqualTo(1L);
    assertThat(summary.failedJobCount()).isEqualTo(1L);
    assertThat(summary.inFlightJobCount()).isEqualTo(1L);
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

    List<BatchDayScheduledOccurrenceView> occurrences =
        mapper.selectScheduledOccurrences(tenantId, calendarCode, bizDate);

    assertThat(occurrences).hasSize(1);
    BatchDayScheduledOccurrenceView rejected = occurrences.getFirst();
    assertThat(rejected.workflowCode()).isEqualTo("workflow-closed-fire");
    assertThat(rejected.occurrenceStatus()).isEqualTo("CLOSED_DAY_REJECTED");
    assertThat(rejected.requestId()).isNull();
    assertThat(rejected.lastRejectionReason())
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

  private static String unique(String prefix) {
    return prefix + "-" + UUID.randomUUID();
  }
}
