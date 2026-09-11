package io.github.pinpols.batch.worker.processes.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.github.pinpols.batch.testing.OrchestratorWireMockSupport;
import io.github.pinpols.batch.worker.processes.BatchWorkerProcessApplication;
import javax.sql.DataSource;
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
  void contextLoads() {
    assertThat(applicationContext).isNotNull();
  }

  @Test
  void platformAndBusinessDataSourcesArePhysicallySeparated() {
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
