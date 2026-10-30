package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.kafka.BatchTopics;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.service.governance.ApprovalWorkflowService;
import io.github.pinpols.batch.orchestrator.application.service.governance.CompensationService;
import io.github.pinpols.batch.orchestrator.application.service.replay.BatchDayReplayRangeCommand;
import io.github.pinpols.batch.orchestrator.application.service.replay.BatchDayReplayRangeOperationService;
import io.github.pinpols.batch.orchestrator.application.service.replay.BatchDayReplayService;
import io.github.pinpols.batch.orchestrator.application.service.replay.BatchDayReplayTerminalReconciler;
import io.github.pinpols.batch.orchestrator.application.service.replay.ReplayOperationPreviewResponse;
import io.github.pinpols.batch.orchestrator.application.service.replay.WorkflowNodeReplayCommand;
import io.github.pinpols.batch.orchestrator.application.service.replay.WorkflowNodeReplayOperationService;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskExecutionService;
import io.github.pinpols.batch.orchestrator.application.service.version.ResultVersionPromoteService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunLaunchService;
import io.github.pinpols.batch.orchestrator.config.BatchDayReplayDispatchProperties;
import io.github.pinpols.batch.orchestrator.domain.command.CompensationSubmitCommand;
import io.github.pinpols.batch.orchestrator.domain.command.TaskOutcomeCommand;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayReplayEntryEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayReplaySessionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.ReplayOperationEntity;
import io.github.pinpols.batch.orchestrator.domain.query.JobInstanceBatchDayCandidatesQuery;
import io.github.pinpols.batch.orchestrator.domain.query.JobTaskQuery;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import io.github.pinpols.batch.orchestrator.infrastructure.scheduler.BatchDayReplayDispatcher;
import io.github.pinpols.batch.orchestrator.infrastructure.scheduler.WorkerRegistryCache;
import io.github.pinpols.batch.orchestrator.infrastructure.trigger.TriggerLaunchConsumer;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayReplayEntryMapper;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayReplaySessionMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobTaskMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("重放执行身份与 REPORT 原子性：真实启动、来源隔离、回滚和并发计数")
class ReplayExecutionIdentityIntegrationTest extends AbstractIntegrationTest {
  private static final LocalDate DAY = LocalDate.of(2026, 10, 16);

  private final BatchDayReplayDispatcher dispatcher;

  private final BatchDayReplayRangeOperationService rangeReplay;

  private final BatchDayReplayService dayReplay;

  private final BatchDayReplayDispatchProperties replayProperties;

  private final OrchestratorConfigCacheService cache;

  private final WorkerRegistryCache workerCache;

  private final JdbcTemplate jdbc;

  private final TriggerLaunchConsumer consumer;

  private final WorkflowRunLaunchService launch;

  private final CompensationService compensation;

  private final WorkflowNodeReplayOperationService workflowNodeReplayOperationService;

  private final ApprovalWorkflowService approvalWorkflowService;

  private final TaskExecutionService execution;

  private final ResultVersionPromoteService resultVersionPromoteService;

  private final JobTaskMapper tasks;

  private final JobInstanceMapper jobs;

  private final JobPartitionMapper partitions;

  private final BatchDayReplayTerminalReconciler reconciler;

  private final BatchDayReplayEntryMapper entries;

  private final BatchDayReplaySessionMapper replaySessions;

  private final PlatformTransactionManager manager;

  @Autowired
  ReplayExecutionIdentityIntegrationTest(
      BatchDayReplayDispatcher dispatcher,
      BatchDayReplayRangeOperationService rangeReplay,
      BatchDayReplayService dayReplay,
      BatchDayReplayDispatchProperties replayProperties,
      OrchestratorConfigCacheService cache,
      WorkerRegistryCache workerCache,
      JdbcTemplate jdbc,
      TriggerLaunchConsumer consumer,
      WorkflowRunLaunchService launch,
      CompensationService compensation,
      WorkflowNodeReplayOperationService workflowNodeReplayOperationService,
      ApprovalWorkflowService approvalWorkflowService,
      TaskExecutionService execution,
      ResultVersionPromoteService resultVersionPromoteService,
      JobTaskMapper tasks,
      JobInstanceMapper jobs,
      JobPartitionMapper partitions,
      BatchDayReplayTerminalReconciler reconciler,
      BatchDayReplayEntryMapper entries,
      BatchDayReplaySessionMapper replaySessions,
      PlatformTransactionManager manager) {
    this.dispatcher = dispatcher;
    this.rangeReplay = rangeReplay;
    this.dayReplay = dayReplay;
    this.replayProperties = replayProperties;
    this.cache = cache;
    this.workerCache = workerCache;
    this.jdbc = jdbc;
    this.consumer = consumer;
    this.launch = launch;
    this.compensation = compensation;
    this.workflowNodeReplayOperationService = workflowNodeReplayOperationService;
    this.approvalWorkflowService = approvalWorkflowService;
    this.execution = execution;
    this.resultVersionPromoteService = resultVersionPromoteService;
    this.tasks = tasks;
    this.jobs = jobs;
    this.partitions = partitions;
    this.reconciler = reconciler;
    this.entries = entries;
    this.replaySessions = replaySessions;
    this.manager = manager;
  }

  private record Accepted(
      Source source, long session, long entry, String command, String requestId, String envelope) {}

  private record Source(String tenant, String code, long job, String worker) {}

  private record Replay(
      Source source, long session, long entry, long job, String command, JobTaskEntity task) {}

  @Test
  @DisplayName("只恢复没有 TriggerRequest 和子 JobInstance 的提交失败 entry")
  void shouldRetryOnlyFailedReplaySubmissionWithoutCreatedExecution() {
    String tenant = tenant();
    Source source = source(tenant);
    long sessionId = session(tenant, 1);
    final String failSessionSql =
        "update batch.batch_day_replay_session set status='PARTIAL_FAILED' where id=?";
    final String insertEntrySql = """
        insert into batch.batch_day_replay_entry
            (session_id,tenant_id,job_code,source_instance_id,status,failure_reason)
        values (?,?,?,?,'FAILED','SUBMIT_FAILED') returning id
        """;
    final String selectEntrySql =
        "select status,rerun_instance_id,failure_reason from batch.batch_day_replay_entry where id=?";
    jdbc.update(failSessionSql, sessionId);
    long entryId = jdbc.queryForObject(
        insertEntrySql, Long.class, sessionId, tenant, source.code(), source.job());

    assertThat(entries.selectByIdForUpdate(tenant, sessionId, entryId)).isNotNull();
    Instant reopenedAt = Instant.now();
    int reopenedCount = replaySessions.reopenPartialFailedForRetry(tenant, sessionId, reopenedAt);
    assertThat(reopenedCount).isEqualTo(1);
    Instant retryQueuedAt = Instant.now();
    int queuedEntryCount = entries.retryFailedUnbound(tenant, sessionId, entryId, retryQueuedAt);
    assertThat(queuedEntryCount).isEqualTo(1);

    Map<String, Object> entryState = jdbc.queryForMap(selectEntrySql, entryId);
    assertThat(entryState)
        .containsEntry("status", "PENDING")
        .containsEntry("rerun_instance_id", null)
        .containsEntry("failure_reason", null);
    Map<String, Object> reopenedSession = sessionRow(sessionId);
    assertThat(reopenedSession).containsEntry("status", "RUNNING");

    String replayRequestId = "replay-" + sessionId + "-" + entryId;
    CompensationSubmitCommand replayCommand = CompensationSubmitCommand.builder()
        .tenantId(tenant)
        .compensationType("JOB")
        .targetId(source.job())
        .jobCode(source.code())
        .bizDate(DAY)
        .operatorId("r25-retry-guard")
        .reason("Create accepted request before retry guard assertion")
        .replaySessionId(sessionId)
        .replayEntryId(entryId)
        .resultPolicy("CREATE_NEW_VERSION")
        .configVersionPolicy("USE_ORIGINAL_CONFIG")
        .build();
    compensation.submit(replayCommand);
    final String markSessionPartialFailedSql = """
        update batch.batch_day_replay_session set status='PARTIAL_FAILED' where id=?
        """;
    final String markEntryFailedSql = """
        update batch.batch_day_replay_entry set status='FAILED',failure_reason='SUBMIT_FAILED'
        where id=? and rerun_instance_id is null
        """;
    jdbc.update(markSessionPartialFailedSql, sessionId);
    jdbc.update(markEntryFailedSql, entryId);
    final String countReplayRequestSql = """
        select count(*) from batch.trigger_request where tenant_id=? and request_id=?
        """;
    Integer replayRequestCount =
        jdbc.queryForObject(countReplayRequestSql, Integer.class, tenant, replayRequestId);
    assertThat(replayRequestCount).isEqualTo(1);
    Instant blockedRetryAt = Instant.now();
    int blockedRetryCount = entries.retryFailedUnbound(tenant, sessionId, entryId, blockedRetryAt);
    assertThat(blockedRetryCount).isZero();
  }

