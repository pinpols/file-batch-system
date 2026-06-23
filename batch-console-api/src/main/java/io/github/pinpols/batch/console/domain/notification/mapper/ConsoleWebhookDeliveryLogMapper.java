package io.github.pinpols.batch.console.domain.notification.mapper;

import io.github.pinpols.batch.console.domain.notification.entity.WebhookDeliveryLogEntity;
import io.github.pinpols.batch.console.domain.notification.param.WebhookDeliveryLogInsertParam;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Param;

/**
 * {@code batch.webhook_delivery_log} MyBatis 映射（替代原 Spring Data JDBC
 * ConsoleWebhookDeliveryLogRepository）。
 */
public interface ConsoleWebhookDeliveryLogMapper {

  List<WebhookDeliveryLogEntity> findRecentByTenant(
      @Param("tenantId") String tenantId, @Param("limit") int limit);

  List<WebhookDeliveryLogEntity> findBySubscription(
      @Param("tenantId") String tenantId,
      @Param("subscriptionId") Long subscriptionId,
      @Param("limit") int limit);

  void insert(@Param("p") WebhookDeliveryLogInsertParam p);

  Long insertReturningId(@Param("p") WebhookDeliveryLogInsertParam p);

  /** Relay 候选扫描：取 PENDING/EXHAUSTED 且到期需投递的行，{@code FOR UPDATE SKIP LOCKED} 防止多 relay 实例抢同一批。 */
  List<WebhookDeliveryLogEntity> findEligibleRetries(
      @Param("now") Instant now, @Param("limit") int limit);

  Optional<WebhookDeliveryLogEntity> findById(@Param("id") Long id);

  /** Relay CAS 抢占：把 next_retry_at 置 null，返回 1=本实例独占成功，0=被其他 relay 抢先。 */
  int claimForRetry(@Param("id") Long id);

  int markRetrySuccess(
      @Param("id") Long id, @Param("attempt") int attempt, @Param("httpStatus") Integer httpStatus);

  int markRetryFailure(
      @Param("id") Long id,
      @Param("attempt") int attempt,
      @Param("httpStatus") Integer httpStatus,
      @Param("responseBody") String responseBody,
      @Param("nextRetryAt") Instant nextRetryAt);

  int markGiveUp(
      @Param("id") Long id,
      @Param("attempt") int attempt,
      @Param("httpStatus") Integer httpStatus,
      @Param("responseBody") String responseBody);
}
