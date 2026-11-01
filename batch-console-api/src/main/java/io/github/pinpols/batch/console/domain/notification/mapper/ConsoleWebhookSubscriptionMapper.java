package io.github.pinpols.batch.console.domain.notification.mapper;

import io.github.pinpols.batch.console.domain.notification.entity.WebhookSubscriptionEntity;
import io.github.pinpols.batch.console.domain.notification.param.ConsoleWebhookSubscriptionInsertParam;
import io.github.pinpols.batch.console.domain.notification.param.ConsoleWebhookSubscriptionUpdateParam;
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

  /** 统计租户当前有效订阅数，用于创建时的配额校验；已软删的行不计入。 */
  long countByTenant(@Param("tenantId") String tenantId);

  void insert(ConsoleWebhookSubscriptionInsertParam parameters);

  void update(ConsoleWebhookSubscriptionUpdateParam parameters);

  void deleteByTenantAndId(@Param("tenantId") String tenantId, @Param("id") Long id);
}