  @Test
  @DisplayName("日期区间重放中单日提交失败可并发安全重试并汇总完成")
  void shouldRecoverRangeReplay_whenOneDaySubmissionFailsAndRetryCompetes() throws Exception {
    final String selectCompensationStatusSql = """
        select command_status from batch.compensation_command
        where tenant_id=? and compensation_type='JOB' and target_id=?
        order by id
        """;
    final String disableSourceWorkflowSql = """
        update batch.workflow_definition set enabled=false
        where tenant_id=? and single_job_definition_id=(
            select job_definition_id from batch.job_instance where tenant_id=? and id=?
        )
        """;
    final String enableSourceWorkflowSql = """
        update batch.workflow_definition set enabled=true
        where tenant_id=? and single_job_definition_id=(
            select job_definition_id from batch.job_instance where tenant_id=? and id=?
        )
        """;
    final String selectReplayRequestCountSql = """
        select count(*)::int from batch.trigger_request where tenant_id=? and request_id=?
        """;

    String tenantId = tenant();
    LocalDate failedBizDate = DAY;
    LocalDate runningBizDate = DAY.plusDays(1);
    Source failedSource = source(tenantId, failedBizDate);
    Source runningSource = source(tenantId, runningBizDate);

    BatchDayReplayRangeCommand previewCommand =
        rangeCommand(tenantId, failedBizDate, runningBizDate, null, null);
    ReplayOperationPreviewResponse preview = rangeReplay.preview(previewCommand);
    assertThat(preview.days())
        .extracting(ReplayOperationPreviewResponse.DayPreview::candidateCount)
        .containsExactly(1, 1);

    BatchDayReplayRangeCommand submitCommand = rangeCommand(
        tenantId, failedBizDate, runningBizDate, preview.operationKey(), preview.previewToken());
    ReplayOperationEntity operation = rangeReplay.submit(submitCommand);
    List<BatchDayReplaySessionEntity> sessions = rangeReplay.sessions(tenantId, operation.id());
    assertThat(sessions)
        .extracting(BatchDayReplaySessionEntity::bizDate)
        .containsExactly(failedBizDate, runningBizDate);
    BatchDayReplaySessionEntity failedSession = sessions.getFirst();
    BatchDayReplaySessionEntity runningSession = sessions.getLast();
    List<BatchDayReplayEntryEntity> failedEntries =
        entries.selectBySessionAndStatus(failedSession.id(), tenantId, "PENDING", 10);
    List<BatchDayReplayEntryEntity> runningEntries =
        entries.selectBySessionAndStatus(runningSession.id(), tenantId, "PENDING", 10);
    assertThat(failedEntries).hasSize(1);
    assertThat(runningEntries).hasSize(1);
    BatchDayReplayEntryEntity failedEntry = failedEntries.getFirst();
    BatchDayReplayEntryEntity runningEntry = runningEntries.getFirst();

    int disabledWorkflows =
        jdbc.update(disableSourceWorkflowSql, tenantId, tenantId, failedSource.job());
    assertThat(disabledWorkflows).isEqualTo(1);

    boolean wasEnabled = replayProperties.isEnabled();
    BatchDayReplayDispatcher target = AopTestUtils.getUltimateTargetObject(dispatcher);
    try {
      replayProperties.setEnabled(true);
      target.scheduledDispatch();
      target.scheduledDispatch();
    } finally {
      replayProperties.setEnabled(wasEnabled);
    }

    List<BatchDayReplayEntryEntity> failedAfterDispatch =
        entries.selectBySessionAndStatus(failedSession.id(), tenantId, null, 10);
    List<BatchDayReplayEntryEntity> runningAfterDispatch =
        entries.selectBySessionAndStatus(runningSession.id(), tenantId, null, 10);
    assertThat(failedAfterDispatch.getFirst().status()).isEqualTo("FAILED");
    assertThat(failedAfterDispatch.getFirst().rerunInstanceId()).isNull();
    assertThat(runningAfterDispatch.getFirst().status()).isEqualTo("RUNNING");
    assertThat(runningAfterDispatch.getFirst().rerunInstanceId()).isNull();
    assertThat(jdbc.queryForList(
            selectCompensationStatusSql, String.class, tenantId, failedSource.job()))
        .containsExactly("FAILED");
    assertThat(jdbc.queryForList(
            selectCompensationStatusSql, String.class, tenantId, runningSource.job()))
        .containsExactly("SUCCESS");
    ReplayOperationEntity runningOperation = rangeReplay.get(tenantId, operation.id());
    assertThat(runningOperation.status()).isEqualTo("RUNNING");
    assertThat(runningOperation.totalCount()).isEqualTo(2);
    assertThat(runningOperation.failedCount()).isEqualTo(1);
    assertThat(runningOperation.inFlightCount()).isEqualTo(1);

    Replay runningReplay =
        consume(dispatched(runningSource, runningSession.id(), runningEntry.id()));
    boolean wasEnabledAfterLaunch = replayProperties.isEnabled();
    try {
      replayProperties.setEnabled(true);
      target.scheduledDispatch();
    } finally {
      replayProperties.setEnabled(wasEnabledAfterLaunch);
    }
    report(runningReplay);
    ReplayOperationEntity partiallyFailedOperation = rangeReplay.get(tenantId, operation.id());
    assertThat(partiallyFailedOperation.status()).isEqualTo("PARTIAL_FAILED");
    assertThat(partiallyFailedOperation.inFlightCount()).isZero();

    String failedRequestId = "replay-" + failedSession.id() + "-" + failedEntry.id();
    Integer failedRequestCount =
        jdbc.queryForObject(selectReplayRequestCountSql, Integer.class, tenantId, failedRequestId);
    assertThat(failedRequestCount).isZero();

    int enabledWorkflows =
        jdbc.update(enableSourceWorkflowSql, tenantId, tenantId, failedSource.job());
    assertThat(enabledWorkflows).isEqualTo(1);
    CountDownLatch retriesReady = new CountDownLatch(2);
    CountDownLatch beginRetries = new CountDownLatch(1);
    var retryExecutor = Executors.newFixedThreadPool(2);
    try {
      Future<String> firstRetry = retryExecutor.submit(() -> retryRangeEntry(
          tenantId, failedSession.id(), failedEntry.id(), retriesReady, beginRetries));
      Future<String> competingRetry = retryExecutor.submit(() -> retryRangeEntry(
          tenantId, failedSession.id(), failedEntry.id(), retriesReady, beginRetries));
      assertThat(retriesReady.await(10, TimeUnit.SECONDS)).isTrue();
      beginRetries.countDown();
      List<String> retryResults =
          List.of(firstRetry.get(10, TimeUnit.SECONDS), competingRetry.get(10, TimeUnit.SECONDS));
      assertThat(retryResults).containsExactlyInAnyOrder("RETRIED", "STATE_CONFLICT");
    } finally {
      beginRetries.countDown();
      retryExecutor.shutdownNow();
    }

    BatchDayReplaySessionEntity reopenedSession =
        replaySessions.selectById(tenantId, failedSession.id());
    assertThat(reopenedSession.status()).isEqualTo("RUNNING");
    ReplayOperationEntity reopenedOperation = rangeReplay.get(tenantId, operation.id());
    assertThat(reopenedOperation.status()).isEqualTo("RUNNING");
    assertThat(reopenedOperation.failedCount()).isZero();
    assertThat(reopenedOperation.inFlightCount()).isEqualTo(1);
    assertThat(reopenedOperation.completedAt()).isNull();

    boolean dispatchWasEnabled = replayProperties.isEnabled();
    try {
      replayProperties.setEnabled(true);
      target.scheduledDispatch();
    } finally {
      replayProperties.setEnabled(dispatchWasEnabled);
    }

    Integer retriedRequestCount =
        jdbc.queryForObject(selectReplayRequestCountSql, Integer.class, tenantId, failedRequestId);
    assertThat(retriedRequestCount).isEqualTo(1);
    Replay failedReplay = consume(dispatched(failedSource, failedSession.id(), failedEntry.id()));
    boolean wasEnabledAfterRetryLaunch = replayProperties.isEnabled();
    try {
      replayProperties.setEnabled(true);
      target.scheduledDispatch();
    } finally {
      replayProperties.setEnabled(wasEnabledAfterRetryLaunch);
    }
    report(failedReplay);

    ReplayOperationEntity completedOperation = rangeReplay.get(tenantId, operation.id());
    assertThat(completedOperation.status()).isEqualTo("SUCCEEDED");
    assertThat(completedOperation.totalCount()).isEqualTo(2);
    assertThat(completedOperation.succeededCount()).isEqualTo(2);
    assertThat(completedOperation.failedCount()).isZero();
    assertThat(completedOperation.inFlightCount()).isZero();
    assertThat(completedOperation.completedAt()).isNotNull();
  }

