package com.example.batch.worker.imports.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.imports.domain.ImportJobContext;
import com.example.batch.worker.imports.domain.ImportPayload;
import com.example.batch.worker.imports.domain.ImportStageResult;
import com.example.batch.worker.imports.infrastructure.ImportRecordGovernanceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 2026-05-01 Fix-2 守护:验证 ParseStep 默认按 PARTITION_NO/COUNT 切分行的零重叠 + 全覆盖语义。
 *
 * <p>规则:{@code lineNo % partitionCount == partitionNo - 1}(lineNo 0-based,partitionNo 1-based)。
 *
 * <p>对照(每个用例都跑一遍):
 *
 * <ul>
 *   <li>{@link #shouldKeepAllRowsWhenPartitionCountIsOne} — count=1 应直通,parsedCount=totalCount
 *   <li>{@link #shouldKeepAllRowsWhenPartitionAwareParseDisabled} — template_config 关闭开关,直通
 *   <li>{@link #shouldSliceWithoutOverlapAcrossPartitions} — 3 partition 各自处理,行数不重叠且并集 = 总行数
 *   <li>{@link #shouldKeepAllRowsWhenPartitionNoOutOfRange} — partitionNo > count 警告 + 直通
 * </ul>
 */
class ParseStepPartitionSliceTest {

  private ParseStep parseStep;

  @BeforeEach
  void setUp() {
    PlatformFileRuntimeRepository runtimeRepository = mock(PlatformFileRuntimeRepository.class);
    ImportRecordGovernanceService recordGovernanceService =
        mock(ImportRecordGovernanceService.class);
    when(runtimeRepository.toLong(any())).thenReturn(1L);
    when(recordGovernanceService.withinThreshold(any())).thenReturn(true);
    when(recordGovernanceService.isSkippable(any())).thenReturn(false);
    parseStep = new ParseStep(new ObjectMapper(), runtimeRepository, recordGovernanceService);
  }

  // ── 直通场景 ──────────────────────────────────────────────────────────────

  @Test
  void shouldKeepAllRowsWhenPartitionCountIsOne() {
    String json = buildJsonArray(9);
    ImportJobContext context = buildContext(json, /*partitionNo*/ 1, /*partitionCount*/ 1, true);

    ImportStageResult result = parseStep.execute(context);

    assertThat(result.success()).isTrue();
    assertThat(context.getAttributes().get("totalCount")).isEqualTo(9L);
    assertThat(((Number) context.getAttributes().get("parsedCount")).longValue()).isEqualTo(9L);
    assertNdjsonLineCount(context, 9);
  }

  @Test
  void shouldKeepAllRowsWhenPartitionAwareParseDisabled() {
    String json = buildJsonArray(9);
    ImportJobContext context = buildContext(json, 2, 3, /*partitionAware*/ false);

    ImportStageResult result = parseStep.execute(context);

    assertThat(result.success()).isTrue();
    // 关闭后 totalCount = parsedCount = 9(完整文件,不切分)
    assertThat(context.getAttributes().get("totalCount")).isEqualTo(9L);
    assertNdjsonLineCount(context, 9);
  }

  // ── 切分场景 ──────────────────────────────────────────────────────────────

  @Test
  void shouldSliceWithoutOverlapAcrossPartitions() throws Exception {
    String json = buildJsonArray(9);

    // 跑 3 个 partition,各自捕获 NDJSON 行集合
    Set<String> partition1 = runAndCaptureRecords(json, 1, 3);
    Set<String> partition2 = runAndCaptureRecords(json, 2, 3);
    Set<String> partition3 = runAndCaptureRecords(json, 3, 3);

    // 各自处理 9/3 = 3 行
    assertThat(partition1).hasSize(3);
    assertThat(partition2).hasSize(3);
    assertThat(partition3).hasSize(3);

    // 两两不交(零重叠)
    assertThat(partition1).doesNotContainAnyElementsOf(partition2);
    assertThat(partition1).doesNotContainAnyElementsOf(partition3);
    assertThat(partition2).doesNotContainAnyElementsOf(partition3);

    // 并集 = 完整原始集(全覆盖)
    Set<String> union = new HashSet<>();
    union.addAll(partition1);
    union.addAll(partition2);
    union.addAll(partition3);
    assertThat(union).hasSize(9);
    Set<String> expected =
        IntStream.rangeClosed(1, 9)
            .mapToObj(i -> "C" + String.format("%03d", i))
            .collect(Collectors.toSet());
    Set<String> actualNos =
        union.stream()
            .map(ParseStepPartitionSliceTest::extractCustomerNo)
            .collect(Collectors.toSet());
    assertThat(actualNos).isEqualTo(expected);
  }

  @Test
  void shouldKeepAllRowsWhenPartitionNoOutOfRange() {
    String json = buildJsonArray(5);
    // partitionNo=4 超过 count=3 → 警告 + 直通
    ImportJobContext context = buildContext(json, 4, 3, true);

    ImportStageResult result = parseStep.execute(context);

    assertThat(result.success()).isTrue();
    assertThat(context.getAttributes().get("totalCount")).isEqualTo(5L);
    assertNdjsonLineCount(context, 5);
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  private Set<String> runAndCaptureRecords(String json, int partitionNo, int partitionCount) {
    ImportJobContext context = buildContext(json, partitionNo, partitionCount, true);
    ImportStageResult result = parseStep.execute(context);
    assertThat(result.success())
        .as("partition %d/%d should succeed", partitionNo, partitionCount)
        .isTrue();
    return readNdjsonLines(context);
  }

  private static String buildJsonArray(int n) {
    return "["
        + IntStream.rangeClosed(1, n)
            .mapToObj(
                i ->
                    "{\"customerNo\":\"C"
                        + String.format("%03d", i)
                        + "\",\"customerName\":\"Name"
                        + i
                        + "\"}")
            .collect(Collectors.joining(","))
        + "]";
  }

  private ImportJobContext buildContext(
      String rawPayload, int partitionNo, int partitionCount, boolean partitionAware) {
    ImportPayload importPayload =
        new ImportPayload(
            null,
            null,
            null,
            null,
            "JSON",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            rawPayload,
            null,
            null,
            null,
            null,
            Boolean.TRUE,
            Map.of());
    ImportJobContext context = new ImportJobContext();
    context.setTenantId("tenant-partition-slice");
    context.setJobCode("PARSE_PARTITION");
    context.setWorkerId("worker-1");
    context.setFileId("99");
    context.setRawPayload(rawPayload);
    Map<String, Object> attrs = new HashMap<>();
    attrs.put(PipelineRuntimeKeys.FILE_ID, 99L);
    attrs.put(PipelineRuntimeKeys.TASK_ID, 999L);
    attrs.put("importPayload", importPayload);
    Map<String, Object> templateConfig = new HashMap<>();
    templateConfig.put("jdbc_mapped_import", Map.of());
    if (!partitionAware) {
      templateConfig.put("partition_aware_parse", "false");
    }
    attrs.put(PipelineRuntimeKeys.TEMPLATE_CONFIG, templateConfig);
    attrs.put(PipelineRuntimeKeys.PARTITION_NO, partitionNo);
    attrs.put(PipelineRuntimeKeys.PARTITION_COUNT, partitionCount);
    context.setAttributes(attrs);
    return context;
  }

  private static Set<String> readNdjsonLines(ImportJobContext context) {
    Object path = context.getAttributes().get(PipelineRuntimeKeys.PARSED_RECORDS_PATH);
    if (path == null) {
      return Set.of();
    }
    try {
      List<String> lines = Files.lines(Path.of(path.toString())).filter(l -> !l.isBlank()).toList();
      return new HashSet<>(lines);
    } catch (Exception e) {
      throw new AssertionError("Could not read NDJSON: " + e.getMessage(), e);
    }
  }

  private static void assertNdjsonLineCount(ImportJobContext context, int expected) {
    assertThat(readNdjsonLines(context)).hasSize(expected);
  }

  private static String extractCustomerNo(String ndjsonLine) {
    int start = ndjsonLine.indexOf("\"customerNo\":\"");
    if (start < 0) {
      return null;
    }
    start += "\"customerNo\":\"".length();
    int end = ndjsonLine.indexOf('"', start);
    return end < 0 ? null : ndjsonLine.substring(start, end);
  }
}
