package io.github.pinpols.batch.trigger.infrastructure;

import io.github.pinpols.batch.common.enums.CatchUpPolicyType;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.trigger.config.TriggerRuntimeProperties;
import io.github.pinpols.batch.trigger.service.ScheduledOccurrenceRecorder;
import io.github.pinpols.batch.trigger.support.TriggerDescriptor;
import java.time.Instant;
import java.util.Date;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobKey;
import org.quartz.ObjectAlreadyExistsException;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.quartz.TriggerListener;
import org.quartz.impl.matchers.GroupMatcher;
import org.springframework.scheduling.TaskScheduler;

/**
 * 有界、短时的本地缓存，使关系变更沿用现有事务提交后
 *
 * <p>The primary cron trigger keeps {@code DO_NOTHING}, so Quartz advances to the next cron time
 * Workflow 缓存失效路径，而不引入另一套 Redis 键生命周期。
 * 继续开启今天的业务日期，同时让较早积压日期和游标保持不变。
 * 保留其原始触发时间，并交由 {@link QuartzLaunchJob} 按现有补扫策略处理。
 */
@Slf4j
@RequiredArgsConstructor
public class QuartzMisfireRecoveryListener implements TriggerListener {

  static final String NAME = "quartzMisfireRecoveryListener";
  static final String RECOVERY_GROUP = "batch-trigger-recovery";
  private static final int RECOVERY_BATCH_SIZE = 100;

  private final Supplier<Scheduler> schedulerSupplier;
  private final TaskScheduler taskScheduler;
  private final ScheduledOccurrenceRecorder occurrenceRecorder;
  private final TriggerRuntimeProperties triggerRuntimeProperties;

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public void triggerFired(Trigger trigger, JobExecutionContext context) {
    // 无需执行实际操作：只有 misfire 事件需要安排恢复任务。
  }

  @Override
  public boolean vetoJobExecution(Trigger trigger, JobExecutionContext context) {
    return false;
  }

  @Override
  public void triggerMisfired(Trigger trigger) {
    if (!TriggerSchedulerFacade.JOB_GROUP.equals(trigger.getKey().getGroup())) {
      return;
    }
    Date originalFireTime = trigger.getNextFireTime();
    if (EmptyChecks.isNull(originalFireTime)) {
      originalFireTime = trigger.getPreviousFireTime();
      if (EmptyChecks.isNull(originalFireTime)) {
        log.warn(
            "skipping Quartz misfire recovery because original fire time is unavailable:"
                + " triggerKey={}, jobKey={}",
            trigger.getKey(),
            trigger.getJobKey());
        return;
      }
      log.warn(
          "Quartz misfire recovery using previousFireTime fallback: triggerKey={}, jobKey={},"
              + " previousFireTime={}",
          trigger.getKey(),
          trigger.getJobKey(),
          originalFireTime);
    }
    TriggerKey recoveryTriggerKey = trigger.getKey();
    JobKey recoveryJobKey = trigger.getJobKey();
    long recoveryOriginalFireTime = originalFireTime.getTime();
    JobDataMap data = trigger.getJobDataMap();
    String tenantId = data.getString(QuartzLaunchJob.TENANT_ID);
    Long workflowDefinitionId = data.containsKey(QuartzLaunchJob.WORKFLOW_DEFINITION_ID)
        ? data.getLong(QuartzLaunchJob.WORKFLOW_DEFINITION_ID)
        : null;
    if (EmptyChecks.isBlank(tenantId) || EmptyChecks.isNull(workflowDefinitionId)) {
      log.error(
          "cannot persist Quartz misfire without Workflow identity: triggerKey={}, jobKey={}",
          recoveryTriggerKey,
          recoveryJobKey);
      return;
    }
    boolean persisted = occurrenceRecorder.markMisfirePending(
        tenantId, workflowDefinitionId, originalFireTime.toInstant());
    if (!persisted) {
      log.error(
          "cannot recover Quartz misfire without a matching planned occurrence: tenantId={},"
              + " workflowDefinitionId={}, scheduledFireTime={}",
          tenantId,
          workflowDefinitionId,
          originalFireTime.toInstant());
      return;
    }
    // Quartz 在持有 JobStore 事务锁时同步回调 triggerMisfired。若回调内再次调用
    // scheduleJob，会等待自己尚未提交的 QRTZ_LOCKS，原回调又因此无法返回，形成自锁。
    // 先把恢复义务写进 occurrence 台账，再切换到锁外线程注册 Quartz trigger；若进程此时退出，
    // TriggerReconciler 会从 MISFIRE_PENDING 台账重新创建该 trigger。
    taskScheduler.schedule(
        () -> scheduleRecovery(
            recoveryTriggerKey, recoveryJobKey, workflowDefinitionId, recoveryOriginalFireTime),
        Instant.now());
  }

