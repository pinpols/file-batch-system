package io.github.pinpols.batch.sdk.checkpoint;

import java.util.Map;

/**
 * ADR-037 决策一 — 断点续跑状态快照。
 *
 * <p>断点的<b>语义由 SDK 定义,持久化由租户 business 实现</b>(见 {@link SdkCheckpoint})。一条状态记录回答三个问题:「我处理到哪了」( {@link
 * #breakPosition})、「成功 / 失败各多少」({@link #succeedCount} / {@link #failCount})、「整个 task 是否已跑完」( {@link
 * #completed})。
 *
 * <p><b>断点是数据自身的主键 / 范围,不是 offset。</b>{@code breakPosition} 存「已处理到的最后一条记录的业务主键 / 排序键 / 行号」,
 * 与切分键同一坐标系,续跑时直接 {@code WHERE key > :breakPosition} 往后捞,而非靠脆弱的 offset/limit。
 *
 * @param breakPosition 断点坐标:已处理到的记录主键 / 范围键(不可变拷贝;首次运行为空 Map)
 * @param succeedCount 累计成功行数(跨重派累加,续跑不归零)
 * @param failCount 累计失败行数
 * @param completed 整个 task 是否已完成;{@code true} 时续跑模板直接幂等跳过
 */
public record SdkCheckpointState(
    Map<String, Object> breakPosition, long succeedCount, long failCount, boolean completed) {

  public SdkCheckpointState {
    breakPosition = breakPosition == null ? Map.of() : Map.copyOf(breakPosition);
  }

  /** 首次运行的初始状态:无断点、计数归零、未完成。 */
  public static SdkCheckpointState initial() {
    return new SdkCheckpointState(Map.of(), 0L, 0L, false);
  }

  /** 派生一个推进后的状态(保留 completed=false),用于每批 {@code commit}。 */
  public SdkCheckpointState advance(
      Map<String, Object> nextBreakPosition, long succeed, long fail) {
    return new SdkCheckpointState(nextBreakPosition, succeed, fail, false);
  }

  /** 派生终态(completed=true),用于 task 跑完后的幂等标记。 */
  public SdkCheckpointState markCompleted() {
    return new SdkCheckpointState(breakPosition, succeedCount, failCount, true);
  }
}
