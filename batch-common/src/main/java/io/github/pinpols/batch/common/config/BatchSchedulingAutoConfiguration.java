package io.github.pinpols.batch.common.config;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.ThreadPoolExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
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

  @Bean
  @ConditionalOnMissingBean(ErrorHandler.class)
  public ErrorHandler batchScheduledTaskErrorHandler() {
    return new RecoverableAwareScheduledTaskErrorHandler();
  }

  /** 统一调度异常分类：瞬时依赖抖动只打一行 WARN，真正代码错误仍保留 ERROR 堆栈。 */
  @Slf4j
  static class RecoverableAwareScheduledTaskErrorHandler implements ErrorHandler {
    @Override
    public void handleError(Throwable t) {
      if (isRecoverable(t)) {
        log.warn(
            "scheduled task transient failure, will retry on next tick: {} ({})",
            t.getClass().getSimpleName(),
            rootCauseMessage(t));
        log.debug("scheduled task transient failure stack", t);
        return;
      }
      log.error("scheduled task unexpected error", t);
    }

    private static boolean isRecoverable(Throwable t) {
      for (Throwable cur = t; cur != null; cur = cur.getCause()) {
        if (cur instanceof DataAccessException
            || cur instanceof RedisConnectionFailureException
            || cur instanceof RedisSystemException
            || cur instanceof ConnectException
            || cur instanceof SocketTimeoutException
            || cur instanceof UnknownHostException
            || cur instanceof ClosedChannelException) {
          return true;
        }
      }
      return false;
    }

    private static String rootCauseMessage(Throwable t) {
      Throwable cur = t;
      while (cur.getCause() != null && cur.getCause() != cur) {
        cur = cur.getCause();
      }
      String message = cur.getMessage();
      return message == null || message.isBlank() ? cur.getClass().getSimpleName() : message;
    }
  }
}
