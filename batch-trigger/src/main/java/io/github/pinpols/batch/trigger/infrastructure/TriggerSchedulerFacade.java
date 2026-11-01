package io.github.pinpols.batch.trigger.infrastructure;

import io.github.pinpols.batch.common.enums.ScheduleType;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.trigger.domain.TriggerDefinitionLoader;
import io.github.pinpols.batch.trigger.domain.TriggerRegistrationService;
import io.github.pinpols.batch.trigger.support.TriggerDescriptor;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.quartz.CronExpression;
import org.quartz.CronScheduleBuilder;
import org.quartz.CronTrigger;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.ObjectAlreadyExistsException;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.SimpleTrigger;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.impl.matchers.GroupMatcher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Quartz 调度器门面，实现 {@link TriggerRegistrationService} 接口，集中管理所有 Quartz job 的 注册、暂停、恢复和状态查询。
 *
 * <p><b>JobKey 格式</b>：所有调度任务使用 {@code tenantId:WF:workflowCode}。
 * group 固定为 {@code batch-trigger}；{@link #pauseByTenant}/{@link #resumeByTenant} 通过租户前缀实现批量操作。
 *
 * <p><b>调度类型支持</b>：
 *
 * <ul>
 *   <li>CRON — 使用 {@code withMisfireHandlingInstructionDoNothing}：Quartz 层不补跑， 错失触发由 {@link
 *       QuartzLaunchJob} 在执行时根据 drift 自行决策 catch-up 策略。
 *   <li>FIXED_RATE — 使用 {@code withMisfireHandlingInstructionNextWithExistingCount}：
 *       只补跑丢失的次数，不集中补跑过量触发。
 *   <li>EVENT / MANUAL — 静默跳过，无需 Quartz 注册（由外部事件或人工 API 触发）。
 * </ul>
 *
 * <p><b>幂等注册</b>：{@link #scheduleWithReplace} 更新主日程时保留辅助恢复回调；
 * 仅元数据变化时只更新 JobDetail，保留固定频率相位和下一次 fire。{@link #registerAll} 持有 ShedLock ({@code lockAtMostFor=PT15M}) 防止多实例并发重复注册。
 */
@Slf4j
@Service
public class TriggerSchedulerFacade implements TriggerRegistrationService {

  public static final String JOB_GROUP = "batch-trigger";
  private static final Duration DEFAULT_MANAGEMENT_LOCK_TIMEOUT = Duration.ofSeconds(5);

  private final TriggerDefinitionLoader triggerDefinitionLoader;
  private final Scheduler scheduler;
  private final Duration managementLockTimeout;
  private final ReentrantLock managementLock = new ReentrantLock(true);

  @Autowired
  public TriggerSchedulerFacade(
      TriggerDefinitionLoader triggerDefinitionLoader, Scheduler scheduler) {
    this(triggerDefinitionLoader, scheduler, DEFAULT_MANAGEMENT_LOCK_TIMEOUT);
  }

  TriggerSchedulerFacade(
      TriggerDefinitionLoader triggerDefinitionLoader,
      Scheduler scheduler,
      Duration managementLockTimeout) {
    this.triggerDefinitionLoader = triggerDefinitionLoader;
    this.scheduler = scheduler;
    this.managementLockTimeout = managementLockTimeout;
  }

  @Override
  // M-13: lockAtMostFor 从 PT5M 增加到 PT15M，大集群枚举注册所有触发器可能超过 5 分钟
  @SchedulerLock(name = "trigger_register_all", lockAtMostFor = "PT15M", lockAtLeastFor = "PT10S")
  public void registerAll() {
    runManaged("registerAll", () -> {
      List<TriggerDescriptor> descriptors = triggerDefinitionLoader.loadAll();
      // 调度注册具有外部副作用，显式顺序执行，便于保留失败位置和逐项调试语义。
      for (TriggerDescriptor descriptor : descriptors) {
        if (descriptor.isEnabled()) {
          scheduleDescriptor(descriptor);
        }
      }
    });
  }

  @Override
  public void registerRecoveryJob(TriggerDescriptor descriptor) {
    runManaged("registerRecoveryJob", () -> {
      JobDetail job = buildJobDetail(descriptor)
          .getJobBuilder()
          .withIdentity(recoveryJobKey(descriptor))
          .build();
      try {
        // 每条恢复 trigger 保存自己的元数据；共享 JobDetail 只作为后续恢复注册的当前模板。
        scheduler.addJob(job, true);
      } catch (ObjectAlreadyExistsException alreadyRegistered) {
        log.debug("recovery job registered concurrently: {}", job.getKey());
      } catch (SchedulerException exception) {
        throw new IllegalStateException("failed to register Quartz recovery job", exception);
      }
    });
  }

  @Override
  public void registerByWorkflowCode(String tenantId, String workflowCode) {
    runManaged("registerByWorkflowCode", () -> {
      triggerDefinitionLoader.setWorkflowScheduleEnabled(tenantId, workflowCode, true);
      TriggerDescriptor descriptor =
          triggerDefinitionLoader.loadByWorkflowCode(tenantId, workflowCode);
      if (EmptyChecks.isNotNull(descriptor) && descriptor.isEnabled()) {
        scheduleDescriptor(descriptor);
      }
    });
  }

  @Override
  public void reconcileByWorkflowCode(String tenantId, String workflowCode) {
    runManaged("reconcileByWorkflowCode", () -> {
      TriggerDescriptor descriptor =
          triggerDefinitionLoader.loadByWorkflowCode(tenantId, workflowCode);
      if (EmptyChecks.isNull(descriptor) || !descriptor.isEnabled()) {
        removeQuartzByIdentity(tenantId, "WF:" + workflowCode);
        return;
      }
      scheduleDescriptor(descriptor);
    });
  }

  @Override
  public void unregisterByWorkflowCode(String tenantId, String workflowCode) {
    runManaged("unregisterByWorkflowCode", () -> {
      deleteQuartzForDescriptor(triggerDefinitionLoader.loadByWorkflowCode(tenantId, workflowCode));
      triggerDefinitionLoader.setWorkflowScheduleEnabled(tenantId, workflowCode, false);
    });
  }

  @Override
  public void removeQuartzJob(String tenantId, String quartzJobIdentity) {
    runManaged("removeQuartzJob", () -> removeQuartzByIdentity(tenantId, quartzJobIdentity));
  }

  @Override
  public void pauseByWorkflowCode(String tenantId, String workflowCode) {
    runManaged("pauseByWorkflowCode", () -> {
      deleteQuartzForDescriptor(triggerDefinitionLoader.loadByWorkflowCode(tenantId, workflowCode));
      triggerDefinitionLoader.setWorkflowScheduleEnabled(tenantId, workflowCode, false);
    });
  }

  @Override
  public void resumeByWorkflowCode(String tenantId, String workflowCode) {
    runManaged("resumeByWorkflowCode", () -> {
      triggerDefinitionLoader.setWorkflowScheduleEnabled(tenantId, workflowCode, true);
      TriggerDescriptor descriptor =
          triggerDefinitionLoader.loadByWorkflowCode(tenantId, workflowCode);
      if (EmptyChecks.isNotNull(descriptor) && descriptor.isEnabled()) {
        scheduleDescriptor(descriptor);
      }
    });
  }

  @Override
  public void pauseByTenant(String tenantId) {
    runManaged("pauseByTenant", () -> {
      try {
        String prefix = tenantId + ":";
        for (JobKey jobKey : scheduler.getJobKeys(GroupMatcher.jobGroupEquals(JOB_GROUP))) {
          if (jobKey.getName().startsWith(prefix)) {
            scheduler.pauseJob(jobKey);
          }
        }
      } catch (SchedulerException e) {
        throw new IllegalStateException("failed to pause triggers for tenant: " + tenantId, e);
      }
    });
  }

  @Override
  public void resumeByTenant(String tenantId) {
    runManaged("resumeByTenant", () -> {
      try {
        String prefix = tenantId + ":";
        for (JobKey jobKey : scheduler.getJobKeys(GroupMatcher.jobGroupEquals(JOB_GROUP))) {
          if (jobKey.getName().startsWith(prefix)) {
            scheduler.resumeJob(jobKey);
          }
        }
      } catch (SchedulerException e) {
        throw new IllegalStateException("failed to resume triggers for tenant: " + tenantId, e);
      }
    });
  }

  @Override
  public void pauseAll() {
    runManaged("pauseAll", () -> {
      try {
        scheduler.pauseAll();
      } catch (SchedulerException e) {
        throw new IllegalStateException("failed to pause all triggers", e);
      }
    });
  }

  @Override
  public void resumeAll() {
    runManaged("resumeAll", () -> {
      try {
        scheduler.resumeAll();
      } catch (SchedulerException e) {
        throw new IllegalStateException("failed to resume all triggers", e);
      }
    });
  }

  /**
   * R7-A5: 用 ScheduleType.code() 替代字面量 + Map 路由替代 if-chain（docs/agent-baseline.md §分支消除 + §领域字典）。 EVENT / MANUAL
   * 不在 map 内即静默跳过（无 Quartz 注册）。
   */
  private final Map<String, Consumer<TriggerDescriptor>> scheduleHandlers = Map.of(
      ScheduleType.CRON.code(), this::scheduleCronDescriptor,
      ScheduleType.FIXED_RATE.code(), this::scheduleFixedRateDescriptor);

  private void scheduleDescriptor(TriggerDescriptor descriptor) {
    try {
      String scheduleType = descriptor.getScheduleType();
      if (EmptyChecks.isNotNull(scheduleType)) {
        Consumer<TriggerDescriptor> handler =
            scheduleHandlers.get(scheduleType.toUpperCase(Locale.ROOT));
        if (EmptyChecks.isNotNull(handler)) {
          handler.accept(descriptor);
        }
      }
    } catch (IllegalArgumentException e) {
      log.warn(
          "skipping invalid trigger descriptor for job={}/{}: {}",
          descriptor.getTenantId(),
          descriptor.getWorkflowCode(),
          SwallowedExceptionLogger.summary(e));
    }
  }

  private void scheduleCronDescriptor(TriggerDescriptor descriptor) {
    String expression = descriptor.getScheduleExpression();
    if (EmptyChecks.isBlank(expression) || !CronExpression.isValidExpression(expression)) {
      throw new IllegalArgumentException(
          "invalid cron expression for job " + descriptor.getJobCode() + ": '" + expression + "'");
    }
    // Linux 5 字段 cron（"分 时 日 月 星期"）在 Quartz 下会被当 6 字段 "秒 分 时 日 月 星期" 解析，
    // 语义严重错位：如 "0 1 * * *" 原意每日 01:00，Quartz 却读成"每小时第 1 分 0 秒触发"。
    // Quartz 标准 cron 必须 6（无年）或 7（有年）字段，此处硬校验，拒绝所有 Linux-cron 误用。
    String trimmedExpression = expression.trim();
    String[] fields = trimmedExpression.split("\\s+");
    int fieldCount = fields.length;
    if (fieldCount < 6 || fieldCount > 7) {
      throw new IllegalArgumentException(
          "cron must be Quartz format (6 or 7 fields: sec min hour day month dow [year]), got "
              + fieldCount
              + " fields for job "
              + descriptor.getJobCode()
              + ": '"
              + expression
              + "'"
              + (fieldCount == 5
                  ? " — Linux 5-field cron is not supported; add a leading '0 ' for seconds"
                  : ""));
    }
    String timezone = descriptor.getTimezone();
    if (EmptyChecks.isNotBlank(timezone)) {
      try {
        ZoneId.of(timezone);
      } catch (DateTimeException e) {
        throw new IllegalArgumentException(
            "invalid timezone for job " + descriptor.getJobCode() + ": '" + timezone + "'", e);
      }
    }
    String identity = quartzIdentity(descriptor);
    JobDetail jobDetail = buildJobDetail(descriptor);
    CronTrigger trigger = TriggerBuilder.newTrigger()
        .withIdentity(identity, JOB_GROUP)
        .forJob(jobDetail)
        .usingJobData(misfireIdentityData(descriptor))
        .withSchedule(CronScheduleBuilder.cronSchedule(expression)
            .inTimeZone(TimeZone.getTimeZone(timezone))
            .withMisfireHandlingInstructionDoNothing())
        .build();
    scheduleWithReplace(jobDetail, trigger);
  }

  private void scheduleFixedRateDescriptor(TriggerDescriptor descriptor) {
    int intervalSeconds = parseFixedRateIntervalSeconds(descriptor);
    String identity = quartzIdentity(descriptor);
    JobDetail jobDetail = buildJobDetail(descriptor);
    Trigger trigger = TriggerBuilder.newTrigger()
        .withIdentity(identity, JOB_GROUP)
        .forJob(jobDetail)
        .usingJobData(misfireIdentityData(descriptor))
        .withSchedule(SimpleScheduleBuilder.simpleSchedule()
            .withIntervalInSeconds(intervalSeconds)
            .repeatForever()
            .withMisfireHandlingInstructionNextWithExistingCount())
        .build();
    scheduleWithReplace(jobDetail, trigger);
  }

  private JobDetail buildJobDetail(TriggerDescriptor descriptor) {
    String identity = quartzIdentity(descriptor);
    JobDataMap jobDataMap = scheduledJobData(descriptor);
    return JobBuilder.newJob(QuartzLaunchJob.class)
        .withIdentity(identity, JOB_GROUP)
        .usingJobData(jobDataMap)
        .storeDurably()
        .build();
  }

  /** 对账与注册复用相同的有效调度载荷，版本和依赖变更不以 cron 变化为前提。 */
  public static JobDataMap scheduledJobData(TriggerDescriptor descriptor) {
    JobDataMap jobDataMap = new JobDataMap();
    jobDataMap.put(QuartzLaunchJob.TENANT_ID, descriptor.getTenantId());
    jobDataMap.put(QuartzLaunchJob.JOB_CODE, descriptor.getJobCode());
    if (EmptyChecks.isNotNull(descriptor.getWorkflowDefinitionId())) {
      jobDataMap.put(QuartzLaunchJob.WORKFLOW_DEFINITION_ID, descriptor.getWorkflowDefinitionId());
    }
    if (EmptyChecks.isNotNull(descriptor.getWorkflowCode())) {
      jobDataMap.put(QuartzLaunchJob.WORKFLOW_CODE, descriptor.getWorkflowCode());
    }
    if (EmptyChecks.isNotNull(descriptor.getWorkflowVersion())) {
      jobDataMap.put(QuartzLaunchJob.WORKFLOW_VERSION, descriptor.getWorkflowVersion());
    }
    jobDataMap.put(
        QuartzLaunchJob.JOB_DEFINITION_VERSION,
        EmptyChecks.isNull(descriptor.getJobDefinitionVersion())
            ? 0
            : descriptor.getJobDefinitionVersion());
    jobDataMap.put(QuartzLaunchJob.SCHEDULE_TYPE, descriptor.getScheduleType());
    jobDataMap.put(QuartzLaunchJob.SCHEDULE_EXPRESSION, descriptor.getScheduleExpression());
    jobDataMap.put(QuartzLaunchJob.TIMEZONE, descriptor.getTimezone());
    jobDataMap.put(QuartzLaunchJob.TRIGGER_MODE, descriptor.getTriggerMode());
    jobDataMap.put(QuartzLaunchJob.CALENDAR_CODE, descriptor.getCalendarCode());
    jobDataMap.put(QuartzLaunchJob.DEPENDS_ON_JOB_CODE, descriptor.getDependsOnJobCode());
    // null 表示使用既有回退语义，空列表表示明确无依赖；两者都必须遮住后续 JobDetail 的新值。
    jobDataMap.put(
        QuartzLaunchJob.DEPENDS_ON_JOB_CODES,
        EmptyChecks.isNull(descriptor.getDependsOnJobCodes())
            ? null
            : new ArrayList<>(descriptor.getDependsOnJobCodes()));
    jobDataMap.put(QuartzLaunchJob.CATCH_UP_POLICY, descriptor.getCatchUpPolicy());
    jobDataMap.put(QuartzLaunchJob.CATCH_UP_MAX_DAYS, descriptor.getCatchUpMaxDays());
    return jobDataMap;
  }

  private JobDataMap misfireIdentityData(TriggerDescriptor descriptor) {
    JobDataMap data = new JobDataMap();
    data.put(QuartzLaunchJob.TENANT_ID, descriptor.getTenantId());
    if (EmptyChecks.isNotNull(descriptor.getWorkflowDefinitionId())) {
      data.put(QuartzLaunchJob.WORKFLOW_DEFINITION_ID, descriptor.getWorkflowDefinitionId());
    }
    return data;
  }

  public static String quartzIdentity(TriggerDescriptor descriptor) {
    if (EmptyChecks.isNull(descriptor)) {
      throw new IllegalArgumentException("Scheduled triggers require a Workflow identity");
    }
    String tenantId = descriptor.getTenantId();
    String workflowCode = descriptor.getWorkflowCode();
    boolean workflowScheduleDescriptorIsIncomplete =
        EmptyChecks.isNull(tenantId) || EmptyChecks.isBlank(workflowCode);
    if (workflowScheduleDescriptorIsIncomplete) {
      throw new IllegalArgumentException("Scheduled triggers require a Workflow identity");
    }
    return tenantId + ":WF:" + workflowCode;
  }

  /** OPEN/CUTOFF，尤其是定时触发按预期跳过该节假日时。 */
  public static JobKey recoveryJobKey(TriggerDescriptor descriptor) {
    return JobKey.jobKey(
        descriptor.getTenantId() + ":RECOVERY:" + descriptor.getWorkflowDefinitionId(), JOB_GROUP);
  }

  private void deleteQuartzForDescriptor(TriggerDescriptor descriptor) {
    if (EmptyChecks.isNull(descriptor)) {
      return;
    }
    try {
      JobKey jobKey = JobKey.jobKey(quartzIdentity(descriptor), JOB_GROUP);
      if (scheduler.checkExists(jobKey)) {
        rebindPendingRecoveryTriggers(jobKey);
        scheduler.deleteJob(jobKey);
      }
    } catch (SchedulerException exception) {
      throw new IllegalStateException(
          "failed to delete Quartz trigger: " + quartzIdentity(descriptor), exception);
    }
  }

  private void rebindPendingRecoveryTriggers(JobKey workflowJobKey) throws SchedulerException {
    JobDetail sourceJob = scheduler.getJobDetail(workflowJobKey);
    if (EmptyChecks.isNull(sourceJob)) {
      return;
    }
    JobDataMap sourceData = sourceJob.getJobDataMap();
    if (!sourceData.containsKey(QuartzLaunchJob.WORKFLOW_DEFINITION_ID)) {
      return;
    }
    List<? extends Trigger> attachedTriggers = scheduler.getTriggersOfJob(workflowJobKey);
    if (EmptyChecks.isEmpty(attachedTriggers)) {
      return;
    }
    String tenantId = sourceData.getString(QuartzLaunchJob.TENANT_ID);
    long workflowDefinitionId = sourceData.getLongValue(QuartzLaunchJob.WORKFLOW_DEFINITION_ID);
    JobKey recoveryJobKey =
        JobKey.jobKey(tenantId + ":RECOVERY:" + workflowDefinitionId, JOB_GROUP);
    if (recoveryJobKey.equals(workflowJobKey)) {
      return;
    }
    JobDetail recoveryJob =
        sourceJob.getJobBuilder().withIdentity(recoveryJobKey).storeDurably().build();
    if (!scheduler.checkExists(recoveryJobKey)) {
      try {
        scheduler.addJob(recoveryJob, false);
      } catch (ObjectAlreadyExistsException alreadyRegistered) {
        if (!scheduler.checkExists(recoveryJobKey)) {
          throw alreadyRegistered;
        }
      }
    }
    for (Trigger trigger : attachedTriggers) {
      boolean misfireRecovery =
          QuartzMisfireRecoveryListener.RECOVERY_GROUP.equals(trigger.getKey().getGroup());
      if (!misfireRecovery && !isReadinessRetry(trigger)) {
        continue;
      }
      JobDataMap frozen = new JobDataMap(sourceData);
      frozen.putAll(trigger.getJobDataMap());
      Trigger rebound = trigger
          .getTriggerBuilder()
          .forJob(recoveryJobKey)
          .usingJobData(frozen)
          .build();
      scheduler.rescheduleJob(trigger.getKey(), rebound);
    }
  }

  /** 标记只用于避免删掉在途回调；持久义务准入仍须由 occurrence 状态裁决。 */
  public static boolean isReadinessRetry(Trigger trigger) {
    return EmptyChecks.isNotNull(trigger)
        && trigger.getJobDataMap().containsKey(QuartzLaunchJob.READINESS_ORIGINAL_FIRE_TIME);
  }

  private void removeQuartzByIdentity(String tenantId, String quartzJobIdentity) {
    try {
      JobKey jobKey = JobKey.jobKey(tenantId + ":" + quartzJobIdentity, JOB_GROUP);
      if (scheduler.checkExists(jobKey)) {
        rebindPendingRecoveryTriggers(jobKey);
        scheduler.deleteJob(jobKey);
      }
    } catch (SchedulerException exception) {
      throw new IllegalStateException(
          "failed to delete Quartz trigger: " + quartzJobIdentity, exception);
    }
  }

  private void scheduleWithReplace(JobDetail jobDetail, Trigger trigger) {
    try {
      if (!scheduler.checkExists(jobDetail.getKey())) {
        scheduler.scheduleJob(jobDetail, trigger);
        return;
      }
      Trigger storedPrimary = scheduler.getTrigger(trigger.getKey());
      boolean primaryIdentityUnchanged = EmptyChecks.isNotNull(storedPrimary)
          && Objects.equals(storedPrimary.getJobDataMap(), trigger.getJobDataMap());
      if (primaryIdentityUnchanged && sameSchedule(storedPrimary, trigger)) {
        // 单独替换定义，保留 FIXED_RATE 相位、nextFireTime 以及辅助重试的调度状态。
        scheduler.addJob(jobDetail, true);
        return;
      }
      // Quartz 在同一 JobStore 操作中替换指定主 trigger；其它辅助 trigger 保留。
      Set<Trigger> primaryTriggers = Set.of(trigger);
      scheduler.scheduleJob(jobDetail, primaryTriggers, true);
    } catch (SchedulerException e) {
      throw new IllegalStateException(
          "failed to register quartz trigger: " + jobDetail.getKey().getName(), e);
    }
  }

  private boolean sameSchedule(Trigger stored, Trigger requested) {
    if (stored instanceof CronTrigger oldCron && requested instanceof CronTrigger newCron) {
      String oldTimezone = oldCron.getTimeZone().getID();
      String newTimezone = newCron.getTimeZone().getID();
      return Objects.equals(oldCron.getCronExpression(), newCron.getCronExpression())
          && Objects.equals(oldTimezone, newTimezone);
    }
    if (stored instanceof SimpleTrigger oldSimple && requested instanceof SimpleTrigger newSimple) {
      return oldSimple.getRepeatInterval() == newSimple.getRepeatInterval()
          && oldSimple.getRepeatCount() == newSimple.getRepeatCount();
    }
    return false;
  }

  private int parseFixedRateIntervalSeconds(TriggerDescriptor descriptor) {
    String expression = descriptor.getScheduleExpression();
    if (EmptyChecks.isBlank(expression)) {
      throw new IllegalArgumentException(
          "FIXED_RATE schedule_expr must be a positive integer (seconds) for job: "
              + descriptor.getJobCode());
    }
    int seconds;
    try {
      String trimmedExpression = expression.trim();
      seconds = Integer.parseInt(trimmedExpression);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(
          "FIXED_RATE schedule_expr must be a positive integer (seconds) for job: "
              + descriptor.getJobCode()
              + ", got: '"
              + expression
              + "'");
    }
    if (seconds <= 0) {
      throw new IllegalArgumentException("FIXED_RATE interval must be > 0 for job: "
          + descriptor.getJobCode()
          + ", got: "
          + seconds);
    }
    return seconds;
  }

  private void runManaged(String operation, Runnable action) {
    callManaged(operation, () -> {
      action.run();
      return null;
    });
  }

  private <T> T callManaged(String operation, Supplier<T> action) {
    boolean locked = false;
    try {
      locked = managementLock.tryLock(managementLockTimeout.toMillis(), TimeUnit.MILLISECONDS);
      if (!locked) {
        throw new TriggerSchedulerBusyException(operation);
      }
      return action.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new TriggerSchedulerBusyException(operation, e);
    } finally {
      if (locked) {
        managementLock.unlock();
      }
    }
  }
}
