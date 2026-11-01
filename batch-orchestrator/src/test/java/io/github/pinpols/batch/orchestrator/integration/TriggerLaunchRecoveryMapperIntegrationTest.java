package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.domain.param.TriggerLaunchRecoveryAttemptParam;
import io.github.pinpols.batch.orchestrator.domain.param.TriggerLaunchRecoveryRetryParam;
import io.github.pinpols.batch.orchestrator.domain.view.TriggerLaunchRecoveryView;
import io.github.pinpols.batch.orchestrator.mapper.TriggerLaunchRecoveryMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link TriggerLaunchRecoveryMapper} 在真实 PostgreSQL 上的幂等登记、到期扫描与状态 CAS 回归。
 *
 * <p>对应用户要求：Kafka 消费重试耗尽后落持久恢复锚点，由周期扫描幂等重投；本测试验证 V348 表与 SQL 语义。
 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("启动恢复锚点持久层: 幂等登记、到期扫描与状态 CAS 的真实数据库行为")
class TriggerLaunchRecoveryMapperIntegrationTest extends AbstractIntegrationTest {

  private static final String SELECT_ROW_SQL =
      "select envelope_ciphertext, trace_id, recovery_status, attempts, last_error_code"
          + " from batch.trigger_launch_recovery where tenant_id = ? and request_id = ?";

  private final String tenantId = "launch-recovery-" + Long.toUnsignedString(System.nanoTime());

  private final TriggerLaunchRecoveryMapper mapper;
  private final JdbcTemplate jdbcTemplate;

  @Autowired
  TriggerLaunchRecoveryMapperIntegrationTest(
      TriggerLaunchRecoveryMapper mapper, JdbcTemplate jdbcTemplate) {
    this.mapper = mapper;
    this.jdbcTemplate = jdbcTemplate;
  }

  @AfterEach
  void cleanUp() {
    String deleteTriggerLaunchRecoverySql =
        "delete from batch.trigger_launch_recovery where tenant_id = ?";
    jdbcTemplate.update(deleteTriggerLaunchRecoverySql, tenantId);
  }

  @Test
  @DisplayName("重试退避按已累计次数增长和封顶，成功终态不被失败覆盖")
  void shouldCalculateBackoffAndPreserveDone() {
    mapper.insertPending(tenantId, "req-retry", "c1", null);
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    TriggerLaunchRecoveryRetryParam retry = TriggerLaunchRecoveryRetryParam.builder()
        .tenantId(tenantId)
        .requestId("req-retry")
        .recoveryGeneration(0L)
        .errorCode("failure")
        .now(now)
        .maxAttempts(5)
        .baseBackoffSeconds(30)
        .maxBackoffSeconds(90)
        .build();
    String sql =
        "select next_attempt_at from batch.trigger_launch_recovery where tenant_id=? and request_id=?";
    for (long seconds : List.of(30L, 60L, 90L)) {
      assertThat(mapper.markRetryOrDead(retry)).isEqualTo("PENDING");
      assertThat(jdbcTemplate
              .queryForObject(sql, Timestamp.class, tenantId, "req-retry")
              .toInstant())
          .isEqualTo(now.plusSeconds(seconds));
    }
    assertThat(mapper.markDone(tenantId, "req-retry", "DONE")).isEqualTo(1);
    assertThat(mapper.markRetryOrDead(retry)).isNull();
    assertThat(jdbcTemplate.queryForMap(SELECT_ROW_SQL, tenantId, "req-retry"))
        .containsEntry("recovery_status", "DONE")
        .containsEntry("attempts", 3);
  }

