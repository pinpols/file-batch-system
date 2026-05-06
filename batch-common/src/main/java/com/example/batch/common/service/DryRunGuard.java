package com.example.batch.common.service;

import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * ADR-026 dry-run 守卫 SPI。Worker 副作用（业务表写入 / 外部 IO / 通知 / 文件落盘等）必须包裹一次：
 *
 * <pre>
 *   guard.runUnlessDryRun("ORDER_INSERT", () -> orderMapper.insert(order));
 *   String fileId = guard.callOrSkip("UPLOAD_REMOTE", () -> client.upload(payload), "DRY_RUN_FILE_ID");
 * </pre>
 *
 * <p>实现：
 *
 * <ul>
 *   <li>{@code DryRunOnGuard} — dryRun=true 模式下记 INFO 日志并跳过；
 *   <li>{@code DryRunOffGuard} — 默认 / 实盘模式下直接执行；
 *   <li>{@code DryRunGuards.passThrough()} — 单元测试默认实例（永不跳过）。
 * </ul>
 *
 * <p>与 bypass-mode 正交：bypass 是放行不安全（鉴权 / 加密），dry-run 是放行安全但不副作用。两者可独立组合。
 */
public interface DryRunGuard {

  /** 当前实例是否处于 dry-run 模式。 */
  boolean isDryRun();

  /** 标签 op 仅在非 dry-run 下执行；dry-run 下打日志返回。 */
  void runUnlessDryRun(String opTag, Runnable action);

  /** 标签 op 仅在非 dry-run 下执行；dry-run 下打日志返回 {@code dryRunFallback}。 */
  <T> T callOrSkip(String opTag, Supplier<T> action, T dryRunFallback);

  /** 工厂：返回永远执行的 pass-through guard（适用于实盘默认 + 单元测试默认）。 */
  static DryRunGuard passThrough() {
    return DryRunGuards.PASS_THROUGH;
  }

  /** 工厂：返回永远跳过 + 打日志的 guard（演练 instance 注入）。 */
  static DryRunGuard skipAll() {
    return DryRunGuards.SKIP_ALL;
  }

  @Slf4j
  final class DryRunGuards {
    private DryRunGuards() {}

    static final DryRunGuard PASS_THROUGH =
        new DryRunGuard() {
          @Override
          public boolean isDryRun() {
            return false;
          }

          @Override
          public void runUnlessDryRun(String opTag, Runnable action) {
            action.run();
          }

          @Override
          public <T> T callOrSkip(String opTag, Supplier<T> action, T dryRunFallback) {
            return action.get();
          }
        };

    static final DryRunGuard SKIP_ALL =
        new DryRunGuard() {
          @Override
          public boolean isDryRun() {
            return true;
          }

          @Override
          public void runUnlessDryRun(String opTag, Runnable action) {
            log.info("[dry-run] skip side-effect op={}", opTag);
          }

          @Override
          public <T> T callOrSkip(String opTag, Supplier<T> action, T dryRunFallback) {
            log.info("[dry-run] skip side-effect op={} fallback={}", opTag, dryRunFallback);
            return dryRunFallback;
          }
        };
  }
}
