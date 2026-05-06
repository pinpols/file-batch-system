package com.example.batch.orchestrator.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.config.BatchTimezoneProperties;
import com.example.batch.common.config.BatchTimezoneProvider;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.orchestrator.application.service.governance.CompensationService;
import com.example.batch.orchestrator.config.BatchDayReplayDispatchProperties;
import com.example.batch.orchestrator.domain.command.CompensationSubmitCommand;
import com.example.batch.orchestrator.domain.entity.BatchDayReplayEntryEntity;
import com.example.batch.orchestrator.domain.entity.BatchDayReplaySessionEntity;
import com.example.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import com.example.batch.orchestrator.mapper.BatchDayReplayEntryMapper;
import com.example.batch.orchestrator.mapper.BatchDayReplaySessionMapper;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BatchDayReplayDispatcherTest {

  private BatchDayReplaySessionMapper sessionMapper;
  private BatchDayReplayEntryMapper entryMapper;
  private CompensationService compensationService;
  private OrchestratorGracefulShutdown gracefulShutdown;
  private BatchDayReplayDispatchProperties properties;
  private BatchDayReplayDispatcher dispatcher;

  @BeforeEach
  void setUp() {
    sessionMapper = mock(BatchDayReplaySessionMapper.class);
    entryMapper = mock(BatchDayReplayEntryMapper.class);
    compensationService = mock(CompensationService.class);
    gracefulShutdown = mock(OrchestratorGracefulShutdown.class);
    when(gracefulShutdown.isDraining()).thenReturn(false);
    properties = new BatchDayReplayDispatchProperties();
    properties.setEnabled(true);
    properties.setSessionBatchSize(10);
    properties.setEntryBatchSize(20);
    BatchDateTimeSupport dateTimeSupport =
        new BatchDateTimeSupport(
            Clock.systemUTC(), new BatchTimezoneProvider(new BatchTimezoneProperties()));
    dispatcher =
        new BatchDayReplayDispatcher(
            sessionMapper,
            entryMapper,
            compensationService,
            properties,
            gracefulShutdown,
            dateTimeSupport);
  }

  @Test
  void disabledShortCircuits() {
    properties.setEnabled(false);
    dispatcher.scheduledDispatch();
    verify(sessionMapper, never()).selectByStatus(anyString(), anyInt());
  }

  @Test
  void drainingShutdownShortCircuits() {
    when(gracefulShutdown.isDraining()).thenReturn(true);
    dispatcher.scheduledDispatch();
    verify(sessionMapper, never()).selectByStatus(anyString(), anyInt());
  }

  @Test
  void noRunningSessionsIsNoop() {
    when(sessionMapper.selectByStatus("RUNNING", 10)).thenReturn(List.of());
    dispatcher.scheduledDispatch();
    verify(entryMapper, never()).selectBySessionAndStatus(anyLong(), anyString(), anyInt());
  }

  @Test
  void outputsOnlySessionsAreSkippedByDispatcher() {
    BatchDayReplaySessionEntity outputs =
        sessionAt(7L, "OUTPUTS_ONLY", "RUNNING", "CREATE_NEW_VERSION");
    when(sessionMapper.selectByStatus("RUNNING", 10)).thenReturn(List.of(outputs));
    dispatcher.scheduledDispatch();
    verify(entryMapper, never()).selectBySessionAndStatus(anyLong(), anyString(), anyInt());
    verify(compensationService, never()).submit(any());
  }

  @Test
  void allFailedSessionDispatchesPendingEntries() {
    BatchDayReplaySessionEntity session =
        sessionAt(8L, "ALL_FAILED", "RUNNING", "CREATE_NEW_VERSION");
    when(sessionMapper.selectByStatus("RUNNING", 10)).thenReturn(List.of(session));
    BatchDayReplayEntryEntity e1 =
        BatchDayReplayEntryEntity.builder()
            .id(1L)
            .sessionId(8L)
            .tenantId("t1")
            .jobCode("JOB_A")
            .sourceInstanceId(101L)
            .status("PENDING")
            .build();
    BatchDayReplayEntryEntity e2 =
        BatchDayReplayEntryEntity.builder()
            .id(2L)
            .sessionId(8L)
            .tenantId("t1")
            .jobCode("JOB_B")
            .sourceInstanceId(102L)
            .status("PENDING")
            .build();
    when(entryMapper.selectBySessionAndStatus(8L, "PENDING", 20)).thenReturn(List.of(e1, e2));
    when(compensationService.submit(any(CompensationSubmitCommand.class))).thenReturn("CMD-OK");

    dispatcher.scheduledDispatch();

    ArgumentCaptor<CompensationSubmitCommand> captor =
        ArgumentCaptor.forClass(CompensationSubmitCommand.class);
    verify(compensationService, times(2)).submit(captor.capture());
    assertThat(captor.getAllValues())
        .allSatisfy(cmd -> assertThat(cmd.replaySessionId()).isEqualTo(8L));
    assertThat(captor.getAllValues())
        .allSatisfy(
            cmd ->
                assertThat(cmd.resultPolicy())
                    .isEqualTo("CREATE_NEW_VERSION")); // 透传 session policy
    verify(entryMapper, times(2))
        .updateStatus(anyLong(), eq("RUNNING"), any(), any(), any(), any(), any(), any());
  }

  @Test
  void compensationSubmitFailureMarksEntryFailed() {
    BatchDayReplaySessionEntity session =
        sessionAt(9L, "ALL_FAILED", "RUNNING", "CREATE_NEW_VERSION");
    when(sessionMapper.selectByStatus("RUNNING", 10)).thenReturn(List.of(session));
    BatchDayReplayEntryEntity entry =
        BatchDayReplayEntryEntity.builder()
            .id(1L)
            .sessionId(9L)
            .tenantId("t1")
            .jobCode("JOB_A")
            .sourceInstanceId(101L)
            .status("PENDING")
            .build();
    when(entryMapper.selectBySessionAndStatus(9L, "PENDING", 20)).thenReturn(List.of(entry));
    when(compensationService.submit(any(CompensationSubmitCommand.class)))
        .thenThrow(new RuntimeException("compensation backpressure"));

    dispatcher.scheduledDispatch();

    verify(entryMapper)
        .updateStatus(eq(1L), eq("FAILED"), any(), any(), any(), any(), any(), any());
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  private static BatchDayReplaySessionEntity sessionAt(
      Long id, String scope, String status, String resultPolicy) {
    return BatchDayReplaySessionEntity.builder()
        .id(id)
        .tenantId("t1")
        .calendarCode("CAL")
        .bizDate(LocalDate.of(2026, 5, 4))
        .scope(scope)
        .resultPolicy(resultPolicy)
        .configVersionPolicy("USE_ORIGINAL_CONFIG")
        .reason("reason-" + id)
        .status(status)
        .totalCount(2)
        .succeededCount(0)
        .failedCount(0)
        .inFlightCount(0)
        .requestedBy("ops")
        .build();
  }
}
