package com.example.batch.console.config;

import com.example.batch.common.config.ShedLockProviderFactory;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Console-api 集群的 ShedLock 配置：让多 console-api 实例间的 {@code @Scheduled} 任务互斥（如 {@link
 * com.example.batch.console.service.WebhookDeliveryRelay}）。
 *
 * <p>复用 batch-common 的 JDBC {@link LockProvider}（同一张 {@code batch.shedlock} 表，跨模块共享租约）。
 *
 * <p>历史问题：本配置缺失曾导致 console-api 启动时 {@code WebhookDeliveryRelay} 的 {@link LockingTaskExecutor} bean
 * 找不到（pre-existing bug，prod 部署 + 11 个 IT 全部 ApplicationContext 加载失败）。
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT2M")
public class ConsoleShedLockConfiguration {

  @Bean
  public LockProvider lockProvider(DataSource dataSource) {
    return ShedLockProviderFactory.jdbcTemplateLockProvider(dataSource);
  }

  @Bean
  public LockingTaskExecutor lockingTaskExecutor(LockProvider lockProvider) {
    return new DefaultLockingTaskExecutor(lockProvider);
  }
}
