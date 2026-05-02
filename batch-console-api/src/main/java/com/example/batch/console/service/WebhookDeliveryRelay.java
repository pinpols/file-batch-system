package com.example.batch.console.service;

import com.example.batch.common.utils.JsonUtils;
import com.example.batch.console.domain.entity.WebhookDeliveryLogEntity;
import com.example.batch.console.domain.entity.WebhookSubscriptionEntity;
import com.example.batch.console.mapper.ConsoleWebhookDeliveryLogMapper;
import com.example.batch.console.mapper.ConsoleWebhookSubscriptionMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * ADR §5.11 / project-assessment-2026-04-30 §6.5: Webhook 持久化重试 relay。
 *
 * <p>循环步骤(每轮持 ShedLock 串行,多 console-api 实例间互斥):
 *
 * <ol>
 *   <li>{@code findEligibleRetries} 拿一批 EXHAUSTED + {@code next_retry_at <= now()} 的行 (FOR UPDATE
 *       SKIP LOCKED 防多实例重发)
 *   <li>逐行 {@code claimForRetry}(CAS 把 next_retry_at 置 null,失败跳过)
 *   <li>反序列化 payload + 加载 subscription → {@link WebhookDispatcher#attemptDelivery} 单次同步重投
 *   <li>成功 → {@code markRetrySuccess};失败且 attempt 未达上限 → {@code markRetryFailure} 退避后再 schedule;
 *       attempt 已达上限 → {@code markGiveUp} + 计数 metric (Prometheus 告警 hook)
 * </ol>
 *
 * <p>退避策略:失败时 {@code next_retry_at = now + min(30min, 2^(attempt - inlineMax) * 5min)}。
 *
 * <p>条件启用:默认开 ({@code batch.webhook.relay.enabled=true});关掉就退化为 dispatcher burst-only 兜底(EXHAUSTED
 * 行躺平,与 ADR §5.11 改造前一致)。
 */
@Component
@Slf4j
@ConditionalOnProperty(
    prefix = "batch.webhook.relay",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class WebhookDeliveryRelay {

  private static final Duration LOCK_AT_MOST = Duration.ofMinutes(2);
  private static final Duration LOCK_AT_LEAST = Duration.ofSeconds(2);

  /** 退避上限,单条失败后最长 30min 后重试。 */
  private static final long MAX_BACKOFF_SECONDS = 30L * 60L;

  /** 退避基数 = dispatcher burst 完后的初次 relay 延迟,与 dispatcher 的 INITIAL_RELAY_DELAY_SECONDS 对齐。 */
  private static final long BACKOFF_BASE_SECONDS = 5L * 60L;

  private final ConsoleWebhookDeliveryLogMapper deliveryLogRepository;
  private final ConsoleWebhookSubscriptionMapper subscriptionRepository;
  private final WebhookDispatcher dispatcher;
  private final LockingTaskExecutor lockingTaskExecutor;
  private final Counter giveUpCounter;

  @Value("${batch.webhook.relay.poll-interval-millis:60000}")
  private long pollIntervalMillis;

  @Value("${batch.webhook.relay.batch-size:50}")
  private int batchSize;

  /**
   * Relay 端绝对最大重试次数(含 dispatcher 的 burst attempts)。 默认 8 = dispatcher 3 + relay 5。达到此值 标 GIVE_UP +
   * 报警。
   */
  @Value("${batch.webhook.relay.absolute-max-attempts:8}")
  private int absoluteMaxAttempts;

  private final AtomicBoolean running = new AtomicBoolean(false);
  private ScheduledExecutorService executor;

  public WebhookDeliveryRelay(
      ConsoleWebhookDeliveryLogMapper deliveryLogRepository,
      ConsoleWebhookSubscriptionMapper subscriptionRepository,
      WebhookDispatcher dispatcher,
      LockingTaskExecutor lockingTaskExecutor,
      MeterRegistry meterRegistry) {
    this.deliveryLogRepository = deliveryLogRepository;
    this.subscriptionRepository = subscriptionRepository;
    this.dispatcher = dispatcher;
    this.lockingTaskExecutor = lockingTaskExecutor;
    this.giveUpCounter =
        Counter.builder("batch_webhook_delivery_give_up_total")
            .description("Webhook 行被 relay 标 GIVE_UP 的累计次数(达到 absolute-max-attempts)")
            .tags(Tags.empty())
            .register(meterRegistry);
  }

  @PostConstruct
  public void start() {
    executor =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "webhook-delivery-relay");
              t.setDaemon(true);
              return t;
            });
    executor.scheduleWithFixedDelay(
        this::poll, pollIntervalMillis, pollIntervalMillis, TimeUnit.MILLISECONDS);
    log.info(
        "WebhookDeliveryRelay 已启动:poll={}ms batch={} absoluteMax={}",
        pollIntervalMillis,
        batchSize,
        absoluteMaxAttempts);
  }

  @PreDestroy
  public void stop() {
    if (executor == null) {
      return;
    }
    executor.shutdown();
    try {
      if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
        log.warn("WebhookDeliveryRelay 未在 15s 内完成关闭,强制中断");
        executor.shutdownNow();
      }
    } catch (InterruptedException e) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  /** 单元测试可直接调用本方法跑一轮(不走自调度循环)。 */
  public void poll() {
    if (!running.compareAndSet(false, true)) {
      return;
    }
    try {
      lockingTaskExecutor.executeWithLock(
          (LockingTaskExecutor.Task) this::pollLocked, lockConfig());
    } catch (DataAccessException dae) {
      log.warn(
          "WebhookDeliveryRelay DB 瞬时异常,下轮重试: {}",
          dae.getMostSpecificCause() == null
              ? dae.getMessage()
              : dae.getMostSpecificCause().getMessage());
    } catch (Throwable t) {
      log.error("WebhookDeliveryRelay 异常", t);
    } finally {
      running.set(false);
    }
  }

  private void pollLocked() {
    Instant now = Instant.now();
    List<WebhookDeliveryLogEntity> batch =
        deliveryLogRepository.findEligibleRetries(now, batchSize);
    if (batch.isEmpty()) {
      return;
    }
    log.debug("WebhookDeliveryRelay 本轮取 {} 条待重投", batch.size());
    for (WebhookDeliveryLogEntity row : batch) {
      try {
        retryOne(row);
      } catch (Throwable t) {
        // 单条异常不能拖累整批;失败已写库,异常本身只为 ERROR 日志
        log.error(
            "WebhookDeliveryRelay 单条重投异常: id={} tenantId={} subscriptionId={}",
            row.getId(),
            row.getTenantId(),
            row.getSubscriptionId(),
            t);
      }
    }
  }

  private void retryOne(WebhookDeliveryLogEntity row) {
    int claimed = deliveryLogRepository.claimForRetry(row.getId());
    if (claimed == 0) {
      // 已被其它 relay 实例抢走或行状态已变,本轮跳过
      return;
    }

    Optional<WebhookSubscriptionEntity> subscription =
        subscriptionRepository.findByTenantAndId(row.getTenantId(), row.getSubscriptionId());
    if (subscription.isEmpty() || !Boolean.TRUE.equals(subscription.get().getEnabled())) {
      // 订阅被删 / 禁用 → 数据问题,直接 GIVE_UP 不再重试
      log.warn(
          "WebhookDeliveryRelay 跳过失效 subscription: id={} tenantId={} subscriptionId={}",
          row.getId(),
          row.getTenantId(),
          row.getSubscriptionId());
      deliveryLogRepository.markGiveUp(
          row.getId(), row.getAttempt(), row.getHttpStatus(), "subscription disabled or deleted");
      giveUpCounter.increment();
      return;
    }

    WebhookEventPayload payload;
    try {
      payload = JsonUtils.fromJson(row.getPayloadJson(), WebhookEventPayload.class);
    } catch (IllegalArgumentException ex) {
      // payload 反序列化失败 = 数据问题,不可能靠重试解决,直接 GIVE_UP
      log.error(
          "WebhookDeliveryRelay 反序列化 payload 失败,标 GIVE_UP: id={} tenantId={}",
          row.getId(),
          row.getTenantId(),
          ex);
      deliveryLogRepository.markGiveUp(
          row.getId(),
          row.getAttempt(),
          row.getHttpStatus(),
          "payload deserialization failed: " + ex.getMessage());
      giveUpCounter.increment();
      return;
    }

    int nextAttempt = row.getAttempt() + 1;
    WebhookDeliveryResult result =
        dispatcher.attemptDelivery(subscription.get(), payload, row.getPayloadJson());
    if (result.success()) {
      deliveryLogRepository.markRetrySuccess(row.getId(), nextAttempt, result.httpStatus());
      log.info(
          "WebhookDeliveryRelay 重投成功: id={} tenantId={} attempt={}",
          row.getId(),
          row.getTenantId(),
          nextAttempt);
      return;
    }

    if (nextAttempt >= absoluteMaxAttempts) {
      deliveryLogRepository.markGiveUp(
          row.getId(), nextAttempt, result.httpStatus(), result.errorSummary());
      giveUpCounter.increment();
      log.warn(
          "WebhookDeliveryRelay 达到上限,标 GIVE_UP: id={} tenantId={} attempt={} max={}",
          row.getId(),
          row.getTenantId(),
          nextAttempt,
          absoluteMaxAttempts);
      return;
    }

    Instant nextRetryAt = Instant.now().plusSeconds(computeBackoffSeconds(nextAttempt));
    deliveryLogRepository.markRetryFailure(
        row.getId(), nextAttempt, result.httpStatus(), result.errorSummary(), nextRetryAt);
    log.debug(
        "WebhookDeliveryRelay 重投失败,排程下次: id={} tenantId={} attempt={} nextRetryAt={}",
        row.getId(),
        row.getTenantId(),
        nextAttempt,
        nextRetryAt);
  }

  /**
   * 退避秒数 = min(MAX_BACKOFF_SECONDS, BACKOFF_BASE_SECONDS * 2^(nextAttempt - 4))。
   *
   * <p>nextAttempt=4(dispatcher 3 burst 后第 1 次 relay 重投失败) → 5min; nextAttempt=5 → 10min;
   * nextAttempt=6 → 20min; nextAttempt=7+ → 30min(截断)。
   */
  long computeBackoffSeconds(int nextAttempt) {
    int exponent = Math.max(0, nextAttempt - 4);
    long backoff = BACKOFF_BASE_SECONDS << Math.min(exponent, 10);
    return Math.min(MAX_BACKOFF_SECONDS, backoff);
  }

  private LockConfiguration lockConfig() {
    return new LockConfiguration(
        Instant.now(), "webhook-delivery-relay", LOCK_AT_MOST, LOCK_AT_LEAST);
  }
}
