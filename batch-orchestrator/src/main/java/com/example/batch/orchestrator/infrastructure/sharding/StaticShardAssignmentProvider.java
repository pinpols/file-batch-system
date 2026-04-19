package com.example.batch.orchestrator.infrastructure.sharding;

import com.example.batch.orchestrator.config.OutboxProperties;

/**
 * 静态 shard 分配源：直接从 {@link OutboxProperties} 读取 {@code shardTotal} / {@code shardIndex}。
 *
 * <p>对应部署模式：StatefulSet + 环境变量 {@code BATCH_OUTBOX_SHARD_TOTAL} / {@code
 * BATCH_OUTBOX_SHARD_INDEX}，扩缩容靠 helm upgrade 重滚。与老行为 100% 一致。
 */
public final class StaticShardAssignmentProvider implements ShardAssignmentProvider {

  private final OutboxProperties properties;

  public StaticShardAssignmentProvider(OutboxProperties properties) {
    this.properties = properties;
  }

  @Override
  public ShardAssignment current() {
    int total = Math.max(1, properties.getShardTotal());
    int index = properties.getShardIndex();
    if (total == 1) {
      return ShardAssignment.single();
    }
    return new ShardAssignment(total, index);
  }
}
