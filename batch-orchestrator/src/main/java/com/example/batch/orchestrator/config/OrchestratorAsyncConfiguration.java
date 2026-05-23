package com.example.batch.orchestrator.config;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * orchestrator 异步 / 调度基础设施 bean.
 *
 * <p>P1 治理项 (audit 2026-05-23): {@code OutboxPollScheduler.onApplicationReady} 内之前直接 {@code new
 * ScheduledThreadPoolExecutor(1, ...)} 私建 executor, 无 Actuator 指标 / 无统一线程命名治理 / 守护线程在 JVM
 * shutdown 时被直接终止. 提取为 Spring 管理 bean (autoStartup=false 模式无意义, 这里依靠 ApplicationReadyEvent
 * 触发首轮调度).
 *
 * <p>注意: 不复用 {@code batch-common} 的 {@code taskScheduler} bean (架构硬约束: 禁覆盖 batch-common 基础设施 bean),
 * 专用 {@code outboxPollScheduler} 与其他领域调度隔离, 防止 outbox 投递热路径被其他 @Scheduled 任务挤占线程。
 */
@Configuration
public class OrchestratorAsyncConfiguration {

  public static final String OUTBOX_POLL_SCHEDULER = "outboxPollScheduler";

  @Bean(name = OUTBOX_POLL_SCHEDULER, destroyMethod = "shutdown")
  public ThreadPoolTaskScheduler outboxPollScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("outbox-poll-scheduler-");
    // 关键改动: 关闭 daemon, 让 JVM shutdown 走 Spring graceful 路径 (awaitTermination 30s 才有意义).
    // 原 setDaemon(true) 的副作用: JVM 退出时 daemon 线程被直接终止, awaitTermination 形同虚设。
    scheduler.setDaemon(false);
    scheduler.setRemoveOnCancelPolicy(true);
    scheduler.setWaitForTasksToCompleteOnShutdown(false);
    scheduler.setAwaitTerminationSeconds(30);
    scheduler.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
    return scheduler;
  }
}
