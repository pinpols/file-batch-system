package com.example.batch.orchestrator.infrastructure.sharding;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 基于 Redis Sorted Set 的动态 shard 分配：
 *
 * <ul>
 *   <li>每个 Pod 定时 {@link #heartbeat()} 把自己的标识写到 {@code batch:orchestrator:members}，
 *       score = 最近心跳时间戳
 *   <li>读取分配时先 {@code ZREMRANGEBYSCORE} 清理超期（超过 {@link #memberTtl}）成员，
 *       再 {@code ZRANGE} 取活 Pod 列表；成员名按字典序排序得到确定顺序
 *   <li>当前 Pod 的 index = 自己在排序列表中的位置；total = 列表长度
 * </ul>
 *
 * <p>容错：Redis 读写任何异常发生时，回退到上一次成功读到的 {@link ShardAssignment}，
 * 保证调度不中断。集群启动时若 Redis 不可用，首次 {@link #current()} 返回
 * {@link ShardAssignment#single()}（退化为单实例）。
 */
@Slf4j
public class RedisShardAssignmentProvider implements ShardAssignmentProvider {

  private static final String MEMBERS_KEY = "batch:orchestrator:members";

  private final StringRedisTemplate redis;
  private final String memberId;
  private final Duration memberTtl;

  /** 缓存上次成功读到的 assignment，Redis 异常时兜底用。 */
  private final AtomicReference<ShardAssignment> lastKnown =
      new AtomicReference<>(ShardAssignment.single());

  /**
   * @param redis Spring Data Redis StringRedisTemplate（和 ShedLock / 其它业务共用连接）
   * @param memberId 当前 Pod 的稳定标识（推荐 K8s POD_NAME；单副本也可用主机名）
   * @param memberTtl 心跳超期时长，超过该时长未心跳的成员视为死亡并清出列表（推荐 30s）
   */
  public RedisShardAssignmentProvider(
      StringRedisTemplate redis, String memberId, Duration memberTtl) {
    if (memberId == null || memberId.isBlank()) {
      throw new IllegalArgumentException("memberId 不能为空");
    }
    this.redis = redis;
    this.memberId = memberId;
    this.memberTtl = memberTtl;
  }

  /**
   * 心跳：把自己的 memberId 写入 sorted set，score = 当前毫秒时间戳。
   *
   * <p>由 {@code @Scheduled} 每 5s 调一次（见 {@code fixedDelayString}）。
   */
  @Scheduled(fixedDelayString = "${batch.outbox.sharding.heartbeat-interval-ms:5000}")
  public void heartbeat() {
    try {
      redis.opsForZSet().add(MEMBERS_KEY, memberId, System.currentTimeMillis());
    } catch (RuntimeException ex) {
      log.warn("Shard coordinator heartbeat failed: member={}, err={}", memberId, ex.toString());
    }
  }

  /**
   * 优雅退出时移除自己，加速其他 Pod 感知（不强制——即使不调，下次 evict 阶段 TTL 会兜）。
   */
  public void leave() {
    try {
      redis.opsForZSet().remove(MEMBERS_KEY, memberId);
    } catch (RuntimeException ex) {
      log.warn("Shard coordinator leave failed: member={}, err={}", memberId, ex.toString());
    }
  }

  @Override
  public ShardAssignment current() {
    try {
      long cutoff = System.currentTimeMillis() - memberTtl.toMillis();
      // 清死成员
      redis.opsForZSet().removeRangeByScore(MEMBERS_KEY, 0, cutoff);
      // 取活成员（按 score 升序 = 按心跳时间升序，但我们要确定性顺序，所以 toArray 后字典序排）
      Set<ZSetOperations.TypedTuple<String>> tuples =
          redis.opsForZSet().rangeWithScores(MEMBERS_KEY, 0, -1);
      if (tuples == null || tuples.isEmpty()) {
        // 空集合：可能自己心跳还没发（首次 current() 在 heartbeat 之前），降级为单实例
        return cacheAndReturn(ShardAssignment.single());
      }
      String[] members = tuples.stream()
          .map(ZSetOperations.TypedTuple::getValue)
          .filter(v -> v != null && !v.isBlank())
          .sorted()
          .toArray(String[]::new);

      int total = members.length;
      int index = -1;
      for (int i = 0; i < total; i++) {
        if (memberId.equals(members[i])) {
          index = i;
          break;
        }
      }
      if (index < 0) {
        // 自己不在集合里：可能刚启动还没心跳，或被误清；降级为单实例
        return cacheAndReturn(ShardAssignment.single());
      }
      return cacheAndReturn(total == 1 ? ShardAssignment.single() : new ShardAssignment(total, index));
    } catch (RuntimeException ex) {
      ShardAssignment fallback = lastKnown.get();
      log.warn(
          "Redis shard coordinator 查询失败，fallback 到上次值 total={} index={}: {}",
          fallback.shardTotal(),
          fallback.shardIndex(),
          ex.toString());
      return fallback;
    }
  }

  private ShardAssignment cacheAndReturn(ShardAssignment assignment) {
    lastKnown.set(assignment);
    return assignment;
  }
}
