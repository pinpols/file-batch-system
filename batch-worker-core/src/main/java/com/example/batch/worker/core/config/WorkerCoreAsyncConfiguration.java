package com.example.batch.worker.core.config;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * worker-core 异步 / 调度基础设施 bean.
 *
 * <p>P1 治理项 (audit 2026-05-23): 把 {@code DefaultTaskExecutionWrapper} 内私建的 {@code
 * Executors.newSingleThreadScheduledExecutor} 提升为 Spring 管理的 {@code ThreadPoolTaskScheduler}, 与
 * {@code batch-common} 统一调度治理 (Actuator 指标、线程命名、生命周期、拒绝策略) 对齐。
 *
 * <p>注意: 这里**不复用** {@code batch-common} 的 {@code taskScheduler} bean (架构硬约束: 禁覆盖 batch-common 基础设施
 * bean), 而是注册一个专用的 {@code workerWatchdogScheduler} 供 watchdog 使用。
 */
@Configuration
public class WorkerCoreAsyncConfiguration {

  public static final String WATCHDOG_SCHEDULER = "workerWatchdogScheduler";

  @Bean(name = WATCHDOG_SCHEDULER, destroyMethod = "shutdown")
  public ThreadPoolTaskScheduler workerWatchdogScheduler(
      WorkerWatchdogSchedulerProperties properties) {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(Math.max(1, properties.getPoolSize()));
    scheduler.setThreadNamePrefix(properties.getThreadNamePrefix());
    scheduler.setDaemon(true);
    scheduler.setRemoveOnCancelPolicy(true);
    scheduler.setWaitForTasksToCompleteOnShutdown(false);
    scheduler.setAwaitTerminationSeconds(Math.max(0, properties.getAwaitTerminationSeconds()));
    // watchdog 任务极轻 (一次性 check); 极端情况下用 caller-runs 兜底, 避免静默丢失
    scheduler.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
    return scheduler;
  }
}
