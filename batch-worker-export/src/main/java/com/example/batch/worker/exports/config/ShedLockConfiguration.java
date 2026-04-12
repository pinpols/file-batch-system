package com.example.batch.worker.exports.config;

import com.example.batch.common.config.ShedLockProviderFactory;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** ShedLock 分布式锁配置，防止多实例重复执行定时任务。 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT2M")
public class ShedLockConfiguration {

  @Value("${batch.shedlock.auto-create:false}")
  private boolean autoCreateTable;

  @Bean
  public LockProvider lockProvider(DataSource dataSource) {
    return ShedLockProviderFactory.jdbcTemplateLockProvider(dataSource, autoCreateTable);
  }
}
