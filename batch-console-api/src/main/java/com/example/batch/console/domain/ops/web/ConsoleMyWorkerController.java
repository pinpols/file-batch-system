package com.example.batch.console.domain.ops.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.domain.ops.entity.WorkerRegistryEntity;
import com.example.batch.console.domain.ops.mapper.WorkerRegistryMapper;
import com.example.batch.console.domain.rbac.support.ConsoleTenantGuard;
import com.example.batch.console.service.ConsoleResponseFactory;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADR-035 P4 "我的 Worker" — 租户管理员看自己 SDK 自托管 worker 列表 + 健康度。
 *
 * <p>权限:租户管理员看本租户;平台管理员可查任意租户(通过 {@link ConsoleTenantGuard#resolveTenant} 解析)。
 *
 * <p>区别于 {@link ConsoleWorkerController}({@code /api/console/workers},运维端 drain/force-offline
 * 等管控操作):本端点只读、只看 {@code is_self_hosted=true} 的 worker,FE 单独页(P4 落地)。
 */
@RestController
@RequestMapping("/api/console/my-workers")
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_TENANT_ADMIN')")
@RequiredArgsConstructor
public class ConsoleMyWorkerController {

  private final WorkerRegistryMapper mapper;
  private final ConsoleTenantGuard tenantGuard;
  private final ConsoleResponseFactory responseFactory;

  /** GET /api/console/my-workers?tenantId=xxx — 列出本租户所有自托管 worker(无分页,通常 ≤ 几十个)。 */
  @GetMapping
  public CommonResponse<List<WorkerRegistryEntity>> list(
      @RequestParam(value = "tenantId", required = false) String tenantId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return responseFactory.success(mapper.selectSelfHostedByTenant(resolved));
  }

  /** GET /api/console/my-workers/count?tenantId=xxx — 本租户自托管 worker 计数(仪表盘卡片用)。 */
  @GetMapping("/count")
  public CommonResponse<Long> count(
      @RequestParam(value = "tenantId", required = false) String tenantId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return responseFactory.success(mapper.countSelfHostedByTenant(resolved));
  }
}
