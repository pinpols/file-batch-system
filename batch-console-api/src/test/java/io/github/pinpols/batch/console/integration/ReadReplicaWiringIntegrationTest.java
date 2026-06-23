package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * P2-4 smoke IT：覆盖 application-test.yml 默认的 read-replica=false，验证生产默认（true）路径在 完整 Spring 上下文里能正常
 * wire。replica URL 指向不可达端口触发 fail-open，断言 readOnly 查询 仍然成功（降级到主库）且 {@code
 * batch.console.replica.failover.count} 计数器递增。
 *
 * <p>本类是「prod 默认行为最小覆盖」用：unit 层 {@code ReadReplicaRoutingDataSourceTest} 已用 mock DataSource
 * 覆盖九条核心逻辑，本 IT 不重复，只验 Spring 装配 + 计数器对接是真实可用。
 */
@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestPropertySource(
    properties = {
      "batch.console.read-replica.enabled=true",
      "batch.console.read-replica.replica.url=jdbc:postgresql://localhost:1/replica_unreachable",
      "batch.console.read-replica.replica.username=batch_user",
      "batch.console.read-replica.replica.password=batch_pass_123",
      "batch.console.read-replica.replica.minimum-idle=0",
      "batch.console.read-replica.replica.maximum-pool-size=2",
      "batch.console.read-replica.replica.connection-timeout-millis=1000",
      "batch.console.read-replica.replica.validation-timeout-millis=500",
      "batch.console.read-replica.failure-threshold=1",
      "batch.console.read-replica.quarantine-seconds=1"
    })
class ReadReplicaWiringIntegrationTest extends AbstractIntegrationTest {

  @Autowired private DataSource dataSource;
  @Autowired private MeterRegistry meterRegistry;
  @Autowired private PlatformTransactionManager transactionManager;

  @DynamicPropertySource
  static void registerReadReplicaPrimary(DynamicPropertyRegistry registry) {
    registry.add(
        "batch.console.read-replica.primary.url", AbstractIntegrationTest::platformJdbcUrl);
    registry.add("batch.console.read-replica.primary.username", () -> "batch_user");
    registry.add("batch.console.read-replica.primary.password", () -> "batch_pass_123");
    registry.add("batch.console.read-replica.primary.minimum-idle", () -> "0");
    registry.add("batch.console.read-replica.primary.maximum-pool-size", () -> "4");
    registry.add("batch.console.read-replica.primary.connection-timeout-millis", () -> "5000");
  }

  @Test
  void wiringInjectsRoutingDataSourceAndFailOpenOnReplicaUnreachable() {
    assertThat(dataSource)
        .as(
            "read-replica.enabled=true 应让 @Primary DataSource 走 LazyConnectionDataSourceProxy 包裹的"
                + " routing DS")
        .isInstanceOf(LazyConnectionDataSourceProxy.class);

    TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
    readOnly.setReadOnly(true);
    Integer one =
        readOnly.execute(
            status -> new JdbcTemplate(dataSource).queryForObject("SELECT 1", Integer.class));

    assertThat(one).as("readOnly 查询路由到不可达 replica 后应 fail-open 落到 primary，结果仍然正确").isEqualTo(1);

    Counter failover = meterRegistry.find("batch.console.replica.failover.count").counter();
    assertThat(failover).as("fail-open 时 batch.console.replica.failover.count 计数器应已注册").isNotNull();
    assertThat(failover.count()).as("至少观察到一次降级").isGreaterThanOrEqualTo(1d);
  }
}
