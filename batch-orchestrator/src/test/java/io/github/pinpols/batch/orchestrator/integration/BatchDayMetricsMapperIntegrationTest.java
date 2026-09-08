package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayInstanceMetrics;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
class BatchDayMetricsMapperIntegrationTest extends AbstractIntegrationTest {

  private final JdbcTemplate jdbcTemplate;
  private final JobInstanceMapper mapper;

  @Autowired
  BatchDayMetricsMapperIntegrationTest(JdbcTemplate jdbcTemplate, JobInstanceMapper mapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.mapper = mapper;
  }

  @Test
  void metricsExcludeDryRunAndTreatPausedAsActive() {
    String tenantId = unique("tenant");
    String calendarCode = unique("calendar");
    LocalDate bizDate = LocalDate.of(2026, 9, 8);
    String jobCode = unique("job");
    Long definitionId = insertJobDefinition(tenantId, calendarCode, jobCode, null);

    insertJobInstance(tenantId, definitionId, jobCode, bizDate, "SUCCESS", false);
    insertJobInstance(tenantId, definitionId, jobCode, bizDate, "FAILED", false);
    insertJobInstance(tenantId, definitionId, jobCode, bizDate, "PAUSED", false);
    insertJobInstance(tenantId, definitionId, jobCode, bizDate, "SUCCESS_DRY_RUN", true);
    insertJobInstance(tenantId, definitionId, jobCode, bizDate, "FAILED_DRY_RUN", true);

    BatchDayInstanceMetrics metrics = mapper.selectBatchDayMetrics(tenantId, calendarCode, bizDate);

    assertThat(metrics.getTotalCount()).isEqualTo(3);
    assertThat(metrics.getSuccessCount()).isEqualTo(1);
    assertThat(metrics.getFailedCount()).isEqualTo(1);
    assertThat(metrics.getActiveCount()).isEqualTo(1);
  }

  @Test
  void batchDayGateTreatsEveryLifecycleTerminalStatusAsComplete() {
    String tenantId = unique("tenant");
    String calendarCode = unique("calendar");
    String jobCode = unique("gate-job");
    String jobGroupCode = unique("group");
    LocalDate bizDate = LocalDate.of(2026, 9, 7);
    Long definitionId = insertJobDefinition(tenantId, calendarCode, jobCode, jobGroupCode);

    insertJobInstance(tenantId, definitionId, jobCode, bizDate, "PARTIAL_FAILED", false);
    insertJobInstance(tenantId, definitionId, jobCode, bizDate, "SUCCESS_DRY_RUN", true);
    insertJobInstance(tenantId, definitionId, jobCode, bizDate, "FAILED_DRY_RUN", true);

    assertThat(mapper.countNonTerminalByJobCodeAndBizDate(tenantId, jobCode, bizDate))
        .isZero();
    assertThat(mapper.countNonTerminalByJobGroupAndBizDate(tenantId, jobGroupCode, bizDate))
        .isZero();

    insertJobInstance(tenantId, definitionId, jobCode, bizDate, "PAUSED", false);

    assertThat(mapper.countNonTerminalByJobCodeAndBizDate(tenantId, jobCode, bizDate))
        .isOne();
    assertThat(mapper.countNonTerminalByJobGroupAndBizDate(tenantId, jobGroupCode, bizDate))
        .isOne();
  }

  private Long insertJobDefinition(
      String tenantId, String calendarCode, String jobCode, String jobGroupCode) {
    return jdbcTemplate.queryForObject(
        """
        insert into batch.job_definition(
          tenant_id, job_code, job_name, job_type, schedule_type, timezone, calendar_code,
          job_group_code
        ) values (?, ?, 'Batch Day Metrics Test', 'GENERAL', 'MANUAL', 'Asia/Shanghai', ?, ?)
        returning id
        """, Long.class, tenantId, jobCode, calendarCode, jobGroupCode);
  }

  private void insertJobInstance(
      String tenantId,
      Long definitionId,
      String jobCode,
      LocalDate bizDate,
      String status,
      boolean dryRun) {
    jdbcTemplate.update(
        """
        insert into batch.job_instance(
          tenant_id, job_definition_id, job_code, instance_no, biz_date, trigger_type,
          instance_status, priority, dedup_key, expected_partition_count,
          success_partition_count, failed_partition_count, trace_id, dry_run
        ) values (?, ?, ?, ?, ?, 'MANUAL', ?, 5, ?, 0, 0, 0, ?, ?)
        """,
        tenantId,
        definitionId,
        jobCode,
        unique("instance"),
        bizDate,
        status,
        unique("dedup"),
        unique("trace"),
        dryRun);
  }

  private String unique(String prefix) {
    return prefix + "-" + UUID.randomUUID();
  }
}
