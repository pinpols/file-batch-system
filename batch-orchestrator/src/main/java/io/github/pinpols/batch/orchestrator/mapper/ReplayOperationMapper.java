package io.github.pinpols.batch.orchestrator.mapper;

import io.github.pinpols.batch.common.enums.ReplayOperationStatus;
import io.github.pinpols.batch.orchestrator.domain.entity.ReplayOperationEntity;
import io.github.pinpols.batch.orchestrator.domain.param.ReplayOperationStartParam;
import io.github.pinpols.batch.orchestrator.domain.param.ReplayOperationWorkflowOutcomeParam;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 租户范围内 ADR-049 重放审计根的 MyBatis 操作。 */
public interface ReplayOperationMapper {

  String setTenantContext(@Param("tenantId") String tenantId);

  int insert(ReplayOperationEntity operation);

  ReplayOperationEntity selectById(@Param("tenantId") String tenantId, @Param("id") Long id);

  ReplayOperationEntity selectByIdentity(
      @Param("tenantId") String tenantId, @Param("operationKey") String operationKey);

  ReplayOperationEntity selectByTargetRequest(
      @Param("tenantId") String tenantId, @Param("requestId") String requestId);

  List<ReplayOperationEntity> selectRecentByTenant(
      @Param("tenantId") String tenantId, @Param("limit") int limit);

  ReplayOperationEntity selectForUpdate(@Param("tenantId") String tenantId, @Param("id") Long id);

  List<ReplayOperationEntity> selectByStatus(
      @Param("status") String status, @Param("limit") int limit);

  List<Long> selectChildSessionIds(
      @Param("tenantId") String tenantId, @Param("operationId") Long operationId);

  int refreshFromChildSessions(
      @Param("tenantId") String tenantId,
      @Param("operationId") Long operationId,
      @Param("now") Instant now);

  /** 修改子会话前锁定聚合根，同时避免阻塞其外键 KEY SHARE 锁。 */
  Long lockForProgressRefresh(
      @Param("tenantId") String tenantId, @Param("operationId") Long operationId);

  int recordApproval(
      @Param("tenantId") String tenantId,
      @Param("operationId") Long operationId,
      @Param("approver") String approver,
      @Param("now") Instant now);

  default int rejectPendingApproval(String tenantId, Long operationId, Instant now) {
    String targetStatus = ReplayOperationStatus.REJECTED.code();
    return rejectPendingApprovalWithStatus(tenantId, operationId, now, targetStatus);
  }

  int rejectPendingApprovalWithStatus(
      @Param("tenantId") String tenantId,
      @Param("operationId") Long operationId,
      @Param("now") Instant now,
      @Param("targetStatus") String targetStatus);

  int startApprovedWorkflowNodeReplay(@Param("param") ReplayOperationStartParam param);

  int startApprovedWorkflowRunReplay(@Param("param") ReplayOperationStartParam param);

  int attachTargetWorkflowRun(
      @Param("tenantId") String tenantId,
      @Param("requestId") String requestId,
      @Param("workflowRunId") Long workflowRunId,
      @Param("now") Instant now);

  int updateWorkflowTargetOutcome(@Param("param") ReplayOperationWorkflowOutcomeParam param);
}
