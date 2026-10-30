package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.domain.observability.application.contract.response.ConsoleSlaReportResponse;
import io.github.pinpols.batch.console.domain.observability.mapper.ConsoleDashboardQueryMapper;
import io.github.pinpols.batch.console.domain.observability.view.dashboard.SlaDayView;
import io.github.pinpols.batch.console.domain.observability.view.dashboard.SlaJobReportView;
import io.github.pinpols.batch.console.domain.observability.view.dashboard.SlaReportView;
import io.github.pinpols.batch.console.domain.observability.view.dashboard.SlaStatsView;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@DisplayName("Dashboard SLA 报表:终态缺失完成时间不能被计为按时")
class ConsoleDashboardSlaMapperIntegrationTest extends AbstractIntegrationTest {

  private final JdbcTemplate jdbcTemplate;

  private final ConsoleDashboardQueryMapper mapper;

  @Autowired
  ConsoleDashboardSlaMapperIntegrationTest(
      JdbcTemplate jdbcTemplate, ConsoleDashboardQueryMapper mapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.mapper = mapper;
  }

  @Test
  @Transactional
  @DisplayName("真实 PostgreSQL 中，缺失 finished_at 的终态记录留在分母但不归为达标")
  void shouldNotCountTerminalInstanceWithoutFinishTimeAsOnTime() {
    String tenantId = "sla-" + UUID.randomUUID();
    String jobCode = "job-" + UUID.randomUUID();
    String insertDefinitionSql = """
        insert into batch.job_definition(
          tenant_id, job_code, job_name, job_type, schedule_type, timezone
        ) values (?, ?, 'SLA mapper test', 'GENERAL', 'MANUAL', 'Asia/Shanghai') returning id
        """;
    Long definitionId =
        jdbcTemplate.queryForObject(insertDefinitionSql, Long.class, tenantId, jobCode);
    Instant now = Instant.now();
    Instant deadline = now.minusSeconds(60);
    Instant startedAt = now.minusSeconds(300);
    insertInstance(
        tenantId, definitionId, jobCode, "SUCCESS", startedAt, deadline, now.minusSeconds(120));
    insertInstance(
        tenantId, definitionId, jobCode, "FAILED", startedAt, deadline, now.minusSeconds(30));
    insertInstance(tenantId, definitionId, jobCode, "SUCCESS", startedAt, deadline, null);
    insertInstance(
        tenantId, definitionId, jobCode, "CANCELLED", startedAt, deadline, now.minusSeconds(120));
    insertInstance(
        tenantId,
        definitionId,
        jobCode,
        "SUCCESS_DRY_RUN",
        startedAt,
        deadline,
        now.minusSeconds(120));

    SlaStatsView stats = mapper.slaStats(tenantId, 30);
    List<SlaDayView> trend = mapper.slaDailyTrend(tenantId, 30);
    List<SlaJobReportView> reports = mapper.slaJobReport(tenantId, 30);

    assertThat(stats.totalWithSla()).isEqualTo(3L);
    assertThat(stats.breached()).isEqualTo(1L);
    assertThat(stats.onTime()).isEqualTo(1L);
    assertThat(trend).hasSize(1);
    assertThat(trend.getFirst().breached()).isEqualTo(1L);
    assertThat(trend.getFirst().onTime()).isEqualTo(1L);
    assertThat(reports).hasSize(1);
    assertThat(reports.getFirst().totalInstances()).isEqualTo(3L);
    assertThat(reports.getFirst().slaTotalWithSla()).isEqualTo(3L);
    assertThat(reports.getFirst().slaBreached()).isEqualTo(1L);
    assertThat(reports.getFirst().slaOnTime()).isEqualTo(1L);
    ConsoleSlaReportResponse response =
        ConsoleSlaReportResponse.from(new SlaReportView(tenantId, 30, reports));
    assertThat(response.jobs().getFirst().slaTotalWithSla()).isEqualTo(3L);
  }

  private void insertInstance(
      String tenantId,
      Long definitionId,
      String jobCode,
      String status,
      Instant startedAt,
      Instant deadline,
      Instant finishedAt) {
    String suffix = UUID.randomUUID().toString();
    String insertInstanceSql = """
        insert into batch.job_instance(
          tenant_id, job_definition_id, job_code, instance_no, biz_date, trigger_type,
          instance_status, priority, dedup_key, expected_partition_count,
          success_partition_count, failed_partition_count, trace_id, dry_run,
          deadline_at, started_at, finished_at
        ) values (?, ?, ?, ?, current_date, 'MANUAL', ?, 5, ?, 0, 0, 0, ?, ?, ?, ?, ?)
        """;
    jdbcTemplate.update(
        insertInstanceSql,
        tenantId,
        definitionId,
        jobCode,
        "INST-" + suffix,
        status,
        "DEDUP-" + suffix,
        "TRACE-" + suffix,
        status.endsWith("_DRY_RUN"),
        Timestamp.from(deadline),
        Timestamp.from(startedAt),
        finishedAt == null ? null : Timestamp.from(finishedAt));
  }
}
