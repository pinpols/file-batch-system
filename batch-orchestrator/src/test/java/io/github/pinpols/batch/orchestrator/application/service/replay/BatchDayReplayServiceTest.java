package io.github.pinpols.batch.orchestrator.application.service.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayReplayPreviewTokenEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayReplaySessionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobDefinitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.ResultVersionEntity;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayPlanCalendarMapper;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayReplayEntryMapper;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayReplayPreviewTokenMapper;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayReplaySessionMapper;
import io.github.pinpols.batch.orchestrator.mapper.DisasterDayOverrideMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.ResultVersionMapper;
import io.github.pinpols.batch.orchestrator.mapper.view.BatchDayReplayAssetPartitionImpactView;
import io.github.pinpols.batch.orchestrator.mapper.view.BatchDayReplayDispatchImpactView;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;

@DisplayName("批量日重放服务: 预览与提交的候选筛选, 影响面统计, 会话流转与试运行口径")
class BatchDayReplayServiceTest {

  private BatchDayReplaySessionMapper sessionMapper;
  private BatchDayReplayEntryMapper entryMapper;
  private BatchDayReplayPreviewTokenMapper previewTokenMapper;
  private JobInstanceMapper jobInstanceMapper;
  private ResultVersionMapper resultVersionMapper;
  private ResultVersionPromoteService promoteService;
  private BatchDayReplayService service;
  private final AtomicReference<BatchDayReplayPreviewTokenEntity> storedPreviewToken =
      new AtomicReference<>();

