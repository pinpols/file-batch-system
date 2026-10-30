package io.github.pinpols.batch.console.infrastructure.realtime;

import io.github.pinpols.batch.common.redis.BatchRedisNamespace;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.domain.observability.realtime.ConsoleRealtimeInstanceIdProvider;
import io.github.pinpols.batch.console.domain.observability.realtime.ConsoleRealtimeStreamEnvelope;
import io.github.pinpols.batch.console.domain.observability.realtime.ConsoleSseEvent;
import io.github.pinpols.batch.console.domain.observability.realtime.RealtimeEventBus;
import io.github.pinpols.batch.console.domain.observability.realtime.RealtimeReplayStore;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 控制台实时事件的 Redis Pub/Sub 发布器。
 *
 * <p>它把事件写入共享频道，由各个 console-api 实例的 Redis subscriber 再转发到本机 SSE 连接。
 */
@Service
@RequiredArgsConstructor
public class ConsoleRealtimeRedisPublisher implements RealtimeEventBus {

  public static final String CHANNEL_KEY = "batch:console:realtime";

  private final StringRedisTemplate redisTemplate;
  private final BatchRedisNamespace redisNamespace;
  private final ConsoleRealtimeInstanceIdProvider instanceIdProvider;
  private final RealtimeReplayStore replayStore;

  @Override
  public void publish(ConsoleSseEvent event) {
    if (event == null) {
      return;
    }
    String sourceInstanceId = instanceIdProvider.instanceId();
    String tenantId = event.tenantId();
    String stream = event.stream();
    String eventType = event.eventType();
    String cursor = event.cursor();
    String serializedData = EmptyChecks.isNull(event.data()) ? "" : JsonUtils.toJson(event.data());
    Instant emittedAt = event.emittedAt();
    ConsoleRealtimeStreamEnvelope envelope = new ConsoleRealtimeStreamEnvelope(
        sourceInstanceId, tenantId, stream, eventType, cursor, false, serializedData, emittedAt);
    replayStore.append(envelope);
    redisTemplate.convertAndSend(redisNamespace.channel(CHANNEL_KEY), JsonUtils.toJson(envelope));
  }
}
