package io.github.pinpols.batch.sdk.task;

import io.github.pinpols.batch.sdk.checkpoint.InMemorySdkCheckpoint;
import io.github.pinpols.batch.sdk.checkpoint.SdkCheckpoint;
import io.github.pinpols.batch.sdk.checkpoint.SdkCheckpointState;
import java.util.Map;

/**
 * ADR-037 决策二 + 决策三 — {@link SdkTaskContext#commit(Map)} 背后的<b>三合一可靠提交协调器</b>。
 *
 * <p>仿照 {@link CancellationSignal} / {@link ProgressReporter}:一个可变持有对象,由框架(dispatcher / 续跑模板)每个
 * task 创建并注入 {@link SdkTaskContext}。它聚合断点存储 {@link SdkCheckpoint}、进度上报槽、取消信号、限流配置,以及跨批次累加的成功 /
 * 失败计数与提交批次计数。
 *
 * <p>{@link #commit(Map)} 一次原子完成三件事:
 *
 * <ol>
 *   <li><b>保存断点</b>(经 {@link SdkCheckpoint#save};JDBC 默认实现在同 Connection 同事务里连业务数据一起提交);
 *   <li><b>限流上报进度</b>:{@code commitCounter % reportIntervalBatches == 0} 时调 {@link
 *       ProgressReporter#report}, 避免每批都打满网络;{@code selfReport=false} 时关掉自动上报,交给业务自己控制;
 *   <li><b>协作式取消</b>:提交成功后若取消标志命中,在已提交的安全点抛 {@link SdkTaskStoppedException}。
 * </ol>
 *
 * <p>计数由模板通过 {@link #recordBatch(long, long)} 在每批 commit 前推进;续跑恢复用 {@link #restoreCounts(long,
 * long)} 把上次断点的累计计数填回,保证进度不归零。
 */
public final class SdkCommitCoordinator {

  private final String taskId;
  private final SdkCheckpoint checkpoint;
  private final ProgressReporter progress;
  private final CancellationSignal cancellation;
  private final boolean selfReport;
  private final int reportIntervalBatches;

  private long succeedCount;
  private long failCount;
  private long commitCounter;

  public SdkCommitCoordinator(
      String taskId,
      SdkCheckpoint checkpoint,
      ProgressReporter progress,
      CancellationSignal cancellation,
      boolean selfReport,
      int reportIntervalBatches) {
    this.taskId = taskId;
    this.checkpoint = checkpoint == null ? new InMemorySdkCheckpoint() : checkpoint;
    this.progress = progress == null ? new ProgressReporter() : progress;
    this.cancellation = cancellation == null ? new CancellationSignal() : cancellation;
    this.selfReport = selfReport;
    this.reportIntervalBatches = Math.max(1, reportIntervalBatches);
  }

  /** 断点存储入口(暴露给 {@link SdkTaskContext#checkpoint()})。 */
  public SdkCheckpoint checkpoint() {
    return checkpoint;
  }

  /** 续跑恢复:把上次断点的累计计数填回,使进度不归零。 */
  public void restoreCounts(long succeed, long fail) {
    this.succeedCount = succeed;
    this.failCount = fail;
  }

  /** 模板在每批 {@code commit} 前推进本批的成功 / 失败增量。 */
  public void recordBatch(long succeedDelta, long failDelta) {
    this.succeedCount += succeedDelta;
    this.failCount += failDelta;
  }

  public long succeedCount() {
    return succeedCount;
  }

  public long failCount() {
    return failCount;
  }

  /**
   * 三合一提交:保存断点(同事务)→ 限流上报 → 取消检查。
   *
   * @param breakPosition 本批已处理到的断点坐标
   * @throws SdkTaskStoppedException 提交成功后命中取消标志时,在安全点抛出
   */
  public void commit(Map<String, Object> breakPosition) {
    SdkCheckpointState state =
        new SdkCheckpointState(breakPosition, succeedCount, failCount, false);
    checkpoint.save(taskId, state);
    commitCounter++;
    if (selfReport && commitCounter % reportIntervalBatches == 0) {
      progress.report(
          Map.of(
              "succeed", succeedCount,
              "fail", failCount,
              "breakPosition", state.breakPosition()));
    }
    if (cancellation.isCancelled()) {
      throw new SdkTaskStoppedException(state.breakPosition());
    }
  }

  /** task 全部跑完后,把终态(completed=true)落进断点,实现重派幂等跳过。 */
  public void markCompleted(Map<String, Object> finalBreakPosition) {
    checkpoint.save(
        taskId, new SdkCheckpointState(finalBreakPosition, succeedCount, failCount, true));
  }
}
