package com.example.batch.console.service;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.console.domain.entity.WebhookDeliveryLogEntity;
import com.example.batch.console.domain.entity.WebhookSubscriptionEntity;
import com.example.batch.console.repository.ConsoleWebhookDeliveryLogRepository;
import com.example.batch.console.repository.ConsoleWebhookSubscriptionRepository;
import com.example.batch.console.support.ConsoleTenantGuard;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

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
        return subscriptionRepository.findByTenantAndId(tenantGuard.resolveTenant(tenantId), id)
                .orElseThrow(() -> new BizException(ResultCode.NOT_FOUND, "webhook subscription not found"));
    }

    public void createSubscription(String tenantId, String name, String callbackUrl,
                                   String eventTypes, String secret, boolean enabled, String operator) {
        subscriptionRepository.insert(tenantGuard.resolveTenant(tenantId),
                name, callbackUrl, eventTypes, secret, enabled, operator);
    }

    public void updateSubscription(String tenantId, Long id, String callbackUrl,
                                   String eventTypes, String secret, boolean enabled, String operator) {
        subscriptionRepository.update(tenantGuard.resolveTenant(tenantId),
                id, callbackUrl, eventTypes, secret, enabled, operator);
    }

    public void deleteSubscription(String tenantId, Long id) {
        subscriptionRepository.deleteByTenantAndId(tenantGuard.resolveTenant(tenantId), id);
    }

    public List<WebhookDeliveryLogEntity> deliveryLogs(String tenantId, Long subscriptionId, int limit) {
        String resolved = tenantGuard.resolveTenant(tenantId);
        if (subscriptionId != null) {
            return deliveryLogRepository.findBySubscription(resolved, subscriptionId, limit);
        }
        return deliveryLogRepository.findRecentByTenant(resolved, limit);
    }

    /** 查询指定租户下所有启用的订阅（供 dispatcher 使用）。 */
    public List<WebhookSubscriptionEntity> findEnabledSubscriptions(String tenantId) {
        return subscriptionRepository.findEnabledByTenant(tenantId);
    }
}
