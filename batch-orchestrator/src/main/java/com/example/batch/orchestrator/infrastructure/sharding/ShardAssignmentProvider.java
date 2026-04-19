package com.example.batch.orchestrator.infrastructure.sharding;

/**
 * Outbox 等分片任务的 shard 分配源。
 *
 * <p>每次调度轮询前调用 {@link #current()} 获取最新 {@link ShardAssignment}。实现可以是静态
 * （从 ENV 读），也可以是动态（Redis 协调活跃实例）。
 *
 * <p>实现契约：
 *
 * <ul>
 *   <li>{@link #current()} 必须尽可能快（调度器每次 poll 前都会调用），内部可以缓存
 *   <li>返回值必须 non-null；即使协调失败也应 fallback 到上次已知值或 {@link ShardAssignment#single()}
 *   <li>必须保证同一时刻集群内不同 Pod 返回的 {@link ShardAssignment#shardTotal()} 一致；
 *       {@link ShardAssignment#shardIndex()} 两两不重复
 * </ul>
 */
public interface ShardAssignmentProvider {

  /**
   * 返回当前 Pod 应该处理的 shard 分配。
   *
   * @return 非 null 的当前分配
   */
  ShardAssignment current();
}
