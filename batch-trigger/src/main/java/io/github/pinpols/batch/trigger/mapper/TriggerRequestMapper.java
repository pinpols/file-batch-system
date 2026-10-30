package io.github.pinpols.batch.trigger.mapper;

import io.github.pinpols.batch.common.persistence.entity.TriggerRequestEntity;
import io.github.pinpols.batch.trigger.domain.TriggerLaunchStatus;
import io.github.pinpols.batch.trigger.domain.query.TriggerRequestQuery;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface TriggerRequestMapper {

  long acquireIdempotencyFence(
      @Param("tenantId") String tenantId, @Param("idempotencyKey") String idempotencyKey);

  long acquireExternalEventIdentityFence(
      @Param("tenantId") String tenantId,
      @Param("eventSource") String eventSource,
      @Param("eventId") String eventId);

  /** 与启停更新共用 Workflow 行锁，须在请求接纳或首次等待的事务内调用。 */
  Boolean lockScheduledWorkflowAdmission(
      @Param("tenantId") String tenantId,
      @Param("workflowDefinitionId") Long workflowDefinitionId,
      @Param("scheduledFireTime") Instant scheduledFireTime,
      @Param("workflowVersion") Integer workflowVersion);

  boolean isWorkflowVersionCurrent(
      @Param("tenantId") String tenantId,
      @Param("workflowDefinitionId") Long workflowDefinitionId,
      @Param("workflowVersion") Integer workflowVersion);

  List<TriggerRequestEntity> selectByQuery(TriggerRequestQuery query);

  TriggerRequestEntity selectById(@Param("id") Long id);

  TriggerRequestEntity selectByTenantAndRequestId(
      @Param("tenantId") String tenantId, @Param("requestId") String requestId);

  TriggerRequestEntity selectByTenantAndDedupKey(
      @Param("tenantId") String tenantId, @Param("dedupKey") String dedupKey);

  TriggerRequestEntity selectByExternalEventIdentity(
      @Param("tenantId") String tenantId,
      @Param("workflowDefinitionId") Long workflowDefinitionId,
      @Param("externalEventSource") String externalEventSource,
      @Param("externalEventId") String externalEventId);

  List<Long> selectWorkflowTargetsByExternalEventIdentity(
      @Param("tenantId") String tenantId,
      @Param("externalEventSource") String externalEventSource,
      @Param("externalEventId") String externalEventId);

  int insertIdempotencyAlias(
      @Param("tenantId") String tenantId,
      @Param("idempotencyKey") String idempotencyKey,
      @Param("requestId") String requestId);

  String selectIdempotencyAliasRequestId(
      @Param("tenantId") String tenantId, @Param("idempotencyKey") String idempotencyKey);

  TriggerLaunchStatus selectLaunchStatus(
      @Param("tenantId") String tenantId,
      @Param("dedupKey") String dedupKey,
      @Param("externalEventSource") String externalEventSource);

  boolean existsEnabledWorkflowDefinition(
      @Param("tenantId") String tenantId, @Param("workflowDefinitionId") Long workflowDefinitionId);

  List<Long> selectEnabledExternalEventWorkflowTargets(
      @Param("tenantId") String tenantId, @Param("eventCode") String eventCode);

  long acquireBatchDaySettlementFence(
      @Param("tenantId") String tenantId,
      @Param("workflowDefinitionId") Long workflowDefinitionId,
      @Param("jobCode") String jobCode,
      @Param("bizDate") LocalDate bizDate);

  int insert(TriggerRequestEntity entity);

  int updateRequestStatus(
      @Param("tenantId") String tenantId,
      @Param("requestId") String requestId,
      @Param("requestStatus") String requestStatus);

  /**
   * CAS 式条件更新：仅当当前状态与 {@code
   * expectedStatus}.
   */
  int updateRequestStatusConditional(
      @Param("tenantId") String tenantId,
      @Param("requestId") String requestId,
      @Param("requestStatus") String requestStatus,
      @Param("expectedStatus") String expectedStatus);
}
