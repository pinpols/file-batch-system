package io.github.pinpols.batch.worker.core.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.worker.core.config.OrchestratorTaskClientProperties;
import io.github.pinpols.batch.worker.core.config.WorkerBatchClaimProperties;
import io.github.pinpols.batch.worker.core.config.WorkerConcurrencyProperties;
import io.github.pinpols.batch.worker.core.config.WorkerExecutionTimeoutProperties;
import io.github.pinpols.batch.worker.core.config.WorkerLeaseProperties;
import io.github.pinpols.batch.worker.core.domain.PulledTask;
import io.github.pinpols.batch.worker.core.domain.StepExecutionResponse;
import io.github.pinpols.batch.worker.core.domain.TaskExecutionReport;
import io.github.pinpols.batch.worker.core.domain.WorkerExecutionResult;
import io.github.pinpols.batch.worker.core.reportoutbox.WorkerReportOutboxCoordinator;
import io.github.pinpols.batch.worker.core.support.StepExecutionAdapter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.client.RestClient;

@DisplayName("Worker同步微批上报:逐项身份和单条回退")
class HttpTaskReportBatchTest {

  @Test
  @DisplayName("同租户并发报告合并传输并保留各自trace及invocation")
  void coalescesConcurrentReports_withoutChangingPerItemIdentity() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.setDispatcher(acceptingDispatcher());
      server.start();
      HttpTaskExecutionClient client = newClient(server, true, null);
      TaskExecutionReport first = report("tenant-a", 1L, "invocation-1");
      TaskExecutionReport second = report("tenant-a", 2L, "invocation-2");

      reportConcurrently(client, List.of(first, second));

