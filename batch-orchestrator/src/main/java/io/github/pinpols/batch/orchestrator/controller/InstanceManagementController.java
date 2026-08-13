package io.github.pinpols.batch.orchestrator.controller;

import io.github.pinpols.batch.orchestrator.application.service.task.InstanceManagementApplicationService;
import io.github.pinpols.batch.orchestrator.application.service.task.InstanceManagementResults.InstanceAction;
import io.github.pinpols.batch.orchestrator.application.service.task.InstanceManagementResults.PartitionAction;
import io.github.pinpols.batch.orchestrator.application.service.task.InstanceManagementResults.RetryFailedPartitions;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 任务实例运行态管控内部控制器，基础路径 {@code /internal/instances}。 支持对任务实例的取消（{@code POST
 * /{id}/cancel}）和强制终止（{@code POST /{id}/terminate}）， 以及对分区实例的取消（{@code POST
 * /partitions/{id}/cancel}）和重试（{@code POST /partitions/{id}/retry}）。 仅限内部服务或运维平台调用，不对外暴露。
 */
@RestController
@RequestMapping("/internal/instances")
@RequiredArgsConstructor
public class InstanceManagementController {

  private final InstanceManagementApplicationService instanceManagementApplicationService;

  @PostMapping("/{id}/cancel")
  public InstanceAction cancel(@PathVariable Long id, @RequestParam("tenantId") String tenantId) {
    return instanceManagementApplicationService.cancel(tenantId, id);
  }

  @PostMapping("/{id}/terminate")
  public InstanceAction terminate(
      @PathVariable Long id, @RequestParam("tenantId") String tenantId) {
    return instanceManagementApplicationService.terminate(tenantId, id);
  }

  @PostMapping("/{id}/pause")
  public InstanceAction pause(@PathVariable Long id, @RequestParam("tenantId") String tenantId) {
    return instanceManagementApplicationService.pause(tenantId, id);
  }

  @PostMapping("/{id}/resume")
  public InstanceAction resume(@PathVariable Long id, @RequestParam("tenantId") String tenantId) {
    return instanceManagementApplicationService.resume(tenantId, id);
  }

  @PostMapping("/partitions/{id}/cancel")
  public PartitionAction cancelPartition(
      @PathVariable Long id, @RequestParam("tenantId") String tenantId) {
    return instanceManagementApplicationService.cancelPartition(tenantId, id);
  }

  @PostMapping("/partitions/{id}/retry")
  public PartitionAction retryPartition(
      @PathVariable Long id, @RequestParam("tenantId") String tenantId) {
    return instanceManagementApplicationService.retryPartition(tenantId, id);
  }

  @PostMapping("/{id}/partitions/retry-failed")
  public RetryFailedPartitions retryFailedPartitions(
      @PathVariable Long id, @RequestParam("tenantId") String tenantId) {
    return instanceManagementApplicationService.retryFailedPartitions(tenantId, id);
  }
}
