package io.github.pinpols.batch.trigger.infrastructure;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.util.Date;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.JobExecutionContext;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerListener;
import org.quartz.impl.matchers.GroupMatcher;
import org.springframework.scheduling.TaskScheduler;

/**
 * Converts a Quartz cron misfire into one business-level recovery execution.
 *
 * <p>The primary cron trigger keeps {@code DO_NOTHING}, so Quartz advances to the next cron time
 * without replaying an outage-sized burst. This listener preserves the missed fire time in a
 * one-shot trigger, allowing {@link QuartzLaunchJob} to apply NONE / AUTO / MANUAL_APPROVAL policy.
 */
@Slf4j
@RequiredArgsConstructor
public class QuartzMisfireRecoveryListener implements TriggerListener {

  static final String NAME = "quartzMisfireRecoveryListener";
  static final String RECOVERY_GROUP = "batch-trigger-recovery";

  private final Supplier<Scheduler> schedulerSupplier;
  private final TaskScheduler taskScheduler;

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public void triggerFired(Trigger trigger, JobExecutionContext context) {
    // no-op: only misfire events need recovery scheduling.
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
    var recoveryTriggerKey = trigger.getKey();
    var recoveryJobKey = trigger.getJobKey();
    long recoveryOriginalFireTime = originalFireTime.getTime();
    // Quartz 在持有 JobStore 事务锁时同步回调 triggerMisfired。若回调内再次调用
    // scheduleJob，会等待自己尚未提交的 QRTZ_LOCKS，原回调又因此无法返回，形成自锁。
    // 切换到 Spring 调度线程后，回调先退出并释放 Quartz 事务；任务会在其后完成补偿注册。
    taskScheduler.schedule(
        () -> scheduleRecovery(recoveryTriggerKey, recoveryJobKey, recoveryOriginalFireTime),
        java.time.Instant.now());
  }

  private void scheduleRecovery(
      org.quartz.TriggerKey triggerKey, org.quartz.JobKey jobKey, long originalFireTime) {
    Trigger recovery = TriggerBuilder.newTrigger()
        .withIdentity("misfire-recovery-" + UUID.randomUUID(), RECOVERY_GROUP)
        .forJob(jobKey)
        .usingJobData(QuartzLaunchJob.MISFIRE_ORIGINAL_FIRE_TIME, originalFireTime)
        .startNow()
        .withSchedule(
            SimpleScheduleBuilder.simpleSchedule().withMisfireHandlingInstructionFireNow())
        .build();
    try {
      Scheduler scheduler = schedulerSupplier.get();
      scheduler.resumeTriggers(GroupMatcher.triggerGroupEquals(RECOVERY_GROUP));
      scheduler.scheduleJob(recovery);
      scheduler.resumeTrigger(recovery.getKey());
      log.info(
          "scheduled Quartz misfire recovery: triggerKey={}, jobKey={}, originalFireTime={}",
          triggerKey,
          jobKey,
          originalFireTime);
    } catch (SchedulerException exception) {
      log.error(
          "failed to schedule Quartz misfire recovery: triggerKey={}, jobKey={}",
          triggerKey,
          jobKey,
          exception);
    }
  }

  @Override
  public void triggerComplete(
      Trigger trigger,
      JobExecutionContext context,
      Trigger.CompletedExecutionInstruction triggerInstructionCode) {
    // no-op: completion events do not affect misfire recovery.
  }
}
