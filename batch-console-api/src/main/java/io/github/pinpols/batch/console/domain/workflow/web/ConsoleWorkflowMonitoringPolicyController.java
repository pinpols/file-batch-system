package io.github.pinpols.batch.console.domain.workflow.web;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.common.persistence.entity.WorkflowMonitoringPolicyEntity;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleSecurityExpressions;
import io.github.pinpols.batch.console.domain.workflow.application.WorkflowMonitoringPolicyService;
import io.github.pinpols.batch.console.domain.workflow.application.contract.request.WorkflowMonitoringPolicyRequest;
import io.github.pinpols.batch.console.domain.workflow.application.contract.response.WorkflowMonitoringPolicyResponse;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.Idempotent;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Workflow 三类软时限的配置契约，不参与图编辑或运行状态推进。 */
@RestController
@RequestMapping("/api/console/workflow-definitions/{id}/monitoring-policy")
@RequiredArgsConstructor
@Idempotent
public class ConsoleWorkflowMonitoringPolicyController {
  private final WorkflowMonitoringPolicyService policyService;
  private final ConsoleResponseFactory responseFactory;

  @GetMapping
  @PreAuthorize(ConsoleSecurityExpressions.ANY_CONSOLE_ROLE)
  public CommonResponse<WorkflowMonitoringPolicyResponse> get(
      @PathVariable Long id, @RequestParam String tenantId) {
    WorkflowMonitoringPolicyEntity policy = policyService.get(id, tenantId);
    WorkflowMonitoringPolicyResponse response = WorkflowMonitoringPolicyResponse.from(policy);
    return responseFactory.success(response);
  }

  @PutMapping
  @PreAuthorize(ConsoleSecurityExpressions.ADMIN_OR_TENANT_ADMIN)
  public CommonResponse<WorkflowMonitoringPolicyResponse> save(
      @PathVariable Long id, @Valid @RequestBody WorkflowMonitoringPolicyRequest request) {
    WorkflowMonitoringPolicyEntity policy = policyService.save(id, request);
    WorkflowMonitoringPolicyResponse response = WorkflowMonitoringPolicyResponse.from(policy);
    return responseFactory.success(response);
  }
}
