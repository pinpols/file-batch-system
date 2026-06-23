package io.github.pinpols.batch.e2e.config;

import io.github.pinpols.batch.common.config.ShedLockProviderFactory;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * E2E 显式声明 ShedLock，用于覆盖 orchestrator 的 Redis {@link LockProvider}；须与 {@link
 * io.github.pinpols.batch.common.config.BatchShedLockAutoConfiguration} 行为对齐，否则 {@code
 * batch.shedlock.auto-create} 在 E2E 中不生效——此前仅用 {@link
 * ShedLockProviderFactory#jdbcTemplateLockProvider(javax.sql.DataSource)}（永不自动建表），在 Flyway 未跑或 仅
 * platform-init 的环境会报 {@code batch.shedlock} 不存在。
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT2M")
public class E2eShedLockConfiguration {

  @Bean
  public LockProvider lockProvider(
      DataSource dataSource, @Value("${batch.shedlock.auto-create:true}") boolean autoCreateTable) {
    return ShedLockProviderFactory.jdbcTemplateLockProvider(dataSource, autoCreateTable);
  }

  @Bean
  public LockingTaskExecutor lockingTaskExecutor(LockProvider lockProvider) {
    return new DefaultLockingTaskExecutor(lockProvider);
  }
}
