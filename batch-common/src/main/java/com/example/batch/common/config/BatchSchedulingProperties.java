package com.example.batch.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "batch.scheduling")
public class BatchSchedulingProperties {

  /** batch 各服务中 `@Scheduled` 任务共用的调度线程池大小。 */
  private int poolSize = 8;

  /** 调度线程名前缀。 */
  private String threadNamePrefix = "batch-scheduler-";

  /** 服务关闭时，共享调度器是否等待正在执行的任务完成。 */
  private boolean waitForTasksToCompleteOnShutdown = false;

  /** 关闭时等待任务完成的最长时间。 */
  private int awaitTerminationSeconds = 30;

  /** 开始关闭后，已调度的周期任务是否继续执行。 */
  private boolean continueExistingPeriodicTasksAfterShutdown = false;

  /** 开始关闭后，已调度的延迟任务是否继续执行。 */
  private boolean executeExistingDelayedTasksAfterShutdown = false;
}
