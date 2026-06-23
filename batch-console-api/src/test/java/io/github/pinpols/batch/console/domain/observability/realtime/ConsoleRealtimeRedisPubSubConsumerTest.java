package io.github.pinpols.batch.console.domain.observability.realtime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.utils.JsonUtils;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.core.StringRedisTemplate;

// LENIENT:message() 工厂内 stub `Message.getChannel()` 用于语义文档化(消息匹配 publisher channel),
// 部分 consumer 路径仅读 body 不读 channel,strict 会误判 UnnecessaryStubbing。
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConsoleRealtimeRedisPubSubConsumerTest {

  @Mock private StringRedisTemplate redisTemplate;
  @Mock private ConsoleRealtimeEventHub realtimeEventHub;
  @Mock private ConsoleOpsSummaryRealtimeStream summaryRealtimeStream;
  @Mock private ConsoleRealtimeInstanceIdProvider instanceIdProvider;
  private final ConsoleRealtimeMetrics realtimeMetrics =
      new ConsoleRealtimeMetrics(new SimpleMeterRegistry());

  private ConsoleRealtimeRedisPubSubConsumer consumer;

  @BeforeEach
  void setUp() {
    when(instanceIdProvider.instanceId()).thenReturn("console-a");
    consumer =
        new ConsoleRealtimeRedisPubSubConsumer(
            redisTemplate,
            realtimeEventHub,
            summaryRealtimeStream,
            instanceIdProvider,
            realtimeMetrics);
  }

  @Test
  void shouldIgnoreMessagesPublishedBySameInstance() {
    ConsoleRealtimeStreamEnvelope envelope =
        new ConsoleRealtimeStreamEnvelope(
            "console-a",
            "t1",
            "workflow-definitions",
            "workflow-definition-updated",
            "cursor-1",
            false,
            JsonUtils.toJson("payload"),
            Instant.parse("2026-04-05T10:00:00Z"));

    consumer.onMessage(message(envelope), null);

    verify(realtimeEventHub, never()).publish(any());
  }

  @Test
  void shouldForwardMessagesFromOtherInstances() {
    ConsoleRealtimeStreamEnvelope envelope =
        new ConsoleRealtimeStreamEnvelope(
            "console-b",
            "t1",
            "workflow-definitions",
            "workflow-definition-updated",
            "cursor-1",
            false,
            JsonUtils.toJson("payload"),
            Instant.parse("2026-04-05T10:00:00Z"));

    consumer.onMessage(message(envelope), null);

    verify(realtimeEventHub).publish(any());
  }

  @Test
  void shouldUseSummarySnapshotFromPayloadWithoutReloadingDb() {
    ConsoleRealtimeStreamEnvelope envelope =
        new ConsoleRealtimeStreamEnvelope(
            "console-b",
            "t1",
            "ops-summary",
            "ops-summary-updated",
            "cursor-2",
            true,
            "",
            Instant.parse("2026-04-05T10:00:01Z"));

    consumer.onMessage(message(envelope), null);

    verify(summaryRealtimeStream).publishSnapshot("t1");
    verify(realtimeEventHub, never()).publish(any());
  }

  private Message message(ConsoleRealtimeStreamEnvelope envelope) {
    Message message = mock(Message.class);
    when(message.getBody()).thenReturn(JsonUtils.toJson(envelope).getBytes(StandardCharsets.UTF_8));
    when(message.getChannel())
        .thenReturn(ConsoleRealtimeRedisPublisher.CHANNEL_KEY.getBytes(StandardCharsets.UTF_8));
    return message;
  }
}
