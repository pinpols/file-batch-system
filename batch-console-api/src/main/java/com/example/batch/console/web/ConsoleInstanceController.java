package com.example.batch.console.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.application.ConsoleOrchestratorProxyService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.Idempotent;
import java.util.Map;
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
  public CommonResponse<Map<String, Object>> cancel(
      @PathVariable Long id, @RequestParam("tenantId") String tenantId) {
    return responseFactory.success(orchestratorProxyService.instanceAction(id, tenantId, "cancel"));
  }

  @PostMapping("/{id}/terminate")
  public CommonResponse<Map<String, Object>> terminate(
      @PathVariable Long id, @RequestParam("tenantId") String tenantId) {
    return responseFactory.success(
        orchestratorProxyService.instanceAction(id, tenantId, "terminate"));
  }

  @PostMapping("/partitions/{id}/cancel")
  public CommonResponse<Map<String, Object>> cancelPartition(
      @PathVariable Long id, @RequestParam("tenantId") String tenantId) {
    return responseFactory.success(
        orchestratorProxyService.partitionAction(id, tenantId, "cancel"));
  }

  @PostMapping("/partitions/{id}/retry")
  public CommonResponse<Map<String, Object>> retryPartition(
      @PathVariable Long id, @RequestParam("tenantId") String tenantId) {
    return responseFactory.success(orchestratorProxyService.partitionAction(id, tenantId, "retry"));
  }
}
