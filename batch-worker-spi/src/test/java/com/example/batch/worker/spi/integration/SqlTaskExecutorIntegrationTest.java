package com.example.batch.worker.spi.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.spi.task.TaskContext;
import com.example.batch.common.spi.task.TaskResult;
import com.example.batch.testing.AbstractIntegrationTest;
import com.example.batch.testing.OrchestratorWireMockSupport;
import com.example.batch.worker.spi.BatchWorkerSpiApplication;
import com.example.batch.worker.spi.sql.SqlExecutorProperties;
import com.example.batch.worker.spi.sql.SqlTaskExecutor;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** SqlTaskExecutor 集成测试:打真实 testcontainers PG,跑真 JDBC SELECT(单测是 mock JDBC,本测验真连接 + 真结果集解析)。 */
@SpringBootTest(
    classes = BatchWorkerSpiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SqlTaskExecutorIntegrationTest extends AbstractIntegrationTest {

  @DynamicPropertySource
  static void orchestratorStub(DynamicPropertyRegistry registry) {
    OrchestratorWireMockSupport.registerOrchestratorBaseUrls(registry);
  }

  @Autowired DataSource dataSource;
  @Autowired BeanFactory beanFactory;

  private SqlTaskExecutor executor() {
    SqlExecutorProperties props = new SqlExecutorProperties();
    props.setEnabled(true);
    props.setForbidOsCapableRole(false); // testcontainers superuser;角色闸拒绝路径单列 IT 验
    props.setAllowedStatementTypes(Set.of("SELECT"));
    return new SqlTaskExecutor(props, beanFactory, dataSource);
  }

  private TaskContext ctx(Map<String, Object> params) {
    return new TaskContext("t1", "sql-job", "ti-1", "spi-node-1", params, Map.of());
  }

  @Test
  void runsRealSelectAgainstPostgres() {
    TaskResult r = executor().execute(ctx(Map.of("sql", "SELECT 42 AS answer")));
    assertThat(r.success()).isTrue();
    // 真结果集:42 应出现在 output
    assertThat(r.output()).isNotNull();
    assertThat(r.output().toString()).contains("42");
  }

  @Test
  void rejectsNonSelectWhenOnlySelectAllowed() {
    // 默认只允许 SELECT;真库连接下 DELETE 仍被 validation 拦在执行前
    TaskResult r =
        executor().execute(ctx(Map.of("sql", "DELETE FROM batch.job_definition WHERE 1=0")));
    assertThat(r.success()).isFalse();
  }

  @Test
  void forbidOsCapableRoleRejectsSuperuserConnection() {
    // testcontainers 连接是 superuser(OS 能力角色)→ forbidOsCapableRole=true 时代码层直接拒,连 SELECT 也不放。
    SqlExecutorProperties props = new SqlExecutorProperties();
    props.setEnabled(true);
    props.setAllowedStatementTypes(Set.of("SELECT"));
    props.setForbidOsCapableRole(true);
    TaskResult r =
        new SqlTaskExecutor(props, beanFactory, dataSource).execute(ctx(Map.of("sql", "SELECT 1")));
    assertThat(r.success()).isFalse();
  }
}
