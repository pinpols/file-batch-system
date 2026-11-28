package io.github.pinpols.batch.orchestrator.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import io.github.pinpols.batch.orchestrator.domain.query.WorkflowMonitoringQuery;
import io.github.pinpols.batch.orchestrator.domain.view.WorkflowMonitoringAlertCandidate;

public interface WorkflowMonitoringMapper {
    String setTenantContext(@Param("tenantId") String tenantId);

    String acquireAlertLock(
            @Param("tenantId") String tenantId,
            @Param("resourceKey") String resourceKey,
            @Param("violation") String violation);

    List<WorkflowMonitoringAlertCandidate> selectCandidates(WorkflowMonitoringQuery query);
}