      assertThat(server.getRequestCount()).isEqualTo(1);
      RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
      assertThat(request).isNotNull();
      assertThat(request.getUrl().encodedPath()).isEqualTo("/internal/tasks/report-batch");
      String tenantHeader = request.getHeaders().get(CommonConstants.DEFAULT_TENANT_ID_HEADER);
      assertThat(tenantHeader).isEqualTo("tenant-a");
      String body = request.getBody().utf8();
      assertThat(body).contains("invocation-1", "invocation-2", "trace-1", "trace-2");
    }
  }

  @Test
  @DisplayName("关闭微批时保持原单条端点")
  void keepsSingleReportPath_whenBatchingIsDisabled() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.setDispatcher(acceptingDispatcher());
      server.start();
      HttpTaskExecutionClient client = newClient(server, false, null);

      reportConcurrently(client, twoReports());

      List<RecordedRequest> requests = recordedRequests(server);
      assertThat(requests).hasSize(2);
      assertThat(requests)
          .allSatisfy(request ->
              assertThat(request.getUrl().encodedPath()).matches("/internal/tasks/[12]/report"));
    }
  }

  @Test
  @DisplayName("部分失败只重报未确认项，成功项不随批次重投")
  void retriesOnlyRejectedItem_whenOtherItemIsConfirmed() throws Exception {
    String response = """
        {"results":[{"taskId":1,"ok":true},{"taskId":2,"ok":false,"error":"conflict"}]}
        """;
    assertFallbackPaths(response, List.of("/internal/tasks/2/report"));
  }

  @ParameterizedTest(name = "{index}: {0}")
  @MethodSource("ambiguousResponses")
  @DisplayName("响应乱序按身份匹配，缺失、重复和外来身份不伪造确认")
  void reconcilesEachResponseIdentity(String response, List<String> expectedFallbackPaths)
      throws Exception {
    assertFallbackPaths(response, expectedFallbackPaths);
  }

  private static Stream<Object[]> ambiguousResponses() {
    return Stream.of(
        new Object[] {"""
          {"results":[{"taskId":2,"ok":true},{"taskId":1,"ok":true}]}
          """, List.of()},
        new Object[] {"""
          {"results":[{"taskId":1,"ok":true},{"taskId":999,"ok":true}]}
          """, List.of("/internal/tasks/2/report")},
        new Object[] {"""
          {"results":[{"taskId":1,"ok":true},{"taskId":1,"ok":true}]}
          """, List.of("/internal/tasks/1/report", "/internal/tasks/2/report")},
        new Object[] {"""
          {"results":[{"taskId":1,"ok":true}]}
          """, List.of("/internal/tasks/2/report")},
        new Object[] {"""
          {"results":[{"taskId":1},{"taskId":2,"ok":true}]}
          """, List.of("/internal/tasks/1/report")},
        new Object[] {"{}", List.of("/internal/tasks/1/report", "/internal/tasks/2/report")},
        new Object[] {"not json", List.of("/internal/tasks/1/report", "/internal/tasks/2/report")});
  }

  private static void assertFallbackPaths(String batchResponse, List<String> expectedFallbackPaths)
      throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.setDispatcher(new Dispatcher() {
        @Override
        public MockResponse dispatch(RecordedRequest request) {
          if (request.getUrl().encodedPath().equals("/internal/tasks/report-batch")) {
            return jsonResponse(batchResponse);
          }
          return new MockResponse.Builder().code(200).build();
        }
      });
      server.start();
      HttpTaskExecutionClient client = newClient(server, true, null);

      reportConcurrently(client, twoReports());

      List<RecordedRequest> requests = recordedRequests(server);
      long batchRequests = requests.stream()
          .filter(request -> request.getUrl().encodedPath().equals("/internal/tasks/report-batch"))
          .count();
      assertThat(batchRequests).isEqualTo(1);
      List<String> actualFallbackPaths = requests.stream()
          .map(request -> request.getUrl().encodedPath())
          .filter(path -> !path.equals("/internal/tasks/report-batch"))
          .toList();
      assertThat(actualFallbackPaths).containsExactlyInAnyOrderElementsOf(expectedFallbackPaths);
      for (RecordedRequest request : requests) {
        String path = request.getUrl().encodedPath();
        if (path.equals("/internal/tasks/1/report")) {
          assertThat(request.getBody().utf8()).contains("invocation-1", "trace-1");
        } else if (path.equals("/internal/tasks/2/report")) {
          assertThat(request.getBody().utf8()).contains("invocation-2", "trace-2");
        }
      }
    }
  }

  @Test
  @DisplayName("不同租户即使taskId相同也分开HTTP，并保持header与body一致")
  void separatesTenants_evenWhenTaskIdsOverlap() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.setDispatcher(acceptingDispatcher());
      server.start();
      HttpTaskExecutionClient client = newClient(server, true, null);
      List<TaskExecutionReport> reports =
          List.of(report("tenant-a", 1L, "invocation-a"), report("tenant-b", 1L, "invocation-b"));

      reportConcurrently(client, reports);

      List<RecordedRequest> requests = recordedRequests(server);
      assertThat(requests).hasSize(2);
      Set<String> tenantHeaders = new HashSet<>();
      for (RecordedRequest request : requests) {
        String tenantHeader = request.getHeaders().get(CommonConstants.DEFAULT_TENANT_ID_HEADER);
        tenantHeaders.add(tenantHeader);
        Map<?, ?> payload = JsonUtils.fromJson(request.getBody().utf8(), Map.class);
        Map<?, ?> item = payload;
        if (request.getUrl().encodedPath().equals("/internal/tasks/report-batch")) {
          List<?> items = (List<?>) payload.get("items");
          assertThat(items).hasSize(1);
          item = (Map<?, ?>) items.getFirst();
        }
        assertThat(item.get("tenantId")).isEqualTo(tenantHeader);
      }
      assertThat(tenantHeaders).containsExactlyInAnyOrder("tenant-a", "tenant-b");
    }
  }

  @Test
  @DisplayName("同taskId不同invocation不共用同一个批响应身份")
  void separatesInvocations_whenTaskIdIsTheSame() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.setDispatcher(acceptingDispatcher());
      server.start();
      HttpTaskExecutionClient client = newClient(server, true, null);
      List<TaskExecutionReport> reports = List.of(
          report("tenant-a", 1L, "invocation-old"), report("tenant-a", 1L, "invocation-new"));

      reportConcurrently(client, reports);

      List<RecordedRequest> requests = recordedRequests(server);
      assertThat(requests).hasSize(2);
      Set<String> invocations = new HashSet<>();
      for (RecordedRequest request : requests) {
        Map<?, ?> payload = JsonUtils.fromJson(request.getBody().utf8(), Map.class);
        Map<?, ?> item = payload;
        if (request.getUrl().encodedPath().equals("/internal/tasks/report-batch")) {
          List<?> items = (List<?>) payload.get("items");
          assertThat(items).hasSize(1);
          item = (Map<?, ?>) items.getFirst();
        }
        invocations.add((String) item.get("partitionInvocationId"));
      }
      assertThat(invocations).containsExactlyInAnyOrder("invocation-old", "invocation-new");
    }
  }

  @Test
  @DisplayName("批传输结果未知时逐项走原有限重试，不重新提交整个批次")
  void fallsBackPerItem_whenBatchResponseIsUnknown() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.setDispatcher(new Dispatcher() {
        @Override
        public MockResponse dispatch(RecordedRequest request) {
          int code =
              request.getUrl().encodedPath().equals("/internal/tasks/report-batch") ? 503 : 200;
          return new MockResponse.Builder().code(code).build();
        }
      });
      server.start();
      HttpTaskExecutionClient client = newClient(server, true, null);

      reportConcurrently(client, twoReports());

      List<RecordedRequest> requests = recordedRequests(server);
      List<String> paths =
          requests.stream().map(request -> request.getUrl().encodedPath()).toList();
      assertThat(paths)
          .containsExactlyInAnyOrder(
              "/internal/tasks/report-batch",
              "/internal/tasks/1/report",
              "/internal/tasks/2/report");
    }
  }

  @Test
  @DisplayName("批确认失败只交接失败报告，业务各执行一次且租约等发件箱交接后才移除")
  void keepsBusinessExecutionIndependent_fromReportFallbackAndOutbox() throws Exception {
    CountDownLatch enqueueEntered = new CountDownLatch(1);
    CountDownLatch allowEnqueue = new CountDownLatch(1);
    WorkerReportOutboxCoordinator coordinator = mock(WorkerReportOutboxCoordinator.class);
    when(coordinator.enqueue(any(TaskExecutionReport.class))).thenAnswer(invocation -> {
      enqueueEntered.countDown();
      if (!allowEnqueue.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("outbox test barrier timed out");
      }
      return true;
    });
    try (MockWebServer server = new MockWebServer()) {
      server.setDispatcher(new Dispatcher() {
        @Override
        public MockResponse dispatch(RecordedRequest request) {
          if (request.getUrl().encodedPath().endsWith("/renew")) {
            return jsonResponse("""
                {"cancelRequested":false}
                """);
          }
          if (request.getUrl().encodedPath().equals("/internal/tasks/report-batch")) {
            return jsonResponse("""
                {"results":[{"taskId":1,"ok":true},{"taskId":2,"ok":false}]}
                """);
          }
          return new MockResponse.Builder().code(503).build();
        }
      });
      server.start();
      HttpTaskExecutionClient client = newClient(server, true, coordinator);
      ActiveTaskLeaseRegistry leases = new ActiveTaskLeaseRegistry();
      WorkerExecutionTimeoutProperties timeoutProperties = new WorkerExecutionTimeoutProperties();
      timeoutProperties.setPoolSize(2);
      WorkerConcurrencyProperties concurrencyProperties = new WorkerConcurrencyProperties();
      concurrencyProperties.setMaxConcurrentTasks(2);
      TaskExecutionPool executionPool =
          new TaskExecutionPool(timeoutProperties, concurrencyProperties);
      executionPool.start();
      Map<String, Integer> effects = new ConcurrentHashMap<>();
      CountDownLatch executed = new CountDownLatch(2);
      StepExecutionAdapter adapter = request -> {
        String taskId = (String) request.context().get(PipelineRuntimeKeys.TASK_ID);
        effects.merge(taskId, 1, Integer::sum);
        executed.countDown();
        awaitLatch(executed);
        return StepExecutionResponse.successResponse();
      };
      @SuppressWarnings("unchecked")
      ObjectProvider<MeterRegistry> meterProvider = mock(ObjectProvider.class);
      when(meterProvider.getIfAvailable()).thenReturn(new SimpleMeterRegistry());
      TaskScheduler watchdog = mock(TaskScheduler.class);
      DefaultTaskExecutionWrapper wrapper = new DefaultTaskExecutionWrapper(
          adapter,
          client,
          leases,
          executionPool,
          timeoutProperties,
          meterProvider,
          watchdog,
          new WorkerLeaseProperties());
      PulledTask firstTask = pulledTask(1L);
      PulledTask secondTask = pulledTask(2L);
      wrapper.registerClaimedBatch(List.of(firstTask, secondTask));
      try (var listeners = Executors.newFixedThreadPool(2)) {
        Future<WorkerExecutionResult> first = listeners.submit(() -> wrapper.execute(firstTask));
        Future<WorkerExecutionResult> second = listeners.submit(() -> wrapper.execute(secondTask));
        try {
          assertThat(enqueueEntered.await(5, TimeUnit.SECONDS)).isTrue();
          WorkerExecutionResult firstResult = first.get(5, TimeUnit.SECONDS);
          assertThat(firstResult.success()).isTrue();
          assertThat(second.isDone()).isFalse();
          assertThat(leases.size()).isEqualTo(1);
          assertThat(effects).containsExactlyInAnyOrderEntriesOf(Map.of("1", 1, "2", 1));
        } finally {
          allowEnqueue.countDown();
        }
        WorkerExecutionResult secondResult = second.get(5, TimeUnit.SECONDS);
        assertThat(secondResult.success()).isTrue();
        assertThat(leases.size()).isZero();
        ArgumentCaptor<TaskExecutionReport> savedReport =
            ArgumentCaptor.forClass(TaskExecutionReport.class);
        verify(coordinator, times(1)).enqueue(savedReport.capture());
        TaskExecutionReport persisted = savedReport.getValue();
        assertThat(persisted.getTaskId()).isEqualTo(2L);
        assertThat(persisted.getPartitionInvocationId()).isEqualTo("invocation-2");
        List<RecordedRequest> requests = recordedRequests(server);
        assertThat(requests.stream()
                .filter(request -> request.getUrl().encodedPath().endsWith("/renew")))
            .hasSize(2)
            .allSatisfy(request ->
                assertThat(request.getBody().utf8()).contains("\"executionStarted\":true"));
        List<String> paths = requests.stream()
            .map(request -> request.getUrl().encodedPath())
            .filter(path -> path.contains("/report"))
            .toList();
        assertThat(paths)
            .containsExactlyInAnyOrder("/internal/tasks/report-batch", "/internal/tasks/2/report");
      } finally {
        allowEnqueue.countDown();
        client.closeReportBatcher();
        executionPool.shutdown();
      }
    }
  }

  private static void awaitLatch(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("business execution test barrier timed out");
      }
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(ex);
    }
  }

  private static PulledTask pulledTask(Long taskId) {
    PulledTask task = new PulledTask();
    task.setTaskId(taskId.toString());
    task.setTenantId("tenant-a");
    task.setWorkerId("worker-a");
    task.setTaskType("IMPORT");
    task.setJobCode("JOB");
    task.setPayload("{}");
    task.setTraceId("trace-" + taskId);
    task.setPartitionInvocationId("invocation-" + taskId);
    return task;
  }

  @Test
  @DisplayName("首租户HTTP阻塞时另一租户立即走单条，不串行等待另一租户的HTTP超时")
  void bypassesInFlightBatch_forOtherTenant() throws Exception {
    CountDownLatch batchEntered = new CountDownLatch(1);
    CountDownLatch releaseBatch = new CountDownLatch(1);
    try (MockWebServer server = new MockWebServer()) {
      server.setDispatcher(new Dispatcher() {
        @Override
        public MockResponse dispatch(RecordedRequest request) {
          if (request.getUrl().encodedPath().equals("/internal/tasks/report-batch")) {
            batchEntered.countDown();
            awaitLatch(releaseBatch);
            return jsonResponse("""
                {"results":[{"taskId":1,"ok":true}]}
                """);
          }
          return new MockResponse.Builder().code(200).build();
        }
      });
      server.start();
      Map<String, String> overrides =
          Map.of("batch.worker.task-client.read-timeout-millis", "10000");
      HttpTaskExecutionClient client = newClient(server, true, null, overrides);
      try (var executor = Executors.newFixedThreadPool(2)) {
        Future<?> first =
            executor.submit(() -> client.report(report("tenant-a", 1L, "invocation-1")));
        try {
          assertThat(batchEntered.await(5, TimeUnit.SECONDS)).isTrue();
          Future<?> second =
              executor.submit(() -> client.report(report("tenant-b", 2L, "invocation-2")));

          assertThatCode(() -> second.get(500, TimeUnit.MILLISECONDS)).doesNotThrowAnyException();
          assertThat(first.isDone()).isFalse();
          List<String> paths = recordedRequests(server).stream()
              .map(request -> request.getUrl().encodedPath())
              .toList();
          assertThat(paths)
              .containsExactlyInAnyOrder(
                  "/internal/tasks/report-batch", "/internal/tasks/2/report");
        } finally {
          releaseBatch.countDown();
        }
        first.get(5, TimeUnit.SECONDS);
      } finally {
        releaseBatch.countDown();
        client.closeReportBatcher();
      }
    }
  }

  @Test
  @DisplayName("批HTTP已经消耗首次尝试，单条回退不能扩大原报告重试总预算")
  void shouldCountBatchAttemptAgainstOriginalReportBudget() throws Exception {
    WorkerReportOutboxCoordinator coordinator = mock(WorkerReportOutboxCoordinator.class);
    when(coordinator.enqueue(any(TaskExecutionReport.class))).thenReturn(true);
    try (MockWebServer server = new MockWebServer()) {
      server.setDispatcher(new Dispatcher() {
        @Override
        public MockResponse dispatch(RecordedRequest request) {
          return new MockResponse.Builder().code(503).build();
        }
      });
      server.start();
      Map<String, String> overrides = Map.of(
          "batch.worker.task-client.report-max-attempts", "3",
          "batch.worker.task-client.report-initial-backoff-millis", "1",
          "batch.worker.task-client.report-max-backoff-millis", "1");
      HttpTaskExecutionClient client = newClient(server, true, coordinator, overrides);

      reportConcurrently(client, twoReports());

      List<String> paths = recordedRequests(server).stream()
          .map(request -> request.getUrl().encodedPath())
          .toList();
      assertThat(paths)
          .containsExactlyInAnyOrder(
              "/internal/tasks/report-batch",
              "/internal/tasks/1/report",
              "/internal/tasks/1/report",
              "/internal/tasks/2/report",
              "/internal/tasks/2/report");
      verify(coordinator, times(2)).enqueue(any(TaskExecutionReport.class));
    }
  }

  @Test
  @DisplayName("仅一次报告预算在批传输未确认后直接交发件箱，不再额外发单条HTTP")
  void handsOffToOutbox_whenBatchUsedLastAllowedAttempt() throws Exception {
    WorkerReportOutboxCoordinator coordinator = mock(WorkerReportOutboxCoordinator.class);
    when(coordinator.enqueue(any(TaskExecutionReport.class))).thenReturn(true);
    try (MockWebServer server = new MockWebServer()) {
      server.setDispatcher(new Dispatcher() {
        @Override
        public MockResponse dispatch(RecordedRequest request) {
          return new MockResponse.Builder().code(503).build();
        }
      });
      server.start();
      Map<String, String> overrides = Map.of("batch.worker.task-client.report-max-attempts", "1");
      HttpTaskExecutionClient client = newClient(server, true, coordinator, overrides);

      reportConcurrently(client, twoReports());

      List<String> paths = recordedRequests(server).stream()
          .map(request -> request.getUrl().encodedPath())
          .toList();
      assertThat(paths).containsExactly("/internal/tasks/report-batch");
      verify(coordinator, times(2)).enqueue(any(TaskExecutionReport.class));
    }
  }

  @Test
  @DisplayName("八个并发报告的受控传输对照：单条八次，微批一次；耗时分项记录不作生产容量结论")
  void reducesHttpRoundTrips_forEightConcurrentReports(TestReporter reporter) throws Exception {
    List<Map<String, Object>> measurements = new ArrayList<>();
    for (boolean batching : List.of(false, true)) {
      AtomicLong serverHandlerNanos = new AtomicLong();
      Dispatcher delegate = acceptingDispatcher();
      try (MockWebServer server = new MockWebServer()) {
        server.setDispatcher(new Dispatcher() {
          @Override
          public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
            long startedAt = System.nanoTime();
            try {
              return delegate.dispatch(request);
            } finally {
              serverHandlerNanos.addAndGet(System.nanoTime() - startedAt);
            }
          }
        });
        server.start();
        HttpTaskExecutionClient client = newClient(server, batching, null);
        // 两个对照都先建立HTTP客户端；不把首次Jackson/连接初始化当成攒批收益。
        client.claim("tenant-a", 999L, "worker-a");
        server.takeRequest();
        serverHandlerNanos.set(0L);
        int initialRequests = server.getRequestCount();
        List<TaskExecutionReport> reports = new ArrayList<>();
        for (long taskId = 1L; taskId <= 8L; taskId++) {
          reports.add(report("tenant-a", taskId, "invocation-" + taskId));
        }
        long startedAt = System.nanoTime();

        reportConcurrently(client, reports);

        long totalNanos = System.nanoTime() - startedAt;
        int reportRequests = server.getRequestCount() - initialRequests;
        assertThat(reportRequests).isEqualTo(batching ? 1 : 8);
        List<RecordedRequest> requests = new ArrayList<>();
        for (int index = 0; index < reportRequests; index++) {
          RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
          assertThat(request).isNotNull();
          requests.add(request);
        }
        if (batching) {
          RecordedRequest request = requests.getFirst();
          assertThat(request.getUrl().encodedPath()).isEqualTo("/internal/tasks/report-batch");
          Map<?, ?> body = JsonUtils.fromJson(request.getBody().utf8(), Map.class);
          assertThat((List<?>) body.get("items")).hasSize(8);
        }
        measurements.add(Map.of(
            "reports",
            8,
            "batching",
            batching,
            "reportHttpRequests",
            reportRequests,
            "configuredCoalescingWaitMillis",
            batching ? 100 : 0,
            "mockEndpointHandlerNanos",
            serverHandlerNanos.get(),
            "callerElapsedNanos",
            totalNanos));
        client.closeReportBatcher();
      }
    }
    // callerElapsed包含线程调度、等待窗口和HTTP；mockEndpointHandler仅是fixture端处理耗时，两者不可冒充产能。
    String probeJson = JsonUtils.toJson(measurements);
    reporter.publishEntry("report-transport-probe", probeJson);
    Path probePath = Path.of("target", "report-transport-probe.json");
    Files.writeString(probePath, probeJson);
  }

  private static List<TaskExecutionReport> twoReports() {
    return List.of(report("tenant-a", 1L, "invocation-1"), report("tenant-a", 2L, "invocation-2"));
  }

  private static List<RecordedRequest> recordedRequests(MockWebServer server) throws Exception {
    List<RecordedRequest> requests = new ArrayList<>();
    int count = server.getRequestCount();
    for (int index = 0; index < count; index++) {
      RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
      assertThat(request).isNotNull();
      requests.add(request);
    }
    return requests;
  }

  private static Dispatcher acceptingDispatcher() {
    return new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        if (request.getUrl().encodedPath().equals("/internal/tasks/report-batch")) {
          Map<?, ?> payload = JsonUtils.fromJson(request.getBody().utf8(), Map.class);
          List<?> items = (List<?>) payload.get("items");
          List<Map<String, Object>> results = new ArrayList<>();
          for (Object raw : items) {
            Map<?, ?> item = (Map<?, ?>) raw;
            results.add(Map.of("taskId", item.get("taskId"), "ok", true));
          }
          String responseBody = JsonUtils.toJson(Map.of("results", results));
          return jsonResponse(responseBody);
        }
        return new MockResponse.Builder().code(200).build();
      }
    };
  }

  private static void reportConcurrently(
      HttpTaskExecutionClient client, List<TaskExecutionReport> reports) throws Exception {
    try (var executor = Executors.newFixedThreadPool(reports.size())) {
      CountDownLatch ready = new CountDownLatch(reports.size());
      CountDownLatch start = new CountDownLatch(1);
      List<Future<?>> futures = new ArrayList<>();
      for (TaskExecutionReport report : reports) {
        Future<?> future = executor.submit(() -> {
          ready.countDown();
          try {
            if (!start.await(5, TimeUnit.SECONDS)) {
              throw new IllegalStateException("report start barrier timed out");
            }
          } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
          }
          client.report(report);
        });
        futures.add(future);
      }
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      for (Future<?> future : futures) {
        future.get(10, TimeUnit.SECONDS);
      }
    }
  }

  private static TaskExecutionReport report(String tenant, Long taskId, String invocation) {
    TaskExecutionReport report = new TaskExecutionReport();
    report.setTaskId(taskId);
    report.setTenantId(tenant);
    report.setWorkerId("worker-a");
    report.setPartitionInvocationId(invocation);
    report.setTraceId("trace-" + taskId);
    report.setSuccess(true);
    return report;
  }

  private static HttpTaskExecutionClient newClient(
      MockWebServer server, boolean enabled, WorkerReportOutboxCoordinator coordinator) {
    return newClient(server, enabled, coordinator, Map.of());
  }

  private static HttpTaskExecutionClient newClient(
      MockWebServer server,
      boolean enabled,
      WorkerReportOutboxCoordinator coordinator,
      Map<String, String> overrides) {
    Map<String, String> settings = new HashMap<>(Map.of(
        "batch.worker.task-client.base-url", server.url("/").toString(),
        "batch.worker.task-client.report-batch-enabled", Boolean.toString(enabled),
        "batch.worker.task-client.report-batch-wait-millis", "100",
        "batch.worker.task-client.report-max-attempts", "2",
        "batch.worker.task-client.connect-timeout-millis", "500",
        "batch.worker.task-client.read-timeout-millis", "1000"));
    settings.putAll(overrides);
    MapConfigurationPropertySource propertySource = new MapConfigurationPropertySource(settings);
    OrchestratorTaskClientProperties properties = new Binder(propertySource)
        .bind("batch.worker.task-client", OrchestratorTaskClientProperties.class)
        .get();
    @SuppressWarnings("unchecked")
    ObjectProvider<WorkerReportOutboxCoordinator> outboxProvider = mock(ObjectProvider.class);
    when(outboxProvider.getIfAvailable()).thenReturn(coordinator);
    @SuppressWarnings("unchecked")
    ObjectProvider<RestClient.Builder> builders = mock(ObjectProvider.class);
    when(builders.getObject())
        .thenAnswer(invocation -> RestClient.builder()
            .configureMessageConverters(builder -> builder.configureMessageConvertersList(
                converters -> converters.addFirst(new JacksonJsonHttpMessageConverter()))));
    return new HttpTaskExecutionClient(
        properties,
        new BatchSecurityProperties(),
        builders,
        new MockEnvironment(),
        new SimpleMeterRegistry(),
        outboxProvider,
        new WorkerLeaseProperties(),
        new WorkerBatchClaimProperties());
  }

  private static MockResponse jsonResponse(String body) {
    return new MockResponse.Builder()
        .code(200)
        .addHeader("Content-Type", "application/json")
        .body(body)
        .build();
  }
}
