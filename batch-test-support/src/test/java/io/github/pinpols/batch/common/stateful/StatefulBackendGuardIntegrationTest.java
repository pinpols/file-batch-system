package io.github.pinpols.batch.common.stateful;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.pinpols.batch.testing.TestPostgresContainers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DisplayName("真实 PostgreSQL 上的有状态后端身份守卫集成测试: 验证首次部署记录基线, 后端或位置变更需一次性切换凭证, 切换代次与切换历史持久化")
class StatefulBackendGuardIntegrationTest {

  private static PostgreSQLContainer postgres;
  private static HikariDataSource dataSource;
  private static JdbcTemplate jdbc;
  private StatefulBackendGuard guard;

  @SuppressWarnings("resource")
  @BeforeAll
  static void startPostgres() {
    postgres = TestPostgresContainers.platform();
    postgres.start();
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(postgres.getJdbcUrl());
    config.setUsername(postgres.getUsername());
    config.setPassword(postgres.getPassword());
    config.setMaximumPoolSize(3);
    dataSource = new HikariDataSource(config);
    jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("CREATE SCHEMA batch");
    jdbc.execute("""
        CREATE TABLE batch.stateful_backend_binding (
          feature_key VARCHAR(160) PRIMARY KEY,
          backend VARCHAR(64) NOT NULL,
          backend_identity VARCHAR(1024) NOT NULL,
          generation BIGINT NOT NULL DEFAULT 0,
          last_cutover_id VARCHAR(160),
          updated_by VARCHAR(160) NOT NULL,
          updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
        )
        """);
    jdbc.execute("""
        CREATE TABLE batch.stateful_backend_cutover_history (
          id BIGSERIAL PRIMARY KEY,
          feature_key VARCHAR(160) NOT NULL,
          generation BIGINT NOT NULL,
          action VARCHAR(32) NOT NULL,
          previous_backend VARCHAR(64),
          previous_backend_identity VARCHAR(1024),
          target_backend VARCHAR(64) NOT NULL,
          target_backend_identity VARCHAR(1024) NOT NULL,
          cutover_id VARCHAR(160),
          changed_by VARCHAR(160) NOT NULL,
          changed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
        )
        """);
    jdbc.execute("""
        CREATE UNIQUE INDEX uk_stateful_backend_cutover_token
          ON batch.stateful_backend_cutover_history(feature_key, cutover_id)
          WHERE cutover_id IS NOT NULL
        """);
  }

  @AfterAll
  static void stopPostgres() {
    if (dataSource != null) {
      dataSource.close();
    }
    if (postgres != null) {
      postgres.stop();
    }
  }

  @BeforeEach
  void reset() {
    jdbc.execute("TRUNCATE batch.stateful_backend_cutover_history, batch.stateful_backend_binding");
    guard = new StatefulBackendGuard(dataSource);
  }

  @Test
  @DisplayName("首次校验记录后端身份基线, 同一后端再次校验返回已验证, 且切换历史仅落一条基线记录")
  void recordsBaseline_thenVerifiesSameBackend() {
    StatefulBackendGuard.DesiredBackend redis =
        desired("redis", "host=valkey|port=6379|db=0", null);

    assertThat(guard.verify(redis).action())
        .isEqualTo(StatefulBackendGuard.GuardAction.BASELINE_RECORDED);
    assertThat(guard.verify(redis).action()).isEqualTo(StatefulBackendGuard.GuardAction.VERIFIED);
    assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM batch.stateful_backend_cutover_history", Long.class))
        .isEqualTo(1L);
  }

  @Test
  @DisplayName("已登记后端后未携带一次性切换凭证提交不同后端时被拒绝, 并提示需要新的切换凭证")
  void rejectsBackendChange_withoutCutoverId() {
    guard.verify(desired("redis", "host=valkey|port=6379|db=0", null));

    assertThatThrownBy(
            () -> guard.verify(desired("database", "jdbc=jdbc:postgresql://db/batch", null)))
        .isInstanceOf(StatefulBackendSwitchRejectedException.class)
        .hasMessageContaining("one-time cutover-id");
  }

  @Test
  @DisplayName("携带尚未使用的一次性切换凭证时记录切换并令代次递增为 1, 其他节点随后以同一后端校验返回已验证")
  void recordsExplicitCutover_thenOtherNodesVerify() {
    guard.verify(desired("redis", "host=valkey|port=6379|db=0", null));
    StatefulBackendGuard.DesiredBackend database =
        desired("database", "jdbc=jdbc:postgresql://db/batch", "quota-20260723-01");

    StatefulBackendGuard.GuardResult cutover = guard.verify(database);

    assertThat(cutover.action()).isEqualTo(StatefulBackendGuard.GuardAction.CUTOVER_RECORDED);
    assertThat(cutover.generation()).isEqualTo(1L);
    assertThat(guard.verify(database).action())
        .isEqualTo(StatefulBackendGuard.GuardAction.VERIFIED);
  }

  @Test
  @DisplayName("回滚到先前后端时复用已消耗的一次性切换凭证被拒绝, 并提示该凭证已被使用")
  void rejectsReusedToken_forRollback() {
    guard.verify(desired("redis", "host=valkey|port=6379|db=0", null));
    guard.verify(
        desired("database", "jdbc=jdbc:postgresql://db/batch", "quota-20260723-rollback-test"));

    assertThatThrownBy(() -> guard.verify(
            desired("redis", "host=valkey|port=6379|db=0", "quota-20260723-rollback-test")))
        .isInstanceOf(StatefulBackendSwitchRejectedException.class)
        .hasMessageContaining("already used");
  }

  @Test
  @DisplayName("同一存储后端仅访问位置变化时同样判定为有状态切换, 未携带切换凭证的校验被拒绝")
  void treatsLocationChange_asStatefulCutover() {
    guard.verify(desired("s3", "endpoint=minio-a|bucket=batch", null));

    assertThatThrownBy(() -> guard.verify(desired("s3", "endpoint=minio-b|bucket=batch", null)))
        .isInstanceOf(StatefulBackendSwitchRejectedException.class);
  }

  @Test
  @DisplayName("从禁用态启用、更换存储后端以及再次禁用各自都需要新的一次性切换凭证, 仅携带新凭证时才记录切换")
  void requiresFreshToken_forEachEnableStorageChangeAndDisable() {
    StatefulBackendGuard.DesiredBackend disabled = desired("disabled", "disabled", null);
    guard.verify(disabled);

    assertThatThrownBy(() -> guard.verify(desired("platform_pg", "jdbc=platform", null)))
        .isInstanceOf(StatefulBackendSwitchRejectedException.class);
    guard.verify(desired("platform_pg", "jdbc=platform", "outbox-enable-01"));

    assertThatThrownBy(() -> guard.verify(desired("sqlite", "path=/data/outbox.db", null)))
        .isInstanceOf(StatefulBackendSwitchRejectedException.class);
    guard.verify(desired("sqlite", "path=/data/outbox.db", "outbox-pg-to-sqlite-01"));

    assertThatThrownBy(() -> guard.verify(desired("disabled", "disabled", null)))
        .isInstanceOf(StatefulBackendSwitchRejectedException.class);
    assertThat(
            guard.verify(desired("disabled", "disabled", "outbox-disable-01")).action())
        .isEqualTo(StatefulBackendGuard.GuardAction.CUTOVER_RECORDED);
  }

  private StatefulBackendGuard.DesiredBackend desired(
      String backend, String identity, String cutoverId) {
    return new StatefulBackendGuard.DesiredBackend(
        "quota-runtime-state", backend, identity, cutoverId, "integration-test");
  }
}
