package io.github.pinpols.batch.orchestrator.application.service.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.pinpols.batch.common.config.BatchTimezoneProperties;
import io.github.pinpols.batch.common.config.BatchTimezoneProvider;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayReplayEntryEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayReplaySessionEntity;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayReplayEntryMapper;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayReplaySessionMapper;
import io.github.pinpols.batch.orchestrator.mapper.ReplayOperationMapper;
import java.time.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
@DisplayName("重放回填身份：只接受原请求下的当前执行，不接受来源或作业编码猜测")
class BatchDayReplaySameJobCodeMultiEntryTest {
  @Mock
  private BatchDayReplaySessionMapper sessionMapper;

  @Mock
  private BatchDayReplayEntryMapper entryMapper;

  @Mock
  private ReplayOperationMapper replayOperationMapper;

  private BatchDayReplayTerminalReconciler reconciler;

  @BeforeEach
  void prepare() {
    reconciler = new BatchDayReplayTerminalReconciler(
        sessionMapper,
        entryMapper,
        replayOperationMapper,
        new BatchDateTimeSupport(
            Clock.systemUTC(), new BatchTimezoneProvider(new BatchTimezoneProperties())));
  }

  @Test
  @DisplayName("来源实例的旧终态不能完成新重放条目")
  void shouldRejectSourceTerminal_whenOnlyHistoricalSourceMatches() {
    when(sessionMapper.selectForUpdate("t1", 7L)).thenReturn(session("REPLAY"));
    reconciler.reconcileOnTerminal("t1", 7L, "JOB_DUP", 5001L, "SUCCESS");
    verify(entryMapper).selectByCurrentExecution("t1", 7L, "JOB_DUP", 5001L);
    verify(entryMapper, never()).updateStatus(any());
    verify(entryMapper, never()).selectBySessionId(anyLong());
  }

  @Test
  @DisplayName("当前执行的精确关联用于更新，不能扫描同编码第一条")
  void shouldCompleteExactEntry_whenCurrentExecutionMatches() {
    when(sessionMapper.selectForUpdate("t1", 7L)).thenReturn(session("REPLAY"));
    when(entryMapper.selectByCurrentExecution("t1", 7L, "JOB_DUP", 7100L)).thenReturn(entry());
    when(entryMapper.countBySessionAndStatus(7L, "SUCCEEDED")).thenReturn(1L);
    reconciler.reconcileOnTerminal("t1", 7L, "JOB_DUP", 7100L, "SUCCESS");
    verify(entryMapper)
        .updateStatus(argThat(p -> p.getId().equals(102L)
            && p.getRerunInstanceId().equals(7100L)
            && p.getToStatus().equals("SUCCEEDED")));
    verify(entryMapper, never()).selectBySessionId(anyLong());
  }

  @Test
  @DisplayName("只有 jobCode 相同但执行身份未知时不写入")
  void shouldRejectUnboundTerminal_whenOnlyJobCodeMatches() {
    when(sessionMapper.selectForUpdate("t1", 7L)).thenReturn(session("REPLAY"));
    reconciler.reconcileOnTerminal("t1", 7L, "JOB_DUP", 8000L, "SUCCESS");
    verify(entryMapper, never()).selectBySessionId(anyLong());
    verify(entryMapper, never()).updateStatus(any());
    verify(sessionMapper, never()).updateStatus(any());
  }

  @ParameterizedTest
  @CsvSource({"DRY_RUN,SUCCESS", "REPLAY,SUCCESS_DRY_RUN", "REPLAY,RUNNING"})
  @DisplayName("模式不匹配或非终态时不回退条目、不写完成时间")
  void shouldPreserveEntry_whenTerminalModeDoesNotMatch(String mode, String status) {
    when(sessionMapper.selectForUpdate("t1", 7L)).thenReturn(session(mode));
    when(entryMapper.selectByCurrentExecution("t1", 7L, "JOB_DUP", 7100L)).thenReturn(entry());
    reconciler.reconcileOnTerminal("t1", 7L, "JOB_DUP", 7100L, status);
    verify(entryMapper, never()).updateStatus(any());
    verify(entryMapper, never()).countBySessionAndStatus(anyLong(), anyString());
  }

  @Test
  @DisplayName("缺少执行 Job 身份时不读取来源或修改会话")
  void shouldRejectMissingExecution_whenJobIdentityIsAbsent() {
    reconciler.reconcileOnTerminal("t1", 7L, "JOB_DUP", null, "SUCCESS");
    verifyNoInteractions(sessionMapper, entryMapper);
  }

  @Test
  @DisplayName("回填加入 REPORT 事务，不能在 REPORT 回滚后单独提交")
  void shouldJoinTerminalTransaction_whenReconciliationIsInvoked() throws Exception {
    var annotation = BatchDayReplayTerminalReconciler.class
        .getMethod(
            "reconcileOnTerminal", String.class, Long.class, String.class, Long.class, String.class)
        .getAnnotation(Transactional.class);
    assertThat(annotation.propagation()).isEqualTo(Propagation.REQUIRED);
  }

  private static BatchDayReplayEntryEntity entry() {
    return BatchDayReplayEntryEntity.builder()
        .id(102L)
        .sessionId(7L)
        .tenantId("t1")
        .jobCode("JOB_DUP")
        .sourceInstanceId(5001L)
        .rerunInstanceId(7100L)
        .status("RUNNING")
        .build();
  }

  private static BatchDayReplaySessionEntity session(String mode) {
    return BatchDayReplaySessionEntity.builder()
        .id(7L)
        .tenantId("t1")
        .status("RUNNING")
        .executionMode(mode)
        .totalCount(1)
        .build();
  }
}
