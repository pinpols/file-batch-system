package io.github.pinpols.batch.console.domain.notification.service;

import io.github.pinpols.batch.console.domain.notification.param.WorkflowTerminalNotificationClaimParam;
import io.github.pinpols.batch.console.domain.notification.param.WorkflowTerminalNotificationFinishParam;


import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.config.WebhookRelayProperties;
import io.github.pinpols.batch.console.domain.notification.view.TerminalNotificationDelivery;
import io.github.pinpols.batch.console.domain.notification.entity.WebhookSubscriptionEntity;
import io.github.pinpols.batch.console.domain.notification.mapper.ConsoleWebhookSubscriptionMapper;
import io.github.pinpols.batch.console.domain.notification.mapper.NotificationChannelMapper;
import io.github.pinpols.batch.console.domain.notification.mapper.SubscriptionRuleMapper;
import io.github.pinpols.batch.console.domain.notification.mapper.WorkflowTerminalNotificationMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 持久化终态投递；由现有 Webhook 中继在调度锁下调用。 */
@Service
@Slf4j
public class TerminalNotificationDeliveryService {
  private final WorkflowTerminalNotificationMapper deliveries;
  private final ConsoleWebhookSubscriptionMapper subscriptions;
  private final SubscriptionRuleMapper rules;
  private final NotificationChannelMapper channels;
  private final WebhookDispatcher webhooks;
  private final NotificationSenderRegistry senders;
  private final WebhookRelayProperties properties;
  private final TransactionTemplate transactions;

