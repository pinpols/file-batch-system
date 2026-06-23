package io.github.pinpols.batch.orchestrator.mapper;

import io.github.pinpols.batch.orchestrator.domain.entity.ApprovalCommandEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface ApprovalCommandMapper {

  int insert(ApprovalCommandEntity entity);

  ApprovalCommandEntity selectByTenantAndApprovalNo(
      @Param("tenantId") String tenantId, @Param("approvalNo") String approvalNo);

  int markApproved(
      @Param("tenantId") String tenantId,
      @Param("approvalNo") String approvalNo,
      @Param("approverId") String approverId,
      @Param("approvalReason") String approvalReason,
      @Param("approvedStatus") String approvedStatus,
      @Param("pendingStatus") String pendingStatus);

  int markRejected(
      @Param("tenantId") String tenantId,
      @Param("approvalNo") String approvalNo,
      @Param("approverId") String approverId,
      @Param("approvalReason") String approvalReason,
      @Param("rejectedStatus") String rejectedStatus,
      @Param("pendingStatus") String pendingStatus);

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
