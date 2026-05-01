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
import org.springframework.context.annotation.Bean;

/**
 * ShedLock 共享自动配置：每个模块只要 classpath 含 {@code shedlock-spring} + 容器有 {@link DataSource} bean，就自动获得
 * JDBC {@link LockProvider} + {@link LockingTaskExecutor} bean，{@code @SchedulerLock} AOP 同步激活。
 *
 * <p><b>覆盖机制</b>：定义自己的 {@code @Bean LockProvider}（如 orchestrator 的 {@code
 * RedisShedLockProvider}）即可覆盖默认 JDBC 实现（{@link ConditionalOnMissingBean} 让 auto-config 让位）。
 *
 * <p><b>auto-create 开关</b>：{@code batch.shedlock.auto-create=true} 启动期 {@code ensureShedLockTable}
 * 兜底建 {@code batch.shedlock} 表（仅 dev / 测试，prod 由 Flyway 迁移建表，开关默认 false）。
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
  @Bean
  @ConditionalOnMissingBean(LockProvider.class)
  public LockProvider lockProvider(
      DataSource dataSource,
      @Value("${batch.shedlock.auto-create:false}") boolean autoCreateTable) {
    LockProvider provider =
        ShedLockProviderFactory.jdbcTemplateLockProvider(dataSource, autoCreateTable);
    log.info(
        "ShedLock LockProvider auto-configured: type={}, autoCreate={}",
        provider.getClass().getSimpleName(),
        autoCreateTable);
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
