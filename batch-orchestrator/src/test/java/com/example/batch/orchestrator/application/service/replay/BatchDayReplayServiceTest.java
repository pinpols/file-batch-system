package com.example.batch.orchestrator.application.service.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.config.BatchTimezoneProperties;
import com.example.batch.common.config.BatchTimezoneProvider;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.orchestrator.application.service.version.ResultVersionPromoteService;
import com.example.batch.orchestrator.domain.entity.BatchDayReplayEntryEntity;
import com.example.batch.orchestrator.domain.entity.BatchDayReplaySessionEntity;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.ResultVersionEntity;
import com.example.batch.orchestrator.mapper.BatchDayReplayEntryMapper;
import com.example.batch.orchestrator.mapper.BatchDayReplaySessionMapper;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.mapper.ResultVersionMapper;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

class BatchDayReplayServiceTest {

  private BatchDayReplaySessionMapper sessionMapper;
  private BatchDayReplayEntryMapper entryMapper;
  private JobInstanceMapper jobInstanceMapper;
  private ResultVersionMapper resultVersionMapper;
  private ResultVersionPromoteService promoteService;
  private BatchDayReplayService service;

  @BeforeEach
  void setUp() {
    sessionMapper = mock(BatchDayReplaySessionMapper.class);
    entryMapper = mock(BatchDayReplayEntryMapper.class);
    jobInstanceMapper = mock(JobInstanceMapper.class);
    resultVersionMapper = mock(ResultVersionMapper.class);
    promoteService = mock(ResultVersionPromoteService.class);
    BatchDateTimeSupport dateTimeSupport =
        new BatchDateTimeSupport(
            Clock.systemUTC(), new BatchTimezoneProvider(new BatchTimezoneProperties()));
    service =
        new BatchDayReplayService(
            sessionMapper,
            entryMapper,
            jobInstanceMapper,
            resultVersionMapper,
            promoteService,
            dateTimeSupport);
  }

  @Test
  void submitAllFailedScopeMaterializesEntriesFromCandidates() {
    when(jobInstanceMapper.selectBatchDayCandidates(
            eq("t1"), eq("CAL"), eq(LocalDate.of(2026, 5, 4)), anyList(), anyList()))
        .thenReturn(List.of(jobInstance(101L, "JOB_A"), jobInstance(102L, "JOB_B")));
    when(sessionMapper.insert(any(BatchDayReplaySessionEntity.class))).thenReturn(1);
    when(sessionMapper.selectActiveByCalendarBizDate("t1", "CAL", LocalDate.of(2026, 5, 4)))
        .thenReturn(sessionAt("t1", 7L, "RUNNING", "ALL_FAILED"));

    BatchDayReplaySessionEntity result =
        service.submit(
            BatchDayReplaySubmitCommand.builder()
                .tenantId("t1")
                .calendarCode("CAL")
                .bizDate(LocalDate.of(2026, 5, 4))
                .scope("ALL_FAILED")
                .resultPolicy("CREATE_NEW_VERSION")
                .configVersionPolicy("USE_ORIGINAL_CONFIG")
                .reason("upstream backfill")
                .requestedBy("ops")
                .autoApprove(true)
                .build());

    assertThat(result.status()).isEqualTo("RUNNING");
    verify(entryMapper).insertBatch(anyList());
  }

  @Test
  void submitWithoutCandidatesThrows() {
    when(jobInstanceMapper.selectBatchDayCandidates(
            eq("t1"), eq("CAL"), eq(LocalDate.of(2026, 5, 4)), anyList(), anyList()))
        .thenReturn(List.of());

    assertThatThrownBy(
            () ->
                service.submit(
                    BatchDayReplaySubmitCommand.builder()
                        .tenantId("t1")
                        .calendarCode("CAL")
                        .bizDate(LocalDate.of(2026, 5, 4))
                        .scope("ALL_FAILED")
                        .reason("...")
                        .requestedBy("ops")
                        .autoApprove(true)
                        .build()))
        .isInstanceOf(BizException.class);
  }

