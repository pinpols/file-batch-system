package com.example.batch.trigger.infrastructure;

import com.example.batch.common.enums.CatchUpPolicyType;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.common.utils.IdGenerator;
import com.example.batch.trigger.config.TriggerRuntimeProperties;
import com.example.batch.trigger.domain.MisfireHandler;
import com.example.batch.trigger.domain.command.ScheduledTriggerCommand;
import com.example.batch.trigger.service.TriggerService;
import com.example.batch.trigger.support.TriggerDescriptor;

import lombok.RequiredArgsConstructor;

import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
@RequiredArgsConstructor
public class QuartzLaunchJob implements Job {

    public static final String TENANT_ID = "tenantId";
    public static final String JOB_CODE = "jobCode";
    public static final String SCHEDULE_TYPE = "scheduleType";
    public static final String SCHEDULE_EXPRESSION = "scheduleExpression";
    public static final String TIMEZONE = "timezone";
    public static final String TRIGGER_MODE = "triggerMode";
    public static final String CALENDAR_CODE = "calendarCode";
    public static final String CATCH_UP_POLICY = "catchUpPolicy";
    public static final String CATCH_UP_MAX_DAYS = "catchUpMaxDays";

    private final TriggerService triggerService;
    private final MisfireHandler misfireHandler;
    private final TriggerRuntimeProperties triggerRuntimeProperties;

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        JobDataMap jobDataMap = context.getMergedJobDataMap();
        TriggerDescriptor descriptor = new TriggerDescriptor();
        descriptor.setTenantId(jobDataMap.getString(TENANT_ID));
        descriptor.setJobCode(jobDataMap.getString(JOB_CODE));
        descriptor.setScheduleType(jobDataMap.getString(SCHEDULE_TYPE));
        descriptor.setScheduleExpression(jobDataMap.getString(SCHEDULE_EXPRESSION));
        descriptor.setTimezone(jobDataMap.getString(TIMEZONE));
        descriptor.setTriggerMode(jobDataMap.getString(TRIGGER_MODE));
        descriptor.setCalendarCode(jobDataMap.getString(CALENDAR_CODE));
        descriptor.setCatchUpPolicy(jobDataMap.getString(CATCH_UP_POLICY));
        descriptor.setCatchUpMaxDays(resolveCatchUpMaxDays(jobDataMap));
        descriptor.setEnabled(true);
        Instant scheduledFireTime = context.getScheduledFireTime().toInstant();
        Instant actualFireTime = context.getFireTime().toInstant();
        if (requiresManualApproval(descriptor, scheduledFireTime, actualFireTime)) {
            misfireHandler.handle(descriptor.getTenantId() + ":" + descriptor.getJobCode());
            triggerService.createPendingCatchUp(
                    new ScheduledTriggerCommand(
                            descriptor,
                            scheduledFireTime,
                            TriggerType.CATCH_UP,
                            IdGenerator.newBusinessNo("quartz"),
                            IdGenerator.newTraceId()));
            return;
        }
        TriggerType triggerType = resolveTriggerType(descriptor, scheduledFireTime, actualFireTime);
        triggerService.launchScheduled(
                new ScheduledTriggerCommand(
                        descriptor,
                        scheduledFireTime,
                        triggerType,
                        IdGenerator.newBusinessNo("quartz"),
                        IdGenerator.newTraceId()));
    }

    private TriggerType resolveTriggerType(
            TriggerDescriptor descriptor, Instant scheduledFireTime, Instant actualFireTime) {
        if (scheduledFireTime == null || actualFireTime == null) {
            return TriggerType.SCHEDULED;
        }
        long driftSeconds =
                Math.max(0L, actualFireTime.getEpochSecond() - scheduledFireTime.getEpochSecond());
        if (driftSeconds >= triggerRuntimeProperties.getMisfireCatchUpThresholdSeconds()) {
            misfireHandler.handle(descriptor.getTenantId() + ":" + descriptor.getJobCode());
            return resolveCatchUpPolicy(descriptor, scheduledFireTime, actualFireTime);
        }
        return TriggerType.SCHEDULED;
    }

    /** Misfire 是否转为 catch-up 由 business_calendar 控制，不再只依赖固定时间阈值。 */
    private TriggerType resolveCatchUpPolicy(
            TriggerDescriptor descriptor, Instant scheduledFireTime, Instant actualFireTime) {
        CatchUpPolicyType catchUpPolicy = CatchUpPolicyType.fromCode(descriptor.getCatchUpPolicy());
        if (catchUpPolicy == CatchUpPolicyType.NONE
                || catchUpPolicy == CatchUpPolicyType.MANUAL_APPROVAL) {
            return TriggerType.SCHEDULED;
        }
        long maxDays = descriptor.getCatchUpMaxDays() == null ? 0L : descriptor.getCatchUpMaxDays();
        if (maxDays <= 0L) {
            return TriggerType.CATCH_UP;
        }
        long driftDays = Math.max(0L, Duration.between(scheduledFireTime, actualFireTime).toDays());
        return driftDays <= maxDays ? TriggerType.CATCH_UP : TriggerType.SCHEDULED;
    }

    private boolean requiresManualApproval(
            TriggerDescriptor descriptor, Instant scheduledFireTime, Instant actualFireTime) {
        if (scheduledFireTime == null || actualFireTime == null) {
            return false;
        }
        CatchUpPolicyType catchUpPolicy = CatchUpPolicyType.fromCode(descriptor.getCatchUpPolicy());
        if (catchUpPolicy != CatchUpPolicyType.MANUAL_APPROVAL) {
            return false;
        }
        long driftSeconds =
                Math.max(0L, actualFireTime.getEpochSecond() - scheduledFireTime.getEpochSecond());
        if (driftSeconds < triggerRuntimeProperties.getMisfireCatchUpThresholdSeconds()) {
            return false;
        }
        long maxDays = descriptor.getCatchUpMaxDays() == null ? 0L : descriptor.getCatchUpMaxDays();
        if (maxDays <= 0L) {
            return true;
        }
        long driftDays = Math.max(0L, Duration.between(scheduledFireTime, actualFireTime).toDays());
        return driftDays <= maxDays;
    }

    private Integer resolveCatchUpMaxDays(JobDataMap jobDataMap) {
        Object rawValue = jobDataMap.get(CATCH_UP_MAX_DAYS);
        if (rawValue instanceof Integer integer) {
            return integer;
        }
        if (rawValue instanceof Number number) {
            return number.intValue();
        }
        if (rawValue instanceof String string && !string.isBlank()) {
            return Integer.parseInt(string);
        }
        return null;
    }
}
