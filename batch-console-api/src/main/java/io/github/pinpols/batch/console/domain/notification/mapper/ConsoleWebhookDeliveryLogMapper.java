package io.github.pinpols.batch.console.domain.notification.mapper;

import io.github.pinpols.batch.console.domain.notification.param.ConsoleWebhookDeliveryLogMarkRetryFailureParam;
import io.github.pinpols.batch.console.domain.notification.param.ConsoleWebhookDeliveryLogUpdateGiveUpParam;
import io.github.pinpols.batch.console.domain.notification.param.ConsoleWebhookDeliveryLogUpdateRetryFailureParam;


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

  /** 在当前事务中绑定已授权租户，供热冷历史查询共用。 */
  String bindTenant(@Param("tenantId") String tenantId);

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
      ConsoleWebhookDeliveryLogMarkRetryFailureParam parameters) {
    Long id = parameters.id();
    int attempt = parameters.attempt();
    Integer httpStatus = parameters.httpStatus();
    String responseBody = parameters.responseBody();
    Instant nextRetryAt = parameters.nextRetryAt();

    ConsoleWebhookDeliveryLogUpdateRetryFailureParam consoleWebhookDeliveryLogUpdateRetryFailureParam = ConsoleWebhookDeliveryLogUpdateRetryFailureParam.builder()
        .id(id)
        .attempt(attempt)
        .httpStatus(httpStatus)
        .responseBody(responseBody)
        .nextRetryAt(nextRetryAt)
        .deliveryStatus(PersistenceStatus.EXHAUSTED.name())
        .build();
    return updateRetryFailure(
        consoleWebhookDeliveryLogUpdateRetryFailureParam);
  }

  int updateRetryFailure(
      ConsoleWebhookDeliveryLogUpdateRetryFailureParam parameters);

  default int markGiveUp(Long id, int attempt, Integer httpStatus, String responseBody) {
    ConsoleWebhookDeliveryLogUpdateGiveUpParam consoleWebhookDeliveryLogUpdateGiveUpParam = ConsoleWebhookDeliveryLogUpdateGiveUpParam.builder()
        .id(id)
        .attempt(attempt)
        .httpStatus(httpStatus)
        .responseBody(responseBody)
        .deliveryStatus(PersistenceStatus.GIVE_UP.name())
        .build();
    return updateGiveUp(consoleWebhookDeliveryLogUpdateGiveUpParam);
  }

  int updateGiveUp(
      ConsoleWebhookDeliveryLogUpdateGiveUpParam parameters);
}
