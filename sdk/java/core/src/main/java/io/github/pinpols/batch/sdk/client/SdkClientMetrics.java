package io.github.pinpols.batch.sdk.client;

/**
 * SDK 运行时指标快照 — {@link BatchPlatformClient#metrics()} 的返回值。
 *
 * <p>租户可在自己进程里把字段映射到 Prometheus / 内部监控:
 *
 * <pre>{@code
 * SdkClientMetrics m = client.metrics();
 * gauge("batch_sdk_inflight", m.inFlightTaskCount(), Tags.of("tenant", m.tenantId()));
 * gauge("batch_sdk_healthy", m.healthy() ? 1 : 0);
 * }</pre>
 *
 * <p>Phase 1 §3.1 #1.6 / #SDK-P1-4。
 *
 * @param tenantId 租户标识(label 用)
 * @param workerCode worker 标识(label 用)
 * @param started 是否已调 {@link BatchPlatformClient#start()}
 * @param healthy 等价 {@link BatchPlatformClient#isHealthy()}:started && !fatal && !crashed
 * @param inFlightTaskCount 当前正在执行的任务数(dispatcher 线程池占用)
 * @param maxConcurrentTasks 配置的最大并发(线程池大小上限)
 * @param registeredHandlerCount 已 register 的 handler 数
 * @param dispatcherFatal CLAIM 401/403 后置位,后续 dispatch 全部 drop(参考 P1-2)
 * @param dispatcherDraining stop() 已发起,等待 in-flight 跑完
 * @param consumerCrashed Kafka poll loop 因非预期 Throwable 退出(参考 #1.7)
 * @param kafkaConsumerLag 最近一次 poll 观测到的最大 consumer lag(条数),{@code -1} = 未知(参考 P7-1)
 */
public record SdkClientMetrics(
    String tenantId,
    String workerCode,
    boolean started,
    boolean healthy,
    int inFlightTaskCount,
    int maxConcurrentTasks,
    int registeredHandlerCount,
    boolean dispatcherFatal,
    boolean dispatcherDraining,
    boolean consumerCrashed,
    long kafkaConsumerLag) {}
