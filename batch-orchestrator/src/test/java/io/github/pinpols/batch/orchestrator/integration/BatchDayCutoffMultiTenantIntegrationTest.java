package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.infrastructure.scheduler.BatchDayCutoffScheduler;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.sql.Date;
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
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("批日 cutoff 全局扫描的租户隔离")
class BatchDayCutoffMultiTenantIntegrationTest extends AbstractIntegrationTest {

  @Autowired
  private BatchDayCutoffScheduler scheduler;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Test
  @DisplayName("全局 OPEN 扫描逐租户推进状态并写入本租户审计")
  void shouldAdvanceAndAuditEachTenantCandidateIndependently() {
    String suffix = UUID.randomUUID().toString().replace("-", "");
    String tenantA = "cutoff-a-" + suffix;
    String tenantB = "cutoff-b-" + suffix;
    LocalDate bizDate = LocalDate.of(2026, 10, 16);
    insertOpenBatchDay(tenantA, "CAL_A", bizDate);
    insertOpenBatchDay(tenantB, "CAL_B", bizDate);

    try {
      scheduler.advance();

      assertThat(dayStatus(tenantA, "CAL_A", bizDate)).isEqualTo("CUTOFF");
      assertThat(dayStatus(tenantB, "CAL_B", bizDate)).isEqualTo("CUTOFF");
      assertThat(cutoffAuditCount(tenantA, "CAL_A", bizDate)).isEqualTo(1);
      assertThat(cutoffAuditCount(tenantB, "CAL_B", bizDate)).isEqualTo(1);
      String tenantCutoffAuditCountSql = """
          select count(*) from batch.job_execution_log
          where tenant_id = ? and extra_json->>'toDayStatus' = 'CUTOFF'
          """;
      Integer tenantCutoffAuditCount =
          jdbcTemplate.queryForObject(tenantCutoffAuditCountSql, Integer.class, tenantA);
      assertThat(tenantCutoffAuditCount).isEqualTo(1);
    } finally {
      String deleteTenantCutoffAuditSql = """
          delete from batch.job_execution_log where tenant_id in (?, ?)
      """;
      jdbcTemplate.update(deleteTenantCutoffAuditSql, tenantA, tenantB);
      String deleteTenantBatchDaysSql = """
          delete from batch.batch_day_instance where tenant_id in (?, ?)
      """;
      jdbcTemplate.update(deleteTenantBatchDaysSql, tenantA, tenantB);
    }
  }

  @Test
  @DisplayName("到期记录推进一次并递增版本，未来和冻结记录保持原状")
  void shouldAdvanceOnlyDueDaysAndPreserveFutureAndFrozenDays() {
    String tenantId = "cutoff-policy-" + UUID.randomUUID().toString().replace("-", "");
    String insertCutoffPolicyDaysSql = """
        insert into batch.batch_day_instance (
          tenant_id, calendar_code, biz_date, day_status, cutoff_at, frozen, version
        ) values
          (?, 'DUE', current_date - 1, 'OPEN', current_timestamp - interval '1 day', false, 7),
          (?, 'FUTURE', current_date, 'OPEN', current_timestamp + interval '1 year', false, 7),
          (?, 'FROZEN', current_date - 1, 'OPEN', null, true, 7)
        """;
    jdbcTemplate.update(insertCutoffPolicyDaysSql, tenantId, tenantId, tenantId);
    try {
      scheduler.advance();
      scheduler.advance();

      String cutoffPolicyDaysSql = """
          select calendar_code, day_status, version from batch.batch_day_instance
          where tenant_id = ? order by calendar_code
          """;
      List<Map<String, Object>> actualDays =
          jdbcTemplate.queryForList(cutoffPolicyDaysSql, tenantId);
      assertThat(actualDays)
          .extracting(row -> row.get("day_status"))
          .containsExactly("CUTOFF", "OPEN", "OPEN");
      assertThat(actualDays).extracting(row -> row.get("version")).containsExactly(8L, 7L, 7L);
      String cutoffPolicyAuditSql = """
          select count(*) from batch.job_execution_log
          where tenant_id = ? and extra_json->>'toDayStatus' = 'CUTOFF'
          """;
      Long auditCount = jdbcTemplate.queryForObject(cutoffPolicyAuditSql, Long.class, tenantId);
      assertThat(auditCount).isEqualTo(1L);
    } finally {
      String deleteCutoffPolicyAuditSql = """
          delete from batch.job_execution_log where tenant_id = ?
          """;
      jdbcTemplate.update(deleteCutoffPolicyAuditSql, tenantId);
      String deleteCutoffPolicyDaysSql = """
          delete from batch.batch_day_instance where tenant_id = ?
          """;
      jdbcTemplate.update(deleteCutoffPolicyDaysSql, tenantId);
    }
  }

  private void insertOpenBatchDay(String tenantId, String calendarCode, LocalDate bizDate) {
    String insertOpenBatchDaySql = """
        insert into batch.batch_day_instance (
          tenant_id, calendar_code, biz_date, day_status, cutoff_at
        ) values (?, ?, ?, 'OPEN', current_timestamp - interval '1 day')
        """;
    Date sqlBusinessDate = Date.valueOf(bizDate);
    jdbcTemplate.update(insertOpenBatchDaySql, tenantId, calendarCode, sqlBusinessDate);
  }

  private String dayStatus(String tenantId, String calendarCode, LocalDate bizDate) {
    String batchDayStatusSql = """
        select day_status from batch.batch_day_instance
        where tenant_id = ? and calendar_code = ? and biz_date = ?
        """;
    Date sqlBusinessDate = Date.valueOf(bizDate);
    return jdbcTemplate.queryForObject(
        batchDayStatusSql, String.class, tenantId, calendarCode, sqlBusinessDate);
  }

  private int cutoffAuditCount(String tenantId, String calendarCode, LocalDate bizDate) {
    String cutoffAuditCountSql = """
        select count(*) from batch.job_execution_log
        where tenant_id = ? and log_type = 'AUDIT'
          and extra_json->>'calendarCode' = ? and extra_json->>'bizDate' = ?
          and extra_json->>'toDayStatus' = 'CUTOFF'
        """;
    return jdbcTemplate.queryForObject(
        cutoffAuditCountSql, Integer.class, tenantId, calendarCode, bizDate.toString());
  }
}