  @Test
  void submitDuplicateActiveSessionThrows() {
    when(jobInstanceMapper.selectBatchDayCandidates(
            anyString(), anyString(), any(), anyList(), anyList()))
        .thenReturn(List.of(jobInstance(101L, "JOB_A")));
    when(sessionMapper.insert(any(BatchDayReplaySessionEntity.class)))
        .thenThrow(new DuplicateKeyException("uk_replay_session_active"));

    assertThatThrownBy(
            () ->
                service.submit(
                    BatchDayReplaySubmitCommand.builder()
                        .tenantId("t1")
                        .calendarCode("CAL")
                        .bizDate(LocalDate.of(2026, 5, 4))
                        .scope("ALL_FAILED")
                        .reason("...")
                        .requestedBy("ops")
                        .autoApprove(true)
                        .build()))
        .isInstanceOf(BizException.class);
  }

  @Test
  void submitSubsetWithoutJobCodesIsRejected() {
    assertThatThrownBy(
            () ->
                service.submit(
                    BatchDayReplaySubmitCommand.builder()
                        .tenantId("t1")
                        .calendarCode("CAL")
                        .bizDate(LocalDate.of(2026, 5, 4))
                        .scope("SUBSET_JOB_CODES")
                        .reason("...")
                        .requestedBy("ops")
                        .autoApprove(true)
                        .build()))
        .isInstanceOf(BizException.class);
  }

  @Test
  void submitOutputsOnlyMaterializesFromVersionIds() {
    // R7-A3-P1: materializeOutputsOnlyEntries 改用 selectByIds 批量预取替代 N+1。
    when(resultVersionMapper.selectByIds(eq("t1"), any()))
        .thenReturn(
            List.of(
                resultVersion(11L, "job:JOB_A:2026-05-04", 100L),
                resultVersion(12L, "job:JOB_B:2026-05-04", 101L)));
    when(sessionMapper.insert(any(BatchDayReplaySessionEntity.class))).thenReturn(1);
    when(sessionMapper.selectActiveByCalendarBizDate("t1", "CAL", LocalDate.of(2026, 5, 4)))
        .thenReturn(sessionAt("t1", 5L, "RUNNING", "OUTPUTS_ONLY"));

    BatchDayReplaySessionEntity result =
        service.submit(
            BatchDayReplaySubmitCommand.builder()
                .tenantId("t1")
                .calendarCode("CAL")
                .bizDate(LocalDate.of(2026, 5, 4))
                .scope("OUTPUTS_ONLY")
                .versionIds(List.of(11L, 12L))
                .reason("regulatory restate")
                .requestedBy("ops")
                .autoApprove(true)
                .build());

    assertThat(result.scope()).isEqualTo("OUTPUTS_ONLY");
    verify(entryMapper).insertBatch(anyList());
    verify(jobInstanceMapper, never())
        .selectBatchDayCandidates(anyString(), anyString(), any(), anyList(), anyList());
  }

  @Test
  void approveAdvancesPendingToRunning() {
    when(sessionMapper.selectById("t1", 1L))
        .thenReturn(sessionAt("t1", 1L, "PENDING_APPROVAL", "ALL_FAILED"))
        .thenReturn(sessionAt("t1", 1L, "RUNNING", "ALL_FAILED"));
    when(sessionMapper.updateStatus(
            eq("t1"), eq(1L), eq("RUNNING"), anyList(), any(), any(), eq("approver"), any()))
        .thenReturn(1);

    var result = service.approve("t1", 1L, "approver");

    assertThat(result.status()).isEqualTo("RUNNING");
  }

  @Test
  void approveWhenAlreadyRunningThrows() {
    when(sessionMapper.selectById("t1", 1L))
        .thenReturn(sessionAt("t1", 1L, "RUNNING", "ALL_FAILED"));

    assertThatThrownBy(() -> service.approve("t1", 1L, "approver"))
        .isInstanceOf(BizException.class);
  }

