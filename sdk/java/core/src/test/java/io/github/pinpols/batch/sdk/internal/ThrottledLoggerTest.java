package io.github.pinpols.batch.sdk.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

/** Lane J §J2:{@link ThrottledLogger} 行为单测。 */
class ThrottledLoggerTest {

  @Test
  void shouldRejectInvalidConstructorArgs() {
    Logger l = mock(Logger.class);
    assertThatThrownBy(() -> ThrottledLogger.create(null, Duration.ofSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ThrottledLogger.create(l, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ThrottledLogger.create(l, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ThrottledLogger.create(l, Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void shouldSuppressWithinWindow_andEmitOnlyFirst() {
    Logger delegate = mock(Logger.class);
    when(delegate.isWarnEnabled()).thenReturn(true);
    ThrottledLogger t = ThrottledLogger.create(delegate, Duration.ofSeconds(60));

    // 准备并执行:连续 5 次同 key,只有首条放行
    for (int i = 0; i < 5; i++) {
      t.warn("k1", "warn {}", i);
    }

    // 断言
    verify(delegate, times(1)).warn(any(String.class), any(Object[].class));
  }

  @Test
  void shouldEmitAcrossWindow_withSuppressedCount() throws Exception {
    Logger delegate = mock(Logger.class);
    when(delegate.isWarnEnabled()).thenReturn(true);
    // 窗口 50ms,跨窗口好测
    ThrottledLogger t = ThrottledLogger.create(delegate, Duration.ofMillis(50));

    t.warn("k1", "msg {}", 1); // 放行 (suppressed=0)
    t.warn("k1", "msg {}", 2); // 抑制
    t.warn("k1", "msg {}", 3); // 抑制
    Thread.sleep(80); // 跨窗口
    t.warn("k1", "msg {}", 4); // 放行,附 suppressed=2

    verify(delegate, times(2)).warn(any(String.class), any(Object[].class));
    // 第二次放行的 format 包含 "suppressed"
    verify(delegate).warn(contains("suppressed"), any(Object[].class));
  }

  @Test
  void shouldTrackDifferentKeysIndependently() {
    Logger delegate = mock(Logger.class);
    when(delegate.isInfoEnabled()).thenReturn(true);
    ThrottledLogger t = ThrottledLogger.create(delegate, Duration.ofSeconds(60));

    t.info("a", "a1");
    t.info("b", "b1");
    t.info("a", "a2"); // 抑制
    t.info("b", "b2"); // 抑制
    t.info("c", "c1");

    // 三个不同 key,各放行 1 次 = 3 次
    verify(delegate, times(3)).info(any(String.class), any(Object[].class));
  }

  @Test
  void shouldRespectDelegateLevelToggle() {
    Logger delegate = mock(Logger.class);
    when(delegate.isErrorEnabled()).thenReturn(false);
    ThrottledLogger t = ThrottledLogger.create(delegate, Duration.ofSeconds(60));

    t.error("k", "boom");

    verify(delegate, times(0)).error(any(String.class), any(Object[].class));
  }

  @Test
  void shouldRouteToErrorAndInfoChannels() {
    Logger delegate = mock(Logger.class);
    when(delegate.isInfoEnabled()).thenReturn(true);
    when(delegate.isErrorEnabled()).thenReturn(true);
    ThrottledLogger t = ThrottledLogger.create(delegate, Duration.ofSeconds(60));

    t.info("ki", "info");
    t.error("ke", "err");

    verify(delegate).info(any(String.class), any(Object[].class));
    verify(delegate).error(any(String.class), any(Object[].class));
  }

  @Test
  void shouldNeverDoubleEmit_underConcurrentSameKey() throws Exception {
    Logger delegate = mock(Logger.class);
    when(delegate.isWarnEnabled()).thenReturn(true);
    AtomicInteger emitted = new AtomicInteger();
    // 真实统计调用次数:Mockito 在并发下计数安全
    doAnswer(
            inv -> {
              emitted.incrementAndGet();
              return null;
            })
        .when(delegate)
        .warn(any(String.class), any(Object[].class));

    ThrottledLogger t = ThrottledLogger.create(delegate, Duration.ofSeconds(60));

    int threads = 16;
    int perThread = 200;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(threads);
    for (int i = 0; i < threads; i++) {
      pool.submit(
          () -> {
            try {
              start.await();
              for (int j = 0; j < perThread; j++) {
                t.warn("hot", "x");
              }
            } catch (InterruptedException ignored) {
              Thread.currentThread().interrupt();
            } finally {
              done.countDown();
            }
          });
    }
    start.countDown();
    assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
    pool.shutdownNow();

    // 窗口足够大 + 同一 key:期望恰好放行 1 次(首条)
    assertThat(emitted.get()).isEqualTo(1);
  }
}
