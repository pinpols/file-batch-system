package io.github.pinpols.batch.console.domain.notification.service;

import lombok.Builder;

import io.github.pinpols.batch.console.domain.notification.param.ConsoleWebhookSubscriptionInsertParam;
import io.github.pinpols.batch.console.domain.notification.param.ConsoleWebhookSubscriptionUpdateParam;


import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Guard;
import io.github.pinpols.batch.console.domain.notification.entity.WebhookDeliveryLogEntity;
import io.github.pinpols.batch.console.domain.notification.entity.WebhookSubscriptionEntity;
import io.github.pinpols.batch.console.domain.notification.mapper.ConsoleWebhookDeliveryLogMapper;
import io.github.pinpols.batch.console.domain.notification.mapper.ConsoleWebhookSubscriptionMapper;
import io.github.pinpols.batch.console.shared.query.TenantIdResolver;
import io.github.pinpols.batch.console.support.CallbackUrlValidator;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import java.util.Locale;
import java.util.Optional;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ConsoleWebhookService {

  private final ConsoleWebhookSubscriptionMapper subscriptionRepository;
  private final ConsoleWebhookDeliveryLogMapper deliveryLogRepository;
  private final TenantIdResolver tenantGuard;
  private final CallbackUrlValidator callbackUrlValidator;

  public List<WebhookSubscriptionEntity> listSubscriptions(String tenantId) {
    String resolvedTenantId = tenantGuard.resolveTenant(tenantId);
    return subscriptionRepository.findAllByTenant(resolvedTenantId);
  }

  public WebhookSubscriptionEntity getSubscription(String tenantId, Long id) {
    String resolvedTenantId = tenantGuard.resolveTenant(tenantId);
    return subscriptionRepository
        .findByTenantAndId(resolvedTenantId, id)
        .orElseThrow(
            () -> BizException.of(ResultCode.NOT_FOUND, "error.webhook.subscription_not_found"));
  }

  public WebhookSubscriptionEntity createSubscription(CreateSubscriptionCommand command) {
    String resolved = tenantGuard.resolveTenant(command.tenantId());
    callbackUrlValidator.validate(command.callbackUrl());
    subscriptionRepository.findByTenantAndName(resolved, command.name()).ifPresent(existing -> {
      throw BizException.of(ResultCode.CONFLICT, "error.webhook.subscription_exists");
    });
    String name = command.name();
    String callbackUrl = command.callbackUrl();
    String normalizedEventTypes = normalizeEventTypes(command.eventTypes());
    ConsoleWebhookSubscriptionInsertParam consoleWebhookSubscriptionInsertParam = ConsoleWebhookSubscriptionInsertParam.builder()
        .tenantId(resolved)
        .name(name)
        .callbackUrl(callbackUrl)
        .eventTypes(normalizedEventTypes)
        .secret(command.secret())
        .enabled(command.enabled())
        .operator(command.operator())
        .build();
    subscriptionRepository.insert(
        consoleWebhookSubscriptionInsertParam);
    return subscriptionRepository
        .findByTenantAndName(resolved, command.name())
        .orElseThrow(() -> BizException.of(
            ResultCode.SYSTEM_ERROR, "error.webhook.subscription_created_but_not_found"));
  }

  public WebhookSubscriptionEntity updateSubscription(UpdateSubscriptionCommand command) {
    String resolved = tenantGuard.resolveTenant(command.tenantId());
    Optional<WebhookSubscriptionEntity> existingSubscription =
        subscriptionRepository.findByTenantAndId(resolved, command.id());
    if (!existingSubscription.isPresent()) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.webhook.subscription_not_found");
    }
    callbackUrlValidator.validate(command.callbackUrl());
    Long subscriptionId = command.id();
    String callbackUrl = command.callbackUrl();
    String normalizedEventTypes = normalizeEventTypes(command.eventTypes());
    ConsoleWebhookSubscriptionUpdateParam consoleWebhookSubscriptionUpdateParam = ConsoleWebhookSubscriptionUpdateParam.builder()
        .tenantId(resolved)
        .id(subscriptionId)
        .callbackUrl(callbackUrl)
        .eventTypes(normalizedEventTypes)
        .secret(command.secret())
        .enabled(command.enabled())
        .operator(command.operator())
        .build();
    subscriptionRepository.update(
        consoleWebhookSubscriptionUpdateParam);
    return subscriptionRepository
        .findByTenantAndId(resolved, command.id())
        .orElseThrow(() -> BizException.of(
            ResultCode.SYSTEM_ERROR, "error.webhook.subscription_updated_but_not_found"));
  }

  public void deleteSubscription(String tenantId, Long id) {
    String resolvedTenantId = tenantGuard.resolveTenant(tenantId);
    subscriptionRepository.deleteByTenantAndId(resolvedTenantId, id);
  }

  @Transactional(readOnly = true)
  public List<WebhookDeliveryLogEntity> deliveryLogs(
      String tenantId, Long subscriptionId, int limit) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    deliveryLogRepository.bindTenant(resolved);
    if (subscriptionId != null) {
      return deliveryLogRepository.findBySubscription(resolved, subscriptionId, limit);
    }
    return deliveryLogRepository.findRecentByTenant(resolved, limit);
  }

  /** 查询指定租户下所有启用的订阅（供 dispatcher 使用）。 */
  public List<WebhookSubscriptionEntity> findEnabledSubscriptions(String tenantId) {
    String resolvedTenantId = tenantGuard.resolveTenant(tenantId);
    return subscriptionRepository.findEnabledByTenant(resolvedTenantId);
  }

  private String normalizeEventTypes(String eventTypes) {
    Guard.requireText(eventTypes, "eventTypes is required");
    NotificationEventTypePolicy.requireBusinessEventTypes(eventTypes);
    Stream<String> eventTypeTokens = Arrays.stream(eventTypes.split(","));
    Stream<String> trimmedEventTypes = eventTypeTokens.map(String::trim);
    Stream<String> nonBlankEventTypes = trimmedEventTypes.filter(value -> !EmptyChecks.isBlank(Objects.requireNonNull(value)));
    Stream<String> normalizedEventTypes =
        nonBlankEventTypes.map(value -> value.toUpperCase(Locale.ROOT));
    Stream<String> distinctEventTypes = normalizedEventTypes.distinct();
    return distinctEventTypes
        .reduce((left, right) -> left + "," + right)
        .orElseThrow(() ->
            BizException.of(ResultCode.INVALID_ARGUMENT, "error.webhook.event_types_required"));
  }

  @Builder
  public record CreateSubscriptionCommand(
      String tenantId,
      String name,
      String callbackUrl,
      String eventTypes,
      String secret,
      boolean enabled,
      String operator) {}

  @Builder
  public record UpdateSubscriptionCommand(
      String tenantId,
      Long id,
      String callbackUrl,
      String eventTypes,
      String secret,
      boolean enabled,
      String operator) {}
}