  @Test
  void cancelMovesActiveToCancelled() {
    when(sessionMapper.selectById("t1", 1L))
        .thenReturn(sessionAt("t1", 1L, "RUNNING", "ALL_FAILED"))
        .thenReturn(sessionAt("t1", 1L, "CANCELLED", "ALL_FAILED"));
    when(sessionMapper.updateStatus(
            eq("t1"), eq(1L), eq("CANCELLED"), anyList(), any(), any(), any(), any()))
        .thenReturn(1);

    var result = service.cancel("t1", 1L);

    assertThat(result.status()).isEqualTo("CANCELLED");
  }

  @Test
  void cancelOnTerminalSessionThrows() {
    when(sessionMapper.selectById("t1", 1L))
        .thenReturn(sessionAt("t1", 1L, "SUCCEEDED", "ALL_FAILED"));

    assertThatThrownBy(() -> service.cancel("t1", 1L)).isInstanceOf(BizException.class);
  }

  @Test
  void executeOutputsOnlyPromotesEachEntryAndCompletesSession() {
    when(sessionMapper.selectById("t1", 5L))
        .thenReturn(sessionAt("t1", 5L, "RUNNING", "OUTPUTS_ONLY"))
        .thenReturn(sessionAt("t1", 5L, "SUCCEEDED", "OUTPUTS_ONLY"));
    BatchDayReplayEntryEntity e1 =
        BatchDayReplayEntryEntity.builder()
            .id(1L)
            .sessionId(5L)
            .tenantId("t1")
            .jobCode("JOB_A")
            .resultVersionId(11L)
            .status("PENDING")
            .build();
    BatchDayReplayEntryEntity e2 =
        BatchDayReplayEntryEntity.builder()
            .id(2L)
            .sessionId(5L)
            .tenantId("t1")
            .jobCode("JOB_B")
            .resultVersionId(12L)
            .status("PENDING")
            .build();
    when(entryMapper.selectBySessionAndStatus(eq(5L), eq("PENDING"), anyInt()))
        .thenReturn(List.of(e1, e2));

    var result = service.executeOutputsOnly("t1", 5L);

    verify(promoteService, times(2)).promote(eq("t1"), any(Long.class));
    assertThat(result.status()).isEqualTo("SUCCEEDED");
  }

  @Test
  void executeOutputsOnlyOnNonOutputsScopeThrows() {
    when(sessionMapper.selectById("t1", 5L))
        .thenReturn(sessionAt("t1", 5L, "RUNNING", "ALL_FAILED"));

    assertThatThrownBy(() -> service.executeOutputsOnly("t1", 5L)).isInstanceOf(BizException.class);
  }

  @Test
  void executeOutputsOnlyOnNonRunningSessionThrows() {
    when(sessionMapper.selectById("t1", 5L))
        .thenReturn(sessionAt("t1", 5L, "PENDING_APPROVAL", "OUTPUTS_ONLY"));

    assertThatThrownBy(() -> service.executeOutputsOnly("t1", 5L)).isInstanceOf(BizException.class);
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  private static JobInstanceEntity jobInstance(Long id, String jobCode) {
    JobInstanceEntity entity = new JobInstanceEntity();
    entity.setId(id);
    entity.setTenantId("t1");
    entity.setJobCode(jobCode);
    return entity;
  }

  private static ResultVersionEntity resultVersion(
      Long id, String businessKey, Long jobInstanceId) {
    return ResultVersionEntity.builder()
        .id(id)
        .tenantId("t1")
        .businessKey(businessKey)
        .versionNo(1)
        .jobInstanceId(jobInstanceId)
        .status("EFFECTIVE")
        .build();
  }

  private static BatchDayReplaySessionEntity sessionAt(
      String tenantId, Long id, String status, String scope) {
    return BatchDayReplaySessionEntity.builder()
        .id(id)
        .tenantId(tenantId)
        .calendarCode("CAL")
        .bizDate(LocalDate.of(2026, 5, 4))
        .scope(scope)
        .resultPolicy("CREATE_NEW_VERSION")
        .configVersionPolicy("USE_ORIGINAL_CONFIG")
        .reason("...")
        .status(status)
        .totalCount(2)
        .succeededCount(0)
        .failedCount(0)
        .inFlightCount(0)
        .requestedBy("ops")
        .build();
  }
}
