package io.github.pinpols.batch.console.domain.ops.web;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.console.domain.ops.service.ConsoleWorkerFingerprintQueryService;
import io.github.pinpols.batch.console.domain.ops.web.response.WorkerFingerprintResponse;
import io.github.pinpols.batch.console.domain.ops.web.response.WorkerFingerprintSummaryResponse;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SDK Phase 5 / SDK-P5-3(console Lane D):worker 运行指纹只读端点。
 *
 * <p>V163 给 {@code batch.worker_registry} 加了 {@code build_id} / {@code sdk_version} 列,SDK Phase 5
 * register 路径已上报(SDK-P5-3 #220)。本 controller 暴露列表 + (buildId, sdkVersion) 聚合,供运维灰度切量与可视化排查使用, 取代以前
 * SQL 直查 worker_registry 的应急做法。
 *
 * <p>权限:租户管理员看本租户;平台管理员可查任意租户({@link ConsoleTenantGuard#resolveTenant} 解析)。只读,走 console-api
 * 读写分离只读路径,worker_registry 由 orchestrator 写入。
 */
@RestController
@RequestMapping("/api/console/workers/fingerprints")
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_TENANT_ADMIN')")
@RequiredArgsConstructor
public class ConsoleWorkerFingerprintController {

  private final ConsoleWorkerFingerprintQueryService queryService;
  private final ConsoleResponseFactory responseFactory;

  /**
   * GET /api/console/workers/fingerprints?tenantId=xxx — 列租户「ONLINE + DRAINING」worker
   * 指纹,heartbeat_at 倒序,上限 200 行。
   */
  @GetMapping
  public CommonResponse<List<WorkerFingerprintResponse>> list(
      @RequestParam(value = "tenantId", required = false) String tenantId) {
    return responseFactory.success(queryService.list(tenantId));
  }

  /**
   * GET /api/console/workers/fingerprints/summary?tenantId=xxx — 按 (buildId, sdkVersion) 聚合 ONLINE
   * worker 数,count desc。空值在 SQL 层 COALESCE 为 "(unknown)"。
   */
  @GetMapping("/summary")
  public CommonResponse<List<WorkerFingerprintSummaryResponse>> summary(
      @RequestParam(value = "tenantId", required = false) String tenantId) {
    return responseFactory.success(queryService.summary(tenantId));
  }
}
