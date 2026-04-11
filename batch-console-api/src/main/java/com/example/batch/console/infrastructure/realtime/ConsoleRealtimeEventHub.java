package com.example.batch.console.infrastructure.realtime;

import com.example.batch.common.utils.JsonUtils;
import com.example.batch.console.web.response.ConsoleSseEventResponse;

import jakarta.annotation.PreDestroy;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 控制台实时事件总线。
 *
 * <p>它只负责把共享实时事件分发给本机 SSE 订阅者，不做任何数据库轮询。
 *
 * <p>Redis Streams 负责跨实例事件共享；这个类只保留本机连接管理和 SSE fanout。
 */
@Service
@Slf4j
public class ConsoleRealtimeEventHub {

    private static final long DEFAULT_TIMEOUT_MILLIS = 0L;
    private static final long DEFAULT_HEARTBEAT_MILLIS = 25_000L;
    private static final String DEFAULT_STREAM = "pipeline-definitions";

    private final ConsoleRealtimeReplayStore replayStore;
    private final ConsoleRealtimeMetrics realtimeMetrics;
    private final List<Subscription> subscriptions = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(1, new RealtimeThreadFactory());

    public ConsoleRealtimeEventHub(
            ConsoleRealtimeReplayStore replayStore, ConsoleRealtimeMetrics realtimeMetrics) {
        this.replayStore = replayStore;
        this.realtimeMetrics = realtimeMetrics;
    }

    public SseEmitter subscribe(
            String tenantId, String stream, String eventType, String cursor, Long heartbeatMillis) {
        String resolvedStream = normalizeStream(stream);
        SseEmitter emitter = new SseEmitter(DEFAULT_TIMEOUT_MILLIS);
        Subscription subscription =
                new Subscription(
                        tenantId, resolvedStream, normalizeFilter(eventType), cursor, emitter);
        subscriptions.add(subscription);
        realtimeMetrics.incrementSubscriptions();
        registerLifecycle(subscription);

        long interval = resolveHeartbeatInterval(heartbeatMillis);
        subscription.heartbeatFuture =
                scheduler.scheduleAtFixedRate(
                        () -> sendHeartbeat(subscription),
                        interval,
                        interval,
                        TimeUnit.MILLISECONDS);

        // 订阅建立后立即回一个 ready 事件，前端可据此确认流已连通并拿到当前 cursor/stream。
        sendLifecycleEvent(
                subscription,
                "ready",
                new ConsoleSseEventResponse(
                        resolvedStream,
                        "ready",
                        cursor,
                        eventSnapshot(subscription, "connected"),
                        Instant.now()));
        replay(subscription);
        return emitter;
    }

