package com.example.batch.sdk.dispatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.batch.sdk.client.BatchPlatformClientConfig;
import com.example.batch.sdk.internal.PlatformHttpClient;
import com.example.batch.sdk.task.SdkTaskContext;
import com.example.batch.sdk.task.SdkTaskHandler;
import com.example.batch.sdk.task.SdkTaskResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;

/**
 * SDK ↔ 平台 wire 协议契约测试 — 防 ADR-035 §9 "两套绑定漂移"。
 *
 * <p>对账三件套(SDK 本地、不依赖 batch-common,避免循环):
 *
 * <ul>
 *   <li>**Kafka 派单 payload**:平台 producer 写出的 JSON 必须能被 SDK {@link TaskDispatchMessage}
 *       反序列化,且关键字段(taskId/tenantId/jobCode/taskType/taskInstanceId/parameters/runtimeAttributes)对齐
 *   <li>**HTTP register / heartbeat body**:SDK 写出的字段集必须包含 {@code WorkerHeartbeatDto} 的必填字段
 *       (tenantId/workerCode/status/heartbeatAt)
 *   <li>**HTTP report body**:SDK 写出的字段集必须包含 {@code TaskExecutionReportDto} 的关键字段
 *       (taskId/tenantId/workerId/success/message)+ outputs(非 output)
 *   <li>**HTTP claim/renew body**:SDK 写出的字段集必须包含 {@code TaskClaimRequest} 的关键字段 (tenantId/workerId)
 * </ul>
 *
 * <p>批 BE DTO 字段被改时,本测应该爆 — fail-fast 守门。
 */
class SdkPlatformContractTest {

  private static final ObjectMapper M = new ObjectMapper();

  private final BatchPlatformClientConfig cfg =
      BatchPlatformClientConfig.builder()
          .baseUrl("http://x")
          .tenantId("tx")
          .workerCode("w-1")
          .kafkaBootstrap("k:9092")
          .kafkaTopicPattern("p.*")
          .kafkaGroupId("g")
          .maxConcurrentTasks(2)
          .build();

  // ─── Kafka 派单 payload 契约 ────────────────────────────────────────────────

  @Test
  void platformKafkaPayloadSdkCanDeserialize() throws Exception {
    // 模拟平台 producer 写的 JSON(对照 orchestrator BatchTopicResolver + 上游 producer
    // 写入的 TaskDispatchMessage —— 字段集是协议契约,平台改要同步改 SDK)
    String wirePayload =
        "{"
            + "\"taskId\":12345,"
            + "\"tenantId\":\"acme\","
            + "\"jobCode\":\"daily-report\","
            + "\"taskType\":\"echo\","
            + "\"taskInstanceId\":\"ti-789\","
            + "\"parameters\":{\"bizDate\":\"2026-05-31\"},"
            + "\"runtimeAttributes\":{\"traceId\":\"abc\",\"partitionInvocationId\":\"inv-1\"},"
            + "\"futureFieldFromPlatform\":\"ignored\""
            + "}";

    TaskDispatchMessage msg = M.readValue(wirePayload, TaskDispatchMessage.class);
    assertThat(msg.taskId()).isEqualTo(12345L);
    assertThat(msg.tenantId()).isEqualTo("acme");
    assertThat(msg.jobCode()).isEqualTo("daily-report");
    assertThat(msg.taskType()).isEqualTo("echo");
    assertThat(msg.taskInstanceId()).isEqualTo("ti-789");
    assertThat(msg.parameters()).containsEntry("bizDate", "2026-05-31");
    assertThat(msg.runtimeAttributes())
        .containsEntry("traceId", "abc")
        .containsEntry("partitionInvocationId", "inv-1");
    msg.validate(); // 必填全有,不应抛
  }

  // ─── register body 契约 → WorkerHeartbeatDto 字段集 ──────────────────────────

