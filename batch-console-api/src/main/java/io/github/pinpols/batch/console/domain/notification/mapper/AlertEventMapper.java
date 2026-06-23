package io.github.pinpols.batch.console.domain.notification.mapper;

import io.github.pinpols.batch.common.persistence.entity.AlertEventEntity;
import io.github.pinpols.batch.console.domain.notification.query.AlertEventQuery;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface AlertEventMapper {

  List<AlertEventEntity> selectByQuery(AlertEventQuery query);

  AlertEventEntity selectById(@Param("tenantId") String tenantId, @Param("id") Long id);

  long countByQuery(AlertEventQuery query);

  long countByStatus(@Param("tenantId") String tenantId, @Param("status") String status);

  long countBySeverityAndStatus(
      @Param("tenantId") String tenantId,
      @Param("severity") String severity,
      @Param("status") String status);

  int updateStatus(
      @Param("tenantId") String tenantId, @Param("id") Long id, @Param("status") String status);

  /**
   * 选出「已升级但还没通知过」的 OPEN 告警(status='OPEN' AND escalation_tier &gt; escalation_notified_tier),跨租户、按
   * escalated_at 升序,供升级通知 notifier 逐条投递。
   */
  List<AlertEventEntity> selectEscalatedPendingNotify(@Param("limit") int limit);

  /**
   * CAS 推进通知水位线:仅当当前 escalation_notified_tier 仍等于 {@code expectedNotifiedTier} 且告警仍 OPEN 时, 把它抬到
   * {@code newNotifiedTier}。被并发 ack / 其它实例抢先通知时返回 0(本轮跳过,避免重复推送)。
   */
  int markEscalationNotified(
      @Param("tenantId") String tenantId,
      @Param("id") Long id,
      @Param("expectedNotifiedTier") int expectedNotifiedTier,
      @Param("newNotifiedTier") int newNotifiedTier);
}
