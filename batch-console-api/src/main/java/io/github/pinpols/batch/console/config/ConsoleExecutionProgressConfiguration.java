package io.github.pinpols.batch.console.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** 运行进度共享采样独立于SSE心跳，慢查询不占用连接保活线程。 */
@Configuration(proxyBeanMethods = false)
public class ConsoleExecutionProgressConfiguration {
  public static final String SCHEDULER = "workflowExecutionProgressScheduler";

  @Bean(name = SCHEDULER, destroyMethod = "shutdown")
  public ThreadPoolTaskScheduler executionProgressScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(2);
    scheduler.setThreadNamePrefix("execution-progress-");
    scheduler.setRemoveOnCancelPolicy(true);
    scheduler.setDaemon(true);
    return scheduler;
  }
}