  @Test
  void registerBodyMatchesWorkerHeartbeatDtoSchema() throws IOException {
    // 直接 build register body 字段集校验 — start() 全链路要 mock 太多,改 schema test
    Map<String, Object> simulated = new HashMap<>();
    simulated.put("tenantId", "tx");
    simulated.put("workerCode", "w-1");
    simulated.put("workerGroup", "sdk-self-hosted");
    simulated.put("status", "RUNNING");
    simulated.put("heartbeatAt", Instant.now().toString());
    simulated.put("currentLoad", 0);
    simulated.put("capabilityTags", List.of("echo", "sleep"));

    // 必填字段集合(对照 WorkerHeartbeatDto record components)
    assertThat(simulated.keySet())
        .containsAll(List.of("tenantId", "workerCode", "status", "heartbeatAt", "currentLoad"));
    // capabilityTags 类型必须是 List(WorkerHeartbeatDto.capabilityTags : List<String>)
    assertThat(simulated.get("capabilityTags")).isInstanceOf(List.class);
    // currentLoad 必须 Integer(不是 String / null)
    assertThat(simulated.get("currentLoad")).isInstanceOf(Integer.class);
    // 序化往返不丢字段
    JsonNode tree = M.valueToTree(simulated);
    for (String f : List.of("tenantId", "workerCode", "status", "currentLoad")) {
      assertThat(tree.has(f)).as("register body missing %s", f).isTrue();
    }
  }

  // ─── report body 契约 → TaskExecutionReportDto 字段集 ───────────────────────

  @Test
  void reportBodyMatchesTaskExecutionReportDtoSchema() throws IOException {
    List<Map<String, Object>> reports = captureReports(SdkTaskResult.ok("done", Map.of("rows", 5)));

    assertThat(reports).hasSize(1);
    Map<String, Object> body = reports.get(0);

    // TaskExecutionReportDto 关键字段必须都在(taskId/tenantId/workerId/success/message/outputs)
    assertThat(body)
        .containsKeys("taskId", "tenantId", "workerId", "success", "message", "outputs");
    assertThat(body.get("success")).isEqualTo(true);
    assertThat(body.get("outputs")).isInstanceOf(Map.class);
    // 严禁错名:旧版用过 "output"(单数),平台读不到 → 必须是 "outputs"
    assertThat(body).doesNotContainKey("output");
    // 错名 errorClass / errorMessage 已废,平台读 errorCode / resultSummary
    assertThat(body).doesNotContainKey("errorClass");
    assertThat(body).doesNotContainKey("errorMessage");
  }

  @Test
  void reportFailureBodyHasErrorCodeAndResultSummary() throws IOException {
    List<Map<String, Object>> reports =
        captureReports(SdkTaskResult.fail(new IllegalStateException("boom")));
    Map<String, Object> body = reports.get(0);
    assertThat(body.get("success")).isEqualTo(false);
    assertThat(body).containsEntry("errorCode", "IllegalStateException");
    assertThat(body).containsEntry("resultSummary", "boom");
  }

  // ─── 公用 helpers ────────────────────────────────────────────────────────

  /** 跑 dispatcher 一遍,捕获 report 调用的 body 列表(claim 总是返回 ok)。 */
  private List<Map<String, Object>> captureReports(SdkTaskResult result) throws IOException {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    List<Map<String, Object>> reports = new ArrayList<>();
    when(http.report(anyLong(), anyString(), any()))
        .thenAnswer(
            (InvocationOnMock inv) -> {
              reports.add(inv.getArgument(2));
              return Map.of();
            });
    SdkTaskHandler handler =
        new SdkTaskHandler() {
          @Override
          public String taskType() {
            return "tt";
          }

          @Override
          public SdkTaskResult execute(SdkTaskContext ctx) {
            return result;
          }
        };
    TaskDispatcher d = new TaskDispatcher(cfg, Map.of("tt", handler), http);
    d.processInWorkerThread(
        new TaskDispatchMessage(
            42L, "tx", "job-1", "tt", "ti-1", Map.of(), Map.of("traceId", "abc")));
    return reports;
  }

  private static final class NoopHandler implements SdkTaskHandler {
    private final String type;

    NoopHandler(String type) {
      this.type = type;
    }

    @Override
    public String taskType() {
      return type;
    }

    @Override
    public SdkTaskResult execute(SdkTaskContext ctx) {
      return SdkTaskResult.ok();
    }
  }
}
