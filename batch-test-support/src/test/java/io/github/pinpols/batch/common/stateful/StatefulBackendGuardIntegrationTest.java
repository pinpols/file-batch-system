package io.github.pinpols.batch.common.stateful;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.pinpols.batch.common.config.RuntimeInfrastructureInspector;
import io.github.pinpols.batch.testing.TestPostgresContainers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DisplayName("真实 PostgreSQL 上的有状态后端身份守卫集成测试: 验证首次部署记录基线, 后端或位置变更需一次性切换凭证, 切换代次与切换历史持久化")
class StatefulBackendGuardIntegrationTest {

  private static final String FEATURE_KEY = "quota-runtime-state";

  private static PostgreSQLContainer postgres;
  private static HikariDataSource dataSource;
  private static JdbcTemplate jdbc;
  private StatefulBackendGuard guard;

  @SuppressWarnings("resource")
  @BeforeAll
  static void startPostgres() {
    final String createSchemaSql = """
        CREATE SCHEMA batch
    """;
    final String createStatefulBackendBindingSql = """
        CREATE TABLE batch.stateful_backend_binding (
          feature_key VARCHAR(160) PRIMARY KEY,
          backend VARCHAR(64) NOT NULL,
          backend_identity VARCHAR(1024) NOT NULL,
          generation BIGINT NOT NULL DEFAULT 0,
          last_cutover_id VARCHAR(160),
          updated_by VARCHAR(160) NOT NULL,
          updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
        )
        """;
    final String createStatefulBackendCutoverHistorySql = """
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
        """;
    final String createUniqueSql = """
        CREATE UNIQUE INDEX uk_stateful_backend_cutover_token
          ON batch.stateful_backend_cutover_history(feature_key, cutover_id)
          WHERE cutover_id IS NOT NULL
        """;

    postgres = TestPostgresContainers.platform();
    postgres.start();
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(postgres.getJdbcUrl());
    config.setUsername(postgres.getUsername());
    config.setPassword(postgres.getPassword());
    config.setMaximumPoolSize(3);
    dataSource = new HikariDataSource(config);
    jdbc = new JdbcTemplate(dataSource);
    jdbc.execute(createSchemaSql);
    jdbc.execute(createStatefulBackendBindingSql);
    jdbc.execute(createStatefulBackendCutoverHistorySql);
    jdbc.execute(createUniqueSql);
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
    final String truncateStatefulBackendCutoverHistorySql = """
        TRUNCATE batch.stateful_backend_cutover_history, batch.stateful_backend_binding
    """;

    jdbc.execute(truncateStatefulBackendCutoverHistorySql);
    guard = new StatefulBackendGuard(dataSource);
  }

