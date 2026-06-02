package com.example.batch.worker.core.infrastructure.checkpoint;

/**
 * 续跑位点的不可变快照 — 调用方读出来做"从哪续跑"决策。
 *
 * <p>无位点(首次跑)用 {@link #empty()};已 completed 用 {@link #completed(long)}(processed/marker 字段无意义)。
 *
 * @param positionMarker 上次推进到的位置(Import=行号字符串;Export=cursor 序列化);empty 时为 null
 * @param processedCount 已确认处理的记录数累计
 * @param completed 该 stage 在本 pipeline 实例是否已整体完成(true 时调用方应幂等跳过)
 */
public record ProcessingPosition(String positionMarker, long processedCount, boolean completed) {

  private static final ProcessingPosition EMPTY = new ProcessingPosition(null, 0L, false);

  /** 无位点(首次跑或表中无该 (instance, stage) 行)。 */
  public static ProcessingPosition empty() {
    return EMPTY;
  }

  /** 已完成位点(调用方按幂等语义跳过该 stage)。 */
  public static ProcessingPosition completed(long processedCount) {
    return new ProcessingPosition(null, processedCount, true);
  }
}
