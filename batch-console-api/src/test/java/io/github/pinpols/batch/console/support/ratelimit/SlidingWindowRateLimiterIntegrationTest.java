package io.github.pinpols.batch.console.support.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.config.BatchTimezoneProperties;
import io.github.pinpols.batch.common.config.BatchTimezoneProvider;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@DisplayName("滑动窗口限流: 配额边界、键隔离与并发下的原子性")
class SlidingWindowRateLimiterIntegrationTest extends AbstractIntegrationTest {

  private SlidingWindowRateLimiter rateLimiter;

  @BeforeEach
  void setUp() {
    RedisStandaloneConfiguration config =
        new RedisStandaloneConfiguration(redisHost(), redisPort());
    LettuceConnectionFactory factory = new LettuceConnectionFactory(config);
    factory.afterPropertiesSet();
    StringRedisTemplate template = new StringRedisTemplate(factory);
    template.afterPropertiesSet();
    rateLimiter = new SlidingWindowRateLimiter(
        new RedisRateLimitStore(template),
        new BatchDateTimeSupport(
            Clock.systemUTC(), new BatchTimezoneProvider(new BatchTimezoneProperties())));
  }

  @Test
  @DisplayName("配额为五时前五次请求全部放行, 第六次被拒绝")
  void shouldAllowExactlyLimitRequests() {
    for (int i = 0; i < 5; i++) {
      assertThat(rateLimiter.tryAcquire("test:exact", 5)).isTrue();
    }
    assertThat(rateLimiter.tryAcquire("test:exact", 5)).isFalse();
  }

  @Test
  @DisplayName("其中一个键用尽配额后, 另一个键仍可正常获取配额")
  void shouldIsolateDifferentKeys() {
    for (int i = 0; i < 5; i++) {
      rateLimiter.tryAcquire("test:keyA", 5);
    }
    assertThat(rateLimiter.tryAcquire("test:keyB", 5)).isTrue();
  }

  @Test
  @DisplayName("二十个线程并发争抢十个配额时, 最终只有十次被放行")
  void shouldBeThreadSafeUnderConcurrency() throws InterruptedException {
    int threads = 20;
    int limit = 10;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(threads);
    AtomicInteger allowed = new AtomicInteger();

    for (int i = 0; i < threads; i++) {
      pool.submit(() -> {
        try {
          start.await();
          if (rateLimiter.tryAcquire("test:concurrent", limit)) {
            allowed.incrementAndGet();
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } finally {
          done.countDown();
        }
      });
    }
    start.countDown();
    done.await();
    pool.shutdown();

    // Redis Lua 原子性保证：并发情况下精确限制为 limit
    assertThat(allowed.get()).isEqualTo(limit);
  }
}
