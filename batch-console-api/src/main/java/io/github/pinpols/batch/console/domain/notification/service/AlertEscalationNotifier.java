package io.github.pinpols.batch.console.domain.notification.service;

import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.persistence.entity.AlertEventEntity;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.config.AlertEscalationNotifyProperties;
import io.github.pinpols.batch.console.domain.notification.mapper.AlertEventMapper;
import io.github.pinpols.batch.console.domain.observability.realtime.ConsoleRealtimeDomainEventPublisher;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * 告警升级「最后一公里通知」notifier —— 闭合升级→通知回路。
 *
 * <p>背景:orchestrator 的 {@code AlertEscalationScheduler} 把超过 ack-SLA 仍 OPEN 的告警逐级抬升 {@code
 * escalation_tier}(V176),但只打日志/指标,无人被主动通知(headless)。本 notifier 把「刚升级、还没通知过」的 告警(escalation_tier
 * &gt; escalation_notified_tier)经现有 webhook 投递链路推到订阅方,然后 CAS 推进 {@code escalation_notified_tier}
 * 水位线,保证每次 tier 抬升只通知一次。
 *
 * <p>复用既有能力,不新增 Kafka / policy 表:
 *
 * <ul>
 *   <li>投递:{@link ConsoleRealtimeDomainEventPublisher#publishChanged} 发 {@code
 *       alerts/ALERT_ESCALATED} 领域事件 → {@code ConsoleWebhookDomainEventListener} → 现有 webhook
 *       分发器(与告警 ack 走同一条路)。
 *   <li>调度:console-api 未启用全局 {@code @EnableScheduling},沿用自管理 {@link ScheduledExecutorService} +
 *       programmatic ShedLock(同 {@link WebhookDeliveryRelay}),多实例间互斥。
 * </ul>
 *
 * <p>边界:v1 只覆盖平台已接通的 WEBHOOK(+ Web Push)渠道;EMAIL/钉钉/企微 sender 尚未实现,留作独立后续。
 *
 * <p>条件启用:默认开({@code batch.alert.escalation.notify.enabled=true});关掉退化回 V176 纯日志/指标放大。
 */
