package com.example.batch.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "batch.scheduling")
public class BatchSchedulingProperties {

  /**
   * batch 各服务中 `@Scheduled` 任务共用的调度线程池大小。
   *
   * <p>orchestrator 现有 ~55 个 `@Scheduled` bean 共享本池；默认 16 给 3-4 个并发长任务（archive / snapshot / sla
   * 扫描）+ heartbeat 类轻任务留出余量。设小了高频心跳会被长任务卡住。
   */
  private int poolSize = 16;

  /** 调度线程名前缀。 */
  private String threadNamePrefix = "batch-scheduler-";

  /**
   * 共享调度器的 Spring {@code SmartLifecycle} phase。
   *
   * <p>Spring 会先停止 phase 更高的组件。Redis LettuceConnectionFactory 默认 phase 为 0，所以把调度器放在较高
   * phase，可确保定时任务先停止并排水，再销毁 Redis 连接。
   */
  private int phase = 1_073_741_823;

  /**
   * 服务关闭时，共享调度器是否等待正在执行的任务完成。
   *
   * <p>默认 true 避免 archive / outbox / snapshot 类长任务被强杀留下"删一半"状态。配合 {@link #awaitTerminationSeconds}
   * 给长任务充足时间收尾。
   */
  private boolean waitForTasksToCompleteOnShutdown = true;

  /**
   * 关闭时等待任务完成的最长时间。
   *
   * <p>默认 120s 给 `OutboxArchiveScheduler`（lockAtMost=30min）/ `SuccessInstanceArchiveScheduler`
   * （lockAtMost=2h）等长任务正常退出窗口；超时仍会强制中断，不会无限卡住。
   */
  private int awaitTerminationSeconds = 120;

  /** 开始关闭后，已调度的周期任务是否继续执行。 */
  private boolean continueExistingPeriodicTasksAfterShutdown = false;

  /** 开始关闭后，已调度的延迟任务是否继续执行。 */
  private boolean executeExistingDelayedTasksAfterShutdown = false;
}
