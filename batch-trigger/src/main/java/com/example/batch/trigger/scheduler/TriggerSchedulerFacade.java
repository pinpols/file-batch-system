package com.example.batch.trigger.scheduler;

import com.example.batch.trigger.job.QuartzLaunchJob;
import com.example.batch.trigger.support.TriggerDefinitionLoader;
import com.example.batch.trigger.support.TriggerDescriptor;
import com.example.batch.trigger.support.TriggerRegistrationService;
import java.util.List;
import java.util.TimeZone;
import lombok.RequiredArgsConstructor;
import org.quartz.CronScheduleBuilder;
import org.quartz.CronTrigger;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.TriggerBuilder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TriggerSchedulerFacade implements TriggerRegistrationService {

    private final TriggerDefinitionLoader triggerDefinitionLoader;
    private final Scheduler scheduler;

    @Override
    public void registerAll() {
        List<TriggerDescriptor> descriptors = triggerDefinitionLoader.loadAll();
        descriptors.stream()
                .filter(TriggerDescriptor::isEnabled)
                .forEach(this::scheduleDescriptor);
    }

    @Override
    public void registerByJobCode(String tenantId, String jobCode) {
        TriggerDescriptor descriptor = triggerDefinitionLoader.loadByJobCode(tenantId, jobCode);
        if (descriptor != null && descriptor.isEnabled()) {
            scheduleDescriptor(descriptor);
        }
    }

    private void scheduleDescriptor(TriggerDescriptor descriptor) {
        if (!"CRON".equalsIgnoreCase(descriptor.getScheduleType())) {
            return;
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

            JobDetail jobDetail = JobBuilder.newJob(QuartzLaunchJob.class)
                    .withIdentity(identity, "batch-trigger")
                    .usingJobData(jobDataMap)
                    .storeDurably()
                    .build();

            CronTrigger trigger = TriggerBuilder.newTrigger()
                    .withIdentity(identity, "batch-trigger")
                    .forJob(jobDetail)
                    .withSchedule(CronScheduleBuilder.cronSchedule(descriptor.getScheduleExpression())
                            .inTimeZone(TimeZone.getTimeZone(descriptor.getTimezone()))
                            .withMisfireHandlingInstructionDoNothing())
                    .build();

            if (scheduler.checkExists(jobDetail.getKey())) {
                scheduler.deleteJob(jobDetail.getKey());
            }
            scheduler.scheduleJob(jobDetail, trigger);
        } catch (SchedulerException exception) {
            throw new IllegalStateException("failed to register quartz trigger: " + descriptor.getJobCode(), exception);
        }
    }
}
