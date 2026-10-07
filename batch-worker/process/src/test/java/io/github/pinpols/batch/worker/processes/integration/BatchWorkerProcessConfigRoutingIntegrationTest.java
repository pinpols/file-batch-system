package io.github.pinpols.batch.worker.processes.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.tenant.routing.BusinessRoutingDataSource;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.github.pinpols.batch.testing.OrchestratorWireMockSupport;
import io.github.pinpols.batch.worker.processes.BatchWorkerProcessApplication;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
    classes = BatchWorkerProcessApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("Process Worker 业务分片 CONFIG 模式: 完整应用装配")
class BatchWorkerProcessConfigRoutingIntegrationTest extends AbstractIntegrationTest {

  @DynamicPropertySource
  static void routingProperties(DynamicPropertyRegistry registry) {
    OrchestratorWireMockSupport.registerOrchestratorBaseUrls(registry);
    registerCommonShardProperties(registry);
    registry.add("batch.datasource.business.routing.placement-source", () -> "CONFIG");
  }

  private static void registerCommonShardProperties(DynamicPropertyRegistry registry) {
    registry.add("batch.datasource.business.routing.enabled", () -> true);
    registry.add("batch.datasource.business.routing.pooled-shard-count", () -> 1);
    registry.add("batch.datasource.business.routing.shards[0].key", () -> "shard-0");
    registry.add(
        "batch.datasource.business.routing.shards[0].url",
        AbstractIntegrationTest::businessJdbcUrl);
    registry.add(
        "batch.datasource.business.routing.shards[0].username",
        AbstractIntegrationTest::businessJdbcUsername);
    registry.add(
        "batch.datasource.business.routing.shards[0].password",
        AbstractIntegrationTest::businessJdbcPassword);
  }

  private final DataSource businessDataSource;

  @Autowired
  BatchWorkerProcessConfigRoutingIntegrationTest(
      @Qualifier("processBusinessDataSource") DataSource businessDataSource) {
    this.businessDataSource = businessDataSource;
  }

  @AfterEach
  void clearTenantContext() {
    RlsTenantContextHolder.clear();
  }

  @Test
  @DisplayName("CONFIG 模式启动后按租户取得真实业务库连接")
  void shouldBootWithConfigPlacementAndRouteTenantConnection() {
    assertThat(businessDataSource).isInstanceOf(BusinessRoutingDataSource.class);
    RlsTenantContextHolder.set("t_config_routing");
    assertThat(new JdbcTemplate(businessDataSource)
            .queryForObject("select current_database()", String.class))
        .isNotBlank();
  }
}
