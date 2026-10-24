package io.github.pinpols.batch.orchestrator.mapper;

import io.github.pinpols.batch.orchestrator.domain.enums.WorkflowLaunchWaitStatus;
import io.github.pinpols.batch.orchestrator.domain.param.WorkflowLaunchWaitInsertParam;
import io.github.pinpols.batch.orchestrator.domain.query.WorkflowLaunchWaitClaim;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface WorkflowLaunchWaitMapper {
  String setTenantContext(@Param("tenantId") String tenantId);

  int defer(WorkflowLaunchWaitInsertParam request);

  List<WorkflowLaunchWaitClaim> claimDue(String tenantId, int limit, Instant nextAttemptAt);

  default int markResumed(String tenantId, String requestId) {
    return updateStatus(
        tenantId,
        requestId,
        WorkflowLaunchWaitStatus.WAITING.code(),
        WorkflowLaunchWaitStatus.RESUMED.code());
  }

  default int markRejected(String tenantId, String requestId) {
    return updateStatus(
        tenantId,
        requestId,
        WorkflowLaunchWaitStatus.WAITING.code(),
        WorkflowLaunchWaitStatus.REJECTED.code());
  }

  int updateStatus(String tenantId, String requestId, String expectedStatus, String targetStatus);
}
