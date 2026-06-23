package io.github.pinpols.batch.console.domain.workflow.web;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.domain.job.web.request.EnabledPatchRequest;
import io.github.pinpols.batch.console.domain.rbac.support.ConsolePrincipal;
import io.github.pinpols.batch.console.domain.workflow.application.ConsoleWorkflowDefinitionApplicationService;
import io.github.pinpols.batch.console.domain.workflow.application.ConsoleWorkflowDefinitionApplicationService.DagValidationResult;
import io.github.pinpols.batch.console.domain.workflow.application.WorkflowDesignLockService;
import io.github.pinpols.batch.console.domain.workflow.application.WorkflowDesignLockService.LockHolder;
import io.github.pinpols.batch.console.domain.workflow.infrastructure.WorkflowMermaidRenderer;
import io.github.pinpols.batch.console.domain.workflow.web.request.WorkflowDefinitionFullUpdateRequest;
import io.github.pinpols.batch.console.domain.workflow.web.request.WorkflowDefinitionSaveRequest;
import io.github.pinpols.batch.console.domain.workflow.web.response.WorkflowDefinitionDetailResponse;
import io.github.pinpols.batch.console.domain.workflow.web.response.WorkflowDefinitionVersionSummaryResponse;
import io.github.pinpols.batch.console.domain.workflow.web.response.WorkflowDesignLockResponse;
import io.github.pinpols.batch.console.domain.workflow.web.response.WorkflowMermaidResponse;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.Idempotent;
import jakarta.validation.Valid;
import java.util.List;
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

  // ─── Polish: 版本列表 / 版本详情(workflow-dag-designer diff 页面真实接入)───────────
  // V167 后真实读 workflow_definition_version;历史表无数据时降级单条 current。
  // 详见 docs/api/console-api-protocol.md Changelog 2026-06-04。

  /**
   * 列出工作流定义的所有历史版本(workflow-dag-designer Polish — 闭环 FE diff 页)。
   *
   * <p>真实读 {@code workflow_definition_version};历史表无数据(刚迁移后)→ 单条 current 降级兼容。
   */
  @GetMapping("/{id}/versions")
  @PreAuthorize(
      "hasAnyAuthority('ROLE_ADMIN', 'ROLE_AUDITOR', 'ROLE_TENANT_ADMIN'," + " 'ROLE_TENANT_USER')")
  public CommonResponse<List<WorkflowDefinitionVersionSummaryResponse>> listVersions(
      @PathVariable Long id, @RequestParam("tenantId") String tenantId) {
    return responseFactory.success(workflowDefinitionApplicationService.listVersions(id, tenantId));
  }

  /**
   * 读取指定 version 的完整 detail(当前 version → 主表 + 关联节点边;历史 version → 快照反序列化)。
   *
   * <p>不存在的版本 → NOT_FOUND。
   */
  @GetMapping("/{id}/versions/{version}")
  @PreAuthorize(
      "hasAnyAuthority('ROLE_ADMIN', 'ROLE_AUDITOR', 'ROLE_TENANT_ADMIN'," + " 'ROLE_TENANT_USER')")
  public CommonResponse<WorkflowDefinitionDetailResponse> getVersion(
      @PathVariable Long id,
      @PathVariable Integer version,
      @RequestParam("tenantId") String tenantId) {
    return responseFactory.success(
        workflowDefinitionApplicationService.getVersion(id, tenantId, version));
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
