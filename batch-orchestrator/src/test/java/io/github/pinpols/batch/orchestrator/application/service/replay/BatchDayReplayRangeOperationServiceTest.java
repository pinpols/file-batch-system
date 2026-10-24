package io.github.pinpols.batch.orchestrator.application.service.replay;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchTimezoneProperties;
import io.github.pinpols.batch.common.config.BatchTimezoneProvider;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.orchestrator.config.ReplayOperationProperties;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayReplaySessionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.ReplayOperationEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.ReplayOperationPreviewTokenEntity;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayPlanCalendarMapper;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayReplaySessionMapper;
import io.github.pinpols.batch.orchestrator.mapper.DisasterDayOverrideMapper;
import io.github.pinpols.batch.orchestrator.mapper.ReplayOperationMapper;
import io.github.pinpols.batch.orchestrator.mapper.ReplayOperationPreviewTokenMapper;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("日期区间重放 operation: 范围验证先于日历和候选查询")
class BatchDayReplayRangeOperationServiceTest {

  private BatchDayReplayService singleDayReplayService;
  private ReplayOperationMapper operationMapper;
  private ReplayOperationPreviewTokenMapper previewTokenMapper;
  private BatchDayReplaySessionMapper sessionMapper;
  private BatchDayPlanCalendarMapper planCalendarMapper;
  private DisasterDayOverrideMapper disasterDayOverrideMapper;
  private ReplayOperationProperties properties;
  private BatchDayReplayRangeOperationService service;

  @BeforeEach
  void setUp() {
    singleDayReplayService = mock(BatchDayReplayService.class);
    operationMapper = mock(ReplayOperationMapper.class);
    previewTokenMapper = mock(ReplayOperationPreviewTokenMapper.class);
    sessionMapper = mock(BatchDayReplaySessionMapper.class);
    planCalendarMapper = mock(BatchDayPlanCalendarMapper.class);
    disasterDayOverrideMapper = mock(DisasterDayOverrideMapper.class);
    properties = new ReplayOperationProperties();
    service = new BatchDayReplayRangeOperationService(
        singleDayReplayService,
        operationMapper,
        previewTokenMapper,
        sessionMapper,
        planCalendarMapper,
        disasterDayOverrideMapper,
        new BatchDateTimeSupport(
            Clock.systemUTC(), new BatchTimezoneProvider(new BatchTimezoneProperties())),
        properties);
  }

  @Test
  @DisplayName("超过显式日期上限时拒绝，不查询业务日历或候选")
  void shouldRejectRangeBeyondConfiguredLimitBeforePlanning() {
    BatchDayReplayRangeCommand command = command(
        LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1));

    assertThatThrownBy(() -> service.preview(command))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("range_too_large");

