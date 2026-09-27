package io.github.pinpols.batch.console.domain.observability.web;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.console.application.contract.response.CronPreviewResponse;
import io.github.pinpols.batch.console.application.contract.response.MaintenanceStatusResponse;
import io.github.pinpols.batch.console.domain.observability.application.CronPreviewService;
import io.github.pinpols.batch.console.support.maintenance.MaintenanceStateHolder;
import io.github.pinpols.batch.console.support.maintenance.MaintenanceStateHolder.MaintenanceState;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Console 系统级接口：维护状态公开，其他工具遵循正式角色授权。 */
@RestController
@RequestMapping("/api/console/system")
@RequiredArgsConstructor
public class ConsoleSystemController {

  private final MaintenanceStateHolder maintenanceStateHolder;
  private final CronPreviewService cronPreviewService;

  /**
   * 维护状态探活。前端启动 + 30s 轮询调用,据此切换全局 banner / 降级页。
   *
   * <p>注意:本端点在维护期间仍然返回 200(由 {@code MaintenanceModeFilter} 白名单放行),否则前端无法探测恢复时机。
   */
  @GetMapping("/maintenance")
  public CommonResponse<MaintenanceStatusResponse> maintenanceStatus() {
    MaintenanceState state = maintenanceStateHolder.current();
    MaintenanceStatusResponse response = new MaintenanceStatusResponse(
        state.enabled(),
        state.readOnly(),
        state.message(),
        state.etaAt() != null ? state.etaAt().toString() : null,
        state.affectedServices());
    return CommonResponse.success(response);
  }

  /**
   * Cron 表达式预览:校验 + 计算下 N 次执行时刻(ISO-8601 UTC)。
   *
   * <p>由服务使用 Quartz 解析,与实际调度引擎同一份代码。FE `CronExprInput` 输入防抖
   * 300ms 后调用,展示「最近 3 次执行」。
   *
   * <p>时区使用 {@code batch.timezone.default-zone}(默认 Asia/Shanghai),与 trigger 模块的 scheduler 默认配置对齐。
   *
   * @param expr Quartz 6/7 字段表达式(秒 分 时 日 月 星期 [年])
   * @param count 返回时刻数,默认 3,上限 20
   */
  @GetMapping("/cron-preview")
  @PreAuthorize(
      "hasAnyAuthority('ROLE_ADMIN', 'ROLE_TENANT_ADMIN', 'ROLE_AUDITOR', 'ROLE_TENANT_USER')")
  public CommonResponse<CronPreviewResponse> cronPreview(
      @RequestParam("expr") String expr,
      @RequestParam(value = "count", required = false) Integer count) {
    return CommonResponse.success(cronPreviewService.preview(expr, count));
  }
}
