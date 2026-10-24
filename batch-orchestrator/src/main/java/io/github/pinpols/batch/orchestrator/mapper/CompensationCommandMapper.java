package io.github.pinpols.batch.orchestrator.mapper;

import io.github.pinpols.batch.orchestrator.domain.param.StaleCompensationFailureParam;
import io.github.pinpols.batch.orchestrator.domain.entity.CompensationCommandEntity;
import io.github.pinpols.batch.orchestrator.domain.param.UpdateCompensationStatusParam;
import java.time.Instant;
import org.apache.ibatis.annotations.Param;

public interface CompensationCommandMapper {

  int insert(CompensationCommandEntity entity);

  CompensationCommandEntity selectById(@Param("tenantId") String tenantId, @Param("id") Long id);

  CompensationCommandEntity selectByCommandNo(
      @Param("tenantId") String tenantId, @Param("commandNo") String commandNo);

  CompensationCommandEntity selectByTenantAndApprovalId(
      @Param("tenantId") String tenantId, @Param("approvalId") String approvalId);

  int updateStatus(UpdateCompensationStatusParam param);

  int countRunningByTarget(
      @Param("tenantId") String tenantId,
      @Param("compensationType") String compensationType,
      @Param("targetId") Long targetId,
      @Param("runningStatus") String runningStatus);

  int markStaleRunningFailed(StaleCompensationFailureParam param);
}
