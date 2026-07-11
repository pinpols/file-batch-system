package io.github.pinpols.batch.console.domain.job.web;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.console.domain.audit.support.AuditAction;
import io.github.pinpols.batch.console.domain.job.web.response.ConsoleInstanceActionResponse;
import io.github.pinpols.batch.console.domain.job.web.response.ConsolePartitionActionResponse;
import io.github.pinpols.batch.console.domain.job.web.response.ConsoleRetryFailedPartitionsResponse;
import io.github.pinpols.batch.console.domain.ops.application.ConsoleOrchestratorProxyService;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.Idempotent;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 实例运维（cancel / terminate / partition retry）：状态切换 + 代理下游 orchestrator。 双击会重复调用下游，虽然状态 CAS
 * 能兜住，但会产生额外审计日志 → 类级 @Idempotent。
 */
@RestController
@Validated
@RequestMapping("/api/console/instances")
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
@RequiredArgsConstructor
@Idempotent
public class ConsoleInstanceController {

  private final ConsoleOrchestratorProxyService orchestratorProxyService;
  private final ConsoleResponseFactory responseFactory;

  @PostMapping("/{id}/cancel")
  @AuditAction(
      action = "instance.cancel",
      aggregateType = "job_instance",
      aggregateId = "#id",
      targetTenantParam = "#tenantId")
  public CommonResponse<ConsoleInstanceActionResponse> cancel(
      @PathVariable Long id, @RequestParam("tenantId") String tenantId) {
    return responseFactory.success(
        ConsoleInstanceActionResponse.from(
            orchestratorProxyService.instanceAction(id, tenantId, "cancel")));
  }

  @PostMapping("/{id}/terminate")
  @AuditAction(
      action = "instance.terminate",
      aggregateType = "job_instance",
      aggregateId = "#id",
      targetTenantParam = "#tenantId")
  public CommonResponse<ConsoleInstanceActionResponse> terminate(
      @PathVariable Long id, @RequestParam("tenantId") String tenantId) {
    return responseFactory.success(
        ConsoleInstanceActionResponse.from(
            orchestratorProxyService.instanceAction(id, tenantId, "terminate")));
  }

  @PostMapping("/{id}/pause")
  @AuditAction(
      action = "instance.pause",
      aggregateType = "job_instance",
      aggregateId = "#id",
      targetTenantParam = "#tenantId")
  public CommonResponse<ConsoleInstanceActionResponse> pause(
      @PathVariable Long id, @RequestParam("tenantId") String tenantId) {
    return responseFactory.success(
        ConsoleInstanceActionResponse.from(
            orchestratorProxyService.instanceAction(id, tenantId, "pause")));
  }

  @PostMapping("/{id}/resume")
  @AuditAction(
      action = "instance.resume",
      aggregateType = "job_instance",
      aggregateId = "#id",
      targetTenantParam = "#tenantId")
  public CommonResponse<ConsoleInstanceActionResponse> resume(
      @PathVariable Long id, @RequestParam("tenantId") String tenantId) {
    return responseFactory.success(
        ConsoleInstanceActionResponse.from(
            orchestratorProxyService.instanceAction(id, tenantId, "resume")));
  }

  @PostMapping("/partitions/{id}/cancel")
  @AuditAction(
      action = "partition.cancel",
      aggregateType = "job_partition",
      aggregateId = "#id",
      targetTenantParam = "#tenantId")
  public CommonResponse<ConsolePartitionActionResponse> cancelPartition(
      @PathVariable Long id, @RequestParam("tenantId") String tenantId) {
    return responseFactory.success(
        ConsolePartitionActionResponse.from(
            orchestratorProxyService.partitionAction(id, tenantId, "cancel")));
  }

  @PostMapping("/partitions/{id}/retry")
  @AuditAction(
      action = "partition.retry",
      aggregateType = "job_partition",
      aggregateId = "#id",
      targetTenantParam = "#tenantId")
  public CommonResponse<ConsolePartitionActionResponse> retryPartition(
      @PathVariable Long id, @RequestParam("tenantId") String tenantId) {
    return responseFactory.success(
        ConsolePartitionActionResponse.from(
            orchestratorProxyService.partitionAction(id, tenantId, "retry")));
  }

  @PostMapping("/{id}/partitions/retry-failed")
  @AuditAction(
      action = "partition.retry_failed_shards",
      aggregateType = "job_instance",
      aggregateId = "#id",
      targetTenantParam = "#tenantId")
  public CommonResponse<ConsoleRetryFailedPartitionsResponse> retryFailedPartitions(
      @PathVariable Long id, @RequestParam("tenantId") String tenantId) {
    return responseFactory.success(
        ConsoleRetryFailedPartitionsResponse.from(
            orchestratorProxyService.retryFailedPartitions(id, tenantId)));
  }
}
