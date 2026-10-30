package io.github.pinpols.batch.worker.dispatchs.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.github.pinpols.batch.testing.OrchestratorWireMockSupport;
import io.github.pinpols.batch.worker.dispatchs.BatchWorkerDispatchApplication;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.channel.DispatchChannelHealthRepository;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.channel.DispatchChannelHealthService;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.channel.DispatchChannelHealthSnapshot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 集成测试：DispatchChannelHealthService 将结果写入真实业务数据库，且
 * retrieves consistent health snapshots.
 */
@SpringBootTest(
    classes = BatchWorkerDispatchApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("渠道健康服务集成:真实业务库上的健康快照落库、失败退避累积与放行判定")
class DispatchChannelHealthServiceIntegrationTest extends AbstractIntegrationTest {

  @DynamicPropertySource
  static void orchestratorStub(DynamicPropertyRegistry registry) {
    OrchestratorWireMockSupport.registerOrchestratorBaseUrls(registry);
  }

  @Autowired
  private DispatchChannelHealthService healthService;

  @Autowired
  private DispatchChannelHealthRepository healthRepository;

  @Test
  @DisplayName("投递成功落库健康快照:状态健康、连续失败数为零,并记录最后成功时间与探测信息")
  void shouldPersistHealthySnapshotOnSuccessfulDispatch() {
    Map<String, Object> channelConfig = channelConfig("t1", "ch-001", "API");

    healthService.recordDispatchOutcome(channelConfig, true, "ok", null);

    DispatchChannelHealthSnapshot snapshot = healthRepository.findHealth("t1", "ch-001");
    assertThat(snapshot).isNotNull();
    assertThat(snapshot.tenantId()).isEqualTo("t1");
    assertThat(snapshot.channelCode()).isEqualTo("ch-001");
    assertThat(snapshot.channelType()).isEqualTo("API");
    assertThat(snapshot.healthStatus()).isEqualTo("HEALTHY");
    assertThat(snapshot.consecutiveFailures()).isZero();
    assertThat(snapshot.lastSuccessAt()).isNotNull();
    assertThat(snapshot.probeMessage()).isEqualTo("ok");
  }

  @Test
  @DisplayName("连续两次投递失败后,连续失败数累加到二且健康状态降级")
  void shouldIncrementConsecutiveFailuresOnFailure() {
    Map<String, Object> channelConfig = channelConfig("t1", "ch-002", "SFTP");

    healthService.recordDispatchOutcome(channelConfig, false, "timeout", null);
    healthService.recordDispatchOutcome(channelConfig, false, "timeout", null);

    DispatchChannelHealthSnapshot snapshot = healthRepository.findHealth("t1", "ch-002");
    assertThat(snapshot).isNotNull();
    assertThat(snapshot.consecutiveFailures()).isEqualTo(2);
    assertThat(snapshot.lastFailureAt()).isNotNull();
    assertThat(snapshot.probeMessage()).isEqualTo("timeout");
    assertThat(snapshot.healthStatus()).isEqualTo("DEGRADED");
  }

  @Test
  @DisplayName("八路并发记录失败时失败次数不丢,退避窗口按最新一次失败推算且不少于十四分钟")
  void shouldKeepLatestExponentialBackoff_whenFailuresAreConcurrent() throws Exception {
    int concurrency = 8;
    Map<String, Object> channelConfig = channelConfig("t1", "ch-concurrent", "API");
    CountDownLatch ready = new CountDownLatch(concurrency);
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(concurrency)) {
      for (int i = 0; i < concurrency; i++) {
        executor.submit(() -> {
          ready.countDown();
          start.await();
          healthService.recordDispatchOutcome(channelConfig, false, "timeout", null);
          return null;
        });
      }
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      executor.shutdown();
      assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
    }

    DispatchChannelHealthSnapshot snapshot = healthRepository.findHealth("t1", "ch-concurrent");
    assertThat(snapshot).isNotNull();
    assertThat(snapshot.consecutiveFailures()).isEqualTo(concurrency);
    assertThat(Duration.between(snapshot.lastFailureAt(), snapshot.nextProbeAt()))
        .isGreaterThanOrEqualTo(Duration.ofMinutes(14));
  }

  @Test
  @DisplayName("失败之后投递成功时,状态恢复健康且连续失败数清零")
  void shouldResetConsecutiveFailuresAfterSuccess() {
    Map<String, Object> channelConfig = channelConfig("t1", "ch-003", "NAS");

    healthService.recordDispatchOutcome(channelConfig, false, "error", null);
    healthService.recordDispatchOutcome(channelConfig, false, "error", null);
    healthService.recordDispatchOutcome(channelConfig, true, "ok", null);

    DispatchChannelHealthSnapshot snapshot = healthRepository.findHealth("t1", "ch-003");
    assertThat(snapshot).isNotNull();
    assertThat(snapshot.healthStatus()).isEqualTo("HEALTHY");
    assertThat(snapshot.consecutiveFailures()).isZero();
    assertThat(snapshot.probeMessage()).isEqualTo("ok");
  }

  @Test
  @DisplayName("渠道尚无健康快照时默认放行投递")
  void shouldAllowDispatchWhenNoHealthSnapshotExists() {
    Map<String, Object> channelConfig = channelConfig("t1", "ch-new-999", "API");
    // 此前没有健康记录时，默认应允许通过。
    assertThat(healthService.allowDispatch(channelConfig)).isTrue();
  }

  @Test
  @DisplayName("渠道健康快照为健康状态时放行投递")
  void shouldAllowDispatchForHealthyChannel() {
    Map<String, Object> channelConfig = channelConfig("t1", "ch-004", "API");
    healthService.recordDispatchOutcome(channelConfig, true, "ok", null);

    assertThat(healthService.allowDispatch(channelConfig)).isTrue();
  }

  @Test
  @DisplayName("渠道连续失败进入不健康且退避尚未到期时,投递被拒绝放行")
  void shouldBlockDispatchForUnhealthyChannelBeforeBackoffExpires() {
    Map<String, Object> channelConfig = channelConfig("t1", "ch-005", "API");
    // 触发足够多的失败，使状态变为 UNHEALTHY（默认阈值为 5）。
    for (int i = 0; i < 5; i++) {
      healthService.recordDispatchOutcome(channelConfig, false, "error", null);
    }

    DispatchChannelHealthSnapshot snapshot = healthRepository.findHealth("t1", "ch-005");
    assertThat(snapshot.healthStatus()).isIn("UNHEALTHY", "DEGRADED");
    // 退避时间尚未结束，因此应阻止分发。
    assertThat(healthService.allowDispatch(channelConfig)).isFalse();
  }

  @Test
  @DisplayName("具名快照写入保留全部字段、可空时间和租户隔离，半开探针只允许一次抢占")
  void shouldPersistSnapshotAndFenceHalfOpenClaims() {
    String tenantId = "typed-health-owner";
    String channelCode = "typed-half-open";
    insertChannelConfig(platformJdbcTemplate(), tenantId, channelCode);
    Instant now = Instant.parse("2026-10-27T00:00:00Z");
    Instant previousProbe = now.minusSeconds(2);
    Instant previousSuccess = now.minusSeconds(30);
    Instant expiredBackoff = now.minusSeconds(1);
    Instant hold = now.plusSeconds(60);
    DispatchChannelHealthSnapshot unhealthy = new DispatchChannelHealthSnapshot(
        tenantId,
        channelCode,
        "API",
        "UNHEALTHY",
        5,
        previousProbe,
        previousSuccess,
        previousProbe,
        expiredBackoff,
        "probe failed",
        "probe evidence");

    healthRepository.upsertHealth(unhealthy);

    assertThat(healthRepository.findHealth(tenantId, channelCode)).isEqualTo(unhealthy);
    assertThat(healthRepository.tryClaimHalfOpenProbe("other-tenant", channelCode, now, hold))
        .isFalse();
    assertThat(healthRepository.tryClaimHalfOpenProbe(tenantId, channelCode, now, hold))
        .isTrue();
    assertThat(healthRepository.tryClaimHalfOpenProbe(tenantId, channelCode, now, hold))
        .isFalse();
    DispatchChannelHealthSnapshot claimed = healthRepository.findHealth(tenantId, channelCode);
    assertThat(claimed.nextProbeAt()).isEqualTo(hold);
    assertThat(claimed.consecutiveFailures()).isEqualTo(5);
    assertThat(claimed.lastFailureAt()).isEqualTo(previousProbe);

    DispatchChannelHealthSnapshot healthy = new DispatchChannelHealthSnapshot(
        tenantId, channelCode, "API", "HEALTHY", 0, null, null, null, null, null, null);
    healthRepository.upsertHealth(healthy);

    assertThat(healthRepository.findHealth(tenantId, channelCode)).isEqualTo(healthy);
    assertThat(healthRepository.tryClaimHalfOpenProbe(tenantId, channelCode, now, hold))
        .isFalse();
  }

  @Test
  @DisplayName("健康状态只能引用同租户通道配置，物理删除配置时清理派生状态")
  void shouldEnforceConfiguredTenantReferenceAndCascadePhysicalDelete() {
    JdbcTemplate platform = platformJdbcTemplate();
    String code = "fk-health-" + UUID.randomUUID();
    insertChannelConfig(platform, "fk-health-owner", code);
    insertChannelConfig(platform, "fk-health-other", code);

    insertHealth(platform, "fk-health-owner", code);

    assertThatThrownBy(() -> insertHealth(platform, "fk-health-missing", code))
        .rootCause()
        .satisfies(cause -> assertThat(((SQLException) cause).getSQLState()).isEqualTo("23503"));
    assertThatThrownBy(() -> insertHealth(platform, "fk-health-owner", "missing-channel"))
        .rootCause()
        .satisfies(cause -> assertThat(((SQLException) cause).getSQLState()).isEqualTo("23503"));
    final String selectBatchFileChannelHealthSql = """
        select count(*) from batch.file_channel_health where tenant_id = ? and channel_code = ?
    """;
    Integer selectedBatchFileChannelHealth = platform.queryForObject(
        selectBatchFileChannelHealthSql, Integer.class, "fk-health-owner", code);
    assertThat(selectedBatchFileChannelHealth).isEqualTo(1);

    final String deleteBatchFileChannelConfigSql = """
        delete from batch.file_channel_config where tenant_id = ? and channel_code = ?
    """;
    platform.update(deleteBatchFileChannelConfigSql, "fk-health-owner", code);

    final String selectBatchFileChannelHealthSqlValue1 = """
        select count(*) from batch.file_channel_health where tenant_id = ? and channel_code = ?
    """;
    Integer selectedBatchFileChannelHealth2 = platform.queryForObject(
        selectBatchFileChannelHealthSqlValue1, Integer.class, "fk-health-owner", code);
    assertThat(selectedBatchFileChannelHealth2).isZero();
    final String selectBatchFileChannelConfigSql = """
        select count(*) from batch.file_channel_config where tenant_id = ? and channel_code = ?
    """;
    Integer selectedBatchFileChannelConfig = platform.queryForObject(
        selectBatchFileChannelConfigSql, Integer.class, "fk-health-other", code);
    assertThat(selectedBatchFileChannelConfig).isEqualTo(1);
  }

  @Test
  @DisplayName("停用和软删除不再影响启动健康审计，原故障历史仍保留")
  void shouldAuditOnlyEnabledChannels_whenFaultFixtureIsDisabled() throws Exception {
    JdbcTemplate platform = new JdbcTemplate(new DriverManagerDataSource(
        platformJdbcUrl(), platformJdbcUsername(), platformJdbcPassword()));
    long unhealthyBefore = healthRepository.countByHealthStatus("UNHEALTHY");
    long overdueBefore = healthRepository.countProbeOverdue(Instant.now());
    for (String tenant : List.of("tb", "unrelated-tenant")) {
      final String insertBatchFileChannelConfigSql = """
          insert into batch.file_channel_config (tenant_id, channel_code, channel_name, channel_type,
            target_endpoint, auth_type, config_json, receipt_policy, timeout_seconds, enabled, is_deleted)
          values (?, 'tb_api_fail', 'TB API fail channel [SIM:dispatch-stage5b]', 'API',
            'http://localhost:11080/tb/fail', 'NONE', '{}', 'SYNC', 30, true, false)
          """;
      platform.update(insertBatchFileChannelConfigSql, tenant);
      for (int failure = 0; failure < 5; failure++) {
        healthService.recordDispatchOutcome(
            channelConfig(tenant, "tb_api_fail", "API"), false, "expected fault", null);
      }
      final String updateBatchFileChannelHealthSql = """
          update batch.file_channel_health set next_probe_at = now() - interval '1 minute' where tenant_id = ?
      """;
      platform.update(updateBatchFileChannelHealthSql, tenant);
    }
    assertThat(healthRepository.countByHealthStatus("UNHEALTHY")).isEqualTo(unhealthyBefore + 2);
    assertThat(healthRepository.countProbeOverdue(Instant.now())).isEqualTo(overdueBefore + 2);

    Path cleanupSql =
        Path.of("..", "..", "scripts", "local", "sql", "disable-stage5b-fault-channel.sql");
    String selectStatementSql = Files.readString(cleanupSql);
    platform.execute(selectStatementSql);
    String selectStatementSql2 = Files.readString(cleanupSql);
    platform.execute(selectStatementSql2);

    assertThat(healthRepository.countByHealthStatus("UNHEALTHY")).isEqualTo(unhealthyBefore + 1);
    assertThat(healthRepository.countProbeOverdue(Instant.now())).isEqualTo(overdueBefore + 1);
    assertThat(healthRepository.findHealth("tb", "tb_api_fail").healthStatus())
        .isEqualTo("UNHEALTHY");
    assertThat(healthRepository.findHealth("tb", "tb_api_fail").consecutiveFailures())
        .isEqualTo(5);
    final String selectBatchFileChannelConfigSqlValue2 = """
        select enabled from batch.file_channel_config where tenant_id = 'unrelated-tenant'\s\
        and channel_code = 'tb_api_fail'
    """;
    Boolean selectedBatchFileChannelConfig =
        platform.queryForObject(selectBatchFileChannelConfigSqlValue2, Boolean.class);
    assertThat(selectedBatchFileChannelConfig).isTrue();
    final String updateBatchFileChannelConfigSql = """
        update batch.file_channel_config set is_deleted = true where tenant_id = 'unrelated-tenant'\s\
        and channel_code = 'tb_api_fail'
    """;
    platform.update(updateBatchFileChannelConfigSql);
    assertThat(healthRepository.countByHealthStatus("UNHEALTHY")).isEqualTo(unhealthyBefore);
    assertThat(healthRepository.countProbeOverdue(Instant.now())).isEqualTo(overdueBefore);
  }

  // --- helpers ---

  private static Map<String, Object> channelConfig(
      String tenantId, String channelCode, String channelType) {
    insertChannelConfig(platformJdbcTemplate(), tenantId, channelCode);
    return Map.of(
        "tenant_id", tenantId,
        "channel_code", channelCode,
        "channel_type", channelType);
  }

  private static JdbcTemplate platformJdbcTemplate() {
    return new JdbcTemplate(new DriverManagerDataSource(
        platformJdbcUrl(), platformJdbcUsername(), platformJdbcPassword()));
  }

  private static void insertChannelConfig(JdbcTemplate platform, String tenantId, String code) {
    final String insertBatchFileChannelConfigSqlValue3 = """
        insert into batch.file_channel_config (
          tenant_id, channel_code, channel_name, channel_type, target_endpoint, auth_type,
          config_json, receipt_policy, timeout_seconds, enabled, is_deleted)
        values (?, ?, 'FK health fixture', 'API', 'http://localhost/test', 'NONE', '{}', 'NONE', 30, true,\s\
        false)
        on conflict (tenant_id, channel_code) do nothing
        """;
    platform.update(insertBatchFileChannelConfigSqlValue3, tenantId, code);
  }

  private static void insertHealth(JdbcTemplate platform, String tenantId, String code) {
    final String insertBatchFileChannelHealthSql = """
        insert into batch.file_channel_health (
          tenant_id, channel_code, channel_type, health_status)
        values (?, ?, 'API', 'HEALTHY')
        """;
    platform.update(insertBatchFileChannelHealthSql, tenantId, code);
  }
}
