package com.example.batch.trigger.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.dto.LaunchResponse;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.testing.AbstractIntegrationTest;
import com.example.batch.trigger.BatchTriggerApplication;
import com.example.batch.trigger.domain.OrchestratorTriggerAdapter;
import com.example.batch.trigger.domain.command.PendingCatchUpApprovalCommand;
import com.example.batch.trigger.domain.command.TriggerLaunchCommand;
import com.example.batch.trigger.service.TriggerService;
import com.example.batch.trigger.web.request.TriggerLaunchRequest;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(
    classes = BatchTriggerApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "spring.flyway.enabled=false",
      "spring.autoconfigure.exclude=org.springframework.boot.quartz.autoconfigure.QuartzAutoConfiguration",
      "batch.orchestrator.base-url=http://localhost:8082"
    })
@Import(TriggerServiceIntegrationTest.TestConfig.class)
class TriggerServiceIntegrationTest extends AbstractIntegrationTest {

  @Autowired private TriggerService triggerService;

  @Autowired private JdbcTemplate jdbcTemplate;

  @MockitoBean private OrchestratorTriggerAdapter orchestratorTriggerAdapter;

  @BeforeEach
  void cleanUp() {
    jdbcTemplate.update("delete from batch.trigger_request");
  }

  @Test
  void shouldPersistAcceptedRequestAndDeduplicateRepeatedLaunch() {
    when(orchestratorTriggerAdapter.sendTrigger(any()))
        .thenReturn(new LaunchResponse("inst-001", "trace-001"));

    TriggerLaunchRequest request = new TriggerLaunchRequest();
    request.setTenantId("t1");
    request.setJobCode("IMPORT_JOB");
    request.setBizDate(LocalDate.of(2026, 3, 27));
    request.setTriggerType(TriggerType.API);
    request.setParams(Map.of("source", "integration"));

    TriggerLaunchCommand command =
        new TriggerLaunchCommand(request, "idem-001", "req-001", "trace-001");

    triggerService.launch(command);
    triggerService.launch(command);

    Integer count =
        jdbcTemplate.queryForObject(
            "select count(*) from batch.trigger_request where tenant_id = ? and dedup_key = ?",
            Integer.class,
            "t1",
            "idem-001");
    String status =
        jdbcTemplate.queryForObject(
            "select request_status from batch.trigger_request where tenant_id = ? and request_id ="
                + " ?",
            String.class,
            "t1",
            "req-001");

    assertThat(count).isEqualTo(1);
    assertThat(status).isEqualTo("ACCEPTED");
    verify(orchestratorTriggerAdapter, times(1)).sendTrigger(any());
  }

  @Test
  void shouldApprovePendingCatchUpAndMarkRowLaunched() {
    jdbcTemplate.update(
        """
        insert into batch.trigger_request (
            tenant_id, request_id, trigger_type, job_code, biz_date, dedup_key, request_status, trace_id
        ) values (?, ?, ?, ?, ?, ?, ?, ?)
        """,
        "t1",
        "pending-001",
        TriggerType.CATCH_UP.code(),
        "EXPORT_JOB",
        LocalDate.of(2026, 3, 27),
        "dedup-pending-001",
        "ACCEPTED",
        "trace-pending-001");
    when(orchestratorTriggerAdapter.sendTrigger(any()))
        .thenReturn(new LaunchResponse("inst-002", "trace-pending-001"));

    PendingCatchUpApprovalCommand command = new PendingCatchUpApprovalCommand();
    command.setTenantId("t1");
    command.setRequestId("pending-001");
    command.setReason("manual approval");

    LaunchResponse response = triggerService.approvePendingCatchUp(command);

    String status =
        jdbcTemplate.queryForObject(
            "select request_status from batch.trigger_request where tenant_id = ? and request_id ="
                + " ?",
            String.class,
            "t1",
            "pending-001");

    assertThat(response.instanceNo()).isEqualTo("inst-002");
    assertThat(status).isEqualTo("LAUNCHED");
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
