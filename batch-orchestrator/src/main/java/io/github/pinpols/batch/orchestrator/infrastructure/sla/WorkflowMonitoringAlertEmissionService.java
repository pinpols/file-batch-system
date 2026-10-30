package io.github.pinpols.batch.orchestrator.infrastructure.sla;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.application.service.governance.AlertEventService;
import io.github.pinpols.batch.orchestrator.controller.request.AlertEmitRequest;
import io.github.pinpols.batch.orchestrator.domain.query.WorkflowMonitoringQuery;
import io.github.pinpols.batch.orchestrator.domain.view.WorkflowMonitoringAlertCandidate;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowMonitoringMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 单实体、单违约类型串行复核并落告警；任何发送失败均不推进编排状态。 */
@Service
@RequiredArgsConstructor
public class WorkflowMonitoringAlertEmissionService {
  private final WorkflowMonitoringMapper mapper;
  private final AlertEventService alertEventService;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean emitOnce(WorkflowMonitoringAlertCandidate scanned) {
    String tenantId = scanned.tenantId();
    mapper.setTenantContext(tenantId);
    mapper.acquireAlertLock(tenantId, scanned.resourceKey(), scanned.violation().name());
    WorkflowMonitoringQuery query = WorkflowMonitoringQuery.builder()
        .tenantId(tenantId)
        .resourceKey(scanned.resourceKey())
        .violation(scanned.violation())
        .limit(1)
        .build();
    List<WorkflowMonitoringAlertCandidate> current = mapper.selectCandidates(query);
    if (EmptyChecks.isEmpty(current)) {
      return false;
    }
    WorkflowMonitoringAlertCandidate candidate = current.get(0);
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("monitoringResourceKey", candidate.resourceKey());
    detail.put("workflowDefinitionId", candidate.workflowDefinitionId().toString());
    detail.put("workflowCode", candidate.workflowCode());
    detail.put(
        "workflowRunId",
        EmptyChecks.isNull(candidate.workflowRunId())
            ? null
            : candidate.workflowRunId().toString());
    detail.put(
        "occurrenceId",
        EmptyChecks.isNull(candidate.occurrenceId())
            ? null
            : candidate.occurrenceId().toString());
    detail.put("bizDate", candidate.bizDate());
    detail.put("expectedAt", candidate.expectedAt());
    detail.put("startedAt", candidate.startedAt());
    detail.put("finishedAt", candidate.finishedAt());
    detail.put("opportunityClosedAt", candidate.opportunityClosedAt());
    detail.put("monitoringDeadlineAt", candidate.monitoringDeadlineAt());
    detail.put("violationReason", candidate.violation().name());
    String detailJson = JsonUtils.toJson(detail);
    AlertEmitRequest request = AlertEmitRequest.builder()
        .tenantId(tenantId)
        .serviceName("batch-orchestrator")
        .alertType(candidate.violation().alertType())
        .severity(candidate.severity())
        .title(candidate.violation().title(candidate.workflowCode()))
        .detailJson(detailJson)
        .resourceKey(candidate.resourceKey())
        .traceId(candidate.traceId())
        .build();
    alertEventService.emit(request);
    return true;
  }
}
