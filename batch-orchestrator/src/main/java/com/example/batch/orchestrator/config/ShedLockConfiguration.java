package com.example.batch.orchestrator.config;

import com.example.batch.orchestrator.infrastructure.redis.RedisShedLockProvider;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Orchestrator 集群的 ShedLock 配置，覆盖 batch-common 的默认 JDBC {@link LockProvider} 为 Redis 实现 （高频派发热路径下
 * Redis 锁性能优于 PG）。
 *
 * <p>{@code @EnableSchedulerLock} + {@link net.javacrumbs.shedlock.core.LockingTaskExecutor} bean 由
 * batch-common 的 {@code BatchShedLockAutoConfiguration} 自动提供，本类只负责定义 Redis-based {@link
 * LockProvider}，让 auto-config 的 {@code @ConditionalOnMissingBean} 让位。
 *
 * <p>锁存储于 Redis；{@code defaultLockAtMostFor=PT2M} 由 auto-config 提供，JVM 崩溃后锁自动过期，允许其他实例接管。
 */
@Configuration
@Slf4j
public class ShedLockConfiguration {

  @Value("${spring.application.name:batch-orchestrator}")
  private String environment;

  // #10-3: 添加重试逻辑，避免 Flyway 迁移期间 Redis 瞬时不可用导致 ShedLock 初始化失败
  @Bean
  public LockProvider lockProvider(StringRedisTemplate redisTemplate) {
    int maxAttempts = 3;
    long retryDelayMs = 2000L;
    Exception lastException = null;
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        RedisShedLockProvider provider = new RedisShedLockProvider(redisTemplate, environment);
        if (attempt > 1) {
          log.info("ShedLock LockProvider 初始化成功（第 {} 次尝试）", attempt);
        }
        return provider;
      } catch (Exception ex) {
        lastException = ex;
        log.warn(
            "ShedLock LockProvider 初始化失败（第 {}/{} 次尝试）：{}", attempt, maxAttempts, ex.getMessage());
        if (attempt < maxAttempts) {
          try {
            Thread.sleep(retryDelayMs);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ShedLock 初始化被中断", ie);
          }
        }
      }
    }
    throw new IllegalStateException(
        "ShedLock LockProvider 初始化失败（" + maxAttempts + " 次重试后放弃）。" + " 请确认 Redis 服务可用且连接配置正确。",
        lastException);
  }
}
