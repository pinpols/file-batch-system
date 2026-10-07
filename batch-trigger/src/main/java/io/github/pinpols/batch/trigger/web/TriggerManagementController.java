package io.github.pinpols.batch.trigger.web;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.trigger.domain.TriggerActionResult;
import io.github.pinpols.batch.trigger.domain.TriggerRegistrationService;
import io.github.pinpols.batch.trigger.domain.TriggerSchedulerStatus;
import io.github.pinpols.batch.trigger.infrastructure.TriggerGracefulShutdown;
import io.github.pinpols.batch.trigger.infrastructure.TriggerGracefulShutdown.TriggerDrainStatus;
import lombok.RequiredArgsConstructor;
import org.quartz.SchedulerException;
import org.springframework.web.bind.annotation.*;

/**
 * 触发器运维管理控制器，提供触发器的注册、注销、暂停、恢复以及优雅排水（draining）等运维操作接口。
 * 该接口仅供内部运维使用，不对外暴露；操作结果通过固定响应对象返回当前状态。
 * 暂停/恢复操作同时支持单个任务维度和租户维度的批量控制。
 */
@RestController
@RequestMapping("/api/triggers/management")
@RequiredArgsConstructor
public class TriggerManagementController {

  private static final String KEY_TENANT_ID = "tenantId";
  private static final String KEY_JOB_CODE = "jobCode";

  private final TriggerRegistrationService triggerRegistrationService;
  private final TriggerGracefulShutdown gracefulShutdown;

  @PostMapping("/register")
  public CommonResponse<TriggerActionResult> register(
      @RequestParam(KEY_TENANT_ID) String tenantId, @RequestParam(KEY_JOB_CODE) String jobCode) {
    triggerRegistrationService.registerByJobCode(tenantId, jobCode);
    return CommonResponse.success(new TriggerActionResult(tenantId, jobCode, "REGISTERED"));
  }

  @PostMapping("/unregister")
  public CommonResponse<TriggerActionResult> unregister(
      @RequestParam(KEY_TENANT_ID) String tenantId, @RequestParam(KEY_JOB_CODE) String jobCode) {
    triggerRegistrationService.unregisterByJobCode(tenantId, jobCode);
    return CommonResponse.success(new TriggerActionResult(tenantId, jobCode, "UNREGISTERED"));
  }

  @PostMapping("/pause")
  public CommonResponse<TriggerActionResult> pause(
      @RequestParam(KEY_TENANT_ID) String tenantId, @RequestParam(KEY_JOB_CODE) String jobCode) {
    triggerRegistrationService.pauseByJobCode(tenantId, jobCode);
    return CommonResponse.success(new TriggerActionResult(tenantId, jobCode, "PAUSED"));
  }

  @PostMapping("/resume")
  public CommonResponse<TriggerActionResult> resume(
      @RequestParam(KEY_TENANT_ID) String tenantId, @RequestParam(KEY_JOB_CODE) String jobCode) {
    triggerRegistrationService.resumeByJobCode(tenantId, jobCode);
    return CommonResponse.success(new TriggerActionResult(tenantId, jobCode, "NORMAL"));
  }

  @PostMapping("/pause-all")
  public CommonResponse<TriggerSchedulerStatus> pauseAll() {
    triggerRegistrationService.pauseAll();
    return CommonResponse.success(new TriggerSchedulerStatus("ALL_PAUSED"));
  }

  @PostMapping("/resume-all")
  public CommonResponse<TriggerSchedulerStatus> resumeAll() {
    triggerRegistrationService.resumeAll();
    return CommonResponse.success(new TriggerSchedulerStatus("ALL_RESUMED"));
  }

  @PostMapping("/pause-tenant")
  public CommonResponse<TriggerActionResult> pauseByTenant(@RequestParam String tenantId) {
    triggerRegistrationService.pauseByTenant(tenantId);
    return CommonResponse.success(new TriggerActionResult(tenantId, null, "TENANT_PAUSED"));
  }

  @PostMapping("/resume-tenant")
  public CommonResponse<TriggerActionResult> resumeByTenant(@RequestParam String tenantId) {
    triggerRegistrationService.resumeByTenant(tenantId);
    return CommonResponse.success(new TriggerActionResult(tenantId, null, "TENANT_RESUMED"));
  }

  @PostMapping("/drain/enable")
  public CommonResponse<TriggerDrainStatus> enableDrain() throws SchedulerException {
    gracefulShutdown.startDraining("manual-enable");
    return CommonResponse.success(gracefulShutdown.status());
  }

  @PostMapping("/drain/disable")
  public CommonResponse<TriggerDrainStatus> disableDrain() throws SchedulerException {
    gracefulShutdown.stopDraining("manual-disable");
    return CommonResponse.success(gracefulShutdown.status());
  }
}
