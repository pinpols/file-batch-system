package io.github.pinpols.batch.console.domain.ops.web;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.console.domain.ops.service.ConsoleClusterDiagnosticService;
import io.github.pinpols.batch.console.domain.ops.web.response.ConsoleClusterDiagnosticResponse;
import io.github.pinpols.batch.console.domain.ops.web.response.ConsoleInstanceDiagnosisResponse;
import io.github.pinpols.batch.console.domain.ops.web.response.ConsoleOutboxHealthResponse;
import io.github.pinpols.batch.console.domain.ops.web.response.ConsoleShedLockStatusResponse;
import io.github.pinpols.batch.console.domain.ops.web.response.ConsoleTerminalChildrenHealthResponse;
import io.github.pinpols.batch.console.domain.ops.web.response.ConsoleWorkerConsistencyResponse;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
  public CommonResponse<ConsoleClusterDiagnosticResponse> diagnose(
      @RequestParam("tenantId") String tenantId) {
    return responseFactory.success(
        ConsoleClusterDiagnosticResponse.from(diagnosticService.diagnose(tenantId)));
  }

  @GetMapping("/shedlock")
  public CommonResponse<ConsoleShedLockStatusResponse> shedLockStatus(
      @RequestParam("tenantId") String tenantId) {
    return responseFactory.success(
        ConsoleShedLockStatusResponse.from(diagnosticService.shedLockStatus(tenantId)));
  }

  @GetMapping("/workers")
  public CommonResponse<ConsoleWorkerConsistencyResponse> workerConsistency(
      @RequestParam("tenantId") String tenantId) {
    return responseFactory.success(
        ConsoleWorkerConsistencyResponse.from(diagnosticService.workerConsistency(tenantId)));
  }

  @GetMapping("/outbox")
  public CommonResponse<ConsoleOutboxHealthResponse> outboxHealth(
      @RequestParam("tenantId") String tenantId) {
    return responseFactory.success(
        ConsoleOutboxHealthResponse.from(diagnosticService.outboxHealth(tenantId)));
  }

  @GetMapping("/terminal-children")
  public CommonResponse<ConsoleTerminalChildrenHealthResponse> terminalChildrenHealth(
      @RequestParam("tenantId") String tenantId) {
    return responseFactory.success(
        ConsoleTerminalChildrenHealthResponse.from(
            diagnosticService.terminalChildrenHealth(tenantId)));
  }

  @GetMapping("/instances/{id}")
  public CommonResponse<ConsoleInstanceDiagnosisResponse> instanceDiagnosis(
      @PathVariable("id") Long id, @RequestParam("tenantId") String tenantId) {
    return responseFactory.success(
        ConsoleInstanceDiagnosisResponse.from(diagnosticService.instanceDiagnosis(tenantId, id)));
  }
}
