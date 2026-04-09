package com.example.batch.console.infrastructure.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.example.batch.common.utils.JsonUtils;
import java.nio.charset.StandardCharsets;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.core.StringRedisTemplate;

class ConsoleRealtimeRedisPubSubConsumerTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final ConsoleRealtimeEventHub realtimeEventHub = mock(ConsoleRealtimeEventHub.class);
    private final ConsoleOpsSummaryRealtimeStream summaryRealtimeStream = mock(ConsoleOpsSummaryRealtimeStream.class);
    private final ConsoleRealtimeInstanceIdProvider instanceIdProvider = mock(ConsoleRealtimeInstanceIdProvider.class);
    private final ConsoleRealtimeMetrics realtimeMetrics = new ConsoleRealtimeMetrics(new SimpleMeterRegistry());

    private ConsoleRealtimeRedisPubSubConsumer consumer;

    @BeforeEach
    void setUp() {
        when(instanceIdProvider.instanceId()).thenReturn("console-a");
        consumer = new ConsoleRealtimeRedisPubSubConsumer(
                redisTemplate,
                realtimeEventHub,
                summaryRealtimeStream,
                instanceIdProvider,
                realtimeMetrics
        );
    }

    @Test
    void shouldIgnoreMessagesPublishedBySameInstance() {
        ConsoleRealtimeStreamEnvelope envelope = new ConsoleRealtimeStreamEnvelope(
                "console-a",
                "t1",
                "workflow-definitions",
                "workflow-definition-updated",
                "cursor-1",
                false,
                JsonUtils.toJson("payload"),
                java.time.Instant.parse("2026-04-05T10:00:00Z")
        );

        consumer.onMessage(message(envelope), null);

        verify(realtimeEventHub, org.mockito.Mockito.never()).publish(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldForwardMessagesFromOtherInstances() {
        ConsoleRealtimeStreamEnvelope envelope = new ConsoleRealtimeStreamEnvelope(
                "console-b",
                "t1",
                "workflow-definitions",
                "workflow-definition-updated",
                "cursor-1",
                false,
                JsonUtils.toJson("payload"),
                java.time.Instant.parse("2026-04-05T10:00:00Z")
        );

        consumer.onMessage(message(envelope), null);

        verify(realtimeEventHub).publish(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldUseSummarySnapshotFromPayloadWithoutReloadingDb() {
        ConsoleRealtimeStreamEnvelope envelope = new ConsoleRealtimeStreamEnvelope(
                "console-b",
                "t1",
                "ops-summary",
                "ops-summary-updated",
                "cursor-2",
                true,
                "",
                java.time.Instant.parse("2026-04-05T10:00:01Z")
        );

        consumer.onMessage(message(envelope), null);

        verify(summaryRealtimeStream).publishSnapshot("t1");
        verify(realtimeEventHub, never()).publish(any());
    }

    private Message message(ConsoleRealtimeStreamEnvelope envelope) {
        Message message = mock(Message.class);
        when(message.getBody()).thenReturn(JsonUtils.toJson(envelope).getBytes(StandardCharsets.UTF_8));
        when(message.getChannel()).thenReturn(ConsoleRealtimeRedisPublisher.CHANNEL_KEY.getBytes(StandardCharsets.UTF_8));
        return message;
    }
}