  public void restorePendingMisfires(
      String tenantId, Long workflowDefinitionId, String workflowCode) {
    restorePendingMisfires(
        tenantId,
        workflowDefinitionId,
        JobKey.jobKey(tenantId + ":WF:" + workflowCode, TriggerSchedulerFacade.JOB_GROUP));
  }

  public void restorePendingMisfires(String tenantId, Long workflowDefinitionId, JobKey jobKey) {
    for (Instant fireTime :
        occurrenceRecorder.pendingMisfires(tenantId, workflowDefinitionId, RECOVERY_BATCH_SIZE)) {
      scheduleRecovery(null, jobKey, workflowDefinitionId, fireTime.toEpochMilli());
    }
  }

  /**
   * 终态日历决策前，请求已先取得共享围栏。将该日期保留在
   * 游标位置，待请求启动或进入终态后由后续扫描结算。
   * 灾难恢复覆盖规则必须优先于普通日历节假日规则。
   */
  public void restorePendingMisfires(TriggerDescriptor descriptor, JobKey jobKey) {
    CatchUpPolicyType catchUpPolicy = CatchUpPolicyType.fromCode(descriptor.getCatchUpPolicy());
    boolean automaticCatchUpEnabled = catchUpPolicy == CatchUpPolicyType.AUTO;
    if (automaticCatchUpEnabled) {
      long graceSeconds =
          Math.max(0L, triggerRuntimeProperties.getMisfireCatchUpThresholdSeconds());
      Instant recoveryCutoff = Instant.now().minusSeconds(graceSeconds);
      occurrenceRecorder.recoverUnobservedPlannedFires(
          descriptor.getTenantId(),
          descriptor.getWorkflowDefinitionId(),
          EmptyChecks.isNull(descriptor.getWorkflowVersion()) ? 0 : descriptor.getWorkflowVersion(),
          recoveryCutoff,
          RECOVERY_BATCH_SIZE);
    }
    restorePendingMisfires(descriptor.getTenantId(), descriptor.getWorkflowDefinitionId(), jobKey);
  }

