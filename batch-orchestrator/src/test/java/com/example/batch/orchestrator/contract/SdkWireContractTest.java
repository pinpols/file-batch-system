package com.example.batch.orchestrator.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.dto.WorkerHeartbeatDto;
import com.example.batch.orchestrator.controller.TaskController;
import com.example.batch.orchestrator.controller.request.TaskExecutionReportDto;
import com.example.batch.sdk.wire.ClaimRequest;
import com.example.batch.sdk.wire.HeartbeatRequest;
import com.example.batch.sdk.wire.RegisterRequest;
import com.example.batch.sdk.wire.RenewRequest;
import com.example.batch.sdk.wire.ReportRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

/**
 * SDK wire DTO ↔ 平台 {@code /internal/*} controller body schema 契约测试(SDK 路线图 Phase 0)。
 *
 * <p>SDK 不直接 import 平台侧 DTO,本测在 batch-orchestrator(同时 test-scope 引 batch-worker-sdk)校验:
 *
 * <ol>
 *   <li>SDK 写出的 JSON 平台 DTO 能完整反序列化(必填字段全到位)
 *   <li>SDK record 字段名 ⊆ 平台 DTO 字段名(SDK 不发平台不认的字段)
 *   <li>平台 DTO 必填字段 ⊆ SDK record 字段名(SDK 没漏字段)
 * </ol>
 *
 * <p>故意改一个 DTO 字段名(SDK 侧 / 平台侧任一)→ 本测应立即 fail,CI 拦截漂移。
 *
 * <p>Phase 0 验收:AC-0.3 / AC-0.4 / AC-0.5(详见 {@code docs/plans/sdk-roadmap-2026-h2.md} §16.1)。
 */
class SdkWireContractTest {

  private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

  // ── 1. JSON round-trip(SDK record → JSON → 平台 DTO,关键字段不丢)──────────────

  @Test
  void registerRequest_roundtripsToWorkerHeartbeatDto() throws Exception {
    RegisterRequest sdk =
        new RegisterRequest(
            "tenant-a",
            "worker-1",
            "sdk-self-hosted",
            "RUNNING",
            "host-x",
            "10.0.0.1",
            "pid-42",
            Instant.parse("2026-05-31T10:00:00Z"),
            List.of("import", "export"),
            3);

    WorkerHeartbeatDto platform = jsonRoundtrip(sdk, WorkerHeartbeatDto.class);
    assertThat(platform.tenantId()).isEqualTo("tenant-a");
    assertThat(platform.workerCode()).isEqualTo("worker-1");
    assertThat(platform.workerGroup()).isEqualTo("sdk-self-hosted");
    assertThat(platform.status()).isEqualTo("RUNNING");
    assertThat(platform.hostName()).isEqualTo("host-x");
    assertThat(platform.hostIp()).isEqualTo("10.0.0.1");
    assertThat(platform.processId()).isEqualTo("pid-42");
    assertThat(platform.heartbeatAt()).isEqualTo(Instant.parse("2026-05-31T10:00:00Z"));
    assertThat(platform.capabilityTags()).containsExactly("import", "export");
    assertThat(platform.currentLoad()).isEqualTo(3);
  }

  @Test
  void heartbeatRequest_roundtripsToWorkerHeartbeatDto() throws Exception {
    HeartbeatRequest sdk =
        new HeartbeatRequest(
            "tenant-a", "worker-1", null, "RUNNING", null, null, null, Instant.now(), null, 2);
    WorkerHeartbeatDto platform = jsonRoundtrip(sdk, WorkerHeartbeatDto.class);
    assertThat(platform.tenantId()).isEqualTo("tenant-a");
    assertThat(platform.workerCode()).isEqualTo("worker-1");
    assertThat(platform.status()).isEqualTo("RUNNING");
    assertThat(platform.currentLoad()).isEqualTo(2);
  }

  @Test
  void claimRequest_roundtripsToTaskClaimRequest() throws Exception {
    ClaimRequest sdk = new ClaimRequest("tenant-a", "worker-1", "inv-7");
    TaskController.TaskClaimRequest platform =
        jsonRoundtrip(sdk, TaskController.TaskClaimRequest.class);
    assertThat(platform.tenantId()).isEqualTo("tenant-a");
    assertThat(platform.workerId()).isEqualTo("worker-1");
    assertThat(platform.partitionInvocationId()).isEqualTo("inv-7");
  }

  @Test
  void renewRequest_roundtripsToTaskClaimRequest() throws Exception {
    RenewRequest sdk = new RenewRequest("tenant-a", "worker-1", null);
    TaskController.TaskClaimRequest platform =
        jsonRoundtrip(sdk, TaskController.TaskClaimRequest.class);
    assertThat(platform.tenantId()).isEqualTo("tenant-a");
    assertThat(platform.workerId()).isEqualTo("worker-1");
    assertThat(platform.partitionInvocationId()).isNull();
  }

  @Test
  void reportRequest_roundtripsToTaskExecutionReportDto() throws Exception {
    ReportRequest sdk =
        new ReportRequest(
            42L,
            "tenant-a",
            "worker-1",
            "trace-xyz",
            true,
            "OK",
            "done",
            "summary",
            null,
            "2026-05-31",
            Map.of("rows", 100),
            "inv-7",
            null,
            List.of(Map.of("code", "v1", "message", "ok")));

    TaskExecutionReportDto platform = jsonRoundtrip(sdk, TaskExecutionReportDto.class);
    assertThat(platform.getTaskId()).isEqualTo(42L);
    assertThat(platform.getTenantId()).isEqualTo("tenant-a");
    assertThat(platform.getWorkerId()).isEqualTo("worker-1");
    assertThat(platform.getTraceId()).isEqualTo("trace-xyz");
    assertThat(platform.isSuccess()).isTrue();
    assertThat(platform.getCode()).isEqualTo("OK");
    assertThat(platform.getMessage()).isEqualTo("done");
    assertThat(platform.getResultSummary()).isEqualTo("summary");
    assertThat(platform.getHighWaterMarkOut()).isEqualTo("2026-05-31");
    assertThat(platform.getOutputs()).containsEntry("rows", 100);
    assertThat(platform.getPartitionInvocationId()).isEqualTo("inv-7");
    assertThat(platform.getVerifierFailures()).hasSize(1);
  }