  @Test
  @DisplayName("日期区间多个失败业务日并发恢复后父操作计数保持一致")
  void shouldRecoverMultipleFailedRangeDays_whenRetriesRunConcurrently() throws Exception {
    final String updateSourceWorkflowEnabledSql = """
        update batch.workflow_definition set enabled=?
        where tenant_id=? and single_job_definition_id=(
            select job_definition_id from batch.job_instance where tenant_id=? and id=?
        )
        """;
    final String selectReplayRequestCountSql = """
        select count(*)::int from batch.trigger_request where tenant_id=? and request_id=?
        """;

    String tenantId = tenant();
    List<LocalDate> bizDates = List.of(DAY, DAY.plusDays(1), DAY.plusDays(2));
    Source firstFailedSource = source(tenantId, bizDates.getFirst());
    Source secondFailedSource = source(tenantId, bizDates.get(1));
    Source successfulSource = source(tenantId, bizDates.getLast());

    ReplayOperationPreviewResponse preview = rangeReplay.preview(
        rangeCommand(tenantId, bizDates.getFirst(), bizDates.getLast(), null, null));
    assertThat(preview.days())
        .extracting(ReplayOperationPreviewResponse.DayPreview::candidateCount)
        .containsExactly(1, 1, 1);

    ReplayOperationEntity operation = rangeReplay.submit(rangeCommand(
        tenantId,
        bizDates.getFirst(),
        bizDates.getLast(),
        preview.operationKey(),
        preview.previewToken()));
    List<BatchDayReplaySessionEntity> sessions = rangeReplay.sessions(tenantId, operation.id());
    assertThat(sessions)
        .extracting(BatchDayReplaySessionEntity::bizDate)
        .containsExactlyElementsOf(bizDates);
    BatchDayReplaySessionEntity firstFailedSession = sessions.getFirst();
    BatchDayReplaySessionEntity secondFailedSession = sessions.get(1);
    BatchDayReplaySessionEntity successfulSession = sessions.getLast();
    BatchDayReplayEntryEntity firstFailedEntry = entries
        .selectBySessionAndStatus(firstFailedSession.id(), tenantId, "PENDING", 10)
        .getFirst();
    BatchDayReplayEntryEntity secondFailedEntry = entries
        .selectBySessionAndStatus(secondFailedSession.id(), tenantId, "PENDING", 10)
        .getFirst();
    BatchDayReplayEntryEntity successfulEntry = entries
        .selectBySessionAndStatus(successfulSession.id(), tenantId, "PENDING", 10)
        .getFirst();

    int affectedSourceWorkflowEnabledRows = jdbc.update(
        updateSourceWorkflowEnabledSql, false, tenantId, tenantId, firstFailedSource.job());
    assertThat(affectedSourceWorkflowEnabledRows).isEqualTo(1);
    int affectedSourceWorkflowEnabledRowsWrite2 = jdbc.update(
        updateSourceWorkflowEnabledSql, false, tenantId, tenantId, secondFailedSource.job());
    assertThat(affectedSourceWorkflowEnabledRowsWrite2).isEqualTo(1);

    boolean wasEnabled = replayProperties.isEnabled();
    BatchDayReplayDispatcher target = AopTestUtils.getUltimateTargetObject(dispatcher);
    try {
      replayProperties.setEnabled(true);
      target.scheduledDispatch();
      target.scheduledDispatch();
      target.scheduledDispatch();
    } finally {
      replayProperties.setEnabled(wasEnabled);
    }

    assertThat(entries
            .selectBySessionAndStatus(firstFailedSession.id(), tenantId, null, 10)
            .getFirst()
            .status())
        .isEqualTo("FAILED");
    assertThat(entries
            .selectBySessionAndStatus(secondFailedSession.id(), tenantId, null, 10)
            .getFirst()
            .status())
        .isEqualTo("FAILED");
    assertThat(entries
            .selectBySessionAndStatus(successfulSession.id(), tenantId, null, 10)
            .getFirst()
            .status())
        .isEqualTo("RUNNING");
    ReplayOperationEntity operationWithTwoSubmissionFailures =
        rangeReplay.get(tenantId, operation.id());
    assertThat(operationWithTwoSubmissionFailures.status()).isEqualTo("RUNNING");
    assertThat(operationWithTwoSubmissionFailures.totalCount()).isEqualTo(3);
    assertThat(operationWithTwoSubmissionFailures.failedCount()).isEqualTo(2);
    assertThat(operationWithTwoSubmissionFailures.inFlightCount()).isEqualTo(1);

    Replay successfulReplay =
        consume(dispatched(successfulSource, successfulSession.id(), successfulEntry.id()));
    target.scheduledDispatch();
    report(successfulReplay);

    ReplayOperationEntity partiallyFailedOperation = rangeReplay.get(tenantId, operation.id());
    assertThat(partiallyFailedOperation.status()).isEqualTo("PARTIAL_FAILED");
    assertThat(partiallyFailedOperation.succeededCount()).isEqualTo(1);
    assertThat(partiallyFailedOperation.failedCount()).isEqualTo(2);
    assertThat(partiallyFailedOperation.inFlightCount()).isZero();

    String firstFailedRequestId = "replay-" + firstFailedSession.id() + "-" + firstFailedEntry.id();
    String secondFailedRequestId =
        "replay-" + secondFailedSession.id() + "-" + secondFailedEntry.id();
    Integer selectedReplayRequestCount = jdbc.queryForObject(
        selectReplayRequestCountSql, Integer.class, tenantId, firstFailedRequestId);
    assertThat(selectedReplayRequestCount).isZero();
    Integer selectedReplayRequestCountRead2 = jdbc.queryForObject(
        selectReplayRequestCountSql, Integer.class, tenantId, secondFailedRequestId);
    assertThat(selectedReplayRequestCountRead2).isZero();

    int affectedSourceWorkflowEnabledRowsWrite3 = jdbc.update(
        updateSourceWorkflowEnabledSql, true, tenantId, tenantId, firstFailedSource.job());
    assertThat(affectedSourceWorkflowEnabledRowsWrite3).isEqualTo(1);
    int affectedSourceWorkflowEnabledRowsWrite4 = jdbc.update(
        updateSourceWorkflowEnabledSql, true, tenantId, tenantId, secondFailedSource.job());
    assertThat(affectedSourceWorkflowEnabledRowsWrite4).isEqualTo(1);
    CountDownLatch retriesReady = new CountDownLatch(2);
    CountDownLatch beginRetries = new CountDownLatch(1);
    var retryExecutor = Executors.newFixedThreadPool(2);
    try {
      Future<String> firstRetry = retryExecutor.submit(() -> retryRangeEntry(
          tenantId, firstFailedSession.id(), firstFailedEntry.id(), retriesReady, beginRetries));
      Future<String> secondRetry = retryExecutor.submit(() -> retryRangeEntry(
          tenantId, secondFailedSession.id(), secondFailedEntry.id(), retriesReady, beginRetries));
      assertThat(retriesReady.await(10, TimeUnit.SECONDS)).isTrue();
      beginRetries.countDown();
      assertThat(
              List.of(firstRetry.get(10, TimeUnit.SECONDS), secondRetry.get(10, TimeUnit.SECONDS)))
          .containsExactly("RETRIED", "RETRIED");
    } finally {
      beginRetries.countDown();
      retryExecutor.shutdownNow();
    }

    ReplayOperationEntity operationWithBothRetriesQueued =
        rangeReplay.get(tenantId, operation.id());
    assertThat(operationWithBothRetriesQueued.status()).isEqualTo("RUNNING");
    assertThat(operationWithBothRetriesQueued.totalCount()).isEqualTo(3);
    assertThat(operationWithBothRetriesQueued.succeededCount()).isEqualTo(1);
    assertThat(operationWithBothRetriesQueued.failedCount()).isZero();
    assertThat(operationWithBothRetriesQueued.inFlightCount()).isEqualTo(2);
    assertThat(operationWithBothRetriesQueued.completedAt()).isNull();

    boolean dispatchWasEnabled = replayProperties.isEnabled();
    try {
      replayProperties.setEnabled(true);
      target.scheduledDispatch();
      target.scheduledDispatch();
    } finally {
      replayProperties.setEnabled(dispatchWasEnabled);
    }
    Integer selectedReplayRequestCountRead3 = jdbc.queryForObject(
        selectReplayRequestCountSql, Integer.class, tenantId, firstFailedRequestId);
    assertThat(selectedReplayRequestCountRead3).isEqualTo(1);
    Integer selectedReplayRequestCountRead4 = jdbc.queryForObject(
        selectReplayRequestCountSql, Integer.class, tenantId, secondFailedRequestId);
    assertThat(selectedReplayRequestCountRead4).isEqualTo(1);

    Replay firstRetryReplay =
        consume(dispatched(firstFailedSource, firstFailedSession.id(), firstFailedEntry.id()));
    Replay secondRetryReplay =
        consume(dispatched(secondFailedSource, secondFailedSession.id(), secondFailedEntry.id()));
    target.scheduledDispatch();
    target.scheduledDispatch();
    report(firstRetryReplay);
    report(secondRetryReplay);

    ReplayOperationEntity completedOperation = rangeReplay.get(tenantId, operation.id());
    assertThat(completedOperation.status()).isEqualTo("SUCCEEDED");
    assertThat(completedOperation.totalCount()).isEqualTo(3);
    assertThat(completedOperation.succeededCount()).isEqualTo(3);
    assertThat(completedOperation.failedCount()).isZero();
    assertThat(completedOperation.inFlightCount()).isZero();
    assertThat(completedOperation.completedAt()).isNotNull();
  }

