package io.github.pinpols.batch.console.domain.notification.service;

import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.config.ConsolePushProperties;
import io.github.pinpols.batch.console.config.WebhookRelayProperties;
import io.github.pinpols.batch.console.domain.notification.entity.ConsolePushSubscriptionEntity;
import io.github.pinpols.batch.console.domain.notification.mapper.ConsolePushSubscriptionMapper;
import io.github.pinpols.batch.console.domain.notification.mapper.JobPushDeliveryMapper;
import io.github.pinpols.batch.console.domain.notification.param.JobPushDeliveryClaimParam;
import io.github.pinpols.batch.console.domain.notification.param.JobPushDeliveryFinishParam;
import io.github.pinpols.batch.console.domain.notification.support.ConsolePushSender;
import io.github.pinpols.batch.console.domain.notification.support.ConsolePushSender.DeliveryOutcome;
import io.github.pinpols.batch.console.domain.notification.support.ConsolePushSender.DeliveryResult;
import io.github.pinpols.batch.console.domain.notification.support.ConsolePushSender.PushPayload;
import io.github.pinpols.batch.console.domain.notification.view.JobPushDelivery;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 提交领取记录后才调用推送服务；外部 IO 期间不持有数据库事务。 */
@Service
@Slf4j
public class JobPushDeliveryService {
  private final JobPushDeliveryMapper deliveries;
  private final ConsolePushSubscriptionMapper subscriptions;
  private final ConsolePushSender sender;
  private final ConsolePushProperties properties;
  private final WebhookRelayProperties budgets;
  private final TransactionTemplate transactions;
  private String afterTenant = "";

  public JobPushDeliveryService(
      JobPushDeliveryMapper deliveries,
      ConsolePushSubscriptionMapper subscriptions,
      ConsolePushSender sender,
      ConsolePushProperties properties,
      WebhookRelayProperties budgets,
      @Qualifier("transactionManager") PlatformTransactionManager manager) {
    this.deliveries = deliveries;
    this.subscriptions = subscriptions;
    this.sender = sender;
    this.properties = properties;
    this.budgets = budgets;
    this.transactions = new TransactionTemplate(manager);
  }

  public synchronized void poll(BooleanSupplier stopping) {
    boolean pollingUnavailable = stopped(stopping)
        || !properties.isEnabled()
        || !properties.getJobNotify().isEnabled();
    if (pollingUnavailable) return;
    int remaining = Math.max(1, properties.getJobNotify().getBatchSize());
    List<String> tenants = deliveries.tenantPage(afterTenant, Math.min(100, remaining));
    if (EmptyChecks.isEmpty(Objects.requireNonNull(tenants))) {
      afterTenant = "";
      tenants = deliveries.tenantPage(afterTenant, Math.min(100, remaining));
    }
    for (String tenant : tenants) {
      boolean tenantScanShouldStop = stopped(stopping) || remaining <= 0;
      if (tenantScanShouldStop) return;
      afterTenant = tenant;
      try {
        Instant now = BatchDateTimeSupport.utcNow();
        int limit = remaining;
        remaining -=
            inTenant(tenant, () -> deliveries.reconcileStopped(tenant, now, maxAttempts(), limit));
        if (remaining <= 0) return;
        int candidates = remaining;
        List<JobPushDelivery> pending =
            inTenant(tenant, () -> deliveries.pending(tenant, now, candidates));
        for (JobPushDelivery row : pending) {
          boolean deliveryScanShouldStop = stopped(stopping) || remaining <= 0;
          if (deliveryScanShouldStop) return;
          remaining--;
          deliver(row);
        }
      } catch (RuntimeException failure) {
        log.warn(
            "Job Push scan interrupted tenant={} cause={}",
            tenant,
            SwallowedExceptionLogger.summary(failure));
      }
    }
  }

