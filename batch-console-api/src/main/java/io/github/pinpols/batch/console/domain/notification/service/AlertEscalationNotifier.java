package io.github.pinpols.batch.console.domain.notification.service;

import io.github.pinpols.batch.common.enums.OutboxPublishStatus;
import io.github.pinpols.batch.common.i18n.BizExceptionUtils;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.persistence.entity.AlertEventEntity;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.application.realtime.ConsoleRealtimeEventPort;
import io.github.pinpols.batch.console.config.AlertEscalationNotifyProperties;
import io.github.pinpols.batch.console.domain.notification.entity.AlertEscalationNotificationOutboxEntity;
import io.github.pinpols.batch.console.domain.notification.mapper.AlertEscalationNotificationOutboxMapper;
import io.github.pinpols.batch.console.domain.notification.mapper.AlertEventMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
 * escalation_tier}(V176),但只打日志/指标,无人被主动通知(headless)。本 notifier 把「刚升级、还没通知过」的告警
 * ({@code escalation_tier > escalation_notified_tier})先用 CAS 抢占通知所有权,并在同一事务写入通知 outbox；随后 relay
 * 从 outbox 发布 {@code alerts/ALERT_ESCALATED} 领域事件,保证每次 tier 抬升只入队一次、失败可重试。
 *
 * <p>复用既有能力,新增本地通知 outbox,不新增 Kafka / policy 表:
 *
 * <ul>
 *   <li>入队:{@link AlertEscalationNotificationOutboxService} CAS 推进 {@code escalation_notified_tier} 并写入
 *       {@code alert_escalation_notification_outbox},避免并发抢占失败后仍发出通知。
 *   <li>投递:relay 抢占 outbox 后通过 {@link ConsoleRealtimeEventPort#publishChanged} 发 {@code
 *       alerts/ALERT_ESCALATED} 领域事件 → {@code ConsoleWebhookDomainEventListener} → 现有 webhook
 *       分发器(与告警 ack 走同一条路)。
 *   <li>调度:console-api 未启用全局 {@code @EnableScheduling},沿用自管理 {@link ScheduledExecutorService} +
 *       programmatic ShedLock(同 {@link WebhookDeliveryRelay}),多实例间互斥。
 * </ul>
 *
 * <p>边界:本组件只发布 {@code ALERT_ESCALATED} 领域事件；后续由订阅规则按 WEBHOOK、EMAIL、DINGTALK、WECOM、
 * SLACK 或 SMS 路由到已注册的 sender。Alertmanager 迁移后本组件默认关闭，仅保留为回滚路径。
 *
 * <p>条件启用:默认开({@code batch.alert.escalation.notify.enabled=true});关掉退化回 V176 纯日志/指标放大。
 */
