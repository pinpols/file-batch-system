package com.example.batch.common.config;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.ErrorHandler;

@AutoConfiguration
@EnableConfigurationProperties(BatchSchedulingProperties.class)
public class BatchSchedulingAutoConfiguration {

  /**
   * 共享 taskScheduler。模块如想自定义错误处理（例如把 PG 瞬时不可用降级为 WARN），只需在自己的 @Configuration 中暴露 {@link
   * ErrorHandler} bean，本方法会自动注入；找不到时 Spring 默认 LoggingErrorHandler 接管（所有异常打 ERROR）。
   */
  @Bean(name = "taskScheduler")
  public TaskScheduler taskScheduler(
      BatchSchedulingProperties properties, ObjectProvider<ErrorHandler> errorHandlerProvider) {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(Math.max(1, properties.getPoolSize()));
    scheduler.setThreadNamePrefix(properties.getThreadNamePrefix());
    scheduler.setPhase(properties.getPhase());
    scheduler.setWaitForTasksToCompleteOnShutdown(properties.isWaitForTasksToCompleteOnShutdown());
    scheduler.setAwaitTerminationSeconds(Math.max(0, properties.getAwaitTerminationSeconds()));
    scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(
        properties.isContinueExistingPeriodicTasksAfterShutdown());
    scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(
        properties.isExecuteExistingDelayedTasksAfterShutdown());
    scheduler.setRemoveOnCancelPolicy(true);
    scheduler.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
    errorHandlerProvider.ifAvailable(scheduler::setErrorHandler);
    return scheduler;
  }
}
