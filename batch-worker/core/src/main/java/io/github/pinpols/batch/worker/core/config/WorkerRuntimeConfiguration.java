package io.github.pinpols.batch.worker.core.config;

/** Worker 跨模块运行配置键与默认值的单一来源。 */
public final class WorkerRuntimeConfiguration {

  public static final String MAX_CONCURRENT_TASKS_PROPERTY = "batch.worker.max-concurrent-tasks";
  public static final int DEFAULT_MAX_CONCURRENT_TASKS = 8;
  public static final String MAX_CONCURRENT_TASKS_PLACEHOLDER =
      "${batch.worker.max-concurrent-tasks:8}";

  private WorkerRuntimeConfiguration() {}
}
