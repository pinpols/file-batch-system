package com.example.batch.console.domain.ops.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.domain.ops.service.ConsoleClusterDiagnosticService;
import com.example.batch.console.service.ConsoleResponseFactory;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 跨节点一致性诊断：检查 ShedLock 租约、Worker 注册表、Outbox 状态。 */
@RestController
@RequestMapping("/api/console/ops/cluster-diagnostic")
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
@RequiredArgsConstructor
public class ConsoleClusterDiagnosticController {

  private final ConsoleClusterDiagnosticService diagnosticService;
  private final ConsoleResponseFactory responseFactory;

  @GetMapping
  public CommonResponse<Map<String, Object>> diagnose(@RequestParam("tenantId") String tenantId) {
    return responseFactory.success(diagnosticService.diagnose(tenantId));
  }

  @GetMapping("/shedlock")
  public CommonResponse<Map<String, Object>> shedLockStatus(
      @RequestParam("tenantId") String tenantId) {
    return responseFactory.success(diagnosticService.shedLockStatus(tenantId));
  }

  @GetMapping("/workers")
  public CommonResponse<Map<String, Object>> workerConsistency(
      @RequestParam("tenantId") String tenantId) {
    return responseFactory.success(diagnosticService.workerConsistency(tenantId));
  }

  @GetMapping("/outbox")
  public CommonResponse<Map<String, Object>> outboxHealth(
      @RequestParam("tenantId") String tenantId) {
    return responseFactory.success(diagnosticService.outboxHealth(tenantId));
  }

  @GetMapping("/terminal-children")
  public CommonResponse<Map<String, Object>> terminalChildrenHealth(
      @RequestParam("tenantId") String tenantId) {
    return responseFactory.success(diagnosticService.terminalChildrenHealth(tenantId));
  }
}
