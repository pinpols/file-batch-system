package com.example.batch.console.domain.observability.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.console.config.ConsoleRealtimeProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

// LENIENT:每个 @Test 内部 stub `redisTemplate.opsForList()`,但 append() 走
// executePipelined 不读 opsForList,strict 模式会报 UnnecessaryStubbing。
@SuppressWarnings("unchecked")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConsoleRealtimeReplayStoreTest {

  @Mock private StringRedisTemplate redisTemplate;
  @Mock private ListOperations<String, String> listOperations;

  @Test
  void shouldReplayEventsAfterCursor() {
    when(redisTemplate.opsForList()).thenReturn(listOperations);
    ConsoleRealtimeReplayStore store =
        new ConsoleRealtimeReplayStore(
            redisTemplate, properties(), new ConsoleRealtimeMetrics(new SimpleMeterRegistry()));
    ConsoleRealtimeStreamEnvelope first =
        new ConsoleRealtimeStreamEnvelope(
            "console-a",
            "t1",
            "alerts",
            "alert-updated",
            "cursor-1",
            false,
            JsonUtils.toJson("a"),
            BatchDateTimeSupport.utcNow());
    ConsoleRealtimeStreamEnvelope second =
        new ConsoleRealtimeStreamEnvelope(
            "console-a",
            "t1",
            "alerts",
            "alert-updated",
            "cursor-2",
            false,
            JsonUtils.toJson("b"),
            BatchDateTimeSupport.utcNow());
    // P1(2026-05-23 audit):replay 改用 LRANGE 0 (replayMaxEntries - 1) 截断,与 properties() 配对。
    when(listOperations.range("batch:console:realtime:buffer:t1:alerts", 0, 19_999L))
        .thenReturn(List.of(JsonUtils.toJson(first), JsonUtils.toJson(second)));

    ConsoleRealtimeReplayStore.ReplayBatch replay = store.replay("t1", "alerts", "cursor-1", null);

    assertThat(replay.cursorFound()).isTrue();
    assertThat(replay.events()).hasSize(1);
    assertThat(replay.events().getFirst().cursor()).isEqualTo("cursor-2");
  }

  @Test
  void shouldTrimAndExpireReplayBufferUsingConfiguredRetention() {
    when(redisTemplate.opsForList()).thenReturn(listOperations);
    ConsoleRealtimeReplayStore store =
        new ConsoleRealtimeReplayStore(
            redisTemplate, properties(), new ConsoleRealtimeMetrics(new SimpleMeterRegistry()));

    store.append(
        new ConsoleRealtimeStreamEnvelope(
            "console-a",
            "t1",
            "alerts",
            "alert-updated",
            "cursor-1",
            false,
            JsonUtils.toJson("a"),
            BatchDateTimeSupport.utcNow()));

    // append() 使用单个管线化的低层回调（rPush + lTrim + expire），而非 opsForList()/expire()
    verify(redisTemplate).executePipelined(any(RedisCallback.class));
  }

  private ConsoleRealtimeProperties properties() {
    ConsoleRealtimeProperties properties = new ConsoleRealtimeProperties();
    properties.setReplayMaxEntries(20_000L);
    properties.setReplayTtl(Duration.ofHours(24));
    return properties;
  }
}
