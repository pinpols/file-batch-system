package com.example.batch.worker.core.infrastructure.checkpoint;

/**
 * ADR-038 续跑位点的 stage 维度枚举 — 当前仅 Import LOAD / Export GENERATE 两阶段。
 *
 * <p>Atomic / Dispatch / Process 不在续跑范围(执行 < 1 chunk 边界或无中间状态),不放入。
 */
public enum ProcessingStage {
  /** Import 阶段 LOAD:已处理到的行号续跑。 */
  LOAD,
  /** Export 阶段 GENERATE:已确认的 cursor 续跑。 */
  GENERATE;

  /** DB stage 列值:与枚举 name 一致(CHECK 约束 IN ('LOAD','GENERATE') 限定)。 */
  public String code() {
    return name();
  }
}
