package com.example.batch.trigger.integration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.dto.LaunchResponse;
import com.example.batch.testing.AbstractIntegrationTest;
import com.example.batch.trigger.BatchTriggerApplication;
import com.example.batch.trigger.domain.OrchestratorTriggerAdapter;
import com.example.batch.trigger.infrastructure.QuartzLaunchJob;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.Scheduler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.mockito.Mockito.mock;

@SpringBootTest(
        classes = BatchTriggerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.flyway.enabled=false",
                "spring.autoconfigure.exclude=org.springframework.boot.quartz.autoconfigure.QuartzAutoConfiguration"
        }
)
@Import(QuartzLaunchJobIntegrationTest.TestConfig.class)
class QuartzLaunchJobIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    QuartzLaunchJob quartzLaunchJob;

    @MockitoBean
    OrchestratorTriggerAdapter orchestratorTriggerAdapter;

    @Test
    void shouldInvokeOrchestratorAdapterWhenQuartzFires() throws Exception {
        when(orchestratorTriggerAdapter.sendTrigger(any()))
                .thenReturn(new LaunchResponse("inst-quartz-001", "trace-quartz-001"));

        JobExecutionContext context = mock(JobExecutionContext.class);
        JobDataMap jobDataMap = new JobDataMap();
        jobDataMap.put(QuartzLaunchJob.TENANT_ID, "t1");
        jobDataMap.put(QuartzLaunchJob.JOB_CODE, "IMPORT_JOB");
        jobDataMap.put(QuartzLaunchJob.SCHEDULE_TYPE, "CRON");
        jobDataMap.put(QuartzLaunchJob.SCHEDULE_EXPRESSION, "0/1 * * * * ?");
        jobDataMap.put(QuartzLaunchJob.TIMEZONE, "UTC");
        jobDataMap.put(QuartzLaunchJob.TRIGGER_MODE, "SCHEDULED");
        jobDataMap.put(QuartzLaunchJob.CALENDAR_CODE, "");
        jobDataMap.put(QuartzLaunchJob.CATCH_UP_POLICY, "NONE");
        jobDataMap.put(QuartzLaunchJob.CATCH_UP_MAX_DAYS, 0);
        when(context.getMergedJobDataMap()).thenReturn(jobDataMap);
        when(context.getScheduledFireTime()).thenReturn(Date.from(Instant.parse("2026-03-27T00:00:00Z")));
        when(context.getFireTime()).thenReturn(Date.from(Instant.parse("2026-03-27T00:00:00Z")));

        quartzLaunchJob.execute(context);

        verify(orchestratorTriggerAdapter).sendTrigger(any());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {
        @Bean
        Scheduler scheduler() {
            return mock(Scheduler.class);
        }
    }
}

