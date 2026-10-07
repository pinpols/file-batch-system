package io.github.pinpols.batch.console.domain.observability.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.infrastructure.realtime.ConsoleRealtimeRedisPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

@ExtendWith(MockitoExtension.class)
@DisplayName("实时事件发布器: 空事件短路与回放缓冲加频道的双写")
class ConsoleRealtimeRedisPublisherTest {

  @Mock
  private StringRedisTemplate redisTemplate;

  @Mock
  private ConsoleRealtimeInstanceIdProvider instanceIdProvider;

  @Mock
  private RealtimeReplayStore replayStore;

  private ConsoleRealtimeRedisPublisher publisher;

  @BeforeEach
  void setUp() {
    publisher = new ConsoleRealtimeRedisPublisher(redisTemplate, instanceIdProvider, replayStore);
  }

  @Test
  @DisplayName("事件为空时不写回放缓冲也不发往通知频道")
  void shouldSkipPublish_whenEventNull() {
    publisher.publish(null);

    verify(replayStore, never()).append(any());
    verify(redisTemplate, never()).convertAndSend(anyString(), anyString());
  }

  @Test
  @DisplayName("事件有效时写回放缓冲并发送到通知频道, 信封字段取自事件内容")
  void shouldAppendToReplayStoreAndPublish_whenEventValid() {
    when(instanceIdProvider.instanceId()).thenReturn("instance-1");
    ConsoleSseEvent event = new ConsoleSseEvent(
        "t1", "job-instance", "JOB_STATUS", "cursor-abc", "payload", BatchDateTimeSupport.utcNow());

    publisher.publish(event);

    ArgumentCaptor<ConsoleRealtimeStreamEnvelope> envelopeCaptor =
        ArgumentCaptor.forClass(ConsoleRealtimeStreamEnvelope.class);
    verify(replayStore).append(envelopeCaptor.capture());
    ConsoleRealtimeStreamEnvelope envelope = envelopeCaptor.getValue();
    assertThat(envelope.originInstanceId()).isEqualTo("instance-1");
    assertThat(envelope.tenantId()).isEqualTo("t1");
    assertThat(envelope.stream()).isEqualTo("job-instance");
    assertThat(envelope.eventType()).isEqualTo("JOB_STATUS");
    assertThat(envelope.cursor()).isEqualTo("cursor-abc");

    verify(redisTemplate)
        .convertAndSend(eq(ConsoleRealtimeRedisPublisher.CHANNEL_KEY), anyString());
  }

  @Test
  @DisplayName("事件载荷为空时写入回放缓冲的载荷内容为空串")
  void shouldSerializeEmptyData_whenEventPayloadNull() {
    when(instanceIdProvider.instanceId()).thenReturn("instance-2");
    ConsoleSseEvent event = new ConsoleSseEvent(
        "t1", "ops", "SUMMARY", "cursor-1", null, BatchDateTimeSupport.utcNow());

    publisher.publish(event);

    ArgumentCaptor<ConsoleRealtimeStreamEnvelope> captor =
        ArgumentCaptor.forClass(ConsoleRealtimeStreamEnvelope.class);
    verify(replayStore).append(captor.capture());
    assertThat(captor.getValue().dataJson()).isEmpty();
  }
}
