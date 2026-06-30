package io.github.pinpols.batch.console.domain.ops.web;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.console.domain.ops.entity.CustomTaskTypeEntity;
import io.github.pinpols.batch.console.domain.ops.service.ConsoleCustomTaskTypeQueryService;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SDK Phase 3 M3.1 / API-P3-1：租户查看 SDK 声明的自定义 taskType（{@code custom_task_type_registry}）。
 *
 * <p>权限：租户管理员看本租户；平台管理员可查任意租户（{@link ConsoleTenantGuard#resolveTenant} 解析）。只读， 注册由 worker register
 * 上报维护（orchestrator 侧），console-api 走读写分离只读路径。
 */
@RestController
@RequestMapping("/api/console/custom-task-types")
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_TENANT_ADMIN')")
@RequiredArgsConstructor
public class ConsoleCustomTaskTypeController {

  private final ConsoleCustomTaskTypeQueryService queryService;
  private final ConsoleResponseFactory responseFactory;

  /**
   * GET /api/console/custom-task-types?tenantId=xxx — 列本租户 ACTIVE 自定义 taskType（last_declared_at
   * 倒序）。
   */
  @GetMapping
  public CommonResponse<List<CustomTaskTypeEntity>> list(
      @RequestParam(value = "tenantId", required = false) String tenantId) {
    return responseFactory.success(queryService.listActive(tenantId));
  }

  /** GET /api/console/custom-task-types/count?tenantId=xxx — 本租户 ACTIVE 自定义 taskType 计数。 */
  @GetMapping("/count")
  public CommonResponse<Long> count(
      @RequestParam(value = "tenantId", required = false) String tenantId) {
    return responseFactory.success(queryService.countActive(tenantId));
  }

  /**
   * GET /api/console/custom-task-types/{taskTypeCode}?tenantId=xxx — 单个 taskType 详情（含 descriptor
   * 全文）。
   */
  @GetMapping("/{taskTypeCode}")
  public CommonResponse<CustomTaskTypeEntity> detail(
      @PathVariable("taskTypeCode") String taskTypeCode,
      @RequestParam(value = "tenantId", required = false) String tenantId) {
    return responseFactory.success(queryService.detail(tenantId, taskTypeCode));
  }
}
