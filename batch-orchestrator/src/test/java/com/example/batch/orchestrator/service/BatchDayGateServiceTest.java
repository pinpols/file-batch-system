package com.example.batch.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.config.BatchTimezoneProperties;
import com.example.batch.common.config.BatchTimezoneProvider;
import com.example.batch.common.constants.BatchStatusConstants;
import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.common.persistence.entity.TriggerRequestEntity;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.orchestrator.domain.entity.BatchDayInstanceEntity;
import com.example.batch.orchestrator.domain.entity.BatchDayWaitingLaunchEntity;
import com.example.batch.orchestrator.domain.entity.BusinessCalendarEntity;
import com.example.batch.orchestrator.domain.entity.JobDefinitionEntity;
import com.example.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import com.example.batch.orchestrator.mapper.BatchDayInstanceMapper;
import com.example.batch.orchestrator.mapper.BatchDayWaitingLaunchMapper;
import com.example.batch.orchestrator.mapper.JobExecutionLogMapper;
import com.example.batch.orchestrator.mapper.TriggerRequestMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BatchDayGateServiceTest {

  private OrchestratorConfigCacheService configCacheService;
  private BatchDayInstanceMapper batchDayInstanceMapper;
  private BatchDayWaitingLaunchMapper waitingLaunchMapper;
  private TriggerRequestMapper triggerRequestMapper;
  private JobExecutionLogMapper jobExecutionLogMapper;
  private BatchDayGateService service;

  @BeforeEach
  void setUp() {
    configCacheService = mock(OrchestratorConfigCacheService.class);
    batchDayInstanceMapper = mock(BatchDayInstanceMapper.class);
    waitingLaunchMapper = mock(BatchDayWaitingLaunchMapper.class);
    triggerRequestMapper = mock(TriggerRequestMapper.class);
    jobExecutionLogMapper = mock(JobExecutionLogMapper.class);
    service =
        new BatchDayGateService(
            configCacheService,
            batchDayInstanceMapper,
            waitingLaunchMapper,
            triggerRequestMapper,
            jobExecutionLogMapper,
            new BatchDateTimeSupport(
                Clock.systemUTC(), new BatchTimezoneProvider(new BatchTimezoneProperties())));
  }

  @Test
  void shouldAllowWhenCalendarAllowsOverlap() {
    LaunchRequest request = request();
    LaunchValidationService.LaunchLoadResult loaded = loaded("INHERIT");
    when(configCacheService.findEnabledBusinessCalendar("t1", "CAL"))
        .thenReturn(calendar("ALLOW_OVERLAP"));

    BatchDayGateService.GateDecision decision =
        service.evaluateAndApply(request, loaded, Map.of(), "trace-1");

    assertThat(decision.type()).isEqualTo(BatchDayGateService.GateDecisionType.ALLOW);
    verify(batchDayInstanceMapper, never()).selectByTenantCalendarBizDate(any(), any(), any());
  }

  @Test
  void shouldWaitWhenPreviousDayIsNotClosed() {
    LaunchRequest request = request();
    LaunchValidationService.LaunchLoadResult loaded = loaded("INHERIT");
    when(configCacheService.findEnabledBusinessCalendar("t1", "CAL"))
        .thenReturn(calendar("WAIT_PREVIOUS_DAY"));
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate(
            "t1", "CAL", LocalDate.of(2026, 5, 4)))
        .thenReturn(previous("IN_FLIGHT"));

    BatchDayGateService.GateDecision decision =
        service.evaluateAndApply(request, loaded, Map.of("k", "v"), "trace-1");

    assertThat(decision.type()).isEqualTo(BatchDayGateService.GateDecisionType.WAIT);
    ArgumentCaptor<BatchDayWaitingLaunchEntity> waitingCaptor =
        ArgumentCaptor.forClass(BatchDayWaitingLaunchEntity.class);
    verify(waitingLaunchMapper).insert(waitingCaptor.capture());
    assertThat(waitingCaptor.getValue().waitStatus()).isEqualTo(BatchStatusConstants.WAITING);
    assertThat(waitingCaptor.getValue().bizDate()).isEqualTo(LocalDate.of(2026, 5, 5));
    verify(triggerRequestMapper)
        .updateAcceptance("t1", "req-1", BatchStatusConstants.WAITING, null);
    verify(jobExecutionLogMapper).insert(any());
  }

  @Test
  void shouldRejectWhenCalendarRejectsOpenPreviousDay() {
    LaunchRequest request = request();
    LaunchValidationService.LaunchLoadResult loaded = loaded("INHERIT");
    when(configCacheService.findEnabledBusinessCalendar("t1", "CAL"))
        .thenReturn(calendar("REJECT_IF_PREVIOUS_OPEN"));
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate(
            "t1", "CAL", LocalDate.of(2026, 5, 4)))
        .thenReturn(previous("FAILED"));

    BatchDayGateService.GateDecision decision =
        service.evaluateAndApply(request, loaded, Map.of(), "trace-1");

    assertThat(decision.type()).isEqualTo(BatchDayGateService.GateDecisionType.REJECT);
    verify(waitingLaunchMapper, never()).insert(any());
    verify(triggerRequestMapper)
        .updateAcceptance("t1", "req-1", BatchStatusConstants.REJECTED, null);
    verify(jobExecutionLogMapper).insert(any());
  }

  @Test
  void shouldAllowWhenPreviousDayIsSettled() {
    LaunchRequest request = request();
    LaunchValidationService.LaunchLoadResult loaded = loaded("INHERIT");
    when(configCacheService.findEnabledBusinessCalendar("t1", "CAL"))
        .thenReturn(calendar("WAIT_PREVIOUS_DAY"));
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate(
            "t1", "CAL", LocalDate.of(2026, 5, 4)))
        .thenReturn(previous("SETTLED"));

    BatchDayGateService.GateDecision decision =
        service.evaluateAndApply(request, loaded, Map.of(), "trace-1");

    assertThat(decision.type()).isEqualTo(BatchDayGateService.GateDecisionType.ALLOW);
    verify(waitingLaunchMapper, never()).insert(any());
    verify(triggerRequestMapper, never()).updateAcceptance(eq("t1"), eq("req-1"), any(), any());
  }

  private LaunchRequest request() {
    return new LaunchRequest(
        "t1", "JOB", LocalDate.of(2026, 5, 5), TriggerType.SCHEDULED, "req-1", "trace-1", Map.of());
  }

  private LaunchValidationService.LaunchLoadResult loaded(String scope) {
    TriggerRequestEntity trigger = new TriggerRequestEntity();
    trigger.setRequestId("req-1");
    return new LaunchValidationService.LaunchLoadResult(trigger, job(scope), null, null);
  }

  private JobDefinitionEntity job(String scope) {
    return new JobDefinitionEntity(
        1L,
        "t1",
        "JOB",
        "Job",
        "IMPORT",
        null,
        "CRON",
        "0 0 6 * * ?",
        "Asia/Shanghai",
        "import",
        "q",
        "CAL",
        null,
        "SCHEDULED",
        false,
        "NONE",
        "NONE",
        0,
        0,
        "handler",
        Map.of(),
        5,
        Map.of(),
        1,
        true,
        null,
        "FULL",
        null,
        scope);
  }

  private BusinessCalendarEntity calendar(String policy) {
    return new BusinessCalendarEntity(
        1L,
        "t1",
        "CAL",
        "Calendar",
        "Asia/Shanghai",
        "SKIP",
        "AUTO",
        30,
        LocalTime.of(6, 0),
        60,
        120,
        policy,
        true);
  }

  private BatchDayInstanceEntity previous(String status) {
    Instant at = Instant.parse("2026-05-04T00:00:00Z");
    return new BatchDayInstanceEntity(
        1L,
        "t1",
        "CAL",
        LocalDate.of(2026, 5, 4),
        status,
        at,
        at,
        null,
        null,
        0,
        0,
        "Asia/Shanghai",
        0L,
        at,
        at);
  }
}
