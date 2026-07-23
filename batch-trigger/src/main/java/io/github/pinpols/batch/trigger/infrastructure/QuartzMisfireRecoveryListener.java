package io.github.pinpols.batch.trigger.infrastructure;

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

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public void triggerFired(Trigger trigger, JobExecutionContext context) {}

  @Override
  public boolean vetoJobExecution(Trigger trigger, JobExecutionContext context) {
    return false;
  }

  @Override
  public void triggerMisfired(Trigger trigger) {
    if (!TriggerSchedulerFacade.JOB_GROUP.equals(trigger.getKey().getGroup())
        || trigger.getNextFireTime() == null) {
      return;
    }
    Trigger recovery =
        TriggerBuilder.newTrigger()
            .withIdentity("misfire-recovery-" + UUID.randomUUID(), RECOVERY_GROUP)
            .forJob(trigger.getJobKey())
            .usingJobData(
                QuartzLaunchJob.MISFIRE_ORIGINAL_FIRE_TIME, trigger.getNextFireTime().getTime())
            .startNow()
            .withSchedule(
                SimpleScheduleBuilder.simpleSchedule().withMisfireHandlingInstructionFireNow())
            .build();
    try {
      schedulerSupplier.get().scheduleJob(recovery);
      log.info(
          "scheduled Quartz misfire recovery: jobKey={}, originalFireTime={}",
          trigger.getJobKey(),
          trigger.getNextFireTime());
    } catch (SchedulerException exception) {
      log.error(
          "failed to schedule Quartz misfire recovery: jobKey={}", trigger.getJobKey(), exception);
    }
  }

  @Override
  public void triggerComplete(
      Trigger trigger,
      JobExecutionContext context,
      Trigger.CompletedExecutionInstruction triggerInstructionCode) {}
}
