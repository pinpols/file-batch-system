package io.github.pinpols.batch.sdk.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.sdk.client.BatchPlatformClient;
import io.github.pinpols.batch.sdk.client.BatchPlatformClientConfig;
import io.github.pinpols.batch.sdk.internal.PlatformHttpClient;
import io.github.pinpols.batch.sdk.internal.PlatformHttpException;
import io.github.pinpols.batch.sdk.task.SdkTaskContext;
import io.github.pinpols.batch.sdk.task.SdkTaskHandler;
import io.github.pinpols.batch.sdk.task.SdkTaskResult;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * testkit 自测 —— 同时是 roadmap §7.4 DoD「租户用 testkit 写 handler 集成测,5s 跑完」的可执行证明。
 *
 * <p>跑通的链路:{@code client.start()} → SDK consumer 订到内嵌 broker → {@link FakeBatchPlatform#dispatch}
 * 真发派单 → handler 执行 → REPORT 回到 HTTP stub → {@link FakeBatchPlatform#awaitReport} 拿到结果。
 */
@BatchWorkerTest
@DisplayName("testkit 端到端自测:内嵌消息代理与 HTTP 桩下,派单、执行、回执全链路在 5 秒内跑完")
class FakeBatchPlatformSelfTest {

  @Test
  @DisplayName("未支持的路径和错误方法不能伪装为成功")
  void shouldRejectUnsupportedRoutesAndMethods(FakeBatchPlatform platform) throws Exception {
    var client = HttpClient.newHttpClient();
    int registrationCount = platform.registrations().size();
    for (String path : List.of(
        "/internal/tasks/9080/renew-lease",
        "/internal/unrelated/register",
        "/internal/tasks/report-batch")) {
      var request = HttpRequest.newBuilder(URI.create(platform.baseUrl() + path))
          .POST(HttpRequest.BodyPublishers.ofString("{}"))
          .build();
      assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
          .as(path)
          .isEqualTo(404);
    }
    var wrongMethod = HttpRequest.newBuilder(
            URI.create(platform.baseUrl() + "/internal/workers/worker-a/heartbeat"))
        .GET()
        .build();
    assertThat(client.send(wrongMethod, HttpResponse.BodyHandlers.ofString()).statusCode())
        .isEqualTo(405);
    for (String action : List.of("heartbeat", "deactivate")) {
      var supportedRequest = HttpRequest.newBuilder(
              URI.create(platform.baseUrl() + "/internal/workers/worker-a/" + action))
          .POST(HttpRequest.BodyPublishers.ofString("{}"))
          .build();
      assertThat(client
              .send(supportedRequest, HttpResponse.BodyHandlers.ofString())
              .statusCode())
          .as(action)
          .isEqualTo(200);
    }
    assertThat(platform.registrations()).hasSize(registrationCount);
  }

  @Test
  @DisplayName("任务报告完成后不得再认领")
  void shouldRejectClaim_whenTaskIsCompleted(FakeBatchPlatform platform) throws Exception {
    SdkTaskHandler handler = new SdkTaskHandler() {
      @Override
      public String taskType() {
        return "testkit_completed_claim";
      }

      @Override
      public SdkTaskResult execute(SdkTaskContext context) {
        return SdkTaskResult.ok("complete");
      }
    };
    BatchPlatformClient client = BatchPlatformClient.builder(
            platform.configFor("tenant-a", "worker-a"))
        .register(handler)
        .build();
    client.start();
    try {
      platform.dispatch(TaskDispatchMessageBuilder.dispatch("testkit_completed_claim")
          .tenantId("tenant-a")
          .taskId(9084L)
          .build());
      assertThat(platform.awaitReport(9084L, Duration.ofSeconds(5)).success()).isTrue();
      var request = HttpRequest.newBuilder(
              URI.create(platform.baseUrl() + "/internal/tasks/9084/claim"))
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(
              "{\"tenantId\":\"tenant-a\",\"workerId\":\"worker-a\"}"))
          .build();
      var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).isEqualTo(409);
      String invocation = platform.nonIdempotentStarts().get(9084L);
      assertThat(invocation).isNotBlank();
      String owner = "{\"tenantId\":\"tenant-a\",\"workerId\":\"worker-a\","
          + "\"partitionInvocationId\":\"" + invocation + "\"}";
      for (String endpoint : List.of("renew", "non-idempotent-started")) {
        var activeRequest = HttpRequest.newBuilder(
                URI.create(platform.baseUrl() + "/internal/tasks/9084/" + endpoint))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(owner))
            .build();
        var activeResponse =
            HttpClient.newHttpClient().send(activeRequest, HttpResponse.BodyHandlers.ofString());
        assertThat(activeResponse.statusCode()).as(endpoint).isEqualTo(409);
      }
      platform.requeue(9084L);
      assertThat(platform.reports()).doesNotContainKey(9084L);
      var retry = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
      assertThat(retry.statusCode()).isEqualTo(200);
      String retryInvocation = new ObjectMapper()
          .readTree(retry.body())
          .path("partitionInvocationId")
          .asText();
      assertThat(retryInvocation).isNotBlank().isNotEqualTo(invocation);
      assertThat(catchThrowable(() -> platform.requeue(9084L)))
          .isInstanceOf(IllegalStateException.class);
      for (String endpoint : List.of("renew", "non-idempotent-started", "report")) {
        var staleRequest = HttpRequest.newBuilder(
                URI.create(platform.baseUrl() + "/internal/tasks/9084/" + endpoint))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(owner))
            .build();
        assertThat(HttpClient.newHttpClient()
                .send(staleRequest, HttpResponse.BodyHandlers.ofString())
                .statusCode())
            .as(endpoint)
            .isEqualTo(409);
      }
    } finally {
      client.stop();
    }
  }

  @Test
  @DisplayName("报告正文缺失 taskId 时使用路径 ID")
  void reportWithoutBodyTaskId_recordsPathTask(FakeBatchPlatform platform) throws Exception {
    verifyReportPathTask(false, platform);
  }

  @Test
  @DisplayName("报告正文 taskId 不一致时使用路径 ID")
  void reportWithDifferentBodyTaskId_recordsPathTask(FakeBatchPlatform platform) throws Exception {
    verifyReportPathTask(true, platform);
  }

  private void verifyReportPathTask(boolean conflictingBodyId, FakeBatchPlatform platform)
      throws Exception {
    long taskId = conflictingBodyId ? 9085L : 9083L;
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicReference<String> invocation = new AtomicReference<>();
    SdkTaskHandler handler = new SdkTaskHandler() {
      @Override
      public String taskType() {
        return "testkit_report_path";
      }

      @Override
      public SdkTaskResult execute(SdkTaskContext context) {
        invocation.set((String) context.runtimeAttributes().get("partitionInvocationId"));
        started.countDown();
        try {
          if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(interrupted);
        }
        return SdkTaskResult.ok("handler completed");
      }
    };
    BatchPlatformClient client = BatchPlatformClient.builder(
            platform.configFor("tenant-a", "worker-a"))
        .register(handler)
        .build();
    client.start();
    try {
      platform.dispatch(TaskDispatchMessageBuilder.dispatch("testkit_report_path")
          .tenantId("tenant-a")
          .taskId(taskId)
          .build());
      assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
      String body = """
          {"tenantId":"tenant-a","workerId":"worker-a",
          "partitionInvocationId":"%s",%s"success":true,"message":"manual report"}
          """.formatted(invocation.get(), conflictingBodyId ? "\"taskId\":9999," : "");
      var request = HttpRequest.newBuilder(
              URI.create(platform.baseUrl() + "/internal/tasks/" + taskId + "/report"))
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body))
          .build();
      var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(platform.reports()).containsKey(taskId);
      assertThat(platform.reports().get(taskId).message()).isEqualTo("manual report");
      var repeated = HttpRequest.newBuilder(
              URI.create(platform.baseUrl() + "/internal/tasks/" + taskId + "/report"))
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(
              body.replace("manual report", "conflicting duplicate")))
          .build();
      assertThat(HttpClient.newHttpClient()
              .send(repeated, HttpResponse.BodyHandlers.ofString())
              .statusCode())
          .isEqualTo(200);
      assertThat(platform.reports().get(taskId).message()).isEqualTo("manual report");
    } finally {
      release.countDown();
      client.stop();
    }
  }

  /** 成功路径:handler 返回 ok + outputs,平台收到 success=true 的 report。 */
  @Test
  @DisplayName("handler 成功执行,5s 内收到成功 report")
  void handlerSucceeds_endToEnd(FakeBatchPlatform platform) {
    AtomicBoolean startedBeforeExecution = new AtomicBoolean();
    AtomicReference<String> seenInvocation = new AtomicReference<>();
    SdkTaskHandler handler = new SdkTaskHandler() {
      @Override
      public String taskType() {
        return "testkit_demo";
      }

      @Override
      public SdkTaskResult execute(SdkTaskContext ctx) {
        startedBeforeExecution.set(platform.nonIdempotentStarts().containsKey(ctx.taskId()));
        seenInvocation.set((String) ctx.runtimeAttributes().get("partitionInvocationId"));
        int rows = ((Number) ctx.parameters().getOrDefault("rows", 0)).intValue();
        return SdkTaskResult.ok("imported " + rows, Map.of("rows", rows));
      }
    };

    BatchPlatformClient client = BatchPlatformClient.builder(
            platform.configFor("tenant-a", "worker-a"))
        .register(handler)
        .build();
    client.start();
    try {
      platform.dispatch(TaskDispatchMessageBuilder.dispatch("testkit_demo")
          .tenantId("tenant-a")
          .taskId(101L)
          .runtimeAttribute("partitionInvocationId", "inv-stale-dispatch")
          .param("rows", 3)
          .build());

      RecordedReport report = platform.awaitReport(101L, Duration.ofSeconds(5));
      assertThat(report.success()).isTrue();
      assertThat(report.message()).isEqualTo("imported 3");
      assertThat(report.outputs()).containsEntry("rows", 3);
      assertThat(platform.claims()).contains(101L);
      assertThat(platform.registrations()).isNotEmpty();
      assertThat(startedBeforeExecution.get()).isTrue();
      String invocationId = seenInvocation.get();
      assertThat(invocationId).isNotBlank().isNotEqualTo("inv-stale-dispatch");
      assertThat(platform.nonIdempotentStarts()).containsEntry(101L, invocationId);
      verifyStartRejectsForeignIdentity(platform, invocationId);
    } finally {
      client.stop();
    }
  }

  @Test
  @DisplayName("无租户派单失败后不能留下可触发 500 的半成品")
  void invalidTenantDispatch_doesNotLeavePartialClaimState(FakeBatchPlatform platform)
      throws Exception {
    SdkTaskHandler handler = new SdkTaskHandler() {
      @Override
      public String taskType() {
        return "testkit_invalid";
      }

      @Override
      public SdkTaskResult execute(SdkTaskContext context) {
        return SdkTaskResult.ok("ok");
      }
    };
    BatchPlatformClient client = BatchPlatformClient.builder(
            platform.configFor("tenant-a", "worker-a"))
        .register(handler)
        .build();
    client.start();
    try {
      Throwable invalid = catchThrowable(
          () -> platform.dispatch(TaskDispatchMessageBuilder.dispatch("testkit_invalid")
              .tenantId(null)
              .taskId(9081L)
              .build()));
      assertThat(invalid).isNotNull();
      var http = HttpClient.newHttpClient();
      var request = HttpRequest.newBuilder(
              URI.create(platform.baseUrl() + "/internal/tasks/9081/claim"))
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(
              "{\"tenantId\":\"tenant-a\",\"workerId\":\"worker-a\"}"))
          .build();
      var response = http.send(request, HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).isEqualTo(409);
      assertThat(invalid).isInstanceOf(IllegalArgumentException.class);
    } finally {
      client.stop();
    }
  }

  @Test
  @DisplayName("已认领任务重复派发不改变原调用的载荷")
  void repeatedDispatch_doesNotReplaceClaimedPayload(FakeBatchPlatform platform) throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    SdkTaskHandler handler = new SdkTaskHandler() {
      @Override
      public String taskType() {
        return "testkit_repeat";
      }

      @Override
      public SdkTaskResult execute(SdkTaskContext context) {
        started.countDown();
        try {
          if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(interrupted);
        }
        return SdkTaskResult.ok("ok");
      }
    };
    BatchPlatformClient client = BatchPlatformClient.builder(
            platform.configFor("tenant-a", "worker-a"))
        .register(handler)
        .build();
    client.start();
    try {
      platform.dispatch(TaskDispatchMessageBuilder.dispatch("testkit_repeat")
          .tenantId("tenant-a")
          .taskId(9082L)
          .param("rows", 3)
          .build());
      assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
      platform.dispatch(TaskDispatchMessageBuilder.dispatch("testkit_repeat")
          .tenantId("tenant-a")
          .taskId(9082L)
          .param("rows", 9)
          .build());
      var request = HttpRequest.newBuilder(
              URI.create(platform.baseUrl() + "/internal/tasks/9082/claim"))
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(
              "{\"tenantId\":\"tenant-a\",\"workerId\":\"worker-a\"}"))
          .build();
      var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).isEqualTo(200);
      var mapper = new ObjectMapper();
      var body = mapper.readTree(response.body());
      assertThat(mapper.readTree(body.path("payload").asText()).path("rows").asInt())
          .isEqualTo(3);
      assertThat(body.path("partitionInvocationId").asText()).isNotBlank();
    } finally {
      release.countDown();
      client.stop();
    }
  }

  /** 失败路径:handler 抛异常,平台收到 success=false + errorCode 的 report。 */
  @Test
  @DisplayName("handler 抛异常,report 标记失败并带 errorCode")
  void handlerThrows_reportsFailure(FakeBatchPlatform platform) {
    AtomicBoolean startedBeforeExecution = new AtomicBoolean();
    SdkTaskHandler handler = new SdkTaskHandler() {
      @Override
      public String taskType() {
        return "testkit_boom";
      }

      @Override
      public SdkTaskResult execute(SdkTaskContext ctx) {
        startedBeforeExecution.set(platform.nonIdempotentStarts().containsKey(ctx.taskId()));
        throw new IllegalStateException("kaboom");
      }
    };

    BatchPlatformClient client = BatchPlatformClient.builder(
            platform.configFor("tenant-a", "worker-boom"))
        .register(handler)
        .build();
    client.start();
    try {
      platform.dispatch(TaskDispatchMessageBuilder.dispatch("testkit_boom")
          .tenantId("tenant-a")
          .taskId(202L)
          .build());

      RecordedReport report = platform.awaitReport(202L, Duration.ofSeconds(5));
      assertThat(report.success()).isFalse();
      // errorCode 词表统一(本次改动):未捕获 handler 异常统一 protocol 常量 EXECUTION_FAILED,
      // 原异常类名折进 message 保留可诊断性。
      assertThat(report.errorCode()).isEqualTo("EXECUTION_FAILED");
      assertThat(report.message()).contains("kaboom");
      assertThat(startedBeforeExecution.get()).isTrue();
      assertThat(platform.nonIdempotentStarts()).containsKey(202L);
    } finally {
      client.stop();
    }
  }

  private void verifyStartRejectsForeignIdentity(FakeBatchPlatform platform, String invocationId) {
    BatchPlatformClientConfig clientConfig = platform.configFor("tenant-a", "worker-a");
    PlatformHttpClient client = new PlatformHttpClient(clientConfig);
    try {
      Map<String, Object> foreignWorker = Map.of(
          "tenantId",
          "tenant-a",
          "workerId",
          "other-worker",
          "partitionInvocationId",
          invocationId);
      Map<String, Object> foreignTenant = Map.of(
          "tenantId",
          "other-tenant",
          "workerId",
          "worker-a",
          "partitionInvocationId",
          invocationId);
      Map<String, Object> staleInvocation = Map.of(
          "tenantId",
          "tenant-a",
          "workerId",
          "worker-a",
          "partitionInvocationId",
          "inv-stale-dispatch");
      assertStartRejected(client, foreignWorker);
      assertStartRejected(client, foreignTenant);
      assertStartRejected(client, staleInvocation);
      assertThat(platform.nonIdempotentStarts()).containsEntry(101L, invocationId);
    } finally {
      client.evictIdleConnections();
    }
  }

  private void assertStartRejected(PlatformHttpClient client, Map<String, Object> body) {
    Throwable failure = catchThrowable(() -> client.markNonIdempotentExecutionStarted(101L, body));
    assertThat(failure).isInstanceOf(PlatformHttpException.class);
    PlatformHttpException httpFailure = (PlatformHttpException) failure;
    int status = httpFailure.statusCode();
    assertThat(status).isEqualTo(409);
  }
}
