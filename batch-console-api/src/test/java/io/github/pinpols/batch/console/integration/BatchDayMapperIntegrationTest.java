package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.domain.job.mapper.BatchDayMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class BatchDayMapperIntegrationTest extends AbstractIntegrationTest {

  private final JdbcTemplate jdbcTemplate;
  private final BatchDayMapper mapper;

  @Autowired
  BatchDayMapperIntegrationTest(JdbcTemplate jdbcTemplate, BatchDayMapper mapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.mapper = mapper;
  }

  @Test
  void jobSummariesExcludeDryRunAndTreatPausedAsInFlight() {
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

  private Long insertJobDefinition(String tenantId, String calendarCode, String jobCode) {
    return jdbcTemplate.queryForObject("""
        insert into batch.job_definition(
          tenant_id, job_code, job_name, job_type, schedule_type, timezone, calendar_code
        ) values (?, ?, 'Batch Day Console Test', 'GENERAL', 'MANUAL', 'Asia/Shanghai', ?)
        returning id
        """, Long.class, tenantId, jobCode, calendarCode);
  }

  private void insertJobInstance(
      String tenantId,
      Long definitionId,
      String jobCode,
      LocalDate bizDate,
      String status,
      boolean dryRun) {
    String suffix = UUID.randomUUID().toString();
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
        "INST-" + suffix,
        bizDate,
        status,
        "DEDUP-" + suffix,
        "TRACE-" + suffix,
        dryRun);
  }

  private static long number(Map<String, Object> row, String key) {
    return ((Number) row.get(key)).longValue();
  }

  private static String unique(String prefix) {
    return prefix + "-" + UUID.randomUUID();
  }
}
