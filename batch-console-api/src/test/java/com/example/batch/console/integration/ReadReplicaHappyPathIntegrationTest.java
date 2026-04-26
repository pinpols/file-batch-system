package com.example.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.console.BatchConsoleApiApplication;
import com.example.batch.console.config.RoutingHints;
import com.example.batch.testing.AbstractIntegrationTest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * P2-4 happy-path IT：起独立第二个 PG 容器扮演 replica，验证 prod 默认开启 {@code read-replica.enabled=true} 下：
 *
 * <ul>
 *   <li>readOnly 事务实际命中 replica（用 {@code current_database()} 区分两个独立 PG）
 *   <li>非 readOnly 事务命中 primary
 *   <li>{@link RoutingHints#forcePrimary} （即 {@code @RouteToPrimary} 注解的底层机制） 在 readOnly 事务里仍能强制走
 *       primary
 *   <li>replica 不可达 → quarantine → replica 恢复 → quarantine 期满后自动回到 replica
 * </ul>
 *
 * <p><b>覆盖范围声明</b>：第二个 PG 是独立实例（schema 一致，但<b>没有 streaming replication</b>）， 仅验证路由 / 连接池 / 计数器 /
 * quarantine 时序行为。streaming standby 的 WAL 回放冲突、 pg_basebackup 恢复、主从延迟下的强一致语义不在本 IT 覆盖范围（详见 {@code
 * docs/runbook/read-replica.md} 的手工验收步骤）。
 *
 * <p>故障入场（fail-open 单点失败计数）由姐妹 IT {@link ReadReplicaWiringIntegrationTest} 用不可达 URL 覆盖。
 */
@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestPropertySource(
    properties = {
      "batch.console.read-replica.enabled=true",
      "batch.console.read-replica.failure-threshold=2",
      "batch.console.read-replica.quarantine-seconds=2"
    })
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReadReplicaHappyPathIntegrationTest extends AbstractIntegrationTest {

  private static final String REPLICA_DB_NAME = "batch_replica";

  @SuppressWarnings("resource")
  private static final PostgreSQLContainer<?> REPLICA_PG =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"))
          .withDatabaseName(REPLICA_DB_NAME)
          .withUsername("batch_user")
          .withPassword("batch_pass_123")
          .withUrlParam("sslmode", "disable")
          // socketTimeout：query 在已建立连接上读响应的最大时长（秒）。Hikari 的 connectionTimeout
          // 只覆盖"从池里借连接"，不覆盖"连接已借出后查询读响应"。replica 容器被 pause 后，
          // 在已校验过的存量连接上跑 query 会挂死在 TCP read，需要 driver 层的 socketTimeout 兜底。
          .withUrlParam("socketTimeout", "2")
          .withUrlParam("loginTimeout", "2")
          .withInitScript("db/platform-init.sql");

  @BeforeAll
  static void startReplica() {
    REPLICA_PG.start();
  }

  @AfterAll
  static void stopReplica() {
    REPLICA_PG.stop();
  }

  @DynamicPropertySource
  static void registerReadReplicaProperties(DynamicPropertyRegistry registry) {
    registry.add(
        "batch.console.read-replica.primary.url", AbstractIntegrationTest::platformJdbcUrl);
    registry.add("batch.console.read-replica.primary.username", () -> "batch_user");
    registry.add("batch.console.read-replica.primary.password", () -> "batch_pass_123");
    registry.add("batch.console.read-replica.primary.minimum-idle", () -> "0");
    registry.add("batch.console.read-replica.primary.maximum-pool-size", () -> "4");

    registry.add("batch.console.read-replica.replica.url", REPLICA_PG::getJdbcUrl);
    registry.add("batch.console.read-replica.replica.username", REPLICA_PG::getUsername);
    registry.add("batch.console.read-replica.replica.password", REPLICA_PG::getPassword);
    registry.add("batch.console.read-replica.replica.minimum-idle", () -> "0");
    registry.add("batch.console.read-replica.replica.maximum-pool-size", () -> "4");
    // 1.5s 连接超时：replica 暂停后 fail-open 触发足够快，避免单测耗时拖长
    registry.add("batch.console.read-replica.replica.connection-timeout-millis", () -> "1500");
    registry.add("batch.console.read-replica.replica.validation-timeout-millis", () -> "1000");
  }

  @Autowired private DataSource dataSource;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private MeterRegistry meterRegistry;

  @Test
  @Order(1)
  void wiringInjectsRoutingDataSource() {
    assertThat(dataSource)
        .as("read-replica.enabled=true 应让 @Primary DataSource 走 routing DS")
        .isInstanceOf(LazyConnectionDataSourceProxy.class);
  }

  @Test
  @Order(2)
  void readOnlyTransactionRoutesToReplica() {
    assertThat(dbFromTransaction(true))
        .as("readOnly 事务应命中独立 replica 容器")
        .isEqualTo(REPLICA_DB_NAME);
  }

  @Test
  @Order(3)
  void writeTransactionRoutesToPrimary() {
    assertThat(dbFromTransaction(false))
        .as("非 readOnly 事务应命中 primary 容器")
        .isEqualTo("batch_platform");
  }

  @Test
  @Order(4)
  void forcePrimaryHintOverridesReadOnly() {
    String[] db = new String[1];
    RoutingHints.forcePrimary(() -> db[0] = dbFromTransaction(true));
    assertThat(db[0])
        .as("RoutingHints.forcePrimary（@RouteToPrimary 注解的底层机制）应在 readOnly 事务内强制走 primary")
        .isEqualTo("batch_platform");
  }

  @Test
  @Order(5)
  void quarantineRecoversWhenReplicaResumes() throws Exception {
    double failoverBefore = currentFailoverCount();

    // 1. pause replica 容器（cgroup 冻结，端口映射保留），后续 readOnly 查询连接超时 → fail-open
    REPLICA_PG.getDockerClient().pauseContainerCmd(REPLICA_PG.getContainerId()).exec();
    try {
      // 2. 触发 failureThreshold (=2) 次失败，第二次进入 quarantine
      assertThat(dbFromTransaction(true))
          .as("replica 暂停 → 第 1 次 readOnly 应 fail-open 落 primary")
          .isEqualTo("batch_platform");
      assertThat(dbFromTransaction(true))
          .as("replica 暂停 → 第 2 次 readOnly 仍 fail-open 落 primary 并触发 quarantine")
          .isEqualTo("batch_platform");
      assertThat(currentFailoverCount() - failoverBefore)
          .as("两次 readOnly 应观察到至少 2 次降级计数")
          .isGreaterThanOrEqualTo(2d);
    } finally {
      // 3. 恢复 replica
      REPLICA_PG.getDockerClient().unpauseContainerCmd(REPLICA_PG.getContainerId()).exec();
    }

    // 4. quarantine 2s 期满 + 一点缓冲后，下一次 readOnly 应自动回到 replica
    Thread.sleep(2_500L);
    assertThat(dbFromTransaction(true))
        .as("quarantine 期满后 readOnly 应自动重新尝试 replica 并成功")
        .isEqualTo(REPLICA_DB_NAME);
  }

  private String dbFromTransaction(boolean readOnly) {
    TransactionTemplate tx = new TransactionTemplate(transactionManager);
    tx.setReadOnly(readOnly);
    return tx.execute(
        status ->
            new JdbcTemplate(dataSource).queryForObject("SELECT current_database()", String.class));
  }

  private double currentFailoverCount() {
    Counter counter = meterRegistry.find("batch.console.replica.failover.count").counter();
    return counter == null ? 0d : counter.count();
  }
}
