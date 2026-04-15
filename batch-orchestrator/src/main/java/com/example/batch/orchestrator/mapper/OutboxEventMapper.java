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
}
