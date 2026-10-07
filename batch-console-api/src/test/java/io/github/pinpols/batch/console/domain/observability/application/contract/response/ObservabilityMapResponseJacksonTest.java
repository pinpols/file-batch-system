package io.github.pinpols.batch.console.domain.observability.application.contract.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.console.shared.view.ConsolePipelineProgressItemResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * wire 红线守护：observability 域类型化 response record 的 JSON key 必须与历史 Map 响应逐字一致。 覆盖动态维度键映射 （byStatus
 * additionalProperties）、嵌套列表、NON_NULL 省略、Instant 归一。
 */
@DisplayName("可观测域响应记录序列化契约: 动态维度键, 嵌套列表与空值键省略")
class ObservabilityMapResponseJacksonTest {

  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

  @Test
  @DisplayName("作业统计保留按状态动态生成的键与嵌套趋势列表, 数值按原样输出")
  void shouldKeepDynamicStatusKeysAndNestedTrend_whenJobStatsRowMapped() throws Exception {
    Map<String, Object> byStatus = new LinkedHashMap<>();
    byStatus.put("SUCCESS", 8L);
    byStatus.put("FAILED", 2L);
    Map<String, Object> row = Map.of(
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
  @DisplayName("执行进度显式写入的结束时间为空时仍保留该键, 并还原开始时间")
  void shouldPreserveNullTemporalKeys_whenExecutionProgressRowMapped() throws Exception {
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
  @DisplayName("超期合规统计中平均时长为空时保留该键, 其余计数按原值输出")
  void shouldKeepNullAvgDurationKey_whenSlaComplianceRowMapped() throws Exception {
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
  @DisplayName("系统参数命中时输出键与值, 未命中时只输出参数键")
  void shouldOmitValueKey_whenSystemParameterValueMissing() throws Exception {
    // 命中：{key,value}；未命中：仅 {key}（NON_NULL 省略 value）。
    Map<String, Object> hit = roundTrip(ConsoleSystemParameterValueResponse.of("k1", "v1"));
    assertThat(hit).containsOnlyKeys("key", "value").containsEntry("value", "v1");

    Map<String, Object> miss = roundTrip(ConsoleSystemParameterValueResponse.of("k1", null));
    assertThat(miss).containsOnlyKeys("key").doesNotContainKey("value");
  }

  @Test
  @DisplayName("流水线进度中总行数提示为空时仍保留该键, 其余字段按原值输出")
  void shouldKeepNullTotalRowsHintKey_whenPipelineProgressSerialized() throws Exception {
    // 透传自 orchestrator record（无 NON_NULL），totalRowsHint 为 null 时显式保留键。
    Map<String, Object> back = roundTrip(new ConsolePipelineProgressItemResponse(
        "LOAD", 100L, null, Instant.parse("2026-07-11T02:00:00Z")));

    assertThat(back)
        .containsOnlyKeys("stageCode", "rowsProcessed", "totalRowsHint", "heartbeatAt")
        .containsEntry("stageCode", "LOAD")
        .containsEntry("totalRowsHint", null)
        .containsEntry("rowsProcessed", 100);
  }

  private Map<String, Object> roundTrip(Object value) throws Exception {
    return mapper.readValue(mapper.writeValueAsString(value), new TypeReference<>() {});
  }
}
