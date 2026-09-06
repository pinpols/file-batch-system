package io.github.pinpols.batch.trigger.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.enums.ResultCode;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TriggerApiAdmissionGuardTest {

  @Test
  void shouldRejectWhenApiLaunchConcurrencyIsExhausted() throws Exception {
    TriggerRuntimeProperties properties = new TriggerRuntimeProperties();
    properties.setApiLaunchMaxConcurrency(1);
    properties.setApiLaunchQueueCapacity(0);
    TriggerApiAdmissionGuard guard = new TriggerApiAdmissionGuard(properties);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Thread holder = new Thread(() -> guard.execute(() -> {
      entered.countDown();
      await(release);
      return null;
    }));

    holder.start();
    try {
      if (!entered.await(2, TimeUnit.SECONDS)) {
        throw new AssertionError("admission holder did not enter");
      }
      assertThatThrownBy(() -> guard.execute(() -> null))
          .extracting("code")
          .isEqualTo(ResultCode.RATE_LIMITED);
    } finally {
      release.countDown();
      holder.join(2_000);
    }
  }

  @Test
  void shouldServeBriefBurstAfterAnInFlightRequestReleasesItsPermit() throws Exception {
    TriggerRuntimeProperties properties = new TriggerRuntimeProperties();
    properties.setApiLaunchMaxConcurrency(1);
    properties.setApiLaunchQueueCapacity(1);
    properties.setApiLaunchQueueWaitMillis(1_000);
    TriggerApiAdmissionGuard guard = new TriggerApiAdmissionGuard(properties);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch waiterCompleted = new CountDownLatch(1);
    AtomicReference<Throwable> waiterFailure = new AtomicReference<>();
    Thread holder = new Thread(() -> guard.execute(() -> {
      entered.countDown();
      await(release);
      return null;
    }));
    Thread waiter = new Thread(() -> {
      try {
        guard.execute(() -> {
          waiterCompleted.countDown();
          return null;
        });
      } catch (Throwable throwable) {
        waiterFailure.set(throwable);
      }
    });

    holder.start();
    try {
      if (!entered.await(2, TimeUnit.SECONDS)) {
        throw new AssertionError("admission holder did not enter");
      }
      waiter.start();
      awaitQueued(guard);
      release.countDown();
      if (!waiterCompleted.await(2, TimeUnit.SECONDS)) {
        throw new AssertionError("queued admission request did not complete");
      }
      assertThat(waiterFailure.get()).isNull();
    } finally {
      release.countDown();
      holder.join(2_000);
      waiter.join(2_000);
    }
  }

  @Test
  void shouldShrinkAdaptiveBudgetAfterSlowRequestAndRecoverAfterFastRequest() {
    TriggerRuntimeProperties properties = new TriggerRuntimeProperties();
    properties.setApiLaunchMaxConcurrency(8);
    properties.setApiLaunchMinConcurrency(2);
    properties.setApiLaunchSlowRequestThresholdMillis(1);
    properties.setApiLaunchAdaptiveEnabled(true);
    TriggerApiAdmissionGuard guard = new TriggerApiAdmissionGuard(properties);

    guard.execute(() -> {
      sleep(5);
      return null;
    });
    assertThat(guard.effectiveConcurrencyForTest()).isEqualTo(4);

    guard.execute(() -> null);
    assertThat(guard.effectiveConcurrencyForTest()).isEqualTo(5);
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(2, TimeUnit.SECONDS)) {
        throw new AssertionError("admission holder was not released");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError("admission holder interrupted", exception);
    }
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError("test sleep interrupted", exception);
    }
  }

  private static void awaitQueued(TriggerApiAdmissionGuard guard) throws InterruptedException {
    for (int attempt = 0; attempt < 20; attempt++) {
      if (guard.queuedForTest() == 1) {
        return;
      }
      Thread.sleep(10);
    }
    throw new AssertionError("admission request did not enter the bounded queue");
  }
}
