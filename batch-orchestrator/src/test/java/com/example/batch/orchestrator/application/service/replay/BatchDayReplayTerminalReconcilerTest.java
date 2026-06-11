package com.example.batch.orchestrator.application.service.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
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
import com.example.batch.orchestrator.domain.entity.BatchDayReplayEntryEntity;
import com.example.batch.orchestrator.domain.entity.BatchDayReplaySessionEntity;
import com.example.batch.orchestrator.mapper.BatchDayReplayEntryMapper;
import com.example.batch.orchestrator.mapper.BatchDayReplaySessionMapper;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BatchDayReplayTerminalReconcilerTest {

  // 字面量集中(单文件内,不进 TestConstants 全局):
  //   ENTRY_* = batch_day_replay_entry.status 状态(SUCCEEDED / FAILED)
  //   JOB_*   = job_instance 终态(SUCCESS / FAILED)— 传入 reconcileOnTerminal 的 lastInstanceStatus
  private static final String ENTRY_SUCCEEDED = "SUCCEEDED";
  private static final String ENTRY_FAILED = "FAILED";
  private static final String JOB_SUCCESS = "SUCCESS";
  private static final String JOB_FAILED = "FAILED";

  private BatchDayReplaySessionMapper sessionMapper;
  private BatchDayReplayEntryMapper entryMapper;
  private BatchDayReplayTerminalReconciler reconciler;

  @BeforeEach
  void setUp() {
    sessionMapper = mock(BatchDayReplaySessionMapper.class);
    entryMapper = mock(BatchDayReplayEntryMapper.class);
    BatchDateTimeSupport dateTimeSupport =
        new BatchDateTimeSupport(
            Clock.systemUTC(), new BatchTimezoneProvider(new BatchTimezoneProperties()));
    reconciler = new BatchDayReplayTerminalReconciler(sessionMapper, entryMapper, dateTimeSupport);
  }

  @Test
  void successInstanceMovesEntryToSucceededAndCompletesSession() {
    when(sessionMapper.selectById("t1", 7L)).thenReturn(session(7L, "RUNNING", 2));
    BatchDayReplayEntryEntity pendingEntry =
        BatchDayReplayEntryEntity.builder()
            .id(11L)
            .sessionId(7L)
            .tenantId("t1")
            .jobCode("JOB_A")
            .status("RUNNING")
            .build();
    BatchDayReplayEntryEntity otherEntry =
        BatchDayReplayEntryEntity.builder()
            .id(12L)
            .sessionId(7L)
            .tenantId("t1")
            .jobCode("JOB_B")
            .status(ENTRY_SUCCEEDED)
            .build();
    when(entryMapper.selectBySessionId(7L)).thenReturn(List.of(pendingEntry, otherEntry));
    when(entryMapper.countBySessionAndStatus(7L, ENTRY_SUCCEEDED)).thenReturn(2L);
    when(entryMapper.countBySessionAndStatus(7L, ENTRY_FAILED)).thenReturn(0L);
    when(entryMapper.countBySessionAndStatus(7L, "PENDING")).thenReturn(0L);
    when(entryMapper.countBySessionAndStatus(7L, "RUNNING")).thenReturn(0L);

    reconciler.reconcileOnTerminal("t1", 7L, "JOB_A", 1001L, JOB_SUCCESS);

    verify(entryMapper)
        .updateStatus(
            anyString(),
            eq(11L),
            eq(ENTRY_SUCCEEDED),
            eq(1001L),
            any(),
            any(),
            any(),
            any(),
            any());
    ArgumentCaptor<List<String>> expected = ArgumentCaptor.captor();
    verify(sessionMapper)
        .updateStatus(
            eq("t1"), eq(7L), eq(ENTRY_SUCCEEDED), expected.capture(), any(), any(), any(), any());
    assertThat(expected.getValue()).containsExactly("RUNNING");
  }

  @Test
  void failedInstanceCompletesSessionAsPartialFailedWhenSomeFailed() {
    when(sessionMapper.selectById("t1", 8L)).thenReturn(session(8L, "RUNNING", 2));
    BatchDayReplayEntryEntity entry =
        BatchDayReplayEntryEntity.builder()
            .id(20L)
            .sessionId(8L)
            .tenantId("t1")
            .jobCode("JOB_X")
            .status("RUNNING")
            .build();
    when(entryMapper.selectBySessionId(8L)).thenReturn(List.of(entry));
    when(entryMapper.countBySessionAndStatus(8L, ENTRY_SUCCEEDED)).thenReturn(1L);
    when(entryMapper.countBySessionAndStatus(8L, ENTRY_FAILED)).thenReturn(1L);
    when(entryMapper.countBySessionAndStatus(8L, "PENDING")).thenReturn(0L);
    when(entryMapper.countBySessionAndStatus(8L, "RUNNING")).thenReturn(0L);

    reconciler.reconcileOnTerminal("t1", 8L, "JOB_X", 2001L, JOB_FAILED);

    verify(entryMapper)
        .updateStatus(
            anyString(), eq(20L), eq(ENTRY_FAILED), eq(2001L), any(), any(), any(), any(), any());
    verify(sessionMapper)
        .updateStatus(
            eq("t1"), eq(8L), eq("PARTIAL_FAILED"), anyList(), any(), any(), any(), any());
  }

  @Test
  void runningEntryStillInFlightDoesNotTerminateSession() {
    when(sessionMapper.selectById("t1", 9L)).thenReturn(session(9L, "RUNNING", 2));
    BatchDayReplayEntryEntity entry =
        BatchDayReplayEntryEntity.builder()
            .id(30L)
            .sessionId(9L)
            .tenantId("t1")
            .jobCode("JOB_C")
            .status("RUNNING")
            .build();
    when(entryMapper.selectBySessionId(9L)).thenReturn(List.of(entry));
    when(entryMapper.countBySessionAndStatus(9L, ENTRY_SUCCEEDED)).thenReturn(1L);
    when(entryMapper.countBySessionAndStatus(9L, ENTRY_FAILED)).thenReturn(0L);
    when(entryMapper.countBySessionAndStatus(9L, "PENDING")).thenReturn(0L);
    when(entryMapper.countBySessionAndStatus(9L, "RUNNING")).thenReturn(1L);

    reconciler.reconcileOnTerminal("t1", 9L, "JOB_C", 3001L, JOB_SUCCESS);

    verify(sessionMapper, times(1))
        .updateCounts(eq("t1"), eq(9L), eq(1), eq(0), eq(1), eq(2), any());
    verify(sessionMapper, never())
        .updateStatus(anyString(), anyLong(), anyString(), anyList(), any(), any(), any(), any());
  }

  @Test
  void unknownReplaySessionIsNoop() {
    when(sessionMapper.selectById("t1", 999L)).thenReturn(null);

    reconciler.reconcileOnTerminal("t1", 999L, "JOB_A", 100L, JOB_SUCCESS);

    verify(entryMapper, never()).selectBySessionId(anyLong());
    verify(sessionMapper, never())
        .updateCounts(anyString(), anyLong(), anyInt(), anyInt(), anyInt(), anyInt(), any());
  }

  @Test
  void missingEntryIsNoopButSafe() {
    when(sessionMapper.selectById("t1", 50L)).thenReturn(session(50L, "RUNNING", 1));
    when(entryMapper.selectBySessionId(50L)).thenReturn(List.of());

    reconciler.reconcileOnTerminal("t1", 50L, "JOB_X", 9999L, JOB_SUCCESS);

    verify(entryMapper, never())
        .updateStatus(
            anyString(), anyLong(), anyString(), any(), any(), any(), any(), any(), any());
    verify(sessionMapper, never())
        .updateCounts(anyString(), anyLong(), anyInt(), anyInt(), anyInt(), anyInt(), any());
  }

  @Test
  void invalidArgumentsShortCircuit() {
    reconciler.reconcileOnTerminal(null, 1L, "JOB", 1L, JOB_SUCCESS);
    reconciler.reconcileOnTerminal("t1", null, "JOB", 1L, JOB_SUCCESS);
    reconciler.reconcileOnTerminal("t1", 1L, null, 1L, JOB_SUCCESS);
    reconciler.reconcileOnTerminal("t1", 1L, "JOB", 1L, null);

    verify(sessionMapper, never()).selectById(anyString(), anyLong());
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  private static BatchDayReplaySessionEntity session(Long id, String status, int totalCount) {
    return BatchDayReplaySessionEntity.builder()
        .id(id)
        .tenantId("t1")
        .calendarCode("CAL")
        .bizDate(LocalDate.of(2026, 5, 4))
        .scope("ALL_FAILED")
        .resultPolicy("CREATE_NEW_VERSION")
        .configVersionPolicy("USE_ORIGINAL_CONFIG")
        .reason("...")
        .status(status)
        .totalCount(totalCount)
        .succeededCount(0)
        .failedCount(0)
        .inFlightCount(totalCount)
        .requestedBy("ops")
        .build();
  }
}
