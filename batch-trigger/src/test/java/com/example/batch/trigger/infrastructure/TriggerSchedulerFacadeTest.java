package com.example.batch.trigger.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.enums.CatchUpPolicyType;
import com.example.batch.trigger.domain.TriggerDefinitionLoader;
import com.example.batch.trigger.support.TriggerDescriptor;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.CronTrigger;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;

@ExtendWith(MockitoExtension.class)
class TriggerSchedulerFacadeTest {

    @Mock
    private TriggerDefinitionLoader triggerDefinitionLoader;
    @Mock
    private Scheduler scheduler;

    private TriggerSchedulerFacade facade;

    @BeforeEach
    void setUp() {
        facade = new TriggerSchedulerFacade(triggerDefinitionLoader, scheduler);
    }

    @Test
    void shouldScheduleEnabledCronDefinitionsWithCatchUpMetadata() throws Exception {
        when(triggerDefinitionLoader.loadAll()).thenReturn(List.of(
                descriptor("t1", "JOB_CRON", "CRON", true),
                descriptor("t1", "JOB_MANUAL", "MANUAL", true),
                descriptor("t1", "JOB_DISABLED", "CRON", false)
        ));

        facade.registerAll();

        ArgumentCaptor<JobDetail> jobDetailCaptor = ArgumentCaptor.forClass(JobDetail.class);
        ArgumentCaptor<CronTrigger> triggerCaptor = ArgumentCaptor.forClass(CronTrigger.class);
        verify(scheduler).scheduleJob(jobDetailCaptor.capture(), triggerCaptor.capture());

        JobDetail jobDetail = jobDetailCaptor.getValue();
        assertThat(jobDetail.getKey().getName()).isEqualTo("t1:JOB_CRON");
        assertThat(jobDetail.getKey().getGroup()).isEqualTo(TriggerSchedulerFacade.JOB_GROUP);
        assertThat(jobDetail.getJobDataMap())
                .containsEntry(QuartzLaunchJob.CALENDAR_CODE, "BIZ_CAL")
                .containsEntry(QuartzLaunchJob.CATCH_UP_POLICY, CatchUpPolicyType.AUTO.code())
                .containsEntry(QuartzLaunchJob.CATCH_UP_MAX_DAYS, 3);
        assertThat(triggerCaptor.getValue().getCronExpression()).isEqualTo("0 0 1 * * ?");
        assertThat(triggerCaptor.getValue().getMisfireInstruction())
                .isEqualTo(CronTrigger.MISFIRE_INSTRUCTION_DO_NOTHING);
    }

    @Test
    void shouldDeleteExistingQuartzJobBeforeRescheduling() throws Exception {
        TriggerDescriptor descriptor = descriptor("t1", "JOB_REPLACE", "CRON", true);
        when(triggerDefinitionLoader.loadByJobCode("t1", "JOB_REPLACE")).thenReturn(descriptor);
        when(scheduler.checkExists(any(JobKey.class))).thenReturn(true);

        facade.registerByJobCode("t1", "JOB_REPLACE");

        var inOrder = inOrder(scheduler);
        inOrder.verify(scheduler).checkExists(any(JobKey.class));
        inOrder.verify(scheduler).deleteJob(any());
        inOrder.verify(scheduler).scheduleJob(any(JobDetail.class), any(CronTrigger.class));
    }

    private TriggerDescriptor descriptor(String tenantId, String jobCode, String scheduleType, boolean enabled) {
        TriggerDescriptor descriptor = new TriggerDescriptor();
        descriptor.setTenantId(tenantId);
        descriptor.setJobCode(jobCode);
        descriptor.setScheduleType(scheduleType);
        descriptor.setScheduleExpression("0 0 1 * * ?");
        descriptor.setTimezone("UTC");
        descriptor.setTriggerMode("SCHEDULED");
        descriptor.setCalendarCode("BIZ_CAL");
        descriptor.setCatchUpPolicy(CatchUpPolicyType.AUTO.code());
        descriptor.setCatchUpMaxDays(3);
        descriptor.setEnabled(enabled);
        return descriptor;
    }
}
