package com.example.batch.trigger.mapper;

import com.example.batch.common.persistence.entity.TriggerOutboxEventEntity;
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
 *   <li>TriggerOutboxRelay 周期扫描:{@link #selectPending} → {@link #markPublishing} → {@link
 *       #markPublished} / {@link #markFailed}
 * </ul>
 */
public interface TriggerOutboxEventMapper {

  /**
   * 与 trigger_request INSERT 同事务调用,落 PENDING 状态行。返回受影响行数(unique 约束冲突时业务侧应拦截
   * IntegrityConstraintException 而非到这里)。
   */
  int insert(TriggerOutboxEventEntity entity);

  /**
   * 取一批待发事件:{@code publish_status IN ('PENDING','FAILED') AND next_publish_at <= now()}, 按 {@code
   * created_at ASC} 排序,锁定行(SKIP LOCKED)防多实例 trigger 重发。
   */
  List<TriggerOutboxEventEntity> selectPending(
      @Param("now") Instant now,
      @Param("limit") int limit,
      @Param("pendingStatus1") String pendingStatus1,
      @Param("pendingStatus2") String pendingStatus2);

  /** 标记为 PUBLISHING(投递前状态),只对 PENDING/FAILED 行生效。 返回 1 表示成功抢占,0 表示已被其它 relay 实例抢走。 */
  int markPublishing(
      @Param("id") Long id,
      @Param("publishingStatus") String publishingStatus,
      @Param("pendingStatus1") String pendingStatus1,
      @Param("pendingStatus2") String pendingStatus2);

  /** 投递成功,设置 published_at + status。 */
  int markPublished(@Param("id") Long id, @Param("status") String status);

  /** 投递失败,递增 publish_attempt,写 last_error,延迟下次扫描。 */
  int markFailed(
      @Param("id") Long id,
      @Param("status") String status,
      @Param("lastError") String lastError,
      @Param("nextPublishAt") Instant nextPublishAt);

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
