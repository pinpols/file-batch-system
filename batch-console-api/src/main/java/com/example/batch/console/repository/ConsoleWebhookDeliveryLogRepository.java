package com.example.batch.console.repository;

import com.example.batch.console.domain.entity.WebhookDeliveryLogEntity;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ConsoleWebhookDeliveryLogRepository
        extends Repository<WebhookDeliveryLogEntity, Long> {

    @Query(
            """
            SELECT id, tenant_id, subscription_id, event_type, payload_json, http_status,
                   response_body, delivery_status, attempt, next_retry_at, created_at
              FROM batch.webhook_delivery_log
             WHERE tenant_id = :tenantId
             ORDER BY created_at DESC
             LIMIT :limit
            """)
    List<WebhookDeliveryLogEntity> findRecentByTenant(
            @Param("tenantId") String tenantId, @Param("limit") int limit);

    @Query(
            """
            SELECT id, tenant_id, subscription_id, event_type, payload_json, http_status,
                   response_body, delivery_status, attempt, next_retry_at, created_at
              FROM batch.webhook_delivery_log
             WHERE tenant_id = :tenantId AND subscription_id = :subscriptionId
             ORDER BY created_at DESC
             LIMIT :limit
            """)
    List<WebhookDeliveryLogEntity> findBySubscription(
            @Param("tenantId") String tenantId,
            @Param("subscriptionId") Long subscriptionId,
            @Param("limit") int limit);

    @Modifying
    @Query(
            """
            INSERT INTO batch.webhook_delivery_log
                   (tenant_id, subscription_id, event_type, payload_json, http_status, response_body, delivery_status, attempt)
            VALUES (:tenantId, :subscriptionId, :eventType, cast(:payloadJson as jsonb), :httpStatus, :responseBody, :deliveryStatus, :attempt)
            """)
    void insert(
            @Param("tenantId") String tenantId,
            @Param("subscriptionId") Long subscriptionId,
            @Param("eventType") String eventType,
            @Param("payloadJson") String payloadJson,
            @Param("httpStatus") Integer httpStatus,
            @Param("responseBody") String responseBody,
            @Param("deliveryStatus") String deliveryStatus,
            @Param("attempt") int attempt);
}
