package io.github.pinpols.batch.console.domain.ops.web.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * wire 红线守护：ops 域类型化 response record 的 JSON key 必须与历史 Map 响应逐字一致。 覆盖嵌套 MyBatis 行 NON_NULL 省略、 动态
 * evidence 负载、Kafka lag 两种混合形态、Instant 归一。
 */
class OpsMapResponseJacksonTest {

  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

  @Test
  void configApprovalDetailShouldOmitNullApprovalColumnsButKeepApprovalKey() throws Exception {
    // 顶层 approval 键恒定（无审批时为 null）；嵌套 approval 行来自 MyBatis map，pending 时省略 reviewedBy 等 null 列。
    Map<String, Object> approvalRow = new LinkedHashMap<>();
    approvalRow.put("id", 100L);
    approvalRow.put("tenantId", "ta");
    approvalRow.put("releaseId", 7L);
    approvalRow.put("approvalStatus", "PENDING");
    approvalRow.put("requestedBy", "admin");
    approvalRow.put("requestedAt", Timestamp.from(Instant.parse("2026-07-11T02:00:00Z")));

    Map<String, Object> row = new LinkedHashMap<>();
    row.put("releaseId", 7L);
    row.put("tenantId", "ta");
    row.put("configType", "JOB");
    row.put("configKey", "job-a");
    row.put("configStatus", "PENDING_APPROVAL");
    row.put("approval", approvalRow);

    Map<String, Object> back = roundTrip(ConsoleConfigApprovalDetailResponse.from(row));

    assertThat(back)
        .containsOnlyKeys(
            "releaseId", "tenantId", "configType", "configKey", "configStatus", "approval");
    Map<String, Object> approval =
        mapper.convertValue(back.get("approval"), new TypeReference<>() {});
    assertThat(approval)
        .containsEntry("approvalStatus", "PENDING")
        .containsKey("requestedAt")
        .doesNotContainKey("reviewedBy")
        .doesNotContainKey("reviewedAt")
        .doesNotContainKey("reviewComment");
    // Timestamp → Instant 归一由 record 承载。
    assertThat(ConsoleConfigApprovalDetailResponse.from(row).approval().requestedAt())
        .isEqualTo(Instant.parse("2026-07-11T02:00:00Z"));
  }

  @Test
  void configApprovalDetailShouldKeepExplicitNullApprovalWhenAbsent() throws Exception {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("releaseId", 7L);
    row.put("tenantId", "ta");
    row.put("configType", "JOB");
    row.put("configKey", "job-a");
    row.put("configStatus", "DRAFT");
    row.put("approval", null);

    Map<String, Object> back = roundTrip(ConsoleConfigApprovalDetailResponse.from(row));
    assertThat(back).containsKey("approval").containsEntry("approval", null);
  }

  @Test
  void workerConsistencyShouldOmitNullWorkerGroupKey() throws Exception {
    Map<String, Object> nullGroupRow = new LinkedHashMap<>();
    nullGroupRow.put("totalWorkers", 2L);
    nullGroupRow.put("onlineWorkers", 1L);
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("onlineWorkers", 3L);
    row.put("healthy", true);
    row.put("workerGroups", List.of(nullGroupRow));

    Map<String, Object> back = roundTrip(ConsoleWorkerConsistencyResponse.from(row));
    Map<String, Object> group0 =
        mapper.convertValue(((List<?>) back.get("workerGroups")).get(0), new TypeReference<>() {});
    assertThat(group0).doesNotContainKey("workerGroup").containsEntry("totalWorkers", 2);
    assertThat(back).containsEntry("onlineWorkers", 3).containsEntry("healthy", true);
  }

  @Test
  void instanceDiagnosisShouldTypeSummaryAndKeepDynamicEvidence() throws Exception {
    Map<String, Object> instance = new LinkedHashMap<>();
    instance.put("id", 9L);
    instance.put("instanceNo", "INS-9");
    instance.put("jobCode", "JOB_A");
    instance.put("instanceStatus", "RUNNING");
    instance.put("queueCode", null); // 显式 null 键必须保留（LinkedHashMap 透传）

    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("partitionStatusCounts", List.of(Map.of("status", "RUNNING", "count", 2L)));
    summary.put("taskStatusCounts", List.of());
    summary.put(
        "outboxStatusCounts",
        List.of(
            Map.of(
                "status",
                "NEW",
                "count",
                1L,
                "oldest",
                Timestamp.from(Instant.parse("2026-07-11T02:00:00Z")))));
    summary.put("onlineWorkersForGroup", 3L);

    Map<String, Object> finding = new LinkedHashMap<>();
    finding.put("severity", "WARN");
    finding.put("reasonCode", "NO_ONLINE_WORKER_FOR_GROUP");
    finding.put("message", "msg");
    finding.put("suggestedActions", List.of("do x"));
    finding.put("evidence", Map.of("workerGroup", "grp-1", "activeTasks", 5L));

    Map<String, Object> row = new LinkedHashMap<>();
    row.put("tenantId", "ta");
    row.put("jobInstanceId", 9L);
    row.put("healthy", false);
    row.put("instance", instance);
    row.put("summary", summary);
    row.put("findings", List.of(finding));

    Map<String, Object> back = roundTrip(ConsoleInstanceDiagnosisResponse.from(row));

    Map<String, Object> backInstance =
        mapper.convertValue(back.get("instance"), new TypeReference<>() {});
    assertThat(backInstance).containsKey("queueCode").containsEntry("queueCode", null);
    assertThat(backInstance).containsEntry("instanceNo", "INS-9");

    Map<String, Object> backSummary =
        mapper.convertValue(back.get("summary"), new TypeReference<>() {});
    Map<String, Object> outbox0 =
        mapper.convertValue(
            ((List<?>) backSummary.get("outboxStatusCounts")).get(0), new TypeReference<>() {});
    // MyBatis 行省略 null 列：该 outbox 行无 newest → 键不得出现。
    assertThat(outbox0).containsKeys("status", "count", "oldest").doesNotContainKey("newest");
    assertThat(outbox0).containsEntry("status", "NEW").containsEntry("count", 1);

    Map<String, Object> backFinding =
        mapper.convertValue(((List<?>) back.get("findings")).get(0), new TypeReference<>() {});
    assertThat(backFinding)
        .containsKeys("severity", "reasonCode", "message", "suggestedActions", "evidence");
    Map<String, Object> evidence =
        mapper.convertValue(backFinding.get("evidence"), new TypeReference<>() {});
    assertThat(evidence).containsEntry("workerGroup", "grp-1").containsEntry("activeTasks", 5);
  }

