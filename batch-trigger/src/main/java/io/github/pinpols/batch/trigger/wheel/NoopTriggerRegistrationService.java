package io.github.pinpols.batch.trigger.wheel;

import io.github.pinpols.batch.trigger.domain.TriggerRegistrationService;
import io.github.pinpols.batch.trigger.domain.TriggerStatusInfo;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Wheel 模式下的 {@link TriggerRegistrationService} 空实现。
 *
 * <p>R-arch-audit-2026-05-23 P1: Wheel scheduler 不通过 Quartz {@code Scheduler} 注册 / 暂停 / 恢复 触发器,
 * 而是直接由 {@link HashedWheelTriggerScheduler} + {@link WheelTriggerReconciler} 根据 {@code
 * trigger_runtime_state} 表 + cron 表达式驱动 fire。
 *
 * <p>历史上 {@link TriggerRegistrationService} 接口的实现是 {@code TriggerSchedulerFacade} (仅在 Quartz
 * 模式装配),wheel 模式下 {@link TriggerRegistrationService} 没有对应 bean → 任何依赖它的代码 (如 {@link
 * io.github.pinpols.batch.trigger.infrastructure.QuartzLaunchJob} 的 tenant-suspend 自愈分支、 {@link
 * io.github.pinpols.batch.trigger.web.TriggerManagementController} 的运维 API)无法注入。
 *
 * <p>本类提供空实现 — 所有 Quartz-specific 操作降级为 no-op + warn 日志, {@link #schedulerStatus()} 返回 {@code
 * "WHEEL"} 表示本服务运行在时间轮模式。 运维 API 在 wheel 模式下应使用 wheel-specific 端点(待补);本接口下的操作仅保留兼容性。
 */
@Slf4j
@Component
@ConditionalOnProperty(
    name = "batch.trigger.scheduler-impl",
    havingValue = "wheel",
    matchIfMissing = true)
public class NoopTriggerRegistrationService implements TriggerRegistrationService {

  @Override
  public void registerAll() {
    log.debug("wheel mode: TriggerRegistrationService.registerAll() noop");
  }

  @Override
  public void registerByJobCode(String tenantId, String jobCode) {
    log.debug("wheel mode: registerByJobCode noop tenantId={} jobCode={}", tenantId, jobCode);
  }

  @Override
  public void unregisterByJobCode(String tenantId, String jobCode) {
    log.debug("wheel mode: unregisterByJobCode noop tenantId={} jobCode={}", tenantId, jobCode);
  }

  @Override
  public void pauseByJobCode(String tenantId, String jobCode) {
    log.debug("wheel mode: pauseByJobCode noop tenantId={} jobCode={}", tenantId, jobCode);
  }

  @Override
  public void resumeByJobCode(String tenantId, String jobCode) {
    log.debug("wheel mode: resumeByJobCode noop tenantId={} jobCode={}", tenantId, jobCode);
  }

  @Override
  public void pauseByTenant(String tenantId) {
    log.debug("wheel mode: pauseByTenant noop tenantId={}", tenantId);
  }

  @Override
  public void resumeByTenant(String tenantId) {
    log.debug("wheel mode: resumeByTenant noop tenantId={}", tenantId);
  }

  @Override
  public List<TriggerStatusInfo> listRegisteredTriggers() {
    // wheel 模式下应使用 trigger_runtime_state 查询;此接口仅返回空列表占位。
    return List.of();
  }

  @Override
  public void pauseAll() {
    log.debug("wheel mode: pauseAll noop");
  }

  @Override
  public void resumeAll() {
    log.debug("wheel mode: resumeAll noop");
  }

  @Override
  public String schedulerStatus() {
    return "WHEEL";
  }
}