  @Test
  @DisplayName("重复登记保留原密文；DONE 终态不被新投递复活")
  void shouldUpsertIdempotentlyWithoutResurrectingTerminalStatus() {
    assertThat(mapper.insertPending(tenantId, "req-1", "cipher-v1", "trace-1")).isEqualTo(1);
    assertThat(mapper.insertPending(tenantId, "req-1", "cipher-v2", "trace-2")).isZero();

    Map<String, Object> row = jdbcTemplate.queryForMap(SELECT_ROW_SQL, tenantId, "req-1");
    assertThat(row.get("envelope_ciphertext")).isEqualTo("cipher-v1");
    assertThat(row.get("trace_id")).isEqualTo("trace-1");
    assertThat(row.get("recovery_status")).isEqualTo("PENDING");

    assertThat(mapper.markDone(tenantId, "req-1", "DONE")).isEqualTo(1);
    assertThat(mapper.insertPending(tenantId, "req-1", "cipher-v3", "trace-3")).isZero();
    Map<String, Object> terminal = jdbcTemplate.queryForMap(SELECT_ROW_SQL, tenantId, "req-1");
    assertThat(terminal.get("recovery_status")).isEqualTo("DONE");
    assertThat(terminal.get("envelope_ciphertext")).isEqualTo("cipher-v1");
  }

  @Test
  @DisplayName("到期扫描只取 PENDING 且 next_attempt_at 已到期的记录，并遵守 limit")
  void shouldSelectOnlyDuePendingRecords() {
    mapper.insertPending(tenantId, "req-due", "c1", null);
    mapper.insertPending(tenantId, "req-future", "c2", null);
    String updateTriggerLaunchRecoverySql =
        "update batch.trigger_launch_recovery set next_attempt_at = now() - interval '1 minute'"
            + " where tenant_id = ? and request_id = 'req-due'";
    jdbcTemplate.update(updateTriggerLaunchRecoverySql, tenantId);
    String updateTriggerLaunchRecoverySql2 =
        "update batch.trigger_launch_recovery set next_attempt_at = now() + interval '1 hour'"
            + " where tenant_id = ? and request_id = 'req-future'";
    jdbcTemplate.update(updateTriggerLaunchRecoverySql2, tenantId);

    List<TriggerLaunchRecoveryView> due =
        mapper.selectDue(BatchDateTimeSupport.utcNow(), 50).stream()
            .filter(entity -> tenantId.equals(entity.getTenantId()))
            .toList();
    assertThat(due).extracting(TriggerLaunchRecoveryView::getRequestId).containsExactly("req-due");
    assertThat(due.getFirst().getEnvelopeCiphertext()).isEqualTo("c1");
  }

  @Test
  @DisplayName("markAttempt 是 PENDING 独占 CAS：推进后 attempts+1，终态上调用不生效")
  void shouldApplyAttemptCasOnlyOnPendingStatus() {
    mapper.insertPending(tenantId, "req-cas", "c1", "trace-cas");
    Instant nextAttemptAt = BatchDateTimeSupport.utcNow().plusSeconds(30);

    assertThat(mapper.markAttempt(TriggerLaunchRecoveryAttemptParam.builder()
            .tenantId(tenantId)
            .requestId("req-cas")
            .recoveryGeneration(0L)
            .status("PENDING")
            .nextAttemptAt(nextAttemptAt)
            .errorCode("IllegalStateException")
            .build()))
        .isEqualTo(1);
    Map<String, Object> retried = jdbcTemplate.queryForMap(SELECT_ROW_SQL, tenantId, "req-cas");
    assertThat(retried.get("attempts")).isEqualTo(1);
    assertThat(retried.get("last_error_code")).isEqualTo("IllegalStateException");

    assertThat(mapper.markDone(tenantId, "req-cas", "DONE")).isEqualTo(1);
    assertThat(mapper.markAttempt(TriggerLaunchRecoveryAttemptParam.builder()
            .tenantId(tenantId)
            .requestId("req-cas")
            .recoveryGeneration(0L)
            .status("PENDING")
            .nextAttemptAt(nextAttemptAt)
            .errorCode("again")
            .build()))
        .isZero();
    assertThat(mapper.markDone(tenantId, "req-cas", "DONE")).isZero();
  }
}
