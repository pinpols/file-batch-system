package io.github.pinpols.batch.orchestrator.mapper;

import io.github.pinpols.batch.orchestrator.domain.param.AlertEventQueryParam;
import io.github.pinpols.batch.common.enums.AlertStatus;
import io.github.pinpols.batch.common.persistence.entity.AlertEventEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface AlertEventMapper {

  default int insertOrMerge(AlertEventEntity entity) {
    return upsert(entity, AlertStatus.OPEN.code());
  }

  int upsert(
      @Param("entity") AlertEventEntity entity, @Param("defaultStatus") String defaultStatus);

  List<AlertEventEntity> selectByQuery(AlertEventQueryParam param);

  /**
   * 选出超过 ack-SLA 仍 OPEN 且未达最高 tier 的告警,供升级 sweep 处理。
   *
   * <p>SLA 随 tier 递进:第 N 级需静默 slaMinutes*N 分钟,按 last_seen_at 升序处理。
   */
  default List<AlertEventEntity> selectOverdueForEscalation(
      int slaMinutes, int maxTier, int limit) {
    return selectOverdueByStatus(slaMinutes, maxTier, limit, AlertStatus.OPEN.code());
  }

  List<AlertEventEntity> selectOverdueByStatus(
      @Param("slaMinutes") int slaMinutes,
      @Param("maxTier") int maxTier,
      @Param("limit") int limit,
      @Param("status") String status);

  /**
   * 把单条告警的 escalation_tier +1 并打 escalated_at 时间戳。
   *
   * <p>带 expectedTier 乐观守护:仅当行仍 OPEN 且 tier 未变时才更新,防止并发 ack 重复升级。
   */
  default int markEscalated(Long id, String tenantId, int expectedTier) {
    return incrementEscalationTier(id, tenantId, expectedTier, AlertStatus.OPEN.code());
  }

  int incrementEscalationTier(
      @Param("id") Long id,
      @Param("tenantId") String tenantId,
      @Param("expectedTier") int expectedTier,
      @Param("expectedStatus") String expectedStatus);

  /**
   * 选出当前仍 OPEN 的告警,供 AM re-emit 周期重发维持 firing 状态(迁移方案 §6.1)。
   *
   * <p>按 last_seen_at 降序取最近的一批(control 单轮规模),AM 端按 label 集合幂等,重复 fire 无副作用。
   */
  default List<AlertEventEntity> selectOpenForReemit(int limit) {
    return selectByStatusForReemit(AlertStatus.OPEN.code(), limit);
  }

  List<AlertEventEntity> selectByStatusForReemit(
      @Param("status") String status, @Param("limit") int limit);
}