  @BeforeEach
  void setUp() {
    sessionMapper = mock(BatchDayReplaySessionMapper.class);
    entryMapper = mock(BatchDayReplayEntryMapper.class);
    previewTokenMapper = mock(BatchDayReplayPreviewTokenMapper.class);
    storedPreviewToken.set(null);
    when(previewTokenMapper.insert(any())).thenAnswer(invocation -> {
      storedPreviewToken.set(invocation.getArgument(0));
      return 1;
    });
    when(previewTokenMapper.selectForUpdate(anyString(), anyString()))
        .thenAnswer(invocation -> storedPreviewToken.get());
    when(previewTokenMapper.consume(anyString(), anyString())).thenAnswer(invocation -> {
      BatchDayReplayPreviewTokenEntity token = storedPreviewToken.get();
      storedPreviewToken.set(new BatchDayReplayPreviewTokenEntity(
          token.tenantId(),
          token.tokenHash(),
          token.requestHash(),
          token.snapshotHash(),
          token.expiresAt(),
          Instant.now(),
          token.sessionId()));
      return 1;
    });
    when(previewTokenMapper.linkSession(anyString(), anyString(), anyLong()))
        .thenAnswer(invocation -> {
          BatchDayReplayPreviewTokenEntity token = storedPreviewToken.get();
          storedPreviewToken.set(new BatchDayReplayPreviewTokenEntity(
              token.tenantId(),
              token.tokenHash(),
              token.requestHash(),
              token.snapshotHash(),
              token.expiresAt(),
              token.consumedAt(),
              invocation.getArgument(2)));
          return 1;
        });
    when(previewTokenMapper.deleteExpired(any(), any(), anyInt())).thenReturn(0);
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
        previewTokenMapper,
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
  @DisplayName("全失败范围提交时按候选实例生成重放条目并创建会话")
  void shouldMaterializeEntries_whenSubmittingAllFailedScope() {
    when(jobInstanceMapper.selectBatchDayCandidates(
            eq("t1"), eq("CAL"), eq(LocalDate.of(2026, Month.MAY, 4)), anyList(), anyList()))
        .thenReturn(List.of(jobInstance(101L, "JOB_A"), jobInstance(102L, "JOB_B")));
    when(sessionMapper.insert(any(BatchDayReplaySessionEntity.class))).thenReturn(1);
    when(sessionMapper.selectActiveByCalendarBizDate("t1", "CAL", LocalDate.of(2026, Month.MAY, 4)))
        .thenReturn(sessionAt("t1", 7L, "RUNNING", "ALL_FAILED"));

    BatchDayReplaySubmitCommand command = BatchDayReplaySubmitCommand.builder()
        .tenantId("t1")
        .calendarCode("CAL")
        .bizDate(LocalDate.of(2026, Month.MAY, 4))
        .scope("ALL_FAILED")
        .resultPolicy("CREATE_NEW_VERSION")
        .configVersionPolicy("USE_ORIGINAL_CONFIG")
        .reason("upstream backfill")
        .requestedBy("ops")
        .autoApprove(true)
        .build();
    BatchDayReplaySessionEntity result = service.submit(withPreviewToken(service, command));

    assertThat(result.status()).isEqualTo("RUNNING");
    verify(entryMapper).insertBatch(anyList());
  }

  @Test
  @DisplayName("提交时历史配置版本策略被归一化为使用最新配置")
  void shouldNormalizePolicy_whenLegacyConfigVersionPolicyGiven() {
    when(jobInstanceMapper.selectBatchDayCandidates(
            eq("t1"), eq("CAL"), eq(LocalDate.of(2026, Month.MAY, 4)), anyList(), anyList()))
        .thenReturn(List.of(jobInstance(101L, "JOB_A")));
    when(sessionMapper.insert(any(BatchDayReplaySessionEntity.class))).thenReturn(1);
    when(sessionMapper.selectActiveByCalendarBizDate("t1", "CAL", LocalDate.of(2026, Month.MAY, 4)))
        .thenReturn(sessionAt("t1", 7L, "RUNNING", "ALL_FAILED"));

    BatchDayReplaySubmitCommand command = BatchDayReplaySubmitCommand.builder()
        .tenantId("t1")
        .calendarCode("CAL")
        .bizDate(LocalDate.of(2026, Month.MAY, 4))
        .scope("ALL_FAILED")
        .configVersionPolicy("USE_CURRENT_CONFIG")
        .reason("compatibility check")
        .requestedBy("ops")
        .autoApprove(true)
        .build();
    service.submit(withPreviewToken(service, command));

    ArgumentCaptor<BatchDayReplaySessionEntity> captor =
        ArgumentCaptor.forClass(BatchDayReplaySessionEntity.class);
    verify(sessionMapper).insert(captor.capture());
    assertThat(captor.getValue().configVersionPolicy()).isEqualTo("USE_LATEST_CONFIG");
  }

  @Test
  @DisplayName("预览时历史指定版本策略被归一化为使用指定版本")
  void shouldNormalizePolicy_whenPreviewUsesLegacySpecificVersionPolicy() {
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
  @DisplayName("预览返回影响面统计, 不落库会话与条目")
  void shouldReturnImpactWithoutWriting_whenPreviewing() {
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
  @DisplayName("提交必须携带未过期的一次性预览凭证且同一凭证不能重复使用")
  void shouldRequireAndConsumeSingleUsePreviewToken() {
    when(jobInstanceMapper.selectBatchDayCandidates(
            anyString(), anyString(), any(), anyList(), anyList()))
        .thenReturn(List.of(jobInstance(101L, "JOB_A")));
    when(sessionMapper.insert(any(BatchDayReplaySessionEntity.class))).thenReturn(1);
    when(sessionMapper.selectActiveByCalendarBizDate("t1", "CAL", LocalDate.of(2026, Month.MAY, 4)))
        .thenReturn(sessionAt("t1", 7L, "RUNNING", "ALL_FAILED"));
    BatchDayReplaySubmitCommand command = BatchDayReplaySubmitCommand.builder()
        .tenantId("t1")
        .calendarCode("CAL")
        .bizDate(LocalDate.of(2026, Month.MAY, 4))
        .scope("ALL_FAILED")
        .reason("upstream backfill")
        .requestedBy("ops")
        .autoApprove(true)
        .build();

    BatchDayReplaySubmitCommand authorized = withPreviewToken(service, command);
    when(sessionMapper.selectById(eq("t1"), anyLong()))
        .thenReturn(sessionAt("t1", 7L, "RUNNING", "ALL_FAILED"));

    BatchDayReplaySessionEntity first = service.submit(authorized);
    BatchDayReplaySessionEntity retry = service.submit(authorized);

    assertThat(retry.id()).isEqualTo(first.id());
    verify(previewTokenMapper, times(1)).consume(eq("t1"), anyString());
    verify(previewTokenMapper, times(1)).linkSession(eq("t1"), anyString(), anyLong());
    verify(sessionMapper, times(1)).insert(any(BatchDayReplaySessionEntity.class));
  }

  @Test
  @DisplayName("缺少预览凭证时不能创建重放会话")
  void shouldRejectSubmit_whenPreviewTokenIsMissing() {
    when(jobInstanceMapper.selectBatchDayCandidates(
            anyString(), anyString(), any(), anyList(), anyList()))
        .thenReturn(List.of(jobInstance(101L, "JOB_A")));
    BatchDayReplaySubmitCommand command = BatchDayReplaySubmitCommand.builder()
        .tenantId("t1")
        .calendarCode("CAL")
        .bizDate(LocalDate.of(2026, Month.MAY, 4))
        .scope("ALL_FAILED")
        .reason("upstream backfill")
        .requestedBy("ops")
        .build();

    assertThatThrownBy(() -> service.submit(command)).isInstanceOf(BizException.class);
    verify(sessionMapper, never()).insert(any(BatchDayReplaySessionEntity.class));
    verify(previewTokenMapper, never()).consume(anyString(), anyString());
  }

  @Test
  @DisplayName("并发消费同一预览凭证时返回预览失效业务冲突")
  void shouldRejectSubmit_whenPreviewTokenWasConsumedConcurrently() {
    when(jobInstanceMapper.selectBatchDayCandidates(
            anyString(), anyString(), any(), anyList(), anyList()))
        .thenReturn(List.of(jobInstance(101L, "JOB_A")));
    BatchDayReplaySubmitCommand command = BatchDayReplaySubmitCommand.builder()
        .tenantId("t1")
        .calendarCode("CAL")
        .bizDate(LocalDate.of(2026, Month.MAY, 4))
        .scope("ALL_FAILED")
        .reason("upstream backfill")
        .requestedBy("ops")
        .build();
    BatchDayReplaySubmitCommand authorized = withPreviewToken(service, command);
    when(previewTokenMapper.selectForUpdate(anyString(), anyString()))
        .thenThrow(new PessimisticLockingFailureException("preview token was consumed"));

    assertThatThrownBy(() -> service.submit(authorized)).isInstanceOf(BizException.class);
    verify(sessionMapper, never()).insert(any(BatchDayReplaySessionEntity.class));
    verify(previewTokenMapper, never()).consume(anyString(), anyString());
  }

  @Test
  @DisplayName("提交参数与已预览参数不同时拒绝创建会话")
  void shouldRejectSubmit_whenRequestChangedAfterPreview() {
    when(jobInstanceMapper.selectBatchDayCandidates(
            anyString(), anyString(), any(), anyList(), anyList()))
        .thenReturn(List.of(jobInstance(101L, "JOB_A")));
    BatchDayReplaySubmitCommand command = BatchDayReplaySubmitCommand.builder()
        .tenantId("t1")
        .calendarCode("CAL")
        .bizDate(LocalDate.of(2026, Month.MAY, 4))
        .scope("ALL_FAILED")
        .reason("original request")
        .requestedBy("ops")
        .build();
    BatchDayReplaySubmitCommand authorized = withPreviewToken(service, command);
    BatchDayReplaySubmitCommand changed =
        copyWithToken(authorized, "changed request", authorized.previewToken());

    assertThatThrownBy(() -> service.submit(changed)).isInstanceOf(BizException.class);
    verify(sessionMapper, never()).insert(any(BatchDayReplaySessionEntity.class));
    verify(previewTokenMapper, never()).consume(anyString(), anyString());
  }

  @Test
  @DisplayName("预览后候选发生变化时拒绝提交旧快照")
  void shouldRejectSubmit_whenCandidateSnapshotChanged() {
    when(jobInstanceMapper.selectBatchDayCandidates(
            anyString(), anyString(), any(), anyList(), anyList()))
        .thenReturn(List.of(jobInstance(101L, "JOB_A")))
        .thenReturn(List.of(jobInstance(102L, "JOB_A")));
    BatchDayReplaySubmitCommand command = BatchDayReplaySubmitCommand.builder()
        .tenantId("t1")
        .calendarCode("CAL")
        .bizDate(LocalDate.of(2026, Month.MAY, 4))
        .scope("ALL_FAILED")
        .reason("upstream backfill")
        .requestedBy("ops")
        .build();
    BatchDayReplaySubmitCommand authorized = withPreviewToken(service, command);

    assertThatThrownBy(() -> service.submit(authorized)).isInstanceOf(BizException.class);
    verify(sessionMapper, never()).insert(any(BatchDayReplaySessionEntity.class));
    verify(previewTokenMapper, never()).consume(anyString(), anyString());
  }

  @Test
  @DisplayName("过期预览凭证不能提交")
  void shouldRejectSubmit_whenPreviewTokenExpired() {
    when(jobInstanceMapper.selectBatchDayCandidates(
            anyString(), anyString(), any(), anyList(), anyList()))
        .thenReturn(List.of(jobInstance(101L, "JOB_A")));
    BatchDayReplaySubmitCommand command = BatchDayReplaySubmitCommand.builder()
        .tenantId("t1")
        .calendarCode("CAL")
        .bizDate(LocalDate.of(2026, Month.MAY, 4))
        .scope("ALL_FAILED")
        .reason("upstream backfill")
        .requestedBy("ops")
        .build();
    BatchDayReplaySubmitCommand authorized = withPreviewToken(service, command);
    BatchDayReplayPreviewTokenEntity token = storedPreviewToken.get();
    storedPreviewToken.set(new BatchDayReplayPreviewTokenEntity(
        token.tenantId(),
        token.tokenHash(),
        token.requestHash(),
        token.snapshotHash(),
        Instant.EPOCH,
        null,
        null));

    assertThatThrownBy(() -> service.submit(authorized)).isInstanceOf(BizException.class);
    verify(sessionMapper, never()).insert(any(BatchDayReplaySessionEntity.class));
  }

  @Test
  @DisplayName("预览结果同时包含资产分区影响与派发影响明细")
  void shouldIncludeImpacts_whenPreviewing() {
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
  @DisplayName("没有候选实例时预览返回提示, 不创建空会话")
  void shouldReturnWarning_whenNoCandidates() {
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
  @DisplayName("没有候选实例时提交被拒绝并抛出业务异常")
  void shouldThrow_whenSubmittingWithoutCandidates() {
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
  @DisplayName("试运行提交默认关闭时被拒绝, 且不写会话")
  void shouldRejectDryRunSubmit_whenDisabledByDefault() {
    when(jobInstanceMapper.selectBatchDayCandidates(
            anyString(), anyString(), any(), anyList(), anyList()))
        .thenReturn(List.of(jobInstance(101L, "JOB_A")));

    assertThatThrownBy(() -> service.submit(baseDryRunCommand().build()))
        .isInstanceOf(BizException.class);
    verify(sessionMapper, never()).insert(any());
  }

  @Test
  @DisplayName("启用试运行提交时会话的执行模式与结果策略被强制为仅试运行")
  void shouldForceDryRunPolicy_whenDryRunSubmitEnabled() {
    BatchDayReplayService dryRunService = dryRunService(true);
    when(jobInstanceMapper.selectBatchDayCandidates(
            anyString(), anyString(), any(), anyList(), anyList()))
        .thenReturn(List.of(jobInstance(101L, "JOB_A")));
    when(sessionMapper.insert(any())).thenReturn(1);
    when(sessionMapper.selectActiveByCalendarBizDate("t1", "CAL", LocalDate.of(2026, Month.MAY, 4)))
        .thenReturn(sessionAt("t1", 9L, "RUNNING", "ALL"));

    BatchDayReplaySubmitCommand command =
        baseDryRunCommand().resultPolicy("CREATE_NEW_VERSION").build();
    dryRunService.submit(withPreviewToken(dryRunService, command));

    ArgumentCaptor<BatchDayReplaySessionEntity> captor =
        ArgumentCaptor.forClass(BatchDayReplaySessionEntity.class);
    verify(sessionMapper).insert(captor.capture());
    assertThat(captor.getValue().executionMode()).isEqualTo("DRY_RUN");
    assertThat(captor.getValue().resultPolicy()).isEqualTo("DRY_RUN_ONLY");
  }

  @Test
  @DisplayName("按计划预览时生成不可变快照条目, 条目不关联来源实例")
  void shouldMaterializeImmutableSnapshot_whenPreviewingSchedulePlan() {
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
  @DisplayName("同日历营业日已有活动会话时提交抛出业务异常")
  void shouldThrow_whenActiveSessionAlreadyExists() {
    when(jobInstanceMapper.selectBatchDayCandidates(
            anyString(), anyString(), any(), anyList(), anyList()))
        .thenReturn(List.of(jobInstance(101L, "JOB_A")));
    when(sessionMapper.insert(any(BatchDayReplaySessionEntity.class)))
        .thenThrow(new DuplicateKeyException("uk_replay_session_active"));

    assertThatThrownBy(() -> service.submit(withPreviewToken(
            service,
            BatchDayReplaySubmitCommand.builder()
                .tenantId("t1")
                .calendarCode("CAL")
                .bizDate(LocalDate.of(2026, Month.MAY, 4))
                .scope("ALL_FAILED")
                .reason("...")
                .requestedBy("ops")
                .autoApprove(true)
                .build())))
        .isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("子集范围未给出任务清单时提交被拒绝")
  void shouldRejectSubmit_whenSubsetScopeHasNoJobCodes() {
    assertThatThrownBy(() -> service.submit(withPreviewToken(
            service,
            BatchDayReplaySubmitCommand.builder()
                .tenantId("t1")
                .calendarCode("CAL")
                .bizDate(LocalDate.of(2026, Month.MAY, 4))
                .scope("SUBSET_JOB_CODES")
                .reason("...")
                .requestedBy("ops")
                .autoApprove(true)
                .build())))
        .isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("仅产出范围提交时按结果版本批量生成条目, 不查询作业实例")
  void shouldMaterializeFromVersionIds_whenSubmittingOutputsOnly() {
    // R7-A3-P1: materializeOutputsOnlyEntries 改用 selectByIds 批量预取替代 N+1。
    when(resultVersionMapper.selectByIds(eq("t1"), any()))
        .thenReturn(List.of(
            resultVersion(11L, "job:JOB_A:2026-05-04", 100L),
            resultVersion(12L, "job:JOB_B:2026-05-04", 101L)));
    when(sessionMapper.insert(any(BatchDayReplaySessionEntity.class))).thenReturn(1);
    when(sessionMapper.selectActiveByCalendarBizDate("t1", "CAL", LocalDate.of(2026, Month.MAY, 4)))
        .thenReturn(sessionAt("t1", 5L, "RUNNING", BatchDayReplayScope.OUTPUTS_ONLY.code()));

    BatchDayReplaySubmitCommand command = BatchDayReplaySubmitCommand.builder()
        .tenantId("t1")
        .calendarCode("CAL")
        .bizDate(LocalDate.of(2026, Month.MAY, 4))
        .scope(BatchDayReplayScope.OUTPUTS_ONLY.code())
        .versionIds(List.of(11L, 12L))
        .reason("regulatory restate")
        .requestedBy("ops")
        .autoApprove(true)
        .build();
    BatchDayReplaySessionEntity result = service.submit(withPreviewToken(service, command));

    assertThat(result.scope()).isEqualTo(BatchDayReplayScope.OUTPUTS_ONLY.code());
    verify(entryMapper).insertBatch(anyList());
    verify(jobInstanceMapper, never())
        .selectBatchDayCandidates(anyString(), anyString(), any(), anyList(), anyList());
  }

  @Test
  @DisplayName("仅产出范围预览展示待提升的结果版本及其影响")
  void shouldShowPromotedVersions_whenPreviewingOutputsOnly() {
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
  @DisplayName("审批待处理会话后状态推进为运行中")
  void shouldAdvanceToRunning_whenApprovingPendingSession() {
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
  @DisplayName("会话已在运行中时审批被拒绝")
  void shouldThrow_whenApprovingRunningSession() {
    when(sessionMapper.selectById("t1", 1L))
        .thenReturn(sessionAt("t1", 1L, "RUNNING", "ALL_FAILED"));

    assertThatThrownBy(() -> service.approve("t1", 1L, "approver"))
        .isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("取消运行中的会话后状态变为已取消")
  void shouldMoveToCancelled_whenCancellingActiveSession() {
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
  @DisplayName("会话已处于终态时取消被拒绝")
  void shouldThrow_whenCancellingTerminalSession() {
    when(sessionMapper.selectById("t1", 1L))
        .thenReturn(sessionAt("t1", 1L, "SUCCEEDED", "ALL_FAILED"));

    assertThatThrownBy(() -> service.cancel("t1", 1L)).isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("查询重放条目时空白状态表示全部,显式状态仍传给 mapper")
  void shouldNormalizeBlankEntryStatus_whenListingEntries() {
    when(sessionMapper.selectById("t1", 5L))
        .thenReturn(sessionAt("t1", 5L, "RUNNING", "ALL_FAILED"));
    when(entryMapper.selectBySessionAndStatus(5L, "t1", null, 20)).thenReturn(List.of());
    when(entryMapper.selectBySessionAndStatus(5L, "t1", "PENDING", 20)).thenReturn(List.of());

    assertThat(service.listEntries("t1", 5L, null, 20)).isEmpty();
    assertThat(service.listEntries("t1", 5L, " ", 20)).isEmpty();
    assertThat(service.listEntries("t1", 5L, "PENDING", 20)).isEmpty();

    verify(entryMapper, times(2)).selectBySessionAndStatus(5L, "t1", null, 20);
    verify(entryMapper).selectBySessionAndStatus(5L, "t1", "PENDING", 20);
  }

  @Test
  @DisplayName("执行仅产出会话时逐条提升结果版本, 完成后会话置为成功")
  void shouldPromoteEachEntryAndComplete_whenExecutingOutputsOnly() {
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
  @DisplayName("对非仅产出范围的会话执行提升时被拒绝")
  void shouldThrow_whenExecutingNonOutputsOnlyScope() {
    when(sessionMapper.selectById("t1", 5L))
        .thenReturn(sessionAt("t1", 5L, "RUNNING", "ALL_FAILED"));

    assertThatThrownBy(() -> service.executeOutputsOnly("t1", 5L)).isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("会话不处于运行中时执行提升被拒绝")
  void shouldThrow_whenExecutingSessionNotRunning() {
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
        previewTokenMapper,
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

  private BatchDayReplaySubmitCommand withPreviewToken(
      BatchDayReplayService replayService, BatchDayReplaySubmitCommand command) {
    BatchDayReplayPreviewResponse preview = replayService.preview(command);
    return copyWithToken(command, command.reason(), preview.previewToken());
  }

  private BatchDayReplaySubmitCommand copyWithToken(
      BatchDayReplaySubmitCommand command, String reason, String previewToken) {
    return new BatchDayReplaySubmitCommand(
        command.tenantId(),
        command.calendarCode(),
        command.bizDate(),
        command.scope(),
        command.jobCodes(),
        command.versionIds(),
        command.resultPolicy(),
        command.configVersionPolicy(),
        command.configVersion(),
        reason,
        command.requestedBy(),
        command.autoApprove(),
        command.traceId(),
        command.executionMode(),
        command.candidateSource(),
        previewToken);
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
