package com.example.batch.testing.chaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.batch.common.config.ShedLockProviderFactory;
import com.example.batch.common.time.BatchDateTimeSupport;
import java.time.Duration;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 故障注入意图:验证 Redis 全断(toxiproxy disable)下:
 *
 * <ul>
 *   <li>RedisLockProvider.lock(...) 抛连接异常 — 应用上层(ShedLock 调度器)可 catch 转 jdbc fallback
 *   <li>StringRedisTemplate 操作快速失败,不会无限 hang(短 timeout 必须生效)
 *   <li>Redis 恢复后 lock provider 立即恢复正常(无需重启应用)
 * </ul>
 *
 * <p>对应业务路径:{@code BATCH_SHEDLOCK_PROVIDER=redis} 主路径 + Redis 不可达时的 jdbc fallback。
 */
@DisplayName("Redis 全断注入 — ShedLock redis provider 必快速失败,可被 jdbc fallback 接管;恢复后无副作用")
class RedisDownToxicIT extends AbstractChaosIntegrationTest {

  @Test
  @DisplayName("Redis 断 → Lettuce 短 timeout 必抛 RedisConnectionFailure — 上层可 catch 切 jdbc fallback")
  void shouldFailFastWhenRedisDown() throws Exception {
    LettuceConnectionFactory factory = newLettuceFactory();
    try {
      // 健康路径预热:确认非故障时可正常 ping
      StringRedisTemplate template = new StringRedisTemplate(factory);
      assertThat(template.getConnectionFactory().getConnection().ping()).isEqualTo("PONG");

      withDown(
          ProxyTarget.REDIS,
          () ->
              assertThatThrownBy(() -> template.getConnectionFactory().getConnection().ping())
                  .isInstanceOf(DataAccessException.class));
    } finally {
      factory.destroy();
    }
  }

  @Test
  @DisplayName("Redis 断 + RedisLockProvider.lock → 抛连接异常(应用可 catch 走 jdbc fallback 分支)")
  void redisLockProviderShouldThrowWhenRedisDown() throws Exception {
    LettuceConnectionFactory factory = newLettuceFactory();
    try {
      LockProvider provider = ShedLockProviderFactory.redisLockProvider(factory, "chaos");
      LockConfiguration cfg =
          new LockConfiguration(
              BatchDateTimeSupport.utcNow(),
              "chaos-redis-down-" + System.nanoTime(),
              Duration.ofSeconds(30),
              Duration.ZERO);

      // 故障前:lock 正常
      SimpleLock pre = provider.lock(cfg).orElseThrow();
      pre.unlock();

      withDown(
          ProxyTarget.REDIS,
          () ->
              // Spring Data Redis 把 Lettuce 连接/超时异常封装为 DataAccessException 子类:
              // RedisConnectionFailure / RedisSystemException / QueryTimeoutException 等都属此分类。
              assertThatThrownBy(() -> provider.lock(cfg)).isInstanceOf(DataAccessException.class));

      // 故障消除后恢复 — Lettuce 持久连接需要短暂时间重连,轮询最多 5s 内必恢复
      SimpleLock post = pollUntilLockSucceeds(provider, cfg, Duration.ofSeconds(5));
      post.unlock();
    } finally {
      factory.destroy();
    }
  }

  private SimpleLock pollUntilLockSucceeds(
      LockProvider provider, LockConfiguration cfg, Duration timeout) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    RuntimeException last = null;
    while (System.nanoTime() < deadline) {
      try {
        return provider.lock(cfg).orElseThrow();
      } catch (RuntimeException ex) {
        last = ex;
        Thread.sleep(100);
      }
    }
    throw new AssertionError("RedisLockProvider 未在恢复窗口内自愈", last);
  }

  private LettuceConnectionFactory newLettuceFactory() {
    RedisStandaloneConfiguration cfg =
        new RedisStandaloneConfiguration(redisProxiedHost(), redisProxiedPort());
    LettuceClientConfiguration clientCfg =
        LettuceClientConfiguration.builder()
            .commandTimeout(Duration.ofSeconds(2))
            .shutdownTimeout(Duration.ZERO)
            .build();
    LettuceConnectionFactory factory = new LettuceConnectionFactory(cfg, clientCfg);
    factory.afterPropertiesSet();
    return factory;
  }
}
