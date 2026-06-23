package io.github.pinpols.batch.sdk.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.sdk.checkpoint.InMemorySdkCheckpoint;
import io.github.pinpols.batch.sdk.checkpoint.SdkCheckpointState;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-037 决策二 + 决策三 — 三合一 commit 原子性 / 限流上报 / 取消安全点单测。 */
class SdkCommitCoordinatorTest {

  private SdkCommitCoordinator coordinator(
      InMemorySdkCheckpoint cp,
      ProgressReporter progress,
      CancellationSignal cancel,
      boolean selfReport,
      int interval) {
    return new SdkCommitCoordinator("task-1", cp, progress, cancel, selfReport, interval);
  }

  @Test
  @DisplayName("commit 原子三合一:断点保存 + 计数随提交推进(内存实现)")
  void shouldSaveCheckpointWithCountsOnCommit() {
    // arrange
    InMemorySdkCheckpoint cp = new InMemorySdkCheckpoint();
    SdkCommitCoordinator c =
        coordinator(cp, new ProgressReporter(), new CancellationSignal(), true, 1);

    // act
    c.recordBatch(10, 1);
    c.commit(Map.of("id", 10));

    // assert: 断点行携带最新计数 + breakPosition
    SdkCheckpointState saved = cp.load("task-1").orElseThrow();
    assertThat(saved.succeedCount()).isEqualTo(10L);
    assertThat(saved.failCount()).isEqualTo(1L);
    assertThat(saved.breakPosition()).containsEntry("id", 10);
    assertThat(saved.completed()).isFalse();
  }

  @Test
  @DisplayName("进度按 reportIntervalBatches 取模限流:interval=3 → 3 批只上报 1 次")
  void shouldRateLimitProgressReport() {
    // arrange
    InMemorySdkCheckpoint cp = new InMemorySdkCheckpoint();
    ProgressReporter progress = new ProgressReporter();
    SdkCommitCoordinator c = coordinator(cp, progress, new CancellationSignal(), true, 3);

    // act: 2 批不应触发上报
    c.recordBatch(5, 0);
    c.commit(Map.of("id", 1));
    assertThat(progress.latest()).isNull();
    c.recordBatch(5, 0);
    c.commit(Map.of("id", 2));
    assertThat(progress.latest()).isNull();

    // act: 第 3 批触发
    c.recordBatch(5, 0);
    c.commit(Map.of("id", 3));

    // assert
    assertThat(progress.latest())
        .containsEntry("succeed", 15L)
        .containsEntry("fail", 0L)
        .containsEntry("breakPosition", Map.of("id", 3));
  }

  @Test
  @DisplayName("selfReport=false → 自动上报关闭,断点照常保存")
  void shouldSkipReportWhenSelfReportOff() {
    // arrange
    InMemorySdkCheckpoint cp = new InMemorySdkCheckpoint();
    ProgressReporter progress = new ProgressReporter();
    SdkCommitCoordinator c = coordinator(cp, progress, new CancellationSignal(), false, 1);

    // act
    c.recordBatch(7, 0);
    c.commit(Map.of("id", 1));

    // assert: 没上报,但断点保存了
    assertThat(progress.latest()).isNull();
    assertThat(cp.load("task-1")).isPresent();
  }

  @Test
  @DisplayName("决策三:commit 成功后命中取消 → 在已提交安全点抛 SdkTaskStoppedException(携带 breakPosition)")
  void shouldThrowStoppedAfterCommitWhenCancelled() {
    // arrange
    InMemorySdkCheckpoint cp = new InMemorySdkCheckpoint();
    CancellationSignal cancel = new CancellationSignal();
    SdkCommitCoordinator c = coordinator(cp, new ProgressReporter(), cancel, true, 1);
    c.recordBatch(20, 0);
    cancel.cancel();

    // act + assert: 抛停止,且断点已落(安全点在提交之后)
    assertThatThrownBy(() -> c.commit(Map.of("id", 20)))
        .isInstanceOf(SdkTaskStoppedException.class)
        .satisfies(
            ex ->
                assertThat(((SdkTaskStoppedException) ex).breakPosition()).containsEntry("id", 20));
    assertThat(cp.load("task-1"))
        .get()
        .satisfies(
            s -> {
              assertThat(s.succeedCount()).isEqualTo(20L);
              assertThat(s.breakPosition()).containsEntry("id", 20);
            });
  }

  @Test
  @DisplayName("未取消时 commit 正常返回,不抛")
  void shouldNotThrowWhenNotCancelled() {
    SdkCommitCoordinator c =
        coordinator(
            new InMemorySdkCheckpoint(), new ProgressReporter(), new CancellationSignal(), true, 1);
    c.recordBatch(1, 0);
    c.commit(Map.of("id", 1)); // no exception
  }

  @Test
  @DisplayName("markCompleted 落 completed=true 终态,供重派幂等跳过")
  void shouldMarkCompleted() {
    InMemorySdkCheckpoint cp = new InMemorySdkCheckpoint();
    SdkCommitCoordinator c =
        coordinator(cp, new ProgressReporter(), new CancellationSignal(), true, 1);
    c.recordBatch(50, 2);
    c.markCompleted(Map.of("id", 50));

    assertThat(cp.load("task-1"))
        .get()
        .satisfies(
            s -> {
              assertThat(s.completed()).isTrue();
              assertThat(s.succeedCount()).isEqualTo(50L);
              assertThat(s.failCount()).isEqualTo(2L);
            });
  }

  @Test
  @DisplayName("restoreCounts 续跑恢复累计计数,进度不归零")
  void shouldRestoreCountsForResume() {
    InMemorySdkCheckpoint cp = new InMemorySdkCheckpoint();
    SdkCommitCoordinator c =
        coordinator(cp, new ProgressReporter(), new CancellationSignal(), true, 1);

    // act: 恢复到 1000/5,再提交一批 100
    c.restoreCounts(1000, 5);
    c.recordBatch(100, 0);
    c.commit(Map.of("id", 1100));

    // assert: 在恢复基线上累加
    assertThat(c.succeedCount()).isEqualTo(1100L);
    assertThat(c.failCount()).isEqualTo(5L);
  }
}
