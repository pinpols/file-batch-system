package com.example.batch.console.domain.observability.realtime;

import com.example.batch.common.utils.JsonUtils;
import com.example.batch.console.config.ConsoleRealtimeProperties;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ConsoleRealtimeReplayStore {

  private static final String KEY_PREFIX = "batch:console:realtime:buffer:";

  private final StringRedisTemplate redisTemplate;
  private final ConsoleRealtimeProperties realtimeProperties;
  private final ConsoleRealtimeMetrics realtimeMetrics;

  public void append(ConsoleRealtimeStreamEnvelope envelope) {
    if (envelope == null
        || envelope.tenantId() == null
        || envelope.stream() == null
        || envelope.cursor() == null) {
      return;
    }
    String key = bufferKey(envelope.tenantId(), envelope.stream());
    String payload = JsonUtils.toJson(envelope);
    long maxEntries = realtimeProperties.getReplayMaxEntries();
    long ttlSeconds =
        realtimeProperties.getReplayTtl() != null
                && !realtimeProperties.getReplayTtl().isZero()
                && !realtimeProperties.getReplayTtl().isNegative()
            ? realtimeProperties.getReplayTtl().getSeconds()
            : 0L;
    redisTemplate.executePipelined(
        (RedisCallback<Object>)
            connection -> {
              byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
              byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
              connection.listCommands().rPush(keyBytes, payloadBytes);
              if (maxEntries > 0) {
                connection.listCommands().lTrim(keyBytes, -maxEntries, -1);
              }
              if (ttlSeconds > 0) {
                connection.keyCommands().expire(keyBytes, ttlSeconds);
              }
              return null;
            });
  }

  public ReplayBatch replay(String tenantId, String stream, String afterCursor, String eventType) {
    if (tenantId == null
        || tenantId.isBlank()
        || stream == null
        || stream.isBlank()
        || afterCursor == null
        || afterCursor.isBlank()) {
      return new ReplayBatch(List.of(), true);
    }
    // P1(2026-05-23 audit):读端按写端 replayMaxEntries 上限截断,避免配置漂移或被旁路写入造成
    //   LRANGE 0 -1 拉全量大列表导致 SSE 线程 GC 抖动。后续 cursor 匹配仍按截断后的窗口判定。
    long maxEntries = realtimeProperties.getReplayMaxEntries();
    long endIndex = maxEntries > 0 ? maxEntries - 1 : -1L;
    List<String> rawEntries =
        redisTemplate.opsForList().range(bufferKey(tenantId, stream), 0, endIndex);
    if (rawEntries == null || rawEntries.isEmpty()) {
      return new ReplayBatch(List.of(), false);
    }
    List<ConsoleRealtimeStreamEnvelope> envelopes = new ArrayList<>(rawEntries.size());
    for (String raw : rawEntries) {
      try {
        ConsoleRealtimeStreamEnvelope envelope =
            JsonUtils.fromJson(raw, ConsoleRealtimeStreamEnvelope.class);
        if (envelope != null) {
          envelopes.add(envelope);
        }
      } catch (Exception exception) {
        log.warn(
            "console realtime replay buffer decode failed: tenantId={}, stream={}," + " reason={}",
            tenantId,
            stream,
            exception.getMessage(),
            exception);
        realtimeMetrics.recordReplayDecodeFailure(stream);
      }
    }
    if (envelopes.isEmpty()) {
      return new ReplayBatch(List.of(), false);
    }

    int cursorIndex = -1;
    for (int index = 0; index < envelopes.size(); index++) {
      if (afterCursor.equals(envelopes.get(index).cursor())) {
        cursorIndex = index;
        break;
      }
    }
    List<ConsoleRealtimeStreamEnvelope> result = new ArrayList<>();
    int startIndex = cursorIndex >= 0 ? cursorIndex + 1 : 0;
    for (int index = startIndex; index < envelopes.size(); index++) {
      ConsoleRealtimeStreamEnvelope envelope = envelopes.get(index);
      if (eventType != null && !eventType.isBlank() && !eventType.equals(envelope.eventType())) {
        continue;
      }
      result.add(envelope);
    }
    return new ReplayBatch(Collections.unmodifiableList(result), cursorIndex >= 0);
  }

  private String bufferKey(String tenantId, String stream) {
    return KEY_PREFIX + tenantId + ":" + stream;
  }

  public record ReplayBatch(List<ConsoleRealtimeStreamEnvelope> events, boolean cursorFound) {}
}
