package com.example.batch.orchestrator.controller;

import com.example.batch.orchestrator.application.service.workflow.WorkflowRunManagementApplicationService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 工作流运行实例管控内部控制器，基础路径 {@code /internal/workflow-runs}。 支持取消（{@code POST /{id}/cancel}）、强制终止（{@code
 * POST /{id}/terminate}） 以及跳过指定节点（{@code POST /{id}/skip-node}，需传入 {@code nodeCode}）三类操作。
 * 仅限内部服务或运维平台调用，不对外暴露。
 */
@RestController
@RequestMapping("/internal/workflow-runs")
@RequiredArgsConstructor
public class WorkflowRunManagementController {

  private final WorkflowRunManagementApplicationService workflowRunManagementApplicationService;

  @PostMapping("/{id}/cancel")
  public Map<String, Object> cancel(
      @PathVariable Long id, @RequestParam("tenantId") String tenantId) {
    return workflowRunManagementApplicationService.cancel(tenantId, id);
  }

  @PostMapping("/{id}/terminate")
  public Map<String, Object> terminate(
      @PathVariable Long id, @RequestParam("tenantId") String tenantId) {
    return workflowRunManagementApplicationService.terminate(tenantId, id);
  }

  @PostMapping("/{id}/skip-node")
  public Map<String, Object> skipNode(
      @PathVariable Long id,
      @RequestParam("tenantId") String tenantId,
      @RequestParam("nodeCode") String nodeCode) {
    return workflowRunManagementApplicationService.skipNode(tenantId, id, nodeCode);
  }
}