@Component
@Slf4j
@ConditionalOnProperty(
    prefix = "batch.alert.escalation.notify",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = false)
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
  private static final int MAX_PUBLISH_ATTEMPTS = 10;
  private static final long RETRY_DELAY_SECONDS = 60L;
  private static final long STALE_PUBLISHING_SECONDS = 120L;

  private final AlertEventMapper alertEventMapper;
  private final AlertEscalationNotificationOutboxMapper outboxMapper;
  private final AlertEscalationNotificationOutboxService outboxService;
  private final ConsoleRealtimeEventPort domainEventPublisher;
  private final LockingTaskExecutor lockingTaskExecutor;
  private final AlertEscalationNotifyProperties properties;
  private final Counter notifyCounter;

  private final AtomicBoolean running = new AtomicBoolean(false);
  private final AtomicBoolean stopping = new AtomicBoolean(false);
  private ScheduledExecutorService executor;
  private final AtomicReference<ScheduledFuture<?>> scheduledTask = new AtomicReference<>();

  public AlertEscalationNotifier(
      AlertEventMapper alertEventMapper,
      AlertEscalationNotificationOutboxMapper outboxMapper,
      AlertEscalationNotificationOutboxService outboxService,
      ConsoleRealtimeEventPort domainEventPublisher,
      LockingTaskExecutor lockingTaskExecutor,
      AlertEscalationNotifyProperties properties,
      MeterRegistry meterRegistry) {
    this.alertEventMapper = alertEventMapper;
    this.outboxMapper = outboxMapper;
    this.outboxService = outboxService;
    this.domainEventPublisher = domainEventPublisher;
    this.lockingTaskExecutor = lockingTaskExecutor;
    this.properties = properties;
    this.notifyCounter = Counter.builder("batch.alert.escalation.notifications")
        .description("告警升级被推到平台内通知链路(webhook)的累计次数")
        .tags(Tags.empty())
        .register(meterRegistry);
  }

  @PostConstruct
  public void start() {
    executor = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "alert-escalation-notifier");
      t.setDaemon(true);
      return t;
    });
    scheduledTask.set(executor.scheduleWithFixedDelay(
        this::poll,
        properties.getPollIntervalMillis(),
        properties.getPollIntervalMillis(),
        TimeUnit.MILLISECONDS));
    log.info(
        "AlertEscalationNotifier started: poll={}ms batch={}",
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
    ScheduledFuture<?> task = scheduledTask.getAndSet(null);
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
        log.warn("AlertEscalationNotifier did not shut down within 15s; forcing interruption");
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
      lockingTaskExecutor.executeWithLock((Runnable) this::pollLocked, lockConfig());
    } catch (DataAccessException dae) {
      if (stopping.get() && isShutdownNoise(dae)) {
        log.info("AlertEscalationNotifier poll skipped during shutdown: {}", dae.getMessage());
        return;
      }
      log.warn(
          "AlertEscalationNotifier transient DB failure; retrying on the next cycle: {}",
          dae.getMostSpecificCause() == null
              ? dae.getMessage()
              : dae.getMostSpecificCause().getMessage());
    } catch (Exception t) {
      if (stopping.get() && isShutdownNoise(t)) {
        log.info("AlertEscalationNotifier poll skipped during shutdown: {}", t.getMessage());
        return;
      }
      log.error("AlertEscalationNotifier failed", t);
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
    resetStalePublishing();
    List<AlertEventEntity> batch =
        alertEventMapper.selectEscalatedPendingNotify(properties.getBatchSize());
    if (EmptyChecks.isNotEmpty(batch)) {
      log.debug("AlertEscalationNotifier loaded {} escalation notifications", batch.size());
      for (AlertEventEntity alert : batch) {
        if (stopping.get()) {
          return;
        }
        try {
          enqueueOne(alert);
        } catch (Exception t) {
          // 单条异常不能拖累整批；CAS 未成功时水位线不推进，下一轮会重试。
          log.error(
              "AlertEscalationNotifier failed to enqueue one notification: alertId={} tenantId={}",
              alert.getId(),
              alert.getTenantId(),
              t);
        }
      }
    }
    publishPendingOutbox();
  }

  private void resetStalePublishing() {
    List<AlertEscalationNotificationOutboxEntity> stale = outboxMapper.selectStalePublishing(
        properties.getBatchSize(), OutboxPublishStatus.PUBLISHING.code(), STALE_PUBLISHING_SECONDS);
    if (EmptyChecks.isEmpty(stale)) {
      return;
    }
    int reset = 0;
    for (AlertEscalationNotificationOutboxEntity row : stale) {
      reset += outboxMapper.resetStalePublishing(
          row.getId(),
          row.getTenantId(),
          OutboxPublishStatus.FAILED.code(),
          OutboxPublishStatus.PUBLISHING.code());
    }
    if (reset > 0) {
      log.warn("AlertEscalationNotifier reset stale PUBLISHING outbox rows: count={}", reset);
    }
  }

  private void publishPendingOutbox() {
    List<AlertEscalationNotificationOutboxEntity> pending = outboxMapper.selectPending(
        BatchDateTimeSupport.utcNow(),
        properties.getBatchSize(),
        OutboxPublishStatus.NEW.code(),
        OutboxPublishStatus.FAILED.code());
    if (EmptyChecks.isEmpty(pending)) {
      return;
    }
    for (AlertEscalationNotificationOutboxEntity row : pending) {
      if (stopping.get()) {
        return;
      }
      publishOne(row);
    }
  }

  private void enqueueOne(AlertEventEntity alert) {
    int tier = zeroIfNull(alert.getEscalationTier());
    int notifiedTier = zeroIfNull(alert.getEscalationNotifiedTier());
    if (tier <= notifiedTier) {
      // 防御:select 谓词已过滤,这里再兜一层
      return;
    }
    outboxService.enqueue(
        alert,
        ALERT_STREAM,
        ESCALATED_EVENT_TYPE,
        new AlertEscalationNotifyPayload(
            alert.getId(),
            alert.getAlertType(),
            alert.getSeverity(),
            alert.getTitle(),
            tier,
            alert.getTraceId()));
  }

  private void publishOne(AlertEscalationNotificationOutboxEntity row) {
    int claimed = outboxMapper.markPublishing(
        row.getId(),
        row.getTenantId(),
        OutboxPublishStatus.PUBLISHING.code(),
        OutboxPublishStatus.NEW.code(),
        OutboxPublishStatus.FAILED.code());
    if (claimed == 0) {
      return;
    }
    try {
      AlertEscalationNotifyPayload payload =
          JsonUtils.fromJson(row.getPayloadJson(), AlertEscalationNotifyPayload.class);
      domainEventPublisher.publishChanged(
          row.getTenantId(), row.getStream(), row.getEventType(), payload);
      int marked = outboxMapper.markPublished(
          row.getId(),
          row.getTenantId(),
          OutboxPublishStatus.PUBLISHED.code(),
          OutboxPublishStatus.PUBLISHING.code());
      if (marked > 0) {
        notifyCounter.increment();
        logPublished(row);
      }
    } catch (Exception ex) {
      markPublishFailure(row, ex);
    }
  }

  private void markPublishFailure(AlertEscalationNotificationOutboxEntity row, Exception ex) {
    int nextAttempt = zeroIfNull(row.getAttemptCount()) + 1;
    String error = BizExceptionUtils.ofLiteral(ex.getMessage()).renderedMessage();
    if (nextAttempt >= MAX_PUBLISH_ATTEMPTS) {
      outboxMapper.markGiveUp(
          row.getId(),
          row.getTenantId(),
          OutboxPublishStatus.GIVE_UP.code(),
          error,
          OutboxPublishStatus.PUBLISHING.code());
      log.error(
          "AlertEscalationNotifier GIVE_UP: outboxId={} alertId={} tenantId={} attempt={} error={}",
          row.getId(),
          row.getAlertEventId(),
          row.getTenantId(),
          nextAttempt,
          error);
      return;
    }
    Instant nextPublishAt = BatchDateTimeSupport.utcNow().plusSeconds(RETRY_DELAY_SECONDS);
    outboxMapper.markFailed(
        row.getId(),
        row.getTenantId(),
        OutboxPublishStatus.FAILED.code(),
        nextPublishAt,
        error,
        OutboxPublishStatus.PUBLISHING.code());
    log.warn(
        "AlertEscalationNotifier publish failed: outboxId={} alertId={} tenantId={} attempt={} "
            + "nextPublishAt={} error={}",
        row.getId(),
        row.getAlertEventId(),
        row.getTenantId(),
        nextAttempt,
        nextPublishAt,
        error);
  }

  private void logPublished(AlertEscalationNotificationOutboxEntity row) {
    log.info(
        "Alert escalation pushed to in-platform notification: alertId={} tenantId={} tier={} "
            + "eventType={}",
        row.getAlertEventId(),
        row.getTenantId(),
        row.getEscalationTier(),
        row.getEventType());
  }

  private static int zeroIfNull(Integer value) {
    return EmptyChecks.isNull(value) ? 0 : value;
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
