package io.github.pinpols.batch.worker.atomic.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.spi.task.TaskContext;
import io.github.pinpols.batch.common.spi.task.TaskResult;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.github.pinpols.batch.testing.OrchestratorWireMockSupport;
import io.github.pinpols.batch.worker.atomic.BatchWorkerAtomicApplication;
import io.github.pinpols.batch.worker.atomic.sql.SqlExecutorProperties;
import io.github.pinpols.batch.worker.atomic.sql.SqlTaskExecutor;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** SqlTaskExecutor 集成测试:打真实 testcontainers PG,跑真 JDBC SELECT(单测是 mock JDBC,本测验真连接 + 真结果集解析)。 */
@SpringBootTest(
    classes = BatchWorkerAtomicApplication.class,
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
  void truncatesResultSetBeyondMaxResultRows() {
    // maxResultRows 设小值,用真 PG generate_series 触发结果集截断:
    // resultTruncated=true,lastResultRows 仍报真实行数,lastResultSet 行数被截到上限。
    SqlExecutorProperties props = new SqlExecutorProperties();
    props.setEnabled(true);
    props.setForbidOsCapableRole(false);
    props.setMaxResultRows(5);
    SqlTaskExecutor exec = new SqlTaskExecutor(props, beanFactory, dataSource);

    TaskResult r = exec.execute(ctx(Map.of("sql", "SELECT g FROM generate_series(1, 50) g")));

    assertThat(r.success()).isTrue();
    assertThat(r.output()).containsEntry("resultTruncated", true);
    assertThat(r.output()).containsEntry("lastResultRows", 50); // 真实行数全数
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) r.output().get("lastResultSet");
    assertThat(rows).hasSize(5); // 被截到 maxResultRows
  }

  @Test
  void forbidOsCapableRoleRejectsSuperuserConnection() {
    // testcontainers 连接是 superuser(OS 能力角色)→ forbidOsCapableRole=true 时代码层直接拒,连 SELECT 也不放。
    SqlExecutorProperties props = new SqlExecutorProperties();
    props.setEnabled(true);
    props.setForbidOsCapableRole(true);
    TaskResult r =
        new SqlTaskExecutor(props, beanFactory, dataSource).execute(ctx(Map.of("sql", "SELECT 1")));
    assertThat(r.success()).isFalse();
  }
}