@Component
@Slf4j
@ConditionalOnProperty(
    prefix = "batch.alert.escalation.notify",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class AlertEscalationNotifier {

  /**
   * 升级通知发往的 SSE / webhook 流与事件类型。流沿用告警的 {@code alerts} 流(与 ack 的 {@code alert-updated} 同流); 事件类型用
   * {@code ALERT_ESCALATED}(UPPER_UNDERSCORE),对齐 {@code ConsoleEventCatalogController}
   * 暴露给前端订阅的事件目录命名风格,使前端 subscription_rule 能配到并匹配升级事件(此前用连字符 {@code alert-escalated},归一化成 {@code
   * ALERT-ESCALATED},与目录下划线风格不一致,订阅永远匹配不到)。
   */
  private static final String ALERT_STREAM = "alerts";

  private static final String ESCALATED_EVENT_TYPE = "ALERT_ESCALATED";

  private static final Duration LOCK_AT_MOST = Duration.ofMinutes(2);
  private static final Duration LOCK_AT_LEAST = Duration.ofSeconds(2);

  private final AlertEventMapper alertEventMapper;
  private final ConsoleRealtimeDomainEventPublisher domainEventPublisher;
  private final LockingTaskExecutor lockingTaskExecutor;
  private final AlertEscalationNotifyProperties properties;
  private final Counter notifyCounter;

  private final AtomicBoolean running = new AtomicBoolean(false);
  private final AtomicBoolean stopping = new AtomicBoolean(false);
  private ScheduledExecutorService executor;
  private volatile ScheduledFuture<?> scheduledTask;

  public AlertEscalationNotifier(
      AlertEventMapper alertEventMapper,
      ConsoleRealtimeDomainEventPublisher domainEventPublisher,
      LockingTaskExecutor lockingTaskExecutor,
      AlertEscalationNotifyProperties properties,
      MeterRegistry meterRegistry) {
    this.alertEventMapper = alertEventMapper;
    this.domainEventPublisher = domainEventPublisher;
    this.lockingTaskExecutor = lockingTaskExecutor;
    this.properties = properties;
    this.notifyCounter =
        Counter.builder("batch.alert.escalation.notifications")
            .description("告警升级被推到平台内通知链路(webhook)的累计次数")
            .tags(Tags.empty())
            .register(meterRegistry);
  }

  @PostConstruct
  public void start() {
    executor =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "alert-escalation-notifier");
              t.setDaemon(true);
              return t;
            });
    scheduledTask =
        executor.scheduleWithFixedDelay(
            this::poll,
            properties.getPollIntervalMillis(),
            properties.getPollIntervalMillis(),
            TimeUnit.MILLISECONDS);
    log.info(
        "AlertEscalationNotifier 已启动:poll={}ms batch={}",
        properties.getPollIntervalMillis(),
        properties.getBatchSize());
  }

  @EventListener(ContextClosedEvent.class)
  public void stopOnContextClosed(ContextClosedEvent event) {
    stopExecutor("context-closed");
  }

  @PreDestroy
  public void stop() {
    stopExecutor("pre-destroy");
  }

  private void stopExecutor(String source) {
    if (!stopping.compareAndSet(false, true)) {
      return;
    }
    ScheduledFuture<?> task = scheduledTask;
    if (task != null) {
      task.cancel(true);
    }
    if (executor == null) {
      return;
    }
    log.info("AlertEscalationNotifier stopping: source={}", source);
    executor.shutdown();
    try {
      if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
        log.warn("AlertEscalationNotifier 未在 15s 内完成关闭,强制中断");
        executor.shutdownNow();
      }
    } catch (InterruptedException e) {
      SwallowedExceptionLogger.info(AlertEscalationNotifier.class, "catch:InterruptedException", e);
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  /** 单元测试可直接调用本方法跑一轮(不走自调度循环)。 */
  public void poll() {
    if (stopping.get()) {
      return;
    }
    if (!running.compareAndSet(false, true)) {
      return;
    }
    try {
      if (stopping.get()) {
        return;
      }
      lockingTaskExecutor.executeWithLock(
          (LockingTaskExecutor.Task) this::pollLocked, lockConfig());
    } catch (DataAccessException dae) {
      if (stopping.get() && isShutdownNoise(dae)) {
        log.info("AlertEscalationNotifier poll skipped during shutdown: {}", dae.getMessage());
        return;
      }
      log.warn(
          "AlertEscalationNotifier DB 瞬时异常,下轮重试: {}",
          dae.getMostSpecificCause() == null
              ? dae.getMessage()
              : dae.getMostSpecificCause().getMessage());
    } catch (Throwable t) {
      if (stopping.get() && isShutdownNoise(t)) {
        log.info("AlertEscalationNotifier poll skipped during shutdown: {}", t.getMessage());
        return;
      }
      log.error("AlertEscalationNotifier 异常", t);
    } finally {
      running.set(false);
    }
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

  private void pollLocked() {
    if (stopping.get()) {
      return;
    }
    List<AlertEventEntity> batch =
        alertEventMapper.selectEscalatedPendingNotify(properties.getBatchSize());
    if (batch.isEmpty()) {
      return;
    }
    log.debug("AlertEscalationNotifier 本轮取 {} 条待通知升级告警", batch.size());
    for (AlertEventEntity alert : batch) {
      if (stopping.get()) {
        return;
      }
      try {
        notifyOne(alert);
      } catch (Throwable t) {
        // 单条异常不能拖累整批;水位线未推进,下轮会重试
        log.error(
            "AlertEscalationNotifier 单条通知异常: alertId={} tenantId={}",
            alert.getId(),
            alert.getTenantId(),
            t);
      }
    }
  }

  private void notifyOne(AlertEventEntity alert) {
    int tier = alert.getEscalationTier() == null ? 0 : alert.getEscalationTier();
    int notifiedTier =
        alert.getEscalationNotifiedTier() == null ? 0 : alert.getEscalationNotifiedTier();
    if (tier <= notifiedTier) {
      // 防御:select 谓词已过滤,这里再兜一层
      return;
    }
    // 先发事件(至少一次语义:webhook 自带投递日志 + relay 重试),再 CAS 推进水位线。
    domainEventPublisher.publishChanged(
        alert.getTenantId(),
        ALERT_STREAM,
        ESCALATED_EVENT_TYPE,
        new AlertEscalationNotifyPayload(
            alert.getId(),
            alert.getAlertType(),
            alert.getSeverity(),
            alert.getTitle(),
            tier,
            alert.getTraceId()));
    int marked =
        alertEventMapper.markEscalationNotified(
            alert.getTenantId(), alert.getId(), notifiedTier, tier);
    if (marked == 0) {
      // 被并发 ack 或其它实例抢先通知,水位线没动 —— 不重复计数。
      return;
    }
    notifyCounter.increment();
    log.info(
        "Alert escalation pushed to in-platform notification: alertId={} tenantId={} tier={} "
            + "alertType={} severity={} traceId={}",
        alert.getId(),
        alert.getTenantId(),
        tier,
        alert.getAlertType(),
        alert.getSeverity(),
        alert.getTraceId());
  }

  private LockConfiguration lockConfig() {
    return new LockConfiguration(
        BatchDateTimeSupport.utcNow(), "alert-escalation-notify", LOCK_AT_MOST, LOCK_AT_LEAST);
  }

  /** 升级通知 webhook 载荷(序列化进领域事件 {@code data},投递给订阅方)。 */
  public record AlertEscalationNotifyPayload(
      Long alertId,
      String alertType,
      String severity,
      String title,
      int escalationTier,
      String traceId) {}
}
