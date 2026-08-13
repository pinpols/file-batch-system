package io.github.pinpols.batch.console.domain.workflow.web;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.console.application.ops.ConsoleOrchestratorPort;
import io.github.pinpols.batch.console.application.ops.response.ConsoleWorkflowRunActionResponse;
import io.github.pinpols.batch.console.application.ops.response.ConsoleWorkflowRunSkipNodeResponse;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.shared.audit.AuditAction;
import io.github.pinpols.batch.console.support.web.Idempotent;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequestMapping("/api/console/workflow-runs")
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
@RequiredArgsConstructor
@Idempotent
public class ConsoleWorkflowRunController {

  private final ConsoleOrchestratorPort orchestratorProxyService;
  private final ConsoleResponseFactory responseFactory;

  @PostMapping("/{id}/cancel")
  @AuditAction(
      action = "workflowRun.cancel",
      aggregateType = "workflow_run",
      aggregateId = "#id",
      targetTenantParam = "#tenantId")
  public CommonResponse<ConsoleWorkflowRunActionResponse> cancel(
      @PathVariable Long id, @RequestParam("tenantId") String tenantId) {
    return responseFactory.success(
        orchestratorProxyService.workflowRunAction(id, tenantId, "cancel"));
  }

  @PostMapping("/{id}/terminate")
  @AuditAction(
      action = "workflowRun.terminate",
      aggregateType = "workflow_run",
      aggregateId = "#id",
      targetTenantParam = "#tenantId")
  public CommonResponse<ConsoleWorkflowRunActionResponse> terminate(
      @PathVariable Long id, @RequestParam("tenantId") String tenantId) {
    return responseFactory.success(
        orchestratorProxyService.workflowRunAction(id, tenantId, "terminate"));
  }

  @PostMapping("/{id}/pause")
  @AuditAction(
      action = "workflowRun.pause",
      aggregateType = "workflow_run",
      aggregateId = "#id",
      targetTenantParam = "#tenantId")
  public CommonResponse<ConsoleWorkflowRunActionResponse> pause(
      @PathVariable Long id, @RequestParam("tenantId") String tenantId) {
    return responseFactory.success(
        orchestratorProxyService.workflowRunAction(id, tenantId, "pause"));
  }

  @PostMapping("/{id}/resume")
  @AuditAction(
      action = "workflowRun.resume",
      aggregateType = "workflow_run",
      aggregateId = "#id",
      targetTenantParam = "#tenantId")
  public CommonResponse<ConsoleWorkflowRunActionResponse> resume(
      @PathVariable Long id, @RequestParam("tenantId") String tenantId) {
    return responseFactory.success(
        orchestratorProxyService.workflowRunAction(id, tenantId, "resume"));
  }

  @PostMapping("/{id}/skip-node")
  @AuditAction(
      action = "workflowRun.skipNode",
      aggregateType = "workflow_run",
      aggregateId = "#id",
      targetTenantParam = "#tenantId")
  public CommonResponse<ConsoleWorkflowRunSkipNodeResponse> skipNode(
      @PathVariable Long id,
      @RequestParam("tenantId") String tenantId,
      @RequestParam("nodeCode") String nodeCode) {
    return responseFactory.success(
        orchestratorProxyService.workflowRunSkipNode(id, tenantId, nodeCode));
  }
}
