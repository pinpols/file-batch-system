package com.example.batch.orchestrator.config;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * orchestrator 异步 / 调度基础设施 bean.
 *
 * <p>P1 治理项 (audit 2026-05-23): {@code OutboxPollScheduler.onApplicationReady} 内之前直接 {@code new
 * ScheduledThreadPoolExecutor(1, ...)} 私建 executor, 无 Actuator 指标 / 无统一线程命名治理 / 守护线程在 JVM shutdown
 * 时被直接终止. 提取为 Spring 管理 bean (autoStartup=false 模式无意义, 这里依靠 ApplicationReadyEvent 触发首轮调度).
 *
 * <p>注意: 不复用 {@code batch-common} 的 {@code taskScheduler} bean (架构硬约束: 禁覆盖 batch-common 基础设施 bean),
 * 专用 {@code outboxPollScheduler} 与其他领域调度隔离, 防止 outbox 投递热路径被其他 @Scheduled 任务挤占线程。
 */
@Configuration
public class OrchestratorAsyncConfiguration {

  // 2026-05-24:常量值从 "outboxPollScheduler" 改为 "outboxPollTaskScheduler",
  // 避免与 OutboxPollScheduler @Component bean(默认 bean 名 outboxPollScheduler)
  // 同名冲突(Spring Boot 4 默认不允许 override → BeanDefinitionOverrideException)。
  public static final String OUTBOX_POLL_SCHEDULER = "outboxPollTaskScheduler";

  /**
   * {@code defaultCandidate = false}:Spring Boot {@code TaskExecutionAutoConfiguration} 的
   * {@code @ConditionalOnMissingBean(Executor.class)} 按类型查 Executor,本 scheduler 实现 Executor 但语义只服务
   * outbox 投递。设为 non-default 候选,让 autoconfig 继续按需创建 applicationTaskExecutor。 注入侧仍可通过
   * {@code @Qualifier(OUTBOX_POLL_SCHEDULER)} 精确取本 bean。
   */
  @Bean(name = OUTBOX_POLL_SCHEDULER, destroyMethod = "shutdown", defaultCandidate = false)
  public ThreadPoolTaskScheduler outboxPollTaskScheduler() {
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

  // applicationTaskExecutor 由 Spring Boot TaskExecutionAutoConfiguration 提供:
  // 其 @ConditionalOnMissingBean 按 NAME 判断("applicationTaskExecutor"),不按 Executor 类型。
  // outboxPollTaskScheduler 虽实现 Executor 但名字不同,不会阻止 autoconfig 创建 applicationTaskExecutor。
  // E2E slice 测试 app(E2eConsoleImportApplication 等)在 slice 模式下 autoconfig 不触发,
  // 各自显式补 minimal pool — slice 测试自家责任,本配置不越界声明。
}
