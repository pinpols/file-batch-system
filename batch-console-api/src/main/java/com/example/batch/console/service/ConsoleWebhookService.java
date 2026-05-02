package com.example.batch.console.service;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.Guard;
import com.example.batch.console.domain.entity.WebhookDeliveryLogEntity;
import com.example.batch.console.domain.entity.WebhookSubscriptionEntity;
import com.example.batch.console.mapper.ConsoleWebhookDeliveryLogMapper;
import com.example.batch.console.mapper.ConsoleWebhookSubscriptionMapper;
import com.example.batch.console.support.CallbackUrlValidator;
import com.example.batch.console.support.auth.ConsoleTenantGuard;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ConsoleWebhookService {

  private final ConsoleWebhookSubscriptionMapper subscriptionRepository;
  private final ConsoleWebhookDeliveryLogMapper deliveryLogRepository;
  private final ConsoleTenantGuard tenantGuard;
  private final CallbackUrlValidator callbackUrlValidator;

  public List<WebhookSubscriptionEntity> listSubscriptions(String tenantId) {
    return subscriptionRepository.findAllByTenant(tenantGuard.resolveTenant(tenantId));
  }

  public WebhookSubscriptionEntity getSubscription(String tenantId, Long id) {
    return subscriptionRepository
        .findByTenantAndId(tenantGuard.resolveTenant(tenantId), id)
        .orElseThrow(
            () -> BizException.of(ResultCode.NOT_FOUND, "error.webhook.subscription_not_found"));
  }

  public WebhookSubscriptionEntity createSubscription(CreateSubscriptionCommand command) {
    String resolved = tenantGuard.resolveTenant(command.tenantId());
    callbackUrlValidator.validate(command.callbackUrl());
    subscriptionRepository
        .findByTenantAndName(resolved, command.name())
        .ifPresent(
            existing -> {
              throw BizException.of(ResultCode.CONFLICT, "error.webhook.subscription_exists");
            });
    subscriptionRepository.insert(
        resolved,
        command.name(),
        command.callbackUrl(),
        normalizeEventTypes(command.eventTypes()),
        command.secret(),
        command.enabled(),
        command.operator());
    return subscriptionRepository
        .findByTenantAndName(resolved, command.name())
        .orElseThrow(
            () ->
                BizException.of(
                    ResultCode.SYSTEM_ERROR, "error.webhook.subscription_created_but_not_found"));
  }

  public WebhookSubscriptionEntity updateSubscription(UpdateSubscriptionCommand command) {
    String resolved = tenantGuard.resolveTenant(command.tenantId());
    if (subscriptionRepository.findByTenantAndId(resolved, command.id()).isEmpty()) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.webhook.subscription_not_found");
    }
    callbackUrlValidator.validate(command.callbackUrl());
    subscriptionRepository.update(
        resolved,
        command.id(),
        command.callbackUrl(),
        normalizeEventTypes(command.eventTypes()),
        command.secret(),
        command.enabled(),
        command.operator());
    return subscriptionRepository
        .findByTenantAndId(resolved, command.id())
        .orElseThrow(
            () ->
                BizException.of(
                    ResultCode.SYSTEM_ERROR, "error.webhook.subscription_updated_but_not_found"));
  }

  public void deleteSubscription(String tenantId, Long id) {
    subscriptionRepository.deleteByTenantAndId(tenantGuard.resolveTenant(tenantId), id);
  }

  public List<WebhookDeliveryLogEntity> deliveryLogs(
      String tenantId, Long subscriptionId, int limit) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    if (subscriptionId != null) {
      return deliveryLogRepository.findBySubscription(resolved, subscriptionId, limit);
    }
    return deliveryLogRepository.findRecentByTenant(resolved, limit);
  }

  /** 查询指定租户下所有启用的订阅（供 dispatcher 使用）。 */
  public List<WebhookSubscriptionEntity> findEnabledSubscriptions(String tenantId) {
    return subscriptionRepository.findEnabledByTenant(tenantGuard.resolveTenant(tenantId));
  }

  private String normalizeEventTypes(String eventTypes) {
    Guard.requireText(eventTypes, "eventTypes is required");
    String normalized =
        Arrays.stream(eventTypes.split(","))
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .map(value -> value.toUpperCase(Locale.ROOT))
            .distinct()
            .reduce((left, right) -> left + "," + right)
            .orElseThrow(
                () ->
                    BizException.of(
                        ResultCode.INVALID_ARGUMENT, "error.webhook.event_types_required"));
    return normalized;
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