  @Test
  @DisplayName("31 日重放在真实数据库按调度批次推进,业务日和来源不漂移且父子计数完整收敛")
  void shouldCompleteMaximumDateRange_whenSessionsAreDispatchedInBoundedBatches() {
    final String selectReplayIdentitySql = """
        select job.biz_date, source.id as source_instance_id,
               request.request_id, run.run_status
        from batch.job_instance job
        join batch.job_instance source on source.tenant_id=job.tenant_id
            and source.id=job.parent_instance_id
        join batch.trigger_request request on request.tenant_id=job.tenant_id
            and request.id=job.trigger_request_id
        join batch.workflow_run run on run.tenant_id=job.tenant_id
            and run.id=request.parent_workflow_run_id
        where job.tenant_id=? and job.id=?
        """;
    String tenantId = tenant();
    int dayCount = 31;
    int sessionBudget = replayProperties.getSessionBatchSize();
    assertThat(sessionBudget).isPositive();
    Map<LocalDate, Source> sources = new LinkedHashMap<>();
    List<LocalDate> expectedDates = DAY.datesUntil(DAY.plusDays(dayCount)).toList();
    for (LocalDate bizDate : expectedDates) {
      sources.put(bizDate, source(tenantId, bizDate));
    }
    BatchDayReplayRangeCommand previewCommand =
        rangeCommand(tenantId, expectedDates.getFirst(), expectedDates.getLast(), null, null);
    ReplayOperationPreviewResponse preview = rangeReplay.preview(previewCommand);
    assertThat(preview.days()).hasSize(dayCount);
    assertThat(preview.days())
        .extracting(ReplayOperationPreviewResponse.DayPreview::bizDate)
        .containsExactlyElementsOf(expectedDates);
    assertThat(preview.days())
        .allSatisfy(day -> assertThat(day.candidateCount()).isEqualTo(1));
    BatchDayReplayRangeCommand submitCommand = rangeCommand(
        tenantId,
        expectedDates.getFirst(),
        expectedDates.getLast(),
        preview.operationKey(),
        preview.previewToken());
    ReplayOperationEntity operation = rangeReplay.submit(submitCommand);
    ReplayOperationEntity repeatedOperation = rangeReplay.submit(submitCommand);
    assertThat(repeatedOperation.id()).isEqualTo(operation.id());
    List<BatchDayReplaySessionEntity> sessions = rangeReplay.sessions(tenantId, operation.id());
    assertThat(sessions)
        .extracting(BatchDayReplaySessionEntity::bizDate)
        .containsExactlyElementsOf(expectedDates);
    Set<Long> completedSessions = new HashSet<>();
    List<Long> replayJobIds = new ArrayList<>();
    List<Integer> batchSizes = new ArrayList<>();
    BatchDayReplayDispatcher target = AopTestUtils.getUltimateTargetObject(dispatcher);
    boolean wasEnabled = replayProperties.isEnabled();
    try {
      for (int round = 0; completedSessions.size() < dayCount && round < dayCount; round++) {
        try {
          replayProperties.setEnabled(true);
          target.scheduledDispatch();
        } finally {
          replayProperties.setEnabled(wasEnabled);
        }
        int completedBeforeRound = completedSessions.size();
        for (BatchDayReplaySessionEntity session : sessions) {
          if (completedSessions.contains(session.id())) {
            continue;
          }
          List<BatchDayReplayEntryEntity> sessionEntries =
              entries.selectBySessionAndStatus(session.id(), tenantId, null, 10);
          assertThat(sessionEntries).hasSize(1);
          BatchDayReplayEntryEntity entry = sessionEntries.getFirst();
          if ("PENDING".equals(entry.status())) {
            continue;
          }
          assertThat(entry.status()).isEqualTo("RUNNING");
          Source source = sources.get(session.bizDate());
          Accepted accepted = dispatched(source, session.id(), entry.id());
          Replay replay = consume(accepted);
          report(replay);
          Map<String, Object> identity =
              jdbc.queryForMap(selectReplayIdentitySql, tenantId, replay.job());
          assertThat(identity.get("biz_date").toString())
              .isEqualTo(session.bizDate().toString());
          assertThat(identity.get("source_instance_id")).isEqualTo(source.job());
          assertThat(identity.get("run_status")).isEqualTo("SUCCESS");
          var originalJob = jobs.selectById(tenantId, source.job());
          assertThat(originalJob.getInstanceStatus()).isEqualTo("FAILED");
          String replayEntryStatus = entryStatus(replay);
          assertThat(replayEntryStatus).isEqualTo("SUCCEEDED");
          completedSessions.add(session.id());
          replayJobIds.add(replay.job());
        }
        int completedInRound = completedSessions.size() - completedBeforeRound;
        assertThat(completedInRound).isBetween(1, sessionBudget);
        batchSizes.add(completedInRound);
      }
    } finally {
      replayProperties.setEnabled(wasEnabled);
    }
    assertThat(completedSessions).hasSize(dayCount);
    assertThat(replayJobIds).hasSize(dayCount).doesNotHaveDuplicates();
    assertThat(batchSizes.getFirst()).isEqualTo(Math.min(dayCount, sessionBudget));
    ReplayOperationEntity completedOperation = rangeReplay.get(tenantId, operation.id());
    assertThat(completedOperation.status()).isEqualTo("SUCCEEDED");
    assertThat(completedOperation.totalCount()).isEqualTo(dayCount);
    assertThat(completedOperation.succeededCount()).isEqualTo(dayCount);
    assertThat(completedOperation.failedCount()).isZero();
    assertThat(completedOperation.inFlightCount()).isZero();
    assertThat(completedOperation.completedAt()).isNotNull();
    List<BatchDayReplaySessionEntity> completedChildren =
        rangeReplay.sessions(tenantId, operation.id());
    assertThat(completedChildren)
        .hasSize(dayCount)
        .allSatisfy(session -> assertThat(session.status()).isEqualTo("SUCCEEDED"));
  }

