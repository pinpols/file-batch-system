package com.example.batch.orchestrator.mapper;

import com.example.batch.common.persistence.entity.AlertEventEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface AlertEventMapper {

  int insertOrMerge(AlertEventEntity entity);

  List<AlertEventEntity> selectByQuery(
      @Param("tenantId") String tenantId,
      @Param("severity") String severity,
      @Param("status") String status,
      @Param("alertType") String alertType,
      @Param("limit") Integer limit);

  /**
   * 选出超过 ack-SLA 仍 OPEN 且未达最高 tier 的告警,供升级 sweep 处理。
   *
   * <p>SLA 随 tier 递进:第 N 级需静默 slaMinutes*N 分钟,按 last_seen_at 升序处理。
   */
  List<AlertEventEntity> selectOverdueForEscalation(
      @Param("slaMinutes") int slaMinutes,
      @Param("maxTier") int maxTier,
      @Param("limit") int limit);

  /**
   * 把单条告警的 escalation_tier +1 并打 escalated_at 时间戳。
   *
   * <p>带 expectedTier 乐观守护:仅当行仍 OPEN 且 tier 未变时才更新,防止并发 ack 重复升级。
   */
  int markEscalated(
      @Param("id") Long id,
      @Param("tenantId") String tenantId,
      @Param("expectedTier") int expectedTier);
}
