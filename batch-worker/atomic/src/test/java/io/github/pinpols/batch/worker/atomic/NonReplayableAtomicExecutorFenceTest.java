package io.github.pinpols.batch.worker.atomic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import io.github.pinpols.batch.common.enums.TaskErrorCodes;
import io.github.pinpols.batch.common.spi.task.BatchTaskExecutor;
import io.github.pinpols.batch.common.spi.task.BatchTaskExecutorRegistry;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.worker.atomic.http.HttpExecutorProperties;
import io.github.pinpols.batch.worker.atomic.http.HttpTaskExecutor;
import io.github.pinpols.batch.worker.atomic.runtime.DataSourceResolver;
import io.github.pinpols.batch.worker.atomic.shell.ShellExecutorProperties;
import io.github.pinpols.batch.worker.atomic.shell.ShellTaskExecutor;
import io.github.pinpols.batch.worker.atomic.spark.SparkSubmitExecutorProperties;
import io.github.pinpols.batch.worker.atomic.spark.SparkSubmitTaskExecutor;
import io.github.pinpols.batch.worker.atomic.sql.SqlExecutorProperties;
import io.github.pinpols.batch.worker.atomic.sql.SqlTaskExecutor;
import io.github.pinpols.batch.worker.atomic.storedproc.StoredProcExecutorProperties;
import io.github.pinpols.batch.worker.atomic.storedproc.StoredProcTaskExecutor;
import io.github.pinpols.batch.worker.core.domain.StepExecutionRequest;
import io.github.pinpols.batch.worker.core.domain.StepExecutionResponse;
import io.github.pinpols.batch.worker.core.infrastructure.DefaultStepExecutionAdapter;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.support.TaskExecutionClient;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("ATOMIC 不可重放执行器的持久围栏")
class NonReplayableAtomicExecutorFenceTest {

  @TempDir Path tempDir;

  @Test
  @DisplayName("持久围栏拒绝时 SQL 执行器不得获取数据库连接")
  void shouldNotOpenSqlConnection_whenFenceIsRejected() {
    DataSource dataSource = mock(DataSource.class);
    DataSourceResolver dataSourceResolver = beanName -> dataSource;
    SqlExecutorProperties properties = new SqlExecutorProperties();
    BatchTaskExecutor executor = new SqlTaskExecutor(properties, dataSourceResolver);
    Map<String, Object> parameters =
        Map.of("taskType", "sql", "sql", "CREATE TABLE fence_test (id BIGINT)");

    assertFenceRejectsBeforeExecution(executor, parameters);

    verifyNoInteractions(dataSource);
  }

  @Test
  @DisplayName("持久围栏拒绝时 Shell 执行器不得创建进程副作用")
  void shouldNotRunShellCommand_whenFenceIsRejected() {
    Path outputFile = tempDir.resolve("atomic-fence-must-not-run.txt");
    Path workdirBase = tempDir.resolve("shell-work");
    ShellExecutorProperties properties = new ShellExecutorProperties();
    properties.setWorkdirBase(workdirBase);
    BatchTaskExecutor executor = new ShellTaskExecutor(properties);
    Map<String, Object> parameters = Map.of(
        "taskType", "shell",
        "command", "/usr/bin/touch",
        "args", List.of(outputFile.toString()));

    assertFenceRejectsBeforeExecution(executor, parameters);

    assertThat(outputFile).doesNotExist();
  }

  @Test
  @DisplayName("持久围栏拒绝时 Spark 执行器不得提交作业")
  void shouldNotSubmitSparkJob_whenFenceIsRejected() {
    SparkSubmitExecutorProperties properties = new SparkSubmitExecutorProperties();
    Path sparkSubmitPath = tempDir.resolve("spark-submit-must-not-run");
    String sparkSubmitBin = sparkSubmitPath.toString();
    properties.setSparkSubmitBin(sparkSubmitBin);
    BatchTaskExecutor executor = new SparkSubmitTaskExecutor(properties);
    Map<String, Object> parameters = Map.of(
        "taskType", "spark_submit",
        "appResource", "file:/tmp/not-used.jar",
        "master", "local",
        "deployMode", "client");

    assertFenceRejectsBeforeExecution(executor, parameters);
  }

