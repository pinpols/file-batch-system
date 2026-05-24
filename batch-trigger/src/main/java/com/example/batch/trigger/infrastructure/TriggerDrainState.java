package com.example.batch.trigger.infrastructure;

import com.example.batch.common.time.BatchDateTimeSupport;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Trigger 排水 (draining) 状态管理 — 与具体 scheduler 实现解耦的纯状态 bean。
 *
 * <p>R-arch-audit-2026-05-23 P1: 历史上 {@link TriggerGracefulShutdown} 同时承担状态保管 + Quartz {@code
 * Scheduler.standby/start/shutdown} 调度,在 wheel 模式下 Quartz 不再驱动 fire,但 {@code isDraining()} 语义仍由
 * Quartz standby 状态计算,与 wheel 真实停机路径 ({@link
 * com.example.batch.trigger.wheel.HashedWheelTriggerScheduler#shutdown()} 走 {@code @PreDestroy})
 * 完全脱节。
 *
 * <p>本类抽离 draining 标志位与时间戳,作为 scheduler-agnostic 的真值源:
 *
 * <ul>
 *   <li>{@link TriggerGracefulShutdown} 在 startDraining/stopDraining 时更新本状态, 然后再分别调用
 *       scheduler-specific 的停机动作 (Quartz: standby; Wheel: 由 @PreDestroy 接管)。
 *   <li>{@code TriggerController} / {@code BatchDayCutoffScheduler} / {@code TriggerReconciler}
 *       等只关心"是否在排水"的调用方,可直接注入本 bean,无需依赖 Quartz {@code Scheduler}。
 * </ul>
 */
@Slf4j
@Component
public class TriggerDrainState {

  private final AtomicBoolean draining = new AtomicBoolean(false);
  private volatile Instant drainingSince;
  private volatile String reason;

  /**
   * CAS 启动排水。返回 {@code true} 表示本调用是首次置位 (调用方应做 scheduler-specific 停机动作), 返回 {@code false}
   * 表示已在排水中,幂等无副作用。
   */
  public boolean startDraining(String source) {
    if (draining.compareAndSet(false, true)) {
      drainingSince = BatchDateTimeSupport.utcNow();
      reason = source;
      log.info("Trigger drain state: STARTED source={}", source);
      return true;
    }
    return false;
  }

  /**
   * CAS 停止排水。返回 {@code true} 表示本调用是首次清位 (调用方应做 scheduler-specific 恢复动作), 返回 {@code false} 表示已不在排水中。
   */
  public boolean stopDraining(String source) {
    if (draining.compareAndSet(true, false)) {
      log.info("Trigger drain state: STOPPED source={}", source);
      drainingSince = null;
      reason = source;
      return true;
    }
    drainingSince = null;
    reason = source;
    return false;
  }

  public boolean isDraining() {
    return draining.get();
  }

  public Instant getDrainingSince() {
    return drainingSince;
  }

  public String getReason() {
    return reason;
  }
}