    verifyNoInteractions(
        singleDayReplayService, operationMapper, previewTokenMapper, planCalendarMapper,
        disasterDayOverrideMapper);
  }

  @Test
  @DisplayName("恰好达到默认日期上限时按含首尾日期预览全部业务日")
  void shouldAcceptRangeAtConfiguredLimit() {
    LocalDate fromDate = LocalDate.of(2026, 1, 1);
    LocalDate throughDate = LocalDate.of(2026, 1, 31);
    when(singleDayReplayService.previewImpact(any()))
        .thenAnswer(invocation -> preview(invocation.getArgument(0), null));

    ReplayOperationPreviewResponse response = service.preview(command(fromDate, throughDate));

    assertThat(response.days()).hasSize(31);
    assertThat(response.days().getFirst().bizDate()).isEqualTo(fromDate);
    assertThat(response.days().getLast().bizDate()).isEqualTo(throughDate);
    assertThat(response.totalCandidateCount()).isEqualTo(31);
    verify(singleDayReplayService, org.mockito.Mockito.times(31)).previewImpact(any());
  }

  @Test
  @DisplayName("反向日期区间在访问日历前拒绝")
  void shouldRejectReversedDatesBeforePlanning() {
    BatchDayReplayRangeCommand command = command(
        LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 1));

    assertThatThrownBy(() -> service.preview(command))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("invalid_argument");

    verifyNoInteractions(
        singleDayReplayService, operationMapper, previewTokenMapper, planCalendarMapper,
        disasterDayOverrideMapper);
  }

  @Test
  @DisplayName("预览签发父凭证，提交一次性创建父 operation 和两个每日子 session，重试不重复创建")
  void shouldAdmitRangeAsParentAndDayChildrenUsingOneFrozenPreview() {
    LocalDate firstDate = LocalDate.of(2026, 1, 5);
    LocalDate secondDate = LocalDate.of(2026, 1, 6);
    BatchDayReplayRangeCommand initialCommand = command(firstDate, secondDate);
    when(planCalendarMapper.selectEffectiveDayType("tenant", "CAL", firstDate))
        .thenReturn("WORKDAY");
    when(planCalendarMapper.selectEffectiveDayType("tenant", "CAL", secondDate))
        .thenReturn("WORKDAY");
    when(singleDayReplayService.previewImpact(any()))
        .thenAnswer(invocation -> preview(invocation.getArgument(0), null));
    AtomicReference<ReplayOperationPreviewTokenEntity> storedToken = new AtomicReference<>();
    when(previewTokenMapper.insert(any())).thenAnswer(invocation -> {
      storedToken.set(invocation.getArgument(0));
      return 1;
    });

    ReplayOperationPreviewResponse preview = service.preview(initialCommand);

    assertThat(preview.totalCandidateCount()).isEqualTo(2);
    assertThat(preview.days()).extracting(ReplayOperationPreviewResponse.DayPreview::bizDate)
        .containsExactly(firstDate, secondDate);
    assertThat(storedToken.get().operationKey()).isEqualTo(preview.operationKey().toString());
    assertThat(storedToken.get().snapshotPayload()).doesNotContain("child-preview-token");

    BatchDayReplayRangeCommand submitCommand = new BatchDayReplayRangeCommand(
        "tenant", "CAL", firstDate, secondDate, "ALL_FAILED", null, null, null, null,
        null, "reason", "operator", false, "trace", "REPLAY", "EXISTING_INSTANCES",
        preview.operationKey(), preview.previewToken());
    when(operationMapper.selectByIdentity("tenant", preview.operationKey().toString()))
        .thenReturn(null)
        .thenAnswer(invocation -> operation().toBuilder().id(70L).operationKey(preview.operationKey().toString()).build());
    when(previewTokenMapper.selectForUpdate("tenant", sha256(preview.previewToken())))
        .thenReturn(storedToken.get());
    when(previewTokenMapper.consume(
        eq("tenant"), eq(sha256(preview.previewToken())), any(Instant.class)))
        .thenReturn(1);
    when(operationMapper.insert(any(ReplayOperationEntity.class))).thenReturn(1);
    when(singleDayReplayService.preview(any()))
        .thenAnswer(invocation -> preview(invocation.getArgument(0), "child-preview-token"));
    when(singleDayReplayService.submitForOperation(any(), eq(70L))).thenAnswer(invocation -> {
      var childCommand = (BatchDayReplaySubmitCommand) invocation.getArgument(0);
      return BatchDayReplaySessionEntity.builder()
          .id(childCommand.bizDate().equals(firstDate) ? 101L : 102L)
          .tenantId("tenant")
          .replayOperationId(70L)
          .bizDate(childCommand.bizDate())
          .status("PENDING_APPROVAL")
          .build();
    });
    when(previewTokenMapper.linkOperation("tenant", sha256(preview.previewToken()), 70L))
        .thenReturn(1);
    when(operationMapper.refreshFromChildSessions(eq("tenant"), eq(70L), any())).thenReturn(1);
    when(operationMapper.selectById("tenant", 70L)).thenReturn(operation().toBuilder()
        .id(70L)
        .operationKey(preview.operationKey().toString())
        .build());

    ReplayOperationEntity accepted = service.submit(submitCommand);

    assertThat(accepted.id()).isEqualTo(70L);
    ArgumentCaptor<BatchDayReplaySubmitCommand> childCommandCaptor =
        ArgumentCaptor.forClass(BatchDayReplaySubmitCommand.class);
    verify(singleDayReplayService, org.mockito.Mockito.times(2))
        .submitForOperation(childCommandCaptor.capture(), eq(70L));
    assertThat(childCommandCaptor.getAllValues())
        .extracting(BatchDayReplaySubmitCommand::bizDate)
        .containsExactly(firstDate, secondDate);
    assertThat(childCommandCaptor.getAllValues())
        .extracting(BatchDayReplaySubmitCommand::previewToken)
        .containsOnly("child-preview-token");

    ArgumentCaptor<ReplayOperationEntity> insertedOperationCaptor =
        ArgumentCaptor.forClass(ReplayOperationEntity.class);
    verify(operationMapper).insert(insertedOperationCaptor.capture());
    ReplayOperationEntity persistedOperation = insertedOperationCaptor.getValue().toBuilder()
        .id(70L)
        .build();
    org.mockito.Mockito.doReturn(persistedOperation)
        .when(operationMapper)
        .selectByIdentity("tenant", preview.operationKey().toString());

    BatchDayReplayRangeCommand retriedCommand = new BatchDayReplayRangeCommand(
        submitCommand.tenantId(), submitCommand.calendarCode(), submitCommand.fromDate(),
        submitCommand.throughDate(), submitCommand.scope(), submitCommand.jobCodes(),
        submitCommand.versionIds(), submitCommand.resultPolicy(), submitCommand.configVersionPolicy(),
        submitCommand.configVersion(), submitCommand.reason(), submitCommand.requestedBy(),
        submitCommand.autoApprove(), "retry-trace", submitCommand.executionMode(),
        submitCommand.candidateSource(), submitCommand.operationKey(), submitCommand.previewToken());
    ReplayOperationEntity retried = service.submit(retriedCommand);

    assertThat(retried).isEqualTo(persistedOperation);
    verify(singleDayReplayService, org.mockito.Mockito.times(2))
        .submitForOperation(any(), eq(70L));
  }

  @Test
  @DisplayName("同一审批人重试已完成的父操作审批时返回既有操作")
  void shouldReturnExistingOperation_whenApprovalWasAlreadyAppliedBySameOperator() {
    ReplayOperationEntity alreadyApproved = operation().toBuilder()
        .id(70L)
        .status("RUNNING")
        .approvedBy("admin")
        .build();
    when(operationMapper.selectForUpdate("tenant", 70L)).thenReturn(alreadyApproved);

    ReplayOperationEntity result = service.approve("tenant", 70L, "admin");

    assertThat(result).isEqualTo(alreadyApproved);
    verify(singleDayReplayService, never()).approve(anyString(), anyLong(), anyString());
  }

  @Test
  @DisplayName("只按租户和区间父操作读取每日子 session")
  void shouldListSessionsOnlyAfterTenantScopedOperationExists() {
    ReplayOperationEntity parent = operation().toBuilder().id(70L).build();
    List<BatchDayReplaySessionEntity> expected = List.of(
        BatchDayReplaySessionEntity.builder().id(101L).tenantId("tenant")
            .replayOperationId(70L).bizDate(LocalDate.of(2026, 1, 5)).build());
    when(operationMapper.selectById("tenant", 70L)).thenReturn(parent);
    when(sessionMapper.selectByReplayOperation("tenant", 70L)).thenReturn(expected);

    assertThat(service.sessions("tenant", 70L)).isEqualTo(expected);

    verify(sessionMapper).selectByReplayOperation("tenant", 70L);
  }

  @Test
  @DisplayName("租户无权读取的父操作不查询任何子 session")
  void shouldNotQuerySessions_whenParentOperationIsNotVisibleToTenant() {
    when(operationMapper.selectById("other-tenant", 70L)).thenReturn(null);

    assertThatThrownBy(() -> service.sessions("other-tenant", 70L))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("operation_not_found");

    verify(sessionMapper, never()).selectByReplayOperation(anyString(), anyLong());
  }

  private static BatchDayReplayRangeCommand command(LocalDate from, LocalDate through) {
    return new BatchDayReplayRangeCommand(
        "tenant", "CAL", from, through, "ALL_FAILED", null, null, null, null,
        null, "reason", "operator", false, "trace", "REPLAY", "EXISTING_INSTANCES", null, null);
  }

  private static BatchDayReplayPreviewResponse preview(
      BatchDayReplaySubmitCommand command, String previewToken) {
    BatchDayReplayPreviewResponse.PreviewEntry entry =
        new BatchDayReplayPreviewResponse.PreviewEntry("JOB_A", 500L, null, "RERUN_INSTANCE", "job:JOB_A");
    return new BatchDayReplayPreviewResponse(
        command.tenantId(), command.calendarCode(), command.bizDate(), command.scope(), "REPLAY",
        "EXISTING_INSTANCES", "CREATE_NEW_VERSION", "USE_ORIGINAL_CONFIG", null,
        previewToken, previewToken == null ? null : Instant.parse("2026-01-05T12:05:00Z"),
        1, List.of(entry), List.of(), List.of(), List.of(), List.of());
  }

  private static ReplayOperationEntity operation() {
    return ReplayOperationEntity.builder()
        .tenantId("tenant")
        .operationKey(UUID.randomUUID().toString())
        .operationType("BATCH_DAY_RANGE")
        .status("PENDING_APPROVAL")
        .requestHash("a".repeat(64))
        .snapshotHash("b".repeat(64))
        .snapshotPayload("[]")
        .reason("reason")
        .requestedBy("operator")
        .totalCount(2)
        .succeededCount(0)
        .failedCount(0)
        .inFlightCount(2)
        .build();
  }

  private static String sha256(String value) {
    try {
      var digest = java.security.MessageDigest.getInstance("SHA-256");
      return java.util.HexFormat.of().formatHex(digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
