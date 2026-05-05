package com.example.batch.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.dto.LaunchResponse;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.orchestrator.domain.entity.BatchDayInstanceEntity;
import com.example.batch.orchestrator.domain.entity.BatchDayWaitingLaunchEntity;
import com.example.batch.orchestrator.mapper.BatchDayInstanceMapper;
import com.example.batch.orchestrator.mapper.BatchDayWaitingLaunchMapper;
import com.example.batch.orchestrator.mapper.JobExecutionLogMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BatchDayOperationServiceTest {

  private BatchDayInstanceMapper batchDayInstanceMapper;
  private BatchDayWaitingLaunchMapper waitingLaunchMapper;
  private JobExecutionLogMapper jobExecutionLogMapper;
  private LaunchService launchService;
  private BatchDayOperationService service;

  @BeforeEach
  void setUp() {
    batchDayInstanceMapper = mock(BatchDayInstanceMapper.class);
    waitingLaunchMapper = mock(BatchDayWaitingLaunchMapper.class);
    jobExecutionLogMapper = mock(JobExecutionLogMapper.class);
    launchService = mock(LaunchService.class);
    service =
        new BatchDayOperationService(
            batchDayInstanceMapper, waitingLaunchMapper, jobExecutionLogMapper, launchService);
  }

  @Test
  void shouldFreezeOpenBatchDay() {
    BatchDayInstanceEntity current = batchDay("OPEN");
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate(
            "t1", "CAL", LocalDate.of(2026, 5, 4)))
        .thenReturn(current);
    when(batchDayInstanceMapper.updateWithCas(any())).thenReturn(1);

    BatchDayOperationService.BatchDayOperationResult result =
        service.operate(
            "t1",
            "CAL",
            LocalDate.of(2026, 5, 4),
            BatchDayOperationService.BatchDayOperation.FREEZE,
            "ops",
            "manual hold");

    assertThat(result.batchDay().dayStatus()).isEqualTo("OPEN");
    assertThat(result.batchDay().frozen()).isTrue();
    assertThat(result.releasedLaunchCount()).isZero();
    verify(jobExecutionLogMapper).insert(any());
  }

  @Test
  void shouldSkipNonTerminalBatchDay() {
    BatchDayInstanceEntity current = batchDay("CUTOFF");
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate(
            "t1", "CAL", LocalDate.of(2026, 5, 4)))
        .thenReturn(current);
    when(batchDayInstanceMapper.updateWithCas(any())).thenReturn(1);

    service.operate(
        "t1",
        "CAL",
        LocalDate.of(2026, 5, 4),
        BatchDayOperationService.BatchDayOperation.SKIP,
        "ops",
        "no input");

    ArgumentCaptor<BatchDayInstanceEntity> captor =
        ArgumentCaptor.forClass(BatchDayInstanceEntity.class);
    verify(batchDayInstanceMapper).updateWithCas(captor.capture());
    assertThat(captor.getValue().dayStatus()).isEqualTo("SKIPPED");
    assertThat(captor.getValue().settledAt()).isNotNull();
  }

  @Test
  void shouldRejectFreezeOnTerminalBatchDay() {
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate(
            "t1", "CAL", LocalDate.of(2026, 5, 4)))
        .thenReturn(batchDay("SETTLED"));

    assertThatThrownBy(
            () ->
                service.operate(
                    "t1",
                    "CAL",
                    LocalDate.of(2026, 5, 4),
                    BatchDayOperationService.BatchDayOperation.FREEZE,
                    "ops",
                    "hold"))
        .isInstanceOf(BizException.class);

    verify(batchDayInstanceMapper, never()).updateWithCas(any());
  }

  @Test
  void shouldReleaseWaitingLaunchesForNextBizDate() {
    BatchDayInstanceEntity current = batchDay("FAILED");
    when(batchDayInstanceMapper.selectByTenantCalendarBizDate(
            "t1", "CAL", LocalDate.of(2026, 5, 4)))
        .thenReturn(current);
    when(batchDayInstanceMapper.updateWithCas(any())).thenReturn(1);
    when(waitingLaunchMapper.selectWaitingByCalendarBizDate(
            "t1", "CAL", LocalDate.of(2026, 5, 5), 200))
        .thenReturn(List.of(waitingLaunch()));
    when(launchService.launch(any())).thenReturn(new LaunchResponse("inst-1", "trace-1"));

    BatchDayOperationService.BatchDayOperationResult result =
        service.operate(
            "t1",
            "CAL",
            LocalDate.of(2026, 5, 4),
            BatchDayOperationService.BatchDayOperation.RELEASE,
            "ops",
            "manual release");

    assertThat(result.batchDay().dayStatus()).isEqualTo("MANUAL_RELEASED");
    assertThat(result.releasedLaunchCount()).isEqualTo(1);
    verify(launchService).launch(any(LaunchRequest.class));
    verify(waitingLaunchMapper).markReleased("t1", "req-1", "ops");
  }

  private BatchDayInstanceEntity batchDay(String status) {
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
        false,
        null,
        null,
        null,
        0L,
        at,
        at);
  }

  private BatchDayWaitingLaunchEntity waitingLaunch() {
    Instant at = Instant.parse("2026-05-05T00:00:00Z");
    Map<String, Object> payload =
        Map.of(
            "tenantId", "t1",
            "jobCode", "JOB",
            "bizDate", "2026-05-05",
            "triggerType", "SCHEDULED",
            "requestId", "req-1",
            "traceId", "trace-1",
            "params", Map.of("k", "v"));
    return new BatchDayWaitingLaunchEntity(
        1L,
        "t1",
        "CAL",
        "JOB",
        LocalDate.of(2026, 5, 5),
        "req-1",
        "trace-1",
        "SCHEDULED",
        "PREVIOUS_BATCH_DAY_NOT_CLOSED",
        JsonUtils.toJson(payload),
        "WAITING",
        null,
        null,
        at,
        at);
  }
}