  // ── 2. 字段集对齐(SDK ⊆ 平台,平台必填 ⊆ SDK)─────────────────────────────

  @Test
  void registerRequest_fieldsAreSubsetOfPlatformDto() {
    assertSdkFieldsSubsetOfPlatform(RegisterRequest.class, WorkerHeartbeatDto.class);
  }

  @Test
  void heartbeatRequest_fieldsAreSubsetOfPlatformDto() {
    assertSdkFieldsSubsetOfPlatform(HeartbeatRequest.class, WorkerHeartbeatDto.class);
  }

  @Test
  void claimRequest_fieldsMatchPlatformDto() {
    assertSdkFieldsSubsetOfPlatform(ClaimRequest.class, TaskController.TaskClaimRequest.class);
  }

  @Test
  void renewRequest_fieldsMatchPlatformDto() {
    assertSdkFieldsSubsetOfPlatform(RenewRequest.class, TaskController.TaskClaimRequest.class);
  }

  @Test
  void reportRequest_coversAllSdkSendableFields() {
    // ReportRequest 字段全部应该被平台 TaskExecutionReportDto 识别。
    // 平台是 @Data class(非 record),通过 Jackson tree 比对。
    JsonNode sdkJson =
        mapper.valueToTree(
            new ReportRequest(
                1L, "t", "w", "tr", true, "OK", "m", "s", "e", "h", Map.of(), "p", "fc",
                List.of()));
    JsonNode platformJson = mapper.valueToTree(new TaskExecutionReportDto());
    // 把 SDK 写出 keys 全部尝试 set 到平台 DTO,Jackson 不抛(ignoreUnknown true 也行)
    List<String> sdkKeys = fieldsOf(sdkJson);
    List<String> platformGetters = getterFieldNames(TaskExecutionReportDto.class);
    assertThat(platformGetters).as("平台 DTO 必须能接受所有 SDK 发出的 ReportRequest 字段").containsAll(sdkKeys);
  }

  // ── 3. 必填字段守护(平台必填 ⊆ SDK,防 SDK 漏字段)───────────────────────

  @Test
  void platformRequiredFields_areAllSendableFromSdk() {
    // 当前 contract:WorkerHeartbeatDto / TaskClaimRequest / TaskExecutionReportDto 都用
    // @JsonIgnoreProperties(ignoreUnknown=true) 或等价宽容反序列化,平台不强校验字段缺失;
    // 但 SDK 必须能发出平台所有"业务必需"字段,本测覆盖核心 4 个。
    List<String> heartbeatRequired = List.of("tenantId", "workerCode", "status", "heartbeatAt");
    assertThat(recordFieldNames(RegisterRequest.class)).containsAll(heartbeatRequired);
    assertThat(recordFieldNames(HeartbeatRequest.class)).containsAll(heartbeatRequired);

    assertThat(recordFieldNames(ClaimRequest.class)).containsAll(List.of("tenantId", "workerId"));
    assertThat(recordFieldNames(RenewRequest.class)).containsAll(List.of("tenantId", "workerId"));

    assertThat(recordFieldNames(ReportRequest.class))
        .containsAll(List.of("taskId", "tenantId", "workerId", "success"));
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  private <T> T jsonRoundtrip(Object sdk, Class<T> platformType) throws Exception {
    byte[] bytes = mapper.writeValueAsBytes(sdk);
    return mapper.readValue(bytes, platformType);
  }

  private static List<String> recordFieldNames(Class<?> rec) {
    if (!rec.isRecord()) {
      throw new IllegalArgumentException("not a record: " + rec);
    }
    RecordComponent[] comps = rec.getRecordComponents();
    return Arrays.stream(comps).map(RecordComponent::getName).toList();
  }

  private static List<String> getterFieldNames(Class<?> beanType) {
    return Arrays.stream(beanType.getMethods())
        .filter(m -> m.getParameterCount() == 0)
        .filter(
            m ->
                (m.getName().startsWith("get") && m.getName().length() > 3)
                    || (m.getName().startsWith("is") && m.getName().length() > 2))
        .map(m -> propName(m.getName()))
        .distinct()
        .toList();
  }

  private static String propName(String getterName) {
    String trimmed =
        getterName.startsWith("get") ? getterName.substring(3) : getterName.substring(2);
    return Character.toLowerCase(trimmed.charAt(0)) + trimmed.substring(1);
  }

  private static List<String> fieldsOf(JsonNode node) {
    return StreamSupport.stream(
            Spliterators.spliteratorUnknownSize(node.fieldNames(), Spliterator.ORDERED), false)
        .toList();
  }

  private void assertSdkFieldsSubsetOfPlatform(Class<?> sdkRecord, Class<?> platformDto) {
    List<String> sdkFields = recordFieldNames(sdkRecord);
    List<String> platformFields =
        platformDto.isRecord() ? recordFieldNames(platformDto) : getterFieldNames(platformDto);
    assertThat(platformFields)
        .as(
            "平台 %s 必须包含 SDK %s 的所有字段(防 SDK 发不被识别的字段)",
            platformDto.getSimpleName(), sdkRecord.getSimpleName())
        .containsAll(sdkFields);
  }
}
