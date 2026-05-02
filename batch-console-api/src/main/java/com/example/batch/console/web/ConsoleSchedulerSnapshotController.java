package com.example.batch.console.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.application.ops.ConsoleOrchestratorProxyService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.cache.ConsoleQueryCacheService;
import com.example.batch.console.web.response.ops.ConsoleSchedulerSnapshotHistoryResponse;
import com.example.batch.console.web.response.ops.ConsoleSchedulerSnapshotResponse;
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
@PreAuthorize(
    "hasAnyAuthority('ROLE_ADMIN', 'ROLE_AUDITOR', 'ROLE_CONFIG_ADMIN', 'ROLE_TENANT_USER')")
@RequiredArgsConstructor
public class ConsoleSchedulerSnapshotController {

  private final ConsoleOrchestratorProxyService orchestratorProxyService;
  private final ConsoleResponseFactory responseFactory;
  private final ConsoleQueryCacheService cacheService;

  /** 当前调度快照（Redis 缓存 30s，分钟级数据无需实时）。 */
  @GetMapping("/snapshot")
  public CommonResponse<ConsoleSchedulerSnapshotResponse> live(
      @RequestParam("tenantId") String tenantId) {
    ConsoleSchedulerSnapshotResponse result =
        cacheService.getOrLoad(
            "snapshot:" + tenantId,
            ConsoleQueryCacheService.SNAPSHOT_TTL,
            ConsoleSchedulerSnapshotResponse.class,
            () -> orchestratorProxyService.schedulerSnapshot(tenantId));
    return responseFactory.success(result);
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
