package com.example.batch.worker.core.infrastructure;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 进程级 pipeline stage 行级进度 sink。
 *
 * <p>2026-06-03 落地 {@code docs/design/pipeline-stage-progress-display.md}:IMPORT LOAD / EXPORT
 * GENERATE 是流式跑百万行的 stage,运维需要"已处理多少 / 还剩多久"信号判活 + 估 ETA。
 *
 * <p>设计要点:
 *
 * <ul>
 *   <li><b>进程级单例</b>(AtomicReference):一个 worker JVM 同时只跑一个 stage(CLAIM 模型), 无并发竞争,无锁
 *   <li><b>心跳读取</b>:{@link HttpWorkerRegistryClient#toHeartbeatDto} 每次组装心跳时 读最新值,自动随 heartbeat
 *       30s/次上报
 *   <li><b>stage 结束 clear</b>:LoadStep / GenerateStep finally 块调 {@link #clear()}, 避免上一个 stage
 *       残留进度被下一个 stage 心跳带上来
 *   <li><b>未上报态</b>:两个值都 null = stage 不在跑或不支持进度(PROCESS / DISPATCH 原子 stage)
 * </ul>
 *
 * <p>不持久化:进度只关心当前态,worker 崩溃后下一次 CLAIM 由 {@code pipeline_progress.position_marker} 续跑(ADR-038),不靠本
 * sink。
 */
public final class PipelineStageProgressSink {

  private static final AtomicReference<Snapshot> CURRENT = new AtomicReference<>();

  private PipelineStageProgressSink() {}

  /** stage 进度变化时调用(LoadStep / GenerateStep)。 */
  public static void publish(long rowsProcessed, Long totalRowsHint) {
    CURRENT.set(new Snapshot(rowsProcessed, totalRowsHint));
  }

  /** stage 结束时调用,清空。 */
  public static void clear() {
    CURRENT.set(null);
  }

  /** 心跳读取(无值 null)。 */
  public static Long currentRowsProcessed() {
    Snapshot s = CURRENT.get();
    return s == null ? null : s.rowsProcessed;
  }

  /** 心跳读取(无值 null)。 */
  public static Long currentTotalRowsHint() {
    Snapshot s = CURRENT.get();
    return s == null ? null : s.totalRowsHint;
  }

  private record Snapshot(long rowsProcessed, Long totalRowsHint) {}
}
