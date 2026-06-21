package com.example.batch.common.config;

import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * ShedLock 共享自动配置：每个模块只要 classpath 含 {@code shedlock-spring} + 容器有 {@link DataSource} bean，就自动获得
 * JDBC {@link LockProvider} + {@link LockingTaskExecutor} bean，{@code @SchedulerLock} AOP 同步激活。
 *
 * <p><b>覆盖机制</b>：定义自己的 {@code @Bean LockProvider}（如 orchestrator 的 {@code
 * RedisShedLockProvider}）即可覆盖默认 JDBC 实现（{@link ConditionalOnMissingBean} 让 auto-config 让位）。
 *
 * <p><b>auto-create 开关</b>：{@code batch.shedlock.auto-create=true} 启动期 {@code ensureShedLockTable}
 * 回退建 {@code batch.shedlock} 表（仅 dev / 测试，prod 由 Flyway 迁移建表，开关默认 false）。
 *
 * <p><b>历史背景</b>：之前每个模块（trigger / 4 个 worker / console-api）各自 copy 一份 {@code @Configuration
 * ShedLockConfiguration}（6 处重复），新模块加 {@code @Scheduled} 容易漏配 ShedLock
 * 导致启动失败（pre-existing：console-api 的 {@code WebhookDeliveryRelay} 因此 11 个 IT 全部 ApplicationContext
 * 加载失败）。 抽 auto-config 后新模块零配置即可获得 ShedLock 支持。
 */
@Slf4j
@AutoConfiguration
@ConditionalOnClass(LockingTaskExecutor.class)
@EnableSchedulerLock(defaultLockAtMostFor = "PT2M")
public class BatchShedLockAutoConfiguration {

  // 不加 @ConditionalOnBean(DataSource.class)：condition 评估发生在 BeanDefinition 注册阶段，
  // DataSource 此时可能尚未注册（即使加了 @AutoConfigureAfter 也不可靠）会被误判为不存在。
  // 改为依赖 Spring 自然注入：DataSource 缺失时 Spring 会在创建本 bean 时显式报
  // UnsatisfiedDependency，比静默不创建友好。
  //
  // 2026-05-28:batch.shedlock.provider 切换,**默认 redis**(SETNX 比 PG row UPDATE 快 1 个量级)。
  //   provider=redis(默认):Redis SETNX,适合 HA 多节点;lettuce client 由 batch-common 全模块共享
  //   provider=jdbc       :JDBC + batch.shedlock 表(回滚 / Redis 不可用时降级)
  // 业务代码(48 处 @SchedulerLock)切换时**无需任何改动**,ShedLock 抽象了 provider。
  @Bean
  @ConditionalOnMissingBean(LockProvider.class)
  @ConditionalOnProperty(name = "batch.shedlock.provider", havingValue = "jdbc")
  public LockProvider jdbcLockProvider(
      DataSource dataSource,
      @Value("${batch.shedlock.auto-create:false}") boolean autoCreateTable) {
    LockProvider provider =
        ShedLockProviderFactory.jdbcTemplateLockProvider(dataSource, autoCreateTable);
    log.info(
        "ShedLock LockProvider auto-configured: type=JDBC ({}), autoCreate={}",
        provider.getClass().getSimpleName(),
        autoCreateTable);
    return provider;
  }

  @Bean
  @ConditionalOnMissingBean(LockProvider.class)
  @ConditionalOnProperty(
      name = "batch.shedlock.provider",
      havingValue = "redis",
      matchIfMissing = true)
  public LockProvider redisLockProvider(
      RedisConnectionFactory connectionFactory,
      // 默认用 spring.application.name 做 env prefix(每服务一份命名空间,统一格式
      // job-lock:<service>:<lockName>),想跨环境隔离时显式覆盖 batch.shedlock.redis.key-prefix-env。
      @Value("${batch.shedlock.redis.key-prefix-env:${spring.application.name:default}}")
          String environment) {
    LockProvider provider =
        ShedLockProviderFactory.redisLockProvider(connectionFactory, environment);
    log.info(
        "ShedLock LockProvider auto-configured: type=Redis ({}), env={}",
        provider.getClass().getSimpleName(),
        environment);
    return provider;
  }

  // 不加 @ConditionalOnBean(LockProvider.class)：condition 评估发生在 bean 注册前，
  // 同 auto-config 内自己声明的 LockProvider 此刻还未注册，会被误判为不存在 →
  // 整个 LockingTaskExecutor bean 不创建。Spring 会按构造器参数依赖关系自动确保 LockProvider
  // 先创建（无 LockProvider bean 时会在 LockingTaskExecutor 创建时报 UnsatisfiedDependency，
  // 这正是预期行为）。
  @Bean
  @ConditionalOnMissingBean(LockingTaskExecutor.class)
  public LockingTaskExecutor lockingTaskExecutor(LockProvider lockProvider) {
    return new DefaultLockingTaskExecutor(lockProvider);
  }
}
