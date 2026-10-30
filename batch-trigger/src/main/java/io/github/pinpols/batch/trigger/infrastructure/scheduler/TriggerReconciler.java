package io.github.pinpols.batch.trigger.infrastructure.scheduler;

import io.github.pinpols.batch.common.enums.ScheduleType;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.trigger.domain.TriggerDefinitionLoader;
import io.github.pinpols.batch.trigger.domain.TriggerRegistrationService;
import io.github.pinpols.batch.trigger.infrastructure.QuartzMisfireRecoveryListener;
import io.github.pinpols.batch.trigger.infrastructure.QuartzScheduledFireEnumerator;
import io.github.pinpols.batch.trigger.infrastructure.TriggerGracefulShutdown;
import io.github.pinpols.batch.trigger.infrastructure.TriggerSchedulerFacade;
import io.github.pinpols.batch.trigger.service.TriggerScheduledOccurrencePlanner;
import io.github.pinpols.batch.trigger.support.TriggerDescriptor;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.quartz.CronTrigger;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleTrigger;
import org.quartz.Trigger;
import org.quartz.impl.matchers.GroupMatcher;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定期对账 Workflow 权威调度配置与 Quartz JobStore 执行态，使两者保持一致。
 *
 * <p><b>权威源</b>：启用的 Workflow（包括没有单作业关联的组合 Workflow），其 Workflow 自身的
 * {@code schedule_type/schedule_expr/timezone/trigger_mode/schedule_enabled} 决定 Quartz 注册；Workflow 和关联 Job 仍须启用，
 * Job enabled 作为执行准入，而 schedule_enabled 独立表达暂停调度。组合 Workflow 没有关联 Job 时仍可定时触发；若关联 Job 已禁用，则不注册。
 *
 * <p><b>对账规则</b>：
 *
 * <ul>
 *   <li>DB 有 + Quartz 无 → {@link TriggerRegistrationService#registerByWorkflowCode} 注册
 *   <li>DB 无 + Quartz 有 → {@link TriggerRegistrationService#removeQuartzJob} 清理 Quartz 残留，不改权威启停状态
 *   <li>DB 有 + Quartz 有 且 schedule drift（cron / interval / timezone 不一致）→ 触发 {@code
 *       registerByWorkflowCode}，只替换主 trigger；仅版本/依赖等元数据变化则更新 JobDetail，保留原相位和辅助回调
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
@RequiredArgsConstructor
public class TriggerReconciler {

  private final TriggerDefinitionLoader triggerDefinitionLoader;
  private final TriggerRegistrationService triggerRegistrationService;
  private final Scheduler scheduler;
  private final TriggerGracefulShutdown gracefulShutdown;
  private final TriggerScheduledOccurrencePlanner occurrencePlanner;
  private final QuartzMisfireRecoveryListener misfireRecoveryListener;

  @EventListener(ApplicationReadyEvent.class)
  @SchedulerLock(name = "trigger_reconciler", lockAtMostFor = "PT5M", lockAtLeastFor = "PT10S")
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
      SwallowedExceptionLogger.warn(
          TriggerReconciler.class, "trigger-reconcile-pass-failed", exception);
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
      JobKey key = JobKey.jobKey(
          TriggerSchedulerFacade.quartzIdentity(descriptor), TriggerSchedulerFacade.JOB_GROUP);
      expectedJobs.add(key);
      if (!quartzJobs.contains(key)) {
        triggerRegistrationService.reconcileByWorkflowCode(
            descriptor.getTenantId(), descriptor.getWorkflowCode());
        registered++;
      } else if (hasScheduleDrift(key, descriptor)) {
        log.info(
            "trigger schedule drift detected, re-registering: jobKey={}, newExpr={}, tz={}",
            key,
            descriptor.getScheduleExpression(),
            descriptor.getTimezone());
        triggerRegistrationService.reconcileByWorkflowCode(
            descriptor.getTenantId(), descriptor.getWorkflowCode());
        replaced++;
      }
      materializeUpcomingOccurrences(key, descriptor);
      misfireRecoveryListener.restorePendingMisfires(descriptor, key);
    }
    // 在当前业务日期之后重新检查已持久化日期；截止时间到达时，存储日期不会漂移。
    // 创建业务日记录或其义务前，先获取与结算相同的事务围栏，
    List<TriggerDescriptor> pendingMisfireDefinitions =
        triggerDefinitionLoader.loadPendingMisfireDefinitions();
    for (TriggerDescriptor descriptor : pendingMisfireDefinitions) {
      JobKey key = TriggerSchedulerFacade.recoveryJobKey(descriptor);
      expectedJobs.add(key);
      triggerRegistrationService.registerRecoveryJob(descriptor);
    }
    int unregistered = 0;
    for (JobKey key : quartzJobs) {
      if (expectedJobs.contains(key)) {
        continue;
      }
      if (key.getName().contains(":RECOVERY:") && hasAttachedReadinessRetry(key)) {
        continue;
      }
      String[] parts = key.getName().split(":", 2);
      if (parts.length != 2) {
        log.warn("trigger reconciler skipping malformed quartz JobKey: {}", key);
        continue;
      }
      triggerRegistrationService.removeQuartzJob(parts[0], parts[1]);
      if (parts[1].startsWith("WF:")) {
        String workflowCode = parts[1].substring("WF:".length());
        occurrencePlanner.supersedeFuturePlans(parts[0], workflowCode, Instant.now());
      } else if (!parts[1].startsWith("RECOVERY:")) {
        log.warn(
            "trigger reconciler removed a non-Workflow Quartz identity without ledger cleanup: {}",
            key);
      }
      unregistered++;
    }
    // 使开日和最终结算遵循同一加锁顺序。
    // 将终态日历跳过与 Trigger 准入串行化，并保留已
    for (TriggerDescriptor descriptor : pendingMisfireDefinitions) {
      JobKey recoveryJobKey = TriggerSchedulerFacade.recoveryJobKey(descriptor);
      misfireRecoveryListener.restorePendingMisfires(
          descriptor.getTenantId(), descriptor.getWorkflowDefinitionId(), recoveryJobKey);
    }
    boolean triggerReconciliationChangedRegistrations =
        registered > 0 || unregistered > 0 || replaced > 0;
    if (triggerReconciliationChangedRegistrations) {
      log.info(
          "trigger reconcile drift resolved: registered={}, replaced={}, unregistered={},"
              + " expectedTotal={}",
          registered,
          replaced,
          unregistered,
          expectedJobs.size());
    }
  }

  private void materializeUpcomingOccurrences(JobKey key, TriggerDescriptor descriptor) {
    try {
      List<? extends Trigger> triggers = scheduler.getTriggersOfJob(key);
      Trigger primary = primaryTrigger(key, triggers);
      if (EmptyChecks.isNull(primary)) {
        return;
      }
      occurrencePlanner.materialize(
          descriptor, QuartzScheduledFireEnumerator.fireTimeSource(primary), Instant.now());
    } catch (SchedulerException | RuntimeException exception) {
      SwallowedExceptionLogger.warn(
          TriggerReconciler.class, "scheduled-occurrence-materialization-failed", exception);
    }
  }

  /**
   * 检查 Quartz 里的 Trigger 与 DB descriptor 的 schedule 是否漂移： CRON 比 cronExpression +
   * timezone；FIXED_RATE 比 repeat interval（秒）。 用于 DB-Quartz 都存在但表达式/时区被人改了 DB 的情况——本方法返回 true 会触发
   * re-register 更新主日程；表达式未变时仍核对版本、依赖等完整执行元数据。
   */
  private boolean hasScheduleDrift(JobKey key, TriggerDescriptor descriptor) {
    String type = descriptor.getScheduleType();
    if (EmptyChecks.isBlank(type)) {
      // descriptor 没声明 schedule_type（异常数据 / 旧测试 fixture）→ 保守不判 drift
      return false;
    }
    try {
      List<? extends Trigger> triggers = scheduler.getTriggersOfJob(key);
      if (EmptyChecks.isEmpty(triggers)) {
        // Quartz 里有 jobKey 但没 trigger（罕见）→ 保守不判 drift，下一轮再说
        return false;
      }
      Trigger quartzTrigger = primaryTrigger(key, triggers);
      if (EmptyChecks.isNull(quartzTrigger)) {
        // 同一个 Job 可能临时挂 recovery/readiness retry Trigger。不能用第一条辅助 Trigger
        // 判断主调度漂移，否则会 delete-and-add 整个 Job，连恢复 Trigger 一起删掉。
        return false;
      }
      ScheduleDriftChecker checker = driftCheckers().get(type.toUpperCase(Locale.ROOT));
      if (EmptyChecks.isNotNull(checker) && checker.hasDrift(quartzTrigger, descriptor)) {
        return true;
      }
      JobDetail storedJob = scheduler.getJobDetail(key);
      if (EmptyChecks.isNull(storedJob)) {
        return true;
      }
      JobDataMap expectedData = TriggerSchedulerFacade.scheduledJobData(descriptor);
      return !expectedData.equals(storedJob.getJobDataMap());
    } catch (SchedulerException exception) {
      log.warn(
          "failed to inspect quartz trigger for drift check: key={}, reason={}",
          key,
          SwallowedExceptionLogger.summary(exception));
      return false;
    }
  }

  private boolean hasAttachedReadinessRetry(JobKey jobKey) {
    try {
      List<? extends Trigger> attached = scheduler.getTriggersOfJob(jobKey);
      for (Trigger trigger : attached) {
        if (TriggerSchedulerFacade.isReadinessRetry(trigger)) {
          return true;
        }
      }
      return false;
    } catch (SchedulerException failure) {
      throw new IllegalStateException("failed to inspect recovery readiness triggers", failure);
    }
  }

  private Map<String, ScheduleDriftChecker> driftCheckers() {
    return Map.of(
        ScheduleType.CRON.code(), this::hasCronScheduleDrift,
        ScheduleType.FIXED_RATE.code(), this::hasFixedRateScheduleDrift);
  }

  private boolean hasCronScheduleDrift(Trigger quartzTrigger, TriggerDescriptor descriptor) {
    if (!(quartzTrigger instanceof CronTrigger ct)) {
      return true;
    }
    if (!Objects.equals(ct.getCronExpression(), descriptor.getScheduleExpression())) {
      return true;
    }
    String quartzTz =
        EmptyChecks.isNull(ct.getTimeZone()) ? null : ct.getTimeZone().getID();
    return !Objects.equals(quartzTz, descriptor.getTimezone());
  }

  private boolean hasFixedRateScheduleDrift(Trigger quartzTrigger, TriggerDescriptor descriptor) {
    if (!(quartzTrigger instanceof SimpleTrigger st)) {
      return true;
    }
    long expectedMillis = parseSecondsOrMinusOne(descriptor.getScheduleExpression()) * 1000L;
    return expectedMillis > 0 && st.getRepeatInterval() != expectedMillis;
  }

  @FunctionalInterface
  private interface ScheduleDriftChecker {
    boolean hasDrift(Trigger quartzTrigger, TriggerDescriptor descriptor);
  }

  private Trigger primaryTrigger(JobKey key, List<? extends Trigger> triggers) {
    for (Trigger trigger : triggers) {
      if (key.getName().equals(trigger.getKey().getName())
          && TriggerSchedulerFacade.JOB_GROUP.equals(trigger.getKey().getGroup())) {
        return trigger;
      }
    }
    return null;
  }

  private long parseSecondsOrMinusOne(String expression) {
    if (EmptyChecks.isBlank(expression)) {
      return -1L;
    }
    try {
      String trimmedExpression = expression.trim();
      return Long.parseLong(trimmedExpression);
    } catch (NumberFormatException ignored) {
      SwallowedExceptionLogger.info(
          TriggerReconciler.class, "catch:NumberFormatException", ignored);

      return -1L;
    }
  }

  private Set<JobKey> loadQuartzJobKeys() {
    try {
      GroupMatcher<JobKey> quartzJobGroup =
          GroupMatcher.jobGroupEquals(TriggerSchedulerFacade.JOB_GROUP);
      Set<JobKey> quartzJobKeys = scheduler.getJobKeys(quartzJobGroup);
      return Set.copyOf(quartzJobKeys);
    } catch (SchedulerException exception) {
      throw new IllegalStateException("failed to list quartz job keys", exception);
    }
  }
}
