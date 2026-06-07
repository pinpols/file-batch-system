package com.example.batch.orchestrator.application.service.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * P0-2 复现/守护测试: 当 session 中 (sessionId, sourceInstanceId) 能精确匹配 entry 时, reconciler 必须按
 * source_instance_id 命中而非线性扫第一条 jobCode entry。
 *
 * <p>历史 bug: {@code findEntry} 只按 (sessionId, tenantId, jobCode) 线性扫第一条。 虽然当前 schema {@code
 * uk_replay_entry_session_job} 阻止同 jobCode 多 entry,但若未来放宽该约束允许 同 jobCode 多 source instance,旧逻辑会把第二条
 * source 的终态错回填到第一条 entry,卡 session 永远 inFlight>0。
 *
 * <p>本测试构造"同 sessionId + 同 jobCode + 两条不同 sourceInstanceId"的场景, 断言 reconciler 通过 mapper 的
 * selectBySessionAndSourceInstanceId 精确命中, 而不是回到 selectBySessionId 线性扫。
 */
class BatchDayReplaySameJobCodeMultiEntryTest {

  private static final String ENTRY_SUCCEEDED = "SUCCEEDED";
  private static final String JOB_SUCCESS = "SUCCESS";

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
  @DisplayName("同 jobCode 多 entry 时按 sourceInstanceId 精确回填,不踩第一条")
  void shouldReconcileBySourceInstanceId_whenMultipleEntriesShareSameJobCode() {
    // 准备: 两条 entry,同 jobCode,不同 sourceInstanceId
    BatchDayReplayEntryEntity entryA =
        BatchDayReplayEntryEntity.builder()
            .id(101L)
            .sessionId(7L)
            .tenantId("t1")
            .jobCode("JOB_DUP")
            .sourceInstanceId(5000L)
            .status("RUNNING")
            .build();
    BatchDayReplayEntryEntity entryB =
        BatchDayReplayEntryEntity.builder()
            .id(102L)
            .sessionId(7L)
            .tenantId("t1")
            .jobCode("JOB_DUP")
            .sourceInstanceId(5001L)
            .status("RUNNING")
            .build();
    when(sessionMapper.selectById("t1", 7L)).thenReturn(session(7L, "RUNNING", 2));
    // 入参 jobInstanceId=5001 → 对应第二条 entry B
    when(entryMapper.selectByRerunInstanceId("t1", 5001L)).thenReturn(null);
    when(entryMapper.selectBySessionAndSourceInstanceId(7L, "t1", 5001L)).thenReturn(entryB);
    when(entryMapper.countBySessionAndStatus(anyLong(), anyString())).thenReturn(0L);

    // 执行
    reconciler.reconcileOnTerminal("t1", 7L, "JOB_DUP", 5001L, JOB_SUCCESS);

    // 断言: 更新的是 entryB (id=102),不是 entryA (id=101)
    ArgumentCaptor<Long> updatedId = ArgumentCaptor.forClass(Long.class);
    verify(entryMapper)
        .updateStatus(
            updatedId.capture(), eq(ENTRY_SUCCEEDED), eq(5001L), any(), any(), any(), any(), any());
    assertThat(updatedId.getValue()).as("必须按 sourceInstanceId 命中第二条 entry").isEqualTo(102L);
    // 不应回退到线性扫
    verify(entryMapper, org.mockito.Mockito.never()).selectBySessionId(anyLong());
  }

  @Test
  @DisplayName("rerun 再入(已写过 rerun_instance_id)时按 rerun_instance_id 反查直接命中")
  void shouldReconcileByRerunInstanceId_whenAlreadyWrittenOnFirstPass() {
    // 准备
    BatchDayReplayEntryEntity entry =
        BatchDayReplayEntryEntity.builder()
            .id(201L)
            .sessionId(8L)
            .tenantId("t1")
            .jobCode("JOB_R")
            .sourceInstanceId(7000L)
            .rerunInstanceId(7100L)
            .status("RUNNING")
            .build();
    when(sessionMapper.selectById("t1", 8L)).thenReturn(session(8L, "RUNNING", 1));
    when(entryMapper.selectByRerunInstanceId("t1", 7100L)).thenReturn(entry);
    when(entryMapper.countBySessionAndStatus(anyLong(), anyString())).thenReturn(0L);

    // 执行
    reconciler.reconcileOnTerminal("t1", 8L, "JOB_R", 7100L, JOB_SUCCESS);

    // 断言
    verify(entryMapper)
        .updateStatus(eq(201L), eq(ENTRY_SUCCEEDED), eq(7100L), any(), any(), any(), any(), any());
    // 不需要 source 兜底也不需要线性扫
    verify(entryMapper, org.mockito.Mockito.never())
        .selectBySessionAndSourceInstanceId(anyLong(), anyString(), anyLong());
    verify(entryMapper, org.mockito.Mockito.never()).selectBySessionId(anyLong());
  }

  @Test
  @DisplayName("source/rerun 都对不上时降级到 jobCode 线性扫保持向后兼容")
  void shouldFallBackToJobCodeScan_whenNeitherRerunNorSourceMatches() {
    BatchDayReplayEntryEntity entry =
        BatchDayReplayEntryEntity.builder()
            .id(301L)
            .sessionId(9L)
            .tenantId("t1")
            .jobCode("JOB_LEGACY")
            .sourceInstanceId(null) // legacy: source 被运维清理
            .status("RUNNING")
            .build();
    when(sessionMapper.selectById("t1", 9L)).thenReturn(session(9L, "RUNNING", 1));
    when(entryMapper.selectByRerunInstanceId("t1", 8000L)).thenReturn(null);
    when(entryMapper.selectBySessionAndSourceInstanceId(9L, "t1", 8000L)).thenReturn(null);
    when(entryMapper.selectBySessionId(9L)).thenReturn(List.of(entry));
    when(entryMapper.countBySessionAndStatus(anyLong(), anyString())).thenReturn(0L);

    reconciler.reconcileOnTerminal("t1", 9L, "JOB_LEGACY", 8000L, JOB_SUCCESS);

    verify(entryMapper)
        .updateStatus(eq(301L), eq(ENTRY_SUCCEEDED), eq(8000L), any(), any(), any(), any(), any());
  }

  private static BatchDayReplaySessionEntity session(Long id, String status, int totalCount) {
    return BatchDayReplaySessionEntity.builder()
        .id(id)
        .tenantId("t1")
        .calendarCode("CAL")
        .bizDate(LocalDate.of(2026, 5, 4))
        .scope("ALL")
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
