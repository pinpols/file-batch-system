package com.example.batch.worker.core.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.worker.core.config.OrchestratorTaskClientProperties;
import com.example.batch.worker.core.domain.TaskExecutionReport;
import com.example.batch.worker.core.reportoutbox.WorkerReportOutboxCoordinator;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/** Worker → Orchestrator HTTP 的弹性测试：5xx / I/O 错误有限重试；429 立即失败。 */
@SuppressWarnings("removal")
class HttpTaskExecutionClientTest {

  @Test
  void reportRetriesOn503ThenSucceeds() throws Exception {
    MockWebServer server = new MockWebServer();
    try {
      server.enqueue(new MockResponse().setResponseCode(503));
      server.enqueue(new MockResponse().setResponseCode(200));
      server.start();

      OrchestratorTaskClientProperties props = clientProperties(server.getPort());
      props.setReportMaxAttempts(3);
      props.setReportInitialBackoffMillis(5);
      props.setReportMaxBackoffMillis(20);

      SimpleMeterRegistry registry = new SimpleMeterRegistry();
      @SuppressWarnings("unchecked")
      ObjectProvider<WorkerReportOutboxCoordinator> noopCoordinator =
          Mockito.mock(ObjectProvider.class);
      Mockito.when(noopCoordinator.getIfAvailable()).thenReturn(null);
      HttpTaskExecutionClient client =
          new HttpTaskExecutionClient(
              props,
              new BatchSecurityProperties(),
              jsonRestClientBuilder(),
              new MockEnvironment(),
              registry,
              noopCoordinator);

      TaskExecutionReport report = report(42L);
      client.report(report);

      assertThat(server.getRequestCount()).isEqualTo(2);
      assertThat(
              registry
                  .find("worker.report.failed.total")
                  .tag("reason", "SERVER_ERROR")
                  .counter()
                  .count())
          .isEqualTo(1.0d);
    } finally {
      server.shutdown();
    }
  }

  @Test
  void reportDoesNotRetryOn429() throws Exception {
    MockWebServer server = new MockWebServer();
    try {
      server.enqueue(new MockResponse().setResponseCode(429).setBody("slow down"));
      server.start();

      OrchestratorTaskClientProperties props = clientProperties(server.getPort());
      props.setReportMaxAttempts(5);

      SimpleMeterRegistry registry = new SimpleMeterRegistry();
      @SuppressWarnings("unchecked")
      ObjectProvider<WorkerReportOutboxCoordinator> noopCoordinator =
          Mockito.mock(ObjectProvider.class);
      Mockito.when(noopCoordinator.getIfAvailable()).thenReturn(null);
      HttpTaskExecutionClient client =
          new HttpTaskExecutionClient(
              props,
              new BatchSecurityProperties(),
              jsonRestClientBuilder(),
              new MockEnvironment(),
              registry,
              noopCoordinator);

      assertThatThrownBy(() -> client.report(report(7L)))
          .isInstanceOf(HttpClientErrorException.class);

      assertThat(server.getRequestCount()).isEqualTo(1);
      assertThat(
              registry
                  .find("worker.report.failed.total")
                  .tag("reason", "RATE_LIMITED")
                  .counter()
                  .count())
          .isEqualTo(1.0d);
    } finally {
      server.shutdown();
    }
  }

  private static RestClient.Builder jsonRestClientBuilder() {
    return RestClient.builder()
        .messageConverters(
            converters ->
                converters.add(0, new MappingJackson2HttpMessageConverter(new ObjectMapper())));
  }

  private static OrchestratorTaskClientProperties clientProperties(int port) {
    OrchestratorTaskClientProperties props = new OrchestratorTaskClientProperties();
    props.setBaseUrl("http://127.0.0.1:" + port);
    props.setConnectTimeoutMillis(3_000);
    props.setReadTimeoutMillis(10_000);
    return props;
  }

  private static TaskExecutionReport report(long taskId) {
    TaskExecutionReport r = new TaskExecutionReport();
    r.setTaskId(taskId);
    r.setTenantId("t1");
    r.setWorkerId("w1");
    r.setSuccess(true);
    return r;
  }
}
