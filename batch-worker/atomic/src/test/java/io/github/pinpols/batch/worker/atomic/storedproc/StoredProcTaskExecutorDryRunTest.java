package io.github.pinpols.batch.worker.atomic.storedproc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.github.pinpols.batch.common.spi.task.TaskContext;
import io.github.pinpols.batch.common.spi.task.TaskResult;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanFactory;

/** ADR-026 §dry-run 守护:dry-run 上下文下 StoredProc executor 必须不开 Callable,不接触 DB。 */
@DisplayName("存储过程执行器 dry-run 短路: 不打开连接")
class StoredProcTaskExecutorDryRunTest {

  private StoredProcExecutorProperties props;
  private DataSource ds;
  private BeanFactory beanFactory;
  private StoredProcTaskExecutor executor;

  @BeforeEach
  void setUp() {
    props = new StoredProcExecutorProperties();
    props.setEnabled(true);
    props.setForbidOsCapableRole(false);
    props.setAllowSecurityDefiner(true);
    ds = mock(DataSource.class);
    beanFactory = mock(BeanFactory.class);
    executor = new StoredProcTaskExecutor(props, beanFactory, ds);
  }

  @Test
  @DisplayName("dry-run 上下文下应返回过程调用计划, 敏感入参取值不外泄且不获取连接")
  void shouldShortCircuit_whenDryRun_andNotOpenConnection() throws Exception {
    // 准备
    TaskContext ctx = new TaskContext(
        "t1",
        "job-1",
        "ti-1",
        "w-1",
        Map.of(
            "procedureName", "batch.refresh_metrics",
            "inParams", List.of("sensitive-arg-1", 42),
            "outParams", List.of("INTEGER", "VARCHAR")),
        Map.of(PipelineRuntimeKeys.DRY_RUN, true));

    // 执行
    TaskResult result = executor.execute(ctx);

    // 断言
    assertThat(result.success()).isTrue();
    assertThat(result.message()).startsWith("dry-run:");
    assertThat(result.output())
        .containsEntry(PipelineRuntimeKeys.DRY_RUN, true)
        .containsEntry("plannedAction", "storedProc")
        .containsEntry("procedureName", "batch.refresh_metrics")
        .containsEntry("inParamCount", 2)
        .containsEntry("outParamTypes", List.of("INTEGER", "VARCHAR"));
    // inParams value 不入 output(可能含业务敏感数据)
    assertThat(result.output().values())
        .noneMatch(v -> String.valueOf(v).contains("sensitive-arg-1"));
    verify(ds, never()).getConnection();
  }
}