    public void publishAfterCommit(ConsoleSseEvent event) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            // 只在事务提交后向前端广播，避免把最终会回滚的数据状态提前暴露出去。
                            publish(event);
                        }
                    });
            return;
        }
        publish(event);
    }

    public void publish(ConsoleSseEvent event) {
        if (event == null) {
            return;
        }
        for (Subscription subscription : new ArrayList<>(subscriptions)) {
            if (!matches(subscription, event)) {
                continue;
            }
            sendLifecycleEvent(
                    subscription,
                    event.eventType(),
                    new ConsoleSseEventResponse(
                            event.stream(),
                            event.eventType(),
                            event.cursor(),
                            event.data(),
                            event.emittedAt() != null ? event.emittedAt() : Instant.now()));
        }
    }

    @PreDestroy
    void shutdown() {
        for (Subscription subscription : new ArrayList<>(subscriptions)) {
            close(subscription);
        }
        scheduler.shutdownNow();
    }

    private boolean matches(Subscription subscription, ConsoleSseEvent event) {
        return Objects.equals(subscription.tenantId, event.tenantId())
                && (subscription.stream == null
                        || subscription.stream.equals("*")
                        || subscription.stream.equals(event.stream()))
                && (subscription.eventType == null
                        || subscription.eventType.isBlank()
                        || subscription.eventType.equals(event.eventType()));
    }

    private void sendHeartbeat(Subscription subscription) {
        if (!subscription.active.get()) {
            close(subscription);
            return;
        }
        // 心跳只用于保活和连接探测，不承载业务数据。
        sendLifecycleEvent(
                subscription,
                "heartbeat",
                new ConsoleSseEventResponse(
                        subscription.stream,
                        "heartbeat",
                        subscription.cursor,
                        eventSnapshot(subscription, "alive"),
                        Instant.now()));
    }

    private void sendLifecycleEvent(
            Subscription subscription, String eventName, ConsoleSseEventResponse payload) {
        if (!subscription.active.get()) {
            return;
        }
        try {
            synchronized (subscription.emitter) {
                subscription.emitter.send(SseEmitter.event().name(eventName).data(payload));
            }
        } catch (IOException | IllegalStateException exception) {
            if (log.isDebugEnabled()) {
                log.debug(
                        "Console realtime SSE send failed: tenantId={}, stream={}, eventType={}",
                        subscription.tenantId,
                        subscription.stream,
                        eventName,
                        exception);
            }
            close(subscription);
        }
    }

    private void replay(Subscription subscription) {
        if (subscription.cursor == null || subscription.cursor.isBlank()) {
            return;
        }
        ConsoleRealtimeReplayStore.ReplayBatch replayBatch =
                replayStore.replay(
                        subscription.tenantId,
                        subscription.stream,
                        subscription.cursor,
                        subscription.eventType);
        if (!replayBatch.cursorFound()) {
            realtimeMetrics.recordReplayCursorMiss(subscription.stream);
            sendLifecycleEvent(
                    subscription,
                    "reset-required",
                    new ConsoleSseEventResponse(
                            subscription.stream,
                            "reset-required",
                            subscription.cursor,
                            eventSnapshot(subscription, "cursor-not-found"),
                            Instant.now()));
            return;
        }
        for (ConsoleRealtimeStreamEnvelope envelope : replayBatch.events()) {
            ConsoleSseEvent event = toEvent(envelope);
            if (event != null) {
                sendLifecycleEvent(
                        subscription,
                        event.eventType(),
                        new ConsoleSseEventResponse(
                                event.stream(),
                                event.eventType(),
                                event.cursor(),
                                event.data(),
                                event.emittedAt() != null ? event.emittedAt() : Instant.now()));
            }
        }
        realtimeMetrics.recordReplayDelivered(subscription.stream, replayBatch.events().size());
    }

    private void registerLifecycle(Subscription subscription) {
        subscription.emitter.onCompletion(() -> close(subscription));
        subscription.emitter.onTimeout(() -> close(subscription));
        subscription.emitter.onError(throwable -> close(subscription));
    }

    private void close(Subscription subscription) {
        if (subscription == null || !subscription.active.compareAndSet(true, false)) {
            return;
        }
        ScheduledFuture<?> heartbeatFuture = subscription.heartbeatFuture;
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(true);
        }
        subscriptions.remove(subscription);
        realtimeMetrics.decrementSubscriptions();
        try {
            subscription.emitter.complete();
        } catch (IllegalStateException ignored) {
            // emitter 已完成
        }
    }

    private long resolveHeartbeatInterval(Long heartbeatMillis) {
        if (heartbeatMillis == null || heartbeatMillis <= 0) {
            return DEFAULT_HEARTBEAT_MILLIS;
        }
        return Math.max(10_000L, heartbeatMillis);
    }

    private String normalizeStream(String stream) {
        return (stream == null || stream.isBlank()) ? DEFAULT_STREAM : stream.trim();
    }

    private String normalizeFilter(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Object eventSnapshot(Subscription subscription, String status) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("tenantId", subscription.tenantId);
        snapshot.put("stream", subscription.stream);
        snapshot.put("eventType", subscription.eventType);
        snapshot.put("cursor", subscription.cursor);
        snapshot.put("status", status);
        return snapshot;
    }

    private ConsoleSseEvent toEvent(ConsoleRealtimeStreamEnvelope envelope) {
        if (envelope == null) {
            return null;
        }
        Object data = null;
        if (envelope.dataJson() != null && !envelope.dataJson().isBlank()) {
            data = JsonUtils.fromJson(envelope.dataJson(), Object.class);
        }
        return new ConsoleSseEvent(
                envelope.tenantId(),
                envelope.stream(),
                envelope.eventType(),
                envelope.cursor(),
                data,
                envelope.emittedAt() != null ? envelope.emittedAt() : Instant.now());
    }

    private static final class Subscription {
        private final String tenantId;
        private final String stream;
        private final String eventType;
        private final String cursor;
        private final SseEmitter emitter;
        private final AtomicBoolean active = new AtomicBoolean(true);
        private volatile ScheduledFuture<?> heartbeatFuture;

        private Subscription(
                String tenantId,
                String stream,
                String eventType,
                String cursor,
                SseEmitter emitter) {
            // cursor 由服务端生成并回显给客户端，用于断线后的短窗口回放。
            this.tenantId = tenantId;
            this.stream = stream;
            this.eventType = eventType;
            this.cursor = cursor;
            this.emitter = emitter;
        }
    }

    private static final class RealtimeThreadFactory implements ThreadFactory {
        private int index = 1;

        @Override
        public synchronized Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "console-realtime-" + index++);
            thread.setDaemon(true);
            return thread;
        }
    }
}
