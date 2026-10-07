package io.github.pinpols.batch.worker.core.config;

import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Worker 并发上限配置（{@code batch.worker} 前缀）。
 *
 * <p>把原先散落在 5 个 WorkerLoop、5 个 TaskConsumer、{@code AbstractTaskConsumer}、
 * {@code KafkaConsumerConfiguration}、{@code TaskExecutionPool} 与 {@code WorkerStartupRuntimeAudit}
 * 中重复读取的并发上限 key 收敛为统一类型安全配置，保留原有 key 与默认值。
 *
 * <p>键名与默认值的唯一定义仍在 {@link WorkerRuntimeConfiguration}
 * （{@code MAX_CONCURRENT_TASKS_PROPERTY} / {@code DEFAULT_MAX_CONCURRENT_TASKS}），本类只承载绑定与默认值引用。
 *
 * <p>该 key 直接挂在 {@code batch.worker} 前缀下（无二级分组），与 {@link WorkerTempFileProperties}
 * 共用前缀但字段互不重叠，属既有的扁平 key 约定。
 */
@Data
@Validated
@ConfigurationProperties(prefix = "batch.worker")
@SuppressWarnings("ConfigurationProperties") // 与 WorkerTempFileProperties 共享前缀，子键互不重叠。
public class WorkerConcurrencyProperties {

  /** 单个 worker 实例允许并发执行的最大任务数。 */
  @Min(value = 1, message = "worker max concurrent tasks must be positive")
  private int maxConcurrentTasks = WorkerRuntimeConfiguration.DEFAULT_MAX_CONCURRENT_TASKS;
}
