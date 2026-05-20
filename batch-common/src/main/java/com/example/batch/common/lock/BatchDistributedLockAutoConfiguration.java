package com.example.batch.common.lock;

import com.example.batch.common.config.BatchShedLockAutoConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@AutoConfigureAfter(BatchShedLockAutoConfiguration.class)
@ConditionalOnClass({LockingTaskExecutor.class, org.aspectj.lang.annotation.Aspect.class})
@ConditionalOnProperty(
    name = "batch.lock.distributed.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class BatchDistributedLockAutoConfiguration {

  @Bean
  @ConditionalOnBean(LockingTaskExecutor.class)
  @ConditionalOnMissingBean
  public DistributedLockAspect distributedLockAspect(LockingTaskExecutor lockingTaskExecutor) {
    return new DistributedLockAspect(lockingTaskExecutor);
  }
}
