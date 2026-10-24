package io.github.pinpols.batch.console.domain.notification.support;

import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.console.config.ConsolePushProperties;
import io.github.pinpols.batch.console.domain.notification.service.JobPushDeliveryService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** 为 Job 终态接收流程中持久化的设备通知意图安排有界投递。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConsolePushJobNotifier {

  private final ConsolePushProperties properties;
  private final JobPushDeliveryService deliveries;

  private final AtomicBoolean stopping = new AtomicBoolean(false);
  private ScheduledExecutorService scheduler;

  @PostConstruct
  void start() {
    if (!properties.isEnabled() || !properties.getJobNotify().isEnabled()) {
      log.info(
          "[push] ConsolePushJobNotifier disabled (push.enabled or job-notify.enabled = false)");
      return;
    }
    scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "push-job-notifier");
      t.setDaemon(true);
      return t;
    });
    long intervalMillis = properties.getJobNotify().getPollIntervalMillis();
    scheduler.scheduleWithFixedDelay(
        this::pollSafely, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    log.info(
        "[push] ConsolePushJobNotifier started, intervalMillis={} batchSize={}",
        intervalMillis,
        properties.getJobNotify().getBatchSize());
  }

  @EventListener(ContextClosedEvent.class)
  void stopOnContextClosed(ContextClosedEvent event) {
    stopScheduler("context-closed");
  }

  @PreDestroy
  void stop() {
    stopScheduler("pre-destroy");
  }

  private void stopScheduler(String source) {
    if (!stopping.compareAndSet(false, true)) {
      return;
    }
    if (scheduler != null) {
      log.info("[push] ConsolePushJobNotifier stopping: source={}", source);
      scheduler.shutdownNow();
    }
  }

  void pollSafely() {
    if (stopping.get()) {
      return;
    }
    try {
      pollOnce();
    } catch (RuntimeException e) {
      if (stopping.get() && isShutdownNoise(e)) {
        String causeSummary = SwallowedExceptionLogger.summary(e);
        log.info("[push] job-notify poll skipped during shutdown: {}", causeSummary);
        return;
      }
      log.error("[push] job-notify poll failed", e);
    }
  }

  void pollOnce() {
    if (!stopping.get()) deliveries.poll(stopping::get);
  }

  private static boolean isShutdownNoise(Throwable throwable) {
    Throwable current = throwable;
    while (current != null) {
      String message = current.getMessage();
      boolean isConnectionShutdownMessage = message != null
          && (message.contains("has been closed")
              || message.contains("Connection pool shut down")
              || message.contains("Interrupted"));
      if (isConnectionShutdownMessage) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }
}
