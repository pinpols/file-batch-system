package io.github.pinpols.batch.orchestrator.application.service.replay;

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

import io.github.pinpols.batch.common.config.BatchTimezoneProperties;
import io.github.pinpols.batch.common.config.BatchTimezoneProvider;
import io.github.pinpols.batch.common.enums.BatchDayReplayExecutionMode;
import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayReplayEntryEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayReplaySessionEntity;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayReplayEntryMapper;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayReplaySessionMapper;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Month;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("批量日重放终态对账器: 条目状态收敛与会话终态判定口径")
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
    BatchDateTimeSupport dateTimeSupport = new BatchDateTimeSupport(
        Clock.systemUTC(), new BatchTimezoneProvider(new BatchTimezoneProperties()));
    reconciler = new BatchDayReplayTerminalReconciler(sessionMapper, entryMapper, dateTimeSupport);
  }

  @Test
  @DisplayName("作业成功时条目置为成功, 会话计数更新并收敛为成功")
  void shouldMarkEntrySucceededAndCompleteSession_whenJobSucceeds() {
    when(sessionMapper.selectById("t1", 7L)).thenReturn(session(7L, "RUNNING", 2));
    BatchDayReplayEntryEntity pendingEntry = BatchDayReplayEntryEntity.builder()
        .id(11L)
        .sessionId(7L)
        .tenantId("t1")
        .jobCode("JOB_A")
        .status("RUNNING")
        .build();
    BatchDayReplayEntryEntity otherEntry = BatchDayReplayEntryEntity.builder()
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
        .updateStatus(eq(11L), eq(ENTRY_SUCCEEDED), eq(1001L), any(), any(), any(), any(), any());
    ArgumentCaptor<List<String>> expected = ArgumentCaptor.captor();
    verify(sessionMapper)
        .updateStatus(
            eq("t1"), eq(7L), eq(ENTRY_SUCCEEDED), expected.capture(), any(), any(), any(), any());
    assertThat(expected.getValue()).containsExactly("RUNNING", "PARTIAL_FAILED");
  }

  @Test
  @DisplayName("存在失败条目时会话收敛为部分失败")
  void shouldCompleteAsPartialFailed_whenSomeEntriesFailed() {
    when(sessionMapper.selectById("t1", 8L)).thenReturn(session(8L, "RUNNING", 2));
    BatchDayReplayEntryEntity entry = BatchDayReplayEntryEntity.builder()
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
        .updateStatus(eq(20L), eq(ENTRY_FAILED), eq(2001L), any(), any(), any(), any(), any());
    verify(sessionMapper)
        .updateStatus(
            eq("t1"),
            eq(8L),
            eq(JobInstanceStatus.PARTIAL_FAILED.code()),
            anyList(),
            any(),
            any(),
            any(),
            any());
  }

  @Test
  @DisplayName("仍有条目在运行时会话不进入终态")
  void shouldKeepSessionRunning_whenEntryStillInFlight() {
    when(sessionMapper.selectById("t1", 9L)).thenReturn(session(9L, "RUNNING", 2));
    BatchDayReplayEntryEntity entry = BatchDayReplayEntryEntity.builder()
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
  @DisplayName("失败条目恢复后, 部分失败的会话重新收敛为成功")
  void shouldMoveBackToSucceeded_whenFailedEntryRecovered() {
    when(sessionMapper.selectById("t1", 10L))
        .thenReturn(session(10L, JobInstanceStatus.PARTIAL_FAILED.code(), 1));
    BatchDayReplayEntryEntity entry = BatchDayReplayEntryEntity.builder()
        .id(40L)
        .sessionId(10L)
        .tenantId("t1")
        .jobCode("JOB_RECOVERED")
        .status(ENTRY_FAILED)
        .build();
    when(entryMapper.selectBySessionId(10L)).thenReturn(List.of(entry));
    when(entryMapper.countBySessionAndStatus(10L, ENTRY_SUCCEEDED)).thenReturn(1L);
    when(entryMapper.countBySessionAndStatus(10L, ENTRY_FAILED)).thenReturn(0L);
    when(entryMapper.countBySessionAndStatus(10L, "PENDING")).thenReturn(0L);
    when(entryMapper.countBySessionAndStatus(10L, "RUNNING")).thenReturn(0L);

    reconciler.reconcileOnTerminal("t1", 10L, "JOB_RECOVERED", 4001L, JOB_SUCCESS);

    verify(sessionMapper)
        .updateStatus(
            eq("t1"),
            eq(10L),
            eq(ENTRY_SUCCEEDED),
            eq(List.of("RUNNING", "PARTIAL_FAILED")),
            any(),
            any(),
            any(),
            any());
  }

  @Test
  @DisplayName("会话不存在时对账不产生任何写入")
  void shouldDoNothing_whenSessionMissing() {
    when(sessionMapper.selectById("t1", 999L)).thenReturn(null);

    reconciler.reconcileOnTerminal("t1", 999L, "JOB_A", 100L, JOB_SUCCESS);

    verify(entryMapper, never()).selectBySessionId(anyLong());
    verify(sessionMapper, never())
        .updateCounts(anyString(), anyLong(), anyInt(), anyInt(), anyInt(), anyInt(), any());
  }

  @Test
  @DisplayName("会话下没有对应条目时对账安全返回")
  void shouldDoNothing_whenEntryMissing() {
    when(sessionMapper.selectById("t1", 50L)).thenReturn(session(50L, "RUNNING", 1));
    when(entryMapper.selectBySessionId(50L)).thenReturn(List.of());

    reconciler.reconcileOnTerminal("t1", 50L, "JOB_X", 9999L, JOB_SUCCESS);

    verify(entryMapper, never())
        .updateStatus(anyLong(), anyString(), any(), any(), any(), any(), any(), any());
    verify(sessionMapper, never())
        .updateCounts(anyString(), anyLong(), anyInt(), anyInt(), anyInt(), anyInt(), any());
  }

  @Test
  @DisplayName("参数缺失时对账直接返回, 不查询会话")
  void shouldShortCircuit_whenArgumentsInvalid() {
    reconciler.reconcileOnTerminal(null, 1L, "JOB", 1L, JOB_SUCCESS);
    reconciler.reconcileOnTerminal("t1", null, "JOB", 1L, JOB_SUCCESS);
    reconciler.reconcileOnTerminal("t1", 1L, null, 1L, JOB_SUCCESS);
    reconciler.reconcileOnTerminal("t1", 1L, "JOB", 1L, null);

    verify(sessionMapper, never()).selectById(anyString(), anyLong());
  }

  @Test
  @DisplayName("试运行会话接受试运行终态并据此收敛条目")
  void shouldAcceptDryRunStatus_whenSessionRunsInDryRun() {
    BatchDayReplaySessionEntity dryRunSession = session(60L, "RUNNING", 1).toBuilder()
        .executionMode(BatchDayReplayExecutionMode.DRY_RUN.code())
        .build();
    when(sessionMapper.selectById("t1", 60L)).thenReturn(dryRunSession);
    BatchDayReplayEntryEntity entry = BatchDayReplayEntryEntity.builder()
        .id(61L)
        .sessionId(60L)
        .tenantId("t1")
        .jobCode("JOB_DRY")
        .status("RUNNING")
        .build();
    when(entryMapper.selectBySessionId(60L)).thenReturn(List.of(entry));
    when(entryMapper.countBySessionAndStatus(60L, ENTRY_SUCCEEDED)).thenReturn(1L);
    when(entryMapper.countBySessionAndStatus(60L, ENTRY_FAILED)).thenReturn(0L);
    when(entryMapper.countBySessionAndStatus(60L, "PENDING")).thenReturn(0L);
    when(entryMapper.countBySessionAndStatus(60L, "RUNNING")).thenReturn(0L);

    reconciler.reconcileOnTerminal(
        "t1", 60L, "JOB_DRY", 6001L, JobInstanceStatus.SUCCESS_DRY_RUN.code());

    verify(entryMapper)
        .updateStatus(eq(61L), eq(ENTRY_SUCCEEDED), eq(6001L), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("试运行会话收到正式终态时按模式不匹配处理, 不更新会话")
  void shouldSkipNormalStatus_whenSessionRunsInDryRun() {
    BatchDayReplaySessionEntity dryRunSession = session(70L, "RUNNING", 1).toBuilder()
        .executionMode(BatchDayReplayExecutionMode.DRY_RUN.code())
        .build();
    when(sessionMapper.selectById("t1", 70L)).thenReturn(dryRunSession);
    BatchDayReplayEntryEntity entry = BatchDayReplayEntryEntity.builder()
        .id(71L)
        .sessionId(70L)
        .tenantId("t1")
        .jobCode("JOB_DRY")
        .status("RUNNING")
        .build();
    when(entryMapper.selectBySessionId(70L)).thenReturn(List.of(entry));
    when(entryMapper.countBySessionAndStatus(70L, ENTRY_SUCCEEDED)).thenReturn(0L);
    when(entryMapper.countBySessionAndStatus(70L, ENTRY_FAILED)).thenReturn(0L);
    when(entryMapper.countBySessionAndStatus(70L, "PENDING")).thenReturn(0L);
    when(entryMapper.countBySessionAndStatus(70L, "RUNNING")).thenReturn(1L);

    reconciler.reconcileOnTerminal("t1", 70L, "JOB_DRY", 7001L, JobInstanceStatus.SUCCESS.code());

    verify(entryMapper)
        .updateStatus(eq(71L), eq("RUNNING"), eq(7001L), any(), any(), any(), any(), any());
    verify(sessionMapper, never())
        .updateStatus(eq("t1"), eq(70L), anyString(), anyList(), any(), any(), any(), any());
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  private static BatchDayReplaySessionEntity session(Long id, String status, int totalCount) {
    return BatchDayReplaySessionEntity.builder()
        .id(id)
        .tenantId("t1")
        .calendarCode("CAL")
        .bizDate(LocalDate.of(2026, Month.MAY, 4))
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
