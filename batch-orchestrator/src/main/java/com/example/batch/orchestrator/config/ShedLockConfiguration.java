package com.example.batch.orchestrator.config;

import com.example.batch.orchestrator.infrastructure.redis.RedisShedLockProvider;

import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Orchestrator 集群的 ShedLock 配置，确保每个 {@code @Scheduled} 任务在任意时刻仅在一个实例上执行。 锁存储于 Redis；{@code
 * defaultLockAtMostFor} 为安全兜底，JVM 崩溃后锁自动过期，允许其他实例接管。
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT2M")
public class ShedLockConfiguration {

    @Value("${spring.application.name:batch-orchestrator}")
    private String environment;

    @Bean
    public LockProvider lockProvider(StringRedisTemplate redisTemplate) {
        return new RedisShedLockProvider(redisTemplate, environment);
    }

    @Bean
    public LockingTaskExecutor lockingTaskExecutor(LockProvider lockProvider) {
        return new DefaultLockingTaskExecutor(lockProvider);
    }
}
