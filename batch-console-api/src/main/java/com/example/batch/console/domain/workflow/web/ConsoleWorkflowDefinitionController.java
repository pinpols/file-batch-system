package com.example.batch.console.domain.workflow.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.console.domain.job.web.request.EnabledPatchRequest;
import com.example.batch.console.domain.rbac.support.ConsolePrincipal;
import com.example.batch.console.domain.workflow.application.ConsoleWorkflowDefinitionApplicationService;
import com.example.batch.console.domain.workflow.application.ConsoleWorkflowDefinitionApplicationService.DagValidationResult;
import com.example.batch.console.domain.workflow.application.WorkflowDesignLockService;
import com.example.batch.console.domain.workflow.application.WorkflowDesignLockService.LockHolder;
import com.example.batch.console.domain.workflow.infrastructure.WorkflowMermaidRenderer;
import com.example.batch.console.domain.workflow.web.request.WorkflowDefinitionFullUpdateRequest;
import com.example.batch.console.domain.workflow.web.request.WorkflowDefinitionSaveRequest;
import com.example.batch.console.domain.workflow.web.response.WorkflowDefinitionDetailResponse;
import com.example.batch.console.domain.workflow.web.response.WorkflowDesignLockResponse;
import com.example.batch.console.domain.workflow.web.response.WorkflowMermaidResponse;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.web.Idempotent;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/console/workflow-definitions")
@RequiredArgsConstructor
@Idempotent
public class ConsoleWorkflowDefinitionController {

  private final ConsoleWorkflowDefinitionApplicationService workflowDefinitionApplicationService;
  private final ConsoleResponseFactory responseFactory;
  private final WorkflowDesignLockService designLockService;

  @GetMapping("/{id}")
  @PreAuthorize(
      "hasAnyAuthority('ROLE_ADMIN', 'ROLE_AUDITOR', 'ROLE_TENANT_ADMIN'," + " 'ROLE_TENANT_USER')")
  public CommonResponse<WorkflowDefinitionDetailResponse> getById(
      @PathVariable Long id, @RequestParam("tenantId") String tenantId) {
    return responseFactory.success(workflowDefinitionApplicationService.getById(id, tenantId));
  }

  @PostMapping
  @PreAuthorize("hasAuthority('ROLE_ADMIN')")
  public CommonResponse<WorkflowDefinitionDetailResponse> create(
      @Valid @RequestBody WorkflowDefinitionSaveRequest request) {
    return responseFactory.success(workflowDefinitionApplicationService.create(request));
  }

  @PutMapping("/{id}")
  @PreAuthorize("hasAuthority('ROLE_ADMIN')")
  public CommonResponse<WorkflowDefinitionDetailResponse> update(
      @PathVariable Long id, @Valid @RequestBody WorkflowDefinitionSaveRequest request) {
    return responseFactory.success(workflowDefinitionApplicationService.update(id, request));
  }

  /** 启用/禁用工作流定义。 */
  @PatchMapping("/{id}")
  @PreAuthorize("hasAuthority('ROLE_ADMIN')")
  public CommonResponse<Void> patch(
      @PathVariable Long id, @Valid @RequestBody EnabledPatchRequest request) {
    workflowDefinitionApplicationService.toggleEnabled(
        id, request.getTenantId(), request.getEnabled());
    return responseFactory.success(null);
  }

  @PostMapping("/{id}/validate")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_AUDITOR', 'ROLE_TENANT_ADMIN')")
  public CommonResponse<DagValidationResult> validate(
      @PathVariable Long id, @RequestParam("tenantId") String tenantId) {
    return responseFactory.success(workflowDefinitionApplicationService.validate(id, tenantId));
  }

  /**
   * 把 workflow 渲染为 mermaid flowchart 文本,可贴入 GitHub README / PR / 文档站。运行时 viewer 与 docs/PR review
   * 两种场景共享同一图形语言。
   */
  @GetMapping("/{id}/mermaid")
  @PreAuthorize(
      "hasAnyAuthority('ROLE_ADMIN', 'ROLE_AUDITOR', 'ROLE_TENANT_ADMIN'," + " 'ROLE_TENANT_USER')")
  public CommonResponse<WorkflowMermaidResponse> mermaid(
      @PathVariable Long id, @RequestParam("tenantId") String tenantId) {
    WorkflowDefinitionDetailResponse detail =
        workflowDefinitionApplicationService.getById(id, tenantId);
    return responseFactory.success(
        new WorkflowMermaidResponse(WorkflowMermaidRenderer.render(detail)));
  }

  // ─── BE Spike: workflow-dag-designer 全量替换 + 单人编辑锁 ───────────────────────────
  // 详见 docs/design/workflow-dag-designer.md

  /**
   * 画布 Save 全量替换:同事务清空 nodes/edges + 重写 + version 自增。必须先 acquire 锁。
   *
   * <p>失败码:CONFLICT(锁不归属/未持锁/expectedVersion 冲突)、INVALID_ARGUMENT(workflowCode 试图改)、NOT_FOUND。
   */
  @PutMapping("/{id}/full")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_TENANT_ADMIN')")
  public CommonResponse<WorkflowDefinitionDetailResponse> fullUpdate(
      @PathVariable Long id, @Valid @RequestBody WorkflowDefinitionFullUpdateRequest request) {
    return responseFactory.success(
        workflowDefinitionApplicationService.fullUpdate(id, request, currentUsername()));
  }

  /** 申请编辑锁(5min TTL)。别人持锁 → 409 CONFLICT 带 lockedBy。 */
  @PutMapping("/{id}/lock")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_TENANT_ADMIN')")
  public CommonResponse<WorkflowDesignLockResponse> acquireLock(
      @PathVariable Long id, @RequestParam("tenantId") String tenantId) {
    LockHolder holder = designLockService.acquire(tenantId, id, currentUsername());
    return responseFactory.success(toResponse(holder));
  }

  /** 释放编辑锁(必须持锁人调用);非持锁人 → 403。锁已过期 → 幂等 204。 */
  @DeleteMapping("/{id}/lock")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_TENANT_ADMIN')")
  public ResponseEntity<Void> releaseLock(
      @PathVariable Long id, @RequestParam("tenantId") String tenantId) {
    designLockService.release(tenantId, id, currentUsername());
    return ResponseEntity.noContent().build();
  }

  /** 续期编辑锁(再续 5min);锁已过期 → 409(让前端重新 acquire)。 */
  @PutMapping("/{id}/lock/renew")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_TENANT_ADMIN')")
  public CommonResponse<WorkflowDesignLockResponse> renewLock(
      @PathVariable Long id, @RequestParam("tenantId") String tenantId) {
    LockHolder holder = designLockService.renew(tenantId, id, currentUsername());
    return responseFactory.success(toResponse(holder));
  }

  private static WorkflowDesignLockResponse toResponse(LockHolder holder) {
    return new WorkflowDesignLockResponse(holder.lockedBy(), holder.expiresAt());
  }

  /** 从 SecurityContext 取当前 username;未认证 / 非 ConsolePrincipal → UNAUTHORIZED。 */
  private static String currentUsername() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.getPrincipal() instanceof ConsolePrincipal principal) {
      return principal.username();
    }
    throw BizException.of(ResultCode.UNAUTHORIZED, "error.auth.unauthenticated");
  }
}
