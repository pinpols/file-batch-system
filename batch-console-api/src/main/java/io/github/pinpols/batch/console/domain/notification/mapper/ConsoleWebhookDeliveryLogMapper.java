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

  enum PersistenceStatus {
    PENDING,
    SUCCESS,
    EXHAUSTED,
    GIVE_UP
  }

  List<WebhookDeliveryLogEntity> findRecentByTenant(
      @Param("tenantId") String tenantId, @Param("limit") int limit);

  List<WebhookDeliveryLogEntity> findBySubscription(
      @Param("tenantId") String tenantId,
      @Param("subscriptionId") Long subscriptionId,
      @Param("limit") int limit);

  void insert(@Param("p") WebhookDeliveryLogInsertParam p);

  Long insertReturningId(@Param("p") WebhookDeliveryLogInsertParam p);

  /** Relay 候选扫描：取 PENDING/EXHAUSTED 且到期需投递的行，{@code FOR UPDATE SKIP LOCKED} 防止多 relay 实例抢同一批。 */
  default List<WebhookDeliveryLogEntity> findEligibleRetries(Instant now, int limit) {
    List<String> eligibleStatuses =
        List.of(PersistenceStatus.PENDING.name(), PersistenceStatus.EXHAUSTED.name());
    return findEligibleRetriesByStatuses(now, limit, eligibleStatuses);
  }

  List<WebhookDeliveryLogEntity> findEligibleRetriesByStatuses(
      @Param("now") Instant now,
      @Param("limit") int limit,
      @Param("eligibleStatuses") List<String> eligibleStatuses);

  Optional<WebhookDeliveryLogEntity> findById(@Param("id") Long id);

  /** Relay CAS 抢占：把 next_retry_at 置 null，返回 1=本实例独占成功，0=被其他 relay 抢先。 */
  default int claimForRetry(Long id) {
    List<String> eligibleStatuses =
        List.of(PersistenceStatus.PENDING.name(), PersistenceStatus.EXHAUSTED.name());
    return claimForRetryByStatuses(id, eligibleStatuses);
  }

  int claimForRetryByStatuses(
      @Param("id") Long id, @Param("eligibleStatuses") List<String> eligibleStatuses);

  default int markRetrySuccess(Long id, int attempt, Integer httpStatus) {
    return updateRetrySuccess(id, attempt, httpStatus, PersistenceStatus.SUCCESS.name());
  }

  int updateRetrySuccess(
      @Param("id") Long id,
      @Param("attempt") int attempt,
      @Param("httpStatus") Integer httpStatus,
      @Param("deliveryStatus") String deliveryStatus);

  default int markRetryFailure(
      Long id, int attempt, Integer httpStatus, String responseBody, Instant nextRetryAt) {
    return updateRetryFailure(
        id, attempt, httpStatus, responseBody, nextRetryAt, PersistenceStatus.EXHAUSTED.name());
  }

  int updateRetryFailure(
      @Param("id") Long id,
      @Param("attempt") int attempt,
      @Param("httpStatus") Integer httpStatus,
      @Param("responseBody") String responseBody,
      @Param("nextRetryAt") Instant nextRetryAt,
      @Param("deliveryStatus") String deliveryStatus);

  default int markGiveUp(Long id, int attempt, Integer httpStatus, String responseBody) {
    return updateGiveUp(id, attempt, httpStatus, responseBody, PersistenceStatus.GIVE_UP.name());
  }

  int updateGiveUp(
      @Param("id") Long id,
      @Param("attempt") int attempt,
      @Param("httpStatus") Integer httpStatus,
      @Param("responseBody") String responseBody,
      @Param("deliveryStatus") String deliveryStatus);
}
