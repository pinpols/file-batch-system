package io.github.pinpols.batch.console.domain.workflow.application;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.persistence.entity.WorkflowMonitoringPolicyEntity;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Guard;
import io.github.pinpols.batch.console.domain.workflow.application.contract.request.WorkflowMonitoringPolicyRequest;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowDefinitionEntity;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowDefinitionMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowMonitoringPolicyMapper;
import io.github.pinpols.batch.console.shared.query.TenantIdResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 根级监控配置入口；按现有 Workflow 角色和租户边界读写独立策略。 */
@Service
@RequiredArgsConstructor
public class WorkflowMonitoringPolicyService {
  private final TenantIdResolver tenantGuard;
  private final WorkflowDefinitionMapper definitionMapper;
  private final WorkflowMonitoringPolicyMapper policyMapper;

  @Transactional(readOnly = true)
  public WorkflowMonitoringPolicyEntity get(Long definitionId, String tenantId) {
    String tenant = tenantGuard.resolveTenant(tenantId);
    requireDefinition(tenant, definitionId);
    policyMapper.setTenantContext(tenant);
    WorkflowMonitoringPolicyEntity policy = policyMapper.selectByDefinition(tenant, definitionId);
    if (EmptyChecks.isNull(policy)) {
      policy = new WorkflowMonitoringPolicyEntity();
      policy.setTenantId(tenant);
      policy.setWorkflowDefinitionId(definitionId);
    }
    return policy;
  }

  @Transactional
  public WorkflowMonitoringPolicyEntity save(
      Long definitionId, WorkflowMonitoringPolicyRequest request) {
    String tenant = tenantGuard.resolveTenant(request.getTenantId());
    requireDefinition(tenant, definitionId);
    policyMapper.setTenantContext(tenant);
    WorkflowMonitoringPolicyEntity policy = new WorkflowMonitoringPolicyEntity();
    policy.setTenantId(tenant);
    policy.setWorkflowDefinitionId(definitionId);
    policy.setSoftRuntimeSeconds(request.getSoftRuntimeSeconds());
    policy.setSoftRuntimeSeverity(request.getSoftRuntimeSeverity());
    policy.setStartGraceSeconds(request.getStartGraceSeconds());
    policy.setStartGraceSeverity(request.getStartGraceSeverity());
    policy.setCompletionDeadlineLocalTime(request.getCompletionDeadlineLocalTime());
    policy.setCompletionDeadlineDayOffset(request.getCompletionDeadlineDayOffset());
    policy.setCompletionDeadlineSeverity(request.getCompletionDeadlineSeverity());
    policyMapper.upsert(policy);
    WorkflowMonitoringPolicyEntity saved = policyMapper.selectByDefinition(tenant, definitionId);
    if (EmptyChecks.isNull(saved)) {
      throw BizException.of(
          ResultCode.SYSTEM_ERROR, "error.workflow.monitoring_policy_not_persisted");
    }
    return saved;
  }

  private void requireDefinition(String tenantId, Long definitionId) {
    WorkflowDefinitionEntity definition = definitionMapper.selectById(tenantId, definitionId);
    Guard.requireFound(definition, "Workflow definition not found");
  }
}
