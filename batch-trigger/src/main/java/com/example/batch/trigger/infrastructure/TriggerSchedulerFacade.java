package com.example.batch.trigger.infrastructure;

import com.example.batch.trigger.domain.TriggerDefinitionLoader;
import com.example.batch.trigger.domain.TriggerRegistrationService;
import com.example.batch.trigger.domain.TriggerStatusInfo;
import com.example.batch.trigger.support.TriggerDescriptor;

import lombok.RequiredArgsConstructor;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import org.quartz.CronExpression;
import org.quartz.CronScheduleBuilder;
import org.quartz.CronTrigger;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.impl.matchers.GroupMatcher;
import org.springframework.stereotype.Service;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

@Service
@RequiredArgsConstructor
public class TriggerSchedulerFacade implements TriggerRegistrationService {

    static final String JOB_GROUP = "batch-trigger";

    private final TriggerDefinitionLoader triggerDefinitionLoader;
    private final Scheduler scheduler;

    @Override
    // M-13: lockAtMostFor 从 PT5M 增加到 PT15M，大集群枚举注册所有触发器可能超过 5 分钟
    @SchedulerLock(name = "trigger_register_all", lockAtMostFor = "PT15M", lockAtLeastFor = "PT10S")
    public void registerAll() {
        List<TriggerDescriptor> descriptors = triggerDefinitionLoader.loadAll();
        descriptors.stream().filter(TriggerDescriptor::isEnabled).forEach(this::scheduleDescriptor);
    }

    @Override
    public void registerByJobCode(String tenantId, String jobCode) {
        TriggerDescriptor descriptor = triggerDefinitionLoader.loadByJobCode(tenantId, jobCode);
        if (descriptor != null && descriptor.isEnabled()) {
            scheduleDescriptor(descriptor);
        }
    }

    @Override
    public void unregisterByJobCode(String tenantId, String jobCode) {
        try {
            JobKey jobKey = JobKey.jobKey(tenantId + ":" + jobCode, JOB_GROUP);
            if (scheduler.checkExists(jobKey)) {
                scheduler.deleteJob(jobKey);
            }
        } catch (SchedulerException e) {
            throw new IllegalStateException("failed to unregister trigger: " + jobCode, e);
        }
    }

    @Override
    public void pauseByJobCode(String tenantId, String jobCode) {
        try {
            JobKey jobKey = JobKey.jobKey(tenantId + ":" + jobCode, JOB_GROUP);
            if (scheduler.checkExists(jobKey)) {
                scheduler.pauseJob(jobKey);
            }
        } catch (SchedulerException e) {
            throw new IllegalStateException("failed to pause trigger: " + jobCode, e);
        }
    }

    @Override
    public void resumeByJobCode(String tenantId, String jobCode) {
        try {
            JobKey jobKey = JobKey.jobKey(tenantId + ":" + jobCode, JOB_GROUP);
            if (scheduler.checkExists(jobKey)) {
                scheduler.resumeJob(jobKey);
            }
        } catch (SchedulerException e) {
            throw new IllegalStateException("failed to resume trigger: " + jobCode, e);
        }
    }

    @Override
    public List<TriggerStatusInfo> listRegisteredTriggers() {
        try {
            List<TriggerStatusInfo> result = new ArrayList<>();
            for (JobKey jobKey : scheduler.getJobKeys(GroupMatcher.jobGroupEquals(JOB_GROUP))) {
                JobDetail detail = scheduler.getJobDetail(jobKey);
                if (detail == null) continue;
                JobDataMap data = detail.getJobDataMap();
                String identity = jobKey.getName();
                String[] parts = identity.split(":", 2);
                String tid = parts.length > 0 ? parts[0] : "";
                String jc = parts.length > 1 ? parts[1] : identity;

                List<? extends Trigger> triggers = scheduler.getTriggersOfJob(jobKey);
                String status = "UNKNOWN";
                Instant prevFire = null;
                Instant nextFire = null;
                if (!triggers.isEmpty()) {
                    Trigger t = triggers.get(0);
                    Trigger.TriggerState state = scheduler.getTriggerState(t.getKey());
                    status = state.name();
                    if (t.getPreviousFireTime() != null)
                        prevFire = t.getPreviousFireTime().toInstant();
                    if (t.getNextFireTime() != null) nextFire = t.getNextFireTime().toInstant();
                }
                result.add(
                        new TriggerStatusInfo(
                                tid,
                                jc,
                                data.getString(QuartzLaunchJob.SCHEDULE_TYPE),
                                data.getString(QuartzLaunchJob.SCHEDULE_EXPRESSION),
                                data.getString(QuartzLaunchJob.TIMEZONE),
                                data.getString(QuartzLaunchJob.TRIGGER_MODE),
                                status,
                                prevFire,
                                nextFire));
            }
            return result;
        } catch (SchedulerException e) {
            throw new IllegalStateException("failed to list triggers", e);
        }
    }

