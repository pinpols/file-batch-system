package com.example.batch.sdk.handler;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.sdk.task.SdkTaskContext;
import com.example.batch.sdk.task.SdkTaskResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-036 Import 模板基类单测 — 走基类 execute 整条模板序。 */
class SdkAbstractImportHandlerTest {

  private static final SdkTaskContext CTX =
      new SdkTaskContext("tx", "job", "ti", 1L, "w-1", Map.of(), Map.of());

  /** 测试用 Import handler:rows 模拟读取,记录 loadBatch 调用次数与每批行数。 */
  private static final class RecordingImportHandler extends SdkAbstractImportHandler<Integer> {
    private final List<Integer> rows;
    private final int batchSize;
    private final boolean failOnOpen;
    private final boolean failOnRead;
    private final boolean failOnLoad;
    final AtomicInteger openCalls = new AtomicInteger();
    final AtomicInteger readCalls = new AtomicInteger();
    final AtomicInteger streamCloseCalls = new AtomicInteger();
    final List<Integer> loadBatchSizes = new ArrayList<>();

    RecordingImportHandler(
        List<Integer> rows,
        int batchSize,
        boolean failOnOpen,
        boolean failOnRead,
        boolean failOnLoad) {
      this.rows = rows;
      this.batchSize = batchSize;
      this.failOnOpen = failOnOpen;
      this.failOnRead = failOnRead;
      this.failOnLoad = failOnLoad;
    }

    static RecordingImportHandler of(List<Integer> rows, int batchSize) {
      return new RecordingImportHandler(rows, batchSize, false, false, false);
    }

    @Override
    public String taskType() {
      return "test_import";
    }

    @Override
    protected int batchSize() {
      return batchSize;
    }

    @Override
    protected void openSource(SdkTaskContext ctx) throws Exception {
      openCalls.incrementAndGet();
      if (failOnOpen) {
        throw new IllegalStateException("openSource boom");
      }
    }

    @Override
    protected Stream<Integer> readRows(SdkTaskContext ctx) throws Exception {
      readCalls.incrementAndGet();
      if (failOnRead) {
        throw new IllegalStateException("readRows boom");
      }
      return rows.stream().onClose(streamCloseCalls::incrementAndGet);
    }

    @Override
    protected void loadBatch(SdkTaskContext ctx, List<Integer> batch) throws Exception {
      if (failOnLoad) {
        throw new IllegalStateException("loadBatch boom");
      }
      loadBatchSizes.add(batch.size());
    }
  }

  private static List<Integer> rows(int n) {
    List<Integer> list = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      list.add(i);
    }
    return list;
  }

  @Test
  @DisplayName("100 行 batchSize=30 → loadBatch 调 4 次 (30+30+30+10),success=100")
  void shouldChunkIntoBatches_whenRowsExceedBatchSize() {
    // arrange
    RecordingImportHandler handler = RecordingImportHandler.of(rows(100), 30);

    // act
    SdkTaskResult result = handler.execute(CTX);

    // assert
    assertThat(result.success()).isTrue();
    assertThat(handler.loadBatchSizes).containsExactly(30, 30, 30, 10);
    assertThat(result.output()).containsEntry("success", 100L).containsEntry("total", 100L);
  }

  @Test
  @DisplayName("0 行 → loadBatch 不调,success=0")
  void shouldNotLoad_whenNoRows() {
    // arrange
    RecordingImportHandler handler = RecordingImportHandler.of(List.of(), 30);

    // act
    SdkTaskResult result = handler.execute(CTX);

    // assert
    assertThat(result.success()).isTrue();
    assertThat(handler.loadBatchSizes).isEmpty();
    assertThat(result.output()).containsEntry("success", 0L).containsEntry("total", 0L);
  }

  @Test
  @DisplayName("readRows 抛异常 → execute fail")
  void shouldFail_whenReadRowsThrows() {
    // arrange
    RecordingImportHandler handler = new RecordingImportHandler(rows(10), 30, false, true, false);

    // act
    SdkTaskResult result = handler.execute(CTX);

    // assert
    assertThat(result.success()).isFalse();
    assertThat(handler.loadBatchSizes).isEmpty();
    assertThat(handler.openCalls).hasValue(1);
  }

  @Test
  @DisplayName("loadBatch 抛异常 → execute fail")
  void shouldFail_whenLoadBatchThrows() {
    // arrange
    RecordingImportHandler handler = new RecordingImportHandler(rows(10), 30, false, false, true);

    // act
    SdkTaskResult result = handler.execute(CTX);

    // assert
    assertThat(result.success()).isFalse();
    assertThat(handler.loadBatchSizes).isEmpty();
  }

  @Test
  @DisplayName("行数整除 batchSize (60 行 batchSize=30) → loadBatch 2 次,无空 flush")
  void shouldNotEmptyFlush_whenRowsDivisibleByBatchSize() {
    // arrange
    RecordingImportHandler handler = RecordingImportHandler.of(rows(60), 30);

    // act
    SdkTaskResult result = handler.execute(CTX);

    // assert
    assertThat(result.success()).isTrue();
    assertThat(handler.loadBatchSizes).containsExactly(30, 30);
    assertThat(result.output()).containsEntry("success", 60L);
  }

  @Test
  @DisplayName("openSource 抛异常 → readRows/loadBatch 都不调,fail")
  void shouldFail_whenOpenSourceThrows() {
    // arrange
    RecordingImportHandler handler = new RecordingImportHandler(rows(10), 30, true, false, false);

    // act
    SdkTaskResult result = handler.execute(CTX);

    // assert
    assertThat(result.success()).isFalse();
    assertThat(handler.readCalls).hasValue(0);
    assertThat(handler.loadBatchSizes).isEmpty();
  }

  @Test
  @DisplayName("正常读完 → 行流 close() 被调用一次(释放 ResultSet/InputStream)")
  void shouldCloseRowStream_whenIterationCompletes() {
    // arrange
    RecordingImportHandler handler = RecordingImportHandler.of(rows(100), 30);

    // act
    SdkTaskResult result = handler.execute(CTX);

    // assert
    assertThat(result.success()).isTrue();
    assertThat(handler.streamCloseCalls).hasValue(1);
  }

  @Test
  @DisplayName("loadBatch 中途抛异常 → 行流仍 close()(try-with-resources 兜底,不泄露)")
  void shouldCloseRowStream_whenLoadBatchThrowsMidIteration() {
    // arrange
    RecordingImportHandler handler = new RecordingImportHandler(rows(100), 30, false, false, true);

    // act
    SdkTaskResult result = handler.execute(CTX);

    // assert
    assertThat(result.success()).isFalse();
    assertThat(handler.streamCloseCalls).hasValue(1);
  }

  @Test
  @DisplayName("自定义 batchSize() 覆盖生效 — 50 行 batchSize=10 → 5 批")
  void shouldRespectCustomBatchSize_whenOverridden() {
    // arrange
    RecordingImportHandler handler = RecordingImportHandler.of(rows(50), 10);

    // act
    SdkTaskResult result = handler.execute(CTX);

    // assert
    assertThat(result.success()).isTrue();
    assertThat(handler.loadBatchSizes).containsExactly(10, 10, 10, 10, 10);
    assertThat(result.output()).containsEntry("success", 50L).containsEntry("total", 50L);
  }
}