  private void scheduleRecovery(
      TriggerKey triggerKey, JobKey jobKey, Long workflowDefinitionId, long originalFireTime) {
    String recoveryName = "misfire-recovery-" + workflowDefinitionId + "-" + originalFireTime;
    TriggerKey recoveryKey = TriggerKey.triggerKey(recoveryName, RECOVERY_GROUP);
    Scheduler scheduler = schedulerSupplier.get();
    try {
      JobKey durableJobKey = ensureDurableRecoveryJob(scheduler, jobKey, workflowDefinitionId);
      if (EmptyChecks.isNull(durableJobKey)) {
        return;
      }
      JobDetail source = scheduler.getJobDetail(jobKey);
      if (EmptyChecks.isNull(source)) {
        return;
      }
      // 共享恢复 Job 可以承载多个版本；每个 fire 必须覆盖全部原描述，避免继承其它 fire 的元数据。
      JobDataMap recoveryData = new JobDataMap(source.getJobDataMap());
      recoveryData.put(QuartzLaunchJob.MISFIRE_ORIGINAL_FIRE_TIME, originalFireTime);
      Trigger recovery = TriggerBuilder.newTrigger()
          .withIdentity(recoveryKey)
          .forJob(durableJobKey)
          .usingJobData(recoveryData)
          .startNow()
          .withSchedule(
              SimpleScheduleBuilder.simpleSchedule().withMisfireHandlingInstructionFireNow())
          .build();
      scheduler.resumeTriggers(GroupMatcher.triggerGroupEquals(RECOVERY_GROUP));
      if (scheduler.checkExists(recoveryKey)) {
        Trigger existing = scheduler.getTrigger(recoveryKey);
        if (EmptyChecks.isNotNull(existing)) {
          JobKey existingJobKey = existing.getJobKey();
          if (durableJobKey.equals(existingJobKey)) {
            scheduler.resumeTrigger(recoveryKey);
            return;
          }
          JobDataMap reboundData = new JobDataMap(recoveryData);
          reboundData.putAll(existing.getJobDataMap());
          // TriggerBuilder 合并时保留已有值；从空 builder 构建，避免新版模板覆盖旧 fire 的快照。
          Trigger rebound = TriggerBuilder.newTrigger()
              .withIdentity(recoveryKey)
              .forJob(durableJobKey)
              .usingJobData(reboundData)
              .startNow()
              .withSchedule(
                  SimpleScheduleBuilder.simpleSchedule().withMisfireHandlingInstructionFireNow())
              .build();
          Date nextFireTime = scheduler.rescheduleJob(recoveryKey, rebound);
          if (EmptyChecks.isNotNull(nextFireTime)) {
            scheduler.resumeTrigger(recoveryKey);
            log.info(
                "rebound Quartz misfire recovery to durable JobDetail: triggerKey={},"
                    + " previousJobKey={}, jobKey={}, workflowDefinitionId={}, originalFireTime={}",
                recoveryKey,
                existingJobKey,
                durableJobKey,
                workflowDefinitionId,
                originalFireTime);
            return;
          }
        }
      }
      scheduler.scheduleJob(recovery);
      scheduler.resumeTrigger(recovery.getKey());
      log.info(
          "scheduled Quartz misfire recovery: triggerKey={}, jobKey={}, workflowDefinitionId={},"
              + " originalFireTime={}",
          triggerKey,
          durableJobKey,
          workflowDefinitionId,
          originalFireTime);
    } catch (ObjectAlreadyExistsException exception) {
      log.debug(
          "Quartz misfire recovery already registered by another scheduler: triggerKey={}",
          recoveryKey);
    } catch (SchedulerException exception) {
      try {
        Trigger existing = scheduler.getTrigger(recoveryKey);
        JobKey durableJobKey = ensureDurableRecoveryJob(scheduler, jobKey, workflowDefinitionId);
        boolean existingMisfireUsesDurableJobKey = EmptyChecks.isNotNull(durableJobKey)
            && EmptyChecks.isNotNull(existing)
            && (durableJobKey.equals(existing.getJobKey()));
        if (existingMisfireUsesDurableJobKey) {
          scheduler.resumeTrigger(recoveryKey);
          log.debug(
              "Quartz misfire recovery already registered by a concurrent reconciler:"
                  + " triggerKey={}, jobKey={}",
              recoveryKey,
              existing.getJobKey());
          return;
        }
      } catch (SchedulerException lookupException) {
        exception.addSuppressed(lookupException);
      }
      log.error(
          "failed to schedule Quartz misfire recovery: triggerKey={}, jobKey={}",
          triggerKey,
          jobKey,
          exception);
    }
  }

  private JobKey ensureDurableRecoveryJob(
      Scheduler scheduler, JobKey sourceJobKey, Long workflowDefinitionId)
      throws SchedulerException {
    if (isDedicatedRecoveryJob(sourceJobKey)) {
      return sourceJobKey;
    }
    JobDetail sourceJob = scheduler.getJobDetail(sourceJobKey);
    if (EmptyChecks.isNull(sourceJob)) {
      log.warn(
          "cannot register misfire recovery without source Workflow JobDetail: jobKey={},"
              + " workflowDefinitionId={}",
          sourceJobKey,
          workflowDefinitionId);
      return null;
    }
    String tenantId = sourceJob.getJobDataMap().getString(QuartzLaunchJob.TENANT_ID);
    if (EmptyChecks.isBlank(tenantId) || EmptyChecks.isNull(workflowDefinitionId)) {
      log.error(
          "cannot register durable misfire recovery without Workflow identity: jobKey={},"
              + " tenantId={}, workflowDefinitionId={}",
          sourceJobKey,
          tenantId,
          workflowDefinitionId);
      return null;
    }
    JobKey recoveryJobKey =
        JobKey.jobKey(tenantId + ":RECOVERY:" + workflowDefinitionId, sourceJobKey.getGroup());
    if (!scheduler.checkExists(recoveryJobKey)) {
      JobDetail recoveryJob =
          sourceJob.getJobBuilder().withIdentity(recoveryJobKey).storeDurably().build();
      try {
        scheduler.addJob(recoveryJob, false);
      } catch (ObjectAlreadyExistsException alreadyRegistered) {
        if (!scheduler.checkExists(recoveryJobKey)) {
          throw alreadyRegistered;
        }
      }
    }
    return recoveryJobKey;
  }

  private boolean isDedicatedRecoveryJob(JobKey jobKey) {
    return EmptyChecks.isNotNull(jobKey) && jobKey.getName().contains(":RECOVERY:");
  }

  @Override
  public void triggerComplete(
      Trigger trigger,
      JobExecutionContext context,
      Trigger.CompletedExecutionInstruction triggerInstructionCode) {
    // 无需执行实际操作：完成事件不会影响 misfire 恢复。
  }
}
