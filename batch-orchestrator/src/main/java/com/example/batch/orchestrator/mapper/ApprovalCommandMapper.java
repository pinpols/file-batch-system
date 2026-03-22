package com.example.batch.orchestrator.mapper;

import com.example.batch.orchestrator.domain.entity.ApprovalCommandEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface ApprovalCommandMapper {

    int insert(ApprovalCommandEntity entity);

    ApprovalCommandEntity selectByTenantAndApprovalNo(@Param("tenantId") String tenantId,
                                                      @Param("approvalNo") String approvalNo);

    int markApproved(@Param("tenantId") String tenantId,
                     @Param("approvalNo") String approvalNo,
                     @Param("approverId") String approverId,
                     @Param("approvalReason") String approvalReason);

    int markRejected(@Param("tenantId") String tenantId,
                     @Param("approvalNo") String approvalNo,
                     @Param("approverId") String approverId,
                     @Param("approvalReason") String approvalReason);

    int markExecuted(@Param("tenantId") String tenantId,
                     @Param("approvalNo") String approvalNo);

    List<ApprovalCommandEntity> selectPending(@Param("tenantId") String tenantId,
                                              @Param("limit") int limit);
}