  @Test
  @DisplayName("精确失败 JOB 节点重放创建新受管 Workflow root 且重复信封不重复执行")
  void shouldReplayExactFailedWorkflowNodeIntoNewRootExactlyOnce() {
    final String selectWorkflowRunIdSql = """
        select workflow_run_id from batch.workflow_node_run where job_instance_id=?
    """;
    final String selectIdSql = """
        select id from batch.workflow_node_run where workflow_run_id=? and job_instance_id=? and node_type='JOB'
    """;
    final String selectRunStatusSql = """
        select run_status from batch.workflow_run where tenant_id=? and id=?
    """;
    final String selectStatementSql = """
        select * from batch.compensation_command where tenant_id=? and command_no=?
    """;
    final String selectPayloadJsonSql = """
        select payload_json::text from batch.outbox_event where tenant_id=? and event_type='WORKFLOW_LAUNCH'\s\
        and event_key=?
    """;
    final String selectIdSql2 = """
        select id from batch.workflow_run where tenant_id=? and trigger_request_id=(select id\s\
        from batch.trigger_request where tenant_id=? and request_id=?)
    """;
    final String selectCountSql = """
        select count(*) from batch.workflow_run where tenant_id=? and trigger_request_id=(select id\s\
        from batch.trigger_request where tenant_id=? and request_id=?)
    """;
    final String selectReplayOperationSql = """
        select operation_type, status, source_workflow_run_id, source_workflow_node_run_id,
            source_job_instance_id, target_request_id, target_workflow_run_id, total_count,
            succeeded_count, in_flight_count, requested_by, approved_by
        from batch.replay_operation
        where tenant_id=? and operation_type='WORKFLOW_NODE'
            and source_workflow_run_id=? and source_workflow_node_run_id=?
    """;

    Source source = source(tenant());
    Long sourceRunId = jdbc.queryForObject(selectWorkflowRunIdSql, Long.class, source.job());
    Long sourceNodeRunId = jdbc.queryForObject(selectIdSql, Long.class, sourceRunId, source.job());
    String selectedRunStatus =
        jdbc.queryForObject(selectRunStatusSql, String.class, source.tenant(), sourceRunId);
    assertThat(selectedRunStatus).isEqualTo("FAILED");

    WorkflowNodeReplayCommand previewRequest = WorkflowNodeReplayCommand.builder()
        .tenantId(source.tenant())
        .workflowRunId(sourceRunId)
        .workflowNodeRunId(sourceNodeRunId)
        .reason("Replay the exact failed JOB node")
        .requestedBy("r25-node-replay")
        .resultPolicy("CREATE_NEW_VERSION")
        .configVersionPolicy("USE_ORIGINAL_CONFIG")
        .configVersion(null)
        .traceId("r25-source-trace")
        .operationKey(null)
        .previewToken(null)
        .build();
    var replayPreview = workflowNodeReplayOperationService.preview(previewRequest);
    var pendingOperation =
        workflowNodeReplayOperationService.submitForApproval(WorkflowNodeReplayCommand.builder()
            .tenantId(source.tenant())
            .workflowRunId(sourceRunId)
            .workflowNodeRunId(sourceNodeRunId)
            .reason("Replay the exact failed JOB node")
            .requestedBy("r25-node-replay")
            .resultPolicy(replayPreview.resultPolicy())
            .configVersionPolicy(replayPreview.configVersionPolicy())
            .configVersion(replayPreview.configVersion())
            .traceId("r25-source-trace")
            .operationKey(replayPreview.operationKey())
            .previewToken(replayPreview.previewToken())
            .build());

    Map<String, Object> approvalPayload = new LinkedHashMap<>();
    approvalPayload.put("tenantId", source.tenant());
    approvalPayload.put("compensationType", "WORKFLOW_NODE");
    approvalPayload.put("targetId", sourceNodeRunId);
    approvalPayload.put("sourceWorkflowRunId", sourceRunId);
    approvalPayload.put("sourceWorkflowNodeRunId", sourceNodeRunId);
    approvalPayload.put("jobCode", source.code());
    approvalPayload.put("bizDate", DAY.toString());
    approvalPayload.put("reason", "Replay the exact failed JOB node");
    approvalPayload.put("replayOperationId", pendingOperation.id());
    approvalPayload.put("operationKey", replayPreview.operationKey());
    approvalPayload.put("resultPolicy", replayPreview.resultPolicy());
    approvalPayload.put("configVersionPolicy", replayPreview.configVersionPolicy());
    approvalPayload.put("previewToken", replayPreview.previewToken());
    String approvalPayloadJson = JsonUtils.toJson(approvalPayload);
    String approvalNo =
        approvalWorkflowService.submit(ApprovalWorkflowService.ApprovalSubmitCommand.of(
            source.tenant(),
            new ApprovalWorkflowService.ApprovalTarget(
                "COMPENSATION",
                "COMPENSATION",
                "WORKFLOW_NODE",
                String.valueOf(sourceNodeRunId),
                approvalPayloadJson),
            new ApprovalWorkflowService.ApprovalSource(
                "r25-node-replay", "r25-source-trace", "r25-source-idem", "Replay exact node")));
    approvalWorkflowService.approve(
        source.tenant(), approvalNo, "r25-approver", "Approved exact failed node replay");

    String commandNo = compensation.submit(CompensationSubmitCommand.builder()
        .tenantId(source.tenant())
        .compensationType("WORKFLOW_NODE")
        .targetId(sourceNodeRunId)
        .sourceWorkflowRunId(sourceRunId)
        .sourceWorkflowNodeRunId(sourceNodeRunId)
        .replayOperationId(pendingOperation.id())
        .jobCode(source.code())
        .bizDate(DAY)
        .resultPolicy(replayPreview.resultPolicy())
        .configVersionPolicy(replayPreview.configVersionPolicy())
        .operatorId("r25-node-replay")
        .approvalId(approvalNo)
        .reason("Replay the exact failed JOB node")
        .build());

    var command = jdbc.queryForMap(selectStatementSql, source.tenant(), commandNo);
    assertThat(command)
        .containsEntry("compensation_type", "WORKFLOW_NODE")
        .containsEntry("target_id", sourceNodeRunId)
        .containsEntry("source_workflow_run_id", sourceRunId)
        .containsEntry("command_status", "SUCCESS");
    Map<?, ?> result = JsonUtils.fromJson(command.get("result_summary").toString(), Map.class);
    String requestId = (String) result.get("launchRequestId");
    assertThat(((Number) result.get("sourceWorkflowRunId")).longValue()).isEqualTo(sourceRunId);
    assertThat(((Number) result.get("sourceWorkflowNodeRunId")).longValue())
        .isEqualTo(sourceNodeRunId);
    assertThat(((Number) result.get("sourceJobInstanceId")).longValue()).isEqualTo(source.job());

    String envelope = jdbc.queryForObject(
        selectPayloadJsonSql,
        String.class,
        source.tenant(),
        source.tenant() + ":workflow-launch:" + requestId);
    var record = new ConsumerRecord<>(BatchTopics.TRIGGER_LAUNCH_V1, 0, 0L, requestId, envelope);
    consumer.consume(record, () -> {});
    Long replayJobId = jobs.selectCurrentByWorkflowRequest(
            source.tenant(), requestId, source.code())
        .getId();
    assertThat(replayJobId).isNotEqualTo(source.job());
    var replayJob = jobs.selectById(source.tenant(), replayJobId);
    assertThat(replayJob.getParentInstanceId()).isEqualTo(source.job());
    assertThat(replayJob.getBizDate()).isEqualTo(DAY);
    Map<?, ?> replayParams = JsonUtils.fromJson(replayJob.getParamsSnapshot(), Map.class);
    Map<?, ?> requestParams = (Map<?, ?>) replayParams.get("requestParams");
    assertThat(((Number) requestParams.get("sourceWorkflowRunId")).longValue())
        .isEqualTo(sourceRunId);
    assertThat(((Number) requestParams.get("sourceWorkflowNodeRunId")).longValue())
        .isEqualTo(sourceNodeRunId);
    assertThat(((Number) requestParams.get("sourceJobInstanceId")).longValue())
        .isEqualTo(source.job());
    Long replayRunId =
        jdbc.queryForObject(selectIdSql2, Long.class, source.tenant(), source.tenant(), requestId);
    assertThat(replayRunId).isNotEqualTo(sourceRunId);
    Map<String, Object> replayOperation =
        jdbc.queryForMap(selectReplayOperationSql, source.tenant(), sourceRunId, sourceNodeRunId);
    assertThat(((Number) replayOperation.get("target_workflow_run_id")).longValue())
        .isEqualTo(replayRunId);
    assertThat(replayOperation)
        .containsEntry("operation_type", "WORKFLOW_NODE")
        .containsEntry("status", "RUNNING")
        .containsEntry("source_workflow_run_id", sourceRunId)
        .containsEntry("source_workflow_node_run_id", sourceNodeRunId)
        .containsEntry("source_job_instance_id", source.job())
        .containsEntry("requested_by", "r25-node-replay")
        .containsEntry("approved_by", "r25-approver")
        .containsEntry("target_request_id", requestId)
        .containsEntry("total_count", 1)
        .containsEntry("succeeded_count", 0)
        .containsEntry("in_flight_count", 1);

    consumer.consume(record, () -> {});
    assertThat(jobs.selectCurrentByWorkflowRequest(source.tenant(), requestId, source.code())
            .getId())
        .isEqualTo(replayJobId);
    Integer selectedWorkflowRunCount = jdbc.queryForObject(
        selectCountSql, Integer.class, source.tenant(), source.tenant(), requestId);
    assertThat(selectedWorkflowRunCount).isEqualTo(1);
    String selectedRunStatusRead2 =
        jdbc.queryForObject(selectRunStatusSql, String.class, source.tenant(), sourceRunId);
    assertThat(selectedRunStatusRead2).isEqualTo("FAILED");
  }

  @Test
  @DisplayName("精确失败 JOB 节点包含未知外部结果时拒绝创建新 Workflow 执行")
  void shouldRejectFailedWorkflowNodeReplay_whenSourceHasUnknownExternalOutcome() {
    final String selectWorkflowRunIdSql = """
        select workflow_run_id from batch.workflow_node_run where job_instance_id=?
    """;
    final String selectIdSql = """
        select id from batch.workflow_node_run where workflow_run_id=? and job_instance_id=? and node_type='JOB'
    """;
    final String updateJobTaskSql = """
        update batch.job_task set error_code='EXTERNAL_OUTCOME_UNKNOWN' where tenant_id=? and job_instance_id=?\
        """;
    final String selectCountSql = """
        select count(*) from batch.trigger_request where tenant_id=?
    """;
    final String selectCountSql2 = """
        select count(*) from batch.outbox_event where tenant_id=? and event_type='WORKFLOW_LAUNCH'
    """;

    Source source = source(tenant());
    Long sourceRunId = jdbc.queryForObject(selectWorkflowRunIdSql, Long.class, source.job());
    Long sourceNodeRunId = jdbc.queryForObject(selectIdSql, Long.class, sourceRunId, source.job());
    jdbc.update(updateJobTaskSql, source.tenant(), source.job());
    long requestsBefore = jdbc.queryForObject(selectCountSql, Long.class, source.tenant());
    long launchesBefore = jdbc.queryForObject(selectCountSql2, Long.class, source.tenant());

    assertThatThrownBy(
            () -> workflowNodeReplayOperationService.preview(WorkflowNodeReplayCommand.builder()
                .tenantId(source.tenant())
                .workflowRunId(sourceRunId)
                .workflowNodeRunId(sourceNodeRunId)
                .reason("Do not duplicate an unresolved external effect")
                .requestedBy("r25-node-replay-unknown")
                .resultPolicy("CREATE_NEW_VERSION")
                .configVersionPolicy("USE_ORIGINAL_CONFIG")
                .configVersion(null)
                .traceId("r25-unknown-trace")
                .operationKey(null)
                .previewToken(null)
                .build()))
        .hasMessageContaining("error.workflow.replay_external_outcome_unknown");

    Long selectedTriggerRequestCount =
        jdbc.queryForObject(selectCountSql, Long.class, source.tenant());
    assertThat(selectedTriggerRequestCount).isEqualTo(requestsBefore);
    Long selectedOutboxEventCount =
        jdbc.queryForObject(selectCountSql2, Long.class, source.tenant());
    assertThat(selectedOutboxEventCount).isEqualTo(launchesBefore);
    assertThat(jobs.selectById(source.tenant(), source.job()).getInstanceStatus())
        .isEqualTo("FAILED");
  }

  @Test
  @DisplayName("受理不猜执行 Job；消费者启动后 REPORT 只回填实际执行")
  void shouldBindExecutingChild_whenCompensationCreatesWorkflowRoot() {
    final String selectRelatedJobInstanceIdSql = """
        select related_job_instance_id from batch.compensation_command where tenant_id=? and command_no=?
    """;
    final String selectRerunInstanceIdSql = """
        select rerun_instance_id from batch.batch_day_replay_entry where id=?
    """;

    Source source = source(tenant());
    long session = session(source.tenant(), 1);
    Replay replay = replay(source, session);
    assertThat(replay.job()).isNotEqualTo(source.job());
    assertThat(jobs.selectById(source.tenant(), replay.job()).getParentInstanceId())
        .isEqualTo(source.job());
    assertThat(jobs.selectById(source.tenant(), replay.job()).getBizDate()).isEqualTo(DAY);
    Long selectedRelatedJobInstanceId = jdbc.queryForObject(
        selectRelatedJobInstanceIdSql, Long.class, source.tenant(), replay.command());
    assertThat(selectedRelatedJobInstanceId).isNull();
    assertThat(
            entries.selectByCurrentExecution(source.tenant(), session, source.code(), source.job()))
        .isNull();
    assertThat(entries.selectByCurrentExecution(
            "foreign-tenant", session, source.code(), replay.job()))
        .isNull();
    reconciler.reconcileOnTerminal(
        source.tenant(), session, source.code(), source.job(), "SUCCESS");
    assertThat(entryStatus(replay)).isEqualTo("RUNNING");
    Long selectedRerunInstanceId =
        jdbc.queryForObject(selectRerunInstanceIdSql, Long.class, replay.entry());
    assertThat(selectedRerunInstanceId).isNull();
    reconciler.reconcileAdmissions(source.tenant(), session);
    Long selectedRerunInstanceIdRead2 =
        jdbc.queryForObject(selectRerunInstanceIdSql, Long.class, replay.entry());
    assertThat(selectedRerunInstanceIdRead2).isEqualTo(replay.job());
    report(replay);
    assertThat(entryStatus(replay)).isEqualTo("SUCCEEDED");
    Long selectedRerunInstanceIdRead3 =
        jdbc.queryForObject(selectRerunInstanceIdSql, Long.class, replay.entry());
    assertThat(selectedRerunInstanceIdRead3).isEqualTo(replay.job());
    assertThat(sessionRow(session))
        .containsEntry("status", "SUCCEEDED")
        .containsEntry("succeeded_count", 1)
        .containsEntry("in_flight_count", 0);
  }

