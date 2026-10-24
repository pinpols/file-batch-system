package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.awaitility.Awaitility.await;

import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.service.replay.BatchDayReplayPreviewResponse;
import io.github.pinpols.batch.orchestrator.application.service.replay.BatchDayReplayRangeCommand;
import io.github.pinpols.batch.orchestrator.application.service.replay.BatchDayReplayRangeOperationService;
import io.github.pinpols.batch.orchestrator.application.service.replay.BatchDayReplayService;
import io.github.pinpols.batch.orchestrator.application.service.replay.BatchDayReplaySubmitCommand;
import io.github.pinpols.batch.orchestrator.application.service.replay.ReplayOperationPreviewResponse;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayReplaySessionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.ReplayOperationEntity;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayPlanCalendarMapper;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayReplaySessionMapper;
import io.github.pinpols.batch.orchestrator.mapper.DisasterDayOverrideMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("日期区间 Replay Operation 服务级 PostgreSQL 事务与并发验收")
class BatchDayReplayRangeOperationServiceIntegrationTest extends AbstractIntegrationTest {

  private static final LocalDate FROM_DATE = LocalDate.of(2026, 10, 19);
  private static final LocalDate THROUGH_DATE = LocalDate.of(2026, 10, 20);

  @Autowired
  private BatchDayReplayRangeOperationService rangeOperationService;

  @Autowired
  private BatchDayReplaySessionMapper sessionMapper;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @MockitoBean
  private BatchDayReplayService singleDayReplayService;

  @MockitoBean
  private BatchDayPlanCalendarMapper planCalendarMapper;

  @MockitoBean
  private DisasterDayOverrideMapper disasterDayOverrideMapper;

  @Test
  @DisplayName("第二个业务日子 session 创建失败时,父操作、首日子 session 和凭证消费全部回滚")
  void shouldRollbackWholeAdmission_whenLaterChildCreationFails() {
    String tenantId = newTenantId();
    BatchDayReplayRangeCommand previewCommand = command(tenantId, null, null);
    configureCalendarAndPreviews();
    ReplayOperationPreviewResponse preview = rangeOperationService.preview(previewCommand);
    BatchDayReplayRangeCommand submitCommand = command(
        tenantId, preview.operationKey(), preview.previewToken());

    doAnswer(invocation -> {
      BatchDayReplaySubmitCommand childCommand = invocation.getArgument(0);
      if (childCommand.bizDate().equals(THROUGH_DATE)) {
        throw new IllegalStateException("injected second-day admission failure");
      }
      insertChildSession(childCommand, invocation.getArgument(1));
      return sessionMapper.selectActiveByCalendarBizDate(
          tenantId, childCommand.calendarCode(), childCommand.bizDate());
    }).when(singleDayReplayService).submitForOperation(any(), anyLong());

    assertThatThrownBy(() -> rangeOperationService.submit(submitCommand))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("injected second-day admission failure");

    int persistedOperations = jdbcTemplate.queryForObject(
        selectOperationCountSql, Integer.class, tenantId, preview.operationKey());
    int persistedChildren = jdbcTemplate.queryForObject(
        selectChildCountSql, Integer.class, tenantId, FROM_DATE, THROUGH_DATE);
    int consumedPreviewTokens = jdbcTemplate.queryForObject(
        selectConsumedPreviewTokenCountSql, Integer.class, tenantId, preview.operationKey());

    assertThat(persistedOperations).isZero();
    assertThat(persistedChildren).isZero();
    assertThat(consumedPreviewTokens).isZero();
  }

  @Test
  @DisplayName("并发提交同一预览凭证时只创建一份父操作和每日子 session")
  void shouldCreateSingleOperation_whenSamePreviewIsSubmittedConcurrently() throws Exception {
    String tenantId = newTenantId();
    configureCalendarAndPreviews();
    ReplayOperationPreviewResponse preview = rangeOperationService.preview(
        command(tenantId, null, null));
    BatchDayReplayRangeCommand submitCommand = command(
        tenantId, preview.operationKey(), preview.previewToken());
    CountDownLatch firstSubmissionInsideTransaction = new CountDownLatch(1);
    CountDownLatch releaseFirstSubmission = new CountDownLatch(1);
    AtomicBoolean pauseFirstChildPreview = new AtomicBoolean(true);
    ExecutorService executor = Executors.newFixedThreadPool(2);

    try {
      doAnswer(invocation -> {
        if (pauseFirstChildPreview.compareAndSet(true, false)) {
          firstSubmissionInsideTransaction.countDown();
          if (!releaseFirstSubmission.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("timed out waiting to release first admission");
          }
        }
        return childPreview(invocation.getArgument(0), "child-token");
      }).when(singleDayReplayService).preview(any());
      insertChildSessionsForOperation(tenantId);

      Future<ReplayOperationEntity> firstSubmission = executor.submit(
          () -> rangeOperationService.submit(submitCommand));
      assertThat(firstSubmissionInsideTransaction.await(10, TimeUnit.SECONDS)).isTrue();
      Future<ReplayOperationEntity> duplicateSubmission = executor.submit(
          () -> rangeOperationService.submit(submitCommand));

      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(() -> {
            int lockWaiters = jdbcTemplate.queryForObject(
                selectPreviewTokenLockWaiterCountSql, Integer.class);
            assertThat(lockWaiters).isGreaterThanOrEqualTo(1);
          });
      releaseFirstSubmission.countDown();

      ReplayOperationEntity accepted = firstSubmission.get(10, TimeUnit.SECONDS);
      ReplayOperationEntity duplicate = duplicateSubmission.get(10, TimeUnit.SECONDS);
      assertThat(duplicate.id()).isEqualTo(accepted.id());

      int persistedOperations = jdbcTemplate.queryForObject(
          selectOperationCountSql, Integer.class, tenantId, preview.operationKey());
      int persistedChildren = jdbcTemplate.queryForObject(
          selectChildCountSql, Integer.class, tenantId, FROM_DATE, THROUGH_DATE);
      assertThat(persistedOperations).isEqualTo(1);
      assertThat(persistedChildren).isEqualTo(2);
    } finally {
      releaseFirstSubmission.countDown();
      executor.shutdownNow();
    }
  }

