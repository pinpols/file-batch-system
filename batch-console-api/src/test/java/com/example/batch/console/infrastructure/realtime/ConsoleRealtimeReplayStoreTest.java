package com.example.batch.console.infrastructure.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.utils.JsonUtils;
import com.example.batch.console.config.ConsoleRealtimeProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

@SuppressWarnings("unchecked")
class ConsoleRealtimeReplayStoreTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final ListOperations<String, String> listOperations = mock(ListOperations.class);

    @Test
    void shouldReplayEventsAfterCursor() {
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        ConsoleRealtimeReplayStore store = new ConsoleRealtimeReplayStore(
                redisTemplate,
                properties(),
                new ConsoleRealtimeMetrics(new SimpleMeterRegistry())
        );
        ConsoleRealtimeStreamEnvelope first = new ConsoleRealtimeStreamEnvelope(
                "console-a", "t1", "alerts", "alert-updated", "cursor-1", false, JsonUtils.toJson("a"), Instant.now());
        ConsoleRealtimeStreamEnvelope second = new ConsoleRealtimeStreamEnvelope(
                "console-a", "t1", "alerts", "alert-updated", "cursor-2", false, JsonUtils.toJson("b"), Instant.now());
        when(listOperations.range("batch:console:realtime:buffer:t1:alerts", 0, -1))
                .thenReturn(List.of(JsonUtils.toJson(first), JsonUtils.toJson(second)));

        ConsoleRealtimeReplayStore.ReplayBatch replay = store.replay("t1", "alerts", "cursor-1", null);

        assertThat(replay.cursorFound()).isTrue();
        assertThat(replay.events()).hasSize(1);
        assertThat(replay.events().getFirst().cursor()).isEqualTo("cursor-2");
    }

    @Test
    void shouldTrimAndExpireReplayBufferUsingConfiguredRetention() {
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        ConsoleRealtimeReplayStore store = new ConsoleRealtimeReplayStore(
                redisTemplate,
                properties(),
                new ConsoleRealtimeMetrics(new SimpleMeterRegistry())
        );

        store.append(new ConsoleRealtimeStreamEnvelope(
                "console-a", "t1", "alerts", "alert-updated", "cursor-1", false, JsonUtils.toJson("a"), Instant.now()));

        // append() uses a single pipelined low-level callback (rPush + lTrim + expire), not opsForList()/expire()
        verify(redisTemplate).executePipelined(any(RedisCallback.class));
    }

    private ConsoleRealtimeProperties properties() {
        ConsoleRealtimeProperties properties = new ConsoleRealtimeProperties();
        properties.setReplayMaxEntries(20_000L);
        properties.setReplayTtl(Duration.ofHours(24));
        return properties;
    }
}
