package com.example.batch.orchestrator.infrastructure.sharding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;

class RedisShardAssignmentProviderTest {

  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

  @SuppressWarnings("unchecked")
  private final ZSetOperations<String, String> zset = mock(ZSetOperations.class);

  private RedisShardAssignmentProvider provider(String memberId) {
    when(redis.opsForZSet()).thenReturn(zset);
    return new RedisShardAssignmentProvider(redis, memberId, Duration.ofSeconds(30));
  }

  @Test
  void heartbeat_writes_score_with_member_id() {
    RedisShardAssignmentProvider p = provider("orch-0");
    p.heartbeat();
    verify(zset).add(eq("batch:orchestrator:members"), eq("orch-0"), anyDouble());
  }

  @Test
  void current_with_three_members_returns_correct_index() {
    RedisShardAssignmentProvider p = provider("orch-1");
    // 返回 3 个 member，字典序 orch-0 / orch-1 / orch-2
    Set<TypedTuple<String>> tuples = new LinkedHashSet<>();
    tuples.add(new DefaultTypedTuple<>("orch-2", 1.0));
    tuples.add(new DefaultTypedTuple<>("orch-0", 2.0));
    tuples.add(new DefaultTypedTuple<>("orch-1", 3.0));
    when(zset.rangeWithScores(anyString(), eq(0L), eq(-1L))).thenReturn(tuples);

    ShardAssignment a = p.current();

    assertThat(a.shardTotal()).isEqualTo(3);
    assertThat(a.shardIndex()).isEqualTo(1); // orch-1 字典序第 2 位（index=1）
  }

  @Test
  void current_single_member_returns_single() {
    RedisShardAssignmentProvider p = provider("orch-0");
    Set<TypedTuple<String>> tuples = new LinkedHashSet<>();
    tuples.add(new DefaultTypedTuple<>("orch-0", 1.0));
    when(zset.rangeWithScores(anyString(), eq(0L), eq(-1L))).thenReturn(tuples);

    ShardAssignment a = p.current();

    assertThat(a.shardTotal()).isEqualTo(1);
    assertThat(a.shardIndex()).isEqualTo(0);
  }

  @Test
  void current_empty_members_returns_single() {
    RedisShardAssignmentProvider p = provider("orch-0");
    when(zset.rangeWithScores(anyString(), eq(0L), eq(-1L))).thenReturn(Set.of());

    ShardAssignment a = p.current();

    assertThat(a.shardTotal()).isEqualTo(1);
  }

  @Test
  void current_self_not_in_members_returns_single() {
    RedisShardAssignmentProvider p = provider("orch-missing");
    Set<TypedTuple<String>> tuples = new LinkedHashSet<>();
    tuples.add(new DefaultTypedTuple<>("orch-0", 1.0));
    tuples.add(new DefaultTypedTuple<>("orch-1", 2.0));
    when(zset.rangeWithScores(anyString(), eq(0L), eq(-1L))).thenReturn(tuples);

    ShardAssignment a = p.current();

    // 自己不在集合里，降级为 single（避免以为还在 rebalance 就处理别人的分片）
    assertThat(a.shardTotal()).isEqualTo(1);
  }

  @Test
  void current_redis_exception_returns_last_known() {
    RedisShardAssignmentProvider p = provider("orch-0");
    // 先成功一次
    Set<TypedTuple<String>> ok = new LinkedHashSet<>();
    ok.add(new DefaultTypedTuple<>("orch-0", 1.0));
    ok.add(new DefaultTypedTuple<>("orch-1", 2.0));
    when(zset.rangeWithScores(anyString(), eq(0L), eq(-1L))).thenReturn(ok);
    ShardAssignment first = p.current();
    assertThat(first.shardTotal()).isEqualTo(2);

    // 然后模拟 Redis 异常
    when(zset.rangeWithScores(anyString(), eq(0L), eq(-1L)))
        .thenThrow(new RuntimeException("connection refused"));

    ShardAssignment fallback = p.current();
    // 返回上次成功值，不抛异常
    assertThat(fallback.shardTotal()).isEqualTo(first.shardTotal());
    assertThat(fallback.shardIndex()).isEqualTo(first.shardIndex());
  }

  @Test
  void current_cleans_stale_members_before_read() {
    RedisShardAssignmentProvider p = provider("orch-0");
    Set<TypedTuple<String>> tuples = new LinkedHashSet<>();
    tuples.add(new DefaultTypedTuple<>("orch-0", 1.0));
    when(zset.rangeWithScores(anyString(), eq(0L), eq(-1L))).thenReturn(tuples);

    p.current();

    // 验证先调 removeRangeByScore 清理超期成员，再调 rangeWithScores
    verify(zset, times(1))
        .removeRangeByScore(eq("batch:orchestrator:members"), eq(0D), anyDouble());
  }
}
