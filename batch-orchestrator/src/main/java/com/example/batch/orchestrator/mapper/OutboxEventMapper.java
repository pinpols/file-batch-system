package com.example.batch.orchestrator.mapper;

import com.example.batch.orchestrator.domain.entity.OutboxEventEntity;
import com.example.batch.orchestrator.domain.query.OutboxEventQuery;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface OutboxEventMapper {

  int insert(OutboxEventEntity entity);

  List<OutboxEventEntity> selectPending(OutboxEventQuery query);

  int markPublishing(
      @Param("tenantId") String tenantId,
      @Param("id") Long id,
      @Param("publishingStatus") String publishingStatus,
      @Param("pendingStatus1") String pendingStatus1,
      @Param("pendingStatus2") String pendingStatus2);

  int markPublished(
      @Param("tenantId") String tenantId, @Param("id") Long id, @Param("status") String status);

  int markFailed(
      @Param("tenantId") String tenantId,
      @Param("id") Long id,
      @Param("status") String status,
      @Param("nextPublishAt") Instant nextPublishAt);

  int markGiveUp(
      @Param("tenantId") String tenantId, @Param("id") Long id, @Param("status") String status);

  /**
   * 将滞留在 PUBLISHING 状态超过指定时长的事件重置为 FAILED，防止事件永久卡死。
   *
   * @return 被重置的记录数
   */
  int resetStalePublishing(
      @Param("publishingStatus") String publishingStatus,
      @Param("failedStatus") String failedStatus,
      @Param("timeoutSeconds") long timeoutSeconds);

  /** 积压量指标：统计指定状态的 outbox 事件总数（跨租户）。供 Micrometer gauge 使用。 */
  long countByStatuses(@Param("statuses") List<String> statuses);

  /** 异常指标：统计卡在 PUBLISHING 且超期的事件数。正常情况下 resetStalePublishing 会清 0。 */
  long countStalePublishing(
      @Param("publishingStatus") String publishingStatus,
      @Param("timeoutSeconds") long timeoutSeconds);
}
