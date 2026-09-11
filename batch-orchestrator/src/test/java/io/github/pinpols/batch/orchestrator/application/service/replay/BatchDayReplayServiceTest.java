package io.github.pinpols.batch.orchestrator.application.service.replay;

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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchTimezoneProperties;
import io.github.pinpols.batch.common.config.BatchTimezoneProvider;
import io.github.pinpols.batch.common.enums.BatchDayReplayScope;
import io.github.pinpols.batch.common.enums.ConfigLifecycleStatus;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.orchestrator.application.plan.SchedulePlan;
import io.github.pinpols.batch.orchestrator.application.plan.SchedulePlanBuilder;
import io.github.pinpols.batch.orchestrator.application.service.version.ResultVersionPromoteService;
import io.github.pinpols.batch.orchestrator.config.BatchDayDryRunProperties;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayReplayEntryEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayReplaySessionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobDefinitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.ResultVersionEntity;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayPlanCalendarMapper;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayReplayEntryMapper;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayReplaySessionMapper;
import io.github.pinpols.batch.orchestrator.mapper.DisasterDayOverrideMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.ResultVersionMapper;
import io.github.pinpols.batch.orchestrator.mapper.view.BatchDayReplayAssetPartitionImpactView;
import io.github.pinpols.batch.orchestrator.mapper.view.BatchDayReplayDispatchImpactView;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Month;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
    BatchDayDryRunProperties dryRunProperties = new BatchDayDryRunProperties();
    BatchTimezoneProvider timezoneProvider =
        new BatchTimezoneProvider(new BatchTimezoneProperties());
    BatchDateTimeSupport dateTimeSupport =
        new BatchDateTimeSupport(Clock.systemUTC(), timezoneProvider);
    service = new BatchDayReplayService(
        sessionMapper,
        entryMapper,
        jobInstanceMapper,
        resultVersionMapper,
        promoteService,
        dateTimeSupport,
        dryRunProperties,
        mock(JobDefinitionMapper.class),
        mock(SchedulePlanBuilder.class),
        mock(BatchDayPlanCalendarMapper.class),
        mock(DisasterDayOverrideMapper.class),
        timezoneProvider);
  }

  @Test
  void submitAllFailedScopeMaterializesEntriesFromCandidates() {
    when(jobInstanceMapper.selectBatchDayCandidates(
            eq("t1"), eq("CAL"), eq(LocalDate.of(2026, Month.MAY, 4)), anyList(), anyList()))
        .thenReturn(List.of(jobInstance(101L, "JOB_A"), jobInstance(102L, "JOB_B")));
    when(sessionMapper.insert(any(BatchDayReplaySessionEntity.class))).thenReturn(1);
    when(sessionMapper.selectActiveByCalendarBizDate("t1", "CAL", LocalDate.of(2026, Month.MAY, 4)))
        .thenReturn(sessionAt("t1", 7L, "RUNNING", "ALL_FAILED"));

    BatchDayReplaySessionEntity result = service.submit(BatchDayReplaySubmitCommand.builder()
        .tenantId("t1")
        .calendarCode("CAL")
        .bizDate(LocalDate.of(2026, Month.MAY, 4))
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
  void submitNormalizesLegacyConfigVersionPolicy() {
    when(jobInstanceMapper.selectBatchDayCandidates(
            eq("t1"), eq("CAL"), eq(LocalDate.of(2026, Month.MAY, 4)), anyList(), anyList()))
        .thenReturn(List.of(jobInstance(101L, "JOB_A")));
    when(sessionMapper.insert(any(BatchDayReplaySessionEntity.class))).thenReturn(1);
    when(sessionMapper.selectActiveByCalendarBizDate("t1", "CAL", LocalDate.of(2026, Month.MAY, 4)))
        .thenReturn(sessionAt("t1", 7L, "RUNNING", "ALL_FAILED"));

    service.submit(BatchDayReplaySubmitCommand.builder()
        .tenantId("t1")
        .calendarCode("CAL")
        .bizDate(LocalDate.of(2026, Month.MAY, 4))
        .scope("ALL_FAILED")
        .configVersionPolicy("USE_CURRENT_CONFIG")
        .reason("compatibility check")
        .requestedBy("ops")
        .autoApprove(true)
        .build());

    ArgumentCaptor<BatchDayReplaySessionEntity> captor =
        ArgumentCaptor.forClass(BatchDayReplaySessionEntity.class);
    verify(sessionMapper).insert(captor.capture());
    assertThat(captor.getValue().configVersionPolicy()).isEqualTo("USE_LATEST_CONFIG");
  }

  @Test
  void previewNormalizesLegacySpecificVersionPolicy() {
    when(jobInstanceMapper.selectBatchDayCandidates(
            eq("t1"), eq("CAL"), eq(LocalDate.of(2026, Month.MAY, 4)), anyList(), anyList()))
        .thenReturn(List.of(jobInstance(101L, "JOB_A")));

    BatchDayReplayPreviewResponse result = service.preview(BatchDayReplaySubmitCommand.builder()
        .tenantId("t1")
        .calendarCode("CAL")
        .bizDate(LocalDate.of(2026, Month.MAY, 4))
        .scope("ALL_FAILED")
        .configVersionPolicy("USE_SPECIFIC_VERSION")
        .configVersion(3)
        .reason("compatibility check")
        .requestedBy("ops")
        .build());

    assertThat(result.configVersionPolicy()).isEqualTo("USE_SPECIFIED_VERSION");
  }

  @Test
  void previewAllFailedScopeReturnsImpactWithoutWritingSession() {
    when(jobInstanceMapper.selectBatchDayCandidates(
            eq("t1"), eq("CAL"), eq(LocalDate.of(2026, Month.MAY, 4)), anyList(), anyList()))
        .thenReturn(List.of(jobInstance(101L, "JOB_A"), jobInstance(102L, "JOB_B")));

    BatchDayReplayPreviewResponse result = service.preview(BatchDayReplaySubmitCommand.builder()
        .tenantId("t1")
        .calendarCode("CAL")
        .bizDate(LocalDate.of(2026, Month.MAY, 4))
        .scope("ALL_FAILED")
        .resultPolicy("CREATE_NEW_VERSION")
        .configVersionPolicy("USE_ORIGINAL_CONFIG")
        .reason("upstream backfill")
        .requestedBy("ops")
        .build());

    assertThat(result.totalCount()).isEqualTo(2);
    assertThat(result.entries())
        .extracting(BatchDayReplayPreviewResponse.PreviewEntry::action)
        .containsOnly("RERUN_INSTANCE");
    assertThat(result.resultVersionImpacts())
        .extracting(BatchDayReplayPreviewResponse.ResultVersionImpact::action)
        .containsOnly("CREATE_NEW_RESULT_VERSION");
    verifyNoInteractions(sessionMapper);
    verify(entryMapper, never()).insertBatch(anyList());
  }

  @Test
  void previewShouldIncludeAssetPartitionAndDispatchImpacts() {
    when(jobInstanceMapper.selectBatchDayCandidates(
            eq("t1"), eq("CAL"), eq(LocalDate.of(2026, Month.MAY, 4)), anyList(), anyList()))
        .thenReturn(List.of(jobInstance(101L, "JOB_A")));
    when(entryMapper.selectAssetPartitionImpacts(eq("t1"), any()))
        .thenReturn(List.of(new BatchDayReplayAssetPartitionImpactView(
            "job:JOB_A:2026-05-04", "JOB_A", "2026-05-04", 11L, "EFFECTIVE")));
    when(entryMapper.selectDispatchImpacts(eq("t1"), any()))
        .thenReturn(List.of(new BatchDayReplayDispatchImpactView(101L, 3L, 2L, 1L, 1L)));

    BatchDayReplayPreviewResponse result = service.preview(BatchDayReplaySubmitCommand.builder()
        .tenantId("t1")
        .calendarCode("CAL")
        .bizDate(LocalDate.of(2026, Month.MAY, 4))
        .scope("ALL_FAILED")
        .reason("upstream backfill")
        .requestedBy("ops")
        .build());

    assertThat(result.assetPartitionImpacts())
        .singleElement()
        .extracting(BatchDayReplayPreviewResponse.AssetPartitionImpact::assetCode)
        .isEqualTo("JOB_A");
    assertThat(result.dispatchImpacts()).singleElement().satisfies(impact -> {
      assertThat(impact.sourceInstanceId()).isEqualTo(101L);
      assertThat(impact.recordCount()).isEqualTo(3L);
      assertThat(impact.failedCount()).isEqualTo(1L);
    });
  }

  @Test
  void previewWithoutCandidatesReturnsWarningInsteadOfCreatingEmptySession() {
    when(jobInstanceMapper.selectBatchDayCandidates(
            eq("t1"), eq("CAL"), eq(LocalDate.of(2026, Month.MAY, 4)), anyList(), anyList()))
        .thenReturn(List.of());

    BatchDayReplayPreviewResponse result = service.preview(BatchDayReplaySubmitCommand.builder()
        .tenantId("t1")
        .calendarCode("CAL")
        .bizDate(LocalDate.of(2026, Month.MAY, 4))
        .scope("ALL_FAILED")
        .reason("precheck")
        .requestedBy("ops")
        .build());

    assertThat(result.totalCount()).isZero();
    assertThat(result.warnings()).containsExactly("NO_CANDIDATES");
    verifyNoInteractions(sessionMapper);
    verify(entryMapper, never()).insertBatch(anyList());
  }

  @Test
  void submitWithoutCandidatesThrows() {
    when(jobInstanceMapper.selectBatchDayCandidates(
            eq("t1"), eq("CAL"), eq(LocalDate.of(2026, Month.MAY, 4)), anyList(), anyList()))
        .thenReturn(List.of());

    assertThatThrownBy(() -> service.submit(BatchDayReplaySubmitCommand.builder()
            .tenantId("t1")
            .calendarCode("CAL")
            .bizDate(LocalDate.of(2026, Month.MAY, 4))
            .scope("ALL_FAILED")
            .reason("...")
            .requestedBy("ops")
            .autoApprove(true)
            .build()))
        .isInstanceOf(BizException.class);
  }

  @Test
  void dryRunSubmitIsDisabledByDefault() {
    when(jobInstanceMapper.selectBatchDayCandidates(
            anyString(), anyString(), any(), anyList(), anyList()))
        .thenReturn(List.of(jobInstance(101L, "JOB_A")));

    assertThatThrownBy(() -> service.submit(baseDryRunCommand().build()))
        .isInstanceOf(BizException.class);
    verify(sessionMapper, never()).insert(any());
  }

  @Test
  void dryRunSubmitForcesInternalResultPolicy() {
    BatchDayReplayService dryRunService = dryRunService(true);
    when(jobInstanceMapper.selectBatchDayCandidates(
            anyString(), anyString(), any(), anyList(), anyList()))
        .thenReturn(List.of(jobInstance(101L, "JOB_A")));
    when(sessionMapper.insert(any())).thenReturn(1);
    when(sessionMapper.selectActiveByCalendarBizDate("t1", "CAL", LocalDate.of(2026, Month.MAY, 4)))
        .thenReturn(sessionAt("t1", 9L, "RUNNING", "ALL"));

    dryRunService.submit(baseDryRunCommand().resultPolicy("CREATE_NEW_VERSION").build());

    ArgumentCaptor<BatchDayReplaySessionEntity> captor =
        ArgumentCaptor.forClass(BatchDayReplaySessionEntity.class);
    verify(sessionMapper).insert(captor.capture());
    assertThat(captor.getValue().executionMode()).isEqualTo("DRY_RUN");
    assertThat(captor.getValue().resultPolicy()).isEqualTo("DRY_RUN_ONLY");
  }

  @Test
  void schedulePlanPreviewMaterializesImmutableSnapshot() {
    JobDefinitionMapper definitionMapper = mock(JobDefinitionMapper.class);
    SchedulePlanBuilder planBuilder = mock(SchedulePlanBuilder.class);
    BatchDayPlanCalendarMapper calendarMapper = mock(BatchDayPlanCalendarMapper.class);
    DisasterDayOverrideMapper overrideMapper = mock(DisasterDayOverrideMapper.class);
    BatchDayReplayService dryRunService =
        dryRunService(true, definitionMapper, planBuilder, calendarMapper, overrideMapper);
    JobDefinitionEntity definition = JobDefinitionEntity.builder()
        .id(7L)
        .tenantId("t1")
        .jobCode("JOB_PLAN")
        .calendarCode("CAL")
        .scheduleType("CRON")
        .scheduleExpr("0 0 1 * * *")
        .timezone("Asia/Shanghai")
        .defaultParams(Map.of("source", "snapshot"))
        .version(3)
        .enabled(true)
        .build();
    SchedulePlan plan = new SchedulePlan();
    plan.setQueueCode("Q1");
    plan.setWorkerGroup("IMPORT");
    plan.setDefaultWorkerType("IMPORT");
    plan.setPartitionCount(2);
    when(definitionMapper.selectByTenantAndEnabled("t1", true)).thenReturn(List.of(definition));
    when(calendarMapper.selectEffectiveDayType("t1", "CAL", LocalDate.of(2026, Month.MAY, 4)))
        .thenReturn("WORKDAY");
    when(planBuilder.build(any())).thenReturn(plan);

    BatchDayReplayPreviewResponse preview = dryRunService.preview(
        baseDryRunCommand().candidateSource("SCHEDULE_PLAN").build());

    assertThat(preview.entries()).singleElement().satisfies(entry -> {
      assertThat(entry.jobCode()).isEqualTo("JOB_PLAN");
      assertThat(entry.sourceInstanceId()).isNull();
      assertThat(entry.action()).isEqualTo("LAUNCH_SCHEDULE_PLAN");
    });
  }

  @Test
  void submitDuplicateActiveSessionThrows() {
    when(jobInstanceMapper.selectBatchDayCandidates(
            anyString(), anyString(), any(), anyList(), anyList()))
        .thenReturn(List.of(jobInstance(101L, "JOB_A")));
    when(sessionMapper.insert(any(BatchDayReplaySessionEntity.class)))
        .thenThrow(new DuplicateKeyException("uk_replay_session_active"));

    assertThatThrownBy(() -> service.submit(BatchDayReplaySubmitCommand.builder()
            .tenantId("t1")
            .calendarCode("CAL")
            .bizDate(LocalDate.of(2026, Month.MAY, 4))
            .scope("ALL_FAILED")
            .reason("...")
            .requestedBy("ops")
            .autoApprove(true)
            .build()))
        .isInstanceOf(BizException.class);
  }

  @Test
  void submitSubsetWithoutJobCodesIsRejected() {
    assertThatThrownBy(() -> service.submit(BatchDayReplaySubmitCommand.builder()
            .tenantId("t1")
            .calendarCode("CAL")
            .bizDate(LocalDate.of(2026, Month.MAY, 4))
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
        .thenReturn(List.of(
            resultVersion(11L, "job:JOB_A:2026-05-04", 100L),
            resultVersion(12L, "job:JOB_B:2026-05-04", 101L)));
    when(sessionMapper.insert(any(BatchDayReplaySessionEntity.class))).thenReturn(1);
    when(sessionMapper.selectActiveByCalendarBizDate("t1", "CAL", LocalDate.of(2026, Month.MAY, 4)))
        .thenReturn(sessionAt("t1", 5L, "RUNNING", BatchDayReplayScope.OUTPUTS_ONLY.code()));

    BatchDayReplaySessionEntity result = service.submit(BatchDayReplaySubmitCommand.builder()
        .tenantId("t1")
        .calendarCode("CAL")
        .bizDate(LocalDate.of(2026, Month.MAY, 4))
        .scope(BatchDayReplayScope.OUTPUTS_ONLY.code())
        .versionIds(List.of(11L, 12L))
        .reason("regulatory restate")
        .requestedBy("ops")
        .autoApprove(true)
        .build());

    assertThat(result.scope()).isEqualTo(BatchDayReplayScope.OUTPUTS_ONLY.code());
    verify(entryMapper).insertBatch(anyList());
    verify(jobInstanceMapper, never())
        .selectBatchDayCandidates(anyString(), anyString(), any(), anyList(), anyList());
  }

  @Test
  void previewOutputsOnlyShowsPromotedResultVersions() {
    when(resultVersionMapper.selectByIds(eq("t1"), any()))
        .thenReturn(List.of(resultVersion(11L, "job:JOB_A:2026-05-04", 100L)));

    BatchDayReplayPreviewResponse result = service.preview(BatchDayReplaySubmitCommand.builder()
        .tenantId("t1")
        .calendarCode("CAL")
        .bizDate(LocalDate.of(2026, Month.MAY, 4))
        .scope(BatchDayReplayScope.OUTPUTS_ONLY.code())
        .versionIds(List.of(11L))
        .reason("regulatory restate")
        .requestedBy("ops")
        .build());

    assertThat(result.entries()).singleElement().satisfies(entry -> {
      assertThat(entry.action()).isEqualTo("PROMOTE_RESULT_VERSION");
      assertThat(entry.resultVersionId()).isEqualTo(11L);
    });
    assertThat(result.resultVersionImpacts())
        .singleElement()
        .extracting(BatchDayReplayPreviewResponse.ResultVersionImpact::action)
        .isEqualTo("PROMOTE_EXISTING_VERSION");
    verifyNoInteractions(sessionMapper);
    verify(entryMapper, never()).insertBatch(anyList());
  }

  @Test
  void approveAdvancesPendingToRunning() {
    when(sessionMapper.selectById("t1", 1L))
        .thenReturn(
            sessionAt("t1", 1L, ConfigLifecycleStatus.PENDING_APPROVAL.code(), "ALL_FAILED"))
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
        .thenReturn(sessionAt("t1", 5L, "RUNNING", BatchDayReplayScope.OUTPUTS_ONLY.code()))
        .thenReturn(sessionAt("t1", 5L, "SUCCEEDED", BatchDayReplayScope.OUTPUTS_ONLY.code()));
    BatchDayReplayEntryEntity e1 = BatchDayReplayEntryEntity.builder()
        .id(1L)
        .sessionId(5L)
        .tenantId("t1")
        .jobCode("JOB_A")
        .resultVersionId(11L)
        .status("PENDING")
        .build();
    BatchDayReplayEntryEntity e2 = BatchDayReplayEntryEntity.builder()
        .id(2L)
        .sessionId(5L)
        .tenantId("t1")
        .jobCode("JOB_B")
        .resultVersionId(12L)
        .status("PENDING")
        .build();
    when(entryMapper.selectBySessionAndStatus(eq(5L), eq("t1"), eq("PENDING"), anyInt()))
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
        .thenReturn(sessionAt(
            "t1",
            5L,
            ConfigLifecycleStatus.PENDING_APPROVAL.code(),
            BatchDayReplayScope.OUTPUTS_ONLY.code()));

    assertThatThrownBy(() -> service.executeOutputsOnly("t1", 5L)).isInstanceOf(BizException.class);
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  private BatchDayReplayService dryRunService(boolean enabled) {
    return dryRunService(
        enabled,
        mock(JobDefinitionMapper.class),
        mock(SchedulePlanBuilder.class),
        mock(BatchDayPlanCalendarMapper.class),
        mock(DisasterDayOverrideMapper.class));
  }

  private BatchDayReplayService dryRunService(
      boolean enabled,
      JobDefinitionMapper definitionMapper,
      SchedulePlanBuilder planBuilder,
      BatchDayPlanCalendarMapper calendarMapper,
      DisasterDayOverrideMapper overrideMapper) {
    BatchDayDryRunProperties properties = new BatchDayDryRunProperties();
    properties.setEnabled(enabled);
    BatchTimezoneProvider timezoneProvider =
        new BatchTimezoneProvider(new BatchTimezoneProperties());
    return new BatchDayReplayService(
        sessionMapper,
        entryMapper,
        jobInstanceMapper,
        resultVersionMapper,
        promoteService,
        new BatchDateTimeSupport(Clock.systemUTC(), timezoneProvider),
        properties,
        definitionMapper,
        planBuilder,
        calendarMapper,
        overrideMapper,
        timezoneProvider);
  }

  private static BatchDayReplaySubmitCommand.BatchDayReplaySubmitCommandBuilder
      baseDryRunCommand() {
    return BatchDayReplaySubmitCommand.builder()
        .tenantId("t1")
        .calendarCode("CAL")
        .bizDate(LocalDate.of(2026, Month.MAY, 4))
        .scope("ALL")
        .executionMode("DRY_RUN")
        .candidateSource("EXISTING_INSTANCES")
        .reason("release rehearsal")
        .requestedBy("ops")
        .autoApprove(true);
  }

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
        .bizDate(LocalDate.of(2026, Month.MAY, 4))
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
