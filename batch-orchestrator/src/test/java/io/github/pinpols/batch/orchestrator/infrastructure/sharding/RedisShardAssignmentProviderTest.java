package io.github.pinpols.batch.orchestrator.infrastructure.sharding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;

@ExtendWith(MockitoExtension.class)
@DisplayName("Redis 分片分配提供者:心跳与离开对轮询资格的维护,以及成员集合变化和读取异常下的分片计算")
class RedisShardAssignmentProviderTest {

  private static final String MEMBERS_KEY = "batch:test:orchestrator:members";

  @Mock
  private StringRedisTemplate redis;

  @Mock
  private ZSetOperations<String, String> zset;

  private RedisShardAssignmentProvider provider(String memberId) {
    when(redis.opsForZSet()).thenReturn(zset);
    return new RedisShardAssignmentProvider(redis, memberId, MEMBERS_KEY, Duration.ofSeconds(30));
  }

  @Test
  @DisplayName("心跳按成员标识写入成员集合的过期分数,维持自身在线")
  void shouldWriteMemberScore_whenHeartbeatFires() {
    RedisShardAssignmentProvider p = provider("orch-0");
    p.heartbeat();
    verify(zset).add(eq(MEMBERS_KEY), eq("orch-0"), anyDouble());
  }

  @Test
  @DisplayName("成员离开时从集合移除自身并停止后续轮询")
  void shouldRemoveSelfAndStopPolling_whenMemberLeaves() {
    RedisShardAssignmentProvider p = provider("orch-0");
    p.heartbeat();

    p.leave();

    verify(zset).remove(MEMBERS_KEY, "orch-0");
    assertThat(p.canPoll()).isFalse();
  }

  @Test
  @DisplayName("三个成员在线时按字典序返回本成员的稳定分片下标,并允许继续轮询")
  void shouldReturnOwnShardIndex_whenThreeMembersOnline() {
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
    assertThat(p.canPoll()).isTrue();
  }

  @Test
  @DisplayName("仅自身在线时返回单分片且下标为零,并允许继续轮询")
  void shouldReturnSingleShard_whenOnlySelfOnline() {
    RedisShardAssignmentProvider p = provider("orch-0");
    Set<TypedTuple<String>> tuples = new LinkedHashSet<>();
    tuples.add(new DefaultTypedTuple<>("orch-0", 1.0));
    when(zset.rangeWithScores(anyString(), eq(0L), eq(-1L))).thenReturn(tuples);

    ShardAssignment a = p.current();

    assertThat(a.shardTotal()).isEqualTo(1);
    assertThat(a.shardIndex()).isZero();
    assertThat(p.canPoll()).isTrue();
  }

  @Test
  @DisplayName("成员集合为空时退化为单分片并停止轮询")
  void shouldFallbackToSingleShardAndStopPolling_whenNoMembersOnline() {
    RedisShardAssignmentProvider p = provider("orch-0");
    when(zset.rangeWithScores(anyString(), eq(0L), eq(-1L))).thenReturn(Set.of());

    ShardAssignment a = p.current();

    assertThat(a.shardTotal()).isEqualTo(1);
    assertThat(p.canPoll()).isFalse();
  }

  @Test
  @DisplayName("自身不在成员集合时退化为单分片并停止轮询,避免处理他人分片")
  void shouldFallbackToSingleShardAndStopPolling_whenSelfMissingFromMembers() {
    RedisShardAssignmentProvider p = provider("orch-missing");
    Set<TypedTuple<String>> tuples = new LinkedHashSet<>();
    tuples.add(new DefaultTypedTuple<>("orch-0", 1.0));
    tuples.add(new DefaultTypedTuple<>("orch-1", 2.0));
    when(zset.rangeWithScores(anyString(), eq(0L), eq(-1L))).thenReturn(tuples);

    ShardAssignment a = p.current();

    // 自己不在集合里，降级为 single（避免以为还在 rebalance 就处理别人的分片）
    assertThat(a.shardTotal()).isEqualTo(1);
    assertThat(p.canPoll()).isFalse();
  }

  @Test
  @DisplayName("读取成员集合异常时沿用上次成功结果且不抛异常,并停止轮询")
  void shouldReuseLastKnownAssignment_whenMemberReadFails() {
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
    assertThat(p.canPoll()).isFalse();
  }

  @Test
  @DisplayName("读取当前分配前先清理超期成员,避免把过期成员计入分片")
  void shouldCleanStaleMembersBeforeReading_whenQueryingCurrentAssignment() {
    RedisShardAssignmentProvider p = provider("orch-0");
    Set<TypedTuple<String>> tuples = new LinkedHashSet<>();
    tuples.add(new DefaultTypedTuple<>("orch-0", 1.0));
    when(zset.rangeWithScores(anyString(), eq(0L), eq(-1L))).thenReturn(tuples);

    p.current();

    // 验证先调 removeRangeByScore 清理超期成员，再调 rangeWithScores
    verify(zset, times(1)).removeRangeByScore(eq(MEMBERS_KEY), eq(0D), anyDouble());
  }
}