  @Test
  @DisplayName("REPORT 后发生外层异常时 Job、条目和会话一起回滚，重报才提交成功")
  void shouldRollbackReplayAccounting_whenReportTransactionRollsBack() {
    Source source = source(tenant());
    Replay replay = replay(source, session(source.tenant(), 1));
    assertThatThrownBy(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
          report(replay);
          throw new IllegalStateException("R25_REPORT_ROLLBACK");
        }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("R25_REPORT_ROLLBACK");
    assertThat(jobs.selectById(source.tenant(), replay.job()).getInstanceStatus())
        .isEqualTo("RUNNING");
    assertThat(entryStatus(replay)).isEqualTo("RUNNING");
    assertThat(sessionRow(replay.session()))
        .containsEntry("status", "RUNNING")
        .containsEntry("succeeded_count", 0);
    report(replay);
    assertThat(entryStatus(replay)).isEqualTo("SUCCEEDED");
    assertThat(sessionRow(replay.session()))
        .containsEntry("status", "SUCCEEDED")
        .containsEntry("succeeded_count", 1);
  }

  @Test
  @DisplayName("同会话两条 REPORT 串行核算；首个未提交时第二个等待，最终计数不丢失")
  void shouldSerializeCounts_whenTwoReplayJobsFinishConcurrently() throws Exception {
    final String selectPgBackendPidSql = """
        select pg_backend_pid()
    """;
    final String selectCardinalitySql = """
        select cardinality(pg_blocking_pids(?))
    """;

    String tenant = tenant();
    Source first = source(tenant), second = source(tenant);
    long session = session(tenant, 2);
    Replay a = replay(first, session), b = replay(second, session);
    var held = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var secondPid = new AtomicInteger();
    try (var pool = Executors.newFixedThreadPool(2)) {
      var one = pool.submit(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
        report(a);
        held.countDown();
        try {
          if (!release.await(15, TimeUnit.SECONDS))
            throw new IllegalStateException("report commit gate timed out");
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(interrupted);
        }
      }));
      try {
        assertThat(held.await(15, TimeUnit.SECONDS)).isTrue();
        var two =
            pool.submit(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
              secondPid.set(jdbc.queryForObject(selectPgBackendPidSql, Integer.class));
              report(b);
            }));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
          Integer assertedSecondPid = secondPid.get();
          assertThat(assertedSecondPid).isPositive();
          Integer queriedSecondPid = secondPid.get();
          Integer blockingPidCount =
              jdbc.queryForObject(selectCardinalitySql, Integer.class, queriedSecondPid);
          assertThat(blockingPidCount).isPositive();
        });
        assertThat(sessionRow(session))
            .containsEntry("succeeded_count", 0)
            .containsEntry("in_flight_count", 2);
        release.countDown();
        one.get(15, TimeUnit.SECONDS);
        two.get(15, TimeUnit.SECONDS);
        assertThat(sessionRow(session))
            .containsEntry("status", "SUCCEEDED")
            .containsEntry("succeeded_count", 2)
            .containsEntry("in_flight_count", 0);
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  @DisplayName("受理后配置停用导致拒收时条目明确失败，不永久卡在 RUNNING")
  void shouldCloseRejectedAdmission_whenWorkflowIsDisabledAfterAcceptance() {
    final String updateWorkflowDefinitionSql = """
        update batch.workflow_definition set enabled=false where tenant_id=?\s\
        and single_job_definition_id=(select job_definition_id from batch.job_instance where tenant_id=?\s\
        and id=?)
    """;
    final String selectRequestStatusSql = """
        select request_status from batch.trigger_request where tenant_id=? and request_id=?
    """;
    final String selectStatusSql = """
        select status from batch.batch_day_replay_entry where id=?
    """;

    Source source = source(tenant());
    long session = session(source.tenant(), 1);
    Accepted accepted = accept(source, session);
    jdbc.update(updateWorkflowDefinitionSql, source.tenant(), source.tenant(), source.job());
    consumer.consume(
        new ConsumerRecord<>(
            BatchTopics.TRIGGER_LAUNCH_V1, 0, 0L, accepted.requestId(), accepted.envelope()),
        () -> {});
    String selectedRequestStatus = jdbc.queryForObject(
        selectRequestStatusSql, String.class, source.tenant(), accepted.requestId());
    assertThat(selectedRequestStatus).isEqualTo("REJECTED");
    boolean prior = replayProperties.isEnabled();
    try {
      replayProperties.setEnabled(true);
      BatchDayReplayDispatcher target = AopTestUtils.getUltimateTargetObject(dispatcher);
      target.scheduledDispatch();
    } finally {
      replayProperties.setEnabled(prior);
    }
    String selectedStatus = jdbc.queryForObject(selectStatusSql, String.class, accepted.entry());
    assertThat(selectedStatus).isEqualTo("FAILED");
    assertThat(sessionRow(session))
        .containsEntry("status", "PARTIAL_FAILED")
        .containsEntry("failed_count", 1)
        .containsEntry("in_flight_count", 0);
  }

  @Test
  @DisplayName("原配置策略在受理后修改当前版本时仍执行原版本和原优先级")
  void shouldKeepOriginalVersion_whenCurrentDefinitionChangesBeforeConsumption() {
    final String updateJobDefinitionSql = """
        update batch.job_definition set version=version+1,priority=9 where tenant_id=? and job_code=?
    """;
    final String selectStatusSql = """
        select status,promotion_policy from batch.result_version where tenant_id=? and job_instance_id=?
    """;

    Source source = source(tenant());
    Accepted accepted = accept(source, session(source.tenant(), 1));
    jdbc.update(updateJobDefinitionSql, source.tenant(), source.code());
    cache.evictJobDefinition(source.tenant(), source.code());
    Replay replay = consume(accepted);
    var child = jobs.selectById(source.tenant(), replay.job());
    assertThat(child.getJobDefinitionVersion()).isEqualTo(1);
    assertThat(child.getPriority()).isEqualTo(5);
    assertThat(replayJobPolicy(child.getId())).containsEntry("resultPolicy", "CREATE_NEW_VERSION");
    report(replay);
    var result = jdbc.queryForMap(selectStatusSql, source.tenant(), replay.job());
    assertThat(result)
        .containsEntry("status", "EFFECTIVE")
        .containsEntry("promotion_policy", "AUTO_LATEST");
    assertThat(entryStatus(replay)).isEqualTo("SUCCEEDED");
  }

  @Test
  @DisplayName("指定配置版本按同租户历史 Workflow 快照执行而不读取当前定义")
  void shouldUseSpecifiedDefinitionSnapshot_whenCurrentVersionIsNewer() {
    final String updateBatchDayReplaySessionSql = """
        update batch.batch_day_replay_session set config_version_policy='USE_SPECIFIED_VERSION',config_version=1\s\
        where id=?
    """;
    final String updateJobDefinitionSql = """
        update batch.job_definition set version=2,priority=9 where tenant_id=? and job_code=?
    """;

    Source source = source(tenant());
    long session = session(source.tenant(), 1);
    jdbc.update(updateBatchDayReplaySessionSql, session);
    jdbc.update(updateJobDefinitionSql, source.tenant(), source.code());
    cache.evictJobDefinition(source.tenant(), source.code());
    Replay replay = replay(source, session);
    var child = jobs.selectById(source.tenant(), replay.job());
    assertThat(child.getJobDefinitionVersion()).isEqualTo(1);
    assertThat(child.getPriority()).isEqualTo(5);
    assertThat(child.getParentInstanceId()).isEqualTo(source.job());
    report(replay);
    assertThat(entryStatus(replay)).isEqualTo("SUCCEEDED");
  }

  @Test
  @DisplayName("最新配置策略在 Workflow 启动时选择当前定义并贯穿真实执行")
  void shouldUseLatestDefinitionAtWorkflowStart_whenDefinitionChangesAfterAdmission() {
    final String updateBatchDayReplaySessionSql = """
        update batch.batch_day_replay_session set config_version_policy='USE_LATEST_CONFIG' where id=?
    """;
    final String updateJobDefinitionSql = """
        update batch.job_definition set version=2,priority=9 where tenant_id=? and job_code=?
    """;
    final String updateJobDefinitionSql2 = """
        update batch.job_definition set version=version+1,priority=7 where tenant_id=? and job_code=?
    """;

    Source source = source(tenant());
    long session = session(source.tenant(), 1);
    jdbc.update(updateBatchDayReplaySessionSql, session);
    jdbc.update(updateJobDefinitionSql, source.tenant(), source.code());
    cache.evictJobDefinition(source.tenant(), source.code());
    Accepted accepted = accept(source, session);
    jdbc.update(updateJobDefinitionSql2, source.tenant(), source.code());
    cache.evictJobDefinition(source.tenant(), source.code());
    Replay replay = consume(accepted);
    var child = jobs.selectById(source.tenant(), replay.job());
    assertThat(child.getJobDefinitionVersion()).isEqualTo(3);
    assertThat(child.getPriority()).isEqualTo(7);
    assertThat(child.getParentInstanceId()).isEqualTo(source.job());
    report(replay);
    assertThat(entryStatus(replay)).isEqualTo("SUCCEEDED");
  }

  @Test
  @DisplayName("KEEP_BOTH 重放结果保持待生效且保留明确审批策略")
  void shouldKeepReplayResultPending_whenKeepBothPolicyWasSelected() {
    final String updateBatchDayReplaySessionSql = """
        update batch.batch_day_replay_session set result_policy='KEEP_BOTH' where id=?
    """;
    final String selectStatusSql = """
        select status,promotion_policy from batch.result_version where tenant_id=? and job_instance_id=?
    """;
    final String selectStatusSql2 = """
        select status from batch.result_version where tenant_id=? and job_instance_id=?
    """;
    final String selectCountSql = """
        select count(*) from batch.result_version where tenant_id=? and business_key=? and status='EFFECTIVE'
    """;

    Source failedSource = source(tenant());
    Replay priorReplay = replay(failedSource, session(failedSource.tenant(), 1));
    report(priorReplay);
    Source source = new Source(
        failedSource.tenant(), failedSource.code(), priorReplay.job(), failedSource.worker());
    long session = session(source.tenant(), 1);
    jdbc.update(updateBatchDayReplaySessionSql, session);
    Replay replay = replay(source, session);
    assertThat(replayJobPolicy(replay.job())).containsEntry("resultPolicy", "KEEP_BOTH");
    report(replay);
    var result = jdbc.queryForMap(selectStatusSql, source.tenant(), replay.job());
    assertThat(result)
        .containsEntry("status", "PENDING")
        .containsEntry("promotion_policy", "MANUAL_APPROVAL");
    String selectedStatus =
        jdbc.queryForObject(selectStatusSql2, String.class, source.tenant(), priorReplay.job());
    assertThat(selectedStatus).isEqualTo("EFFECTIVE");
    Integer selectedResultVersionCount = jdbc.queryForObject(
        selectCountSql, Integer.class, source.tenant(), "job:" + source.code() + ":" + DAY);
    assertThat(selectedResultVersionCount).isEqualTo(1);
    assertThat(entryStatus(replay)).isEqualTo("SUCCEEDED");
  }

  @Test
  @DisplayName("MANUAL_CONFIRM_EFFECTIVE 经人工晋升入口后才进入 EFFECTIVE")
  void shouldPromoteManualReplayResultOnlyAfterConfirmation() {
    final String updateBatchDayReplaySessionSql = """
        update batch.batch_day_replay_session set result_policy='MANUAL_CONFIRM_EFFECTIVE' where id=?
    """;
    final String selectIdSql = """
        select id from batch.result_version where tenant_id=? and job_instance_id=?
    """;
    final String selectStatusSql = """
        select status,promotion_policy from batch.result_version where id=?
    """;

    Source source = source(tenant());
    long session = session(source.tenant(), 1);
    jdbc.update(updateBatchDayReplaySessionSql, session);
    Replay replay = replay(source, session);
    report(replay);
    Long versionId = jdbc.queryForObject(selectIdSql, Long.class, source.tenant(), replay.job());
    var pending = jdbc.queryForMap(selectStatusSql, versionId);
    assertThat(pending)
        .containsEntry("status", "PENDING")
        .containsEntry("promotion_policy", "MANUAL_APPROVAL");
    var promoted = resultVersionPromoteService.promote(source.tenant(), versionId);
    assertThat(promoted.status()).isEqualTo("EFFECTIVE");
    assertThat(entryStatus(replay)).isEqualTo("SUCCEEDED");
  }

  @Test
  @DisplayName("派发前定义停用导致提交失败，下一轮应按失败条目收口会话")
  void shouldCloseSession_whenSubmissionFailsBeforeLaunchRequestExists() {
    final String insertBatchDayReplayEntrySql = """
        insert into batch.batch_day_replay_entry (session_id,tenant_id,job_code,source_instance_id,status)
        values (?,?,?,?,'PENDING') returning id
        """;
    final String updateWorkflowDefinitionSql = """
        update batch.workflow_definition set enabled=false where tenant_id=?\s\
        and single_job_definition_id=(select job_definition_id from batch.job_instance where tenant_id=?\s\
        and id=?)
    """;
    final String selectStatusSql = """
        select status from batch.batch_day_replay_entry where id=?
    """;
    final String selectCountSql = """
        select count(*) from batch.trigger_request where tenant_id=? and request_id=?
    """;

    Source source = source(tenant());
    long session = session(source.tenant(), 1);
    long entry = jdbc.queryForObject(
        insertBatchDayReplayEntrySql,
        Long.class,
        session,
        source.tenant(),
        source.code(),
        source.job());
    jdbc.update(updateWorkflowDefinitionSql, source.tenant(), source.tenant(), source.job());
    boolean prior = replayProperties.isEnabled();
    try {
      replayProperties.setEnabled(true);
      BatchDayReplayDispatcher target = AopTestUtils.getUltimateTargetObject(dispatcher);
      target.scheduledDispatch();
      String selectedStatus = jdbc.queryForObject(selectStatusSql, String.class, entry);
      assertThat(selectedStatus).isEqualTo("FAILED");
      Integer selectedTriggerRequestCount = jdbc.queryForObject(
          selectCountSql, Integer.class, source.tenant(), "replay-" + session + "-" + entry);
      assertThat(selectedTriggerRequestCount).isZero();
      target.scheduledDispatch();
    } finally {
      replayProperties.setEnabled(prior);
    }
    assertThat(sessionRow(session))
        .containsEntry("status", "PARTIAL_FAILED")
        .containsEntry("failed_count", 1)
        .containsEntry("in_flight_count", 0);
  }

  @Test
  @DisplayName("试运行重放只生成演练终态，原正式失败和有效结果不被演练完成")
  void shouldKeepFormalObligationFailed_whenReplayRunsInDryRunMode() {
    final String updateBatchDayReplaySessionSql = """
        update batch.batch_day_replay_session set execution_mode='DRY_RUN',result_policy='DRY_RUN_ONLY'\s\
        where id=?
    """;
    final String updateWorkerRegistrySql = """
        update batch.worker_registry set capability_tags='[\"dry-run-safe\"]'::jsonb where tenant_id=?\s\
        and worker_code=?
    """;
    final String selectCountSql = """
        select count(*) from batch.result_version where tenant_id=? and status='EFFECTIVE'
    """;

    Source source = source(tenant());
    long session = session(source.tenant(), 1);
    jdbc.update(updateBatchDayReplaySessionSql, session);
    jdbc.update(updateWorkerRegistrySql, source.tenant(), source.worker());
    workerCache.evict(source.tenant(), "DEFAULT");
    Replay replay = replay(source, session);
    report(replay);
    assertThat(jobs.selectById(source.tenant(), replay.job()).getInstanceStatus())
        .isEqualTo("SUCCESS_DRY_RUN");
    assertThat(jobs.selectById(source.tenant(), source.job()).getInstanceStatus())
        .isEqualTo("FAILED");
    assertThat(entryStatus(replay)).isEqualTo("SUCCEEDED");
    Integer selectedResultVersionCount =
        jdbc.queryForObject(selectCountSql, Integer.class, source.tenant());
    assertThat(selectedResultVersionCount).isZero();
  }

  @Test
  @DisplayName("历史候选使用已冻结的 Workflow 日历，Job 不配置日历也不能漏掉重放和失败补跑")
  void shouldSelectHistoricalJobsByFrozenWorkflowCalendar_whenJobCalendarIsUnset() {
    final String updateJobDefinitionSql = """
        update batch.job_definition set calendar_code=null where tenant_id=? and job_code=?
    """;

    Source source = source(tenant());
    assertThat(jobs.selectById(source.tenant(), source.job()).getCalendarCode())
        .isEqualTo("JOB_CALENDAR");
    jdbc.update(updateJobDefinitionSql, source.tenant(), source.code());
    assertThat(jobs.selectBatchDayCandidates(JobInstanceBatchDayCandidatesQuery.builder()
            .tenantId(source.tenant())
            .calendarCode("JOB_CALENDAR")
            .bizDate(DAY)
            .statuses(List.of("FAILED"))
            .jobCodes(List.of(source.code()))
            .build()))
        .extracting(j -> j.getId())
        .containsExactly(source.job());
    assertThat(jobs.selectBatchDayCatchUpCandidates(source.tenant(), "JOB_CALENDAR", DAY))
        .extracting(j -> j.getId())
        .containsExactly(source.job());
  }

  @Test
  @DisplayName("历史演练失败不得成为正式批日重放或自动补跑源")
  void shouldExcludeDryRunSourcesFromReplayAndCatchUp() {
    final String setDryRunSql =
        "update batch.job_instance set dry_run=? where tenant_id=? and id=?";
    Source source = source(tenant());
    var query = JobInstanceBatchDayCandidatesQuery.builder()
        .tenantId(source.tenant())
        .calendarCode("JOB_CALENDAR")
        .bizDate(DAY)
        .statuses(List.of("FAILED"))
        .jobCodes(List.of(source.code()))
        .build();
    jdbc.update(setDryRunSql, true, source.tenant(), source.job());

    assertThat(jobs.selectBatchDayCandidates(query)).isEmpty();
    assertThat(jobs.selectBatchDayCatchUpCandidates(source.tenant(), "JOB_CALENDAR", DAY))
        .isEmpty();

    jdbc.update(setDryRunSql, false, source.tenant(), source.job());
    assertThat(jobs.selectBatchDayCandidates(query))
        .extracting(j -> j.getId())
        .containsExactly(source.job());
    assertThat(jobs.selectBatchDayCatchUpCandidates(source.tenant(), "JOB_CALENDAR", DAY))
        .extracting(j -> j.getId())
        .containsExactly(source.job());
  }

  private Source source(String tenant) {
    return source(tenant, DAY);
  }

  private Source source(String tenant, LocalDate bizDate) {
    var seed = LaunchIntegrationFixture.prepareWorkflowLaunchWithWorker(
        jdbc, tenant, "IMPORT", "DEFAULT", TriggerType.API, bizDate);
    var response = launch.launch(LaunchRequest.builder()
        .tenantId(tenant)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .requestId(seed.requestId())
        .bizDate(bizDate)
        .triggerType(TriggerType.API)
        .params(Map.of())
        .build());
    long job =
        LaunchIntegrationFixture.selectDispatchedJobInstanceId(jdbc, tenant, response.instanceNo());
    var task = tasks.selectByQuery(JobTaskQuery.ofJobInstance(tenant, job)).getFirst();
    String worker =
        task.getAssignedWorkerCode() == null ? seed.workerCode() : task.getAssignedWorkerCode();
    execution.assignWorker(tenant, task.getId(), worker);
    TaskOutcomeCommand sourceFailure = TaskOutcomeCommand.builder()
        .tenantId(tenant)
        .taskId(task.getId())
        .workerId(worker)
        .partitionInvocationId(
            partitions.selectById(tenant, task.getJobPartitionId()).getCurrentInvocationId())
        .success(false)
        .errorCode("R25_SOURCE_FAILED")
        .errorMessage("source fixture failed through REPORT")
        .build();
    execution.applyTaskOutcome(sourceFailure);
    assertThat(jobs.selectById(tenant, job).getInstanceStatus()).isEqualTo("FAILED");
    return new Source(tenant, seed.jobCode(), job, worker);
  }

  private long session(String tenant, int total) {
    final String insertBatchDayReplaySessionSql = """
        insert into batch.batch_day_replay_session (tenant_id,calendar_code,biz_date,scope,result_policy,
        config_version_policy,reason,status,total_count,in_flight_count,requested_by)
        values (?,'JOB_CALENDAR',?,'ALL','CREATE_NEW_VERSION','USE_ORIGINAL_CONFIG','R25 execution proof','RUNNING',?,?,'r25-test') returning id
        """;

    return jdbc.queryForObject(
        insertBatchDayReplaySessionSql, Long.class, tenant, DAY, total, total);
  }

  private Accepted accept(Source source, long session) {
    final String insertBatchDayReplayEntrySql = """
        insert into batch.batch_day_replay_entry (session_id,tenant_id,job_code,source_instance_id,status)
        values (?,?,?,?,'RUNNING') returning id
        """;
    final String selectPayloadJsonSql = """
        select payload_json::text from batch.outbox_event where tenant_id=? and event_type='WORKFLOW_LAUNCH'\s\
        and event_key=?
    """;

    long entry = jdbc.queryForObject(
        insertBatchDayReplayEntrySql,
        Long.class,
        session,
        source.tenant(),
        source.code(),
        source.job());
    String command = compensation.submit(CompensationSubmitCommand.builder()
        .tenantId(source.tenant())
        .compensationType("JOB")
        .targetId(source.job())
        .jobCode(source.code())
        .bizDate(DAY)
        .operatorId("r25-test")
        .reason("R25 replay")
        .replaySessionId(session)
        .replayEntryId(entry)
        .resultPolicy((String) sessionRow(session).get("result_policy"))
        .configVersionPolicy((String) sessionRow(session).get("config_version_policy"))
        .configVersion((Integer) sessionRow(session).get("config_version"))
        .dryRun("DRY_RUN".equals(sessionRow(session).get("execution_mode")))
        .build());
    String requestId = "replay-" + session + "-" + entry;
    assertThat(jobs.selectCurrentByWorkflowRequest(source.tenant(), requestId, source.code()))
        .isNull();
    String envelope = jdbc.queryForObject(
        selectPayloadJsonSql,
        String.class,
        source.tenant(),
        source.tenant() + ":workflow-launch:" + requestId);
    return new Accepted(source, session, entry, command, requestId, envelope);
  }

  private Accepted dispatched(Source source, long session, long entry) {
    String requestId = "replay-" + session + "-" + entry;
    String eventKey = source.tenant() + ":workflow-launch:" + requestId;
    final String selectPayloadJsonSql = """
        select payload_json::text from batch.outbox_event
        where tenant_id=? and event_type='WORKFLOW_LAUNCH' and event_key=?
        """;
    String envelope =
        jdbc.queryForObject(selectPayloadJsonSql, String.class, source.tenant(), eventKey);
    return new Accepted(source, session, entry, "batch-day-replay-dispatcher", requestId, envelope);
  }

  private String retryRangeEntry(
      String tenantId,
      long sessionId,
      long entryId,
      CountDownLatch retriesReady,
      CountDownLatch beginRetries)
      throws InterruptedException {
    retriesReady.countDown();
    if (!beginRetries.await(10, TimeUnit.SECONDS)) {
      throw new IllegalStateException("timed out waiting to start competing range retries");
    }
    try {
      RlsTenantContextHolder.runWithTenant(
          tenantId,
          () -> dayReplay.retryFailedSubmission(
              tenantId,
              sessionId,
              entryId,
              "r25-range-operator",
              "Retry confirmed unsubmitted replay entry"));
      return "RETRIED";
    } catch (BizException conflict) {
      return conflict.getCode().code();
    }
  }

  private static BatchDayReplayRangeCommand rangeCommand(
      String tenantId,
      LocalDate fromDate,
      LocalDate throughDate,
      UUID operationKey,
      String previewToken) {
    return new BatchDayReplayRangeCommand(
        tenantId,
        "JOB_CALENDAR",
        fromDate,
        throughDate,
        "ALL_FAILED",
        null,
        null,
        "CREATE_NEW_VERSION",
        "USE_ORIGINAL_CONFIG",
        null,
        "R25 range recovery",
        "r25-range-operator",
        true,
        "r25-range-recovery-" + tenantId,
        "REPLAY",
        "EXISTING_INSTANCES",
        operationKey,
        previewToken);
  }

  private Replay replay(Source source, long session) {
    return consume(accept(source, session));
  }

  private Replay consume(Accepted accepted) {
    Source source = accepted.source();
    long session = accepted.session(), entry = accepted.entry();
    String command = accepted.command();
    consumer.consume(
        new ConsumerRecord<>(
            BatchTopics.TRIGGER_LAUNCH_V1, 0, 0L, accepted.requestId(), accepted.envelope()),
        () -> {});
    var job = jobs.selectCurrentByWorkflowRequest(
        source.tenant(), "replay-" + session + "-" + entry, source.code());
    assertThat(job).isNotNull();
    var task = tasks
        .selectByQuery(JobTaskQuery.ofJobInstance(source.tenant(), job.getId()))
        .getFirst();
    String worker =
        task.getAssignedWorkerCode() == null ? source.worker() : task.getAssignedWorkerCode();
    task = execution.assignWorker(source.tenant(), task.getId(), worker);
    return new Replay(source, session, entry, job.getId(), command, task);
  }

  private void report(Replay replay) {
    var source = replay.source();
    TaskOutcomeCommand replaySuccess = TaskOutcomeCommand.builder()
        .tenantId(source.tenant())
        .taskId(replay.task().getId())
        .workerId(replay.task().getAssignedWorkerCode())
        .success(true)
        .partitionInvocationId(partitions
            .selectById(source.tenant(), replay.task().getJobPartitionId())
            .getCurrentInvocationId())
        .build();
    execution.applyTaskOutcome(replaySuccess);
  }

  private String entryStatus(Replay replay) {
    final String selectStatusSql = """
        select status from batch.batch_day_replay_entry where id=?
    """;

    return jdbc.queryForObject(selectStatusSql, String.class, replay.entry());
  }

  private Map<String, Object> sessionRow(long id) {
    final String selectStatementSql = """
        select * from batch.batch_day_replay_session where id=?
    """;

    return jdbc.queryForMap(selectStatementSql, id);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> replayJobPolicy(long jobId) {
    final String selectRerunPolicySnapshotSql = """
        select rerun_policy_snapshot from batch.job_instance where id=?
    """;

    String snapshot = jdbc.queryForObject(selectRerunPolicySnapshotSql, String.class, jobId);
    return JsonUtils.fromJson(snapshot, Map.class);
  }

  private static String tenant() {
    return "r25_" + UUID.randomUUID().toString().substring(0, 10);
  }
}
