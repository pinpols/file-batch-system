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
   *
   * <p>Citus 路由:{@code tenant_id = #{tenantId}} 等值条件使 FOR UPDATE SKIP LOCKED 锁限定在单分片内合法化。
   */
  List<TriggerOutboxEventEntity> selectPending(
      @Param("tenantId") String tenantId,
      @Param("now") Instant now,
      @Param("limit") int limit,
      @Param("pendingStatus1") String pendingStatus1,
      @Param("pendingStatus2") String pendingStatus2);

  /**
   * 取当前有待发事件(NEW/FAILED 且到期)的去重 tenant_id 列表。
   *
   * <p>relay 用它补全租户路由清单——不能只依赖 {@code batch.tenant ACTIVE}:租户被停用(status≠ACTIVE)
   * 后,其已入队但未投递的事件不能永久卡死;此处按"实际有待发行的租户"补齐。Citus:这是跨分片 distinct 只读 聚合(允许),后续每租户仍走 {@code
   * selectPending} 的单分片 FOR UPDATE。
   */
  List<String> selectPendingTenantIds(
      @Param("now") Instant now,
      @Param("pendingStatus1") String pendingStatus1,
      @Param("pendingStatus2") String pendingStatus2);

  /** 标记为 PUBLISHING(投递前状态),只对 PENDING/FAILED 行生效。 返回 1 表示成功抢占,0 表示已被其它 relay 实例抢走。 */
  int markPublishing(
      @Param("tenantId") String tenantId,
      @Param("id") Long id,
      @Param("publishingStatus") String publishingStatus,
      @Param("pendingStatus1") String pendingStatus1,
      @Param("pendingStatus2") String pendingStatus2);

  /** 投递成功,设置 published_at + status。 */
  int markPublished(
      @Param("tenantId") String tenantId, @Param("id") Long id, @Param("status") String status);

  /** 投递失败,递增 publish_attempt,写 last_error,延迟下次扫描。 */
  int markFailed(
      @Param("tenantId") String tenantId,
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
