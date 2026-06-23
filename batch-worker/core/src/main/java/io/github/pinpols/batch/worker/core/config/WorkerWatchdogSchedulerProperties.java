package io.github.pinpols.batch.worker.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Worker task 取消 watchdog 调度器配置 ({@code batch.worker.watchdog-scheduler})。
 *
 * <p>P1 治理项 (audit 2026-05-23): {@code DefaultTaskExecutionWrapper} 之前用 {@code
 * Executors.newSingleThreadScheduledExecutor} 在字段初始化处直接 new, 游离 Spring 生命周期外, 无 Actuator
 * 指标、无统一线程命名治理。提取为 Spring 管理的 {@code ThreadPoolTaskScheduler} bean, 支持外部调参与 metrics 暴露。
 */
@Data
@ConfigurationProperties(prefix = "batch.worker.watchdog-scheduler")
public class WorkerWatchdogSchedulerProperties {

  /** 调度线程池大小. 单线程足够: watchdog 仅在超时后做一次延迟检查, 任务非常轻量. */
  private int poolSize = 1;

  /** 线程名前缀, 与原 {@code worker-task-cancel-watchdog-N} 命名保持一致, 便于运维识别. */
  private String threadNamePrefix = "worker-task-cancel-watchdog-";

  /** Spring 容器关闭时等待已提交任务的最长时间 (秒). */
  private int awaitTerminationSeconds = 5;
}
