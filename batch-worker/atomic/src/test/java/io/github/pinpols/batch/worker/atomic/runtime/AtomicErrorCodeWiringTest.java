package io.github.pinpols.batch.worker.atomic.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.pinpols.batch.common.spi.task.TaskContext;
import io.github.pinpols.batch.common.spi.task.TaskResult;
import io.github.pinpols.batch.worker.atomic.http.HttpExecutorProperties;
import io.github.pinpols.batch.worker.atomic.http.HttpTaskExecutor;
import io.github.pinpols.batch.worker.atomic.shell.ShellExecutorProperties;
import io.github.pinpols.batch.worker.atomic.shell.ShellTaskExecutor;
import io.github.pinpols.batch.worker.atomic.sql.SqlExecutorProperties;
import io.github.pinpols.batch.worker.atomic.sql.SqlTaskExecutor;
import io.github.pinpols.batch.worker.atomic.storedproc.StoredProcExecutorProperties;
import io.github.pinpols.batch.worker.atomic.storedproc.StoredProcTaskExecutor;
import java.nio.file.Path;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.BeanFactory;

/**
 * K3:验证 4 个 executor 失败路径都会在 {@link TaskResult#output()} 填 {@code error_code}。 各 executor
 * 选一条最便捷的失败路径 — Shell:缺 command(CONFIG_INVALID);Sql:缺 sql(CONFIG_INVALID);StoredProc:缺
 * procedureName(CONFIG_INVALID); Http:缺 url(CONFIG_INVALID)。覆盖"失败必填 code"的契约即可。
 */
@DisplayName("原子执行器错误码填充: 失败路径的归因契约")
class AtomicErrorCodeWiringTest {

  @TempDir
  Path tempDir;

  private TaskContext ctx(Map<String, Object> params) {
    return new TaskContext("t1", "j1", "ti1", "w1", params, Map.of());
  }

  @Test
  @DisplayName("命令执行器缺少命令时应判失败, 并填充配置无效错误码")
  void shouldFillConfigInvalid_whenShellCommandMissing() {
    ShellExecutorProperties props = new ShellExecutorProperties();
    props.setEnabled(true);
    props.setWorkdirBase(tempDir);
    ShellTaskExecutor executor = new ShellTaskExecutor(props);

    TaskResult r = executor.execute(ctx(Map.of()));

    assertThat(r.success()).isFalse();
    assertThat(r.output()).containsEntry(AtomicErrorCode.OUTPUT_KEY, "CONFIG_INVALID");
  }

  @Test
  @DisplayName("命令执行器参数携带敏感凭据时应判失败, 并填充安全拒绝错误码")
  void shouldFillSecurityRejected_whenShellParamHasCredential() {
    ShellExecutorProperties props = new ShellExecutorProperties();
    props.setEnabled(true);
    props.setWorkdirBase(tempDir);
    ShellTaskExecutor executor = new ShellTaskExecutor(props);

    TaskResult r = executor.execute(ctx(Map.of("command", "/bin/echo", "password", "leak")));

    assertThat(r.success()).isFalse();
    assertThat(r.output()).containsEntry(AtomicErrorCode.OUTPUT_KEY, "SECURITY_REJECTED");
  }

  @Test
  @DisplayName("数据库执行器缺少语句时应判失败, 并填充配置无效错误码")
  void shouldFillConfigInvalid_whenSqlParamMissing() {
    SqlExecutorProperties props = new SqlExecutorProperties();
    props.setForbidOsCapableRole(false);
    SqlTaskExecutor executor =
        new SqlTaskExecutor(props, mock(BeanFactory.class), mock(DataSource.class));

    TaskResult r = executor.execute(ctx(Map.of()));

    assertThat(r.success()).isFalse();
    assertThat(r.output()).containsEntry(AtomicErrorCode.OUTPUT_KEY, "CONFIG_INVALID");
  }

  @Test
  @DisplayName("存储过程执行器缺少过程名时应判失败, 并填充配置无效错误码")
  void shouldFillConfigInvalid_whenProcedureMissing() {
    StoredProcExecutorProperties props = new StoredProcExecutorProperties();
    StoredProcTaskExecutor executor =
        new StoredProcTaskExecutor(props, mock(BeanFactory.class), mock(DataSource.class));

    TaskResult r = executor.execute(ctx(Map.of()));

    assertThat(r.success()).isFalse();
    assertThat(r.output()).containsEntry(AtomicErrorCode.OUTPUT_KEY, "CONFIG_INVALID");
  }

  @Test
  @DisplayName("接口执行器缺少目标地址时应判失败, 并填充配置无效错误码")
  void shouldFillConfigInvalid_whenHttpUrlMissing() {
    HttpExecutorProperties props = new HttpExecutorProperties();
    HttpTaskExecutor executor = new HttpTaskExecutor(props);

    TaskResult r = executor.execute(ctx(Map.of()));

    assertThat(r.success()).isFalse();
    assertThat(r.output()).containsEntry(AtomicErrorCode.OUTPUT_KEY, "CONFIG_INVALID");
  }

  @Test
  @DisplayName("接口执行器命中默认封禁主机时应判失败, 并填充安全拒绝错误码")
  void shouldFillSecurityRejected_whenHttpHostBlocked() {
    HttpExecutorProperties props = new HttpExecutorProperties();
    // 默认 blockedHostPatterns 含 localhost / 169.254.169.254
    HttpTaskExecutor executor = new HttpTaskExecutor(props);

    TaskResult r = executor.execute(ctx(Map.of("url", "http://localhost/test")));

    assertThat(r.success()).isFalse();
    assertThat(r.output()).containsEntry(AtomicErrorCode.OUTPUT_KEY, "SECURITY_REJECTED");
  }
}