  public TerminalNotificationDeliveryService(
      WorkflowTerminalNotificationMapper deliveries,
      ConsoleWebhookSubscriptionMapper subscriptions,
      SubscriptionRuleMapper rules,
      NotificationChannelMapper channels,
      WebhookDispatcher webhooks,
      NotificationSenderRegistry senders,
      WebhookRelayProperties properties,
      @Qualifier("transactionManager") PlatformTransactionManager transactionManager) {
    this.deliveries = deliveries;
    this.subscriptions = subscriptions;
    this.rules = rules;
    this.channels = channels;
    this.webhooks = webhooks;
    this.senders = senders;
    this.properties = properties;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  public void poll() {
    poll(() -> false);
  }

  public void poll(BooleanSupplier stopping) {
    if (stopping.getAsBoolean() || Thread.currentThread().isInterrupted()) {
      return;
    }
    Instant pollTime = BatchDateTimeSupport.utcNow();
    int effectiveBatchSize = Math.max(1, properties.getBatchSize());
    for (TerminalNotificationDelivery row :
        deliveries.pending(pollTime, effectiveBatchSize)) {
      if (stopping.getAsBoolean() || Thread.currentThread().isInterrupted()) {
        return;
      }
      try {
        deliver(row);
      } catch (RuntimeException failure) {
        // 已提交的领取记录会过期；完成事务丢失不会清除重试义务。
        log.warn(
            "Terminal notification delivery interrupted; claim remains recoverable: tenant={} id={} source={} cause={}",
            row.tenantId(),
            row.id(),
            row.source(),
            SwallowedExceptionLogger.summary(failure));
      }
    }
  }

  private void deliver(TerminalNotificationDelivery row) {
    Instant now = BatchDateTimeSupport.utcNow();
    UUID tokenId = UUID.randomUUID();
    String token = tokenId.toString();
    int maxAttempts = Math.max(1, properties.getAbsoluteMaxAttempts());
    String tenantId = row.tenantId();
    Integer claimResult = inTenant(tenantId, () -> {
      Instant leaseExpiresAt = now.plus(Duration.ofMinutes(2));
      WorkflowTerminalNotificationClaimParam workflowTerminalNotificationClaimParam = WorkflowTerminalNotificationClaimParam.builder()
          .row(row)
          .token(token)
          .now(now)
          .until(leaseExpiresAt)
          .maxAttempts(maxAttempts)
          .build();
      return deliveries.claim(workflowTerminalNotificationClaimParam);
    });
    if (!Integer.valueOf(1).equals(claimResult)) {
      return;
    }
    if (row.attempt() >= maxAttempts) {
      inTenant(
          row.tenantId(),
          () -> {
            WorkflowTerminalNotificationFinishParam workflowTerminalNotificationFinishParam = WorkflowTerminalNotificationFinishParam.builder()
                .row(row)
                .token(token)
                .status("EXHAUSTED")
                .error("delivery attempt budget exhausted; manual reconciliation required")
                .nextRetry(null)
                .build();
            return deliveries.finish(
              workflowTerminalNotificationFinishParam);
          });
      return;
    }
    Destination destination = inTenant(row.tenantId(), () -> destination(row));
    if (row.attempt() > 0 && EmptyChecks.isNotNull(destination.message())) {
      inTenant(
          row.tenantId(),
          () -> {
            WorkflowTerminalNotificationFinishParam workflowTerminalNotificationFinishParam2 = WorkflowTerminalNotificationFinishParam.builder()
                .row(row)
                .token(token)
                .status("EXHAUSTED")
                .error("previous provider delivery outcome unknown; manual reconciliation required")
                .nextRetry(null)
                .build();
            return deliveries.finish(
              workflowTerminalNotificationFinishParam2);
          });
      return;
    }
    WebhookDeliveryResult result;
    try {
      if (EmptyChecks.isNotNull(destination.problem())) {
        result = WebhookDeliveryResult.failure(null, destination.problem());
      } else if (EmptyChecks.isNotNull(destination.webhook())) {
        result = webhooks.attemptDelivery(
            destination.webhook(), destination.payload(), row.payloadJson());
      } else {
        NotificationSender sender = senders.resolve(destination.message().channelType());
        result = EmptyChecks.isNull(sender)
            ? WebhookDeliveryResult.failure(null, "notification sender unavailable")
            : sender.send(destination.message());
      }
    } catch (RuntimeException failure) {
      result = WebhookDeliveryResult.failure(null, SwallowedExceptionLogger.summary(failure));
    }
    boolean success = result.success();
    // Webhook 协议携带稳定的 Idempotency-Key。其他服务方未声明
    // 幂等能力，因此结果不确定的失败必须保留并供人工协调。
    boolean retry =
        !success && EmptyChecks.isNotNull(destination.webhook()) && row.attempt() + 1 < maxAttempts;
    Instant nextRetry = null;
    if (retry) {
      Instant retryScheduledAt = BatchDateTimeSupport.utcNow();
      int boundedBackoffExponent = Math.min(row.attempt(), 8);
      long retryDelaySeconds = Math.min(1800L, 5L << boundedBackoffExponent);
      nextRetry = retryScheduledAt.plusSeconds(retryDelaySeconds);
    }
    String error = result.errorSummary();
    String boundedError;
    if (EmptyChecks.isNull(error)) {
      boundedError = null;
    } else {
      int boundedErrorLength = Math.min(1024, error.length());
      boundedError = error.substring(0, boundedErrorLength);
    }
    Instant retryAt = nextRetry;
    int completed = inTenant(
        row.tenantId(),
        () -> {
          WorkflowTerminalNotificationFinishParam workflowTerminalNotificationFinishParam3 = WorkflowTerminalNotificationFinishParam.builder()
              .row(row)
              .token(token)
              .status(success ? "SUCCESS" : "EXHAUSTED")
              .error(boundedError)
              .nextRetry(retryAt)
              .build();
          return deliveries.finish(
            workflowTerminalNotificationFinishParam3);
        });
    if (completed == 0) {
      log.info(
          "Terminal notification completion fenced: tenant={} id={} source={}",
          row.tenantId(),
          row.id(),
          row.source());
    } else if (!success) {
      log.warn(
          "Terminal notification not delivered: tenant={} id={} source={} retry={} cause={}",
          row.tenantId(),
          row.id(),
          row.source(),
          retry,
          boundedError);
    }
  }

  private Destination destination(TerminalNotificationDelivery row) {
    WebhookEventPayload payload = JsonUtils.fromJson(row.payloadJson(), WebhookEventPayload.class);
    if ("WEBHOOK".equals(row.source())) {
      Optional<WebhookSubscriptionEntity> subscription =
          subscriptions.findByTenantAndId(row.tenantId(), row.recipientId());
      return subscription
          .filter(s -> Boolean.TRUE.equals(s.getEnabled()))
          .map(s -> Destination.webhook(s, payload))
          .orElseGet(
              () -> Destination.unavailable(payload, "subscription disabled or deleted"));
    }
    Map<String, Object> rule = rules.selectById(row.tenantId(), row.recipientId());
    Map<String, Object> channel = channels.selectByCode(row.tenantId(), row.channelCode());
    boolean ruleOrChannelIsUnavailableForDelivery = EmptyChecks.isNull(rule)
        || !Boolean.TRUE.equals(rule.get("enabled"))
        || !row.channelCode().equals(rule.get("channel_code"))
        || EmptyChecks.isNull(channel)
        || !Boolean.TRUE.equals(channel.get("enabled"));
    if (ruleOrChannelIsUnavailableForDelivery) {
      return Destination.unavailable(
          payload, "selected rule or channel disabled, deleted or reassigned");
    }
    String type = String.valueOf(channel.get("channel_type"));
    String configJson = String.valueOf(channel.get("config_json"));
    if ("WEBHOOK".equals(type)) {
      Map<?, ?> config = JsonUtils.fromJson(configJson, Map.class);
      if (EmptyChecks.isNull(config)
          || !(config.get("url") instanceof String url)
          || EmptyChecks.isBlank(url)) {
        return Destination.unavailable(payload, "webhook channel URL missing");
      }
      WebhookSubscriptionEntity subscription = new WebhookSubscriptionEntity();
      subscription.setTenantId(row.tenantId());
      subscription.setCallbackUrl(url);
      Object configuredSecret = config.get("secret");
      String webhookSecret =
          EmptyChecks.isNull(configuredSecret) ? null : String.valueOf(configuredSecret);
      subscription.setSecret(webhookSecret);
      subscription.setEnabled(true);
      return Destination.webhook(subscription, payload);
    }
    NotificationMessage message = new NotificationMessage(
        row.tenantId(), row.channelCode(), type, configJson, payload, row.payloadJson());
    return Destination.notification(message, payload);
  }

  private <T> T inTenant(String tenant, Supplier<T> action) {
    return RlsTenantContextHolder.runWithTenant(
        tenant,
        () -> transactions.execute(ignored -> {
          deliveries.bindTenant(tenant);
          return action.get();
        }));
  }

  private record Destination(
      WebhookSubscriptionEntity webhook,
      NotificationMessage message,
      WebhookEventPayload payload,
      String problem) {

    private static Destination webhook(
        WebhookSubscriptionEntity subscription, WebhookEventPayload payload) {
      return new Destination(subscription, null, payload, null);
    }

    private static Destination notification(
        NotificationMessage message, WebhookEventPayload payload) {
      return new Destination(null, message, payload, null);
    }

    private static Destination unavailable(WebhookEventPayload payload, String problem) {
      return new Destination(null, null, payload, problem);
    }
  }
}
