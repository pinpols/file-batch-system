package com.example.batch.worker.core.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.worker.core.config.OrchestratorTaskClientProperties;
import com.example.batch.worker.core.config.WorkerLeaseProperties;
import com.example.batch.worker.core.domain.TaskExecutionReport;
import com.example.batch.worker.core.reportoutbox.WorkerReportOutboxCoordinator;
import com.example.batch.worker.core.support.TaskLeaseRenewItem;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.client.RestClient;

/**
 * Worker → Orchestrator HTTP 的弹性测试：5xx / I/O 错误 / 429 均按退避重试，R6 P0-7 起 429 不再立即失败， 避免高峰期 worker
 * REPORT 数据被静默丢弃。
 */
class HttpTaskExecutionClientTest {

  @Test
  void reportRetriesOn503ThenSucceeds() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(new MockResponse().setResponseCode(503));
      server.enqueue(new MockResponse().setResponseCode(200));
      server.start();

      OrchestratorTaskClientProperties props = clientProperties(server.getPort());
      props.setReportMaxAttempts(3);
      props.setReportInitialBackoffMillis(5);
      props.setReportMaxBackoffMillis(20);

      SimpleMeterRegistry registry = new SimpleMeterRegistry();
      @SuppressWarnings("unchecked")
      ObjectProvider<WorkerReportOutboxCoordinator> noopCoordinator = mock(ObjectProvider.class);
      when(noopCoordinator.getIfAvailable()).thenReturn(null);
      HttpTaskExecutionClient client =
          new HttpTaskExecutionClient(
              props,
              new BatchSecurityProperties(),
              restClientBuilderProvider(),
              new MockEnvironment(),
              registry,
              noopCoordinator,
              new WorkerLeaseProperties());

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
    }
  }

  @Test
  void reportRetriesOn429AndSucceedsWhenLimitClears() throws Exception {
    // R6 P0-7：429 = orchestrator sliding-window 限流的瞬时拒绝，过去 worker 直接放弃 REPORT 等于把
    // task 数据丢掉（orchestrator 端只能等 lease 过期回收）。改为按退避重试，与 5xx / I/O 同处理。
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(new MockResponse().setResponseCode(429).setBody("slow down"));
      server.enqueue(new MockResponse().setResponseCode(429).setBody("slow down"));
      server.enqueue(new MockResponse().setResponseCode(200));
      server.start();

      OrchestratorTaskClientProperties props = clientProperties(server.getPort());
      props.setReportMaxAttempts(5);

      SimpleMeterRegistry registry = new SimpleMeterRegistry();
      @SuppressWarnings("unchecked")
      ObjectProvider<WorkerReportOutboxCoordinator> noopCoordinator = mock(ObjectProvider.class);
      when(noopCoordinator.getIfAvailable()).thenReturn(null);
      HttpTaskExecutionClient client =
          new HttpTaskExecutionClient(
              props,
              new BatchSecurityProperties(),
              restClientBuilderProvider(),
              new MockEnvironment(),
              registry,
              noopCoordinator,
              new WorkerLeaseProperties());

      client.report(report(7L));

      assertThat(server.getRequestCount()).isEqualTo(3);
      assertThat(
              registry
                  .find("worker.report.failed.total")
                  .tag("reason", "RATE_LIMITED")
                  .counter()
                  .count())
          .isEqualTo(2.0d);
    }
  }

  @Test
  void renewLeasesBatchUsesSingleHttpCallForChunk() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(
          new MockResponse()
              .setResponseCode(200)
              .setHeader("Content-Type", "application/json")
              .setBody(
                  "{\"results\":[{\"taskId\":1,\"renewed\":true},{\"taskId\":2,\"renewed\":false}]}"));
      server.start();

      OrchestratorTaskClientProperties props = clientProperties(server.getPort());
      props.setClaimMaxAttempts(2);

      @SuppressWarnings("unchecked")
      ObjectProvider<WorkerReportOutboxCoordinator> noopCoordinator = mock(ObjectProvider.class);
      when(noopCoordinator.getIfAvailable()).thenReturn(null);
      HttpTaskExecutionClient client =
          new HttpTaskExecutionClient(
              props,
              new BatchSecurityProperties(),
              restClientBuilderProvider(),
              new MockEnvironment(),
              null,
              noopCoordinator,
              new WorkerLeaseProperties());

      List<TaskLeaseRenewItem> items =
          List.of(
              new TaskLeaseRenewItem("t1", 1L, "w1", null),
              new TaskLeaseRenewItem("t1", 2L, "w1", "inv"));
      Map<Long, Boolean> out = client.renewLeasesBatch(items);

      assertThat(out).containsEntry(1L, true).containsEntry(2L, false);
      assertThat(server.getRequestCount()).isEqualTo(1);
    }
  }

  private static RestClient.Builder jsonRestClientBuilder() {
    return RestClient.builder()
        .configureMessageConverters(
            b ->
                b.configureMessageConvertersList(
                    converters -> converters.add(0, new JacksonJsonHttpMessageConverter())));
  }

  private static ObjectProvider<RestClient.Builder> restClientBuilderProvider() {
    return new ObjectProvider<>() {
      @Override
      public RestClient.Builder getObject(Object... args) {
        return jsonRestClientBuilder();
      }

      @Override
      public RestClient.Builder getObject() {
        return jsonRestClientBuilder();
      }

      @Override
      public RestClient.Builder getIfAvailable() {
        return jsonRestClientBuilder();
      }

      @Override
      public RestClient.Builder getIfUnique() {
        return jsonRestClientBuilder();
      }
    };
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
