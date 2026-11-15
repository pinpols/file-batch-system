package io.github.pinpols.batch.trigger.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("手工 launch 本地准入闸门:并发耗尽即限流、队列等待放行与慢请求自适应收缩恢复")
class TriggerApiAdmissionGuardTest {

  @Test
  @DisplayName("并发已达上限且队列容量为 0 时,后来的请求立即以限流错误被拒绝")
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
  @DisplayName("队列尚未满时突发请求会排队,等在飞请求释放许可后正常执行且不报错")
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
  @DisplayName("排队中的请求按到达顺序获得许可，后到的请求不能插到先到请求前面")
  void shouldGrantQueuedRequestsInArrivalOrder() throws Exception {
    TriggerRuntimeProperties properties = new TriggerRuntimeProperties();
    properties.setApiLaunchMaxConcurrency(1);
    properties.setApiLaunchQueueCapacity(4);
    properties.setApiLaunchQueueWaitMillis(5_000);
    TriggerApiAdmissionGuard guard = new TriggerApiAdmissionGuard(properties);
    CountDownLatch holderEntered = new CountDownLatch(1);
    CountDownLatch releaseHolder = new CountDownLatch(1);
    AtomicInteger turn = new AtomicInteger();
    int[] entryOrder = new int[4];
    CountDownLatch[] entered = latches(4);
    CountDownLatch[] release = latches(4);
    Thread holder = new Thread(() -> guard.execute(() -> {
      holderEntered.countDown();
      await(releaseHolder);
      return null;
    }));
    Thread[] waiters = new Thread[4];
    for (int index = 0; index < waiters.length; index++) {
      int waiterIndex = index;
      waiters[index] = new Thread(() -> guard.execute(() -> {
        entryOrder[waiterIndex] = turn.incrementAndGet();
        entered[waiterIndex].countDown();
        await(release[waiterIndex]);
        return null;
      }));
    }

    holder.start();
    try {
      if (!holderEntered.await(2, TimeUnit.SECONDS)) {
        throw new AssertionError("admission holder did not enter");
      }
      for (int index = 0; index < waiters.length; index++) {
        waiters[index].start();
        awaitQueued(guard, index + 1);
      }
      releaseHolder.countDown();
      for (int index = 0; index < waiters.length; index++) {
        if (!entered[index].await(2, TimeUnit.SECONDS)) {
          throw new AssertionError("queued request " + index + " did not enter in order");
        }
        assertThat(guard.queuedForTest()).isEqualTo(waiters.length - index - 1);
        assertThat(entryOrder[index]).isEqualTo(index + 1);
        release[index].countDown();
      }
    } finally {
      releaseHolder.countDown();
      for (CountDownLatch waiterRelease : release) {
        waiterRelease.countDown();
      }
      holder.join(2_000);
      for (Thread waiter : waiters) {
        waiter.join(2_000);
      }
    }
  }

  @Test
  @DisplayName("自适应开启时慢请求把许可从 8 减半到 4,紧接的快请求再回升到 5")
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

  @Test
  @DisplayName("排队等待超时后离开队列，并以限流错误拒绝")
  void shouldRejectAndLeaveQueueWhenWaitExpires() throws Exception {
    TriggerRuntimeProperties properties = new TriggerRuntimeProperties();
    properties.setApiLaunchMaxConcurrency(1);
    properties.setApiLaunchQueueCapacity(1);
    properties.setApiLaunchQueueWaitMillis(30);
    TriggerApiAdmissionGuard guard = new TriggerApiAdmissionGuard(properties);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Thread holder = new Thread(() -> guard.execute(() -> {
      entered.countDown();
      await(release);
      return null;
    }));
    AtomicReference<Throwable> waiterFailure = new AtomicReference<>();
    Thread waiter = new Thread(() -> {
      try {
        guard.execute(() -> null);
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
      waiter.join(2_000);
      assertThat(waiterFailure.get()).isInstanceOf(BizException.class);
      assertThat(((BizException) waiterFailure.get()).getCode()).isEqualTo(ResultCode.RATE_LIMITED);
      assertThat(guard.queuedForTest()).isZero();
    } finally {
      release.countDown();
      holder.join(2_000);
      waiter.join(2_000);
    }
  }

  @Test
  @DisplayName("排队线程被中断时离开队列，并保留中断标记")
  void shouldRejectAndLeaveQueueWhenWaitingThreadIsInterrupted() throws Exception {
    TriggerRuntimeProperties properties = new TriggerRuntimeProperties();
    properties.setApiLaunchMaxConcurrency(1);
    properties.setApiLaunchQueueCapacity(1);
    properties.setApiLaunchQueueWaitMillis(5_000);
    TriggerApiAdmissionGuard guard = new TriggerApiAdmissionGuard(properties);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Thread holder = new Thread(() -> guard.execute(() -> {
      entered.countDown();
      await(release);
      return null;
    }));
    AtomicReference<Throwable> waiterFailure = new AtomicReference<>();
    AtomicBoolean interrupted = new AtomicBoolean();
    Thread waiter = new Thread(() -> {
      try {
        guard.execute(() -> null);
      } catch (Throwable throwable) {
        interrupted.set(Thread.currentThread().isInterrupted());
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
      waiter.interrupt();
      waiter.join(2_000);
      assertThat(waiterFailure.get()).isInstanceOf(BizException.class);
      assertThat(((BizException) waiterFailure.get()).getCode()).isEqualTo(ResultCode.RATE_LIMITED);
      assertThat(interrupted).isTrue();
      assertThat(guard.queuedForTest()).isZero();
    } finally {
      release.countDown();
      holder.join(2_000);
      waiter.join(2_000);
    }
  }

  @Test
  @DisplayName("指标读取当前活跃数、排队数和有效并发上限")
  void shouldExposeActiveQueuedAndLimitGauges() throws Exception {
    TriggerRuntimeProperties properties = new TriggerRuntimeProperties();
    properties.setApiLaunchMaxConcurrency(2);
    properties.setApiLaunchQueueCapacity(1);
    properties.setApiLaunchQueueWaitMillis(1_000);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    TriggerApiAdmissionGuard guard = new TriggerApiAdmissionGuard(properties, registry);
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
      assertThat(registry.get("batch.trigger.api_launch.admission.active").gauge().value())
          .isEqualTo(1);
      assertThat(registry.get("batch.trigger.api_launch.admission.queued").gauge().value())
          .isZero();
      assertThat(registry.get("batch.trigger.api_launch.admission.limit").gauge().value())
          .isEqualTo(2);
    } finally {
      release.countDown();
      holder.join(2_000);
    }
    assertThat(registry.get("batch.trigger.api_launch.admission.active").gauge().value())
        .isZero();
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
    awaitQueued(guard, 1);
  }

  private static void awaitQueued(TriggerApiAdmissionGuard guard, int queued)
      throws InterruptedException {
    for (int attempt = 0; attempt < 50; attempt++) {
      if (guard.queuedForTest() == queued) {
        return;
      }
      Thread.sleep(10);
    }
    throw new AssertionError("admission queue did not reach " + queued);
  }

  private static CountDownLatch[] latches(int count) {
    CountDownLatch[] latches = new CountDownLatch[count];
    for (int index = 0; index < count; index++) {
      latches[index] = new CountDownLatch(1);
    }
    return latches;
  }
}
