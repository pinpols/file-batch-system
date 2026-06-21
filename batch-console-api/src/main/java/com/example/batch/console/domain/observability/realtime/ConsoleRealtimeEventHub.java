package com.example.batch.console.domain.observability.realtime;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.logging.SwallowedExceptionLogger;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.console.config.ConsoleAsyncConfiguration;
import com.example.batch.console.config.ConsoleRealtimeProperties;
import com.example.batch.console.domain.ops.web.response.ConsoleSseEventResponse;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 控制台实时事件总线：把事件分发给本机的 SSE 订阅者。
 *
 * <p>分工边界：本类<b>只</b>做本机连接管理与 fanout，不轮询 DB；跨实例的事件共享由 Redis Streams （见 {@code
 * ConsoleRealtimeReplayStore} 与发布端）负责。
 *
 * <p>关键机制：
 *
 * <ul>
 *   <li><b>事务提交后广播</b>（{@link #publishAfterCommit}）：检测到当前有事务时注册 {@code afterCommit}
 *       synchronization，只在事务成功提交后才 fanout——避免把最终会回滚的中间态推到前端造成 UI 幻读。
 *   <li><b>3 维过滤</b>：订阅按 {@code (tenantId, stream, eventType)} 严格匹配事件，{@code stream="*"} 或
 *       eventType 为 null/blank 视为通配。
 *   <li><b>心跳保活</b>：默认 25s 发 heartbeat 事件，防止代理/负载均衡在空闲连接上断线； 下限 clamp 到 10s 防止客户端指定过短间隔耗尽线程资源。
 *   <li><b>断线回放</b>（{@link #replay}）：新订阅带 {@code cursor} 时从 replay store 拉该 cursor 之后的历史
 *       事件补推；cursor 已被回收或不存在时发 {@code reset-required} 事件要求前端重连并拿新 cursor。
 *   <li><b>生命周期</b>：emitter 的 onCompletion / onTimeout / onError 统一走 {@link #close}， 用 {@code
 *       AtomicBoolean} 保证幂等清理（取消心跳 future + 从订阅列表移除 + 指标递减）。
 * </ul>
 */
@Service
@Slf4j
public class ConsoleRealtimeEventHub {

  private static final long DEFAULT_HEARTBEAT_MILLIS = 25_000L;
  private static final String DEFAULT_STREAM = "pipeline-definitions";

  private final ConsoleRealtimeReplayStore replayStore;
  private final ConsoleRealtimeMetrics realtimeMetrics;
  private final ConsoleRealtimeProperties realtimeProperties;
  private final List<Subscription> subscriptions = new CopyOnWriteArrayList<>();
  private final TaskScheduler scheduler;

  public ConsoleRealtimeEventHub(
      ConsoleRealtimeReplayStore replayStore,
      ConsoleRealtimeMetrics realtimeMetrics,
      ConsoleRealtimeProperties realtimeProperties,
      @Qualifier(ConsoleAsyncConfiguration.REALTIME_SCHEDULER) TaskScheduler scheduler) {
    this.replayStore = replayStore;
    this.realtimeMetrics = realtimeMetrics;
    this.realtimeProperties = realtimeProperties;
    this.scheduler = scheduler;
  }

  public SseEmitter subscribe(
      String tenantId, String stream, String eventType, String cursor, Long heartbeatMillis) {
    // P1-5：超出单实例 SSE 上限时拒绝,防止僵尸连接累积击穿进程。前端拿到 503 后退避重试。
    int max = realtimeProperties.getMaxSubscriptions();
    if (max > 0 && subscriptions.size() >= max) {
      throw BizException.of(
          ResultCode.SERVICE_UNAVAILABLE,
          "error.realtime.subscription_limit_exceeded",
          String.valueOf(max));
    }
    String resolvedStream = normalizeStream(stream);
    long emitterTimeoutMillis =
        realtimeProperties.getEmitterTimeout() != null
            ? realtimeProperties.getEmitterTimeout().toMillis()
            : 0L;
    SseEmitter emitter = new SseEmitter(emitterTimeoutMillis);
    Subscription subscription =
        new Subscription(tenantId, resolvedStream, normalizeFilter(eventType), cursor, emitter);
    subscriptions.add(subscription);
    realtimeMetrics.incrementSubscriptions();
    registerLifecycle(subscription);

    long interval = resolveHeartbeatInterval(heartbeatMillis);
    subscription.heartbeatFuture =
        scheduler.scheduleAtFixedRate(
            () -> sendHeartbeat(subscription),
            Instant.now().plusMillis(interval),
            Duration.ofMillis(interval));

    // 订阅建立后立即回一个 ready 事件，前端可据此确认流已连通并拿到当前 cursor/stream。
    sendLifecycleEvent(
        subscription,
        "ready",
        new ConsoleSseEventResponse(
            resolvedStream,
            "ready",
            cursor,
            eventSnapshot(subscription, "connected"),
            BatchDateTimeSupport.utcNow()));
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
              event.emittedAt() != null ? event.emittedAt() : BatchDateTimeSupport.utcNow()));
    }
  }

  @PreDestroy
  void shutdown() {
    // scheduler 由 Spring 容器管理生命周期 (consoleRealtimeScheduler bean), 这里只清理订阅状态。
    for (Subscription subscription : new ArrayList<>(subscriptions)) {
      close(subscription);
    }
  }

  private boolean matches(Subscription subscription, ConsoleSseEvent event) {
    return Objects.equals(subscription.tenantId, event.tenantId())
        && (subscription.stream == null
            || "*".equals(subscription.stream)
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
            BatchDateTimeSupport.utcNow()));
  }

  private void sendLifecycleEvent(
      Subscription subscription, String eventName, ConsoleSseEventResponse payload) {
    // 便宜检查：对已关闭订阅跳过，省掉进入 Spring ResponseBodyEmitter 内部 writeLock
    // + 捕构 IllegalStateException 的开销。失败也只是少写一次 log。
    if (!subscription.active.get()) {
      return;
    }
    // 这里不再 synchronized(subscription.emitter)：
    //   1) Spring ResponseBodyEmitter 内部已有 ReentrantLock writeLock 串行化 send
    //   2) 我们的 monitor 锁不影响 close() 路径的 emitter.complete()——两把锁互相看不见
    //   3) 正常 race（active 检查过了但随即被 close）Spring 会抛 IllegalStateException，
    //      下面 catch 已调 close(subscription) 善后
    try {
      subscription.emitter.send(SseEmitter.event().name(eventName).data(payload));
    } catch (IOException | IllegalStateException exception) {
      if (log.isDebugEnabled()) {
        log.debug(
            "Console realtime SSE send failed: tenantId={}, stream={}, eventType={}",
            logValue(subscription.tenantId),
            logValue(subscription.stream),
            logValue(eventName),
            exception);
      }
      close(subscription);
    }
  }

  private static String logValue(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
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
              BatchDateTimeSupport.utcNow()));
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
                event.emittedAt() != null ? event.emittedAt() : BatchDateTimeSupport.utcNow()));
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
    // C-2.12: CAS 过关后把清理逻辑统一放进 try/finally；
    // 之前 subscriptions.remove 在 finally 外，中间任一步骤抛异常会导致
    // subscription 残留在列表里（active=false 但未 remove），publish() 每次遍历都做无效比对。
    try {
      ScheduledFuture<?> heartbeatFuture = subscription.heartbeatFuture;
      if (heartbeatFuture != null) {
        heartbeatFuture.cancel(true);
      }
    } finally {
      subscriptions.remove(subscription);
      realtimeMetrics.decrementSubscriptions();
      try {
        subscription.emitter.complete();
      } catch (RuntimeException ignored) {
        // emitter 已完成 / 客户端断开后 response 不可写（AsyncRequestNotUsableException 等）；
        // close() 仅是清理路径，任何 emitter 状态异常都不应外抛去触发 @ControllerAdvice。
        SwallowedExceptionLogger.info(
            ConsoleRealtimeEventHub.class, "catch:emitterCompleteFailed", ignored);
      }
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
        envelope.emittedAt() != null ? envelope.emittedAt() : BatchDateTimeSupport.utcNow());
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
        String tenantId, String stream, String eventType, String cursor, SseEmitter emitter) {
      // cursor 由服务端生成并回显给客户端，用于断线后的短窗口回放。
      this.tenantId = tenantId;
      this.stream = stream;
      this.eventType = eventType;
      this.cursor = cursor;
      this.emitter = emitter;
    }
  }
}
