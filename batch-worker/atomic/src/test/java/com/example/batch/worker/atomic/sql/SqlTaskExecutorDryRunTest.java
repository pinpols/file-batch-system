package com.example.batch.worker.atomic.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.example.batch.common.spi.task.TaskContext;
import com.example.batch.common.spi.task.TaskResult;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanFactory;

/** ADR-026 §dry-run 守护:dry-run 上下文下 SQL executor 必须不发任何 SQL,直接回 success + plannedAction 摘要。 */
class SqlTaskExecutorDryRunTest {

  private SqlExecutorProperties props;
  private DataSource ds;
  private BeanFactory beanFactory;
  private SqlTaskExecutor executor;

  @BeforeEach
  void setUp() {
    props = new SqlExecutorProperties();
    props.setEnabled(true);
    props.setForbidOsCapableRole(false);
    ds = mock(DataSource.class);
    beanFactory = mock(BeanFactory.class);
    executor = new SqlTaskExecutor(props, beanFactory, ds);
  }

  @Test
  void shouldShortCircuit_whenDryRunFromRuntimeAttributes() throws Exception {
    // 准备
    TaskContext ctx =
        new TaskContext(
            "t1",
            "job-1",
            "ti-1",
            "w-1",
            Map.of("sql", "UPDATE t SET x=1; DELETE FROM t WHERE y=2;"),
            Map.of("dryRun", true));

    // 执行
    TaskResult result = executor.execute(ctx);

    // 断言
    assertThat(result.success()).isTrue();
    assertThat(result.message()).startsWith("dry-run:");
    assertThat(result.output())
        .containsEntry("dryRun", true)
        .containsEntry("plannedAction", "sql")
        .containsEntry("statementCount", 2)
        .containsKey("statements")
        .containsKey("dataSourceBean")
        .containsKey("statementTimeoutSeconds");
    // 关键:dataSource 没被打开过
    verify(ds, never()).getConnection();
  }

  @Test
  void shouldShortCircuit_whenDryRunFromParametersFallback() throws Exception {
    // 旧调用方可能把 dryRun 塞到 parameters
    TaskContext ctx =
        new TaskContext(
            "t1", "job-1", "ti-1", "w-1", Map.of("sql", "SELECT 1;", "dryRun", true), Map.of());

    TaskResult result = executor.execute(ctx);

    assertThat(result.success()).isTrue();
    assertThat(result.output()).containsEntry("dryRun", true);
    verify(ds, never()).getConnection();
  }
}
