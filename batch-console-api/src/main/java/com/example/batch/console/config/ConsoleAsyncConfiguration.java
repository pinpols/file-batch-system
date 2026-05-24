package com.example.batch.console.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * P0-4 (pre-launch audit 2026-05-18)：为 @Async 注册有界线程池。
 *
 * <p>未配置前 Spring 用 {@code SimpleAsyncTaskExecutor} —— 每次调用都新建线程,无上限。 在告警 / 推送风暴下 OS 线程耗尽直接 OOM。
 *
 * <p>所有 @Async 方法必须显式指定 executor 名(例 {@code @Async("pushTaskExecutor")}), 走 caller-runs
 * 拒绝策略让发起线程承担背压、避免任务静默丢失。
 */
@Slf4j
@Configuration
@EnableAsync
public class ConsoleAsyncConfiguration {

  public static final String PUSH_TASK_EXECUTOR = "pushTaskExecutor";
  public static final String REALTIME_SCHEDULER = "consoleRealtimeScheduler";

  @Bean(name = PUSH_TASK_EXECUTOR)
  public Executor pushTaskExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(4);
    executor.setMaxPoolSize(16);
    executor.setQueueCapacity(200);
    executor.setKeepAliveSeconds(60);
    executor.setThreadNamePrefix("push-async-");
    // queue 满了让调用线程自己跑,而不是默认 AbortPolicy 丢任务/抛异常静默漏推送
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(30);
    executor.initialize();
    return executor;
  }

  /**
   * P1 治理项 (audit 2026-05-23): SSE 实时事件总线 / 摘要流之前各自直接 {@code Executors.newScheduledThreadPool}, 游离
   * Spring 生命周期外, 无 Actuator 指标. 这里统一注册一个 {@code consoleRealtimeScheduler} 给 {@code
   * ConsoleRealtimeEventHub} 心跳调度与 {@code ConsoleOpsSummaryRealtimeStream} debounce 刷新共用. pool size
   * 走 2 起步: 一格供 hub 高频心跳, 一格供 summary 延迟刷新.
   */
  @Bean(name = REALTIME_SCHEDULER, destroyMethod = "shutdown")
  public ThreadPoolTaskScheduler consoleRealtimeScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(2);
    scheduler.setThreadNamePrefix("console-realtime-");
    scheduler.setDaemon(true);
    scheduler.setRemoveOnCancelPolicy(true);
    scheduler.setWaitForTasksToCompleteOnShutdown(false);
    scheduler.setAwaitTerminationSeconds(5);
    scheduler.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
    return scheduler;
  }
}