  private void configureCalendarAndPreviews() {
    when(planCalendarMapper.selectEffectiveDayType(any(), any(), any())).thenReturn("WORKDAY");
    when(singleDayReplayService.previewImpact(any())).thenAnswer(
        invocation -> childPreview(invocation.getArgument(0), null));
    when(singleDayReplayService.preview(any())).thenAnswer(
        invocation -> childPreview(invocation.getArgument(0), "child-token"));
  }

  private void insertChildSessionsForOperation(String tenantId) {
    doAnswer(invocation -> {
      BatchDayReplaySubmitCommand childCommand = invocation.getArgument(0);
      Long operationId = invocation.getArgument(1);
      insertChildSession(childCommand, operationId);
      return sessionMapper.selectActiveByCalendarBizDate(
          tenantId, childCommand.calendarCode(), childCommand.bizDate());
    }).when(singleDayReplayService).submitForOperation(any(), anyLong());
  }

  private void insertChildSession(BatchDayReplaySubmitCommand command, Long operationId) {
    Instant now = Instant.now();
    sessionMapper.insert(BatchDayReplaySessionEntity.builder()
        .tenantId(command.tenantId())
        .replayOperationId(operationId)
        .calendarCode(command.calendarCode())
        .bizDate(command.bizDate())
        .scope(command.scope())
        .executionMode(command.executionMode())
        .candidateSource(command.candidateSource())
        .scopePayload("{}")
        .resultPolicy(command.resultPolicy())
        .configVersionPolicy(command.configVersionPolicy())
        .configVersion(command.configVersion())
        .reason(command.reason())
        .status("PENDING_APPROVAL")
        .totalCount(1)
        .succeededCount(0)
        .failedCount(0)
        .inFlightCount(1)
        .requestedBy(command.requestedBy())
        .traceId(command.traceId())
        .createdAt(now)
        .updatedAt(now)
        .build());
  }

  private static BatchDayReplayPreviewResponse childPreview(
      BatchDayReplaySubmitCommand command, String previewToken) {
    BatchDayReplayPreviewResponse.PreviewEntry entry =
        new BatchDayReplayPreviewResponse.PreviewEntry(
            "JOB_A", 500L, null, "RERUN_INSTANCE", "job:JOB_A");
    return new BatchDayReplayPreviewResponse(
        command.tenantId(), command.calendarCode(), command.bizDate(), command.scope(),
        "REPLAY", "EXISTING_INSTANCES", "CREATE_NEW_VERSION", "USE_ORIGINAL_CONFIG", null,
        previewToken, Instant.now().plusSeconds(60), 1, List.of(entry), List.of(), List.of(),
        List.of(), List.of());
  }

  private static BatchDayReplayRangeCommand command(
      String tenantId, UUID operationKey, String previewToken) {
    return new BatchDayReplayRangeCommand(
        tenantId, "CAL-RANGE-IT", FROM_DATE, THROUGH_DATE, "ALL_FAILED", null, null,
        "CREATE_NEW_VERSION", "USE_ORIGINAL_CONFIG", null, "integration", "operator", false,
        "trace-range-it", "REPLAY", "EXISTING_INSTANCES", operationKey, previewToken);
  }

  private static String newTenantId() {
    return "replay-range-it-" + UUID.randomUUID().toString().replace("-", "");
  }

  private static final String selectOperationCountSql = """
      select count(*)::int
      from batch.replay_operation
      where tenant_id = ? and operation_key = ?::uuid
      """;

  private static final String selectChildCountSql = """
      select count(*)::int
      from batch.batch_day_replay_session
      where tenant_id = ? and biz_date between ? and ?
      """;

  private static final String selectConsumedPreviewTokenCountSql = """
      select count(*)::int
      from batch.replay_operation_preview_token
      where tenant_id = ? and operation_key = ?::uuid and consumed_at is not null
      """;

  private static final String selectPreviewTokenLockWaiterCountSql = """
      select count(*)::int
      from pg_stat_activity
      where wait_event_type = 'Lock'
        and query ilike '%replay_operation_preview_token%'
      """;
}