  @Test
  @DisplayName("持久围栏拒绝时存储过程执行器不得获取数据库连接")
  void shouldNotOpenStoredProcedureConnection_whenFenceIsRejected() {
    DataSource dataSource = mock(DataSource.class);
    DataSourceResolver dataSourceResolver = beanName -> dataSource;
    StoredProcExecutorProperties properties = new StoredProcExecutorProperties();
    BatchTaskExecutor executor = new StoredProcTaskExecutor(properties, dataSourceResolver);
    Map<String, Object> parameters = Map.of("taskType", "stored_proc", "procedureName", "batch.noop");

    assertFenceRejectsBeforeExecution(executor, parameters);

    verifyNoInteractions(dataSource);
  }

  @Test
  @DisplayName("持久围栏拒绝时非幂等 HTTP POST 不得发送请求")
  void shouldNotSendHttpPost_whenFenceIsRejected() throws Exception {
    AtomicInteger requestCount = new AtomicInteger();
    InetSocketAddress bindAddress = new InetSocketAddress("127.0.0.1", 0);
    HttpServer server = HttpServer.create(bindAddress, 0);
    server.createContext("/side-effect", exchange -> {
      requestCount.incrementAndGet();
      exchange.sendResponseHeaders(200, -1);
      exchange.close();
    });
    server.start();
    try {
      HttpExecutorProperties properties = new HttpExecutorProperties();
      properties.setBlockedHostPatterns(Set.of());
      properties.setBlockPrivateIps(false);
      BatchTaskExecutor executor = new HttpTaskExecutor(properties);
      int serverPort = server.getAddress().getPort();
      String endpoint = "http://127.0.0.1:" + serverPort + "/side-effect";
      Map<String, Object> parameters = Map.of(
          "taskType", "http", "url", endpoint, "method", "POST", "body", "payload");

      assertFenceRejectsBeforeExecution(executor, parameters);

      assertThat(requestCount).hasValue(0);
    } finally {
      server.stop(0);
    }
  }

  private static void assertFenceRejectsBeforeExecution(
      BatchTaskExecutor executor, Map<String, Object> parameters) {
    TaskExecutionClient taskExecutionClient = mock(TaskExecutionClient.class);
    when(taskExecutionClient.markNonIdempotentExecutionStarted(
            "tenant-a", 41L, "worker-a", "inv-41"))
        .thenReturn(false);
    List<BatchTaskExecutor> registeredExecutors = List.of(executor);
    BatchTaskExecutorRegistry registry = new BatchTaskExecutorRegistry(registeredExecutors);
    DefaultStepExecutionAdapter adapter =
        new DefaultStepExecutionAdapter(registry, taskExecutionClient);
    String payload = JsonUtils.toJson(parameters);
    Map<String, Object> context = Map.of(
        PipelineRuntimeKeys.PAYLOAD,
        payload,
        PipelineRuntimeKeys.TASK_ID,
        41L,
        PipelineRuntimeKeys.PARTITION_INVOCATION_ID,
        "inv-41");
    StepExecutionRequest request =
        new StepExecutionRequest("tenant-a", "job-a", "ATOMIC", "worker-a", context);

    StepExecutionResponse response = adapter.execute(request);

    assertThat(response.success()).isFalse();
    assertThat(response.code()).isEqualTo(TaskErrorCodes.EXTERNAL_OUTCOME_UNKNOWN);
    verify(taskExecutionClient)
        .markNonIdempotentExecutionStarted("tenant-a", 41L, "worker-a", "inv-41");
  }
}
