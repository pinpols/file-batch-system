package io.github.pinpols.batch.worker.processes.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.github.pinpols.batch.testing.OrchestratorWireMockSupport;
import io.github.pinpols.batch.worker.core.support.AbstractWorkerLoop;
import io.github.pinpols.batch.worker.processes.BatchWorkerProcessApplication;
import io.github.pinpols.batch.worker.processes.mapper.platform.ProcessInvocationFenceMapper;
import io.github.pinpols.batch.worker.processes.stage.ProcessInvocationFence;
import java.util.List;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
    classes = BatchWorkerProcessApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
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
  @DisplayName("Worker 注册端口与主服务真实绑定端口一致")
  void shouldRegisterActualBoundPort() {
    Integer boundPort =
        applicationContext.getEnvironment().getProperty("local.server.port", Integer.class);
    assertThat(boundPort).isNotNull().isPositive();
    assertThat(
            applicationContext.getBean(AbstractWorkerLoop.class).ensureStarted().getPort())
        .isEqualTo(boundPort);
  }

  @Test
  @DisplayName("应用上下文成功启动,容器实例可注入")
  void shouldLoadApplicationContext_whenStarted() {
    assertThat(applicationContext).isNotNull();
    assertThat(applicationContext.getBean(ProcessInvocationFenceMapper.class)).isNotNull();
    ProcessInvocationFence fence = applicationContext.getBean(ProcessInvocationFence.class);
    assertThat(AopUtils.isAopProxy(fence)).isTrue();
  }

  @Test
  @DisplayName("平台库会加载 PROCESS invocation fence 的 XML 语句")
  void shouldLoadPlatformInvocationFenceXmlStatements() {
    SqlSessionFactory factory =
        applicationContext.getBean("processPlatformSqlSessionFactory", SqlSessionFactory.class);
    String namespace = ProcessInvocationFenceMapper.class.getName() + ".";
    for (String method : List.of(
        "listCleanupTenants",
        "selectTaskInstanceId",
        "selectTaskCurrentStagingGeneration",
        "acquireInstanceFence",
        "selectCurrentInvocationId",
        "rotateCurrentStagingGeneration",
        "selectCurrentStagingGeneration")) {
      var statement = factory.getConfiguration().getMappedStatement(namespace + method);
      assertThat(statement.getResource()).contains("mapper/ProcessInvocationFenceMapper.xml");
    }
  }

  @Test
  @DisplayName("平台库与业务库是两个不同的数据库,且业务库上存在处理暂存表")
  void shouldSeparatePlatformAndBusinessDatabases_whenDataSourcesInspected() {
    JdbcTemplate platform = new JdbcTemplate(platformDataSource);
    JdbcTemplate business = new JdbcTemplate(businessDataSource);

    final String selectSql = """
        select current_database()
    """;
    String platformDatabase = platform.queryForObject(selectSql, String.class);
    final String selectSqlValue1 = """
        select current_database()
    """;
    String businessDatabase = business.queryForObject(selectSqlValue1, String.class);

    assertThat(platformDatabase).isNotBlank();
    assertThat(businessDatabase).isNotBlank().isNotEqualTo(platformDatabase);
    final String selectSqlValue2 = """
        select to_regclass('batch.process_staging')
    """;
    String selected = business.queryForObject(selectSqlValue2, String.class);
    assertThat(selected).isEqualTo("batch.process_staging");
  }
}
