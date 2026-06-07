package com.example.batch.console.domain.observability.realtime;

import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.common.utils.JsonUtils;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Service;
import org.springframework.util.ErrorHandler;

/**
 * 控制台 Redis Pub/Sub 消费器。
 *
 * <p>每个 console-api 实例订阅共享频道，把事件转回本机 SSE 总线；广播语义由 Redis Pub/Sub 提供。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConsoleRealtimeRedisPubSubConsumer implements MessageListener {

  static final String CHANNEL_KEY = ConsoleRealtimeRedisPublisher.CHANNEL_KEY;

  private final StringRedisTemplate redisTemplate;
  private final ConsoleRealtimeEventHub realtimeEventHub;
  private final ConsoleOpsSummaryRealtimeStream summaryRealtimeStream;
  private final ConsoleRealtimeInstanceIdProvider instanceIdProvider;
  private final ConsoleRealtimeMetrics realtimeMetrics;
  private final AtomicBoolean running = new AtomicBoolean(false);

  private RedisMessageListenerContainer listenerContainer;

  @PostConstruct
  void start() {
    if (!running.compareAndSet(false, true)) {
      return;
    }
    RedisConnectionFactory connectionFactory = redisTemplate.getConnectionFactory();
    if (connectionFactory == null) {
      throw new IllegalStateException(
          "redis connection factory is required for realtime pub/sub consumer");
    }
    listenerContainer = new RedisMessageListenerContainer();
    listenerContainer.setConnectionFactory(connectionFactory);
    listenerContainer.setErrorHandler(logErrorHandler());
    listenerContainer.addMessageListener(this, new ChannelTopic(CHANNEL_KEY));
    listenerContainer.afterPropertiesSet();
    listenerContainer.start();
    log.info(
        "console realtime redis pubsub consumer started: instanceId={}, channel={}",
        instanceIdProvider.instanceId(),
        CHANNEL_KEY);
  }

  @EventListener(ContextClosedEvent.class)
  void stopOnContextClosed(ContextClosedEvent event) {
    stopContainer("context-closed");
  }

  @PreDestroy
  void shutdown() {
    stopContainer("pre-destroy");
  }

  private void stopContainer(String source) {
    if (!running.compareAndSet(true, false)) {
      return;
    }
    if (listenerContainer != null) {
      log.info(
          "console realtime redis pubsub consumer stopping: instanceId={}, source={}",
          instanceIdProvider.instanceId(),
          source);
      try {
        listenerContainer.stop();
      } catch (Exception exception) {
        if (isShutdownNoise(exception)) {
          log.info(
              "console realtime pubsub consumer stop skipped during shutdown: instanceId={},"
                  + " reason={}",
              instanceIdProvider.instanceId(),
              exception.getMessage());
        } else {
          log.warn(
              "console realtime pubsub consumer stop failed: instanceId={}, reason={}",
              instanceIdProvider.instanceId(),
              exception.getMessage(),
              exception);
        }
      }
      try {
        listenerContainer.destroy();
      } catch (Exception exception) {
        log.debug(
            "console realtime pubsub consumer destroy skipped: instanceId={}," + " reason={}",
            instanceIdProvider.instanceId(),
            exception.getMessage(),
            exception);
      }
    }
  }

  @Override
  public void onMessage(Message message, byte[] pattern) {
    if (message == null || message.getBody() == null || message.getBody().length == 0) {
      return;
    }
    String payload = new String(message.getBody(), StandardCharsets.UTF_8);
    ConsoleRealtimeStreamEnvelope envelope;
    try {
      envelope = JsonUtils.fromJson(payload, ConsoleRealtimeStreamEnvelope.class);
    } catch (IllegalArgumentException exception) {
      realtimeMetrics.recordPubSubDecodeFailure();
      log.warn(
          "console realtime pubsub payload decode failed: channel={}, reason={}",
          CHANNEL_KEY,
          exception.getMessage(),
          exception);
      return;
    }
    if (envelope == null) {
      return;
    }
    if (instanceIdProvider.instanceId().equals(envelope.originInstanceId())) {
      // 本实例已经在写路径上做过本机直发，避免 Pub/Sub 回环导致重复 SSE。
      return;
    }
    publish(envelope);
  }

  private void publish(ConsoleRealtimeStreamEnvelope envelope) {
    try {
      if (envelope.summaryRefresh()
          && (envelope.dataJson() == null || envelope.dataJson().isBlank())) {
        summaryRealtimeStream.publishSnapshot(envelope.tenantId());
        return;
      }
      realtimeEventHub.publish(
          new ConsoleSseEvent(
              envelope.tenantId(),
              envelope.stream(),
              envelope.eventType(),
              envelope.cursor(),
              envelope.dataJson() == null || envelope.dataJson().isBlank()
                  ? null
                  : JsonUtils.fromJson(envelope.dataJson(), Object.class),
              envelope.emittedAt() == null ? BatchDateTimeSupport.utcNow() : envelope.emittedAt()));
    } catch (Exception exception) {
      realtimeMetrics.recordPubSubHandleFailure(envelope.stream(), envelope.eventType());
      log.warn(
          "console realtime pubsub record handling failed: tenantId={}, stream={},"
              + " eventType={}, reason={}",
          envelope.tenantId(),
          envelope.stream(),
          envelope.eventType(),
          exception.getMessage(),
          exception);
    }
  }

  private ErrorHandler logErrorHandler() {
    return throwable -> {
      if (!running.get() && isShutdownNoise(throwable)) {
        log.info(
            "console realtime pubsub listener skipped during shutdown: channel={}, reason={}",
            CHANNEL_KEY,
            throwable.getMessage());
        return;
      }
      log.error(
          "console realtime pubsub listener container failed: channel={}, reason={}",
          CHANNEL_KEY,
          throwable.getMessage(),
          throwable);
    };
  }

  private static boolean isShutdownNoise(Throwable throwable) {
    Throwable current = throwable;
    while (current != null) {
      String message = current.getMessage();
      if (message != null
          && (message.contains("LettuceConnectionFactory is STOPPING")
              || message.contains("has been closed")
              || message.contains("Connection pool shut down"))) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }
}
