package io.github.pinpols.batch.console.domain.workflow.application;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.application.realtime.ConsoleRealtimeEventPort;
import io.github.pinpols.batch.console.application.realtime.ConsoleRealtimeSubscriptionPort;
import io.github.pinpols.batch.console.config.ConsoleExecutionProgressConfiguration;
import io.github.pinpols.batch.console.domain.workflow.application.contract.response.WorkflowExecutionProgressResponse;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 订阅驱动的服务端进度快照：同租户/运行只共享一份采样，变化后推送完整状态。
 *
 * <p>业务异步变化不只来自Console写接口，故不能把Console本地dirty事件当完整进度源。
 * 服务端每2秒读取实际已提交状态；无订阅即停止，前端不靠逐页轮询查询。
 */
@Service
@Slf4j
public class WorkflowExecutionPushService {
  private static final int MAX_WATCHES = 256;
  private static final Duration INTERVAL = Duration.ofSeconds(2);
  private final WorkflowExecutionQueryService queries;
  private final ConsoleRealtimeSubscriptionPort streams;
  private final ConsoleRealtimeEventPort events;
  private final TaskScheduler scheduler;
  private final Map<Key, Watch> watches = new HashMap<>();

  private record Key(String tenantId, Long runId) {}

  private record Unavailable(Long workflowRunId, String reason) {}

  private static final class Watch {
    int subscriptions;
    volatile WorkflowExecutionProgressResponse last;
    ScheduledFuture<?> future;

    Watch(WorkflowExecutionProgressResponse initial) {
      last = initial;
    }
  }

  public WorkflowExecutionPushService(
      WorkflowExecutionQueryService queries,
      ConsoleRealtimeSubscriptionPort streams,
      ConsoleRealtimeEventPort events,
      @Qualifier(ConsoleExecutionProgressConfiguration.SCHEDULER) TaskScheduler scheduler) {
    this.queries = queries;
    this.streams = streams;
    this.events = events;
    this.scheduler = scheduler;
  }

  public SseEmitter subscribe(String requestedTenantId, Long runId) {
    WorkflowExecutionProgressResponse initial = queries.progress(requestedTenantId, runId);
    Key key = new Key(initial.tenantId(), initial.workflowRunId());
    Watch watch;
    synchronized (watches) {
      watch = watches.get(key);
      if (EmptyChecks.isNull(watch)) {
        if (watches.size() >= MAX_WATCHES) {
          throw BizException.of(
              ResultCode.SERVICE_UNAVAILABLE, "error.realtime.subscription_limit_exceeded");
        }
        watch = new Watch(initial);
        watches.put(key, watch);
        Watch scheduledWatch = watch;
        try {
          watch.future =
              scheduler.scheduleWithFixedDelay(() -> refresh(key, scheduledWatch), INTERVAL);
        } catch (RuntimeException rejected) {
          watches.remove(key, watch);
          throw rejected;
        }
      }
      watch.subscriptions++;
    }
    Watch attached = watch;
    AtomicBoolean released = new AtomicBoolean();
    Runnable close = () -> {
      if (released.compareAndSet(false, true)) release(key, attached);
    };
    SseEmitter emitter = null;
    try {
      emitter = streams.subscribe(key.tenantId(), stream(key.runId()), null, null, null);
      emitter.onCompletion(close);
      emitter.onTimeout(close);
      emitter.onError(error -> close.run());
      // 每次重连都推完整快照，不依赖浏览器重用已经消费的一次性ticket。
      events.publishChanged(
          key.tenantId(), stream(key.runId()), "workflow-execution-progress", initial);
      return emitter;
    } catch (RuntimeException failure) {
      if (EmptyChecks.isNotNull(emitter)) {
        try {
          streams.close(emitter);
        } catch (RuntimeException cleanupFailure) {
          failure.addSuppressed(cleanupFailure);
        }
      }
      close.run();
      throw failure;
    }
  }

  void refresh(Key key, Watch watch) {
    try {
      WorkflowExecutionProgressResponse current =
          queries.progressForAuthorizedTenant(key.tenantId(), key.runId());
      WorkflowExecutionProgressResponse previous = watch.last;
      boolean changed = !Objects.equals(previous.nodes(), current.nodes())
          || !Objects.equals(previous.edges(), current.edges())
          || !Objects.equals(previous.runStatus(), current.runStatus())
          || !Objects.equals(previous.startedAt(), current.startedAt())
          || !Objects.equals(previous.finishedAt(), current.finishedAt())
          || previous.snapshotAvailable() != current.snapshotAvailable();
      if (!changed) return;
      events.publishChanged(
          key.tenantId(), stream(key.runId()), "workflow-execution-progress", current);
      watch.last = current;
    } catch (RuntimeException failure) {
      // 错误事件不包含SQL、快照原文或凭据；页面保留最后数据并显示非实时错误。
      try {
        events.publishChanged(
            key.tenantId(),
            stream(key.runId()),
            "workflow-execution-error",
            new Unavailable(key.runId(), "SNAPSHOT_UNAVAILABLE"));
      } catch (RuntimeException unavailable) {
        // 发布通道不可用时仍保留下一次采样机会，客户端使用断线补查恢复。
        log.debug(
            "workflow execution error event unavailable tenant={}, run={}",
            key.tenantId(),
            key.runId());
      }
      log.debug(
          "workflow execution refresh unavailable tenant={}, run={}", key.tenantId(), key.runId());
    }
  }

  private void release(Key key, Watch watch) {
    synchronized (watches) {
      if (--watch.subscriptions <= 0 && watches.remove(key, watch)) watch.future.cancel(false);
    }
  }

  @PreDestroy
  void stop() {
    synchronized (watches) {
      watches.values().forEach(watch -> watch.future.cancel(false));
      watches.clear();
    }
  }

  private static String stream(Long id) {
    return "workflow-execution-" + id;
  }
}
