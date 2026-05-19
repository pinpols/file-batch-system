package com.example.batch.console.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.config.ConsoleMaintenanceProperties;
import com.example.batch.console.web.response.MaintenanceStatusResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Console 系统级公开接口:健康/维护状态等不要求登录的探活类端点。 */
@RestController
@RequestMapping("/api/console/system")
@RequiredArgsConstructor
public class ConsoleSystemController {

  private final ConsoleMaintenanceProperties maintenance;

  /**
   * 维护状态探活。前端启动 + 30s 轮询调用,据此切换全局 banner / 降级页。
   *
   * <p>注意:本端点在维护期间仍然返回 200(由 {@code MaintenanceModeFilter} 白名单放行),否则前端无法探测恢复时机。
   */
  @GetMapping("/maintenance")
  public CommonResponse<MaintenanceStatusResponse> maintenanceStatus() {
    MaintenanceStatusResponse response =
        new MaintenanceStatusResponse(
            maintenance.isEnabled(),
            maintenance.isReadOnly(),
            maintenance.getMessage(),
            maintenance.getEtaAt() != null ? maintenance.getEtaAt().toString() : null);
    return CommonResponse.success(response);
  }
}
