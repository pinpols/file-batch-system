package io.github.pinpols.batch.sdk.dispatcher;

/**
 * SDK Phase 2 §2.4:worker 运行态状态机,由心跳回包的 platform directive(见 {@link HeartbeatDirective})驱动。
 *
 * <p>状态语义:
 *
 * <ul>
 *   <li>{@code NORMAL}:正常接派单、跑任务。
 *   <li>{@code DEGRADED}:平台降级提示(如希望降并发);SDK 仍接派单,只是后续可据 desiredMaxConcurrent 收敛(当前与 NORMAL 同样接单)。
 *   <li>{@code PAUSED}:平台暂停;SDK 停止认领新任务(Kafka partition pause,不丢 offset),在手任务跑完;可恢复。
 *   <li>{@code DRAINING}:平台排空;同 PAUSED 停止认领新任务,通常伴随 worker 下线(不可逆地走向 stop)。
 * </ul>
 *
 * <p>{@code PAUSED} / {@code DRAINING} 都不接新任务,区别在语义:PAUSED 期望可恢复,DRAINING 期望终态。
 */
public enum WorkerRuntimeState {
  NORMAL,
  DEGRADED,
  PAUSED,
  DRAINING;

  /** 是否允许认领 / 提交新任务。PAUSED / DRAINING 一律拒新,NORMAL / DEGRADED 接单。 */
  public boolean acceptsNewTasks() {
    return this == NORMAL || this == DEGRADED;
  }
}
