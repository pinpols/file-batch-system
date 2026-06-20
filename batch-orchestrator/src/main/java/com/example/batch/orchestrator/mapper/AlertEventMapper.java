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
   * 选出超过 ack-SLA 仍处于 OPEN 且未爬到最高 tier 的告警，供升级 sweep 处理。
   *
   * <p>SLA 随 tier 递进：第 {@code escalation_tier+1} 级需静默 {@code slaMinutes*(tier+1)} 分钟才触发，越往上越慢，
   * 避免单个故障在短时间内连环升级刷屏。按 {@code last_seen_at} 升序优先处理最久未动的。
   */
  List<AlertEventEntity> selectOverdueForEscalation(
      @Param("slaMinutes") int slaMinutes,
      @Param("maxTier") int maxTier,
      @Param("limit") int limit);

  /**
   * 把单条告警的 escalation_tier +1 并打 escalated_at 时间戳。
   *
   * <p>带 {@code expectedTier} 乐观守护:仅当行仍是 OPEN 且 tier 未被其它节点改动时才更新,返回受影响行数,
   * 防止多节点(虽有 ShedLock)或并发 ack 造成重复升级。
   */
  int markEscalated(
      @Param("id") Long id,
      @Param("tenantId") String tenantId,
      @Param("expectedTier") int expectedTier);
}
