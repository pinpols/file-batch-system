package io.github.pinpols.batch.console.domain.workflow.mapper;

import io.github.pinpols.batch.common.persistence.entity.WorkflowMonitoringPolicyEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface WorkflowMonitoringPolicyMapper {
  String setTenantContext(@Param("tenantId") String tenantId);

  WorkflowMonitoringPolicyEntity selectByDefinition(
      @Param("tenantId") String tenantId, @Param("workflowDefinitionId") Long workflowDefinitionId);

  List<WorkflowMonitoringPolicyEntity> selectByTenant(@Param("tenantId") String tenantId);

  void upsert(WorkflowMonitoringPolicyEntity policy);
}
