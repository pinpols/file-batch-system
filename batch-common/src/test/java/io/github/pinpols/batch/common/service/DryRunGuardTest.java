package io.github.pinpols.batch.common.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DryRunGuardTest {

  @Test
  void passThroughExecutesEveryAction() {
    DryRunGuard guard = DryRunGuard.passThrough();
    AtomicInteger counter = new AtomicInteger();

    guard.runUnlessDryRun("SIDE_EFFECT", counter::incrementAndGet);
    String value = guard.callOrSkip("EXTERNAL_CALL", () -> "real-result", "dry-fallback");

    assertThat(guard.isDryRun()).isFalse();
    assertThat(counter.get()).isEqualTo(1);
    assertThat(value).isEqualTo("real-result");
  }

  @Test
  void skipAllShortCircuitsSideEffectAndReturnsFallback() {
    DryRunGuard guard = DryRunGuard.skipAll();
    AtomicInteger counter = new AtomicInteger();

    guard.runUnlessDryRun("DB_INSERT", counter::incrementAndGet);
    String value = guard.callOrSkip("REMOTE_UPLOAD", () -> "real-result", "dry-fallback");

    assertThat(guard.isDryRun()).isTrue();
    assertThat(counter.get()).isZero();
    assertThat(value).isEqualTo("dry-fallback");
  }

  @Test
  void skipAllNeverInvokesSupplierEvenIfSupplierThrows() {
    DryRunGuard guard = DryRunGuard.skipAll();

    String value =
        guard.callOrSkip(
            "RISKY",
            () -> {
              throw new IllegalStateException("must not be called in dry-run");
            },
            "fallback");

    assertThat(value).isEqualTo("fallback");
  }
}
