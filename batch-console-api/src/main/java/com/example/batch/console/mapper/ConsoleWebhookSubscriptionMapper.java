package com.example.batch.console.mapper;

import com.example.batch.console.domain.entity.WebhookSubscriptionEntity;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Param;

/**
 * {@code batch.webhook_subscription} MyBatis 映射（替代原 Spring Data JDBC
 * ConsoleWebhookSubscriptionRepository）。
 */
public interface ConsoleWebhookSubscriptionMapper {

  List<WebhookSubscriptionEntity> findAllByTenant(@Param("tenantId") String tenantId);

  Optional<WebhookSubscriptionEntity> findByTenantAndId(
      @Param("tenantId") String tenantId, @Param("id") Long id);

  Optional<WebhookSubscriptionEntity> findByTenantAndName(
      @Param("tenantId") String tenantId, @Param("name") String name);

  List<WebhookSubscriptionEntity> findEnabledByTenant(@Param("tenantId") String tenantId);

  void insert(
      @Param("tenantId") String tenantId,
      @Param("name") String name,
      @Param("callbackUrl") String callbackUrl,
      @Param("eventTypes") String eventTypes,
      @Param("secret") String secret,
      @Param("enabled") boolean enabled,
      @Param("operator") String operator);

  void update(
      @Param("tenantId") String tenantId,
      @Param("id") Long id,
      @Param("callbackUrl") String callbackUrl,
      @Param("eventTypes") String eventTypes,
      @Param("secret") String secret,
      @Param("enabled") boolean enabled,
      @Param("operator") String operator);

  void deleteByTenantAndId(@Param("tenantId") String tenantId, @Param("id") Long id);
}
