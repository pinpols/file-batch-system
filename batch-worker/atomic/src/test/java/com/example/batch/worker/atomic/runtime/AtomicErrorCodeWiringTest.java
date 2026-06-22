package com.example.batch.worker.atomic.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.example.batch.common.spi.task.TaskContext;
import com.example.batch.common.spi.task.TaskResult;
import com.example.batch.worker.atomic.http.HttpExecutorProperties;
import com.example.batch.worker.atomic.http.HttpTaskExecutor;
import com.example.batch.worker.atomic.shell.ShellExecutorProperties;
import com.example.batch.worker.atomic.shell.ShellTaskExecutor;
import com.example.batch.worker.atomic.sql.SqlExecutorProperties;
import com.example.batch.worker.atomic.sql.SqlTaskExecutor;
import com.example.batch.worker.atomic.storedproc.StoredProcExecutorProperties;
import com.example.batch.worker.atomic.storedproc.StoredProcTaskExecutor;
import java.nio.file.Path;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.BeanFactory;

/**
 * K3:验证 4 个 executor 失败路径都会在 {@link TaskResult#output()} 填 {@code error_code}。 各 executor
 * 选一条最便捷的失败路径 — Shell:缺 command(CONFIG_INVALID);Sql:缺 sql(CONFIG_INVALID);StoredProc:缺
 * procedureName(CONFIG_INVALID); Http:缺 url(CONFIG_INVALID)。覆盖"失败必填 code"的契约即可。
 */
class AtomicErrorCodeWiringTest {

  @TempDir Path tempDir;

  private TaskContext ctx(Map<String, Object> params) {
    return new TaskContext("t1", "j1", "ti1", "w1", params, Map.of());
  }

  @Test
  void shellFailureShouldFillErrorCode() {
    ShellExecutorProperties props = new ShellExecutorProperties();
    props.setEnabled(true);
    props.setWorkdirBase(tempDir);
    ShellTaskExecutor executor = new ShellTaskExecutor(props);

    TaskResult r = executor.execute(ctx(Map.of()));

    assertThat(r.success()).isFalse();
    assertThat(r.output()).containsEntry(AtomicErrorCode.OUTPUT_KEY, "CONFIG_INVALID");
  }

  @Test
  void shellSensitiveCredentialShouldFillSecurityRejected() {
    ShellExecutorProperties props = new ShellExecutorProperties();
    props.setEnabled(true);
    props.setWorkdirBase(tempDir);
    ShellTaskExecutor executor = new ShellTaskExecutor(props);

    TaskResult r = executor.execute(ctx(Map.of("command", "/bin/echo", "password", "leak")));

    assertThat(r.success()).isFalse();
    assertThat(r.output()).containsEntry(AtomicErrorCode.OUTPUT_KEY, "SECURITY_REJECTED");
  }

  @Test
  void sqlMissingParamShouldFillErrorCode() {
    SqlExecutorProperties props = new SqlExecutorProperties();
    props.setForbidOsCapableRole(false);
    SqlTaskExecutor executor =
        new SqlTaskExecutor(props, mock(BeanFactory.class), mock(DataSource.class));

    TaskResult r = executor.execute(ctx(Map.of()));

    assertThat(r.success()).isFalse();
    assertThat(r.output()).containsEntry(AtomicErrorCode.OUTPUT_KEY, "CONFIG_INVALID");
  }

  @Test
  void storedProcMissingParamShouldFillErrorCode() {
    StoredProcExecutorProperties props = new StoredProcExecutorProperties();
    StoredProcTaskExecutor executor =
        new StoredProcTaskExecutor(props, mock(BeanFactory.class), mock(DataSource.class));

    TaskResult r = executor.execute(ctx(Map.of()));

    assertThat(r.success()).isFalse();
    assertThat(r.output()).containsEntry(AtomicErrorCode.OUTPUT_KEY, "CONFIG_INVALID");
  }

  @Test
  void httpMissingUrlShouldFillErrorCode() {
    HttpExecutorProperties props = new HttpExecutorProperties();
    HttpTaskExecutor executor = new HttpTaskExecutor(props);

    TaskResult r = executor.execute(ctx(Map.of()));

    assertThat(r.success()).isFalse();
    assertThat(r.output()).containsEntry(AtomicErrorCode.OUTPUT_KEY, "CONFIG_INVALID");
  }

  @Test
  void httpBlockedHostShouldFillSecurityRejected() {
    HttpExecutorProperties props = new HttpExecutorProperties();
    // 默认 blockedHostPatterns 含 localhost / 169.254.169.254
    HttpTaskExecutor executor = new HttpTaskExecutor(props);

    TaskResult r = executor.execute(ctx(Map.of("url", "http://localhost/test")));

    assertThat(r.success()).isFalse();
    assertThat(r.output()).containsEntry(AtomicErrorCode.OUTPUT_KEY, "SECURITY_REJECTED");
  }
}
