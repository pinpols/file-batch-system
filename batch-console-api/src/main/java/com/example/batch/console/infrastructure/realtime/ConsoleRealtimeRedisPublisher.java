package com.example.batch.console.infrastructure.realtime;

import com.example.batch.common.utils.JsonUtils;
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
public class ConsoleRealtimeRedisPublisher {

  static final String CHANNEL_KEY = "batch:console:realtime";

  private final StringRedisTemplate redisTemplate;
  private final ConsoleRealtimeInstanceIdProvider instanceIdProvider;
  private final ConsoleRealtimeReplayStore replayStore;

  public void publish(ConsoleSseEvent event) {
    if (event == null) {
      return;
    }
    ConsoleRealtimeStreamEnvelope envelope =
        new ConsoleRealtimeStreamEnvelope(
            instanceIdProvider.instanceId(),
            event.tenantId(),
            event.stream(),
            event.eventType(),
            event.cursor(),
            false,
            event.data() == null ? "" : JsonUtils.toJson(event.data()),
            event.emittedAt());
    replayStore.append(envelope);
    redisTemplate.convertAndSend(CHANNEL_KEY, JsonUtils.toJson(envelope));
  }
}
