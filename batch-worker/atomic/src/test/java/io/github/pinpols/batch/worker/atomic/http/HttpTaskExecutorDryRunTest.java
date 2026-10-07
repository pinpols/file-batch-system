package io.github.pinpols.batch.worker.atomic.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.github.pinpols.batch.common.spi.task.TaskContext;
import io.github.pinpols.batch.common.spi.task.TaskResult;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-026 §dry-run 守护:dry-run 上下文下 HTTP executor 必须不发请求(用真 server 计请求数 = 0 验证)。 */
@DisplayName("HTTP 任务执行器 dry-run 短路: 不发起真实请求")
class HttpTaskExecutorDryRunTest {

  private HttpExecutorProperties props;
  private HttpTaskExecutor executor;
  private HttpServer server;
  private int serverPort;
  private AtomicInteger requestCount;

  @BeforeEach
  void setUp() throws Exception {
    props = new HttpExecutorProperties();
    props.setEnabled(true);
    props.setDefaultTimeout(Duration.ofSeconds(3));
    props.setBlockedHostPatterns(Set.of());
    props.setBlockPrivateIps(false);
    executor = new HttpTaskExecutor(props);

    requestCount = new AtomicInteger();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    serverPort = server.getAddress().getPort();
    server.createContext("/", exchange -> {
      requestCount.incrementAndGet();
      exchange.sendResponseHeaders(200, -1);
      exchange.close();
    });
    server.start();
  }

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  @DisplayName("dry-run 上下文下应短路返回计划动作, 且本地服务零请求, 凭据与报文不落入输出")
  void shouldShortCircuit_whenDryRun_andNotSendHttpRequest() {
    // 准备
    String url = "http://127.0.0.1:" + serverPort + "/secret-endpoint";
    TaskContext ctx = new TaskContext(
        "t1",
        "job-1",
        "ti-1",
        "w-1",
        Map.of(
            "method",
            "POST",
            "url",
            url,
            "headers",
            Map.of("X-Tenant", "t1", "Authorization", "Bearer SECRET"),
            "body",
            "payload-with-pii"),
        Map.of(PipelineRuntimeKeys.DRY_RUN, true));

    // 执行
    TaskResult result = executor.execute(ctx);

    // 断言
    assertThat(result.success()).isTrue();
    assertThat(result.message()).startsWith("dry-run:");
    assertThat(result.output())
        .containsEntry(PipelineRuntimeKeys.DRY_RUN, true)
        .containsEntry("plannedAction", "http")
        .containsEntry("method", "POST")
        .containsEntry("url", url);
    assertThat(result.output().get("headerKeys"))
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.list(String.class))
        .contains("X-Tenant", "Authorization");
    // 关键 1:真 server 一个请求都没收到
    assertThat(requestCount.get()).isZero();
    // 关键 2:header value / body 不进 output(防泄密)
    assertThat(result.output().values()).noneMatch(v -> {
      String s = String.valueOf(v);
      return s.contains("SECRET") || s.contains("payload-with-pii");
    });
  }
}