    @Override
    public void pauseAll() {
        try {
            scheduler.pauseAll();
        } catch (SchedulerException e) {
            throw new IllegalStateException("failed to pause all triggers", e);
        }
    }

    @Override
    public void resumeAll() {
        try {
            scheduler.resumeAll();
        } catch (SchedulerException e) {
            throw new IllegalStateException("failed to resume all triggers", e);
        }
    }

    @Override
    public String schedulerStatus() {
        try {
            if (scheduler.isShutdown()) return "SHUTDOWN";
            if (scheduler.isInStandbyMode()) return "STANDBY";
            if (scheduler.isStarted()) {
                var pausedGroups = scheduler.getPausedTriggerGroups();
                if (pausedGroups.contains(JOB_GROUP)) return "PAUSED";
                return "STARTED";
            }
            return "UNKNOWN";
        } catch (SchedulerException e) {
            throw new IllegalStateException("failed to get scheduler status", e);
        }
    }

    private void scheduleDescriptor(TriggerDescriptor descriptor) {
        if (!"CRON".equalsIgnoreCase(descriptor.getScheduleType())) {
            return;
        }
        String expression = descriptor.getScheduleExpression();
        if (!CronExpression.isValidExpression(expression)) {
            throw new IllegalArgumentException(
                    "invalid cron expression for job "
                            + descriptor.getJobCode()
                            + ": '"
                            + expression
                            + "'");
        }
        String timezone = descriptor.getTimezone();
        if (timezone != null && !timezone.isBlank()) {
            try {
                ZoneId.of(timezone);
            } catch (DateTimeException e) {
                throw new IllegalArgumentException(
                        "invalid timezone for job "
                                + descriptor.getJobCode()
                                + ": '"
                                + timezone
                                + "'",
                        e);
            }
        }
        try {
            String identity = descriptor.getTenantId() + ":" + descriptor.getJobCode();
            JobDataMap jobDataMap = new JobDataMap();
            jobDataMap.put(QuartzLaunchJob.TENANT_ID, descriptor.getTenantId());
            jobDataMap.put(QuartzLaunchJob.JOB_CODE, descriptor.getJobCode());
            jobDataMap.put(QuartzLaunchJob.SCHEDULE_TYPE, descriptor.getScheduleType());
            jobDataMap.put(QuartzLaunchJob.SCHEDULE_EXPRESSION, descriptor.getScheduleExpression());
            jobDataMap.put(QuartzLaunchJob.TIMEZONE, descriptor.getTimezone());
            jobDataMap.put(QuartzLaunchJob.TRIGGER_MODE, descriptor.getTriggerMode());
            jobDataMap.put(QuartzLaunchJob.CALENDAR_CODE, descriptor.getCalendarCode());
            jobDataMap.put(QuartzLaunchJob.CATCH_UP_POLICY, descriptor.getCatchUpPolicy());
            jobDataMap.put(QuartzLaunchJob.CATCH_UP_MAX_DAYS, descriptor.getCatchUpMaxDays());

            JobDetail jobDetail =
                    JobBuilder.newJob(QuartzLaunchJob.class)
                            .withIdentity(identity, JOB_GROUP)
                            .usingJobData(jobDataMap)
                            .storeDurably()
                            .build();

            CronTrigger trigger =
                    TriggerBuilder.newTrigger()
                            .withIdentity(identity, JOB_GROUP)
                            .forJob(jobDetail)
                            .withSchedule(
                                    CronScheduleBuilder.cronSchedule(
                                                    descriptor.getScheduleExpression())
                                            .inTimeZone(
                                                    TimeZone.getTimeZone(descriptor.getTimezone()))
                                            .withMisfireHandlingInstructionDoNothing())
                            .build();

            if (scheduler.checkExists(jobDetail.getKey())) {
                scheduler.deleteJob(jobDetail.getKey());
            }
            scheduler.scheduleJob(jobDetail, trigger);
        } catch (SchedulerException exception) {
            throw new IllegalStateException(
                    "failed to register quartz trigger: " + descriptor.getJobCode(), exception);
        }
    }
}
