package io.github.pinpols.batch.worker.processes.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.github.pinpols.batch.testing.OrchestratorWireMockSupport;
import io.github.pinpols.batch.worker.processes.BatchWorkerProcessApplication;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
    classes = BatchWorkerProcessApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("处理 Worker 应用启动集成:上下文装配,以及平台库与业务库物理分离的部署约束")
class BatchWorkerProcessApplicationIntegrationTest extends AbstractIntegrationTest {

  @DynamicPropertySource
  static void orchestratorStub(DynamicPropertyRegistry registry) {
    OrchestratorWireMockSupport.registerOrchestratorBaseUrls(registry);
  }

  private final ApplicationContext applicationContext;
  private final DataSource platformDataSource;
  private final DataSource businessDataSource;

  @Autowired
  BatchWorkerProcessApplicationIntegrationTest(
      ApplicationContext applicationContext,
      @Qualifier("processPlatformDataSource") DataSource platformDataSource,
      @Qualifier("processBusinessDataSource") DataSource businessDataSource) {
    this.applicationContext = applicationContext;
    this.platformDataSource = platformDataSource;
    this.businessDataSource = businessDataSource;
  }

  @Test
  @DisplayName("应用上下文成功启动,容器实例可注入")
  void shouldLoadApplicationContext_whenStarted() {
    assertThat(applicationContext).isNotNull();
  }

  @Test
  @DisplayName("平台库与业务库是两个不同的数据库,且业务库上存在处理暂存表")
  void shouldSeparatePlatformAndBusinessDatabases_whenDataSourcesInspected() {
    JdbcTemplate platform = new JdbcTemplate(platformDataSource);
    JdbcTemplate business = new JdbcTemplate(businessDataSource);

    String platformDatabase = platform.queryForObject("select current_database()", String.class);
    String businessDatabase = business.queryForObject("select current_database()", String.class);

    assertThat(platformDatabase).isNotBlank();
    assertThat(businessDatabase).isNotBlank().isNotEqualTo(platformDatabase);
    assertThat(business.queryForObject("select to_regclass('batch.process_staging')", String.class))
        .isEqualTo("batch.process_staging");
  }
}
