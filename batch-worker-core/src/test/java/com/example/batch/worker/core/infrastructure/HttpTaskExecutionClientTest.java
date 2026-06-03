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
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
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
      server.enqueue(new MockResponse.Builder().code(503).build());
      server.enqueue(new MockResponse.Builder().code(200).build());
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
              HttpTaskExecutionClient.meterRegistryProvider(registry),
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
      server.enqueue(new MockResponse.Builder().code(429).body("slow down").build());
      server.enqueue(new MockResponse.Builder().code(429).body("slow down").build());
      server.enqueue(new MockResponse.Builder().code(200).build());
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
              HttpTaskExecutionClient.meterRegistryProvider(registry),
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
  void reportSwallowsAfterHttpExhaustionWhenOutboxUnavailable() throws Exception {
    // P0 #16: HTTP 重试耗尽 + outbox 不可用时,report() 必须吞掉异常(避免 listener 抛回触发 Kafka
    // 重投导致 task 双执行)。改为记 worker.report.dropped.total{reason=outbox_disabled} 由
    // orchestrator lease reclaim 兜底。
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(new MockResponse.Builder().code(503).build());
      server.enqueue(new MockResponse.Builder().code(503).build());
      server.enqueue(new MockResponse.Builder().code(503).build());
      server.start();

      OrchestratorTaskClientProperties props = clientProperties(server.getPort());
      props.setReportMaxAttempts(3);
      props.setReportInitialBackoffMillis(1);
      props.setReportMaxBackoffMillis(2);

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
              HttpTaskExecutionClient.meterRegistryProvider(registry),
              noopCoordinator,
              new WorkerLeaseProperties());

      // 不应抛出
      client.report(report(99L));

      assertThat(server.getRequestCount()).isEqualTo(3);
      assertThat(
              registry
                  .find("worker.report.dropped.total")
                  .tag("reason", "outbox_disabled")
                  .counter()
                  .count())
          .isEqualTo(1.0d);
    }
  }

  @Test
  void reportSwallowsAfterHttpExhaustionWhenOutboxEnqueueFails() throws Exception {
    // P0 #16: outbox 启用但 enqueue 失败(DB 抖动 / repository RuntimeException)亦走 dropped 路径,
    // 不能让异常上抛触发 Kafka 重投。
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(new MockResponse.Builder().code(503).build());
      server.enqueue(new MockResponse.Builder().code(503).build());
      server.start();

      OrchestratorTaskClientProperties props = clientProperties(server.getPort());
      props.setReportMaxAttempts(2);
      props.setReportInitialBackoffMillis(1);
      props.setReportMaxBackoffMillis(2);

      SimpleMeterRegistry registry = new SimpleMeterRegistry();
      WorkerReportOutboxCoordinator coordinator = mock(WorkerReportOutboxCoordinator.class);
      when(coordinator.enqueue(org.mockito.ArgumentMatchers.any())).thenReturn(false);
      @SuppressWarnings("unchecked")
      ObjectProvider<WorkerReportOutboxCoordinator> coordinatorProvider =
          mock(ObjectProvider.class);
      when(coordinatorProvider.getIfAvailable()).thenReturn(coordinator);
      HttpTaskExecutionClient client =
          new HttpTaskExecutionClient(
              props,
              new BatchSecurityProperties(),
              restClientBuilderProvider(),
              new MockEnvironment(),
              HttpTaskExecutionClient.meterRegistryProvider(registry),
              coordinatorProvider,
              new WorkerLeaseProperties());

      client.report(report(100L));

      assertThat(
              registry
                  .find("worker.report.dropped.total")
                  .tag("reason", "outbox_enqueue_failed")
                  .counter()
                  .count())
          .isEqualTo(1.0d);
    }
  }

  @Test
  void renewLeasesBatchUsesSingleHttpCallForChunk() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(
          new MockResponse.Builder()
              .code(200)
              .setHeader("Content-Type", "application/json")
              .body(
                  "{\"results\":[{\"taskId\":1,\"renewed\":true},{\"taskId\":2,\"renewed\":false}]}")
              .build());
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
              HttpTaskExecutionClient.meterRegistryProvider(null),
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
