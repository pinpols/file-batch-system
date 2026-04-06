package com.example.batch.orchestrator.config;

import com.example.batch.orchestrator.infrastructure.redis.RedisShedLockProvider;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * ShedLock configuration for orchestrator cluster.
 *
 * <p>Ensures that each {@code @Scheduled} task runs on exactly one orchestrator instance
 * at a time, regardless of how many replicas are deployed.
 *
 * <p>Locks are stored in {@code batch.shedlock}. In normal runtime the table comes from the
 * shared batch-common Flyway migration; in local profile we allow a small auto-create fallback so
 * a fresh developer database can still boot.
 *
 * <p>{@code defaultLockAtMostFor} is a safety net: if a JVM crashes while holding a lock, the
 * lock auto-expires after this duration so another instance can take over.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT2M")
public class ShedLockConfiguration {

    @Bean
    public LockProvider lockProvider(
            StringRedisTemplate redisTemplate,
            @Value("${spring.application.name:batch-orchestrator}") String environment
    ) {
        return new RedisShedLockProvider(redisTemplate, environment);
    }

    @Bean
    public LockingTaskExecutor lockingTaskExecutor(LockProvider lockProvider) {
        return new DefaultLockingTaskExecutor(lockProvider);
    }
}
