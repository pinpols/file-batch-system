package com.example.batch.console.repository;

import com.example.batch.console.domain.entity.WebhookSubscriptionEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface ConsoleWebhookSubscriptionRepository
    extends Repository<WebhookSubscriptionEntity, Long> {

  @Query(
      """
      SELECT id, tenant_id, name, callback_url, event_types, secret, enabled,
             created_by, updated_by, created_at, updated_at
        FROM batch.webhook_subscription
       WHERE tenant_id = :tenantId
       ORDER BY name
      """)
  List<WebhookSubscriptionEntity> findAllByTenant(@Param("tenantId") String tenantId);

  @Query(
      """
      SELECT id, tenant_id, name, callback_url, event_types, secret, enabled,
             created_by, updated_by, created_at, updated_at
        FROM batch.webhook_subscription
       WHERE tenant_id = :tenantId AND id = :id
       LIMIT 1
      """)
  Optional<WebhookSubscriptionEntity> findByTenantAndId(
      @Param("tenantId") String tenantId, @Param("id") Long id);

  @Query(
      """
      SELECT id, tenant_id, name, callback_url, event_types, secret, enabled,
             created_by, updated_by, created_at, updated_at
        FROM batch.webhook_subscription
       WHERE tenant_id = :tenantId AND name = :name
       LIMIT 1
      """)
  Optional<WebhookSubscriptionEntity> findByTenantAndName(
      @Param("tenantId") String tenantId, @Param("name") String name);

  @Query(
      """
      SELECT id, tenant_id, name, callback_url, event_types, secret, enabled,
             created_by, updated_by, created_at, updated_at
        FROM batch.webhook_subscription
       WHERE tenant_id = :tenantId AND enabled = TRUE
      """)
  List<WebhookSubscriptionEntity> findEnabledByTenant(@Param("tenantId") String tenantId);

  @Modifying
  @Query(
      """
      INSERT INTO batch.webhook_subscription (tenant_id, name, callback_url, event_types, secret, enabled, created_by, updated_by)
      VALUES (:tenantId, :name, :callbackUrl, :eventTypes, :secret, :enabled, :operator, :operator)
      """)
  void insert(
      @Param("tenantId") String tenantId,
      @Param("name") String name,
      @Param("callbackUrl") String callbackUrl,
      @Param("eventTypes") String eventTypes,
      @Param("secret") String secret,
      @Param("enabled") boolean enabled,
      @Param("operator") String operator);

  @Modifying
  @Query(
      """
      UPDATE batch.webhook_subscription
         SET callback_url = :callbackUrl,
             event_types  = :eventTypes,
             secret       = :secret,
             enabled      = :enabled,
             updated_by   = :operator,
             updated_at   = CURRENT_TIMESTAMP
       WHERE tenant_id = :tenantId AND id = :id
      """)
  void update(
      @Param("tenantId") String tenantId,
      @Param("id") Long id,
      @Param("callbackUrl") String callbackUrl,
      @Param("eventTypes") String eventTypes,
      @Param("secret") String secret,
      @Param("enabled") boolean enabled,
      @Param("operator") String operator);

  @Modifying
  @Query("DELETE FROM batch.webhook_subscription WHERE tenant_id = :tenantId AND id = :id")
  void deleteByTenantAndId(@Param("tenantId") String tenantId, @Param("id") Long id);
}
