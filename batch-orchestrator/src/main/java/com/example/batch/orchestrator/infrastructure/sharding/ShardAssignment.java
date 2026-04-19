package com.example.batch.orchestrator.infrastructure.sharding;

/**
 * 当前 Pod 应该处理的 shard 分配。
 *
 * <p>{@code shardIndex} 在 {@code [0, shardTotal)} 区间；{@code shardTotal=1 / shardIndex=0}
 * 表示单实例模式（退化为无分片）。
 *
 * @param shardTotal 当前集群中活跃的 orchestrator 实例数（>= 1）
 * @param shardIndex 当前 Pod 在集群中的位置（0 基，< shardTotal）
 */
public record ShardAssignment(int shardTotal, int shardIndex) {

  public ShardAssignment {
    if (shardTotal < 1) {
      throw new IllegalArgumentException("shardTotal 必须 >= 1，当前：" + shardTotal);
    }
    if (shardIndex < 0 || shardIndex >= shardTotal) {
      throw new IllegalArgumentException(
          "shardIndex 必须在 [0, shardTotal=" + shardTotal + ") 区间，当前：" + shardIndex);
    }
  }

  /** 单实例模式：shardTotal=1, shardIndex=0。 */
  public static ShardAssignment single() {
    return new ShardAssignment(1, 0);
  }
}
