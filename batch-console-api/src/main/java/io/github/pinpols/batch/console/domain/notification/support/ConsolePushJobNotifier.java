package io.github.pinpols.batch.console.domain.notification.support;

import io.github.pinpols.batch.console.config.ConsolePushProperties;
import io.github.pinpols.batch.console.domain.notification.entity.ConsolePushJobNotificationEntity;
import io.github.pinpols.batch.console.domain.notification.mapper.ConsolePushJobNotificationMapper;
import io.github.pinpols.batch.console.domain.notification.support.ConsolePushSender.PushPayload;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * PoC:周期扫描最近 N 分钟终态化的 job_instance,对有 operator_id 的逐条 push 通知到提交人; SCHEDULED 等无 operator
 * 的实例跳过(系统调度任务无明确收件人)。
 *
 * <p>幂等:写入 {@link ConsolePushJobNotificationMapper#insertIgnore} 用 ON CONFLICT DO NOTHING;
 * rowcount=1 才发推,=0 表示已被其他实例 / 上次轮询处理过。
 *
 * <p>调度:自管理 {@link ScheduledExecutorService}(console-api 未启用全局 {@code @EnableScheduling},参 {@code
 * ReplicaLagMonitor})。单 JVM 内 {@code fixedDelay} 顺序串行;多 replica 部署时靠 UNIQUE 回退去重。
 *
 * <p>开关:{@code batch.console.push.job-notify.enabled=false}(默认关),启用前需先验 VAPID key 配齐 + 前端订阅链路连通。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConsolePushJobNotifier {

  private final ConsolePushProperties properties;
  private final ConsolePushJobNotificationMapper notificationMapper;
  private final ConsolePushSender pushSender;

  private final AtomicBoolean stopping = new AtomicBoolean(false);
  private ScheduledExecutorService scheduler;

  @PostConstruct
  void start() {
    if (!properties.isEnabled() || !properties.getJobNotify().isEnabled()) {
      log.info(
          "[push] ConsolePushJobNotifier disabled (push.enabled or job-notify.enabled = false)");
      return;
    }
    scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "push-job-notifier");
              t.setDaemon(true);
              return t;
            });
    long intervalMillis = properties.getJobNotify().getPollIntervalMillis();
    scheduler.scheduleWithFixedDelay(
        this::pollSafely, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    log.info(
        "[push] ConsolePushJobNotifier started, intervalMillis={} lookbackMinutes={} batchSize={}",
        intervalMillis,
        properties.getJobNotify().getLookbackMinutes(),
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
        log.info("[push] job-notify poll skipped during shutdown: {}", e.getMessage());
        return;
      }
      log.error("[push] job-notify poll failed", e);
    }
  }

  void pollOnce() {
    if (stopping.get()) {
      return;
    }
    List<PendingJobNotification> pending =
        notificationMapper.findPending(
            properties.getJobNotify().getLookbackMinutes(),
            properties.getJobNotify().getBatchSize());
    if (pending.isEmpty()) {
      return;
    }
    for (PendingJobNotification p : pending) {
      if (stopping.get()) {
        return;
      }
      ConsolePushJobNotificationEntity record = new ConsolePushJobNotificationEntity();
      record.setTenantId(p.getTenantId());
      record.setJobInstanceId(p.getJobInstanceId());
      int inserted = notificationMapper.insertIgnore(record);
      if (inserted == 0) {
        continue; // 其它 replica 已处理
      }
      PushPayload payload = buildPayload(p);
      pushSender.sendToUser(p.getTenantId(), p.getOperatorId(), payload);
    }
  }

  private static PushPayload buildPayload(PendingJobNotification p) {
    String statusLabel = statusLabel(p.getInstanceStatus());
    String title = String.format("任务 %s %s", p.getJobCode(), statusLabel);
    String body =
        String.format(
            Locale.ROOT, "tenant=%s · instance #%d", p.getTenantId(), p.getJobInstanceId());
    String tag = "job-instance-" + p.getJobInstanceId();
    String url = "/m/jobs/" + p.getJobInstanceId();
    return new PushPayload(title, body, tag, url);
  }

  private static String statusLabel(String status) {
    return switch (status) {
      case "SUCCESS" -> "已成功";
      case "FAILED" -> "已失败";
      case "PARTIAL_FAILED" -> "部分失败";
      case "CANCELLED" -> "已取消";
      case "TERMINATED" -> "已终止";
      default -> status;
    };
  }

  private static boolean isShutdownNoise(Throwable throwable) {
    Throwable current = throwable;
    while (current != null) {
      String message = current.getMessage();
      if (message != null
          && (message.contains("has been closed")
              || message.contains("Connection pool shut down")
              || message.contains("Interrupted"))) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }
}
