package com.example.batch.trigger.infrastructure.scheduler;

import com.example.batch.trigger.domain.TriggerDefinitionLoader;
import com.example.batch.trigger.domain.TriggerRegistrationService;
import com.example.batch.trigger.infrastructure.TriggerGracefulShutdown;
import com.example.batch.trigger.infrastructure.TriggerSchedulerFacade;
import com.example.batch.trigger.support.TriggerDescriptor;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.impl.matchers.GroupMatcher;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定期对账 {@code job_definition} DB 状态与 Quartz JobStore 执行态，使两者保持一致。
 *
 * <p><b>权威源</b>：{@code job_definition.enabled=true AND schedule_type IN ('CRON','FIXED_RATE') AND
 * trigger_mode IN ('SCHEDULED','MIXED')}（由 {@link TriggerDefinitionLoader#loadAll} 过滤）。
 *
 * <p><b>对账规则</b>：
 *
 * <ul>
 *   <li>DB 有 + Quartz 无 → {@link TriggerRegistrationService#registerByJobCode} 注册
 *   <li>DB 无 + Quartz 有 → {@link TriggerRegistrationService#unregisterByJobCode} 清理
 *   <li>DB 有 + Quartz 有 → 保持（本版本不做 schedule 变更检测，由 console toggleEnabled → false →
 *       true 的完整循环触发 replace；也可由 registerByJobCode 热更新 API 显式触发）
 * </ul>
 *
 * <p><b>为什么需要对账</b>：console 侧 {@code toggleEnabled(false)} / job definition 删除只写 DB，
 * 不直接调用 trigger 模块；本 reconciler 以 30s 周期把 Quartz 收敛到 DB。首次启动在
 * {@link ApplicationReadyEvent} 立即跑一次，避免 30s 启动空窗。
 *
 * <p><b>集群并发</b>：ShedLock({@code PT5M}) 保证多实例只有一个在对账；单次对账耗时通常 &lt; 几秒，
 * 5 分钟上限足够兜住大租户规模。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TriggerReconciler {

  private final TriggerDefinitionLoader triggerDefinitionLoader;
  private final TriggerRegistrationService triggerRegistrationService;
  private final Scheduler scheduler;
  private final TriggerGracefulShutdown gracefulShutdown;

  @EventListener(ApplicationReadyEvent.class)
  public void onApplicationReady() {
    log.info("trigger reconciler initial run on application ready");
    reconcile();
  }

  @Scheduled(fixedDelayString = "${batch.trigger.reconcile-interval-millis:30000}")
  @SchedulerLock(name = "trigger_reconciler", lockAtMostFor = "PT5M", lockAtLeastFor = "PT10S")
  public void reconcile() {
    if (gracefulShutdown.isDraining()) {
      return;
    }
    try {
      doReconcile();
    } catch (RuntimeException exception) {
      log.warn("trigger reconcile pass failed, will retry next cycle: {}", exception.getMessage());
    }
  }

  private void doReconcile() {
    Set<JobKey> quartzJobs = loadQuartzJobKeys();
    List<TriggerDescriptor> dbDescriptors = triggerDefinitionLoader.loadAll();
    Set<JobKey> expectedJobs = new HashSet<>();
    int registered = 0;
    for (TriggerDescriptor descriptor : dbDescriptors) {
      if (!descriptor.isEnabled()) {
        continue;
      }
      JobKey key =
          JobKey.jobKey(
              descriptor.getTenantId() + ":" + descriptor.getJobCode(),
              TriggerSchedulerFacade.JOB_GROUP);
      expectedJobs.add(key);
      if (!quartzJobs.contains(key)) {
        triggerRegistrationService.registerByJobCode(
            descriptor.getTenantId(), descriptor.getJobCode());
        registered++;
      }
    }
    int unregistered = 0;
    for (JobKey key : quartzJobs) {
      if (expectedJobs.contains(key)) {
        continue;
      }
      String[] parts = key.getName().split(":", 2);
      if (parts.length != 2) {
        log.warn("trigger reconciler skipping malformed quartz JobKey: {}", key);
        continue;
      }
      triggerRegistrationService.unregisterByJobCode(parts[0], parts[1]);
      unregistered++;
    }
    if (registered > 0 || unregistered > 0) {
      log.info(
          "trigger reconcile drift resolved: registered={}, unregistered={}, expectedTotal={}",
          registered,
          unregistered,
          expectedJobs.size());
    }
  }

  private Set<JobKey> loadQuartzJobKeys() {
    try {
      return new HashSet<>(
          scheduler.getJobKeys(GroupMatcher.jobGroupEquals(TriggerSchedulerFacade.JOB_GROUP)));
    } catch (SchedulerException exception) {
      throw new IllegalStateException("failed to list quartz job keys", exception);
    }
  }
}
