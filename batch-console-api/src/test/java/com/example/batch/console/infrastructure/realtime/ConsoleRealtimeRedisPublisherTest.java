package com.example.batch.console.infrastructure.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.console.domain.observability.realtime.ConsoleRealtimeInstanceIdProvider;
import com.example.batch.console.domain.observability.realtime.ConsoleRealtimeRedisPublisher;
import com.example.batch.console.domain.observability.realtime.ConsoleRealtimeReplayStore;
import com.example.batch.console.domain.observability.realtime.ConsoleRealtimeStreamEnvelope;
import com.example.batch.console.domain.observability.realtime.ConsoleSseEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

@ExtendWith(MockitoExtension.class)
class ConsoleRealtimeRedisPublisherTest {

  @Mock private StringRedisTemplate redisTemplate;
  @Mock private ConsoleRealtimeInstanceIdProvider instanceIdProvider;
  @Mock private ConsoleRealtimeReplayStore replayStore;

  private ConsoleRealtimeRedisPublisher publisher;

  @BeforeEach
  void setUp() {
    publisher = new ConsoleRealtimeRedisPublisher(redisTemplate, instanceIdProvider, replayStore);
  }

  @Test
  void publishNullEventIsNoOp() {
    publisher.publish(null);

    verify(replayStore, never()).append(any());
    verify(redisTemplate, never()).convertAndSend(anyString(), anyString());
  }

  @Test
  void publishAppendsToReplayStoreAndSendsToChannel() {
    when(instanceIdProvider.instanceId()).thenReturn("instance-1");
    ConsoleSseEvent event =
        new ConsoleSseEvent(
            "t1",
            "job-instance",
            "JOB_STATUS",
            "cursor-abc",
            "payload",
            BatchDateTimeSupport.utcNow());

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
  void publishNullDataSerializesAsEmptyString() {
    when(instanceIdProvider.instanceId()).thenReturn("instance-2");
    ConsoleSseEvent event =
        new ConsoleSseEvent(
            "t1", "ops", "SUMMARY", "cursor-1", null, BatchDateTimeSupport.utcNow());

    publisher.publish(event);

    ArgumentCaptor<ConsoleRealtimeStreamEnvelope> captor =
        ArgumentCaptor.forClass(ConsoleRealtimeStreamEnvelope.class);
    verify(replayStore).append(captor.capture());
    assertThat(captor.getValue().dataJson()).isEmpty();
  }
}