  @Test
  @DisplayName("首次校验记录后端身份基线, 同一后端再次校验返回已验证, 且切换历史仅落一条基线记录")
  void recordsBaseline_thenVerifiesSameBackend() {
    final String selectCountSql = """
        SELECT count(*) FROM batch.stateful_backend_cutover_history
    """;

    StatefulBackendGuard.DesiredBackend redis =
        desired("redis", "host=valkey|port=6379|db=0", null);

    assertThat(guard.verify(redis).action())
        .isEqualTo(StatefulBackendGuard.GuardAction.BASELINE_RECORDED);
    assertThat(guard.verify(redis).action()).isEqualTo(StatefulBackendGuard.GuardAction.VERIFIED);
    Long selectedStatefulBackendCutoverHistoryCount =
        jdbc.queryForObject(selectCountSql, Long.class);
    assertThat(selectedStatefulBackendCutoverHistoryCount).isEqualTo(1L);
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

  @ParameterizedTest
  @ValueSource(strings = {"database", "redis", "filesystem", "s3"})
  @DisplayName("仅资源名大小写改变也必须拒绝无凭据切换，原登记和审计保持不变")
  void shouldRejectUnmarkedResourceCaseChange_whenIdentityIsPersisted(String backend) {
    String originalIdentity = caseSensitiveIdentity(backend, "Orders");
    String otherIdentity = caseSensitiveIdentity(backend, "orders");
    guard.verify(desired(backend, originalIdentity, null));

    assertThatThrownBy(() -> guard.verify(desired(backend, otherIdentity, null)))
        .isInstanceOf(StatefulBackendSwitchRejectedException.class);

    String bindingSql = """
        SELECT backend_identity FROM batch.stateful_backend_binding
        WHERE feature_key = ?
        """;
    String registeredIdentity = jdbc.queryForObject(bindingSql, String.class, FEATURE_KEY);
    assertThat(registeredIdentity).isEqualTo(originalIdentity);
    String historySql = """
        SELECT count(*) FROM batch.stateful_backend_cutover_history
        WHERE feature_key = ?
        """;
    Long historyCount = jdbc.queryForObject(historySql, Long.class, FEATURE_KEY);
    assertThat(historyCount).isEqualTo(1L);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "url",
        "sentinel-indexed",
        "cluster-indexed",
        "masterreplica-indexed",
        "connection-details"
      })
  @DisplayName("生效Redis节点改变必须触发持久守卫拒绝，URL和列表配置不能绕过")
  void shouldRejectEffectiveRedisLocationChange_whenBindingIsPersisted(String scenario) {
    redisRunner(scenario, "redis-a").run(first -> {
      String original = first.getBean(RuntimeInfrastructureInspector.class).redisBackendIdentity();
      guard.verify(desired("redis", original, null));
      redisRunner(scenario, "redis-b").run(second -> {
        String target = second.getBean(RuntimeInfrastructureInspector.class).redisBackendIdentity();
        assertThatThrownBy(() -> guard.verify(desired("redis", target, null)))
            .isInstanceOf(StatefulBackendSwitchRejectedException.class);
      });
      String bindingSql = """
        SELECT backend_identity FROM batch.stateful_backend_binding
        WHERE feature_key = ?
        """;
      String registeredIdentity = jdbc.queryForObject(bindingSql, String.class, FEATURE_KEY);
      assertThat(registeredIdentity).isEqualTo(original).doesNotContain("alice", "secret");
      String historySql = """
        SELECT count(*) FROM batch.stateful_backend_cutover_history
        WHERE feature_key = ?
        """;
      Long historyCount = jdbc.queryForObject(historySql, Long.class, FEATURE_KEY);
      assertThat(historyCount).isEqualTo(1L);
    });
  }

  private static ApplicationContextRunner redisRunner(String scenario, String node) {
    ApplicationContextRunner runner = new ApplicationContextRunner()
        .withClassLoader(new FilteredClassLoader("io.lettuce", "redis.clients.jedis"))
        .withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration.class))
        .withUserConfiguration(RuntimeInfrastructureInspector.class);
    return switch (scenario) {
      case "url" ->
        runner.withPropertyValues("spring.data.redis.url=redis://alice:secret@" + node + ":6379/1");
      case "sentinel-indexed" ->
        runner.withPropertyValues(
            "spring.data.redis.sentinel.master=orders",
            "spring.data.redis.sentinel.nodes[0]=" + node + ":26379");
      case "cluster-indexed" ->
        runner.withPropertyValues("spring.data.redis.cluster.nodes[0]=" + node + ":6379");
      case "masterreplica-indexed" ->
        runner.withPropertyValues("spring.data.redis.masterreplica.nodes[0]=" + node + ":6379");
      case "connection-details" ->
        runner.withBean(DataRedisConnectionDetails.class, () -> new DataRedisConnectionDetails() {
          @Override
          public Standalone getStandalone() {
            return Standalone.of(node, 6379, 0);
          }
        });
      default -> throw new IllegalArgumentException(scenario);
    };
  }

  private static String caseSensitiveIdentity(String backend, String resource) {
    return switch (backend) {
      case "database" -> StatefulBackendIdentity.database("jdbc:postgresql://db/" + resource);
      case "redis" -> StatefulBackendIdentity.redis("ignored", 6379, 0, resource, "redis:26379");
      case "filesystem" -> StatefulBackendIdentity.filesystem("./target/store", resource);
      case "s3" -> StatefulBackendIdentity.s3("https://store/" + resource, "us-east-1", "batch");
      default -> throw new IllegalArgumentException(backend);
    };
  }

  private StatefulBackendGuard.DesiredBackend desired(
      String backend, String identity, String cutoverId) {
    return new StatefulBackendGuard.DesiredBackend(
        FEATURE_KEY, backend, identity, cutoverId, "integration-test");
  }
}
