package io.github.pinpols.batch.console.domain.notification.service;

import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.config.WebhookRelayProperties;
import io.github.pinpols.batch.console.domain.notification.entity.TerminalNotificationDelivery;
import io.github.pinpols.batch.console.domain.notification.entity.WebhookSubscriptionEntity;
import io.github.pinpols.batch.console.domain.notification.mapper.ConsoleWebhookSubscriptionMapper;
import io.github.pinpols.batch.console.domain.notification.mapper.NotificationChannelMapper;
import io.github.pinpols.batch.console.domain.notification.mapper.SubscriptionRuleMapper;
import io.github.pinpols.batch.console.domain.notification.mapper.WorkflowTerminalNotificationMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable terminal deliveries, invoked by the existing Webhook relay under its scheduling lock. */
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

  public TerminalNotificationDeliveryService(WorkflowTerminalNotificationMapper deliveries,
      ConsoleWebhookSubscriptionMapper subscriptions, SubscriptionRuleMapper rules,
      NotificationChannelMapper channels, WebhookDispatcher webhooks,
      NotificationSenderRegistry senders, WebhookRelayProperties properties,
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

  public void poll(java.util.function.BooleanSupplier stopping) {
    if (stopping.getAsBoolean() || Thread.currentThread().isInterrupted()) {
      return;
    }
    for (var row : deliveries.pending(BatchDateTimeSupport.utcNow(), Math.max(1, properties.getBatchSize()))) {
      if (stopping.getAsBoolean() || Thread.currentThread().isInterrupted()) {
        return;
      }
      try {
        deliver(row);
      } catch (RuntimeException failure) {
        // A committed claim expires; a lost completion transaction never removes the retry obligation.
        log.warn("Terminal notification delivery interrupted; claim remains recoverable: tenant={} id={} source={} cause={}",
            row.tenantId(), row.id(), row.source(), SwallowedExceptionLogger.summary(failure));
      }
    }
  }

  private void deliver(TerminalNotificationDelivery row) {
    Instant now = BatchDateTimeSupport.utcNow();
    String token = UUID.randomUUID().toString();
    int maxAttempts = Math.max(1, properties.getAbsoluteMaxAttempts());
    if (!Integer.valueOf(1).equals(inTenant(row.tenantId(), () ->
        deliveries.claim(row, token, now, now.plus(Duration.ofMinutes(2)), maxAttempts)))) {
      return;
    }
    if (row.attempt() >= maxAttempts) {
      inTenant(row.tenantId(), () -> deliveries.finish(
          row, token, "EXHAUSTED", "delivery attempt budget exhausted; manual reconciliation required", null));
      return;
    }
    Destination destination = inTenant(row.tenantId(), () -> destination(row));
    if (row.attempt() > 0 && EmptyChecks.isNotNull(destination.message())) {
      inTenant(row.tenantId(), () -> deliveries.finish(row, token, "EXHAUSTED",
          "previous provider delivery outcome unknown; manual reconciliation required", null));
      return;
    }
    WebhookDeliveryResult result;
    try {
      if (EmptyChecks.isNotNull(destination.problem())) {
        result = WebhookDeliveryResult.failure(null, destination.problem());
      } else if (EmptyChecks.isNotNull(destination.webhook())) {
        result = webhooks.attemptDelivery(destination.webhook(), destination.payload(), row.payloadJson());
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
    // The webhook protocol carries a stable Idempotency-Key. Other providers do not declare
    // idempotency, so an uncertain failure remains visible for manual reconciliation.
    boolean retry = !success && EmptyChecks.isNotNull(destination.webhook())
        && row.attempt() + 1 < maxAttempts;
    Instant nextRetry = retry ? BatchDateTimeSupport.utcNow().plusSeconds(
        Math.min(1800L, 5L << Math.min(row.attempt(), 8))) : null;
    String error = result.errorSummary();
    String boundedError = EmptyChecks.isNull(error) ? null : error.substring(0, Math.min(1024, error.length()));
    int completed = inTenant(row.tenantId(), () -> deliveries.finish(
        row, token, success ? "SUCCESS" : "EXHAUSTED", boundedError, nextRetry));
    if (completed == 0) {
      log.info("Terminal notification completion fenced: tenant={} id={} source={}", row.tenantId(), row.id(), row.source());
    } else if (!success) {
      log.warn("Terminal notification not delivered: tenant={} id={} source={} retry={} cause={}",
          row.tenantId(), row.id(), row.source(), retry, boundedError);
    }
  }

  @SuppressWarnings("unchecked")
  private Destination destination(TerminalNotificationDelivery row) {
    WebhookEventPayload payload = JsonUtils.fromJson(row.payloadJson(), WebhookEventPayload.class);
    if ("WEBHOOK".equals(row.source())) {
      var subscription = subscriptions.findByTenantAndId(row.tenantId(), row.recipientId());
      return subscription.filter(s -> Boolean.TRUE.equals(s.getEnabled()))
          .map(s -> new Destination(s, null, payload, null))
          .orElseGet(() -> new Destination(null, null, payload, "subscription disabled or deleted"));
    }
    Map<String, Object> rule = rules.selectById(row.tenantId(), row.recipientId());
    Map<String, Object> channel = channels.selectByCode(row.tenantId(), row.channelCode());
    if (EmptyChecks.isNull(rule) || !Boolean.TRUE.equals(rule.get("enabled"))
        || !row.channelCode().equals(rule.get("channel_code"))
        || EmptyChecks.isNull(channel) || !Boolean.TRUE.equals(channel.get("enabled"))) {
      return new Destination(null, null, payload, "selected rule or channel disabled, deleted or reassigned");
    }
    String type = String.valueOf(channel.get("channel_type"));
    String configJson = String.valueOf(channel.get("config_json"));
    if ("WEBHOOK".equals(type)) {
      Map<String, Object> config = JsonUtils.fromJson(configJson, Map.class);
      if (EmptyChecks.isNull(config) || !(config.get("url") instanceof String url) || url.isBlank()) {
        return new Destination(null, null, payload, "webhook channel URL missing");
      }
      WebhookSubscriptionEntity subscription = new WebhookSubscriptionEntity();
      subscription.setTenantId(row.tenantId());
      subscription.setCallbackUrl(url);
      subscription.setSecret(EmptyChecks.isNull(config.get("secret")) ? null : String.valueOf(config.get("secret")));
      subscription.setEnabled(true);
      return new Destination(subscription, null, payload, null);
    }
    return new Destination(null, new NotificationMessage(row.tenantId(), row.channelCode(), type,
        configJson, payload, row.payloadJson()), payload, null);
  }

  private <T> T inTenant(String tenant, Supplier<T> action) {
    return RlsTenantContextHolder.runWithTenant(tenant, () -> transactions.execute(ignored -> action.get()));
  }

  private record Destination(WebhookSubscriptionEntity webhook, NotificationMessage message,
      WebhookEventPayload payload, String problem) {}
}
