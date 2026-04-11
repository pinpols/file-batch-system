package com.example.batch.console.service;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.Guard;
import com.example.batch.console.domain.entity.WebhookDeliveryLogEntity;
import com.example.batch.console.domain.entity.WebhookSubscriptionEntity;
import com.example.batch.console.repository.ConsoleWebhookDeliveryLogRepository;
import com.example.batch.console.repository.ConsoleWebhookSubscriptionRepository;
import com.example.batch.console.support.ConsoleTenantGuard;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ConsoleWebhookService {

    private final ConsoleWebhookSubscriptionRepository subscriptionRepository;
    private final ConsoleWebhookDeliveryLogRepository deliveryLogRepository;
    private final ConsoleTenantGuard tenantGuard;

    public List<WebhookSubscriptionEntity> listSubscriptions(String tenantId) {
        return subscriptionRepository.findAllByTenant(tenantGuard.resolveTenant(tenantId));
    }

    public WebhookSubscriptionEntity getSubscription(String tenantId, Long id) {
        return subscriptionRepository
                .findByTenantAndId(tenantGuard.resolveTenant(tenantId), id)
                .orElseThrow(
                        () ->
                                new BizException(
                                        ResultCode.NOT_FOUND, "webhook subscription not found"));
    }

    public WebhookSubscriptionEntity createSubscription(
            String tenantId,
            String name,
            String callbackUrl,
            String eventTypes,
            String secret,
            boolean enabled,
            String operator) {
        String resolved = tenantGuard.resolveTenant(tenantId);
        subscriptionRepository
                .findByTenantAndName(resolved, name)
                .ifPresent(
                        existing -> {
                            throw new BizException(
                                    ResultCode.CONFLICT, "webhook subscription already exists");
                        });
        subscriptionRepository.insert(
                resolved,
                name,
                callbackUrl,
                normalizeEventTypes(eventTypes),
                secret,
                enabled,
                operator);
        return subscriptionRepository
                .findByTenantAndName(resolved, name)
                .orElseThrow(
                        () ->
                                new BizException(
                                        ResultCode.SYSTEM_ERROR,
                                        "webhook subscription created but not found"));
    }

    public WebhookSubscriptionEntity updateSubscription(
            String tenantId,
            Long id,
            String callbackUrl,
            String eventTypes,
            String secret,
            boolean enabled,
            String operator) {
        String resolved = tenantGuard.resolveTenant(tenantId);
        if (subscriptionRepository.findByTenantAndId(resolved, id).isEmpty()) {
            throw new BizException(ResultCode.NOT_FOUND, "webhook subscription not found");
        }
        subscriptionRepository.update(
                resolved,
                id,
                callbackUrl,
                normalizeEventTypes(eventTypes),
                secret,
                enabled,
                operator);
        return subscriptionRepository
                .findByTenantAndId(resolved, id)
                .orElseThrow(
                        () ->
                                new BizException(
                                        ResultCode.SYSTEM_ERROR,
                                        "webhook subscription updated but not found"));
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
                                        new BizException(
                                                ResultCode.INVALID_ARGUMENT,
                                                "eventTypes is required"));
        if (Objects.equals(normalized, "*")) {
            return normalized;
        }
        return normalized;
    }
}
