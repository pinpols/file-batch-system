package io.github.pinpols.batch.trigger.mapper;

import io.github.pinpols.batch.common.enums.OutboxPublishStatus;
import io.github.pinpols.batch.common.persistence.entity.TriggerOutboxEventEntity;
import io.github.pinpols.batch.trigger.domain.param.TriggerOutboxEventMarkFailedParam;
import io.github.pinpols.batch.trigger.domain.param.TriggerOutboxEventMarkPublishingBatchParam;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * ADR-010: {@code batch.trigger_outbox_event} 持久化接口。
 *
 * <p>调用方:
 *
 * <ul>
 *   <li>trigger 业务路径(同 trigger_request 事务):{@link #insert}
 *   <li>TriggerOutboxRelay 周期扫描:{@link #selectPending} → {@link #markPublishingBatch} → {@link
 *       #markPublishedBatch} / {@link #markFailed}
 * </ul>
 */
public interface TriggerOutboxEventMapper {

  /**
   * 与 trigger_request INSERT 同事务调用,落 NEW 状态行。返回受影响行数(unique 约束冲突时业务侧应拦截
   * IntegrityConstraintException 而非到这里)。
   */
  default int insert(TriggerOutboxEventEntity entity) {
    return insertWithDefaultStatus(entity, OutboxPublishStatus.NEW.code());
  }

  int insertWithDefaultStatus(
      @Param("entity") TriggerOutboxEventEntity entity,
      @Param("defaultStatus") String defaultStatus);

  /**
   * 取一批待发候选事件:{@code publish_status IN ('NEW','FAILED') AND next_publish_at <= now()}；
   * {@code FOR UPDATE SKIP LOCKED} 只作用于本次查询事务，实际投递所有权由后续批量 CAS 领取确立。
   */
  List<TriggerOutboxEventEntity> selectPending(
      @Param("now") Instant now,
      @Param("limit") int limit,
      @Param("pendingStatus1") String pendingStatus1,
      @Param("pendingStatus2") String pendingStatus2);

  /**
   * 单批 CAS 抢占。claimToken 标识本轮领取；返回实际从 NEW/FAILED 推进到 PUBLISHING 的 id。
   */
  List<Long> markPublishingBatch(TriggerOutboxEventMarkPublishingBatchParam parameters);

  /** 批量写入成功状态；状态和领取 token 均须匹配，旧投递者不能覆盖新领取。 */
  int markPublishedBatch(
      @Param("ids") List<Long> ids,
      @Param("status") String status,
      @Param("publishingStatus") String publishingStatus,
      @Param("claimToken") String claimToken);

  /**
   * 投递失败时递增次数、写入原因和退避时刻；状态与领取 token 均须匹配。
   */
  int markFailed(TriggerOutboxEventMarkFailedParam parameters);

  int resetStalePublishing(
      @Param("publishingStatus") String publishingStatus,
      @Param("failedStatus") String failedStatus,
      @Param("lastError") String lastError,
      @Param("timeoutSeconds") long timeoutSeconds);

  long countByStatuses(@Param("statuses") List<String> statuses);

  long countStalePublishing(
      @Param("publishingStatus") String publishingStatus,
      @Param("timeoutSeconds") long timeoutSeconds);

  /** 仅供测试 / 运维查询(按 tenant 列出)。 */
  List<TriggerOutboxEventEntity> selectByTenantAndRequest(
      @Param("tenantId") String tenantId, @Param("requestId") String requestId);
}
