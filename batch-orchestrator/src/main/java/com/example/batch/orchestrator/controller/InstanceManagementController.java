package com.example.batch.orchestrator.controller;

import com.example.batch.orchestrator.application.service.InstanceManagementApplicationService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 任务实例运行态管控内部控制器，基础路径 {@code /internal/instances}。
 * 支持对任务实例的取消（{@code POST /{id}/cancel}）和强制终止（{@code POST /{id}/terminate}），
 * 以及对分区实例的取消（{@code POST /partitions/{id}/cancel}）和重试（{@code POST /partitions/{id}/retry}）。
 * 仅限内部服务或运维平台调用，不对外暴露。
 */
@RestController
@RequestMapping("/internal/instances")
@RequiredArgsConstructor
public class InstanceManagementController {

  private final InstanceManagementApplicationService instanceManagementApplicationService;

  @PostMapping("/{id}/cancel")
  public Map<String, Object> cancel(
      @PathVariable Long id, @RequestParam("tenantId") String tenantId) {
    return instanceManagementApplicationService.cancel(tenantId, id);
  }

  @PostMapping("/{id}/terminate")
  public Map<String, Object> terminate(
      @PathVariable Long id, @RequestParam("tenantId") String tenantId) {
    return instanceManagementApplicationService.terminate(tenantId, id);
  }

  @PostMapping("/partitions/{id}/cancel")
  public Map<String, Object> cancelPartition(
      @PathVariable Long id, @RequestParam("tenantId") String tenantId) {
    return instanceManagementApplicationService.cancelPartition(tenantId, id);
  }

  @PostMapping("/partitions/{id}/retry")
  public Map<String, Object> retryPartition(
      @PathVariable Long id, @RequestParam("tenantId") String tenantId) {
    return instanceManagementApplicationService.retryPartition(tenantId, id);
  }
}
