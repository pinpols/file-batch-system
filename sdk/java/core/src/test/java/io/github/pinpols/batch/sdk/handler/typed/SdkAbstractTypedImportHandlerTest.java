package io.github.pinpols.batch.sdk.handler.typed;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.sdk.handler.SdkRowResult;
import io.github.pinpols.batch.sdk.task.SdkTaskContext;
import io.github.pinpols.batch.sdk.task.SdkTaskResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A.2 typed Import 模板基类单测 — 验证强类型入参反序列化 + 行流分批模板。 */
class SdkAbstractTypedImportHandlerTest {

  record ImportRequest(String sourcePath, int rows) {}

  record ImportResult(long imported) {}

  /** 测试用 typed Import handler:按入参 rows 生成行,记录 loadBatch 每批行数。 */
  static class RecordingTypedImport
      extends SdkAbstractTypedImportHandler<ImportRequest, ImportResult, Integer> {
    final List<Integer> loadBatchSizes = new ArrayList<>();
    final int batchSize;
    ImportRequest seen;
    int closeCalls;

    RecordingTypedImport(int batchSize) {
      this.batchSize = batchSize;
    }

    @Override
    public String taskType() {
      return "tenant_typed_import";
    }

    @Override
    protected int batchSize() {
      return batchSize;
    }

    @Override
    protected Stream<Integer> readRows(ImportRequest input, SdkTaskContext ctx) {
      this.seen = input;
      return IntStream.range(0, input.rows()).boxed().onClose(() -> closeCalls++);
    }

    @Override
    protected void loadBatch(ImportRequest input, SdkTaskContext ctx, List<Integer> batch) {
      loadBatchSizes.add(batch.size());
    }

    @Override
    protected ImportResult summarize(ImportRequest input, SdkRowResult counts) {
      return new ImportResult(counts.success());
    }
  }

  private static SdkTaskContext ctxWith(Map<String, Object> params) {
    return new SdkTaskContext("t1", "job-1", "ti-1", 7L, "w1", params, Map.of());
  }

  @Test
  @DisplayName("强类型入参反序列化 + 100 行 batchSize=30 → loadBatch 4 次,summarize 进 output")
  void shouldParseInputAndChunkBatches_whenRowsExceedBatchSize() {
    // 准备
    RecordingTypedImport handler = new RecordingTypedImport(30);

    // 执行
    SdkTaskResult result =
        handler.execute(ctxWith(Map.of("sourcePath", "/data/in.csv", "rows", 100)));

    // 断言
    assertThat(result.success()).isTrue();
    assertThat(handler.seen.sourcePath()).isEqualTo("/data/in.csv");
    assertThat(handler.loadBatchSizes).containsExactly(30, 30, 30, 10);
    assertThat(result.output()).containsEntry("imported", 100L);
    assertThat(handler.closeCalls).isEqualTo(1);
  }

  @Test
  @DisplayName("summarize 返 null → 走计数器 output")
  void shouldFallBackToCountsOutput_whenSummarizeNull() {
    // 准备
    RecordingTypedImport handler =
        new RecordingTypedImport(50) {
          @Override
          protected ImportResult summarize(ImportRequest input, SdkRowResult counts) {
            return null;
          }
        };

    // 执行
    SdkTaskResult result = handler.execute(ctxWith(Map.of("sourcePath", "/x", "rows", 10)));

    // 断言
    assertThat(result.success()).isTrue();
    assertThat(result.output()).containsEntry("success", 10L).containsEntry("total", 10L);
  }

  @Test
  @DisplayName("参数结构不匹配 → fail,不进 readRows")
  void shouldFail_whenParametersInvalid() {
    // 准备
    RecordingTypedImport handler = new RecordingTypedImport(30);

    // 执行
    SdkTaskResult result = handler.execute(ctxWith(Map.of("rows", "not-a-number")));

    // 断言
    assertThat(result.success()).isFalse();
    assertThat(result.message()).contains("invalid parameters for taskType=tenant_typed_import");
    assertThat(handler.seen).isNull();
    assertThat(handler.loadBatchSizes).isEmpty();
  }

  @Test
  @DisplayName("loadBatch 抛异常 → fail 且行流仍 close()")
  void shouldFailAndCloseStream_whenLoadBatchThrows() {
    // 准备
    RecordingTypedImport handler =
        new RecordingTypedImport(30) {
          @Override
          protected void loadBatch(ImportRequest input, SdkTaskContext ctx, List<Integer> batch) {
            throw new IllegalStateException("loadBatch boom");
          }
        };

    // 执行
    SdkTaskResult result = handler.execute(ctxWith(Map.of("sourcePath", "/x", "rows", 100)));

    // 断言
    assertThat(result.success()).isFalse();
    assertThat(handler.closeCalls).isEqualTo(1);
  }
}
