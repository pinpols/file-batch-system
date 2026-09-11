package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

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

import io.github.pinpols.batch.common.config.BatchTimezoneProperties;
import io.github.pinpols.batch.common.config.BatchTimezoneProvider;
import io.github.pinpols.batch.common.enums.BatchDayReplayCandidateSource;
import io.github.pinpols.batch.common.enums.BatchDayReplayExecutionMode;
import io.github.pinpols.batch.common.enums.BatchDayReplayScope;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.orchestrator.application.service.governance.CompensationService;
import io.github.pinpols.batch.orchestrator.config.BatchDayReplayDispatchProperties;
import io.github.pinpols.batch.orchestrator.domain.command.CompensationSubmitCommand;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayReplayEntryEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayReplaySessionEntity;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayReplayEntryMapper;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayReplaySessionMapper;
import io.github.pinpols.batch.orchestrator.mapper.CompensationCommandMapper;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.Month;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class BatchDayReplayDispatcherTest {

  private BatchDayReplaySessionMapper sessionMapper;
  private BatchDayReplayEntryMapper entryMapper;
  private CompensationService compensationService;
  private CompensationCommandMapper compensationCommandMapper;
  private OrchestratorGracefulShutdown gracefulShutdown;
  private BatchDayReplayDispatchProperties properties;
  private BatchDayReplayDispatcher dispatcher;

  @BeforeEach
  void setUp() {
    sessionMapper = mock(BatchDayReplaySessionMapper.class);
    entryMapper = mock(BatchDayReplayEntryMapper.class);
    compensationService = mock(CompensationService.class);
    compensationCommandMapper = mock(CompensationCommandMapper.class);
    gracefulShutdown = mock(OrchestratorGracefulShutdown.class);
    when(gracefulShutdown.isDraining()).thenReturn(false);
    properties = new BatchDayReplayDispatchProperties();
    properties.setEnabled(true);
    properties.setSessionBatchSize(10);
    properties.setEntryBatchSize(20);
    BatchDateTimeSupport dateTimeSupport = new BatchDateTimeSupport(
        Clock.systemUTC(), new BatchTimezoneProvider(new BatchTimezoneProperties()));
    dispatcher = new BatchDayReplayDispatcher(
        sessionMapper,
        entryMapper,
        new BatchDayReplayEntryExecutor(
            entryMapper, compensationCommandMapper, compensationService, dateTimeSupport),
        properties,
        gracefulShutdown);
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
  void replayLockUsesConfigurableDurations() throws Exception {
    Method scheduledDispatch =
        BatchDayReplayDispatcher.class.getDeclaredMethod("scheduledDispatch");
    SchedulerLock lock = scheduledDispatch.getAnnotation(SchedulerLock.class);

    assertThat(lock.lockAtLeastFor()).isEqualTo("${batch.replay.dispatch.lock-at-least-for:PT15S}");
    assertThat(lock.lockAtMostFor()).isEqualTo("${batch.replay.dispatch.lock-at-most-for:PT1M}");
    assertThat(properties.getLockAtLeastFor()).isEqualTo(Duration.ofSeconds(15));
    assertThat(properties.getLockAtMostFor()).isEqualTo(Duration.ofMinutes(1));
  }

  @Test
  void noRunningSessionsIsNoop() {
    when(sessionMapper.selectByStatus("RUNNING", 10)).thenReturn(List.of());
    dispatcher.scheduledDispatch();
    verify(entryMapper, never())
        .selectBySessionAndStatus(anyLong(), anyString(), anyString(), anyInt());
  }

  @Test
  void outputsOnlySessionsAreSkippedByDispatcher() {
    BatchDayReplaySessionEntity outputs =
        sessionAt(7L, BatchDayReplayScope.OUTPUTS_ONLY.code(), "RUNNING", "CREATE_NEW_VERSION");
    when(sessionMapper.selectByStatus("RUNNING", 10)).thenReturn(List.of(outputs));
    dispatcher.scheduledDispatch();
    verify(entryMapper, never())
        .selectBySessionAndStatus(anyLong(), anyString(), anyString(), anyInt());
    verify(compensationService, never()).submit(any());
  }

  @Test
  void allFailedSessionDispatchesPendingEntries() {
    BatchDayReplaySessionEntity session =
        sessionAt(8L, "ALL_FAILED", "RUNNING", "CREATE_NEW_VERSION");
    when(sessionMapper.selectByStatus("RUNNING", 10)).thenReturn(List.of(session));
    BatchDayReplayEntryEntity e1 = BatchDayReplayEntryEntity.builder()
        .id(1L)
        .sessionId(8L)
        .tenantId("t1")
        .jobCode("JOB_A")
        .sourceInstanceId(101L)
        .status("PENDING")
        .build();
    BatchDayReplayEntryEntity e2 = BatchDayReplayEntryEntity.builder()
        .id(2L)
        .sessionId(8L)
        .tenantId("t1")
        .jobCode("JOB_B")
        .sourceInstanceId(102L)
        .status("PENDING")
        .build();
    when(entryMapper.selectBySessionAndStatus(8L, "t1", "PENDING", 20)).thenReturn(List.of(e1, e2));
    when(entryMapper.claimPending(anyLong(), eq("t1"), eq(8L), any())).thenReturn(1);
    when(compensationService.submit(any(CompensationSubmitCommand.class))).thenReturn("CMD-OK");

    dispatcher.scheduledDispatch();

    ArgumentCaptor<CompensationSubmitCommand> captor =
        ArgumentCaptor.forClass(CompensationSubmitCommand.class);
    verify(compensationService, times(2)).submit(captor.capture());
    assertThat(captor.getAllValues())
        .allSatisfy(cmd -> assertThat(cmd.replaySessionId()).isEqualTo(8L));
    assertThat(captor.getAllValues())
        .allSatisfy(cmd ->
            assertThat(cmd.resultPolicy()).isEqualTo("CREATE_NEW_VERSION")); // 透传 session policy
    verify(entryMapper, times(2)).claimPending(anyLong(), eq("t1"), eq(8L), any());
  }

  @Test
  void compensationSubmitFailureMarksEntryFailed() {
    BatchDayReplaySessionEntity session =
        sessionAt(9L, "ALL_FAILED", "RUNNING", "CREATE_NEW_VERSION");
    when(sessionMapper.selectByStatus("RUNNING", 10)).thenReturn(List.of(session));
    BatchDayReplayEntryEntity entry = BatchDayReplayEntryEntity.builder()
        .id(1L)
        .sessionId(9L)
        .tenantId("t1")
        .jobCode("JOB_A")
        .sourceInstanceId(101L)
        .status("PENDING")
        .build();
    when(entryMapper.selectBySessionAndStatus(9L, "t1", "PENDING", 20)).thenReturn(List.of(entry));
    when(entryMapper.claimPending(eq(1L), eq("t1"), eq(9L), any())).thenReturn(1);
    when(compensationService.submit(any(CompensationSubmitCommand.class)))
        .thenThrow(new RuntimeException("compensation backpressure"));

    dispatcher.scheduledDispatch();

    verify(entryMapper)
        .updateStatus(eq(1L), eq("FAILED"), any(), any(), any(), any(), any(), any());
  }

  @Test
  void schedulePlanDryRunUsesFrozenSnapshotAndDryRunMode() {
    BatchDayReplaySessionEntity session =
        sessionAt(10L, "ALL", "RUNNING", "DRY_RUN_ONLY").toBuilder()
            .executionMode(BatchDayReplayExecutionMode.DRY_RUN.code())
            .candidateSource(BatchDayReplayCandidateSource.SCHEDULE_PLAN.code())
            .build();
    when(sessionMapper.selectByStatus("RUNNING", 10)).thenReturn(List.of(session));
    BatchDayReplayEntryEntity entry = BatchDayReplayEntryEntity.builder()
        .id(3L)
        .sessionId(10L)
        .tenantId("t1")
        .jobCode("JOB_PLAN")
        .planSnapshot("{\"jobDefinitionVersion\":12,\"defaultParams\":{\"region\":\"cn\"}}")
        .status("PENDING")
        .build();
    when(entryMapper.selectBySessionAndStatus(10L, "t1", "PENDING", 20)).thenReturn(List.of(entry));
    when(entryMapper.claimPending(eq(3L), eq("t1"), eq(10L), any())).thenReturn(1);
    when(compensationService.submit(any(CompensationSubmitCommand.class))).thenReturn("CMD-PLAN");

    dispatcher.scheduledDispatch();

    ArgumentCaptor<CompensationSubmitCommand> captor =
        ArgumentCaptor.forClass(CompensationSubmitCommand.class);
    verify(compensationService).submit(captor.capture());
    CompensationSubmitCommand command = captor.getValue();
    assertThat(command.compensationType()).isEqualTo("BATCH");
    assertThat(command.dryRun()).isTrue();
    assertThat(command.configVersion()).isEqualTo(12);
    assertThat(command.launchParams()).containsEntry("region", "cn");
    assertThat(command.replayEntryId()).isEqualTo(3L);
  }

  @Test
  void eachEntryUsesRequiresNewTransaction() throws Exception {
    Method dispatch = BatchDayReplayEntryExecutor.class.getDeclaredMethod(
        "dispatch", BatchDayReplaySessionEntity.class, BatchDayReplayEntryEntity.class);

    assertThat(dispatch.getAnnotation(Transactional.class).propagation())
        .isEqualTo(Propagation.REQUIRES_NEW);

    Method markFailed = BatchDayReplayEntryExecutor.class.getDeclaredMethod(
        "markFailed",
        BatchDayReplaySessionEntity.class,
        BatchDayReplayEntryEntity.class,
        Exception.class);
    assertThat(markFailed.getAnnotation(Transactional.class).propagation())
        .isEqualTo(Propagation.REQUIRES_NEW);
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  private static BatchDayReplaySessionEntity sessionAt(
      Long id, String scope, String status, String resultPolicy) {
    return BatchDayReplaySessionEntity.builder()
        .id(id)
        .tenantId("t1")
        .calendarCode("CAL")
        .bizDate(LocalDate.of(2026, Month.MAY, 4))
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
