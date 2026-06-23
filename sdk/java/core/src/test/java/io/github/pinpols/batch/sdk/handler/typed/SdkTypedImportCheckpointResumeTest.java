package io.github.pinpols.batch.sdk.handler.typed;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.sdk.checkpoint.InMemorySdkCheckpoint;
import io.github.pinpols.batch.sdk.checkpoint.SdkCheckpointState;
import io.github.pinpols.batch.sdk.task.CancellationSignal;
import io.github.pinpols.batch.sdk.task.ProgressReporter;
import io.github.pinpols.batch.sdk.task.SdkCommitCoordinator;
import io.github.pinpols.batch.sdk.task.SdkTaskContext;
import io.github.pinpols.batch.sdk.task.SdkTaskResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-037 P1~P3 — 续跑模板断点恢复 / 完成幂等跳过 / 协作取消落 cancelled 的端到端单测(Import 模板)。 */
class SdkTypedImportCheckpointResumeTest {

  record ImportRequest(int rows) {}

  /** 测试 handler:按入参 rows 生成行;breakPosition 用最后一行的行号。 */
  static class ResumableImport extends SdkAbstractTypedImportHandler<ImportRequest, Void, Integer> {
    final List<Integer> loaded = new ArrayList<>();
    final int batchSize;

    ResumableImport(int batchSize) {
      this.batchSize = batchSize;
    }

    @Override
    public String taskType() {
      return "tenant_resumable_import";
    }

    @Override
    protected int batchSize() {
      return batchSize;
    }

    @Override
    protected Stream<Integer> readRows(ImportRequest input, SdkTaskContext ctx) {
      return IntStream.range(0, input.rows()).boxed();
    }

    @Override
    protected void loadBatch(ImportRequest input, SdkTaskContext ctx, List<Integer> batch) {
      loaded.addAll(batch);
    }

    @Override
    protected Map<String, Object> breakPosition(ImportRequest input, List<Integer> batch) {
      return batch.isEmpty() ? Map.of() : Map.of("row", batch.get(batch.size() - 1));
    }
  }

  private SdkTaskContext ctxWith(
      Map<String, Object> params, SdkCommitCoordinator coordinator, CancellationSignal cancel) {
    return new SdkTaskContext(
        "t1",
        "job-1",
        "ti-1",
        7L,
        "w1",
        params,
        Map.of(),
        null,
        cancel,
        new ProgressReporter(),
        coordinator);
  }

  @Test
  @DisplayName("断点已 completed → 模板幂等跳过,不再 loadBatch")
  void shouldSkipWhenCheckpointCompleted() {
    // arrange: 预置已完成断点(taskId=7)
    InMemorySdkCheckpoint cp = new InMemorySdkCheckpoint();
    cp.save("7", new SdkCheckpointState(Map.of("row", 99), 100L, 0L, true));
    SdkCommitCoordinator coord =
        new SdkCommitCoordinator(
            "7", cp, new ProgressReporter(), new CancellationSignal(), true, 1);
    ResumableImport handler = new ResumableImport(10);

    // act
    SdkTaskResult result =
        handler.execute(ctxWith(Map.of("rows", 50), coord, new CancellationSignal()));

    // assert: 成功跳过,无任何批次加载
    assertThat(result.success()).isTrue();
    assertThat(result.message()).contains("already completed");
    assertThat(handler.loaded).isEmpty();
  }

  @Test
  @DisplayName("正常跑完 → 每批 commit 落断点,终态 markCompleted=true")
  void shouldCommitEachBatchAndMarkCompleted() {
    // arrange
    InMemorySdkCheckpoint cp = new InMemorySdkCheckpoint();
    SdkCommitCoordinator coord =
        new SdkCommitCoordinator(
            "7", cp, new ProgressReporter(), new CancellationSignal(), true, 1);
    ResumableImport handler = new ResumableImport(30);

    // act: 100 行,batchSize=30 → 4 批
    SdkTaskResult result =
        handler.execute(ctxWith(Map.of("rows", 100), coord, new CancellationSignal()));

    // assert
    assertThat(result.success()).isTrue();
    assertThat(handler.loaded).hasSize(100);
    SdkCheckpointState saved = cp.load("7").orElseThrow();
    assertThat(saved.completed()).isTrue();
    assertThat(saved.succeedCount()).isEqualTo(100L);
  }

  @Test
  @DisplayName("续跑:从已有断点恢复计数,新批次在累计基线上叠加")
  void shouldResumeCountsFromExistingCheckpoint() {
    // arrange: 预置进行中的断点 succeed=1000(未完成)
    InMemorySdkCheckpoint cp = new InMemorySdkCheckpoint();
    cp.save("7", new SdkCheckpointState(Map.of("row", 999), 1000L, 0L, false));
    SdkCommitCoordinator coord =
        new SdkCommitCoordinator(
            "7", cp, new ProgressReporter(), new CancellationSignal(), true, 1);
    ResumableImport handler = new ResumableImport(50);

    // act: 本次再处理 50 行
    SdkTaskResult result =
        handler.execute(ctxWith(Map.of("rows", 50), coord, new CancellationSignal()));

    // assert: 计数从 1000 起累加到 1050(不归零)
    assertThat(result.success()).isTrue();
    assertThat(coord.succeedCount()).isEqualTo(1050L);
    assertThat(cp.load("7").orElseThrow().succeedCount()).isEqualTo(1050L);
  }

  @Test
  @DisplayName("决策三:跑到中途取消 → commit 安全点抛 SdkTaskStoppedException → 模板落 cancelled 终态")
  void shouldEndCancelledWhenCancelledMidRun() {
    // arrange: 取消信号在第一批 commit 前就翻转 → 第一批提交后即停
    InMemorySdkCheckpoint cp = new InMemorySdkCheckpoint();
    CancellationSignal cancel = new CancellationSignal();
    cancel.cancel();
    SdkCommitCoordinator coord =
        new SdkCommitCoordinator("7", cp, new ProgressReporter(), cancel, true, 1);
    ResumableImport handler = new ResumableImport(20);

    // act: 100 行,batchSize=20;第一批提交后命中取消停止
    SdkTaskResult result = handler.execute(ctxWith(Map.of("rows", 100), coord, cancel));

    // assert: cancelled 终态(success=false + cancelled 标记),只加载了第一批
    assertThat(result.success()).isFalse();
    assertThat(result.output()).containsEntry("cancelled", true);
    assertThat(result.output()).containsEntry("breakPosition", Map.of("row", 19));
    assertThat(handler.loaded).hasSize(20);
    // 断点停在安全点 row=19,未标 completed
    SdkCheckpointState saved = cp.load("7").orElseThrow();
    assertThat(saved.completed()).isFalse();
    assertThat(saved.breakPosition()).containsEntry("row", 19);
  }
}
