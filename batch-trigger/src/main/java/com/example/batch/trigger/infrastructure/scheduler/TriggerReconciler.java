package com.example.batch.trigger.infrastructure.scheduler;

import com.example.batch.trigger.domain.TriggerDefinitionLoader;
import com.example.batch.trigger.domain.TriggerRegistrationService;
import com.example.batch.trigger.infrastructure.TriggerGracefulShutdown;
import com.example.batch.trigger.infrastructure.TriggerSchedulerFacade;
import com.example.batch.trigger.support.TriggerDescriptor;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.quartz.CronTrigger;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleTrigger;
import org.quartz.Trigger;
import org.quartz.impl.matchers.GroupMatcher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
 *   <li>DB 有 + Quartz 有 且 schedule drift（cron / interval / timezone 不一致）→ 触发 {@code
 *       registerByJobCode}，由 {@link TriggerSchedulerFacade#scheduleWithReplace} 的 delete-and-add
 *       语义把 Quartz 表达式替换为 DB 最新值
 *   <li>DB 有 + Quartz 有 且完全一致 → 保持
 * </ul>
 *
 * <p><b>为什么需要对账</b>：console 侧 {@code toggleEnabled(false)} / job definition 删除只写 DB， 不直接调用 trigger
 * 模块；本 reconciler 以 30s 周期把 Quartz 收敛到 DB。首次启动在 {@link ApplicationReadyEvent} 立即跑一次，避免 30s 启动空窗。
 *
 * <p><b>集群并发</b>：ShedLock({@code PT5M}) 保证多实例只有一个在对账；单次对账耗时通常 &lt; 几秒， 5 分钟上限足够兜住大租户规模。
 */
@Slf4j
@Component
// matchIfMissing=false：application.yml 一定提供 scheduler-impl 默认值（已切 wheel），缺省装配
// 不再回落到 quartz；仅当显式 BATCH_TRIGGER_SCHEDULER_IMPL=quartz 时本 reconciler 装配。
@ConditionalOnProperty(name = "batch.trigger.scheduler-impl", havingValue = "quartz")
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
    int replaced = 0;
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
      } else if (hasScheduleDrift(key, descriptor)) {
        log.info(
            "trigger schedule drift detected, re-registering: jobKey={}, newExpr={}, tz={}",
            key,
            descriptor.getScheduleExpression(),
            descriptor.getTimezone());
        triggerRegistrationService.registerByJobCode(
            descriptor.getTenantId(), descriptor.getJobCode());
        replaced++;
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
    if (registered > 0 || unregistered > 0 || replaced > 0) {
      log.info(
          "trigger reconcile drift resolved: registered={}, replaced={}, unregistered={},"
              + " expectedTotal={}",
          registered,
          replaced,
          unregistered,
          expectedJobs.size());
    }
  }

  /**
   * 检查 Quartz 里的 Trigger 与 DB descriptor 的 schedule 是否漂移： CRON 比 cronExpression +
   * timezone；FIXED_RATE 比 repeat interval（秒）。 用于 DB-Quartz 都存在但表达式/时区被人改了 DB 的情况——本方法返回 true 会触发
   * re-register 走 {@link TriggerSchedulerFacade#scheduleWithReplace} 的 delete-and-add 替换。
   */
  private boolean hasScheduleDrift(JobKey key, TriggerDescriptor descriptor) {
    String type = descriptor.getScheduleType();
    if (type == null || type.isBlank()) {
      // descriptor 没声明 schedule_type（异常数据 / 旧测试 fixture）→ 保守不判 drift
      return false;
    }
    try {
      List<? extends Trigger> triggers = scheduler.getTriggersOfJob(key);
      if (triggers == null || triggers.isEmpty()) {
        // Quartz 里有 jobKey 但没 trigger（罕见）→ 保守不判 drift，下一轮再说
        return false;
      }
      Trigger quartzTrigger = triggers.get(0);
      if ("CRON".equalsIgnoreCase(type)) {
        if (!(quartzTrigger instanceof CronTrigger ct)) {
          return true;
        }
        if (!Objects.equals(ct.getCronExpression(), descriptor.getScheduleExpression())) {
          return true;
        }
        String quartzTz = ct.getTimeZone() == null ? null : ct.getTimeZone().getID();
        return !Objects.equals(quartzTz, descriptor.getTimezone());
      }
      if ("FIXED_RATE".equalsIgnoreCase(type)) {
        if (!(quartzTrigger instanceof SimpleTrigger st)) {
          return true;
        }
        long expectedMillis = parseSecondsOrMinusOne(descriptor.getScheduleExpression()) * 1000L;
        return expectedMillis > 0 && st.getRepeatInterval() != expectedMillis;
      }
      return false;
    } catch (SchedulerException exception) {
      log.warn(
          "failed to inspect quartz trigger for drift check: key={}, reason={}",
          key,
          exception.getMessage());
      return false;
    }
  }

  private long parseSecondsOrMinusOne(String expression) {
    if (expression == null || expression.isBlank()) {
      return -1L;
    }
    try {
      return Long.parseLong(expression.trim());
    } catch (NumberFormatException ignored) {
      return -1L;
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
