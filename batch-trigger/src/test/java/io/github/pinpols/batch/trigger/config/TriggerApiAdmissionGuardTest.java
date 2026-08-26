package io.github.pinpols.batch.trigger.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.enums.ResultCode;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TriggerApiAdmissionGuardTest {

  @Test
  void shouldRejectWhenApiLaunchConcurrencyIsExhausted() throws Exception {
    TriggerRuntimeProperties properties = new TriggerRuntimeProperties();
    properties.setApiLaunchMaxConcurrency(1);
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
}