  @Test
  void kafkaConsumerLagShouldPreserveNormalAndErrorShapes() throws Exception {
    Map<String, Object> normal = new LinkedHashMap<>();
    normal.put("groupId", "batch-a");
    normal.put("totalLag", 5L);
    normal.put("partitionCount", 2);
    normal.put(
        "partitionsWithLag",
        List.of(
            Map.of(
                "topic",
                "t1",
                "partition",
                0,
                "committedOffset",
                10L,
                "endOffset",
                15L,
                "lag",
                5L)));

    Map<String, Object> normalBack = roundTrip(ConsoleKafkaConsumerLagResponse.from(normal));
    assertThat(normalBack)
        .containsOnlyKeys("groupId", "totalLag", "partitionCount", "partitionsWithLag")
        .doesNotContainKey("error");
    assertThat(normalBack).containsEntry("totalLag", 5).containsEntry("partitionCount", 2);

    // 无积压组：不含 partitionsWithLag 键。
    Map<String, Object> noLag = new LinkedHashMap<>();
    noLag.put("groupId", "batch-b");
    noLag.put("totalLag", 0L);
    noLag.put("partitionCount", 1);
    Map<String, Object> noLagBack = roundTrip(ConsoleKafkaConsumerLagResponse.from(noLag));
    assertThat(noLagBack)
        .containsOnlyKeys("groupId", "totalLag", "partitionCount")
        .doesNotContainKey("partitionsWithLag");

    // 出错条目：仅 {groupId,error}。
    Map<String, Object> error = new LinkedHashMap<>();
    error.put("groupId", "batch-c");
    error.put("error", "boom");
    Map<String, Object> errorBack = roundTrip(ConsoleKafkaConsumerLagResponse.from(error));
    assertThat(errorBack).containsOnlyKeys("groupId", "error").containsEntry("error", "boom");
  }

  @Test
  void forensicExportShouldKeepNullDownloadUrlKey() throws Exception {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("exportId", "exp-1");
    row.put("status", "COMPLETED");
    row.put("storagePath", "/tmp/exp-1.zip");
    row.put("fileSizeBytes", 2048L);
    row.put("sha256", "abc");
    row.put("downloadUrl", null);

    Map<String, Object> back = roundTrip(ConsoleForensicExportResponse.from(row));
    assertThat(back)
        .containsKeys(
            "exportId", "status", "storagePath", "fileSizeBytes", "sha256", "downloadUrl");
    assertThat(back).containsEntry("downloadUrl", null).containsEntry("fileSizeBytes", 2048);
  }

  @Test
  void shedLockStatusShouldTypeNestedLockEntries() throws Exception {
    Map<String, Object> lock = new LinkedHashMap<>();
    lock.put("name", "job-scheduler");
    lock.put("lockUntil", Instant.parse("2026-07-11T02:05:00Z"));
    lock.put("lockedAt", Instant.parse("2026-07-11T02:00:00Z"));
    lock.put("lockedBy", "node-1");
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("totalLocks", 1);
    row.put("activeLocks", 1L);
    row.put("locks", List.of(lock));

    Map<String, Object> back = roundTrip(ConsoleShedLockStatusResponse.from(row));
    assertThat(back).containsEntry("totalLocks", 1).containsEntry("activeLocks", 1);
    Map<String, Object> lock0 =
        mapper.convertValue(((List<?>) back.get("locks")).get(0), new TypeReference<>() {});
    assertThat(lock0)
        .containsKeys("name", "lockUntil", "lockedAt", "lockedBy")
        .containsEntry("name", "job-scheduler")
        .containsEntry("lockedBy", "node-1");
  }

  private Map<String, Object> roundTrip(Object value) throws Exception {
    return mapper.readValue(mapper.writeValueAsString(value), new TypeReference<>() {});
  }
}
