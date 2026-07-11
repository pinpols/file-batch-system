package io.github.pinpols.batch.console.domain.observability.web.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * wire 红线守护：observability 域类型化 response record 的 JSON key 必须与历史 Map 响应逐字一致。 覆盖动态维度键映射 （byStatus
 * additionalProperties）、嵌套列表、NON_NULL 省略、Instant 归一。
 */
class ObservabilityMapResponseJacksonTest {

  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

  @Test
  void jobStatsShouldKeepDynamicByStatusKeysAndNestedTrend() throws Exception {
    Map<String, Object> byStatus = new LinkedHashMap<>();
    byStatus.put("SUCCESS", 8L);
    byStatus.put("FAILED", 2L);
    Map<String, Object> row =
        Map.of(
            "byStatus",
            byStatus,
            "total",
            10L,
            "dailyTrend",
            List.of(Map.of("day", "2026-07-11", "status", "SUCCESS", "count", 8L)));

    Map<String, Object> back = roundTrip(ConsoleJobStatsResponse.from(row));

    assertThat(back).containsOnlyKeys("byStatus", "total", "dailyTrend");
    assertThat(
            mapper.convertValue(back.get("byStatus"), new TypeReference<Map<String, Object>>() {}))
        .containsEntry("SUCCESS", 8)
        .containsEntry("FAILED", 2);
    assertThat(back).containsEntry("total", 10);
    Map<String, Object> trend0 =
        mapper.convertValue(((List<?>) back.get("dailyTrend")).get(0), new TypeReference<>() {});
    assertThat(trend0).containsOnlyKeys("day", "status", "count");
    assertThat(trend0).containsEntry("day", "2026-07-11").containsEntry("status", "SUCCESS");
  }

  @Test
  void executionProgressShouldPreserveNullTemporalKeys() throws Exception {
    // service 用 LinkedHashMap 显式 put startedAt/finishedAt（可为 null）→ 键必须保留（不加 NON_NULL）。
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", 9L);
    row.put("jobCode", "JOB_A");
    row.put("instanceNo", "INS-9");
    row.put("instanceStatus", "RUNNING");
    row.put("expectedPartitions", 4);
    row.put("successPartitions", 2);
    row.put("failedPartitions", 0);
    row.put("completedPartitions", 2);
    row.put("progressPercent", 50L);
    row.put("startedAt", Instant.parse("2026-07-11T02:00:00Z"));
    row.put("finishedAt", null);

    Map<String, Object> back = roundTrip(ConsoleExecutionProgressResponse.from(row));

    assertThat(back)
        .containsKeys(
            "id",
            "jobCode",
            "instanceNo",
            "instanceStatus",
            "expectedPartitions",
            "successPartitions",
            "failedPartitions",
            "completedPartitions",
            "progressPercent",
            "startedAt",
            "finishedAt");
    assertThat(back).containsEntry("progressPercent", 50).containsEntry("finishedAt", null);
    // Instant 归一由 record 承载（JSON 表示由生产 ObjectMapper 决定，此处仅校验键集与转换）。
    assertThat(ConsoleExecutionProgressResponse.from(row).startedAt())
        .isEqualTo(Instant.parse("2026-07-11T02:00:00Z"));
  }

  @Test
  void slaComplianceShouldKeepNullAvgDurationKey() throws Exception {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("breached", 1L);
    row.put("onTime", 9L);
    row.put("totalWithSla", 10L);
    row.put("avgDurationSeconds", null);
    row.put("dailyTrend", List.of(Map.of("day", "2026-07-11", "breached", 1L, "onTime", 9L)));

    Map<String, Object> back = roundTrip(ConsoleSlaComplianceResponse.from(row));

    assertThat(back).containsKey("avgDurationSeconds").containsEntry("avgDurationSeconds", null);
    assertThat(back).containsEntry("breached", 1).containsEntry("totalWithSla", 10);
  }

  @Test
  void systemParameterValueShouldOmitValueWhenMissing() throws Exception {
    // 命中：{key,value}；未命中：仅 {key}（NON_NULL 省略 value）。
    Map<String, Object> hit = roundTrip(ConsoleSystemParameterValueResponse.of("k1", "v1"));
    assertThat(hit).containsOnlyKeys("key", "value").containsEntry("value", "v1");

    Map<String, Object> miss = roundTrip(ConsoleSystemParameterValueResponse.of("k1", null));
    assertThat(miss).containsOnlyKeys("key").doesNotContainKey("value");
  }

  @Test
  void pipelineProgressShouldKeepNullTotalRowsHintKey() throws Exception {
    // 透传自 orchestrator record（无 NON_NULL），totalRowsHint 为 null 时显式保留键。
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("workerCode", "w-1");
    row.put("rowsProcessed", 100L);
    row.put("totalRowsHint", null);
    row.put("heartbeatAt", Instant.parse("2026-07-11T02:00:00Z"));

    Map<String, Object> back = roundTrip(ConsolePipelineProgressItemResponse.from(row));

    assertThat(back).containsKeys("workerCode", "rowsProcessed", "totalRowsHint", "heartbeatAt");
    assertThat(back).containsEntry("totalRowsHint", null).containsEntry("rowsProcessed", 100);
  }

  private Map<String, Object> roundTrip(Object value) throws Exception {
    return mapper.readValue(mapper.writeValueAsString(value), new TypeReference<>() {});
  }
}