  private void deliver(JobPushDelivery row) {
    Instant now = BatchDateTimeSupport.utcNow();
    UUID tokenId = UUID.randomUUID();
    String token = tokenId.toString();
    Integer claimResult = inTenant(row.tenantId(), () -> {
      Instant leaseExpiresAt = now.plus(Duration.ofMinutes(2));
      int maxAttempts = maxAttempts();
      JobPushDeliveryClaimParam claimParam =
          new JobPushDeliveryClaimParam(row, token, now, leaseExpiresAt, maxAttempts);
      return deliveries.claim(claimParam);
    });
    if (!Integer.valueOf(1).equals(claimResult)) {
      return;
    }
    try {
      Optional<ConsolePushSubscriptionEntity> device = inTenant(
          row.tenantId(),
          () -> subscriptions.findByTenantAndId(row.tenantId(), row.subscriptionId()));
      DeliveryResult result;
      boolean pushDeviceSubscriptionDoesNotMatch = !device.isPresent()
          || !row.operatorId().equals(device.get().getUsername())
          || !row.subscriptionFingerprint().equals(JobPushIntakeService.fingerprint(device.get()));
      if (pushDeviceSubscriptionDoesNotMatch) {
        result = new DeliveryResult(DeliveryOutcome.GONE, null);
      } else {
        ConsolePushSubscriptionEntity subscription = device.get();
        PushPayload payload = JsonUtils.fromJson(row.payloadJson(), PushPayload.class);
        result = sender.sendToSubscription(subscription, payload);
      }
      boolean retry = result.outcome() == DeliveryOutcome.RETRYABLE_FAILURE
          && row.attempt() + 1 < maxAttempts();
      boolean notReady = result.outcome() == DeliveryOutcome.NOT_READY;
      Instant next = null;
      if (notReady) {
        next = BatchDateTimeSupport.utcNow().plusSeconds(30);
      } else if (retry) {
        Instant retryScheduledAt = BatchDateTimeSupport.utcNow();
        int boundedBackoffExponent = Math.min(row.attempt(), 8);
        long retryDelaySeconds = Math.min(1800L, 5L << boundedBackoffExponent);
        next = retryScheduledAt.plusSeconds(retryDelaySeconds);
      }
      String status =
          switch (result.outcome()) {
            case ACCEPTED -> "ACCEPTED";
            case GONE -> "GONE";
            case NOT_READY -> "PENDING";
            case RETRYABLE_FAILURE -> retry ? "PENDING" : "EXHAUSTED";
            case PERMANENT_FAILURE, UNKNOWN -> "EXHAUSTED";
          };
      String error = result.outcome() == DeliveryOutcome.ACCEPTED
          ? null
          : result.outcome().name() + ": provider status=" + result.httpStatus();
      Instant nextAttemptAt = next;
      int completed = inTenant(row.tenantId(), () -> {
        JobPushDeliveryFinishParam finishParam = new JobPushDeliveryFinishParam(
            row, token, status, error, result.httpStatus(), nextAttemptAt, notReady);
        return deliveries.finish(finishParam);
      });
      if (completed == 0)
        log.info("Job Push completion fenced tenant={} id={}", row.tenantId(), row.id());
    } catch (RuntimeException failure) {
      // 推送服务可能已接收请求，但本地完成记录失败。保留领取记录供 UNKNOWN 状态协调。
      log.warn(
          "Job Push attempt interrupted tenant={} id={} cause={}",
          row.tenantId(),
          row.id(),
          SwallowedExceptionLogger.summary(failure));
    }
  }

  private int maxAttempts() {
    return Math.max(1, budgets.getAbsoluteMaxAttempts());
  }

  private static boolean stopped(BooleanSupplier stopping) {
    return stopping.getAsBoolean() || Thread.currentThread().isInterrupted();
  }

  private <T> T inTenant(String tenant, Supplier<T> action) {
    return RlsTenantContextHolder.runWithTenant(
        tenant,
        () -> transactions.execute(status -> {
          deliveries.bindTenant(tenant);
          return action.get();
        }));
  }
}
