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
      @Param("tenantId") String tenantId,
      @Param("id") Long id,
      @Param("status") String status,
      @Param("publishingStatus") String publishingStatus);

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

  /**
   * Console 运维清理：按租户删除 PUBLISHED 状态、updated_at 早于 cutoff 的事件。 仅供 OutboxOpsApplicationService 在
   * orchestrator 内事务调用，console 通过 HTTP 转发触发。
   */
  int deletePublishedBefore(
      @Param("tenantId") String tenantId, @Param("beforeTime") Instant beforeTime);

  /** Console 运维清理：按租户删除 GIVE_UP 状态、updated_at 早于 cutoff 的事件。语义同上。 */
  int deleteGiveUpBefore(
      @Param("tenantId") String tenantId, @Param("beforeTime") Instant beforeTime);

  /**
   * Console 运维重投递：将指定 id 中、当前状态属于 fromStatuses（如 FAILED/GIVE_UP）的事件 reset 回 NEW， 让 OutboxForwarder
   * 重新拾起。受 ConsoleOutboxOps 应用层调用，console 通过 HTTP 转发触发。
   */
  int resetToNew(
      @Param("tenantId") String tenantId,
      @Param("ids") List<Long> ids,
      @Param("fromStatuses") List<String> fromStatuses);

  /**
   * Outbox archive 调度器：选出指定 status 中、created_at 早于 cutoff 的事件 id（带 limit）。 status 通常是 PUBLISHED 或
   * GIVE_UP；其他状态（NEW/FAILED/PUBLISHING）属于活跃事件不归档。
   */
  List<Long> selectArchivableIds(
      @Param("status") String status, @Param("cutoff") Instant cutoff, @Param("limit") int limit);

  /** 将待瘦身 outbox_event 复制到 archive schema；重复 id 跳过，保证归档任务可重入。 */
  int archiveOutboxEventsByIds(@Param("ids") List<Long> ids);

  /** 将 outbox 相关投递日志复制到 archive schema。 */
  int archiveEventDeliveryLogsByOutboxIds(@Param("outboxIds") List<Long> outboxIds);

  /** 将 outbox 重试记录复制到 archive schema。 */
  int archiveEventOutboxRetriesByOutboxIds(@Param("outboxIds") List<Long> outboxIds);

  /** 按 outbox_event_id 列表删 event_delivery_log（FK 子表）。 */
  int deleteEventDeliveryLogsByOutboxIds(@Param("outboxIds") List<Long> outboxIds);

  /** 按 outbox_event_id 列表删 event_outbox_retry（FK 子表）。 */
  int deleteEventOutboxRetriesByOutboxIds(@Param("outboxIds") List<Long> outboxIds);

  /** 按 id 列表删 outbox_event。 */
  int deleteByIds(@Param("ids") List<Long> ids);
}
