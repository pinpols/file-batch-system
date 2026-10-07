package io.github.pinpols.batch.common.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("试运行守卫:直通与全跳过两种模式下副作用执行与返回值语义")
class DryRunGuardTest {

  @Test
  @DisplayName("直通模式:副作用照常执行,取回真实调用结果且不标记试运行")
  void shouldExecuteActions_whenPassThroughMode() {
    DryRunGuard guard = DryRunGuard.passThrough();
    AtomicInteger counter = new AtomicInteger();

    guard.runUnlessDryRun("SIDE_EFFECT", counter::incrementAndGet);
    String value = guard.callOrSkip("EXTERNAL_CALL", () -> "real-result", "dry-fallback");

    assertThat(guard.isDryRun()).isFalse();
    assertThat(counter.get()).isEqualTo(1);
    assertThat(value).isEqualTo("real-result");
  }

  @Test
  @DisplayName("全跳过模式:副作用不执行,直接返回回退值并标记试运行")
  void shouldSkipSideEffectAndReturnFallback_whenDryRunMode() {
    DryRunGuard guard = DryRunGuard.skipAll();
    AtomicInteger counter = new AtomicInteger();

    guard.runUnlessDryRun("DB_INSERT", counter::incrementAndGet);
    String value = guard.callOrSkip("REMOTE_UPLOAD", () -> "real-result", "dry-fallback");

    assertThat(guard.isDryRun()).isTrue();
    assertThat(counter.get()).isZero();
    assertThat(value).isEqualTo("dry-fallback");
  }

  @Test
  @DisplayName("全跳过模式:即使提供方会抛异常也不被调用,直接返回回退值")
  void shouldNotInvokeSupplier_whenDryRunMode() {
    DryRunGuard guard = DryRunGuard.skipAll();

    String value = guard.callOrSkip(
        "RISKY",
        () -> {
          throw new IllegalStateException("must not be called in dry-run");
        },
        "fallback");

    assertThat(value).isEqualTo("fallback");
  }
}
