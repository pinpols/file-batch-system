package com.example.batch.console.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.redis.testcontainers.RedisContainer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class SlidingWindowRateLimiterIT {

  @Container
  static final RedisContainer REDIS = new RedisContainer(DockerImageName.parse("redis:7-alpine"));

  private SlidingWindowRateLimiter rateLimiter;

  @BeforeEach
  void setUp() {
    RedisStandaloneConfiguration config =
        new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getFirstMappedPort());
    LettuceConnectionFactory factory = new LettuceConnectionFactory(config);
    factory.afterPropertiesSet();
    StringRedisTemplate template = new StringRedisTemplate(factory);
    template.afterPropertiesSet();
    rateLimiter = new SlidingWindowRateLimiter(template);
  }

  @Test
  void shouldAllowExactlyLimitRequests() {
    for (int i = 0; i < 5; i++) {
      assertThat(rateLimiter.tryAcquire("test:exact", 5)).isTrue();
    }
    assertThat(rateLimiter.tryAcquire("test:exact", 5)).isFalse();
  }

  @Test
  void shouldIsolateDifferentKeys() {
    for (int i = 0; i < 5; i++) {
      rateLimiter.tryAcquire("test:keyA", 5);
    }
    assertThat(rateLimiter.tryAcquire("test:keyB", 5)).isTrue();
  }

  @Test
  void shouldBeThreadSafeUnderConcurrency() throws InterruptedException {
    int threads = 20;
    int limit = 10;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(threads);
    AtomicInteger allowed = new AtomicInteger();

    for (int i = 0; i < threads; i++) {
      pool.submit(
          () -> {
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
