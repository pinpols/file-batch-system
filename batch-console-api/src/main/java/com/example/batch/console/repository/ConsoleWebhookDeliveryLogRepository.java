package com.example.batch.console.repository;

import com.example.batch.console.domain.entity.WebhookDeliveryLogEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

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
             (tenant_id, subscription_id, event_type, payload_json, http_status, response_body, delivery_status, attempt, next_retry_at)
      VALUES (:#{#p.tenantId}, :#{#p.subscriptionId}, :#{#p.eventType}, cast(:#{#p.payloadJson} as jsonb), :#{#p.httpStatus}, :#{#p.responseBody}, :#{#p.deliveryStatus}, :#{#p.attempt}, :#{#p.nextRetryAt})
      """)
  void insert(@Param("p") WebhookDeliveryLogInsertParam p);

  // ── ADR §5.11 WebhookDeliveryRelay support ───────────────────────────────────

  @Query(
      """
      SELECT id, tenant_id, subscription_id, event_type, payload_json, http_status,
             response_body, delivery_status, attempt, next_retry_at, created_at
        FROM batch.webhook_delivery_log
       WHERE delivery_status = 'EXHAUSTED'
         AND next_retry_at IS NOT NULL
         AND next_retry_at <= :now
       ORDER BY next_retry_at ASC
       LIMIT :limit
       FOR UPDATE SKIP LOCKED
      """)
  List<WebhookDeliveryLogEntity> findEligibleRetries(
      @Param("now") Instant now, @Param("limit") int limit);

  @Query(
      """
      SELECT id, tenant_id, subscription_id, event_type, payload_json, http_status,
             response_body, delivery_status, attempt, next_retry_at, created_at
        FROM batch.webhook_delivery_log
       WHERE id = :id
       LIMIT 1
      """)
  Optional<WebhookDeliveryLogEntity> findById(@Param("id") Long id);

  /**
   * Relay 抢占:CAS 把 next_retry_at 置 null,防止其它实例同时取到这一行。返回更新行数 = 抢占结果(0=失败,1=成功)。 抢占成功后 relay 真正执行
   * HTTP 重投,根据结果再 markRetrySuccess / markRetryFailure / markGiveUp。
   */
  @Modifying
  @Query(
      """
      UPDATE batch.webhook_delivery_log
         SET next_retry_at = NULL
       WHERE id = :id
         AND delivery_status = 'EXHAUSTED'
         AND next_retry_at IS NOT NULL
      """)
  int claimForRetry(@Param("id") Long id);

  @Modifying
  @Query(
      """
      UPDATE batch.webhook_delivery_log
         SET delivery_status = 'SUCCESS',
             http_status     = :httpStatus,
             response_body   = NULL,
             attempt         = :attempt,
             next_retry_at   = NULL
       WHERE id = :id
      """)
  int markRetrySuccess(
      @Param("id") Long id, @Param("attempt") int attempt, @Param("httpStatus") Integer httpStatus);

  /** 重投仍失败:bump attempt + 退避后再 schedule;状态保持 EXHAUSTED 等下轮 relay 取。 */
  @Modifying
  @Query(
      """
      UPDATE batch.webhook_delivery_log
         SET attempt        = :attempt,
             http_status    = :httpStatus,
             response_body  = :responseBody,
             next_retry_at  = :nextRetryAt
       WHERE id = :id
      """)
  int markRetryFailure(
      @Param("id") Long id,
      @Param("attempt") int attempt,
      @Param("httpStatus") Integer httpStatus,
      @Param("responseBody") String responseBody,
      @Param("nextRetryAt") Instant nextRetryAt);

  @Modifying
  @Query(
      """
      UPDATE batch.webhook_delivery_log
         SET delivery_status = 'GIVE_UP',
             attempt         = :attempt,
             http_status     = :httpStatus,
             response_body   = :responseBody,
             next_retry_at   = NULL
       WHERE id = :id
      """)
  int markGiveUp(
      @Param("id") Long id,
      @Param("attempt") int attempt,
      @Param("httpStatus") Integer httpStatus,
      @Param("responseBody") String responseBody);
}
