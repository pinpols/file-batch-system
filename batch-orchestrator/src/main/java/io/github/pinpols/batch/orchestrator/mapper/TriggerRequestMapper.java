package io.github.pinpols.batch.orchestrator.mapper;

import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import io.github.pinpols.batch.common.enums.TriggerRequestStatus;
import io.github.pinpols.batch.common.persistence.entity.TriggerRequestEntity;
import io.github.pinpols.batch.orchestrator.domain.context.TriggerLaunchPersistenceContext;
import io.github.pinpols.batch.orchestrator.domain.query.TriggerRequestLaunchReconcileRow;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface TriggerRequestMapper {

  int insert(TriggerRequestEntity entity);

  /** replay 恢复路径使用：稳定 requestId 并发重试时只允许首个请求落库。 */
  int insertIfAbsent(TriggerRequestEntity entity);

  TriggerRequestEntity selectById(@Param("tenantId") String tenantId, @Param("id") Long id);

  TriggerRequestEntity selectByTenantAndRequestId(
      @Param("tenantId") String tenantId, @Param("requestId") String requestId);

  /** 一次数据库往返加载 trigger request 及同 dedup key 的最新实例轻量投影。 */
  TriggerLaunchPersistenceContext selectLaunchPersistenceContext(
      @Param("tenantId") String tenantId, @Param("requestId") String requestId);

  TriggerRequestEntity selectByTenantAndDedupKey(
      @Param("tenantId") String tenantId, @Param("dedupKey") String dedupKey);

  default int updateAcceptance(
      String tenantId, String requestId, String requestStatus, Long relatedJobInstanceId) {
    List<String> protectedStatuses = List.of(
        TriggerRequestStatus.LAUNCHED.code(),
        TriggerRequestStatus.REJECTED.code(),
        TriggerRequestStatus.DUPLICATE.code());
    return updateAcceptanceGuarded(
        tenantId,
        requestId,
        requestStatus,
        relatedJobInstanceId,
        protectedStatuses,
        TriggerRequestStatus.LAUNCHED.code());
  }

  int updateAcceptanceGuarded(
      @Param("tenantId") String tenantId,
      @Param("requestId") String requestId,
      @Param("requestStatus") String requestStatus,
      @Param("relatedJobInstanceId") Long relatedJobInstanceId,
      @Param("protectedStatuses") List<String> protectedStatuses,
      @Param("launchedStatus") String launchedStatus);

  /**
   * CAS 更新 trigger_type：仅当当前类型等于 {@code expectedTriggerType} 时改为 {@code triggerType}， 返回受影响行数。用于
   * late-arrival 路由等需要"先 DB 后内存"原子改写的场景，避免内存 / DB 不一致。
   */
  int updateTriggerType(
      @Param("tenantId") String tenantId,
      @Param("requestId") String requestId,
      @Param("triggerType") String triggerType,
      @Param("expectedTriggerType") String expectedTriggerType);

  /**
   * ADR-010 reconciler 用：找出 卡在 ACCEPTED 且 created_at 早于 {@code olderThan}、 但已在 job_instance 中以同
   * dedup_key 写入数据库的 trigger_request。 返回 (tenantId, requestId, jobInstanceId) 供 reconciler 调 {@link
   * #reconcileLaunched} 把状态推到 LAUNCHED。
   *
   * <p>JOIN 路径:trigger_request.dedup_key ↔ job_instance.dedup_key(同租户)。 trigger 与 launch 双方都把同一个
   * dedup_key 写入各自表,因此可作为可靠桥梁; 不依赖 trigger_request_id FK(launch 路径未必回写)。
   */
  default List<TriggerRequestLaunchReconcileRow> selectStaleAcceptedWithJobInstance(
      Instant olderThan, int limit) {
    String requestStatus = TriggerRequestStatus.ACCEPTED.code();
    String excludedInstanceStatus = JobInstanceStatus.CREATED.code();
    return selectStaleByRequestAndExcludedInstanceStatus(
        olderThan, limit, requestStatus, excludedInstanceStatus);
  }

  List<TriggerRequestLaunchReconcileRow> selectStaleByRequestAndExcludedInstanceStatus(
      @Param("olderThan") Instant olderThan,
      @Param("limit") int limit,
      @Param("requestStatus") String requestStatus,
      @Param("excludedInstanceStatus") String excludedInstanceStatus);

  /**
   * CAS 把 trigger_request 从 ACCEPTED + relatedJobInstanceId IS NULL 推到 LAUNCHED + jobInstanceId。 返回
   * 0 表示并发已被其它路径(consumer writeBack / 运维操作)改走,本次跳过即可。
   */
  default int reconcileLaunched(String tenantId, String requestId, Long jobInstanceId) {
    List<String> expectedStatuses = List.of(TriggerRequestStatus.ACCEPTED.code());
    return reconcileStatus(
        tenantId,
        requestId,
        jobInstanceId,
        TriggerRequestStatus.LAUNCHED.code(),
        expectedStatuses,
        true);
  }

  /**
   * CREATED 恢复器专用 CAS：只修复当前请求亲自创建、但 T2 尚未产生任何子项的实例。兼容旧版本把同一
   * request 重投误标为 DUPLICATE 的历史记录；普通业务去重不得调用本方法。
   */
  default int reconcileRecoveredCreatedLaunch(
      String tenantId, String requestId, Long jobInstanceId) {
    List<String> expectedStatuses =
        List.of(TriggerRequestStatus.ACCEPTED.code(), TriggerRequestStatus.DUPLICATE.code());
    return reconcileStatus(
        tenantId,
        requestId,
        jobInstanceId,
        TriggerRequestStatus.LAUNCHED.code(),
        expectedStatuses,
        false);
  }

  int reconcileStatus(
      @Param("tenantId") String tenantId,
      @Param("requestId") String requestId,
      @Param("jobInstanceId") Long jobInstanceId,
      @Param("targetStatus") String targetStatus,
      @Param("expectedStatuses") List<String> expectedStatuses,
      @Param("requireUnboundInstance") boolean requireUnboundInstance);
}
