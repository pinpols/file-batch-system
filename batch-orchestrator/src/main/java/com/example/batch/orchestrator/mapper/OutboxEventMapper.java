package com.example.batch.orchestrator.mapper;

import com.example.batch.orchestrator.domain.entity.OutboxEventEntity;
import com.example.batch.orchestrator.domain.query.OutboxEventQuery;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface OutboxEventMapper {

  int insert(OutboxEventEntity entity);

  List<OutboxEventEntity> selectPending(OutboxEventQuery query);

  /**
   * Citus 租户路由模式:返回当前有"待发且已到投递时间"事件的 distinct tenant_id(可选按 shard 过滤)。 relay 用它发现要处理的租户,再逐租户
   * selectPending(tenant_id 字面量,router 查询无 fan-out)。 这条 distinct 查询本身仍 fan-out,但只取 tenant_id(无行负载,走
   * publish_status 索引), 远轻于原"扫+锁+处理全部待发行"的重 fan-out。直接以 outbox_event 为真相源,不漏停用租户的残留事件。
   */
  List<String> selectTenantIdsWithPendingOutbox(OutboxEventQuery query);

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
   * 异常指标:近窗口内出现重复 {@code (tenant_id, event_key)} 的组数(分区下 NOT EXISTS 幂等被旁路/竞态漏过的抓手)。 正常恒为 0;> 0 即说明有
   * outbox 事件被重复写入。见 docs/design/partition-idempotency-decision.md。
   */
  long countDuplicateEventKeys(@Param("sinceSeconds") long sinceSeconds);

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

  /** P1-3 dry-run: 统计满足 cleanup 条件的 PUBLISHED 行数(不删)。 */
  int countPublishedBefore(
      @Param("tenantId") String tenantId, @Param("beforeTime") Instant beforeTime);

  /** P1-3 dry-run: 统计满足 cleanup 条件的 GIVE_UP 行数(不删)。 */
  int countGiveUpBefore(
      @Param("tenantId") String tenantId, @Param("beforeTime") Instant beforeTime);

  /** P1-3 dry-run: 统计 republish 候选(指定 ids ∩ status ∈ fromStatuses)的行数(不改)。 */
  int countResettable(
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
