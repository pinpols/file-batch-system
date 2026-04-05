package com.example.batch.console.infrastructure.realtime;

import com.example.batch.common.utils.JsonUtils;
import com.example.batch.console.application.ConsoleOpsApplicationService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.ErrorHandler;

/**
 * 控制台 Redis Pub/Sub 消费器。
 *
 * <p>每个 console-api 实例订阅共享频道，把事件转回本机 SSE 总线；广播语义由 Redis Pub/Sub 提供。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConsoleRealtimeRedisPubSubConsumer implements MessageListener {

    static final String CHANNEL_KEY = ConsoleRealtimeRedisPublisher.CHANNEL_KEY;

    private final StringRedisTemplate redisTemplate;
    private final ConsoleRealtimeEventHub realtimeEventHub;
    private final ConsoleOpsApplicationService opsApplicationService;
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
            throw new IllegalStateException("redis connection factory is required for realtime pub/sub consumer");
        }
        listenerContainer = new RedisMessageListenerContainer();
        listenerContainer.setConnectionFactory(connectionFactory);
        listenerContainer.setErrorHandler(logErrorHandler());
        listenerContainer.addMessageListener(this, new ChannelTopic(CHANNEL_KEY));
        listenerContainer.afterPropertiesSet();
        listenerContainer.start();
        log.info("console realtime redis pubsub consumer started: instanceId={}, channel={}",
                instanceIdProvider.instanceId(), CHANNEL_KEY);
    }

    @PreDestroy
    void shutdown() {
        running.set(false);
        if (listenerContainer != null) {
            listenerContainer.stop();
            try {
                listenerContainer.destroy();
            } catch (Exception exception) {
                log.debug("console realtime pubsub consumer destroy skipped: instanceId={}, reason={}",
                        instanceIdProvider.instanceId(), exception.getMessage(), exception);
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
            log.warn("console realtime pubsub payload decode failed: channel={}, reason={}",
                    CHANNEL_KEY, exception.getMessage(), exception);
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
            if (envelope.summaryRefresh() && (envelope.dataJson() == null || envelope.dataJson().isBlank())) {
                realtimeEventHub.publish(new ConsoleSseEvent(
                        envelope.tenantId(),
                        envelope.stream(),
                        envelope.eventType(),
                        envelope.cursor(),
                        opsApplicationService.summary(envelope.tenantId()),
                        envelope.emittedAt() == null ? Instant.now() : envelope.emittedAt()
                ));
                return;
            }
            realtimeEventHub.publish(new ConsoleSseEvent(
                    envelope.tenantId(),
                    envelope.stream(),
                    envelope.eventType(),
                    envelope.cursor(),
                    envelope.dataJson() == null || envelope.dataJson().isBlank()
                            ? null
                            : JsonUtils.fromJson(envelope.dataJson(), Object.class),
                    envelope.emittedAt() == null ? Instant.now() : envelope.emittedAt()
            ));
        } catch (Exception exception) {
            realtimeMetrics.recordPubSubHandleFailure(envelope.stream(), envelope.eventType());
            log.warn("console realtime pubsub record handling failed: tenantId={}, stream={}, eventType={}, reason={}",
                    envelope.tenantId(), envelope.stream(), envelope.eventType(), exception.getMessage(), exception);
        }
    }

    private ErrorHandler logErrorHandler() {
        return throwable -> log.error("console realtime pubsub listener container failed: channel={}, reason={}",
                CHANNEL_KEY, throwable.getMessage(), throwable);
    }
}
