package com.example.batch.trigger.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.trigger.domain.TriggerRegistrationService;
import com.example.batch.trigger.domain.TriggerStatusInfo;
import com.example.batch.trigger.infrastructure.TriggerGracefulShutdown;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 触发器运维管理控制器，提供触发器的注册、注销、暂停、恢复以及优雅排水（draining）等运维操作接口。 该接口仅供内部运维使用，不对外暴露；所有操作结果以 {@code
 * Map<String,String>} 形式返回当前状态。 暂停/恢复操作同时支持单个任务维度和租户维度的批量控制。
 */
@RestController
@RequestMapping("/api/triggers/management")
@RequiredArgsConstructor
public class TriggerManagementController {

  // ── duplicate literal constants ─────────────────────────────────────────
  private static final String KEY_TENANT_ID = "tenantId";
  private static final String KEY_JOB_CODE = "jobCode";
  private static final String KEY_STATUS = "status";

  private final TriggerRegistrationService triggerRegistrationService;
  private final TriggerGracefulShutdown gracefulShutdown;

  @GetMapping("/list")
  public CommonResponse<List<TriggerStatusInfo>> list() {
    return CommonResponse.success(triggerRegistrationService.listRegisteredTriggers());
  }

  @PostMapping("/register")
  public CommonResponse<Map<String, String>> register(
      @RequestParam(KEY_TENANT_ID) String tenantId, @RequestParam(KEY_JOB_CODE) String jobCode) {
    triggerRegistrationService.registerByJobCode(tenantId, jobCode);
    return CommonResponse.success(jobStatus(tenantId, jobCode, "REGISTERED"));
  }

  @PostMapping("/unregister")
  public CommonResponse<Map<String, String>> unregister(
      @RequestParam(KEY_TENANT_ID) String tenantId, @RequestParam(KEY_JOB_CODE) String jobCode) {
    triggerRegistrationService.unregisterByJobCode(tenantId, jobCode);
    return CommonResponse.success(jobStatus(tenantId, jobCode, "UNREGISTERED"));
  }

  @PostMapping("/pause")
  public CommonResponse<Map<String, String>> pause(
      @RequestParam(KEY_TENANT_ID) String tenantId, @RequestParam(KEY_JOB_CODE) String jobCode) {
    triggerRegistrationService.pauseByJobCode(tenantId, jobCode);
    return CommonResponse.success(jobStatus(tenantId, jobCode, "PAUSED"));
  }

  @PostMapping("/resume")
  public CommonResponse<Map<String, String>> resume(
      @RequestParam(KEY_TENANT_ID) String tenantId, @RequestParam(KEY_JOB_CODE) String jobCode) {
    triggerRegistrationService.resumeByJobCode(tenantId, jobCode);
    return CommonResponse.success(jobStatus(tenantId, jobCode, "NORMAL"));
  }

  /** {tenantId, jobCode, status} 响应体构造，register/unregister/pause/resume 共用。 */
  private static Map<String, String> jobStatus(String tenantId, String jobCode, String status) {
    return Map.of(KEY_TENANT_ID, tenantId, KEY_JOB_CODE, jobCode, KEY_STATUS, status);
  }

  @GetMapping("/scheduler-status")
  public CommonResponse<Map<String, String>> schedulerStatus() {
    String status = triggerRegistrationService.schedulerStatus();
    return CommonResponse.success(Map.of(KEY_STATUS, status));
  }

  @PostMapping("/pause-all")
  public CommonResponse<Map<String, String>> pauseAll() {
    triggerRegistrationService.pauseAll();
    return CommonResponse.success(Map.of(KEY_STATUS, "ALL_PAUSED"));
  }

  @PostMapping("/resume-all")
  public CommonResponse<Map<String, String>> resumeAll() {
    triggerRegistrationService.resumeAll();
    return CommonResponse.success(Map.of(KEY_STATUS, "ALL_RESUMED"));
  }

  @PostMapping("/pause-tenant")
  public CommonResponse<Map<String, String>> pauseByTenant(@RequestParam String tenantId) {
    triggerRegistrationService.pauseByTenant(tenantId);
    return CommonResponse.success(Map.of(KEY_STATUS, "TENANT_PAUSED", "tenantId", tenantId));
  }

  @PostMapping("/resume-tenant")
  public CommonResponse<Map<String, String>> resumeByTenant(@RequestParam String tenantId) {
    triggerRegistrationService.resumeByTenant(tenantId);
    return CommonResponse.success(Map.of(KEY_STATUS, "TENANT_RESUMED", "tenantId", tenantId));
  }

  @GetMapping("/drain/status")
  public CommonResponse<Map<String, Object>> drainStatus() throws Exception {
    return CommonResponse.success(gracefulShutdown.status());
  }

  @PostMapping("/drain/enable")
  public CommonResponse<Map<String, Object>> enableDrain() throws Exception {
    gracefulShutdown.startDraining("manual-enable");
    return CommonResponse.success(gracefulShutdown.status());
  }

  @PostMapping("/drain/disable")
  public CommonResponse<Map<String, Object>> disableDrain() throws Exception {
    gracefulShutdown.stopDraining("manual-disable");
    return CommonResponse.success(gracefulShutdown.status());
  }
}
