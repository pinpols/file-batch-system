package com.example.batch.orchestrator.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.dto.WorkerHeartbeatDto;
import com.example.batch.common.dto.WorkerTaskTypeDescriptorDto;
import com.example.batch.common.kafka.TaskDispatchMessage;
import com.example.batch.orchestrator.controller.TaskController.TaskClaimRequest;
import com.example.batch.orchestrator.controller.request.TaskExecutionReportDto;
import com.example.batch.orchestrator.controller.request.TaskHeartbeatRequest;
import com.example.batch.sdk.task.SdkTaskTypeDescriptor;
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
            "build-9",
            "sdk-2.1.0",
            Instant.parse("2026-05-31T10:00:00Z"),
            List.of("echo", "sleep"),
            3,
            null,
            "v2");

    WorkerHeartbeatDto platformSide =
        MAPPER.readValue(MAPPER.writeValueAsBytes(sdkSide), WorkerHeartbeatDto.class);

    assertThat(platformSide.protocolVersion()).isEqualTo("v2");
    assertThat(platformSide.tenantId()).isEqualTo("tenant-acme");
    assertThat(platformSide.workerCode()).isEqualTo("worker-1");
    assertThat(platformSide.workerGroup()).isEqualTo("sdk-self-hosted");
    assertThat(platformSide.status()).isEqualTo("RUNNING");
    assertThat(platformSide.hostName()).isEqualTo("host-a");
    assertThat(platformSide.hostIp()).isEqualTo("10.0.0.1");
    assertThat(platformSide.processId()).isEqualTo("12345");
    assertThat(platformSide.buildId()).isEqualTo("build-9");
    assertThat(platformSide.sdkVersion()).isEqualTo("sdk-2.1.0");
    assertThat(platformSide.heartbeatAt()).isEqualTo(Instant.parse("2026-05-31T10:00:00Z"));
    assertThat(platformSide.capabilityTags()).containsExactly("echo", "sleep");
    assertThat(platformSide.currentLoad()).isEqualTo(3);
    assertThat(platformSide.taskTypes()).isNull();
  }

  @Test
  void registerRequestTaskTypeDescriptorDeserializesAcrossBoundary() throws Exception {
    // Phase 3 M3.1:SDK SdkTaskTypeDescriptor → 平台 WorkerTaskTypeDescriptorDto 字段名 1:1。
    SdkTaskTypeDescriptor descriptor =
        new SdkTaskTypeDescriptor(
            "tenant_acme_import",
            "每日对账导入",
            "v1",
            Map.of("batchSize", 1000),
            Map.of("type", "object", "required", List.of("sourcePath")),
            List.of("bizDate"));
    RegisterRequest sdkSide =
        new RegisterRequest(
            "tenant-acme",
            "worker-1",
            "sdk-self-hosted",
            "RUNNING",
            null,
            null,
            null,
            null,
            null,
            Instant.parse("2026-05-31T10:00:00Z"),
            List.of("tenant_acme_import"),
            0,
            List.of(descriptor),
            "v1");

    WorkerHeartbeatDto platformSide =
        MAPPER.readValue(MAPPER.writeValueAsBytes(sdkSide), WorkerHeartbeatDto.class);

    assertThat(platformSide.taskTypes()).hasSize(1);
    WorkerTaskTypeDescriptorDto dto = platformSide.taskTypes().get(0);
    assertThat(dto.code()).isEqualTo("tenant_acme_import");
    assertThat(dto.displayName()).isEqualTo("每日对账导入");
    assertThat(dto.version()).isEqualTo("v1");
    assertThat(dto.defaults()).containsEntry("batchSize", 1000);
    assertThat(dto.inputSchema()).containsEntry("type", "object");
    assertThat(dto.templateVariables()).containsExactly("bizDate");
  }

  // ─── /internal/workers/{workerCode}/heartbeat ───────────────────────────

  @Test
  void heartbeatRequestDeserializesToWorkerHeartbeatDto() throws Exception {
    // Python SDK PR #320 / Java SDK fix/sdk-java-heartbeat-fields-align 对齐:
    // heartbeat 必须能携带 workerGroup / hostName / hostIp / processId / capabilityTags / buildId
    // 这 6 字段(register 时已上报,但平台回退降级 register 路径时需要它们消除字段丢失窗口)。
    HeartbeatRequest sdkSide =
        new HeartbeatRequest(
            "tenant-acme",
            "worker-1",
            "sdk-self-hosted",
            "RUNNING",
            "host-a",
            "10.0.0.1",
            "12345",
            "build-9",
            Instant.parse("2026-05-31T10:05:00Z"),
            List.of("echo", "sleep"),
            5,
            123_456L,
            1_000_000L);

    WorkerHeartbeatDto platformSide =
        MAPPER.readValue(MAPPER.writeValueAsBytes(sdkSide), WorkerHeartbeatDto.class);

    assertThat(platformSide.tenantId()).isEqualTo("tenant-acme");
    assertThat(platformSide.workerCode()).isEqualTo("worker-1");
    assertThat(platformSide.workerGroup()).isEqualTo("sdk-self-hosted");
    assertThat(platformSide.status()).isEqualTo("RUNNING");
    assertThat(platformSide.hostName()).isEqualTo("host-a");
    assertThat(platformSide.hostIp()).isEqualTo("10.0.0.1");
    assertThat(platformSide.processId()).isEqualTo("12345");
    assertThat(platformSide.buildId()).isEqualTo("build-9");
    assertThat(platformSide.capabilityTags()).containsExactly("echo", "sleep");
    assertThat(platformSide.currentLoad()).isEqualTo(5);
    assertThat(platformSide.heartbeatAt()).isEqualTo(Instant.parse("2026-05-31T10:05:00Z"));
    // 2026-06-03 pipeline stage 行级进度 wire(docs/design/pipeline-stage-progress-display.md)
    assertThat(platformSide.rowsProcessed()).isEqualTo(123_456L);
    assertThat(platformSide.totalRowsHint()).isEqualTo(1_000_000L);
  }

  @Test
  void heartbeatRequestPipelineProgressFieldsAreOptional() throws Exception {
    // 2026-06-03:rowsProcessed / totalRowsHint 是可选字段,LOAD/GENERATE 之外的 stage / 空闲态都是 null
    HeartbeatRequest sdkSide =
        new HeartbeatRequest(
            "tenant-acme",
            "worker-1",
            null,
            "IDLE",
            null,
            null,
            null,
            null,
            Instant.parse("2026-05-31T10:05:00Z"),
            null,
            0,
            null,
            null);
    String json = MAPPER.writeValueAsString(sdkSide);
    // NON_NULL 序列化策略下 null 字段不应出现
    assertThat(json).doesNotContain("rowsProcessed").doesNotContain("totalRowsHint");
    WorkerHeartbeatDto platformSide = MAPPER.readValue(json, WorkerHeartbeatDto.class);
    assertThat(platformSide.rowsProcessed()).isNull();
    assertThat(platformSide.totalRowsHint()).isNull();
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
  void renewRequestDeserializesToTaskHeartbeatRequest() throws Exception {
    // ORCH-P4-1:renew 绑定 TaskHeartbeatRequest;旧 SDK 仅发 3 字段,details=null 向前兼容。
    RenewRequest sdkSide = new RenewRequest("tenant-acme", "worker-1", null);

    TaskHeartbeatRequest platformSide =
        MAPPER.readValue(MAPPER.writeValueAsBytes(sdkSide), TaskHeartbeatRequest.class);

    assertThat(platformSide.tenantId()).isEqualTo("tenant-acme");
    assertThat(platformSide.workerId()).isEqualTo("worker-1");
    assertThat(platformSide.partitionInvocationId()).isNull();
    assertThat(platformSide.details()).isNull();
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
            Instant.parse("2026-05-31T10:00:00Z"),
            null);
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

  @Test
  void platformWorkerTypeBindsToSdkHandlerKeyOverWire() throws Exception {
    // 回归守护(workerType P0):平台派单消息以 JSON 字段名 `workerType` 承载路由键,SDK 必须把它绑定到
    // 自己的 handler 路由键 taskType()(@JsonProperty("workerType") @JsonAlias("taskType"))。
    // 任一端改名 / "瘦身"漏掉这个字段 → register 仍过,但 worker 收到派单后 handlers.get(taskType()) 拿到 null,
    // execute 静默失败(no handler for taskType=null)。SDK 全部 conformance / SampleTenantWorkerIT 都走
    // FakeBatchPlatform / TaskDispatchMessageBuilder,在进程内直接构造 SDK record、从不序列化平台的 `workerType`
    // JSON,所以测不到这条跨进程契约;sim 06 dispatch-execute 腿(真过 Kafka)是它的运行期对照。
    TaskDispatchMessage platform =
        new TaskDispatchMessage(
            "v2",
            "tenant-acme",
            10L,
            null,
            42L,
            "ji-01",
            "daily-report",
            "echo", // workerType:派单路由键,worker 据此选 handler
            null,
            "MEDIUM",
            "trace-abc",
            "idem-1",
            Instant.parse("2026-05-31T10:00:00Z"),
            null);

    // 平台序列化后的 JSON 字段名必须是 `workerType`(不是 `taskType`),这是契约方向锚点。
    JsonNode tree = MAPPER.readTree(MAPPER.writeValueAsBytes(platform));
    assertThat(tree.hasNonNull("workerType")).isTrue();
    assertThat(tree.get("workerType").asText()).isEqualTo("echo");

    // SDK 反序列化后,handler 路由键 taskType() 必须等于平台的 workerType —— 否则 handlers.get() 必拿 null。
    com.example.batch.sdk.dispatcher.TaskDispatchMessage sdk =
        MAPPER.readValue(
            MAPPER.writeValueAsBytes(platform),
            com.example.batch.sdk.dispatcher.TaskDispatchMessage.class);
    assertThat(sdk.taskType())
        .as("平台 workerType 必须绑定到 SDK handler 路由键 taskType()")
        .isEqualTo("echo");
    assertThat(sdk.taskId()).isEqualTo(42L);

    // 向后兼容:v1 旧字段名 `taskType` 经 @JsonAlias 仍能被 SDK 解析为同一路由键。
    com.example.batch.sdk.dispatcher.TaskDispatchMessage legacy =
        MAPPER.readValue(
            "{\"schemaVersion\":\"v1\",\"taskId\":7,\"taskType\":\"echo\"}",
            com.example.batch.sdk.dispatcher.TaskDispatchMessage.class);
    assertThat(legacy.taskType()).isEqualTo("echo");
  }
}
