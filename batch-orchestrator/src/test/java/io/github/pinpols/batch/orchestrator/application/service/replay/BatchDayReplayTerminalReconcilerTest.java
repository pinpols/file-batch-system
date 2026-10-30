package io.github.pinpols.batch.orchestrator.application.service.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchTimezoneProperties;
import io.github.pinpols.batch.common.config.BatchTimezoneProvider;
import io.github.pinpols.batch.common.enums.BatchDayReplayExecutionMode;
import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayReplayEntryEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayReplaySessionEntity;
import io.github.pinpols.batch.orchestrator.domain.param.UpdateBatchDayReplayEntryStatusParam;
import io.github.pinpols.batch.orchestrator.domain.param.UpdateBatchDayReplaySessionStatusParam;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayReplayEntryMapper;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayReplaySessionMapper;
import io.github.pinpols.batch.orchestrator.mapper.ReplayOperationMapper;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Month;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;

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
  private ReplayOperationMapper replayOperationMapper;
  private BatchDayReplayTerminalReconciler reconciler;

  @BeforeEach
  void setUp() {
    sessionMapper = mock(BatchDayReplaySessionMapper.class);
    entryMapper = mock(BatchDayReplayEntryMapper.class);
    replayOperationMapper = mock(ReplayOperationMapper.class);
    BatchDateTimeSupport dateTimeSupport = new BatchDateTimeSupport(
        Clock.systemUTC(), new BatchTimezoneProvider(new BatchTimezoneProperties()));
    reconciler = new BatchDayReplayTerminalReconciler(
        sessionMapper, entryMapper, replayOperationMapper, dateTimeSupport);
  }

  @Test
  @DisplayName("作业成功时条目置为成功, 会话计数更新并收敛为成功")
  void shouldMarkEntrySucceededAndCompleteSession_whenJobSucceeds() {
    stubExistingSession(session(7L, "RUNNING", 2));
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
    when(entryMapper.selectByCurrentExecution("t1", 7L, "JOB_A", 1001L)).thenReturn(pendingEntry);
    when(entryMapper.countBySessionAndStatus(7L, ENTRY_SUCCEEDED)).thenReturn(2L);
    when(entryMapper.countBySessionAndStatus(7L, ENTRY_FAILED)).thenReturn(0L);
    when(entryMapper.countBySessionAndStatus(7L, "PENDING")).thenReturn(0L);
    when(entryMapper.countBySessionAndStatus(7L, "RUNNING")).thenReturn(0L);

    reconciler.reconcileOnTerminal("t1", 7L, "JOB_A", 1001L, JOB_SUCCESS);

    verify(entryMapper)
        .updateStatus(argThat(param -> param.getId().equals(11L)
            && param.getToStatus().equals(ENTRY_SUCCEEDED)
            && param.getRerunInstanceId().equals(1001L)));
    ArgumentCaptor<UpdateBatchDayReplaySessionStatusParam> sessionStatusUpdate =
        ArgumentCaptor.forClass(UpdateBatchDayReplaySessionStatusParam.class);
    verify(sessionMapper).updateStatus(sessionStatusUpdate.capture());
    assertThat(sessionStatusUpdate.getValue().getTenantId()).isEqualTo("t1");
    assertThat(sessionStatusUpdate.getValue().getId()).isEqualTo(7L);
    assertThat(sessionStatusUpdate.getValue().getToStatus()).isEqualTo(ENTRY_SUCCEEDED);
    assertThat(sessionStatusUpdate.getValue().getExpectedStatuses())
        .containsExactly("RUNNING", "PARTIAL_FAILED");
  }

  @Test
  @DisplayName("存在失败条目时会话收敛为部分失败")
  void shouldCompleteAsPartialFailed_whenSomeEntriesFailed() {
    stubExistingSession(session(8L, "RUNNING", 2));
    BatchDayReplayEntryEntity entry = BatchDayReplayEntryEntity.builder()
        .id(20L)
        .sessionId(8L)
        .tenantId("t1")
        .jobCode("JOB_X")
        .status("RUNNING")
        .build();
    when(entryMapper.selectByCurrentExecution("t1", 8L, "JOB_X", 2001L)).thenReturn(entry);
    when(entryMapper.countBySessionAndStatus(8L, ENTRY_SUCCEEDED)).thenReturn(1L);
    when(entryMapper.countBySessionAndStatus(8L, ENTRY_FAILED)).thenReturn(1L);
    when(entryMapper.countBySessionAndStatus(8L, "PENDING")).thenReturn(0L);
    when(entryMapper.countBySessionAndStatus(8L, "RUNNING")).thenReturn(0L);

    reconciler.reconcileOnTerminal("t1", 8L, "JOB_X", 2001L, JOB_FAILED);

    ArgumentCaptor<UpdateBatchDayReplayEntryStatusParam> statusUpdate =
        ArgumentCaptor.forClass(UpdateBatchDayReplayEntryStatusParam.class);
    verify(entryMapper).updateStatus(statusUpdate.capture());
    assertThat(statusUpdate.getValue().getId()).isEqualTo(20L);
    assertThat(statusUpdate.getValue().getToStatus()).isEqualTo(ENTRY_FAILED);
    assertThat(statusUpdate.getValue().getRerunInstanceId()).isEqualTo(2001L);
    verify(sessionMapper)
        .updateStatus(argThat(param -> param.getTenantId().equals("t1")
            && param.getId().equals(8L)
            && param.getToStatus().equals(JobInstanceStatus.PARTIAL_FAILED.code())));
  }

  @Test
  @DisplayName("仍有条目在运行时会话不进入终态")
  void shouldKeepSessionRunning_whenEntryStillInFlight() {
    stubExistingSession(session(9L, "RUNNING", 2));
    BatchDayReplayEntryEntity entry = BatchDayReplayEntryEntity.builder()
        .id(30L)
        .sessionId(9L)
        .tenantId("t1")
        .jobCode("JOB_C")
        .status("RUNNING")
        .build();
    when(entryMapper.selectByCurrentExecution("t1", 9L, "JOB_C", 3001L)).thenReturn(entry);
    when(entryMapper.countBySessionAndStatus(9L, ENTRY_SUCCEEDED)).thenReturn(1L);
    when(entryMapper.countBySessionAndStatus(9L, ENTRY_FAILED)).thenReturn(0L);
    when(entryMapper.countBySessionAndStatus(9L, "PENDING")).thenReturn(0L);
    when(entryMapper.countBySessionAndStatus(9L, "RUNNING")).thenReturn(1L);

    reconciler.reconcileOnTerminal("t1", 9L, "JOB_C", 3001L, JOB_SUCCESS);

    verify(sessionMapper, times(1))
        .updateCounts(ArgumentMatchers.argThat(parameters -> parameters != null
            && Objects.equals(parameters.tenantId(), "t1")
            && Objects.equals(parameters.id(), 9L)
            && Objects.equals(parameters.succeededCount(), 1)
            && Objects.equals(parameters.failedCount(), 0)
            && Objects.equals(parameters.inFlightCount(), 1)
            && Objects.equals(parameters.totalCount(), 2)));
    verify(sessionMapper, never()).updateStatus(any(UpdateBatchDayReplaySessionStatusParam.class));
  }

  @Test
  @DisplayName("失败条目恢复后, 部分失败的会话重新收敛为成功")
  void shouldMoveBackToSucceeded_whenFailedEntryRecovered() {
    stubExistingSession(session(10L, JobInstanceStatus.PARTIAL_FAILED.code(), 1));
    BatchDayReplayEntryEntity entry = BatchDayReplayEntryEntity.builder()
        .id(40L)
        .sessionId(10L)
        .tenantId("t1")
        .jobCode("JOB_RECOVERED")
        .status(ENTRY_FAILED)
        .build();
    when(entryMapper.selectByCurrentExecution("t1", 10L, "JOB_RECOVERED", 4001L))
        .thenReturn(entry);
    when(entryMapper.countBySessionAndStatus(10L, ENTRY_SUCCEEDED)).thenReturn(1L);
    when(entryMapper.countBySessionAndStatus(10L, ENTRY_FAILED)).thenReturn(0L);
    when(entryMapper.countBySessionAndStatus(10L, "PENDING")).thenReturn(0L);
    when(entryMapper.countBySessionAndStatus(10L, "RUNNING")).thenReturn(0L);

    reconciler.reconcileOnTerminal("t1", 10L, "JOB_RECOVERED", 4001L, JOB_SUCCESS);

    verify(sessionMapper)
        .updateStatus(argThat(param -> param.getTenantId().equals("t1")
            && param.getId().equals(10L)
            && param.getToStatus().equals(ENTRY_SUCCEEDED)
            && param.getExpectedStatuses().equals(List.of("RUNNING", "PARTIAL_FAILED"))));
  }

  @Test
  @DisplayName("会话不存在时对账不产生任何写入")
  void shouldDoNothing_whenSessionMissing() {
    when(sessionMapper.selectById("t1", 999L)).thenReturn(null);

    reconciler.reconcileOnTerminal("t1", 999L, "JOB_A", 100L, JOB_SUCCESS);

    verify(sessionMapper).selectById("t1", 999L);
    verify(sessionMapper, never()).selectForUpdate(anyString(), anyLong());
    verifyNoInteractions(entryMapper);
    verify(sessionMapper, never())
        .updateCounts(ArgumentMatchers.argThat(
            parameters -> parameters != null && parameters.tenantId() != null));
  }

  @Test
  @DisplayName("会话下没有对应条目时对账安全返回")
  void shouldDoNothing_whenEntryMissing() {
    stubExistingSession(session(50L, "RUNNING", 1));
    when(entryMapper.selectByCurrentExecution("t1", 50L, "JOB_X", 9999L)).thenReturn(null);

    reconciler.reconcileOnTerminal("t1", 50L, "JOB_X", 9999L, JOB_SUCCESS);

    verify(entryMapper).selectByCurrentExecution("t1", 50L, "JOB_X", 9999L);
    verify(entryMapper, never()).updateStatus(any(UpdateBatchDayReplayEntryStatusParam.class));
    verify(sessionMapper, never())
        .updateCounts(ArgumentMatchers.argThat(
            parameters -> parameters != null && parameters.tenantId() != null));
  }

  @Test
  @DisplayName("参数缺失时对账直接返回, 不查询会话")
  void shouldShortCircuit_whenArgumentsInvalid() {
    reconciler.reconcileOnTerminal(null, 1L, "JOB", 1L, JOB_SUCCESS);
    reconciler.reconcileOnTerminal("t1", null, "JOB", 1L, JOB_SUCCESS);
    reconciler.reconcileOnTerminal("t1", 1L, null, 1L, JOB_SUCCESS);
    reconciler.reconcileOnTerminal("t1", 1L, "JOB", 1L, null);

    verifyNoInteractions(sessionMapper, replayOperationMapper, entryMapper);
  }

  @Test
  @DisplayName("试运行会话接受试运行终态并据此收敛条目")
  void shouldAcceptDryRunStatus_whenSessionRunsInDryRun() {
    BatchDayReplaySessionEntity dryRunSession = session(60L, "RUNNING", 1).toBuilder()
        .executionMode(BatchDayReplayExecutionMode.DRY_RUN.code())
        .build();
    stubExistingSession(dryRunSession);
    BatchDayReplayEntryEntity entry = BatchDayReplayEntryEntity.builder()
        .id(61L)
        .sessionId(60L)
        .tenantId("t1")
        .jobCode("JOB_DRY")
        .status("RUNNING")
        .build();
    when(entryMapper.selectByCurrentExecution("t1", 60L, "JOB_DRY", 6001L)).thenReturn(entry);
    when(entryMapper.countBySessionAndStatus(60L, ENTRY_SUCCEEDED)).thenReturn(1L);
    when(entryMapper.countBySessionAndStatus(60L, ENTRY_FAILED)).thenReturn(0L);
    when(entryMapper.countBySessionAndStatus(60L, "PENDING")).thenReturn(0L);
    when(entryMapper.countBySessionAndStatus(60L, "RUNNING")).thenReturn(0L);

    reconciler.reconcileOnTerminal(
        "t1", 60L, "JOB_DRY", 6001L, JobInstanceStatus.SUCCESS_DRY_RUN.code());

    verify(entryMapper)
        .updateStatus(argThat(param -> param.getId().equals(61L)
            && param.getToStatus().equals(ENTRY_SUCCEEDED)
            && param.getRerunInstanceId().equals(6001L)));
  }

  @Test
  @DisplayName("试运行会话收到正式终态时按模式不匹配处理, 不更新会话")
  void shouldSkipNormalStatus_whenSessionRunsInDryRun() {
    BatchDayReplaySessionEntity dryRunSession = session(70L, "RUNNING", 1).toBuilder()
        .executionMode(BatchDayReplayExecutionMode.DRY_RUN.code())
        .build();
    stubExistingSession(dryRunSession);
    BatchDayReplayEntryEntity entry = BatchDayReplayEntryEntity.builder()
        .id(71L)
        .sessionId(70L)
        .tenantId("t1")
        .jobCode("JOB_DRY")
        .status("RUNNING")
        .build();
    when(entryMapper.selectByCurrentExecution("t1", 70L, "JOB_DRY", 7001L)).thenReturn(entry);
    when(entryMapper.countBySessionAndStatus(70L, ENTRY_SUCCEEDED)).thenReturn(0L);
    when(entryMapper.countBySessionAndStatus(70L, ENTRY_FAILED)).thenReturn(0L);
    when(entryMapper.countBySessionAndStatus(70L, "PENDING")).thenReturn(0L);
    when(entryMapper.countBySessionAndStatus(70L, "RUNNING")).thenReturn(1L);

    reconciler.reconcileOnTerminal("t1", 70L, "JOB_DRY", 7001L, JobInstanceStatus.SUCCESS.code());

    verify(entryMapper).selectByCurrentExecution("t1", 70L, "JOB_DRY", 7001L);
    verify(entryMapper, never()).updateStatus(any());
    verify(sessionMapper, never()).updateStatus(any(UpdateBatchDayReplaySessionStatusParam.class));
  }

  @Test
  @DisplayName("先锁父操作再锁日会话，准入对账后刷新父操作进度")
  void shouldLockParentBeforeChildAndRefreshProgress_whenSessionBelongsToOperation() {
    BatchDayReplaySessionEntity child =
        session(80L, "RUNNING", 1).toBuilder().replayOperationId(90L).build();
    stubExistingSession(child);
    when(replayOperationMapper.lockForProgressRefresh("t1", 90L)).thenReturn(90L);
    when(entryMapper.countBySessionAndStatus(80L, "RUNNING")).thenReturn(1L);

    reconciler.reconcileAdmissions("t1", 80L);

    InOrder locksAndWrites = inOrder(sessionMapper, replayOperationMapper, entryMapper);
    locksAndWrites.verify(sessionMapper).selectById("t1", 80L);
    locksAndWrites.verify(replayOperationMapper).lockForProgressRefresh("t1", 90L);
    locksAndWrites.verify(sessionMapper).selectForUpdate("t1", 80L);
    locksAndWrites.verify(entryMapper).bindCurrentExecutions(eq("t1"), eq(80L), any());
    locksAndWrites.verify(replayOperationMapper).refreshFromChildSessions(eq("t1"), eq(90L), any());
  }

  @Test
  @DisplayName("父操作不存在时拒绝继续写入日会话")
  void shouldRejectReconciliation_whenParentOperationCannotBeLocked() {
    BatchDayReplaySessionEntity child =
        session(80L, "RUNNING", 1).toBuilder().replayOperationId(90L).build();
    when(sessionMapper.selectById("t1", 80L)).thenReturn(child);
    when(replayOperationMapper.lockForProgressRefresh("t1", 90L)).thenReturn(null);

    assertThatThrownBy(() -> reconciler.reconcileAdmissions("t1", 80L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("replay operation not found");

    verify(sessionMapper, never()).selectForUpdate(anyString(), anyLong());
    verifyNoInteractions(entryMapper);
  }

  @Test
  @DisplayName("取得行锁后父操作身份改变时拒绝写入")
  void shouldRejectReconciliation_whenParentIdentityChangesWhileLocking() {
    BatchDayReplaySessionEntity candidate =
        session(80L, "RUNNING", 1).toBuilder().replayOperationId(90L).build();
    BatchDayReplaySessionEntity changed =
        candidate.toBuilder().replayOperationId(91L).build();
    when(sessionMapper.selectById("t1", 80L)).thenReturn(candidate);
    when(replayOperationMapper.lockForProgressRefresh("t1", 90L)).thenReturn(90L);
    when(sessionMapper.selectForUpdate("t1", 80L)).thenReturn(changed);

    assertThatThrownBy(() -> reconciler.reconcileAdmissions("t1", 80L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("replay operation changed");

    verifyNoInteractions(entryMapper);
    verify(replayOperationMapper, never()).refreshFromChildSessions(anyString(), anyLong(), any());
  }

  private void stubExistingSession(BatchDayReplaySessionEntity session) {
    when(sessionMapper.selectById(session.tenantId(), session.id())).thenReturn(session);
    when(sessionMapper.selectForUpdate(session.tenantId(), session.id())).thenReturn(session);
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
