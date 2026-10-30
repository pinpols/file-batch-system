package io.github.pinpols.batch.orchestrator.mapper;

import io.github.pinpols.batch.orchestrator.domain.entity.ApprovalCommandEntity;
import io.github.pinpols.batch.orchestrator.domain.param.ApproveCommandDecisionParam;
import io.github.pinpols.batch.orchestrator.domain.param.RejectCommandDecisionParam;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface ApprovalCommandMapper {

  int insert(ApprovalCommandEntity entity);

  ApprovalCommandEntity selectByTenantAndSourceIdempotencyKey(
      @Param("tenantId") String tenantId,
      @Param("sourceIdempotencyKey") String sourceIdempotencyKey);

  ApprovalCommandEntity selectByTenantAndApprovalNo(
      @Param("tenantId") String tenantId, @Param("approvalNo") String approvalNo);

  int markApproved(ApproveCommandDecisionParam param);

  int markRejected(RejectCommandDecisionParam param);

  int markExecuted(
      @Param("tenantId") String tenantId,
      @Param("approvalNo") String approvalNo,
      @Param("executedStatus") String executedStatus,
      @Param("approvedStatus") String approvedStatus);

  List<ApprovalCommandEntity> selectPending(
      @Param("tenantId") String tenantId,
      @Param("limit") int limit,
      @Param("pendingStatus") String pendingStatus);
}
