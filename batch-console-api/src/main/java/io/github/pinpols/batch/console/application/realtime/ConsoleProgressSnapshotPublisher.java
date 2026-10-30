package io.github.pinpols.batch.console.application.realtime;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.config.ConsoleExecutionProgressConfiguration;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 已授权进度对象按租户/流共享采样；无订阅即停止，时间戳不触发重复业务推送。 */
@Service
@Slf4j
public class ConsoleProgressSnapshotPublisher {
  private static final Duration INTERVAL = Duration.ofSeconds(2);
  private static final int MAX_WATCHES = 256;
  private final ConsoleRealtimeSubscriptionPort subscriptions;
  private final ConsoleRealtimeEventPort events;
  private final TaskScheduler scheduler;
  private final Map<Key, Watch> watches = new HashMap<>();

  private record Key(String tenant, String stream, String event) {}

  private record State(Object snapshot, Object fingerprint) {}

  private static final class Watch {
    final Supplier<State> sample;
    Object fingerprint;
    ScheduledFuture<?> timer;
    int references;

    Watch(Supplier<State> sample, Object fingerprint) {
      this.sample = sample;
      this.fingerprint = fingerprint;
    }
  }

  public ConsoleProgressSnapshotPublisher(
      ConsoleRealtimeSubscriptionPort subscriptions,
      ConsoleRealtimeEventPort events,
      @Qualifier(ConsoleExecutionProgressConfiguration.SCHEDULER) TaskScheduler scheduler) {
    this.subscriptions = subscriptions;
    this.events = events;
    this.scheduler = scheduler;
  }
  /** 调用方必须先授权并取得initial；loader由服务端构造，不接受客户端表达式。 */
  public <T> SseEmitter subscribe(
      String tenant,
      String stream,
      String event,
      T initial,
      Supplier<T> loader,
      Function<T, ?> fingerprint) {
    Key key = new Key(tenant, stream, event);
    Watch attached;
    synchronized (watches) {
      Watch watch = watches.get(key);
      if (EmptyChecks.isNull(watch)) {
        if (watches.size() >= MAX_WATCHES)
          throw BizException.of(
              ResultCode.SERVICE_UNAVAILABLE, "error.realtime.subscription_limit_exceeded");
        watch = new Watch(() -> state(loader, fingerprint), fingerprint.apply(initial));
        watches.put(key, watch);
        Watch scheduled = watch;
        try {
          watch.timer = scheduler.scheduleWithFixedDelay(() -> sample(key, scheduled), INTERVAL);
        } catch (RuntimeException failure) {
          watches.remove(key, watch);
          throw failure;
        }
      }
      watch.references++;
      attached = watch;
    }
    AtomicBoolean released = new AtomicBoolean();
    Runnable close = () -> {
      if (!released.compareAndSet(false, true)) return;
      synchronized (watches) {
        if (--attached.references <= 0 && watches.remove(key, attached))
          attached.timer.cancel(false);
      }
    };
    try {
      SseEmitter emitter = subscriptions.subscribe(tenant, stream, null, null, null);
      emitter.onCompletion(close);
      emitter.onTimeout(close);
      emitter.onError(error -> close.run());
      events.publishChanged(tenant, stream, event, initial);
      return emitter;
    } catch (RuntimeException failure) {
      close.run();
      throw failure;
    }
  }

  private static <T> State state(Supplier<T> loader, Function<T, ?> fingerprint) {
    T snapshot = loader.get();
    return new State(snapshot, fingerprint.apply(snapshot));
  }

  private void sample(Key key, Watch watch) {
    try {
      State state = watch.sample.get();
      if (Objects.equals(watch.fingerprint, state.fingerprint())) return;
      events.publishChanged(key.tenant(), key.stream(), key.event(), state.snapshot());
      watch.fingerprint = state.fingerprint();
    } catch (RuntimeException failure) {
      try {
        events.publishChanged(
            key.tenant(),
            key.stream(),
            key.event() + "-error",
            Map.of("reason", "SNAPSHOT_UNAVAILABLE"));
      } catch (RuntimeException unavailable) {
        log.debug("progress event unavailable tenant={}, stream={}", key.tenant(), key.stream());
      }
    }
  }

  @PreDestroy
  void stop() {
    synchronized (watches) {
      watches.values().forEach(watch -> watch.timer.cancel(false));
      watches.clear();
    }
  }
}
