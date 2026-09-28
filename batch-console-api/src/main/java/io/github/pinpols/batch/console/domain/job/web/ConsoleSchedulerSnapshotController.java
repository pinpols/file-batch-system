package io.github.pinpols.batch.console.domain.job.web;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.console.application.ops.ConsoleOrchestratorPort;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleSecurityExpressions;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.shared.view.ConsoleSchedulerSnapshotHistoryResponse;
import io.github.pinpols.batch.console.shared.view.ConsoleSchedulerSnapshotResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 调度快照代理 REST：转发编排器内部接口，供控制台查看租户调度状态与历史。 */
@RestController
@Validated
@RequestMapping("/api/console/scheduler")
@PreAuthorize(ConsoleSecurityExpressions.ANY_CONSOLE_ROLE)
@RequiredArgsConstructor
public class ConsoleSchedulerSnapshotController {

  private final ConsoleOrchestratorPort orchestratorProxyService;
  private final ConsoleResponseFactory responseFactory;

  /** 当前调度快照（Redis 缓存 30s，分钟级数据无需实时）。 */
  @GetMapping("/snapshot")
  public CommonResponse<ConsoleSchedulerSnapshotResponse> live(
      @RequestParam("tenantId") String tenantId) {
    return responseFactory.success(orchestratorProxyService.schedulerSnapshot(tenantId));
  }

  /** 调度快照历史。 */
  @GetMapping("/snapshot/history")
  public CommonResponse<List<ConsoleSchedulerSnapshotHistoryResponse>> history(
      @RequestParam("tenantId") String tenantId,
      @RequestParam(value = "limit", defaultValue = "20") int limit) {
    return responseFactory.success(
        orchestratorProxyService.schedulerSnapshotHistory(tenantId, limit));
  }
}
