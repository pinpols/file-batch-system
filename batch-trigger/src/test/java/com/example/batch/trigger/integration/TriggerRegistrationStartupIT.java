package com.example.batch.trigger.integration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.testing.AbstractIntegrationTest;
import com.example.batch.trigger.BatchTriggerApplication;
import com.example.batch.trigger.domain.TriggerRegistrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
        classes = BatchTriggerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.flyway.enabled=false",
                "spring.autoconfigure.exclude=org.springframework.boot.quartz.autoconfigure.QuartzAutoConfiguration"
        }
)
@Import(TriggerRegistrationStartupIT.TestConfig.class)
class TriggerRegistrationStartupIT extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    TriggerRegistrationService triggerRegistrationService;

    @Autowired
    Scheduler scheduler;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from batch.job_definition where tenant_id = ?", "t-trigger");
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("delete from batch.job_definition where tenant_id = ?", "t-trigger");
    }

    @Test
    void shouldRegisterAllEnabledCronDefinitionsIntoQuartz() throws Exception {
        insertCronJobDefinition("t-trigger", "JOB_A", "0 0/5 * * * ?");
        insertCronJobDefinition("t-trigger", "JOB_B", "0 10 * * * ?");
        insertNonCronJobDefinition("t-trigger", "JOB_DISABLED");

        when(scheduler.checkExists(any(org.quartz.JobKey.class))).thenReturn(false);
        triggerRegistrationService.registerAll();

        verify(scheduler, times(2)).scheduleJob(any(), any());
    }

    private void insertCronJobDefinition(String tenantId, String jobCode, String cronExpr) {
        jdbcTemplate.update(
                """
                insert into batch.job_definition (
                    tenant_id, job_code, job_name, job_type,
                    schedule_type, schedule_expr, timezone, trigger_mode,
                    priority, enabled
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                tenantId,
                jobCode,
                jobCode,
                "BATCH",
                "CRON",
                cronExpr,
                "UTC",
                "SCHEDULED",
                5,
                true
        );
    }

    private void insertNonCronJobDefinition(String tenantId, String jobCode) {
        jdbcTemplate.update(
                """
                insert into batch.job_definition (
                    tenant_id, job_code, job_name, job_type,
                    schedule_type, timezone, trigger_mode,
                    priority, enabled
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                tenantId,
                jobCode,
                jobCode,
                "BATCH",
                "NONE",
                "UTC",
                "SCHEDULED",
                5,
                true
        );
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {
        @Bean
        Scheduler scheduler() {
            return org.mockito.Mockito.mock(Scheduler.class);
        }
    }
}
