package com.example.batch.orchestrator.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.util.ErrorHandler;

/**
 * 自定义 @Scheduled 任务的全局 ErrorHandler，把"PG 瞬时不可用"类异常降级为 WARN。
 *
 * <p>背景：Spring 默认 {@code TaskUtils.LoggingErrorHandler} 把所有 @Scheduled 任务的未捕获异常打 ERROR。 实际上 {@link
 * DataAccessException}（PG crash recovery 30 秒窗口期 / 网络抖动）会被各 scheduler 的下一轮 tick 自动重试，不需要人介入；用 WARN
 * 更准确，避免 ERROR 日志噪音淹没真问题。
 *
 * <p>识别规则：
 *
 * <ul>
 *   <li>{@link DataAccessException} 及其子类（包括 CannotGetJdbcConnectionException 等）→ WARN
 *   <li>其他异常（NPE / 业务异常 / OOM 等）→ ERROR（保留原行为）
 * </ul>
 */
@Slf4j
@Configuration
public class SchedulerErrorHandlerConfiguration implements SchedulingConfigurer {

  @Bean
  public ThreadPoolTaskScheduler taskScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(10);
    scheduler.setThreadNamePrefix("batch-scheduler-");
    scheduler.setErrorHandler(new RecoverableAwareErrorHandler());
    scheduler.setWaitForTasksToCompleteOnShutdown(true);
    scheduler.setAwaitTerminationSeconds(30);
    scheduler.initialize();
    return scheduler;
  }

  @Override
  public void configureTasks(ScheduledTaskRegistrar registrar) {
    registrar.setTaskScheduler(taskScheduler());
  }

  /** 区分瞬时可恢复异常 vs 真异常的全局 ErrorHandler。 */
  static class RecoverableAwareErrorHandler implements ErrorHandler {
    @Override
    public void handleError(Throwable t) {
      if (isRecoverable(t)) {
        // 瞬时故障，下轮 tick 自动重试；只打一行 WARN，堆栈走 DEBUG
        log.warn(
            "scheduled task transient failure, will retry on next tick: {} ({})",
            t.getClass().getSimpleName(),
            rootCauseMessage(t));
        log.debug("scheduled task transient failure stack", t);
      } else {
        log.error("scheduled task unexpected error", t);
      }
    }

    private static boolean isRecoverable(Throwable t) {
      Throwable cur = t;
      while (cur != null) {
        if (cur instanceof DataAccessException) {
          return true;
        }
        cur = cur.getCause();
      }
      return false;
    }

    private static String rootCauseMessage(Throwable t) {
      Throwable cur = t;
      while (cur.getCause() != null && cur.getCause() != cur) {
        cur = cur.getCause();
      }
      return cur.getMessage();
    }
  }
}
