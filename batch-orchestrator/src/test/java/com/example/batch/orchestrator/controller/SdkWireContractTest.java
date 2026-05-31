package com.example.batch.orchestrator.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.dto.WorkerHeartbeatDto;
import com.example.batch.common.kafka.TaskDispatchMessage;
import com.example.batch.orchestrator.controller.TaskController.TaskClaimRequest;
import com.example.batch.orchestrator.controller.request.TaskExecutionReportDto;
import com.example.batch.sdk.wire.ClaimRequest;
import com.example.batch.sdk.wire.HeartbeatRequest;
import com.example.batch.sdk.wire.RegisterRequest;
import com.example.batch.sdk.wire.RenewRequest;
import com.example.batch.sdk.wire.ReportRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * SDK 端 wire DTO → 平台端 DTO 反序列化契约测试(Phase 0 §2.1 / 决策 #2)。
 *
 * <p>每条用例:
 *
 * <ol>
 *   <li>构造一个填齐字段的 SDK record
 *   <li>jackson 序列化为 JSON
 *   <li>反序列化为平台对应 DTO
 *   <li>断言关键字段值一致(防字段名漂移)
 * </ol>
 *
 * <p>**这测一旦红 → 协议字段被改名 / 删除,SDK / 平台两端必须同步**(plan §15.5 dual-rollout 纪律)。
 */
class SdkWireContractTest {

  private static final ObjectMapper MAPPER =
      new ObjectMapper().registerModule(new JavaTimeModule());

  // ─── /internal/workers/register ─────────────────────────────────────────

  @Test
  void registerRequestDeserializesToWorkerHeartbeatDto() throws Exception {
    RegisterRequest sdkSide =
        new RegisterRequest(
            "tenant-acme",
            "worker-1",
            "sdk-self-hosted",
            "RUNNING",
            "host-a",
            "10.0.0.1",
            "12345",
            Instant.parse("2026-05-31T10:00:00Z"),
            List.of("echo", "sleep"),
            3);

    WorkerHeartbeatDto platformSide =
        MAPPER.readValue(MAPPER.writeValueAsBytes(sdkSide), WorkerHeartbeatDto.class);

    assertThat(platformSide.tenantId()).isEqualTo("tenant-acme");
    assertThat(platformSide.workerCode()).isEqualTo("worker-1");
    assertThat(platformSide.workerGroup()).isEqualTo("sdk-self-hosted");
    assertThat(platformSide.status()).isEqualTo("RUNNING");
    assertThat(platformSide.hostName()).isEqualTo("host-a");
    assertThat(platformSide.hostIp()).isEqualTo("10.0.0.1");
    assertThat(platformSide.processId()).isEqualTo("12345");
    assertThat(platformSide.heartbeatAt()).isEqualTo(Instant.parse("2026-05-31T10:00:00Z"));
    assertThat(platformSide.capabilityTags()).containsExactly("echo", "sleep");
    assertThat(platformSide.currentLoad()).isEqualTo(3);
  }

  // ─── /internal/workers/{workerCode}/heartbeat ───────────────────────────

  @Test
  void heartbeatRequestDeserializesToWorkerHeartbeatDto() throws Exception {
    HeartbeatRequest sdkSide =
        new HeartbeatRequest(
            "tenant-acme",
            "worker-1",
            null,
            "RUNNING",
            null,
            null,
            null,
            Instant.parse("2026-05-31T10:05:00Z"),
            null,
            5);

    WorkerHeartbeatDto platformSide =
        MAPPER.readValue(MAPPER.writeValueAsBytes(sdkSide), WorkerHeartbeatDto.class);

    assertThat(platformSide.tenantId()).isEqualTo("tenant-acme");
    assertThat(platformSide.workerCode()).isEqualTo("worker-1");
    assertThat(platformSide.status()).isEqualTo("RUNNING");
    assertThat(platformSide.currentLoad()).isEqualTo(5);
    assertThat(platformSide.heartbeatAt()).isEqualTo(Instant.parse("2026-05-31T10:05:00Z"));
  }

  // ─── /internal/tasks/{taskId}/claim ─────────────────────────────────────

  @Test
  void claimRequestDeserializesToTaskClaimRequest() throws Exception {
    ClaimRequest sdkSide = new ClaimRequest("tenant-acme", "worker-1", "inv-789");

    TaskClaimRequest platformSide =
        MAPPER.readValue(MAPPER.writeValueAsBytes(sdkSide), TaskClaimRequest.class);

    assertThat(platformSide.tenantId()).isEqualTo("tenant-acme");
    assertThat(platformSide.workerId()).isEqualTo("worker-1");
    assertThat(platformSide.partitionInvocationId()).isEqualTo("inv-789");
  }

  // ─── /internal/tasks/{taskId}/renew ─────────────────────────────────────

  @Test
  void renewRequestDeserializesToTaskClaimRequest() throws Exception {
    // renew 复用 TaskClaimRequest schema(同字段集)
    RenewRequest sdkSide = new RenewRequest("tenant-acme", "worker-1", null);

    TaskClaimRequest platformSide =
        MAPPER.readValue(MAPPER.writeValueAsBytes(sdkSide), TaskClaimRequest.class);

    assertThat(platformSide.tenantId()).isEqualTo("tenant-acme");
    assertThat(platformSide.workerId()).isEqualTo("worker-1");
    assertThat(platformSide.partitionInvocationId()).isNull();
  }

  // ─── /internal/tasks/{taskId}/report ────────────────────────────────────

  @Test
  void reportRequestDeserializesToTaskExecutionReportDto() throws Exception {
    ReportRequest sdkSide =
        new ReportRequest(
            42L,
            "tenant-acme",
            "worker-1",
            "trace-abc",
            true,
            "OK",
            "done",
            "rows=5",
            null,
            "2026-05-31",
            Map.of("rows", 5),
            "inv-789",
            null,
            null);

    TaskExecutionReportDto platformSide =
        MAPPER.readValue(MAPPER.writeValueAsBytes(sdkSide), TaskExecutionReportDto.class);

    assertThat(platformSide.getTaskId()).isEqualTo(42L);
    assertThat(platformSide.getTenantId()).isEqualTo("tenant-acme");
    assertThat(platformSide.getWorkerId()).isEqualTo("worker-1");
    assertThat(platformSide.getTraceId()).isEqualTo("trace-abc");
    assertThat(platformSide.isSuccess()).isTrue();
    assertThat(platformSide.getCode()).isEqualTo("OK");
    assertThat(platformSide.getMessage()).isEqualTo("done");
    assertThat(platformSide.getResultSummary()).isEqualTo("rows=5");
    assertThat(platformSide.getHighWaterMarkOut()).isEqualTo("2026-05-31");
    assertThat(platformSide.getOutputs()).containsEntry("rows", 5);
    assertThat(platformSide.getPartitionInvocationId()).isEqualTo("inv-789");
  }

  @Test
  void reportRequestFailureCarriesErrorCodeAndResultSummary() throws Exception {
    ReportRequest sdkSide =
        new ReportRequest(
            42L,
            "tenant-acme",
            "worker-1",
            null,
            false,
            null,
            "handler failed",
            "boom",
            "IllegalStateException",
            null,
            null,
            null,
            "BIZ_ERROR",
            List.of(Map.of("code", "VERIFY_FAIL", "message", "row 3 invalid")));

    TaskExecutionReportDto platformSide =
        MAPPER.readValue(MAPPER.writeValueAsBytes(sdkSide), TaskExecutionReportDto.class);

    assertThat(platformSide.isSuccess()).isFalse();
    assertThat(platformSide.getMessage()).isEqualTo("handler failed");
    assertThat(platformSide.getErrorCode()).isEqualTo("IllegalStateException");
    assertThat(platformSide.getResultSummary()).isEqualTo("boom");
    assertThat(platformSide.getFailureClass()).isEqualTo("BIZ_ERROR");
    assertThat(platformSide.getVerifierFailures()).hasSize(1);
  }

  // ─── 字段命名陷阱回归 ───────────────────────────────────────────────────

  @Test
  void reportRequestUsesOutputsNotOutput() throws Exception {
    ReportRequest sdkSide =
        new ReportRequest(
            1L,
            "t",
            "w",
            null,
            true,
            null,
            null,
            null,
            null,
            null,
            Map.of("k", "v"),
            null,
            null,
            null);
    JsonNode tree = MAPPER.readTree(MAPPER.writeValueAsBytes(sdkSide));
    // 平台 TaskExecutionReportDto 字段名是 outputs(复数);若 SDK 写 output(单数)
    // 平台读不到 → 节点 output 永远空。本断言守门。
    assertThat(tree.has("outputs")).as("must serialize as 'outputs' (plural)").isTrue();
    assertThat(tree.has("output")).as("must NOT serialize as 'output' (singular)").isFalse();
  }

  @Test
  void reportRequestUsesErrorCodeNotErrorClass() throws Exception {
    ReportRequest sdkSide =
        new ReportRequest(
            1L,
            "t",
            "w",
            null,
            false,
            null,
            null,
            null,
            "RuntimeException",
            null,
            null,
            null,
            null,
            null);
    JsonNode tree = MAPPER.readTree(MAPPER.writeValueAsBytes(sdkSide));
    assertThat(tree.has("errorCode")).isTrue();
    assertThat(tree.has("errorClass")).as("废字段 errorClass 不应出现").isFalse();
  }

  // ─── Kafka payload 契约 ────────────────────────────────────────────────

  @Test
  void platformTaskDispatchMessageCarriesSchemaVersion() throws Exception {
    // 平台侧 TaskDispatchMessage 必须含 schemaVersion 字段(Phase 0 §2.1)。
    TaskDispatchMessage platform =
        new TaskDispatchMessage(
            "v2",
            "tenant-acme",
            10L,
            null,
            42L,
            "ji-01",
            "daily-report",
            "echo",
            null,
            "MEDIUM",
            "trace-abc",
            "idem-1",
            Instant.parse("2026-05-31T10:00:00Z"));
    JsonNode tree = MAPPER.readTree(MAPPER.writeValueAsBytes(platform));
    assertThat(tree.get("schemaVersion").asText()).isEqualTo("v2");

    // SDK 端反序列化能识别 schemaVersion 且认可 v2。
    com.example.batch.sdk.dispatcher.TaskDispatchMessage sdk =
        MAPPER.readValue(
            MAPPER.writeValueAsBytes(platform),
            com.example.batch.sdk.dispatcher.TaskDispatchMessage.class);
    assertThat(sdk.schemaVersion()).isEqualTo("v2");
    assertThat(sdk.isSchemaSupported()).isTrue();
  }
}
