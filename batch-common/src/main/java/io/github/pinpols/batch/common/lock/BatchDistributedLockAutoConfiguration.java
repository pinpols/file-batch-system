package io.github.pinpols.batch.common.lock;

import io.github.pinpols.batch.common.config.BatchShedLockAutoConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * 装配 {@link DistributedLockAspect}。配套 {@link DistributedLock} 的预留能力——该注解目前零生产使用 (见其
 * Javadoc),本自动配置随之处于"备而不用"状态:默认开(matchIfMissing),但没有任何业务方法触发切面。 采纳前可保持原样,无运行时开销(切面只在被标注方法上生效)。
 */
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
