package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.pinpols.batch.common.dto.LaunchEnvelope;
import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.dto.LaunchResponse;
import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import io.github.pinpols.batch.common.enums.PartitionStatus;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.service.SecretPayloadProtector;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.scheduler.ResourceScheduler;
import io.github.pinpols.batch.orchestrator.application.scheduler.TenantSchedulerSnapshotService;
import io.github.pinpols.batch.orchestrator.application.service.replay.WorkflowRunReplaySnapshotResolver;
import io.github.pinpols.batch.orchestrator.application.service.task.LaunchApplicationService;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskControlPayloads.TaskClaimCommand;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskControllerApplicationService;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskOutcomeService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowGraphValidator;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowLaunchWaitService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowLaunchWaitSnapshot;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowNodeDispatchService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunLaunchService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunManagementApplicationService;
import io.github.pinpols.batch.orchestrator.application.trigger.TriggerLaunchRecoveryService;
import io.github.pinpols.batch.orchestrator.domain.command.TaskOutcomeCommand;
import io.github.pinpols.batch.orchestrator.domain.entity.JobDefinitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobPartitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.TenantQuotaPolicyEntity;
import io.github.pinpols.batch.orchestrator.domain.param.TriggerRequestIsValidParentWorkflowNodeLaunchParam;
import io.github.pinpols.batch.orchestrator.domain.param.WorkflowRunMarkRunningParam;
import io.github.pinpols.batch.orchestrator.domain.query.WorkflowLaunchWaitClaim;
import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceSchedulingRequest;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import io.github.pinpols.batch.orchestrator.infrastructure.scheduler.BatchDaySettleScheduler;
import io.github.pinpols.batch.orchestrator.infrastructure.scheduler.BatchDayWaitingReleaseScheduler;
import io.github.pinpols.batch.orchestrator.infrastructure.scheduler.WorkflowLaunchWaitReconciler;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.TriggerRequestMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowDefinitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowRunMapper;
import io.github.pinpols.batch.orchestrator.service.JobDependencyWaitingLaunchService;
import io.github.pinpols.batch.orchestrator.service.LaunchService;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.assertj.core.api.Assertions;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * 集成测试：工作流 DAG 中的 JOB 节点类型。
 *
 * <p>场景：根 Workflow 包含一个引用可执行 Job 的 JOB 节点。编排器应：
 *
 * <ol>
 *   <li>自动启动子 job instance。
 *   <li>为 Job 节点创建独立 Job Instance，并将其关联到对应 WorkflowNodeRun。
 *   <li>子 Job 结果推进根 Workflow；根运行状态不依赖合成父 JobInstance。
 * </ol>
 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("Workflow Job 节点集成验证: 独立 JobInstance 与根运行状态推进")
class JobNodeDispatchIntegrationTest extends AbstractIntegrationTest {

  @Autowired
  private WorkflowLaunchWaitReconciler launchWaitReconciler;

  @Autowired
  private BatchDayWaitingReleaseScheduler batchDayWaitingReleaseScheduler;

  @Autowired
  @MockitoSpyBean
  private WorkflowLaunchWaitService waitingLaunchService;

  @Autowired
  private PlatformTransactionManager platformTransactionManager;

  private final String testTenantId =
      "t-job-node-" + UUID.randomUUID().toString().replace("-", "");
  private static final LocalDate BIZ_DATE = LocalDate.of(2026, Month.MARCH, 1);

  @Autowired
  private TaskOutcomeService taskOutcomeService;

  @Autowired
  private TaskControllerApplicationService taskControllerApplicationService;

  @Autowired
  private WorkflowNodeDispatchService workflowNodeDispatchService;

  @Autowired
  @MockitoSpyBean
  private WorkflowRunMapper workflowRunMapper;

  @MockitoSpyBean
  private WorkflowGraphValidator workflowGraphValidator;

  @MockitoSpyBean
  private JobDependencyWaitingLaunchService jobDependencyWaitingLaunchService;

  @Autowired
  @MockitoSpyBean
  private TriggerRequestMapper triggerRequestMapper;

  @MockitoSpyBean
  private WorkflowDefinitionMapper workflowDefinitionMapper;

  @MockitoSpyBean
  private WorkflowRunReplaySnapshotResolver replaySnapshotResolver;

  @Autowired
  private TriggerLaunchRecoveryService triggerLaunchRecoveryService;

  @Autowired
  private SqlSessionTemplate sqlSessionTemplate;

  @Autowired
  private SecretPayloadProtector secretPayloadProtector;

  @ParameterizedTest
  @ValueSource(strings = {"FAILED", "RUNNING"})
  @DisplayName("终态重放或无效快照且无运行时仍拒绝建档")
  void shouldRejectTerminalReplay_whenRunIsAbsent(String operationStatus) {
    String fixtureFailWorkflowRunSql =
        "update batch.workflow_run set run_status='FAILED', finished_at=now() where tenant_id=? and id=?";
    String fixtureUpdateTriggerTypeSql =
        "update batch.trigger_request set trigger_type='RERUN' where tenant_id=? and request_id=?";
    String fixtureInsertReplaySql =
        "insert into batch.replay_operation (tenant_id,operation_key,operation_type,status,scope,execution_mode,candidate_source,source_workflow_run_id,target_request_id,request_hash,snapshot_hash,snapshot_payload,reason,requested_by,approved_by,total_count,in_flight_count) values (?,?::uuid,'WORKFLOW_RUN',?,'FULL_WORKFLOW','REPLAY','EXACT_SOURCE_WORKFLOW_RUN',?,?, 'request','snapshot','{}'::jsonb,'test','requester','approver',1,?)";
    String fixtureSelectRequestStatusSql =
        "select request_status from batch.trigger_request where tenant_id=? and request_id=?";
    String fixtureCountWorkflowRunsSql =
        "select count(*) from batch.workflow_run r join batch.trigger_request t on t.id=r.trigger_request_id and t.tenant_id=r.tenant_id where t.tenant_id=? and t.request_id=?";
    String fixtureSelectReplayStatusSql =
        "select status from batch.replay_operation where tenant_id=? and target_request_id=?";

    String job = seedChildJob("REPLAY_INVALID_" + System.nanoTime());
    DirectWorkflowSeed target = seedDirectWorkflow(job);
    WorkflowRunEntity source = seedRootlessWorkflow(job);
    jdbcTemplate.update(fixtureFailWorkflowRunSql, testTenantId, source.getId());
    jdbcTemplate.update(fixtureUpdateTriggerTypeSql, testTenantId, target.requestId());
    jdbcTemplate.update(
        fixtureInsertReplaySql,
        testTenantId,
        UUID.randomUUID().toString(),
        operationStatus,
        source.getId(),
        target.requestId(),
        "RUNNING".equals(operationStatus) ? 1 : 0);
    LaunchRequest replay = LaunchRequest.builder()
        .tenantId(testTenantId)
        .workflowDefinitionId(target.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.RERUN)
        .requestId(target.requestId())
        .build();

    assertThatThrownBy(() -> workflowRunLaunchService.launch(replay))
        .isInstanceOf(BizException.class)
        .hasMessage(
            "RUNNING".equals(operationStatus)
                ? "error.workflow.replay_source_snapshot_invalid"
                : "error.workflow.workflow_run_replay_approval_state_invalid");
    String selectRequestStatusResult1 = jdbcTemplate.queryForObject(
        fixtureSelectRequestStatusSql, String.class, testTenantId, target.requestId());
    assertThat(selectRequestStatusResult1).isEqualTo("REJECTED");
    Long countWorkflowRunsResult2 = jdbcTemplate.queryForObject(
        fixtureCountWorkflowRunsSql, Long.class, testTenantId, target.requestId());
    assertThat(countWorkflowRunsResult2).isZero();
    String selectReplayStatusResult3 = jdbcTemplate.queryForObject(
        fixtureSelectReplayStatusSql, String.class, testTenantId, target.requestId());
    assertThat(selectReplayStatusResult3).isEqualTo("FAILED");
  }

  @Test
  @DisplayName("已完成重放不被并发旧读取的准入检查误拒绝")
  void shouldPreserveCompletedReplay_whenConcurrentReadIsStale() throws Exception {
    String fixtureDeleteWorkflowEdgesSql =
        "delete from batch.workflow_edge where tenant_id=? and workflow_definition_id=?";
    String fixtureDeleteWorkflowNodeSql =
        "delete from batch.workflow_node where tenant_id=? and workflow_definition_id=? and node_code='JOB_NODE_1'";
    String fixtureInsertWorkflowEdgeSql =
        "insert into batch.workflow_edge (tenant_id,workflow_definition_id,from_node_code,to_node_code,edge_type,enabled) values (?,?,'START','END','ALWAYS',true)";
    String fixtureSelectExecutionSnapshotSql =
        "select execution_snapshot::text from batch.workflow_run where tenant_id=? and id=?";
    String fixtureInsertRequestSql =
        "insert into batch.trigger_request (tenant_id,request_id,trigger_type,workflow_definition_id,biz_date,dedup_key,request_status) values (?,?,'RERUN',?,?,?,'ACCEPTED')";
    String fixtureInsertReplaySql =
        "insert into batch.replay_operation (tenant_id,operation_key,operation_type,status,scope,execution_mode,candidate_source,source_workflow_run_id,target_request_id,request_hash,snapshot_hash,snapshot_payload,reason,requested_by,approved_by,total_count,in_flight_count) values (?,?::uuid,'WORKFLOW_RUN','RUNNING','FULL_WORKFLOW','REPLAY','EXACT_SOURCE_WORKFLOW_RUN',?,?, 'request','snapshot','{}'::jsonb,'test','requester','approver',1,1)";
    String fixtureSelectReplayStatusSql =
        "select status from batch.replay_operation where tenant_id=? and target_request_id=?";
    String fixtureSelectRequestStatusSql =
        "select request_status from batch.trigger_request where tenant_id=? and request_id=?";
    String fixtureSelectRunStatusSql =
        "select run_status from batch.workflow_run where tenant_id=? and id=?";

    DirectWorkflowSeed source =
        seedDirectWorkflow(seedChildJob("REPLAY_RACE_" + System.nanoTime()));
    jdbcTemplate.update(fixtureDeleteWorkflowEdgesSql, testTenantId, source.workflowDefinitionId());
    jdbcTemplate.update(fixtureDeleteWorkflowNodeSql, testTenantId, source.workflowDefinitionId());
    jdbcTemplate.update(fixtureInsertWorkflowEdgeSql, testTenantId, source.workflowDefinitionId());
    LaunchRequest sourceRequest = LaunchRequest.builder()
        .tenantId(testTenantId)
        .workflowDefinitionId(source.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.API)
        .requestId(source.requestId())
        .build();
    workflowRunLaunchService.launch(sourceRequest);
    Long sourceRun = workflowRunId(source.requestId());
    String graph = jdbcTemplate.queryForObject(
        fixtureSelectExecutionSnapshotSql, String.class, testTenantId, sourceRun);
    String replayRequestId = "replay-race-" + System.nanoTime();
    jdbcTemplate.update(
        fixtureInsertRequestSql,
        testTenantId,
        replayRequestId,
        source.workflowDefinitionId(),
        BIZ_DATE,
        "dedup-" + replayRequestId);
    jdbcTemplate.update(
        fixtureInsertReplaySql,
        testTenantId,
        UUID.randomUUID().toString(),
        sourceRun,
        replayRequestId);
    var frozen = new WorkflowRunReplaySnapshotResolver.Snapshot(
        testTenantId,
        source.workflowDefinitionId(),
        3,
        "WF_CALENDAR",
        BIZ_DATE,
        null,
        null,
        false,
        null,
        Map.of(),
        graph);
    Mockito.doReturn(frozen)
        .when(replaySnapshotResolver)
        .resolve(ArgumentMatchers.any(), ArgumentMatchers.eq(replayRequestId));
    LaunchRequest replay = sourceRequest.toBuilder()
        .triggerType(TriggerType.RERUN)
        .requestId(replayRequestId)
        .build();
    CountDownLatch staleRead = new CountDownLatch(1);
    CountDownLatch releaseStale = new CountDownLatch(1);
    CountDownLatch completionBeforeReceipt = new CountDownLatch(1);
    CountDownLatch releaseReceipt = new CountDownLatch(1);
    AtomicInteger staleReads = new AtomicInteger();
    WorkflowRunMapper directRuns = sqlSessionTemplate.getMapper(WorkflowRunMapper.class);
    TriggerRequestMapper directRequests = sqlSessionTemplate.getMapper(TriggerRequestMapper.class);
    Mockito.doAnswer(invocation -> {
          Object found = directRuns.selectByTenantAndTriggerRequestId(
              invocation.getArgument(0), invocation.getArgument(1));
          if (Thread.currentThread().getName().equals("replay-stale")
              && staleReads.incrementAndGet() == 1) {
            staleRead.countDown();
            if (!releaseStale.await(20, TimeUnit.SECONDS))
              throw new IllegalStateException("timeout");
          }
          return found;
        })
        .when(workflowRunMapper)
        .selectByTenantAndTriggerRequestId(
            ArgumentMatchers.anyString(), ArgumentMatchers.anyLong());
    Mockito.doAnswer(invocation -> {
          if (Thread.currentThread().getName().equals("replay-winner")
              && "LAUNCHED".equals(invocation.getArgument(2))) {
            completionBeforeReceipt.countDown();
            if (!releaseReceipt.await(20, TimeUnit.SECONDS))
              throw new IllegalStateException("timeout");
          }
          return directRequests.updateAcceptance(
              invocation.getArgument(0),
              invocation.getArgument(1),
              invocation.getArgument(2),
              invocation.getArgument(3));
        })
        .when(triggerRequestMapper)
        .updateAcceptance(
            ArgumentMatchers.eq(testTenantId),
            ArgumentMatchers.eq(replayRequestId),
            ArgumentMatchers.anyString(),
            ArgumentMatchers.isNull());
    try (var pool = Executors.newFixedThreadPool(2)) {
      Future<?> stale = pool.submit(() -> {
        Thread.currentThread().setName("replay-stale");
        return RlsTenantContextHolder.runWithTenant(
            testTenantId, () -> workflowRunLaunchService.launch(replay));
      });
      try {
        assertThat(staleRead.await(20, TimeUnit.SECONDS)).isTrue();
        Future<?> winner = pool.submit(() -> {
          Thread.currentThread().setName("replay-winner");
          return RlsTenantContextHolder.runWithTenant(
              testTenantId, () -> workflowRunLaunchService.launch(replay));
        });
        try {
          assertThat(completionBeforeReceipt.await(20, TimeUnit.SECONDS)).isTrue();
          String selectReplayStatusResult4 = jdbcTemplate.queryForObject(
              fixtureSelectReplayStatusSql, String.class, testTenantId, replayRequestId);
          assertThat(selectReplayStatusResult4).isEqualTo("SUCCEEDED");
          releaseStale.countDown();
          assertThatThrownBy(() -> stale.get(20, TimeUnit.SECONDS))
              .hasCauseInstanceOf(ResponseStatusException.class)
              .satisfies(
                  error -> assertThat(((ResponseStatusException) error.getCause()).getStatusCode())
                      .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        } finally {
          releaseStale.countDown();
          releaseReceipt.countDown();
        }
        winner.get(20, TimeUnit.SECONDS);
      } finally {
        releaseStale.countDown();
        releaseReceipt.countDown();
      }
    }
    String selectRequestStatusResult5 = jdbcTemplate.queryForObject(
        fixtureSelectRequestStatusSql, String.class, testTenantId, replayRequestId);
    assertThat(selectRequestStatusResult5).isEqualTo("LAUNCHED");
    String selectRunStatusResult6 = jdbcTemplate.queryForObject(
        fixtureSelectRunStatusSql, String.class, testTenantId, workflowRunId(replayRequestId));
    assertThat(selectRunStatusResult6).isEqualTo("SUCCESS");
    String selectReplayStatusResult7 = jdbcTemplate.queryForObject(
        fixtureSelectReplayStatusSql, String.class, testTenantId, replayRequestId);
    assertThat(selectReplayStatusResult7).isEqualTo("SUCCEEDED");
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @DisplayName("建档版本冲突按并发运行结果决定是否拒绝")
  void shouldRespectConcurrentWinner_whenPreparationVersionConflicts(boolean hasWinner)
      throws Exception {
    String fixtureUpdateWorkflowVersionSql =
        "update batch.workflow_definition set version=version+1 where tenant_id=? and id=?";
    String fixtureSelectRequestStatusSql =
        "select request_status from batch.trigger_request where tenant_id=? and request_id=?";
    String fixtureCountWorkflowRunsSql =
        "select count(*) from batch.workflow_run r join batch.trigger_request t on t.id=r.trigger_request_id and t.tenant_id=r.tenant_id where t.tenant_id=? and t.request_id=?";

    DirectWorkflowSeed seed =
        seedDirectWorkflow(seedChildJob("WORKFLOW_VERSION_RACE_" + System.nanoTime()));
    LaunchRequest request = LaunchRequest.builder()
        .tenantId(testTenantId)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.API)
        .requestId(seed.requestId())
        .build();
    CountDownLatch oldDefinitionRead = new CountDownLatch(1);
    CountDownLatch releaseOldDefinition = new CountDownLatch(1);
    CountDownLatch rolledBack = new CountDownLatch(1);
    CountDownLatch releaseRollback = new CountDownLatch(1);
    CountDownLatch winnerCreated = new CountDownLatch(1);
    CountDownLatch releaseWinner = new CountDownLatch(1);
    AtomicInteger staleDefinitionCalls = new AtomicInteger();
    AtomicInteger staleRequestLocks = new AtomicInteger();
    var directDefinitions = sqlSessionTemplate.getMapper(WorkflowDefinitionMapper.class);
    WorkflowRunMapper directRuns = sqlSessionTemplate.getMapper(WorkflowRunMapper.class);
    TriggerRequestMapper directRequests = sqlSessionTemplate.getMapper(TriggerRequestMapper.class);
    Mockito.doAnswer(invocation -> {
          Object definition = directDefinitions.selectByTenantAndIdAndEnabled(
              invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
          if (Thread.currentThread().getName().equals("workflow-version-stale")
              && staleDefinitionCalls.incrementAndGet() == 1) {
            oldDefinitionRead.countDown();
            if (!releaseOldDefinition.await(20, TimeUnit.SECONDS))
              throw new IllegalStateException("timeout");
          }
          return definition;
        })
        .when(workflowDefinitionMapper)
        .selectByTenantAndIdAndEnabled(
            ArgumentMatchers.anyString(), ArgumentMatchers.anyLong(), ArgumentMatchers.eq(true));
    Mockito.doAnswer(invocation -> {
          if (Thread.currentThread().getName().equals("workflow-version-stale")
              && staleRequestLocks.incrementAndGet() == 2) {
            rolledBack.countDown();
            if (!releaseRollback.await(20, TimeUnit.SECONDS))
              throw new IllegalStateException("timeout");
          }
          return directRequests.selectByTenantAndRequestIdForUpdate(
              invocation.getArgument(0), invocation.getArgument(1));
        })
        .when(triggerRequestMapper)
        .selectByTenantAndRequestIdForUpdate(
            ArgumentMatchers.anyString(), ArgumentMatchers.anyString());
    Mockito.doAnswer(invocation -> {
          if (Thread.currentThread().getName().equals("workflow-version-stale")
              && "REJECTED".equals(invocation.getArgument(2))) {
            rolledBack.countDown();
            if (!releaseRollback.await(20, TimeUnit.SECONDS))
              throw new IllegalStateException("timeout");
          }
          return directRequests.updateAcceptance(
              invocation.getArgument(0),
              invocation.getArgument(1),
              invocation.getArgument(2),
              invocation.getArgument(3));
        })
        .when(triggerRequestMapper)
        .updateAcceptance(
            ArgumentMatchers.anyString(),
            ArgumentMatchers.anyString(),
            ArgumentMatchers.anyString(),
            ArgumentMatchers.isNull());
    Mockito.doAnswer(invocation -> {
          int result = directRuns.markRunning(invocation.getArgument(0));
          if (Thread.currentThread().getName().equals("workflow-version-winner")) {
            winnerCreated.countDown();
            if (!releaseWinner.await(20, TimeUnit.SECONDS))
              throw new IllegalStateException("timeout");
          }
          return result;
        })
        .when(workflowRunMapper)
        .markRunning(ArgumentMatchers.any());
    try (var pool = Executors.newFixedThreadPool(2)) {
      Future<?> stale = pool.submit(() -> {
        Thread.currentThread().setName("workflow-version-stale");
        return RlsTenantContextHolder.runWithTenant(
            testTenantId, () -> workflowRunLaunchService.launch(request));
      });
      try {
        assertThat(oldDefinitionRead.await(20, TimeUnit.SECONDS)).isTrue();
        jdbcTemplate.update(
            fixtureUpdateWorkflowVersionSql, testTenantId, seed.workflowDefinitionId());
        releaseOldDefinition.countDown();
        assertThat(rolledBack.await(20, TimeUnit.SECONDS)).isTrue();
        if (hasWinner) {
          Future<?> winner = pool.submit(() -> {
            Thread.currentThread().setName("workflow-version-winner");
            return RlsTenantContextHolder.runWithTenant(
                testTenantId, () -> workflowRunLaunchService.launch(request));
          });
          try {
            assertThat(winnerCreated.await(20, TimeUnit.SECONDS)).isTrue();
            releaseRollback.countDown();
            assertThatThrownBy(() -> stale.get(20, TimeUnit.SECONDS))
                .hasCauseInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(
                        ((ResponseStatusException) error.getCause()).getStatusCode())
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
          } finally {
            releaseRollback.countDown();
            releaseWinner.countDown();
          }
          winner.get(20, TimeUnit.SECONDS);
        } else {
          releaseRollback.countDown();
          assertThatThrownBy(() -> stale.get(20, TimeUnit.SECONDS))
              .hasCauseInstanceOf(BizException.class);
        }
      } finally {
        releaseOldDefinition.countDown();
        releaseRollback.countDown();
        releaseWinner.countDown();
      }
    }
    String selectRequestStatusResult8 = jdbcTemplate.queryForObject(
        fixtureSelectRequestStatusSql, String.class, testTenantId, seed.requestId());
    assertThat(selectRequestStatusResult8).isEqualTo(hasWinner ? "LAUNCHED" : "REJECTED");
    if (hasWinner) {
      assertThat(linkedNodeJobInstance(workflowRunId(seed.requestId()), "JOB_NODE_1"))
          .isNotNull();
    } else {
      Long countWorkflowRunsResult9 = jdbcTemplate.queryForObject(
          fixtureCountWorkflowRunsSql, Long.class, testTenantId, seed.requestId());
      assertThat(countWorkflowRunsResult9).isZero();
    }
  }

  @Test
  @DisplayName("无运行且父实例不存在时仍拒绝原请求")
  void shouldRejectUnpreparedWorkflow_whenParentIsMissing() {
    String fixtureSelectRequestStatusSql =
        "select request_status from batch.trigger_request where tenant_id=? and request_id=?";
    String fixtureCountWorkflowRunsSql =
        "select count(*) from batch.workflow_run r join batch.trigger_request t on t.id=r.trigger_request_id and t.tenant_id=r.tenant_id where t.tenant_id=? and t.request_id=?";

    DirectWorkflowSeed seed =
        seedDirectWorkflow(seedChildJob("WORKFLOW_PARENT_" + System.nanoTime()));
    LaunchRequest request = LaunchRequest.builder()
        .tenantId(testTenantId)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.API)
        .requestId(seed.requestId())
        .params(Map.of("parentInstanceId", Long.MAX_VALUE))
        .build();
    assertThatThrownBy(() -> workflowRunLaunchService.launch(request))
        .isInstanceOf(BizException.class);
    String selectRequestStatusResult10 = jdbcTemplate.queryForObject(
        fixtureSelectRequestStatusSql, String.class, testTenantId, seed.requestId());
    assertThat(selectRequestStatusResult10).isEqualTo("REJECTED");
    Long countWorkflowRunsResult11 = jdbcTemplate.queryForObject(
        fixtureCountWorkflowRunsSql, Long.class, testTenantId, seed.requestId());
    assertThat(countWorkflowRunsResult11).isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {"disabled", "invalidParent", "recovery", "changedPayload"})
  @DisplayName("并发启动保留原请求状态及首次载荷")
  void shouldPreserveCreatedRun_whenConcurrentDefinitionChanges(String scenario) throws Exception {
    String fixtureDisableWorkflowSql =
        "update batch.workflow_definition set enabled=false where tenant_id=? and id=?";
    String fixtureSelectRequestStatusSql =
        "select request_status from batch.trigger_request where tenant_id=? and request_id=?";
    String fixtureSelectProtectedPayloadSql =
        "select source_payload_protected::text from batch.workflow_node_run where workflow_run_id=? and node_code='JOB_NODE_1' order by run_seq desc limit 1";
    String fixtureSelectRecoveryStatusSql =
        "select recovery_status from batch.trigger_launch_recovery where tenant_id=? and request_id=?";
    String fixtureUpdateRecoveryTimeSql =
        "update batch.trigger_launch_recovery set next_attempt_at=now() - interval '1 second' where tenant_id=? and request_id=?";

    String jobCode = seedChildJob("WORKFLOW_REJECT_RACE_" + System.nanoTime());
    DirectWorkflowSeed seed = seedDirectWorkflow(jobCode);
    LaunchRequest request = LaunchRequest.builder()
        .tenantId(testTenantId)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.API)
        .requestId(seed.requestId())
        .params("changedPayload".equals(scenario) ? Map.of("payloadMarker", "original") : null)
        .build();
    LaunchRequest staleRequest = "invalidParent".equals(scenario)
        ? request.toBuilder().params(Map.of("parentInstanceId", Long.MAX_VALUE)).build()
        : "changedPayload".equals(scenario)
            ? request.toBuilder().params(Map.of("payloadMarker", "changed")).build()
            : request;
    if ("recovery".equals(scenario)) {
      String envelope = JsonUtils.toJson(
          LaunchEnvelope.of(request, testTenantId + ":" + seed.requestId(), Instant.now()));
      assertThat(triggerLaunchRecoveryService.park(envelope, "trace")).isTrue();
    }
    CountDownLatch oldRead = new CountDownLatch(1);
    CountDownLatch releaseOldRead = new CountDownLatch(1);
    CountDownLatch runCreated = new CountDownLatch(1);
    CountDownLatch releaseDispatch = new CountDownLatch(1);
    WorkflowRunMapper directMapper = sqlSessionTemplate.getMapper(WorkflowRunMapper.class);
    Mockito.doAnswer(invocation -> {
          Object found = directMapper.selectByTenantAndTriggerRequestId(
              invocation.getArgument(0), invocation.getArgument(1));
          if (Thread.currentThread().getName().equals("workflow-stale-read")) {
            oldRead.countDown();
            if (!releaseOldRead.await(20, TimeUnit.SECONDS))
              throw new IllegalStateException("timeout");
          }
          return found;
        })
        .when(workflowRunMapper)
        .selectByTenantAndTriggerRequestId(
            ArgumentMatchers.anyString(), ArgumentMatchers.anyLong());
    Mockito.doAnswer(invocation -> {
          int updated = directMapper.markRunning(invocation.getArgument(0));
          if (Thread.currentThread().getName().equals("workflow-created")) {
            runCreated.countDown();
            if (!releaseDispatch.await(20, TimeUnit.SECONDS))
              throw new IllegalStateException("timeout");
          }
          return updated;
        })
        .when(workflowRunMapper)
        .markRunning(ArgumentMatchers.any());
    try (var pool = Executors.newFixedThreadPool(2)) {
      Future<?> stale = pool.submit(() -> {
        Thread.currentThread().setName("workflow-stale-read");
        if ("recovery".equals(scenario)) {
          return triggerLaunchRecoveryService.recoverDue(10);
        }
        return RlsTenantContextHolder.runWithTenant(
            testTenantId, () -> workflowRunLaunchService.launch(staleRequest));
      });
      try {
        if (!oldRead.await(5, TimeUnit.SECONDS)) {
          stale.get(1, TimeUnit.SECONDS);
          throw new IllegalStateException("stale request did not reach the run lookup");
        }
        Future<?> created = pool.submit(() -> {
          Thread.currentThread().setName("workflow-created");
          return RlsTenantContextHolder.runWithTenant(
              testTenantId, () -> workflowRunLaunchService.launch(request));
        });
        try {
          assertThat(runCreated.await(20, TimeUnit.SECONDS)).isTrue();
          if ("disabled".equals(scenario) || "recovery".equals(scenario)) {
            jdbcTemplate.update(
                fixtureDisableWorkflowSql, testTenantId, seed.workflowDefinitionId());
          }
          releaseOldRead.countDown();
          if ("recovery".equals(scenario)) {
            assertThat(stale.get(20, TimeUnit.SECONDS)).isEqualTo(0);
          } else if ("changedPayload".equals(scenario)) {
            assertThat(stale.get(20, TimeUnit.SECONDS)).isNotNull();
          } else {
            assertThatThrownBy(() -> stale.get(20, TimeUnit.SECONDS))
                .hasCauseInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(
                        ((ResponseStatusException) error.getCause()).getStatusCode())
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
          }
        } finally {
          releaseOldRead.countDown();
          releaseDispatch.countDown();
        }
        created.get(20, TimeUnit.SECONDS);
      } finally {
        releaseOldRead.countDown();
        releaseDispatch.countDown();
      }
    }
    String status = jdbcTemplate.queryForObject(
        fixtureSelectRequestStatusSql, String.class, testTenantId, seed.requestId());
    assertThat(status).isEqualTo("LAUNCHED");
    assertThat(linkedNodeJobInstance(workflowRunId(seed.requestId()), "JOB_NODE_1"))
        .isNotNull();
    if ("changedPayload".equals(scenario)) {
      String protectedPayload = jdbcTemplate.queryForObject(
          fixtureSelectProtectedPayloadSql, String.class, workflowRunId(seed.requestId()));
      assertThat(JsonUtils.fromJson(
              secretPayloadProtector.reveal(protectedPayload),
              new TypeReference<Map<String, Object>>() {}))
          .containsEntry("payloadMarker", "original");
    }
    if ("recovery".equals(scenario)) {
      String recoveryStatus = jdbcTemplate.queryForObject(
          fixtureSelectRecoveryStatusSql, String.class, testTenantId, seed.requestId());
      assertThat(recoveryStatus).isEqualTo("PENDING");
      Long originalRun = workflowRunId(seed.requestId());
      Long originalJob = linkedNodeJobInstance(originalRun, "JOB_NODE_1");
      jdbcTemplate.update(fixtureUpdateRecoveryTimeSql, testTenantId, seed.requestId());
      assertThat(triggerLaunchRecoveryService.recoverDue(10)).isEqualTo(1);
      String selectRecoveryStatusResult12 = jdbcTemplate.queryForObject(
          fixtureSelectRecoveryStatusSql, String.class, testTenantId, seed.requestId());
      assertThat(selectRecoveryStatusResult12).isEqualTo("DONE");
      assertThat(workflowRunId(seed.requestId())).isEqualTo(originalRun);
      assertThat(linkedNodeJobInstance(originalRun, "JOB_NODE_1")).isEqualTo(originalJob);
    }
  }

  @Test
  @DisplayName("真实 Workflow 已启动后遇到冲突，恢复重试复用运行和子作业")
  void shouldReuseWorkflowAndJob_whenRecoveryFollowsLaunchConflict() {
    String jobCode = seedChildJob("RECOVERY_CONFLICT_" + System.nanoTime());
    DirectWorkflowSeed seed = seedDirectWorkflow(jobCode);
    LaunchRequest request = LaunchRequest.builder()
        .tenantId(testTenantId)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.API)
        .requestId(seed.requestId())
        .build();
    String envelope = JsonUtils.toJson(
        LaunchEnvelope.of(request, testTenantId + ":" + seed.requestId(), Instant.now()));
    assertThat(triggerLaunchRecoveryService.park(envelope, "trace")).isTrue();
    AtomicInteger attempts = new AtomicInteger();
    Mockito.doAnswer(invocation -> {
          LaunchRequest current = invocation.getArgument(0);
          Object response = invocation.callRealMethod();
          if (seed.requestId().equals(current.requestId()) && attempts.getAndIncrement() == 0) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT, "simulated post-launch conflict");
          }
          return response;
        })
        .when(launchApplicationService)
        .launchFromTrustedQueue(ArgumentMatchers.any());

    assertThat(triggerLaunchRecoveryService.recoverDue(10)).isZero();
    String selectRecovery =
        "select recovery_status from batch.trigger_launch_recovery where tenant_id=? and request_id=?";
    assertThat(jdbcTemplate.queryForObject(
            selectRecovery, String.class, testTenantId, seed.requestId()))
        .isEqualTo("PENDING");
    Long runId = workflowRunId(seed.requestId());
    Long jobId = linkedNodeJobInstance(runId, "JOB_NODE_1");
    assertThat(jobId).isNotNull();
    String makeDue =
        "update batch.trigger_launch_recovery set next_attempt_at=now() - interval '1 second' where tenant_id=? and request_id=?";
    jdbcTemplate.update(makeDue, testTenantId, seed.requestId());
    assertThat(triggerLaunchRecoveryService.recoverDue(10)).isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject(
            selectRecovery, String.class, testTenantId, seed.requestId()))
        .isEqualTo("DONE");
    assertThat(workflowRunId(seed.requestId())).isEqualTo(runId);
    assertThat(linkedNodeJobInstance(runId, "JOB_NODE_1")).isEqualTo(jobId);
    String countRuns =
        "select count(*) from batch.workflow_run wr join batch.trigger_request tr on tr.id=wr.trigger_request_id and tr.tenant_id=wr.tenant_id where tr.tenant_id=? and tr.request_id=?";
    String countJobs =
        "select count(*) from batch.job_instance ji join batch.trigger_request child on child.id=ji.trigger_request_id and child.tenant_id=ji.tenant_id where child.parent_workflow_run_id=? and child.tenant_id=?";
    assertThat(jdbcTemplate.queryForObject(countRuns, Long.class, testTenantId, seed.requestId()))
        .isEqualTo(1L);
    assertThat(jdbcTemplate.queryForObject(countJobs, Long.class, runId, testTenantId))
        .isEqualTo(1L);
    assertThat(attempts).hasValue(2);
  }

  @Autowired
  private WorkflowRunLaunchService workflowRunLaunchService;

  @Autowired
  private LaunchService launchService;

  @MockitoSpyBean
  private LaunchApplicationService launchApplicationService;

  @Autowired
  @MockitoSpyBean
  private OrchestratorConfigCacheService configCacheService;

  @Autowired
  @MockitoSpyBean
  private ResourceScheduler resourceScheduler;

  @Autowired
  private TenantSchedulerSnapshotService tenantSchedulerSnapshotService;

  @Autowired
  private MeterRegistry meterRegistry;

  @Autowired
  private LockProvider lockProvider;

  @Autowired
  private JobPartitionMapper jobPartitionMapper;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private BatchDaySettleScheduler batchDaySettleScheduler;

  @Autowired
  private BatchDayInstanceMapper batchDayInstanceMapper;

  @Autowired
  private WorkflowRunManagementApplicationService workflowRunManagementApplicationService;

  @Test
  @DisplayName("补跑启动中断后两个恢复器复用原请求且只创建一个子 Job")
  void failedBatchDay_shouldRecoverSameRequestAfterLaunchFailure() throws Exception {
    final String insertCalendarSql = """
        insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone, cutoff_time, catch_up_policy)
        values (?, ?, 'test', 'UTC', '00:00:00', 'AUTO')
        """;
    final String updateJobCalendarSql = """
        update batch.job_definition set calendar_code=? where tenant_id=? and job_code=?
        """;
    final String updateWorkflowCalendarSql = """
        update batch.workflow_definition set calendar_code=? where tenant_id=? and
        (id=? or single_job_definition_id=(select id from batch.job_definition where tenant_id=? and job_code=?))
        """;
    final String updateWorkflowStatusSql = """
        update batch.workflow_run set run_status='FAILED' where tenant_id=? and id=?
        """;
    final String updateJobStatusSql = """
        update batch.job_instance set instance_status='FAILED' where tenant_id=? and id=?
        """;
    final String updateDaySql = """
        update batch.batch_day_instance set day_status='IN_FLIGHT', frozen=false
        where tenant_id=? and calendar_code=? and biz_date=?
        """;
    final String selectRootSql = """
        select request_id, request_status from batch.trigger_request
        where tenant_id=? and dedup_key=? and trigger_type='CATCH_UP' and parent_workflow_run_id is null
        """;
    final String countRootSql = """
        select count(*) from batch.trigger_request
        where tenant_id=? and dedup_key=? and trigger_type='CATCH_UP' and parent_workflow_run_id is null
        """;
    final String countWorkflowSql = """
        select count(*) from batch.workflow_run wr
        join batch.trigger_request root on root.tenant_id=wr.tenant_id and root.id=wr.trigger_request_id
        where root.tenant_id=? and root.request_id=?
        """;
    final String countChildSql = """
        select count(*) from batch.job_instance ji
        join batch.trigger_request child on child.tenant_id=ji.tenant_id and child.id=ji.trigger_request_id
        join batch.workflow_run wr on wr.tenant_id=child.tenant_id and wr.id=child.parent_workflow_run_id
        join batch.trigger_request root on root.tenant_id=wr.tenant_id and root.id=wr.trigger_request_id
        where root.tenant_id=? and root.request_id=? and ji.job_code=?
        """;
    String calendar = "CATCHUP_RECOVERY_" + System.nanoTime();
    String jobCode = seedChildJob("WG_CATCHUP_RECOVERY_" + System.nanoTime());
    DirectWorkflowSeed seed = seedDirectWorkflow(jobCode);
    jdbcTemplate.update(insertCalendarSql, testTenantId, calendar);
    jdbcTemplate.update(updateJobCalendarSql, calendar, testTenantId, jobCode);
    jdbcTemplate.update(
        updateWorkflowCalendarSql,
        calendar,
        testTenantId,
        seed.workflowDefinitionId(),
        testTenantId,
        jobCode);
    LaunchRequest original = LaunchRequest.builder()
        .tenantId(testTenantId)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.API)
        .requestId(seed.requestId())
        .build();
    workflowRunLaunchService.launch(original);
    Long originalRun = workflowRunId(seed.requestId());
    Long originalJob = linkedNodeJobInstance(originalRun, "JOB_NODE_1");
    jdbcTemplate.update(updateJobStatusSql, testTenantId, originalJob);
    jdbcTemplate.update(updateWorkflowStatusSql, testTenantId, originalRun);
    assertThat(jdbcTemplate.update(updateDaySql, testTenantId, calendar, BIZ_DATE))
        .isEqualTo(1);
    AtomicInteger calls = new AtomicInteger();
    CountDownLatch concurrentRecovery = new CountDownLatch(2);
    Mockito.doAnswer(invocation -> {
          LaunchRequest request = invocation.getArgument(0);
          if (testTenantId.equals(request.tenantId())) {
            if (calls.getAndIncrement() == 0) {
              throw new IllegalStateException("injected failure after request commit");
            }
            concurrentRecovery.countDown();
            if (!concurrentRecovery.await(10, TimeUnit.SECONDS)) {
              throw new IllegalStateException("second recovery did not arrive");
            }
          }
          return invocation.callRealMethod();
        })
        .when(launchApplicationService)
        .launchFromTrustedQueue(ArgumentMatchers.any());

    assertThatThrownBy(batchDaySettleScheduler::settle)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("injected failure");
    String dedup = testTenantId + ":batch-day-catchup:" + calendar + ":" + BIZ_DATE + ":" + jobCode;
    Map<String, Object> accepted = jdbcTemplate.queryForMap(selectRootSql, testTenantId, dedup);
    assertThat(accepted.get("request_status")).isEqualTo("ACCEPTED");
    String acceptedRequestId = (String) accepted.get("request_id");

    ExecutorService recoverers = Executors.newFixedThreadPool(2);
    try {
      Future<?> first = recoverers.submit(batchDaySettleScheduler::settle);
      Future<?> second = recoverers.submit(batchDaySettleScheduler::settle);
      first.get(30, TimeUnit.SECONDS);
      second.get(30, TimeUnit.SECONDS);
    } finally {
      recoverers.shutdownNow();
      assertThat(recoverers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    Map<String, Object> recovered = jdbcTemplate.queryForMap(selectRootSql, testTenantId, dedup);
    assertThat(recovered)
        .containsEntry("request_id", acceptedRequestId)
        .containsEntry("request_status", "LAUNCHED");
    assertThat(jdbcTemplate.queryForObject(countRootSql, Long.class, testTenantId, dedup))
        .isEqualTo(1L);
    assertThat(jdbcTemplate.queryForObject(
            countWorkflowSql, Long.class, testTenantId, acceptedRequestId))
        .isEqualTo(1L);
    assertThat(jdbcTemplate.queryForObject(
            countChildSql, Long.class, testTenantId, acceptedRequestId, jobCode))
        .isEqualTo(1L);
    assertThat(calls.get()).isEqualTo(3);
  }

  @Test
  @DisplayName("失败批日提交后真实补跑创建 Workflow 与子 Job")
  void failedBatchDay_shouldLaunchRealCatchUpWorkflowAndChildJob() {
    final String insertCalendarSql = """
        insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone, cutoff_time, catch_up_policy)
        values (?, ?, 'test', 'UTC', '00:00:00', 'AUTO')
        """;
    final String updateJobCalendarSql = """
        update batch.job_definition set calendar_code=? where tenant_id=? and job_code=?
        """;
    final String updateWorkflowCalendarSql = """
        update batch.workflow_definition set calendar_code=? where tenant_id=? and
        (id=? or single_job_definition_id=(select id from batch.job_definition where tenant_id=? and job_code=?))
        """;
    final String updateWorkflowStatusSql = """
        update batch.workflow_run set run_status='FAILED' where tenant_id=? and id=?
        """;
    final String updateJobStatusSql = """
        update batch.job_instance set instance_status='FAILED' where tenant_id=? and id=?
        """;
    final String updateDaySql = """
        update batch.batch_day_instance set day_status='IN_FLIGHT', frozen=false
        where tenant_id=? and calendar_code=? and biz_date=?
        """;
    final String selectCatchUpSql = """
        select tr.request_status, tr.workflow_definition_id, wr.id as run_id
        from batch.trigger_request tr
        left join batch.workflow_run wr on wr.tenant_id=tr.tenant_id and wr.trigger_request_id=tr.id
        where tr.tenant_id=? and tr.dedup_key=? and tr.trigger_type='CATCH_UP'
          and tr.parent_workflow_run_id is null
        """;
    final String countCatchUpJobSql = """
        select count(*) from batch.job_instance ji
        join batch.trigger_request child on child.tenant_id=ji.tenant_id and child.id=ji.trigger_request_id
        where ji.tenant_id=? and ji.job_code=? and child.parent_workflow_run_id=?
        """;
    final String resetRootStatusSql = """
        update batch.trigger_request set request_status='ACCEPTED'
        where tenant_id=? and dedup_key=? and trigger_type='CATCH_UP' and parent_workflow_run_id is null
        """;
    final String selectDayStatusSql = """
        select day_status from batch.batch_day_instance where tenant_id=? and calendar_code=? and biz_date=?
        """;
    final String resetFailedDaySql = """
        update batch.batch_day_instance set day_status='FAILED' where tenant_id=? and calendar_code=? and biz_date=?
        """;
    String calendar = "CATCHUP_REAL_" + System.nanoTime();
    String jobCode = seedChildJob("WG_CATCHUP_REAL_" + System.nanoTime());
    DirectWorkflowSeed seed = seedDirectWorkflow(jobCode);
    jdbcTemplate.update(insertCalendarSql, testTenantId, calendar);
    jdbcTemplate.update(updateJobCalendarSql, calendar, testTenantId, jobCode);
    jdbcTemplate.update(
        updateWorkflowCalendarSql,
        calendar,
        testTenantId,
        seed.workflowDefinitionId(),
        testTenantId,
        jobCode);
    LaunchRequest original = LaunchRequest.builder()
        .tenantId(testTenantId)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.API)
        .requestId(seed.requestId())
        .build();
    workflowRunLaunchService.launch(original);
    Long originalRun = workflowRunId(seed.requestId());
    Long originalJob = linkedNodeJobInstance(originalRun, "JOB_NODE_1");
    jdbcTemplate.update(updateJobStatusSql, testTenantId, originalJob);
    jdbcTemplate.update(updateWorkflowStatusSql, testTenantId, originalRun);
    assertThat(jdbcTemplate.update(updateDaySql, testTenantId, calendar, BIZ_DATE))
        .isEqualTo(1);

    batchDaySettleScheduler.settle();

    String dedup = testTenantId + ":batch-day-catchup:" + calendar + ":" + BIZ_DATE + ":" + jobCode;
    Map<String, Object> catchUp = jdbcTemplate.queryForMap(selectCatchUpSql, testTenantId, dedup);
    assertThat(triggerRequestMapper
            .selectCatchUpRootByDedupKey(testTenantId, dedup)
            .getRequestStatus())
        .isEqualTo("LAUNCHED");
    assertThat(catchUp.get("request_status")).isEqualTo("LAUNCHED");
    assertThat(catchUp.get("run_id")).isNotNull();
    assertThat(jdbcTemplate.queryForObject(
            countCatchUpJobSql, Long.class, testTenantId, jobCode, catchUp.get("run_id")))
        .isEqualTo(1L);
    assertThat(jdbcTemplate.update(resetRootStatusSql, testTenantId, dedup)).isEqualTo(1);
    assertThat(triggerRequestMapper
            .selectCatchUpRootByDedupKey(testTenantId, dedup)
            .getRequestStatus())
        .isEqualTo("ACCEPTED");
    // 模拟根请求尚待恢复且子请求已派发的扫描时刻。
    assertThat(jdbcTemplate.update(resetFailedDaySql, testTenantId, calendar, BIZ_DATE))
        .isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject(
            selectDayStatusSql, String.class, testTenantId, calendar, BIZ_DATE))
        .isEqualTo("FAILED");
    assertThat(batchDayInstanceMapper.selectFailedDaysNeedingCatchUp(1000))
        .extracting(day -> day.tenantId())
        .contains(testTenantId);
  }

  @Test
  @DisplayName("旧 WORKFLOW Job wrapper 启动被生产入口拒绝且数据库无运行副作用")
  void legacyWorkflowWrapperLaunch_shouldRejectAndPersistNoExecutionSideEffects() {
    final String updateRequestTargetSql = """
      update batch.trigger_request set job_code = ?, workflow_definition_id = null
      where tenant_id = ? and request_id = ?
  """;
    final String selectRequestStatusSql = """
      select request_status from batch.trigger_request where tenant_id = ? and request_id = ?
  """;
    final String selectCountSql = """
          select count(*) from batch.workflow_run
           where tenant_id = ? and trigger_request_id = (
             select id from batch.trigger_request where tenant_id = ? and request_id = ?
           )
          """;
    final String selectCountSql2 = """
        select count(*) from batch.job_instance
         where tenant_id = ? and trigger_request_id = (
           select id from batch.trigger_request where tenant_id = ? and request_id = ?
         )
        """;
    final String selectCountSql3 = """
      select count(*) from batch.outbox_event where tenant_id = ? and trace_id = ?
  """;

    String workerGroup = "WG_RETIRED_WRAPPER_" + System.nanoTime();
    String childCode = seedChildJob(workerGroup);
    DirectWorkflowSeed seed = seedDirectWorkflow(childCode);
    String wrapperCode = "RETIRED_WRAPPER_" + System.nanoTime();
    jdbcTemplate.update(updateRequestTargetSql, wrapperCode, testTenantId, seed.requestId());
    String traceId = "trace-retired-wrapper-" + System.nanoTime();
    JobDefinitionEntity retiredWrapper = JobDefinitionEntity.builder()
        .tenantId(testTenantId)
        .jobCode(wrapperCode)
        .jobType("WORKFLOW")
        .enabled(true)
        .build();
    Mockito.doReturn(retiredWrapper)
        .when(configCacheService)
        .findEnabledJobDefinition(testTenantId, wrapperCode);

    LaunchRequest request = LaunchRequest.builder()
        .tenantId(testTenantId)
        .jobCode(wrapperCode)
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.API)
        .requestId(seed.requestId())
        .traceId(traceId)
        .params(Map.of("legacyTarget", wrapperCode))
        .build();

    assertThatThrownBy(() -> launchService.launch(request))
        .isInstanceOf(BizException.class)
        .extracting("messageKey")
        .isEqualTo("error.workflow.wrapper_job_retired");

    String selectedRequestStatus = jdbcTemplate.queryForObject(
        selectRequestStatusSql, String.class, testTenantId, seed.requestId());
    assertThat(selectedRequestStatus).isEqualTo("REJECTED");
    Long selectedWorkflowRunCount = jdbcTemplate.queryForObject(
        selectCountSql, Long.class, testTenantId, testTenantId, seed.requestId());
    assertThat(selectedWorkflowRunCount).isZero();
    Long selectedJobInstanceCount = jdbcTemplate.queryForObject(
        selectCountSql2, Long.class, testTenantId, testTenantId, seed.requestId());
    assertThat(selectedJobInstanceCount).isZero();
    Long selectedOutboxEventCount =
        jdbcTemplate.queryForObject(selectCountSql3, Long.class, testTenantId, traceId);
    assertThat(selectedOutboxEventCount).isZero();
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  @ParameterizedTest
  @ValueSource(strings = {"REJECTED", "GIVE_UP", "DRY_RUN"})
  @DisplayName("就绪门闩结束后原请求变为终态或演练身份变化时，T1 不得创建子实例")
  void jobPreparation_shouldRecheckOriginalAfterReadinessGate(String change) {
    String fixtureSelectRunStatusSql =
        "select run_status from batch.workflow_run where tenant_id=? and id=?";
    String fixtureSelectNodeStatusSql =
        "select node_status from batch.workflow_node_run where workflow_run_id=? and node_code='JOB_NODE_1' order by run_seq desc limit 1";

    final String updateTriggerRequestSql = """
      update batch.trigger_request set dry_run=true where tenant_id=? and request_id=?
  """;
    final String updateTriggerRequestSql2 = """
      update batch.trigger_request set request_status=? where tenant_id=? and request_id=?
  """;
    final String selectCountSql = """
      select count(*) from batch.job_instance where tenant_id=? and job_code=?
  """;
    final String selectCountSql2 = """
        select count(*) from batch.job_task task join batch.job_instance job on job.id=task.job_instance_id
        where job.tenant_id=? and job.job_code=?
        """;
    final String selectCountSql3 = """
      select count(*) from batch.workflow_run where tenant_id=? and workflow_definition_id=?
  """;

    String childCode = seedChildJob("WG_T1_REQUEST_" + System.nanoTime());
    DirectWorkflowSeed seed = seedDirectWorkflow(childCode);
    AtomicInteger interventions = new AtomicInteger();
    Mockito.doAnswer(invocation -> {
          Object captured = invocation.callRealMethod();
          LaunchRequest child = invocation.getArgument(0);
          if ("DRY_RUN".equals(change)) {
            jdbcTemplate.update(updateTriggerRequestSql, child.tenantId(), child.requestId());
          } else {
            jdbcTemplate.update(
                updateTriggerRequestSql2, change, child.tenantId(), child.requestId());
          }
          interventions.incrementAndGet();
          return captured;
        })
        .when(jobDependencyWaitingLaunchService)
        .captureOrDefer(ArgumentMatchers.any());
    LaunchRequest request = LaunchRequest.builder()
        .tenantId(testTenantId)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.API)
        .requestId(seed.requestId())
        .build();

    Throwable rejected = Assertions.catchThrowable(() -> workflowRunLaunchService.launch(request));
    if ("REJECTED".equals(change)) {
      // 子请求的业务拒绝由 Workflow 节点消费，根运行以失败终态收敛。
      assertThat(rejected).isNull();
      Long runId = workflowRunId(seed.requestId());
      String selectRunStatusResult13 =
          jdbcTemplate.queryForObject(fixtureSelectRunStatusSql, String.class, testTenantId, runId);
      assertThat(selectRunStatusResult13).isEqualTo("FAILED");
      String selectNodeStatusResult14 =
          jdbcTemplate.queryForObject(fixtureSelectNodeStatusSql, String.class, runId);
      assertThat(selectNodeStatusResult14).isEqualTo("FAILED");
    } else if (rejected instanceof BizException conflict) {
      assertThat(conflict.getCode()).isEqualTo(ResultCode.STATE_CONFLICT);
    } else {
      assertThat(rejected).isInstanceOf(UnexpectedRollbackException.class);
    }

    assertThat(interventions.get()).isEqualTo(1);
    Long selectedJobInstanceCount =
        jdbcTemplate.queryForObject(selectCountSql, Long.class, testTenantId, childCode);
    assertThat(selectedJobInstanceCount).isZero();
    Long selectedJobTaskCount =
        jdbcTemplate.queryForObject(selectCountSql2, Long.class, testTenantId, childCode);
    assertThat(selectedJobTaskCount).isZero();

    // 撤销故障后重复调用：失败终态不复活，其他非终态场景恢复并创建一个子实例。
    Mockito.doCallRealMethod()
        .when(jobDependencyWaitingLaunchService)
        .captureOrDefer(ArgumentMatchers.any());
    workflowRunLaunchService.launch(request);
    Long selectedJobInstanceCountRead2 =
        jdbcTemplate.queryForObject(selectCountSql, Long.class, testTenantId, childCode);
    assertThat(selectedJobInstanceCountRead2).isEqualTo("REJECTED".equals(change) ? 0 : 1);
    if ("REJECTED".equals(change)) {
      String selectRunStatusResult15 = jdbcTemplate.queryForObject(
          fixtureSelectRunStatusSql, String.class, testTenantId, workflowRunId(seed.requestId()));
      assertThat(selectRunStatusResult15).isEqualTo("FAILED");
    }
    Long selectedWorkflowRunCount = jdbcTemplate.queryForObject(
        selectCountSql3, Long.class, testTenantId, seed.workflowDefinitionId());
    assertThat(selectedWorkflowRunCount).isEqualTo(1);
  }

  @Test
  @DisplayName("内部 Job 启动的冲突载荷不能终结其他原请求")
  void jobLaunch_shouldNotRejectMismatchedOriginalRequest() {
    final String selectRequestStatusSql = """
        select request_status from batch.trigger_request where tenant_id=? and request_id=?
    """;
    final String selectChildCountSql = """
        select count(*) from batch.job_instance where tenant_id=? and job_code=?
    """;
    String childCode = seedChildJob("WG_JOB_IDENTITY_" + System.nanoTime());
    DirectWorkflowSeed seed = seedDirectWorkflow(childCode);
    LaunchRequest invalid = LaunchRequest.builder()
        .tenantId(testTenantId)
        .jobCode("missing_" + System.nanoTime())
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.API)
        .requestId(seed.requestId())
        .build();

    assertThatThrownBy(() -> launchService.launch(invalid)).isInstanceOf(BizException.class);
    String status = jdbcTemplate.queryForObject(
        selectRequestStatusSql, String.class, testTenantId, seed.requestId());
    assertThat(status).isEqualTo("ACCEPTED");
    workflowRunLaunchService.launch(invalid.toBuilder().jobCode(null).build());
    String finalStatus = jdbcTemplate.queryForObject(
        selectRequestStatusSql, String.class, testTenantId, seed.requestId());
    assertThat(finalStatus).isEqualTo("LAUNCHED");
    assertThat(
            jdbcTemplate.queryForObject(selectChildCountSql, Long.class, testTenantId, childCode))
        .isEqualTo(1L);
  }

  @Test
  @DisplayName("根请求的演练属性不可被同请求号的不同启动载荷改变")
  void workflowLaunch_shouldRejectDryRunIdentityDriftBeforeCreatingRoot() {
    final String selectCountSql = """
        select count(*) from batch.workflow_run where workflow_definition_id=?
    """;
    final String selectRequestStatusSql = """
        select request_status from batch.trigger_request where tenant_id=? and request_id=?
    """;

    String childCode = seedChildJob("WG_DRY_IDENTITY_" + System.nanoTime());
    DirectWorkflowSeed seed = seedDirectWorkflow(childCode);
    LaunchRequest request = LaunchRequest.builder()
        .tenantId(testTenantId)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.API)
        .requestId(seed.requestId())
        .dryRun(true)
        .build();

    assertThatThrownBy(() -> workflowRunLaunchService.launch(request))
        .isInstanceOf(BizException.class)
        .extracting("messageKey")
        .isEqualTo("error.workflow.trigger_target_mismatch");
    Long selectedWorkflowRunCount =
        jdbcTemplate.queryForObject(selectCountSql, Long.class, seed.workflowDefinitionId());
    assertThat(selectedWorkflowRunCount).isZero();
    String originalStatus = jdbcTemplate.queryForObject(
        selectRequestStatusSql, String.class, testTenantId, seed.requestId());
    assertThat(originalStatus).isEqualTo("ACCEPTED");
    workflowRunLaunchService.launch(request.toBuilder().dryRun(false).build());
    assertThat(jdbcTemplate.queryForObject(selectCountSql, Long.class, seed.workflowDefinitionId()))
        .isEqualTo(1L);
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing", "foreign", "disabled"})
  @DisplayName("失效 JOB 引用在根创建前被拒绝，无运行、任务或 Outbox 副作用")
  void invalidJobReference_shouldRejectBeforeCreatingRuntimeRows(String scenario) {
    final String updateJobDefinitionSql = """
      update batch.job_definition set enabled = false where tenant_id = ? and job_code = ?
  """;
    final String selectRequestStatusSql = """
      select request_status from batch.trigger_request where tenant_id = ? and request_id = ?
  """;
    final String selectCountSql = """
      select count(*) from batch.workflow_run where workflow_definition_id = ?
  """;
    final String selectCountSql2 = """
      select count(*) from batch.job_instance where tenant_id = ? and job_code = ?
  """;
    final String selectCountSql3 = """
      select count(*) from batch.job_task t join batch.job_instance i on i.id=t.job_instance_id\s\
      where i.tenant_id = ? and i.trace_id = ?\
      """;
    final String selectCountSql4 = """
      select count(*) from batch.outbox_event where tenant_id = ? and trace_id = ?
  """;

    String childCode = "MISSING_REF_" + System.nanoTime();
    if (!"missing".equals(scenario)) {
      childCode = seedChildJob(
          "foreign".equals(scenario) ? "t-job-node-other" : testTenantId,
          "WG_REF_" + System.nanoTime());
    }
    if ("disabled".equals(scenario)) {
      jdbcTemplate.update(updateJobDefinitionSql, testTenantId, childCode);
    }
    DirectWorkflowSeed seed = seedDirectWorkflow(childCode);
    String traceId = "trace-invalid-ref-" + System.nanoTime();
    LaunchRequest request = LaunchRequest.builder()
        .tenantId(testTenantId)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.API)
        .requestId(seed.requestId())
        .traceId(traceId)
        .build();

    assertThatThrownBy(() -> workflowRunLaunchService.launch(request))
        .isInstanceOf(BizException.class)
        .extracting("messageKey")
        .isEqualTo("error.workflow.definition_invalid");

    String selectedRequestStatus = jdbcTemplate.queryForObject(
        selectRequestStatusSql, String.class, testTenantId, seed.requestId());
    assertThat(selectedRequestStatus).isEqualTo("REJECTED");
    Long selectedWorkflowRunCount =
        jdbcTemplate.queryForObject(selectCountSql, Long.class, seed.workflowDefinitionId());
    assertThat(selectedWorkflowRunCount).isZero();
    Long selectedJobInstanceCount =
        jdbcTemplate.queryForObject(selectCountSql2, Long.class, testTenantId, childCode);
    assertThat(selectedJobInstanceCount).isZero();
    Long selectedJobTaskCount =
        jdbcTemplate.queryForObject(selectCountSql3, Long.class, testTenantId, traceId);
    assertThat(selectedJobTaskCount).isZero();
    Long selectedOutboxEventCount =
        jdbcTemplate.queryForObject(selectCountSql4, Long.class, testTenantId, traceId);
    assertThat(selectedOutboxEventCount).isZero();
  }

  /** 初始化子任务（单 TASK Worker 的简单任务，空工作流 DAG）。 */
  private String seedChildJob(String workerGroup) {
    return seedChildJob(testTenantId, workerGroup);
  }

  private String seedChildJob(String tenantId, String workerGroup) {
    final String insertJobDefinitionSql = """
          insert into batch.job_definition (
              tenant_id, job_code, job_name, job_type, biz_type, schedule_type, timezone, calendar_code,
              priority, queue_code, worker_group, trigger_mode, dag_enabled, shard_strategy,
              retry_policy, retry_max_count, timeout_seconds, enabled, version
          ) values (?, ?, ?, 'GENERAL', 'IT', 'MANUAL', 'UTC', 'JOB_CALENDAR',
              5, 'q-it', ?, 'API', false, 'NONE',
              'NONE', 0, 0, true, 1)
          returning id
          """;
    final String insertWorkflowDefinitionSql = """
          insert into batch.workflow_definition (
              tenant_id, workflow_code, workflow_name, workflow_type, version, enabled, calendar_code,
              single_job_definition_id
          ) values (?, ?, 'child wf', 'DAG', 1, true, 'JOB_CALENDAR', ?)
          """;
    final String selectIdSql = """
          select id from batch.workflow_definition
          where tenant_id = ? and single_job_definition_id = ?
          """;
    final String insertWorkflowNodeSql = """
          insert into batch.workflow_node (
              tenant_id, workflow_definition_id, node_code, node_name, node_type,
              related_job_code, node_order, retry_policy, retry_max_count, timeout_seconds, enabled
          ) values
              (?, ?, 'START', 'Start', 'START', null, 0, 'NONE', 0, 0, true),
              (?, ?, 'JOB', 'Job', 'JOB', ?, 1, 'NONE', 0, 0, true),
              (?, ?, 'END', 'End', 'END', null, 2, 'NONE', 0, 0, true)
          """;
    final String insertWorkflowEdgeSql = """
          insert into batch.workflow_edge (
              tenant_id, workflow_definition_id, from_node_code, to_node_code, edge_type, enabled
          ) values
              (?, ?, 'START', 'JOB', 'SUCCESS', true),
              (?, ?, 'JOB', 'END', 'SUCCESS', true)
          """;
    final String insertWorkerRegistrySql = """
          insert into batch.worker_registry (
              tenant_id, worker_code, worker_group, capability_tags, status, heartbeat_at, current_load
          ) values (?, ?, ?, '[]'::jsonb, 'ONLINE', now(), 0)
          """;

    String suffix = Long.toUnsignedString(System.nanoTime());
    String childCode = "CHILD_" + suffix;

    Long childJobDefinitionId = jdbcTemplate.queryForObject(
        insertJobDefinitionSql,
        Long.class,
        tenantId,
        childCode,
        "child job " + childCode,
        workerGroup);

    jdbcTemplate.update(
        insertWorkflowDefinitionSql,
        tenantId,
        "JOB-WF-" + childJobDefinitionId,
        childJobDefinitionId);
    Long childWorkflowDefinitionId =
        jdbcTemplate.queryForObject(selectIdSql, Long.class, tenantId, childJobDefinitionId);
    jdbcTemplate.update(
        insertWorkflowNodeSql,
        tenantId,
        childWorkflowDefinitionId,
        tenantId,
        childWorkflowDefinitionId,
        childCode,
        tenantId,
        childWorkflowDefinitionId);
    jdbcTemplate.update(
        insertWorkflowEdgeSql,
        tenantId,
        childWorkflowDefinitionId,
        tenantId,
        childWorkflowDefinitionId);

    jdbcTemplate.update(insertWorkerRegistrySql, tenantId, "wk-child-" + suffix, workerGroup);

    return childCode;
  }

  /** 快照历史仍须使用有效的根请求、子请求及租户拥有的 Job 关联。 */
  private ParentSeed seedParentJob(String childJobCode) {
    final String insertWorkflowDefinitionSql = """
              insert into batch.workflow_definition (
                  tenant_id, workflow_code, workflow_name, workflow_type, version, enabled, calendar_code
              ) values (?, ?, 'parent wf', 'DAG', 1, true, 'JOB_CALENDAR')
              returning id
              """;
    final String insertWorkflowNodeSql = """
          insert into batch.workflow_node (
              tenant_id, workflow_definition_id, node_code, node_name, node_type,
              related_job_code, node_order, retry_policy, retry_max_count,
              timeout_seconds, enabled
          ) values
              (?, ?, 'START', 'Start', 'START', null, 0, 'NONE', 0, 0, true),
              (?, ?, 'JOB_NODE_1', 'Child Job Node', 'JOB', ?, 1, 'NONE', 0, 0, true),
              (?, ?, 'END', 'End', 'END', null, 2, 'NONE', 0, 0, true)
          """;
    final String insertWorkflowEdgeSql = """
          insert into batch.workflow_edge (
              tenant_id, workflow_definition_id, from_node_code, to_node_code, edge_type, enabled
          ) values
              (?, ?, 'START', 'JOB_NODE_1', 'ALWAYS', true),
              (?, ?, 'JOB_NODE_1', 'END', 'SUCCESS', true)
          """;
    final String insertTriggerRequestSql = """
          insert into batch.trigger_request (
              tenant_id, request_id, trigger_type, workflow_definition_id, biz_date,
              dedup_key, request_status, trace_id
          ) values (?, ?, 'API', ?, ?, ?, 'ACCEPTED', 'trace-parent')
          """;

    String suffix = Long.toUnsignedString(System.nanoTime());
    String parentCode = "PARENT_" + suffix;
    String requestId = "req-parent-" + suffix;

    Long parentWfDefId = jdbcTemplate.queryForObject(
        insertWorkflowDefinitionSql, Long.class, testTenantId, parentCode);

    jdbcTemplate.update(
        insertWorkflowNodeSql,
        testTenantId,
        parentWfDefId,
        testTenantId,
        parentWfDefId,
        childJobCode,
        testTenantId,
        parentWfDefId);

    jdbcTemplate.update(
        insertWorkflowEdgeSql, testTenantId, parentWfDefId, testTenantId, parentWfDefId);

    jdbcTemplate.update(
        insertTriggerRequestSql,
        testTenantId,
        requestId,
        parentWfDefId,
        BIZ_DATE,
        "dedup-parent-" + suffix);

    return new ParentSeed(parentWfDefId, requestId);
  }

  private record ParentSeed(Long workflowDefinitionId, String requestId) {}

  private Long workflowRunId(String requestId) {
    final String selectIdSql = """
        select run.id from batch.workflow_run run\s\
        join batch.trigger_request request on request.tenant_id = run.tenant_id\s\
        and request.id = run.trigger_request_id where run.tenant_id = ? and request.request_id = ?\
        """;

    return jdbcTemplate.queryForObject(selectIdSql, Long.class, testTenantId, requestId);
  }

  private record DirectWorkflowSeed(Long workflowDefinitionId, String requestId, String dedupKey) {}

  private DirectWorkflowSeed seedDirectWorkflow(String childJobCode) {
    return seedDirectWorkflow(testTenantId, childJobCode);
  }

  private DirectWorkflowSeed seedDirectWorkflow(String tenantId, String childJobCode) {
    return seedDirectWorkflow(tenantId, childJobCode, BIZ_DATE);
  }

  private DirectWorkflowSeed seedDirectWorkflow(
      String tenantId, String childJobCode, LocalDate bizDate) {
    final String insertWorkflowDefinitionSql = """
          insert into batch.workflow_definition (
              tenant_id, workflow_code, workflow_name, workflow_type, version, enabled, calendar_code
          ) values (?, ?, 'direct workflow', 'DAG', 3, true, 'WF_CALENDAR')
          returning id
          """;
    final String insertWorkflowNodeSql = """
          insert into batch.workflow_node (
              tenant_id, workflow_definition_id, node_code, node_name, node_type,
              related_job_code, node_order, retry_policy, retry_max_count, timeout_seconds, enabled
          ) values
              (?, ?, 'START', 'Start', 'START', null, 0, 'NONE', 0, 0, true),
              (?, ?, 'JOB_NODE_1', 'Child Job Node', 'JOB', ?, 1, 'NONE', 0, 0, true),
              (?, ?, 'END', 'End', 'END', null, 2, 'NONE', 0, 0, true)
          """;
    final String insertWorkflowEdgeSql = """
          insert into batch.workflow_edge (
              tenant_id, workflow_definition_id, from_node_code, to_node_code, edge_type, enabled
          ) values
              (?, ?, 'START', 'JOB_NODE_1', 'ALWAYS', true),
              (?, ?, 'JOB_NODE_1', 'END', 'SUCCESS', true)
          """;
    final String insertTriggerRequestSql = """
          insert into batch.trigger_request (
              tenant_id, request_id, trigger_type, job_code, workflow_definition_id, biz_date,
              dedup_key, request_status, trace_id
          ) values (?, ?, 'API', null, ?, ?, ?, 'ACCEPTED', ?)
          """;

    String suffix = Long.toUnsignedString(System.nanoTime());
    String requestId = "req-direct-wf-" + suffix;
    String dedupKey = "dedup-direct-wf-" + suffix;
    Long workflowDefinitionId = jdbcTemplate.queryForObject(
        insertWorkflowDefinitionSql, Long.class, tenantId, "DIRECT_WF_" + suffix);
    jdbcTemplate.update(
        insertWorkflowNodeSql,
        tenantId,
        workflowDefinitionId,
        tenantId,
        workflowDefinitionId,
        childJobCode,
        tenantId,
        workflowDefinitionId);
    jdbcTemplate.update(
        insertWorkflowEdgeSql, tenantId, workflowDefinitionId, tenantId, workflowDefinitionId);
    jdbcTemplate.update(
        insertTriggerRequestSql,
        tenantId,
        requestId,
        workflowDefinitionId,
        bizDate,
        dedupKey,
        "trace-direct-wf-" + suffix);
    return new DirectWorkflowSeed(workflowDefinitionId, requestId, dedupKey);
  }

  private WorkflowRunEntity seedRootlessWorkflow(String childJobCode) {
    final String insertWorkflowDefinitionSql = """
          insert into batch.workflow_definition (
              tenant_id, workflow_code, workflow_name, workflow_type, version, enabled, calendar_code
          ) values (?, ?, 'rootless workflow', 'DAG', 1, true, 'WF_CALENDAR')
          returning id
          """;
    final String insertWorkflowNodeSql = """
          insert into batch.workflow_node (
              tenant_id, workflow_definition_id, node_code, node_name, node_type,
              related_job_code, node_order, retry_policy, retry_max_count, timeout_seconds, enabled
          ) values
              (?, ?, 'START', 'Start', 'START', null, 0, 'NONE', 0, 0, true),
              (?, ?, 'JOB_NODE_1', 'Child Job Node', 'JOB', ?, 1, 'NONE', 0, 0, true),
              (?, ?, 'END', 'End', 'END', null, 2, 'NONE', 0, 0, true)
          """;
    final String insertWorkflowEdgeSql = """
          insert into batch.workflow_edge (
              tenant_id, workflow_definition_id, from_node_code, to_node_code, edge_type, enabled
          ) values
              (?, ?, 'START', 'JOB_NODE_1', 'ALWAYS', true),
              (?, ?, 'JOB_NODE_1', 'END', 'SUCCESS', true)
          """;
    final String insertWorkflowNodeRunSql = """
          insert into batch.workflow_node_run (
              workflow_run_id, node_code, node_type, run_seq, node_status, retry_count,
              duration_ms, started_at, finished_at
          ) values (?, 'START', 'START', 1, 'SUCCESS', 0, 0, now(), now())
          """;

    String suffix = Long.toUnsignedString(System.nanoTime());
    Long workflowDefinitionId = jdbcTemplate.queryForObject(
        insertWorkflowDefinitionSql, Long.class, testTenantId, "ROOTLESS_WF_" + suffix);
    jdbcTemplate.update(
        insertWorkflowNodeSql,
        testTenantId,
        workflowDefinitionId,
        testTenantId,
        workflowDefinitionId,
        childJobCode,
        testTenantId,
        workflowDefinitionId);
    jdbcTemplate.update(
        insertWorkflowEdgeSql,
        testTenantId,
        workflowDefinitionId,
        testTenantId,
        workflowDefinitionId);
    WorkflowRunEntity run = new WorkflowRunEntity();
    run.setTenantId(testTenantId);
    run.setWorkflowDefinitionId(workflowDefinitionId);
    run.setBizDate(BIZ_DATE);
    run.setRunStatus("RUNNING");
    run.setCurrentNodeCode("JOB_NODE_1");
    run.setTraceId("trace-rootless-" + suffix);
    run.setDryRun(false);
    workflowRunMapper.insert(run);
    jdbcTemplate.update(insertWorkflowNodeRunSql, run.getId());
    return run;
  }

  @Test
  @DisplayName("人工 skip 不伪造条件输出，并持久化未选分支后终结 rootless Workflow")
  void manualSkip_shouldPersistUnselectedConditionBranchAndFinalizeRootlessWorkflow() {
    final String updateWorkflowNodeSql = """
          update batch.workflow_node set node_order = 3
          where workflow_definition_id = ? and node_code = 'END'
          """;
    final String insertWorkflowNodeSql = """
          insert into batch.workflow_node (
              tenant_id, workflow_definition_id, node_code, node_name, node_type,
              related_job_code, node_order, retry_policy, retry_max_count, timeout_seconds, enabled
          ) values (?, ?, 'CONDITION_BRANCH', 'Conditional Job', 'JOB', ?, 2, 'NONE', 0, 0, true)
          """;
    final String insertWorkflowEdgeSql = """
          insert into batch.workflow_edge (
              tenant_id, workflow_definition_id, from_node_code, to_node_code, edge_type,
              condition_expr, enabled
          ) values (?, ?, 'JOB_NODE_1', 'CONDITION_BRANCH', 'CONDITION', 'route = ''A''', true)
          """;
    final String insertWorkflowNodeRunSql = """
          insert into batch.workflow_node_run (
              workflow_run_id, node_code, node_type, run_seq, node_status, retry_count,
              duration_ms, started_at, finished_at
          ) values (?, 'JOB_NODE_1', 'JOB', 1, 'FAILED', 0, 0, now(), now())
          """;
    final String selectNodeStatusSql = """
          select node_status from batch.workflow_node_run
          where workflow_run_id = ? and node_code = 'JOB_NODE_1' order by run_seq desc limit 1
          """;
    final String selectSkipReasonCodeSql = """
          select skip_reason_code from batch.workflow_node_run
          where workflow_run_id = ? and node_code = 'CONDITION_BRANCH' order by run_seq desc limit 1
          """;
    final String selectCountSql = """
          select count(*) from batch.workflow_node_run
          where workflow_run_id = ? and node_code = 'CONDITION_BRANCH'
            and job_instance_id is not null
          """;
    final String selectRunStatusSql = """
          select run_status from batch.workflow_run where tenant_id = ? and id = ?
          """;
    final String selectCountSql2 = """
        select count(*) from batch.outbox_event
        where tenant_id = ? and aggregate_type = 'WORKFLOW_RUN'
          and aggregate_id = ? and event_type = 'WORKFLOW_TERMINAL'
        """;

    String workerGroup = "WG_R29_SKIP_CONDITION_" + System.nanoTime();
    String childCode = seedChildJob(workerGroup);
    WorkflowRunEntity run = seedRootlessWorkflow(childCode);
    jdbcTemplate.update(updateWorkflowNodeSql, run.getWorkflowDefinitionId());
    jdbcTemplate.update(
        insertWorkflowNodeSql, testTenantId, run.getWorkflowDefinitionId(), childCode);
    jdbcTemplate.update(insertWorkflowEdgeSql, testTenantId, run.getWorkflowDefinitionId());
    jdbcTemplate.update(insertWorkflowNodeRunSql, run.getId());

    workflowRunManagementApplicationService.skipNode(
        testTenantId, run.getId(), "JOB_NODE_1", "operator-r29-condition", "bypass failed job");

    String selectedNodeStatus =
        jdbcTemplate.queryForObject(selectNodeStatusSql, String.class, run.getId());
    assertThat(selectedNodeStatus).isEqualTo("SKIPPED");
    String selectedSkipReasonCode =
        jdbcTemplate.queryForObject(selectSkipReasonCodeSql, String.class, run.getId());
    assertThat(selectedSkipReasonCode).isEqualTo("UPSTREAM_SKIPPED");
    Long selectedWorkflowNodeRunCount =
        jdbcTemplate.queryForObject(selectCountSql, Long.class, run.getId());
    assertThat(selectedWorkflowNodeRunCount).isZero();
    String selectedRunStatus =
        jdbcTemplate.queryForObject(selectRunStatusSql, String.class, testTenantId, run.getId());
    assertThat(selectedRunStatus).isEqualTo("SUCCESS");
    Long selectedOutboxEventCount =
        jdbcTemplate.queryForObject(selectCountSql2, Long.class, testTenantId, run.getId());
    assertThat(selectedOutboxEventCount).isEqualTo(1L);
  }

  @Test
  @DisplayName("人工 skip 只派发 SUCCESS/ALWAYS 边，并持久化跳过 FAILURE/条件分支")
  void manualSkip_shouldDispatchSuccessAndAlwaysButSkipFailureAndConditionBranches() {
    final String updateWorkflowNodeSql = """
          update batch.workflow_node set node_order = 6
          where workflow_definition_id = ? and node_code = 'END'
          """;
    final String insertWorkflowNodeSql = """
          insert into batch.workflow_node (
              tenant_id, workflow_definition_id, node_code, node_name, node_type,
              related_job_code, node_order, retry_policy, retry_max_count, timeout_seconds, enabled
          ) values
              (?, ?, 'SUCCESS_BRANCH', 'Success Branch', 'JOB', ?, 2, 'NONE', 0, 0, true),
              (?, ?, 'ALWAYS_BRANCH', 'Always Branch', 'JOB', ?, 3, 'NONE', 0, 0, true),
              (?, ?, 'FAILURE_BRANCH', 'Failure Branch', 'JOB', ?, 4, 'NONE', 0, 0, true),
              (?, ?, 'CONDITION_BRANCH', 'Conditional Branch', 'JOB', ?, 5, 'NONE', 0, 0, true)
          """;
    final String insertWorkflowEdgeSql = """
          insert into batch.workflow_edge (
              tenant_id, workflow_definition_id, from_node_code, to_node_code, edge_type,
              condition_expr, enabled
          ) values
              (?, ?, 'JOB_NODE_1', 'SUCCESS_BRANCH', 'SUCCESS', null, true),
              (?, ?, 'JOB_NODE_1', 'ALWAYS_BRANCH', 'ALWAYS', null, true),
              (?, ?, 'JOB_NODE_1', 'FAILURE_BRANCH', 'FAILURE', null, true),
              (?, ?, 'JOB_NODE_1', 'CONDITION_BRANCH', 'CONDITION', 'route = ''A''', true),
              (?, ?, 'SUCCESS_BRANCH', 'END', 'SUCCESS', null, true),
              (?, ?, 'ALWAYS_BRANCH', 'END', 'SUCCESS', null, true),
              (?, ?, 'FAILURE_BRANCH', 'END', 'SUCCESS', null, true),
              (?, ?, 'CONDITION_BRANCH', 'END', 'SUCCESS', null, true)
          """;
    final String updateWorkflowNodeRunSql = """
          update batch.workflow_node_run set node_status = 'FAILED', finished_at = now()
          where workflow_run_id = ? and node_code = 'JOB_NODE_1'
          """;
    final String selectNodeCodeSql = """
          select node_code, node_status, job_instance_id
          from batch.workflow_node_run
          where workflow_run_id = ?
            and node_code in ('SUCCESS_BRANCH', 'ALWAYS_BRANCH', 'FAILURE_BRANCH', 'CONDITION_BRANCH')
          order by node_code
          """;
    final String selectCountSql = """
          select count(*) from batch.workflow_node_run
          where workflow_run_id = ?
            and node_code in ('SUCCESS_BRANCH', 'ALWAYS_BRANCH')
            and job_instance_id is not null
          """;
    final String selectCountSql2 = """
        select count(*) from batch.workflow_node_run
        where workflow_run_id = ?
          and node_code in ('FAILURE_BRANCH', 'CONDITION_BRANCH')
          and job_instance_id is not null
        """;
    final String selectCountSql3 = """
        select count(*) from batch.workflow_node_run
        where workflow_run_id = ?
          and node_code in ('FAILURE_BRANCH', 'CONDITION_BRANCH')
          and skip_reason_code = 'UPSTREAM_SKIPPED'
        """;
    final String selectRunStatusSql = """
          select run_status from batch.workflow_run where tenant_id = ? and id = ?
          """;
    final String selectCountSql4 = """
        select count(*) from batch.outbox_event
        where tenant_id = ? and aggregate_type = 'WORKFLOW_RUN'
          and aggregate_id = ? and event_type = 'WORKFLOW_TERMINAL'
        """;

    String workerGroup = "WG_R29_SKIP_EDGE_TYPES_" + System.nanoTime();
    String childCode = seedChildJob(workerGroup);
    DirectWorkflowSeed seed = seedDirectWorkflow(childCode);
    jdbcTemplate.update(updateWorkflowNodeSql, seed.workflowDefinitionId());
    jdbcTemplate.update(
        insertWorkflowNodeSql,
        testTenantId,
        seed.workflowDefinitionId(),
        childCode,
        testTenantId,
        seed.workflowDefinitionId(),
        childCode,
        testTenantId,
        seed.workflowDefinitionId(),
        childCode,
        testTenantId,
        seed.workflowDefinitionId(),
        childCode);
    jdbcTemplate.update(
        insertWorkflowEdgeSql,
        testTenantId,
        seed.workflowDefinitionId(),
        testTenantId,
        seed.workflowDefinitionId(),
        testTenantId,
        seed.workflowDefinitionId(),
        testTenantId,
        seed.workflowDefinitionId(),
        testTenantId,
        seed.workflowDefinitionId(),
        testTenantId,
        seed.workflowDefinitionId(),
        testTenantId,
        seed.workflowDefinitionId(),
        testTenantId,
        seed.workflowDefinitionId());
    LaunchRequest launchRequest = LaunchRequest.builder()
        .tenantId(testTenantId)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.API)
        .requestId(seed.requestId())
        .traceId("trace-r29-skip-edge-types-" + System.nanoTime())
        .params(Map.of())
        .build();
    workflowRunLaunchService.launch(launchRequest);
    Long workflowRunId = workflowRunId(seed.requestId());
    jdbcTemplate.update(updateWorkflowNodeRunSql, workflowRunId);

    workflowRunManagementApplicationService.skipNode(
        testTenantId, workflowRunId, "JOB_NODE_1", "operator-r29-edge-types", "approved bypass");

    List<Map<String, Object>> selectedNodeCodeValues =
        jdbcTemplate.queryForList(selectNodeCodeSql, workflowRunId);
    assertThat(selectedNodeCodeValues)
        .extracting(row -> row.get("node_code"), row -> row.get("node_status"))
        .containsExactly(
            Tuple.tuple("ALWAYS_BRANCH", "READY"),
            Tuple.tuple("CONDITION_BRANCH", "SKIPPED"),
            Tuple.tuple("FAILURE_BRANCH", "SKIPPED"),
            Tuple.tuple("SUCCESS_BRANCH", "READY"));
    Long selectedWorkflowNodeRunCount =
        jdbcTemplate.queryForObject(selectCountSql, Long.class, workflowRunId);
    assertThat(selectedWorkflowNodeRunCount).isEqualTo(2L);
    Long selectedWorkflowNodeRunCountRead2 =
        jdbcTemplate.queryForObject(selectCountSql2, Long.class, workflowRunId);
    assertThat(selectedWorkflowNodeRunCountRead2).isZero();
    Long selectedWorkflowNodeRunCountRead3 =
        jdbcTemplate.queryForObject(selectCountSql3, Long.class, workflowRunId);
    assertThat(selectedWorkflowNodeRunCountRead3).isEqualTo(2L);
    String selectedRunStatus =
        jdbcTemplate.queryForObject(selectRunStatusSql, String.class, testTenantId, workflowRunId);
    assertThat(selectedRunStatus).isEqualTo("RUNNING");
    Long selectedOutboxEventCount =
        jdbcTemplate.queryForObject(selectCountSql4, Long.class, testTenantId, workflowRunId);
    assertThat(selectedOutboxEventCount).isZero();
  }

  // ── tests ─────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("Workflow 根运行启动 Job 节点且不创建父 Job 或虚拟分区")
  void workflowRootWithJobNode_shouldLaunchChildInstanceWithoutSyntheticParent() {
    final String selectRelatedJobInstanceIdSql = """
        select related_job_instance_id from batch.workflow_run where id = ?
    """;
    final String selectCountSql = """
        select count(*) from batch.job_instance where tenant_id = ? and job_code = ?
    """;
    final String selectBizDateSql = """
        select biz_date from batch.job_instance where tenant_id = ? and job_code = ?
    """;
    final String selectDataIntervalStartSql = """
      select data_interval_start from batch.job_instance where tenant_id = ? and job_code = ?
  """;
    final String selectDataIntervalEndSql = """
      select data_interval_end from batch.job_instance where tenant_id = ? and job_code = ?
  """;
    final String selectIdSql = """
          select id from batch.job_instance where tenant_id = ? and job_code = ?
          """;
    final String selectCountSql2 = """
        select count(*) from batch.workflow_run wr
        where wr.related_job_instance_id = ?
        """;
    final String selectCountSql3 = """
        select count(*)
        from batch.workflow_node_run node_run
        join batch.workflow_run workflow_run on workflow_run.id = node_run.workflow_run_id
        where workflow_run.id = ?
          and node_run.node_code = 'JOB_NODE_1'
          and node_run.job_instance_id = ?
        """;
    final String selectCountSql4 = """
        select count(*) from batch.job_task where job_instance_id = ?
        """;
    final String selectCountSql5 = """
            select count(*)
            from batch.workflow_node_run wnr
            join batch.workflow_run wr on wr.id = wnr.workflow_run_id
            where wr.id = ?
              and wnr.node_code = 'JOB_NODE_1'
              and wnr.node_status = 'READY'
            """;

    String workerGroup = "WG_JN_" + System.nanoTime();
    String childCode = seedChildJob(workerGroup);
    ParentSeed parent = seedParentJob(childCode);
    Instant intervalStart = Instant.parse("2026-02-28T23:30:00Z");
    Instant intervalEnd = Instant.parse("2026-03-01T01:30:00Z");

    LaunchRequest launchRequest = LaunchRequest.builder()
        .tenantId(testTenantId)
        .workflowDefinitionId(parent.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .dataIntervalStart(intervalStart)
        .dataIntervalEnd(intervalEnd)
        .triggerType(TriggerType.API)
        .requestId(parent.requestId())
        .traceId("trace-job-node-1")
        .params(Map.of())
        .build();
    LaunchResponse response = workflowRunLaunchService.launch(launchRequest);

    assertThat(response.instanceNo()).isNotBlank();

    Long parentWorkflowRunId = workflowRunId(parent.requestId());
    assertThat(parentWorkflowRunId).isNotNull();
    Long selectedRelatedJobInstanceId =
        jdbcTemplate.queryForObject(selectRelatedJobInstanceIdSql, Long.class, parentWorkflowRunId);
    assertThat(selectedRelatedJobInstanceId).isNull();

    // 子 job_instance 已自动创建
    Long childInstanceCount =
        jdbcTemplate.queryForObject(selectCountSql, Long.class, testTenantId, childCode);
    assertThat(childInstanceCount).isEqualTo(1L);

    LocalDate selectedBizDate =
        jdbcTemplate.queryForObject(selectBizDateSql, LocalDate.class, testTenantId, childCode);
    Assertions.assertThat(selectedBizDate)
        .as("the child execution inherits the root's frozen business date")
        .isEqualTo(BIZ_DATE);
    Timestamp selectedDataIntervalStart = jdbcTemplate.queryForObject(
        selectDataIntervalStartSql, Timestamp.class, testTenantId, childCode);
    assertThat(selectedDataIntervalStart)
        .as("the child execution keeps the original interval start across midnight")
        .isEqualTo(Timestamp.from(intervalStart));
    Timestamp selectedDataIntervalEnd = jdbcTemplate.queryForObject(
        selectDataIntervalEndSql, Timestamp.class, testTenantId, childCode);
    assertThat(selectedDataIntervalEnd)
        .as("the child execution keeps the original interval end across midnight")
        .isEqualTo(Timestamp.from(intervalEnd));

    Long childInstanceId =
        jdbcTemplate.queryForObject(selectIdSql, Long.class, testTenantId, childCode);
    Long childWorkflowRunCount =
        jdbcTemplate.queryForObject(selectCountSql2, Long.class, childInstanceId);
    assertThat(childWorkflowRunCount)
        .as("a regular Job node belongs to the parent Workflow, not a nested single-Job Workflow")
        .isZero();
    Long childNodeLinkCount = jdbcTemplate.queryForObject(
        selectCountSql3, Long.class, parentWorkflowRunId, childInstanceId);
    assertThat(childNodeLinkCount).isEqualTo(1L);
    Long childBusinessTaskCount =
        jdbcTemplate.queryForObject(selectCountSql4, Long.class, childInstanceId);
    assertThat(childBusinessTaskCount).isEqualTo(1L);

    // 父 workflow_node_run 中 JOB_NODE_1 为 READY 状态
    Long nodeRunCount =
        jdbcTemplate.queryForObject(selectCountSql5, Long.class, parentWorkflowRunId);
    assertThat(nodeRunCount).isEqualTo(1L);
  }

  @Test
  @DisplayName("显式 Workflow 目标创建唯一无根 Workflow Run,直接派发 Job 节点且重投不重复建实例")
  void explicitWorkflowLaunch_shouldCreateRootRunAndDeduplicateNodeDispatch() {
    final String deleteWorkflowEdgeSql = """
        delete from batch.workflow_edge where workflow_definition_id = ?
    """;
    final String updateWorkflowDefinitionSql = """
      update batch.workflow_definition set version = version + 1 where id = ?
  """;
    final String selectIdSql = """
      select id from batch.trigger_request where tenant_id = ? and request_id = ?
  """;
    final String selectCountSql = """
      select count(*) from batch.workflow_run where tenant_id = ? and trigger_request_id = (select id\s\
      from batch.trigger_request where tenant_id = ? and request_id = ?)\
      """;
    final String selectCountSql2 = """
      select count(*) from batch.job_instance where tenant_id = ? and job_code = ?
  """;
    final String selectReplaySessionIdSql = """
        select replay_session_id from batch.job_instance where tenant_id = ? and job_code = ?
    """;
    final String selectRequestStatusSql = """
        select request_status from batch.trigger_request where tenant_id = ? and request_id = ?
    """;

    String workerGroup = "WG_ROOT_WF_" + System.nanoTime();
    String childCode = seedChildJob(workerGroup);
    DirectWorkflowSeed seed = seedDirectWorkflow(childCode);
    LaunchRequest request = LaunchRequest.builder()
        .tenantId(testTenantId)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.API)
        .requestId(seed.requestId())
        .traceId("trace-direct-wf")
        .replaySessionId(987654321L)
        .params(Map.of("source", "workflow-trigger"))
        .build();

    LaunchResponse first = workflowRunLaunchService.launch(request);
    jdbcTemplate.update(deleteWorkflowEdgeSql, seed.workflowDefinitionId());
    jdbcTemplate.update(updateWorkflowDefinitionSql, seed.workflowDefinitionId());
    LaunchResponse replay = workflowRunLaunchService.launch(request);

    assertThat(replay.instanceNo()).isEqualTo(first.instanceNo());
    WorkflowRunEntity run = workflowRunMapper.selectByTenantAndTriggerRequestId(
        testTenantId,
        jdbcTemplate.queryForObject(selectIdSql, Long.class, testTenantId, seed.requestId()));
    assertThat(run).isNotNull();
    assertThat(run.getRelatedJobInstanceId()).isNull();
    assertThat(run.getWorkflowVersionSnapshot()).isEqualTo(3);
    assertThat(run.getExecutionSnapshot())
        .contains("\"workflowVersion\": 3")
        .contains("\"replaySessionId\": 987654321")
        .contains("\"nodeCode\": \"JOB_NODE_1\"");
    Long selectedWorkflowRunCount = jdbcTemplate.queryForObject(
        selectCountSql, Long.class, testTenantId, testTenantId, seed.requestId());
    assertThat(selectedWorkflowRunCount).isEqualTo(1L);
    Long selectedJobInstanceCount =
        jdbcTemplate.queryForObject(selectCountSql2, Long.class, testTenantId, childCode);
    assertThat(selectedJobInstanceCount).isEqualTo(1L);
    Long selectedReplaySessionId =
        jdbcTemplate.queryForObject(selectReplaySessionIdSql, Long.class, testTenantId, childCode);
    assertThat(selectedReplaySessionId).isEqualTo(987654321L);
    String selectedRequestStatus = jdbcTemplate.queryForObject(
        selectRequestStatusSql, String.class, testTenantId, seed.requestId());
    assertThat(selectedRequestStatus).isEqualTo("LAUNCHED");
  }

  @Test
  @DisplayName("Gateway 条件未命中的分支持久化跳过状态和原因")
  void directWorkflow_shouldRecordWhyConditionalBranchWasNotSelected() {
    final String updateWorkflowNodeSql = """
          update batch.workflow_node
          set node_order = 3
          where workflow_definition_id = ? and node_code = 'END'
          """;
    final String insertWorkflowNodeSql = """
          insert into batch.workflow_node (
              tenant_id, workflow_definition_id, node_code, node_name, node_type,
              related_job_code, node_order, retry_policy, retry_max_count, timeout_seconds, enabled
          ) values (?, ?, 'ROUTE', 'Route', 'GATEWAY', null, 1, 'NONE', 0, 0, true),
                   (?, ?, 'JOB_NODE_2', 'Unselected Job', 'JOB', ?, 2, 'NONE', 0, 0, true)
          """;
    final String deleteWorkflowEdgeSql = """
        delete from batch.workflow_edge where workflow_definition_id = ?
    """;
    final String insertWorkflowEdgeSql = """
          insert into batch.workflow_edge (
              tenant_id, workflow_definition_id, from_node_code, to_node_code, edge_type,
              condition_expr, enabled
          ) values
              (?, ?, 'START', 'ROUTE', 'ALWAYS', null, true),
              (?, ?, 'ROUTE', 'JOB_NODE_1', 'CONDITION', 'route = ''A''', true),
              (?, ?, 'ROUTE', 'JOB_NODE_2', 'CONDITION', 'route = ''B''', true),
              (?, ?, 'JOB_NODE_1', 'END', 'SUCCESS', null, true),
              (?, ?, 'JOB_NODE_2', 'END', 'SUCCESS', null, true)
          """;
    final String selectNodeStatusSql = """
          select node_status from batch.workflow_node_run
          where workflow_run_id = ? and node_code = 'JOB_NODE_2'
          order by run_seq desc limit 1
          """;
    final String selectSkipReasonCodeSql = """
          select skip_reason_code from batch.workflow_node_run
          where workflow_run_id = ? and node_code = 'JOB_NODE_2'
          order by run_seq desc limit 1
          """;
    final String selectCountSql = """
        select count(*) from batch.job_instance where tenant_id = ? and job_code = ?
    """;

    String workerGroup = "WG_ROOT_CONDITION_" + System.nanoTime();
    String selectedJobCode = seedChildJob(workerGroup);
    String unselectedJobCode = seedChildJob(workerGroup);
    DirectWorkflowSeed seed = seedDirectWorkflow(selectedJobCode);
    jdbcTemplate.update(updateWorkflowNodeSql, seed.workflowDefinitionId());
    jdbcTemplate.update(
        insertWorkflowNodeSql,
        testTenantId,
        seed.workflowDefinitionId(),
        testTenantId,
        seed.workflowDefinitionId(),
        unselectedJobCode);
    jdbcTemplate.update(deleteWorkflowEdgeSql, seed.workflowDefinitionId());
    jdbcTemplate.update(
        insertWorkflowEdgeSql,
        testTenantId,
        seed.workflowDefinitionId(),
        testTenantId,
        seed.workflowDefinitionId(),
        testTenantId,
        seed.workflowDefinitionId(),
        testTenantId,
        seed.workflowDefinitionId(),
        testTenantId,
        seed.workflowDefinitionId());

    LaunchRequest request = LaunchRequest.builder()
        .tenantId(testTenantId)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.API)
        .requestId(seed.requestId())
        .traceId("trace-root-condition-branch")
        .params(Map.of("route", "A"))
        .build();
    workflowRunLaunchService.launch(request);

    Long workflowRunId = workflowRunId(seed.requestId());
    assertThat(linkedNodeJobInstance(workflowRunId, "JOB_NODE_1")).isNotNull();
    String selectedNodeStatus =
        jdbcTemplate.queryForObject(selectNodeStatusSql, String.class, workflowRunId);
    assertThat(selectedNodeStatus).isEqualTo("SKIPPED");
    String selectedSkipReasonCode =
        jdbcTemplate.queryForObject(selectSkipReasonCodeSql, String.class, workflowRunId);
    assertThat(selectedSkipReasonCode).isEqualTo("CONDITION_NOT_MATCHED");
    Long selectedJobInstanceCount =
        jdbcTemplate.queryForObject(selectCountSql, Long.class, testTenantId, unselectedJobCode);
    assertThat(selectedJobInstanceCount).isZero();
  }

  @Test
  @DisplayName("根 Workflow 与 GATEWAY 不占租户 Job 配额，仅实际 JOB 节点进入资源准入")
  void rootAndGateway_shouldNotConsumeTenantJobQuota() {
    final String insertTenantQuotaPolicySql = """
          insert into batch.tenant_quota_policy (
              tenant_id, policy_code, max_running_jobs_per_tenant, max_partitions_per_tenant,
              max_qps_per_tenant, fair_share_weight, enabled, description, burst_limit,
              partition_burst_limit, quota_reset_policy, group_shared_max_running_jobs,
              exceeded_strategy
          ) values (?, ?, 1, 0, 0, 1, true, 'R21 root and control node quota test',
                    0, 0, 'NONE', 0, 'QUEUE_DEFER')
          """;
    final String updateWorkflowNodeSql = """
      update batch.workflow_node set node_order = 2 where tenant_id = ? and workflow_definition_id = ?\s\
      and node_code = 'JOB_NODE_1'\
      """;
    final String updateWorkflowNodeSql2 = """
      update batch.workflow_node set node_order = 3 where tenant_id = ? and workflow_definition_id = ?\s\
      and node_code = 'END'\
      """;
    final String insertWorkflowNodeSql = """
          insert into batch.workflow_node (
              tenant_id, workflow_definition_id, node_code, node_name, node_type,
              related_job_code, node_order, retry_policy, retry_max_count, timeout_seconds, enabled
          ) values (?, ?, 'ROUTE', 'Route', 'GATEWAY', null, 1, 'NONE', 0, 0, true)
          """;
    final String deleteWorkflowEdgeSql = """
        delete from batch.workflow_edge where tenant_id = ? and workflow_definition_id = ?
    """;
    final String insertWorkflowEdgeSql = """
          insert into batch.workflow_edge (
              tenant_id, workflow_definition_id, from_node_code, to_node_code, edge_type, enabled
          ) values
              (?, ?, 'START', 'ROUTE', 'ALWAYS', true),
              (?, ?, 'ROUTE', 'JOB_NODE_1', 'ALWAYS', true),
              (?, ?, 'JOB_NODE_1', 'END', 'SUCCESS', true)
          """;
    final String selectRelatedJobInstanceIdSql = """
      select related_job_instance_id from batch.workflow_run where tenant_id = ? and id = ?\
      """;
    final String selectCountSql = """
      select count(*) from batch.job_instance where tenant_id = ? and job_code = ?
  """;
    final String selectCountSql2 = """
      select count(*) from batch.workflow_node_run where workflow_run_id = ? and node_code in ('START', 'ROUTE')\s\
      and node_status = 'SUCCESS'\
      """;

    String workerGroup = "WG_ROOT_GATEWAY_QUOTA_" + System.nanoTime();
    String childJobCode = seedChildJob(workerGroup);
    DirectWorkflowSeed seed = seedDirectWorkflow(childJobCode);
    String policyCode = "root-gateway-quota-" + System.nanoTime();
    jdbcTemplate.update(insertTenantQuotaPolicySql, testTenantId, policyCode);
    jdbcTemplate.update(updateWorkflowNodeSql, testTenantId, seed.workflowDefinitionId());
    jdbcTemplate.update(updateWorkflowNodeSql2, testTenantId, seed.workflowDefinitionId());
    jdbcTemplate.update(insertWorkflowNodeSql, testTenantId, seed.workflowDefinitionId());
    jdbcTemplate.update(deleteWorkflowEdgeSql, testTenantId, seed.workflowDefinitionId());
    jdbcTemplate.update(
        insertWorkflowEdgeSql,
        testTenantId,
        seed.workflowDefinitionId(),
        testTenantId,
        seed.workflowDefinitionId(),
        testTenantId,
        seed.workflowDefinitionId());

    long activeJobsBeforeLaunch =
        tenantSchedulerSnapshotService.buildLive(testTenantId).policies().stream()
            .filter(policy -> policy.policyCode().equals(policyCode))
            .findFirst()
            .orElseThrow()
            .activeJobs();

    LaunchRequest request = LaunchRequest.builder()
        .tenantId(testTenantId)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.API)
        .requestId(seed.requestId())
        .traceId("trace-root-gateway-quota")
        .params(Map.of())
        .build();
    Mockito.clearInvocations(resourceScheduler);

    workflowRunLaunchService.launch(request);

    Long workflowRunId = workflowRunId(seed.requestId());
    Long selectedRelatedJobInstanceId = jdbcTemplate.queryForObject(
        selectRelatedJobInstanceIdSql, Long.class, testTenantId, workflowRunId);
    assertThat(selectedRelatedJobInstanceId).isNull();
    Integer selectedJobInstanceCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, testTenantId, childJobCode);
    assertThat(selectedJobInstanceCount).isEqualTo(1);
    Integer selectedWorkflowNodeRunCount =
        jdbcTemplate.queryForObject(selectCountSql2, Integer.class, workflowRunId);
    assertThat(selectedWorkflowNodeRunCount).isEqualTo(2);
    var policySnapshot = tenantSchedulerSnapshotService.buildLive(testTenantId).policies().stream()
        .filter(policy -> policy.policyCode().equals(policyCode))
        .findFirst()
        .orElseThrow();
    assertThat(policySnapshot.activeJobs()).isEqualTo(activeJobsBeforeLaunch + 1);
    assertThat(policySnapshot.effectiveTenantJobCap()).isEqualTo(1);
    Mockito.verify(resourceScheduler, Mockito.times(1))
        .schedule(Mockito.argThat((ResourceSchedulingRequest scheduledRequest) ->
            testTenantId.equals(scheduledRequest.getTenantId())
                && childJobCode.equals(scheduledRequest.getJobCode())));
  }

  @ParameterizedTest
  @ValueSource(strings = {"AUTO", "MANUAL_APPROVAL"})
  @DisplayName("完整停用的 Workflow 仍接纳已持久化 misfire 的原补跑请求并保持幂等")
  void disabledWorkflow_shouldAdmitOnlyItsPersistedMisfireRecovery(String catchUpPolicy) {
    final String insertTenantSql = """
        insert into batch.tenant (tenant_id, tenant_name, status) values (?, ?, 'ACTIVE')\s\
        on conflict (tenant_id) do nothing
    """;
    final String updateTriggerRequestSql = """
        update batch.trigger_request set trigger_type='CATCH_UP', launch_payload=cast(? as jsonb)\s\
        where tenant_id=? and request_id=?\
        """;
    final String insertScheduledOccurrenceSql = """
          insert into batch.scheduled_occurrence (
              tenant_id, workflow_definition_id, workflow_code, schedule_version, calendar_code,
              scheduled_fire_time, biz_date, occurrence_status, request_id, misfire_detected_at)
          select tenant_id, id, workflow_code, version, calendar_code, ?, ?, 'REQUESTED', ?, now()
          from batch.workflow_definition where tenant_id=? and id=?
          """;
    final String insertTriggerMisfirePendingSql = """
            insert into batch.trigger_misfire_pending (
                tenant_id, workflow_definition_id, scheduled_fire_time, status, catch_up_request_id)
            select tenant_id, workflow_definition_id, ?, 'APPROVED', id
            from batch.trigger_request where tenant_id=? and request_id=?
            """;
    final String updateWorkflowDefinitionSql = """
        update batch.workflow_definition set enabled=false, schedule_enabled=false where tenant_id=? and id=?
    """;
    final String selectCountSql = """
        select count(*) from batch.job_instance where tenant_id=? and job_code=?
    """;
    final String selectDataIntervalStartSql = """
        select data_interval_start from batch.job_instance where tenant_id=? and job_code=?
    """;
    final String selectDataIntervalEndSql = """
        select data_interval_end from batch.job_instance where tenant_id=? and job_code=?
    """;
    final String selectEnabledSql = """
        select enabled from batch.workflow_definition where tenant_id=? and id=?
    """;

    jdbcTemplate.update(insertTenantSql, testTenantId, "misfire recovery tenant");
    String childCode = seedChildJob("WG_DISABLED_RECOVERY_" + System.nanoTime());
    DirectWorkflowSeed seed = seedDirectWorkflow(childCode);
    Instant fire = BIZ_DATE.atStartOfDay(ZoneOffset.UTC).toInstant();
    LaunchRequest request = LaunchRequest.builder()
        .tenantId(testTenantId)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.CATCH_UP)
        .requestId(seed.requestId())
        .dataIntervalStart(fire)
        .dataIntervalEnd(fire.plusSeconds(86400))
        .params(Map.of(
            "workflowVersion", 3, "scheduledAt", fire.toString(), "catchUpPolicy", catchUpPolicy))
        .build();
    jdbcTemplate.update(
        updateTriggerRequestSql, JsonUtils.toJson(request), testTenantId, seed.requestId());
    jdbcTemplate.update(
        insertScheduledOccurrenceSql,
        Timestamp.from(fire),
        BIZ_DATE,
        seed.requestId(),
        testTenantId,
        seed.workflowDefinitionId());
    if ("MANUAL_APPROVAL".equals(catchUpPolicy)) {
      jdbcTemplate.update(
          insertTriggerMisfirePendingSql, Timestamp.from(fire), testTenantId, seed.requestId());
    }
    jdbcTemplate.update(updateWorkflowDefinitionSql, testTenantId, seed.workflowDefinitionId());

    LaunchResponse first = workflowRunLaunchService.launch(request);
    LaunchResponse repeated = workflowRunLaunchService.launch(request);
    assertThat(repeated.instanceNo()).isEqualTo(first.instanceNo());
    Integer selectedJobInstanceCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, testTenantId, childCode);
    assertThat(selectedJobInstanceCount).isEqualTo(1);
    Timestamp selectedDataIntervalStart = jdbcTemplate.queryForObject(
        selectDataIntervalStartSql, Timestamp.class, testTenantId, childCode);
    assertThat(selectedDataIntervalStart).isEqualTo(Timestamp.from(fire));
    Timestamp selectedDataIntervalEnd = jdbcTemplate.queryForObject(
        selectDataIntervalEndSql, Timestamp.class, testTenantId, childCode);
    assertThat(selectedDataIntervalEnd).isEqualTo(Timestamp.from(fire.plusSeconds(86400)));
    Boolean selectedEnabled = jdbcTemplate.queryForObject(
        selectEnabledSql, Boolean.class, testTenantId, seed.workflowDefinitionId());
    assertThat(selectedEnabled).isFalse();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "new_api",
        "missing",
        "wrong_request",
        "wrong_fire",
        "wrong_workflow",
        "wrong_tenant",
        "unapproved",
        "suspended",
        "no_provenance",
        "stale_version"
      })
  @DisplayName("停用恢复不得授权普通请求、伪造来源、跨租户、未审批或已暂停租户")
  void disabledWorkflow_shouldRejectUnauthorizedRecovery(String scenario) {
    final String insertTenantSql = """
        insert into batch.tenant (tenant_id, tenant_name, status) values (?, ?, 'ACTIVE')\s\
        on conflict (tenant_id) do nothing
    """;
    final String updateTriggerRequestSql = """
        update batch.trigger_request set trigger_type='CATCH_UP', launch_payload=cast(? as jsonb)\s\
        where tenant_id=? and request_id=?
    """;
    final String insertScheduledOccurrenceSql = """
          insert into batch.scheduled_occurrence (
              tenant_id, workflow_definition_id, workflow_code, schedule_version, calendar_code,
              scheduled_fire_time, biz_date, occurrence_status, request_id, misfire_detected_at)
          select tenant_id, id, workflow_code, version, calendar_code, ?, ?, 'REQUESTED', ?, now()
          from batch.workflow_definition where tenant_id=? and id=?
          """;
    final String updateWorkflowDefinitionSql = """
        update batch.workflow_definition set enabled=false where tenant_id=? and id=?
    """;
    final String deleteScheduledOccurrenceSql = """
        delete from batch.scheduled_occurrence where tenant_id=? and workflow_definition_id=?
    """;
    final String insertTriggerRequestSql = """
      insert into batch.trigger_request (tenant_id, request_id, trigger_type, workflow_definition_id, biz_date,\s\
      dedup_key, request_status) values (?, ?, 'CATCH_UP', ?, ?, ?, 'ACCEPTED')\
      """;
    final String updateScheduledOccurrenceSql = """
      update batch.scheduled_occurrence set request_id=? where tenant_id=? and workflow_definition_id=?\
      """;
    final String updateTenantSql = """
      update batch.tenant set status='SUSPENDED' where tenant_id=?
  """;
    final String updateScheduledOccurrenceSql2 = """
      update batch.scheduled_occurrence set misfire_detected_at=null where tenant_id=?\s\
      and workflow_definition_id=?
  """;
    final String selectCountSql = """
      select count(*) from batch.workflow_run where tenant_id=? and workflow_definition_id=?
  """;
    final String selectCountSql2 = """
      select count(*) from batch.job_instance where tenant_id=? and job_code=?
  """;
    final String updateTenantSql2 = """
      update batch.tenant set status='ACTIVE' where tenant_id=?
  """;

    jdbcTemplate.update(insertTenantSql, testTenantId, "misfire recovery tenant");
    String childCode = seedChildJob("WG_DISABLED_REJECT_" + System.nanoTime());
    DirectWorkflowSeed seed = seedDirectWorkflow(childCode);
    Instant fire = BIZ_DATE.atStartOfDay(ZoneOffset.UTC).toInstant();
    LaunchRequest request = LaunchRequest.builder()
        .tenantId(testTenantId)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.CATCH_UP)
        .requestId(seed.requestId())
        .dataIntervalStart(fire)
        .dataIntervalEnd(fire.plusSeconds(86400))
        .params(Map.of(
            "workflowVersion",
            3,
            "catchUpPolicy",
            scenario.equals("unapproved") ? "MANUAL_APPROVAL" : "AUTO"))
        .build();
    jdbcTemplate.update(
        updateTriggerRequestSql, JsonUtils.toJson(request), testTenantId, seed.requestId());
    jdbcTemplate.update(
        insertScheduledOccurrenceSql,
        Timestamp.from(fire),
        BIZ_DATE,
        seed.requestId(),
        testTenantId,
        seed.workflowDefinitionId());
    jdbcTemplate.update(updateWorkflowDefinitionSql, testTenantId, seed.workflowDefinitionId());
    switch (scenario) {
      case "new_api" ->
        request = request.toBuilder().triggerType(TriggerType.API).build();
      case "missing" ->
        jdbcTemplate.update(
            deleteScheduledOccurrenceSql, testTenantId, seed.workflowDefinitionId());
      case "wrong_request" -> {
        String unrelatedRequestId = seed.requestId() + "-unrelated";
        jdbcTemplate.update(
            insertTriggerRequestSql,
            testTenantId,
            unrelatedRequestId,
            seed.workflowDefinitionId(),
            BIZ_DATE,
            unrelatedRequestId);
        jdbcTemplate.update(
            updateScheduledOccurrenceSql,
            unrelatedRequestId,
            testTenantId,
            seed.workflowDefinitionId());
      }
      case "wrong_fire" ->
        request = request.toBuilder().dataIntervalStart(fire.plusSeconds(1)).build();
      case "wrong_workflow" ->
        request = request.toBuilder()
            .workflowDefinitionId(seed.workflowDefinitionId() + 1000000)
            .build();
      case "wrong_tenant" ->
        request = request.toBuilder().tenantId("other-tenant").build();
      case "suspended" -> jdbcTemplate.update(updateTenantSql, testTenantId);
      case "no_provenance" ->
        jdbcTemplate.update(
            updateScheduledOccurrenceSql2, testTenantId, seed.workflowDefinitionId());
      case "stale_version" ->
        request = request.toBuilder().params(Map.of("workflowVersion", 4)).build();
      default -> {}
    }
    LaunchRequest attempted = request;
    try {
      assertThatThrownBy(() -> workflowRunLaunchService.launch(attempted))
          .isInstanceOf(BizException.class);
      Integer selectedWorkflowRunCount = jdbcTemplate.queryForObject(
          selectCountSql, Integer.class, testTenantId, seed.workflowDefinitionId());
      assertThat(selectedWorkflowRunCount).isZero();
      Integer selectedJobInstanceCount =
          jdbcTemplate.queryForObject(selectCountSql2, Integer.class, testTenantId, childCode);
      assertThat(selectedJobInstanceCount).isZero();
    } finally {
      jdbcTemplate.update(updateTenantSql2, testTenantId);
    }
  }

  @Test
  @DisplayName("同业务日独立补跑持久等待且不阻塞其他 Workflow,槽位释放后恢复一次")
  void independentCatchUp_shouldWaitForActiveRunAndLaunchAfterItFinishes() {
    final String insertTriggerRequestSql = """
          insert into batch.trigger_request (
              tenant_id, request_id, trigger_type, workflow_definition_id, biz_date,
              dedup_key, request_status)
          values (?, ?, 'CATCH_UP', ?, ?, ?, 'ACCEPTED')
          """;
    final String selectCountSql = """
      select count(*) from batch.workflow_launch_wait where tenant_id = ? and request_id = ?\s\
      and wait_status = 'WAITING'
  """;
    final String selectRequestStatusSql = """
      select request_status from batch.trigger_request where tenant_id = ? and request_id = ?
  """;
    final String selectCountSql2 = """
      select count(*) from batch.workflow_run where tenant_id = ? and workflow_definition_id = ?
  """;
    final String selectWaitStatusSql = """
      select wait_status from batch.workflow_launch_wait where tenant_id = ? and request_id = ?
  """;
    final String updateWorkflowRunSql = """
      update batch.workflow_run set run_status = 'SUCCESS', finished_at = now() where id = ?
  """;
    final String selectCountSql3 = """
      select count(*) from batch.workflow_run where tenant_id = ? and trigger_request_id = (select id\s\
      from batch.trigger_request where tenant_id = ? and request_id = ?)\
      """;

    DirectWorkflowSeed seed =
        seedDirectWorkflow(seedChildJob("WG_BACKPRESSURE_" + System.nanoTime()));
    LaunchRequest first = LaunchRequest.builder()
        .tenantId(testTenantId)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.SCHEDULED)
        .requestId(seed.requestId())
        .build();
    workflowRunLaunchService.launch(first);
    String catchUpRequestId = seed.requestId() + "-catch-up";
    jdbcTemplate.update(
        insertTriggerRequestSql,
        testTenantId,
        catchUpRequestId,
        seed.workflowDefinitionId(),
        BIZ_DATE,
        catchUpRequestId);
    LaunchRequest catchUp = LaunchRequest.builder()
        .tenantId(testTenantId)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.CATCH_UP)
        .requestId(catchUpRequestId)
        .build();

    workflowRunLaunchService.launch(catchUp);
    workflowRunLaunchService.launch(catchUp);
    Integer selectedWorkflowLaunchWaitCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, testTenantId, catchUpRequestId);
    assertThat(selectedWorkflowLaunchWaitCount).isEqualTo(1);
    String selectedRequestStatus = jdbcTemplate.queryForObject(
        selectRequestStatusSql, String.class, testTenantId, catchUpRequestId);
    assertThat(selectedRequestStatus).isEqualTo("PROCESSING");
    Integer selectedWorkflowRunCount = jdbcTemplate.queryForObject(
        selectCountSql2, Integer.class, testTenantId, seed.workflowDefinitionId());
    assertThat(selectedWorkflowRunCount).isEqualTo(1);

    DirectWorkflowSeed independent =
        seedDirectWorkflow(seedChildJob("WG_INDEPENDENT_" + System.nanoTime()));
    LaunchRequest independentRequest = LaunchRequest.builder()
        .tenantId(testTenantId)
        .workflowDefinitionId(independent.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.API)
        .requestId(independent.requestId())
        .build();
    workflowRunLaunchService.launch(independentRequest);
    assertThat(workflowRunId(independent.requestId())).isNotNull();
    launchWaitReconciler.reconcileTenant(testTenantId);
    String selectedWaitStatus = jdbcTemplate.queryForObject(
        selectWaitStatusSql, String.class, testTenantId, catchUpRequestId);
    assertThat(selectedWaitStatus).isEqualTo("WAITING");

    jdbcTemplate.update(updateWorkflowRunSql, workflowRunId(seed.requestId()));
    // 接收端契约：稳定的 OpenLineage 运行标识同时作为幂等键。
    launchWaitReconciler.reconcileTenant(testTenantId);
    workflowRunLaunchService.launch(catchUp);
    workflowRunLaunchService.launch(catchUp);
    Integer selectedWorkflowRunCountRead2 = jdbcTemplate.queryForObject(
        selectCountSql3, Integer.class, testTenantId, testTenantId, catchUpRequestId);
    assertThat(selectedWorkflowRunCountRead2).isEqualTo(1);
    String selectedWaitStatusRead2 = jdbcTemplate.queryForObject(
        selectWaitStatusSql, String.class, testTenantId, catchUpRequestId);
    assertThat(selectedWaitStatusRead2).isEqualTo("RESUMED");
    String selectedRequestStatusRead2 = jdbcTemplate.queryForObject(
        selectRequestStatusSql, String.class, testTenantId, catchUpRequestId);
    assertThat(selectedRequestStatusRead2).isEqualTo("LAUNCHED");
  }

  @Test
  @DisplayName("根运行启动 CAS 前被终止时不能继续创建子 Job 或任务")
  void terminatedBeforeRunningCas_shouldNotDispatchInitialJob() {
    final String updateWorkflowRunSql = """
        update batch.workflow_run set run_status = 'TERMINATED', finished_at = now() where id = ?
    """;
    final String selectRunStatusSql = """
        select run_status from batch.workflow_run where id = ?
    """;
    final String selectCountSql = """
        select count(*) from batch.job_instance where tenant_id = ? and job_code = ?
    """;

    String childCode = seedChildJob("WG_TERMINATE_CAS_" + System.nanoTime());
    DirectWorkflowSeed seed = seedDirectWorkflow(childCode);
    Mockito.doAnswer(invocation -> {
          WorkflowRunMarkRunningParam parameters = invocation.getArgument(0);
          Long runId = parameters.id();
          jdbcTemplate.update(updateWorkflowRunSql, runId);
          return 0;
        })
        .when(workflowRunMapper)
        .markRunning(ArgumentMatchers.argThat(parameters -> parameters != null
            && Objects.equals(parameters.tenantId(), testTenantId)
            && parameters.runStatus() != null
            && parameters.currentNodeCode() != null
            && parameters.startedAt() != null));

    LaunchRequest terminatedWorkflowRequest = LaunchRequest.builder()
        .tenantId(testTenantId)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.API)
        .requestId(seed.requestId())
        .build();
    workflowRunLaunchService.launch(terminatedWorkflowRequest);

    Long terminatedRunId = workflowRunId(seed.requestId());
    String runStatus =
        jdbcTemplate.queryForObject(selectRunStatusSql, String.class, terminatedRunId);
    assertThat(runStatus).isEqualTo("TERMINATED");
    Integer selectedJobInstanceCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, testTenantId, childCode);
    assertThat(selectedJobInstanceCount).isZero();
  }

  @Test
  @DisplayName("根运行已创建但等待完成标记前中断时,恢复不能被自身活跃运行永久阻塞")
  void admittedRootBeforeWaitCompletion_shouldRecoverWithoutDuplicateExecution() {
    final String selectLaunchPayloadSql = """
        select launch_payload::text from batch.workflow_launch_wait where tenant_id = ? and request_id = ?
    """;
    final String selectWaitStatusSql = """
        select wait_status from batch.workflow_launch_wait where tenant_id = ? and request_id = ?
    """;
    final String selectCountSql = """
        select count(*) from batch.workflow_node_run where workflow_run_id = ? and job_instance_id is not null
    """;

    CapacityWaitSeed seed = seedCapacityWait();
    finishCapacityOwner(seed);
    String queuedPayload = jdbcTemplate.queryForObject(
        selectLaunchPayloadSql, String.class, testTenantId, seed.requestId());
    WorkflowRunEntity owner = workflowRunMapper.selectById(testTenantId, seed.ownerRunId());
    WorkflowLaunchWaitClaim claim = new WorkflowLaunchWaitClaim(
        testTenantId,
        seed.requestId(),
        owner.getWorkflowDefinitionId(),
        BIZ_DATE,
        false,
        queuedPayload);
    LaunchRequest queued = waitingLaunchService.restoreRequest(testTenantId, claim);
    Mockito.doThrow(new IllegalStateException("lost wait-completion marker"))
        .when(waitingLaunchService)
        .markResumed(ArgumentMatchers.eq(queued));
    assertThatThrownBy(() -> workflowRunLaunchService.launch(queued))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("lost wait-completion marker");
    Long admittedRunId = workflowRunId(seed.requestId());
    assertThat(admittedRunId).isNotNull();
    Mockito.doCallRealMethod()
        .when(waitingLaunchService)
        .markResumed(ArgumentMatchers.any(LaunchRequest.class));

    launchWaitReconciler.reconcileTenant(testTenantId);

    String selectedWaitStatus = jdbcTemplate.queryForObject(
        selectWaitStatusSql, String.class, testTenantId, seed.requestId());
    assertThat(selectedWaitStatus).isEqualTo("RESUMED");
    assertThat(workflowRunId(seed.requestId())).isEqualTo(admittedRunId);
    Integer selectedWorkflowNodeRunCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, admittedRunId);
    assertThat(selectedWorkflowNodeRunCount).isEqualTo(1);
  }

  @Test
  @DisplayName("JOB 节点因前一业务日等待后由释放调度器启动并关联实例且不重复")
  void batchDayDeferredChild_shouldAttachReleasedJobInstanceToOriginalNodeOnce() {
    final String insertBusinessCalendarSql = """
        insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone, enabled)\s\
        values (?, 'WF_CALENDAR', 'Integration', 'UTC', true) on conflict (tenant_id, calendar_code) do update\s\
        set enabled = true\
        """;
    final String updateBusinessCalendarSql = """
        update batch.business_calendar set day_rollover_policy = 'WAIT_PREVIOUS_DAY' where tenant_id = ?\s\
        and calendar_code = 'WF_CALENDAR'\
        """;
    final String insertBatchDayInstanceSql = """
        insert into batch.batch_day_instance (tenant_id, calendar_code, biz_date, day_status, open_at,\s\
        late_count, catchup_count, timezone_snapshot, dst_policy_snapshot, frozen, version, created_at,\s\
        updated_at, dry_run) values (?, 'WF_CALENDAR', ?, 'OPEN', current_timestamp, 0, 0, 'UTC',\s\
        'gap=RUN_AT_NEXT_VALID_TIME;overlap=RUN_ONCE_EARLIER_OFFSET', false, 0, current_timestamp,\s\
        current_timestamp, false)\
        """;
    final String selectRequestIdSql = """
        select request_id from batch.trigger_request where tenant_id = ? and parent_workflow_run_id = ?\s\
        and parent_node_code = 'JOB_NODE_1'\
        """;
    final String selectNodeStatusSql = """
        select node_status from batch.workflow_node_run where workflow_run_id = ? and node_code = 'JOB_NODE_1'\
        """;
    final String selectJobInstanceIdSql = """
        select job_instance_id from batch.workflow_node_run where workflow_run_id = ?\s\
        and node_code = 'JOB_NODE_1'\
        """;
    final String selectRequestStatusSql = """
        select request_status from batch.trigger_request where tenant_id = ? and request_id = ?
    """;
    final String selectWaitStatusSql = """
      select wait_status from batch.batch_day_waiting_launch where tenant_id = ? and request_id = ?\
      """;
    final String selectCountSql = """
      select count(*) from batch.job_instance where tenant_id = ? and job_code = ?
  """;
    final String updateBatchDayInstanceSql = """
      update batch.batch_day_instance set day_status = 'SETTLED', settled_at = current_timestamp\s\
      where tenant_id = ? and calendar_code = 'WF_CALENDAR' and biz_date = ?\
      """;
    final String selectJobInstanceIdSql2 = """
      select job_instance_id from batch.workflow_node_run where workflow_run_id = ? and node_code = 'JOB_NODE_1'\
      """;
    final String selectCountSql2 = """
      select count(*) from batch.workflow_node_run where workflow_run_id = ? and node_code = 'JOB_NODE_1'\s\
      and job_instance_id = ?\
      """;

    String childCode = seedChildJob("WG_BATCH_DAY_WAIT_" + System.nanoTime());
    jdbcTemplate.update(insertBusinessCalendarSql, testTenantId);
    jdbcTemplate.update(updateBusinessCalendarSql, testTenantId);
    configCacheService.evictBusinessCalendar(testTenantId, "WF_CALENDAR");
    jdbcTemplate.update(insertBatchDayInstanceSql, testTenantId, BIZ_DATE.minusDays(1));

    DirectWorkflowSeed seed = seedDirectWorkflow(childCode);
    LaunchRequest launchRequest = LaunchRequest.builder()
        .tenantId(testTenantId)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.API)
        .requestId(seed.requestId())
        .build();
    workflowRunLaunchService.launch(launchRequest);
    Long rootRunId = workflowRunId(seed.requestId());
    String childRequestId =
        jdbcTemplate.queryForObject(selectRequestIdSql, String.class, testTenantId, rootRunId);

    String selectedNodeStatus =
        jdbcTemplate.queryForObject(selectNodeStatusSql, String.class, rootRunId);
    assertThat(selectedNodeStatus).isEqualTo("READY");
    Long selectedJobInstanceId =
        jdbcTemplate.queryForObject(selectJobInstanceIdSql, Long.class, rootRunId);
    assertThat(selectedJobInstanceId).isNull();
    String selectedRequestStatus = jdbcTemplate.queryForObject(
        selectRequestStatusSql, String.class, testTenantId, childRequestId);
    assertThat(selectedRequestStatus).isEqualTo("WAITING");
    String selectedWaitStatus = jdbcTemplate.queryForObject(
        selectWaitStatusSql, String.class, testTenantId, childRequestId);
    assertThat(selectedWaitStatus).isEqualTo("WAITING");
    Integer selectedJobInstanceCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, testTenantId, childCode);
    assertThat(selectedJobInstanceCount).isZero();

    jdbcTemplate.update(updateBatchDayInstanceSql, testTenantId, BIZ_DATE.minusDays(1));
    batchDayWaitingReleaseScheduler.release();

    Long childInstanceId =
        jdbcTemplate.queryForObject(selectJobInstanceIdSql2, Long.class, rootRunId);
    assertThat(childInstanceId).isNotNull();
    String selectedRequestStatusRead2 = jdbcTemplate.queryForObject(
        selectRequestStatusSql, String.class, testTenantId, childRequestId);
    assertThat(selectedRequestStatusRead2).isEqualTo("LAUNCHED");
    String selectedWaitStatusRead2 = jdbcTemplate.queryForObject(
        selectWaitStatusSql, String.class, testTenantId, childRequestId);
    assertThat(selectedWaitStatusRead2).isEqualTo("RELEASED");
    Integer selectedJobInstanceCountRead2 =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, testTenantId, childCode);
    assertThat(selectedJobInstanceCountRead2).isEqualTo(1);
    Integer selectedWorkflowNodeRunCount =
        jdbcTemplate.queryForObject(selectCountSql2, Integer.class, rootRunId, childInstanceId);
    assertThat(selectedWorkflowNodeRunCount).isEqualTo(1);

    batchDayWaitingReleaseScheduler.release();
    Integer selectedJobInstanceCountRead3 =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, testTenantId, childCode);
    assertThat(selectedJobInstanceCountRead3).isEqualTo(1);
  }

  @Test
  @DisplayName("系统槽位等待扫描逐租户恢复等待且不串用跨租户请求")
  void systemCapacityWaitRecovery_shouldResumeEachRunnableTenantIndependently() {
    final String insertBusinessCalendarSql = """
        insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone, enabled)\s\
        values (?, 'JOB_CALENDAR', 'Integration', 'UTC', true), (?, 'WF_CALENDAR', 'Integration', 'UTC', true)\s\
        on conflict (tenant_id, calendar_code) do nothing\
        """;

    String tenantA = "r20-slot-wait-a-" + System.nanoTime();
    String tenantB = "r20-slot-wait-b-" + System.nanoTime();
    insertRunnableTenant(tenantA);
    insertRunnableTenant(tenantB);
    jdbcTemplate.update(insertBusinessCalendarSql, tenantA, tenantA);
    jdbcTemplate.update(insertBusinessCalendarSql, tenantB, tenantB);

    CapacityWaitSeed waitA = RlsTenantContextHolder.runWithTenant(
        tenantA, () -> seedCapacityWait(tenantA, "r20-shared-request"));
    CapacityWaitSeed waitB = RlsTenantContextHolder.runWithTenant(
        tenantB, () -> seedCapacityWait(tenantB, "r20-shared-request"));
    finishCapacityOwner(waitA);
    finishCapacityOwner(waitB);

    WorkflowLaunchWaitReconciler reconciler =
        AopTestUtils.getUltimateTargetObject(launchWaitReconciler);
    reconciler.reconcile();

    assertCapacityWaitResumed(waitA);
    assertCapacityWaitResumed(waitB);
  }

  @ParameterizedTest
  @ValueSource(strings = {"SETTLEMENT_FIRST", "RECOVERY_FIRST"})
  @DisplayName("Workflow 槽位等待恢复与业务日结算按两种围栏顺序竞争")
  void capacityWaitRecoveryRacingSettlement_shouldKeepBatchDayInFlight(String firstWaiter)
      throws Exception {
    final String selectAcquireBatchDaySettlementFenceSql = """
            select batch.acquire_batch_day_settlement_fence(?, ?, ?)
        """;
    final String selectPgBackendPidSql = """
            select pg_backend_pid()
        """;

    final String selectWorkflowDefinitionIdSql = """
            select workflow_definition_id from batch.workflow_run where tenant_id = ? and id = ?
        """;
    final String selectCalendarCodeSql = """
            select calendar_code from batch.workflow_definition where tenant_id = ? and id = ?\
            """;
    final String updateBatchDayInstanceSql = """
            update batch.batch_day_instance\s\
            set day_status = 'CUTOFF', cutoff_at = current_timestamp - interval '1 second', frozen = false,\s\
            version = version + 1, updated_at = current_timestamp where tenant_id = ? and calendar_code = ?\s\
            and biz_date = ?\
            """;
    final String updateWorkflowLaunchWaitSql = """
            update batch.workflow_launch_wait set next_attempt_at = current_timestamp - interval '1 second'\s\
            where tenant_id = ? and request_id = ? and wait_status = 'WAITING'\
            """;
    final String selectIdSql = """
            select wr.id from batch.workflow_run wr join batch.trigger_request tr on tr.tenant_id = wr.tenant_id\s\
            and tr.id = wr.trigger_request_id where wr.tenant_id = ? and tr.request_id = ?\
            """;
    final String selectCountSql = """
            select count(*) from batch.workflow_run where tenant_id = ? and trigger_request_id = (select id\s\
            from batch.trigger_request where tenant_id = ? and request_id = ?)\
            """;
    final String selectWaitStatusSql = """
        select wait_status from batch.workflow_launch_wait where tenant_id = ? and request_id = ?\
        """;
    final String selectRunStatusSql = """
        select run_status from batch.workflow_run where tenant_id = ? and id = ?
    """;
    final String selectDayStatusSql = """
        select day_status from batch.batch_day_instance where tenant_id = ? and calendar_code = ?\s\
        and biz_date = ?\
        """;

    String tenantId = "r11-slot-wait-" + System.nanoTime();
    insertRunnableTenant(tenantId);
    CapacityWaitSeed seed = seedCapacityWait(tenantId, "req-waiting-" + System.nanoTime());
    finishCapacityOwner(seed);
    Long workflowDefinitionId = jdbcTemplate.queryForObject(
        selectWorkflowDefinitionIdSql, Long.class, seed.tenantId(), seed.ownerRunId());
    String calendarCode = jdbcTemplate.queryForObject(
        selectCalendarCodeSql, String.class, seed.tenantId(), workflowDefinitionId);
    jdbcTemplate.update(updateBatchDayInstanceSql, seed.tenantId(), calendarCode, BIZ_DATE);
    jdbcTemplate.update(updateWorkflowLaunchWaitSql, seed.tenantId(), seed.requestId());

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try (Connection fence = jdbcTemplate.getDataSource().getConnection()) {
      fence.setAutoCommit(false);
      try (var statement = fence.prepareStatement(selectAcquireBatchDaySettlementFenceSql)) {
        statement.setString(1, seed.tenantId());
        statement.setString(2, calendarCode);
        statement.setObject(3, BIZ_DATE);
        statement.executeQuery().close();
      }
      int fencePid;
      try (var statement = fence.createStatement();
          var result = statement.executeQuery(selectPgBackendPidSql)) {
        result.next();
        fencePid = result.getInt(1);
      }

      Future<?> settlement;
      Future<?> recovery;
      if ("SETTLEMENT_FIRST".equals(firstWaiter)) {
        settlement = executor.submit(batchDaySettleScheduler::settle);
        awaitBlockedBy(fencePid);
        recovery = executor.submit(() -> launchWaitReconciler.reconcileTenant(seed.tenantId()));
        awaitBlockedBySql(fencePid, "acquire_batch_day_settlement_fence");
      } else {
        recovery = executor.submit(() -> launchWaitReconciler.reconcileTenant(seed.tenantId()));
        awaitBlockedBySql(fencePid, "acquire_batch_day_settlement_fence");
        settlement = executor.submit(batchDaySettleScheduler::settle);
        awaitBlockedBy(fencePid, 2);
      }
      awaitBlockedBy(fencePid, 2);
      fence.commit();
      settlement.get(10, TimeUnit.SECONDS);
      recovery.get(10, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    Long recoveredRunId =
        jdbcTemplate.queryForObject(selectIdSql, Long.class, seed.tenantId(), seed.requestId());
    assertThat(recoveredRunId).isNotNull().isNotEqualTo(seed.ownerRunId());
    Integer selectedWorkflowRunCount = jdbcTemplate.queryForObject(
        selectCountSql, Integer.class, seed.tenantId(), seed.tenantId(), seed.requestId());
    assertThat(selectedWorkflowRunCount).isEqualTo(1);
    String selectedWaitStatus = jdbcTemplate.queryForObject(
        selectWaitStatusSql, String.class, seed.tenantId(), seed.requestId());
    assertThat(selectedWaitStatus).isEqualTo("RESUMED");
    String selectedRunStatus = jdbcTemplate.queryForObject(
        selectRunStatusSql, String.class, seed.tenantId(), recoveredRunId);
    assertThat(selectedRunStatus).isEqualTo("RUNNING");
    String selectedDayStatus = jdbcTemplate.queryForObject(
        selectDayStatusSql, String.class, seed.tenantId(), calendarCode, BIZ_DATE);
    assertThat(selectedDayStatus).isEqualTo("IN_FLIGHT");
  }

  @Test
  @DisplayName("等待槽位期间被拒绝的请求在恢复后不复活")
  void rejectedWaitingRequest_shouldNotCreateRootRun() {
    final String updateTriggerRequestSql = """
        update batch.trigger_request set request_status = 'REJECTED' where tenant_id = ? and request_id = ?
    """;

    CapacityWaitSeed seed = seedCapacityWait();
    jdbcTemplate.update(updateTriggerRequestSql, testTenantId, seed.requestId());
    finishCapacityOwner(seed);
    launchWaitReconciler.reconcileTenant(testTenantId);
    assertRejectedCapacityWait(seed);
  }

  @ParameterizedTest
  @ValueSource(strings = {"tenant", "date", "shape"})
  @DisplayName("持久化等待的租户、业务日或载荷形状被篡改时拒绝执行且终结等待")
  void corruptedWaitingPayload_shouldFailClosed(String changedField) {
    final String updateWorkflowLaunchWaitSql = """
            update batch.workflow_launch_wait
            set launch_payload = jsonb_set(launch_payload, '{tenantId}', '"foreign-tenant"'::jsonb)
            where tenant_id = ? and request_id = ?
            """;
    final String updateWorkflowLaunchWaitSql2 = """
          update batch.workflow_launch_wait
          set launch_payload = jsonb_set(launch_payload, '{bizDate}', '[2026,3,2]'::jsonb)
          where tenant_id = ? and request_id = ?
          """;
    final String updateWorkflowLaunchWaitSql3 = """
      update batch.workflow_launch_wait set launch_payload = '[]'::jsonb where tenant_id = ? and request_id = ?
  """;

    CapacityWaitSeed seed = seedCapacityWait();
    if (changedField.equals("tenant")) {
      jdbcTemplate.update(updateWorkflowLaunchWaitSql, testTenantId, seed.requestId());
    } else if (changedField.equals("date")) {
      jdbcTemplate.update(updateWorkflowLaunchWaitSql2, testTenantId, seed.requestId());
    } else {
      jdbcTemplate.update(updateWorkflowLaunchWaitSql3, testTenantId, seed.requestId());
    }
    finishCapacityOwner(seed);
    launchWaitReconciler.reconcileTenant(testTenantId);
    assertRejectedCapacityWait(seed);
  }

  @Test
  @DisplayName("真实无绕过权限角色只能读取并修改本租户的 Workflow 启动等待")
  void capacityWait_shouldEnforceRealTenantRowSecurity() {
    final String doStatementSql = """
        DO $$ BEGIN
          IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'workflow_launch_wait_rls_test') THEN
            CREATE ROLE workflow_launch_wait_rls_test NOSUPERUSER NOBYPASSRLS;
          END IF;
        END $$
        """;

    final String grantUsageSql = """
      GRANT USAGE ON SCHEMA batch TO workflow_launch_wait_rls_test
  """;
    final String grantSelectSql = """
      GRANT SELECT, UPDATE ON batch.workflow_launch_wait TO workflow_launch_wait_rls_test
  """;
    final String grantSelectSql2 = """
      GRANT SELECT ON batch.trigger_request, batch.workflow_run TO workflow_launch_wait_rls_test
  """;
    final String setLocalSql = """
      SET LOCAL ROLE workflow_launch_wait_rls_test
  """;

    final String selectSetConfigSql = """
      select set_config('app.tenant_id', '', true)
  """;
    final String selectSetConfigSql2 = """
      select set_config('app.tenant_id', ?, true)
  """;
    final String selectCountSql = """
        select count(*) from batch.workflow_launch_wait where request_id = ?
    """;
    final String selectCurrentUserSql = """
            select current_user || '/' || session_user || '/'
                || coalesce(current_setting('app.tenant_id', true), '<null>') || '/'
                || row_security_active('batch.workflow_launch_wait')::text
            """;
    final String updateWorkflowLaunchWaitSql = """
        update batch.workflow_launch_wait set wait_status = 'REJECTED' where request_id = ?
    """;
    final String selectWaitStatusSql = """
        select wait_status from batch.workflow_launch_wait where tenant_id = ? and request_id = ?
    """;

    CapacityWaitSeed seed = seedCapacityWait();
    finishCapacityOwner(seed);
    jdbcTemplate.execute(doStatementSql);
    jdbcTemplate.execute(grantUsageSql);
    jdbcTemplate.execute(grantSelectSql);
    jdbcTemplate.execute(grantSelectSql2);
    TransactionTemplate tx = new TransactionTemplate(platformTransactionManager);
    tx.executeWithoutResult(status -> {
      jdbcTemplate.execute(setLocalSql);
      jdbcTemplate.queryForObject(selectSetConfigSql, String.class);
      assertThat(waitingLaunchService.claimDue(testTenantId, 100, Instant.now().plusSeconds(5)))
          .anySatisfy(claim -> assertThat(claim.requestId()).isEqualTo(seed.requestId()));
      jdbcTemplate.queryForObject(selectSetConfigSql2, String.class, testTenantId);
      Integer selectedWorkflowLaunchWaitCount =
          jdbcTemplate.queryForObject(selectCountSql, Integer.class, seed.requestId());
      assertThat(selectedWorkflowLaunchWaitCount).isEqualTo(1);
      jdbcTemplate.queryForObject(selectSetConfigSql2, String.class, "foreign-tenant");
      String rlsContext = jdbcTemplate.queryForObject(selectCurrentUserSql, String.class);
      Integer selectedWorkflowLaunchWaitCountRead2 =
          jdbcTemplate.queryForObject(selectCountSql, Integer.class, seed.requestId());
      assertThat(selectedWorkflowLaunchWaitCountRead2)
          .as("RLS context after tenant switch: %s", rlsContext)
          .isZero();
      int affectedWorkflowLaunchWaitRows =
          jdbcTemplate.update(updateWorkflowLaunchWaitSql, seed.requestId());
      assertThat(affectedWorkflowLaunchWaitRows).isZero();
    });
    String selectedWaitStatus = jdbcTemplate.queryForObject(
        selectWaitStatusSql, String.class, testTenantId, seed.requestId());
    assertThat(selectedWaitStatus).isEqualTo("WAITING");
  }

  @Test
  @DisplayName("容量冲突后的等待与原图准入使用同一数据库事务，不能回滚后另起事务补写")
  void capacityWait_shouldPersistInAdmissionTransaction() {
    String currentTransactionSql = "select pg_current_xact_id()::text";
    String waitStatusSql = """
        select wait_status from batch.workflow_launch_wait where tenant_id = ? and request_id = ?
        """;
    String requestStatusSql = """
        select request_status from batch.trigger_request where tenant_id = ? and request_id = ?
        """;
    List<String> admissionTransactions = new ArrayList<>();
    List<String> waitTransactions = new ArrayList<>();
    Mockito.doAnswer(invocation -> {
          String transactionId = jdbcTemplate.queryForObject(currentTransactionSql, String.class);
          admissionTransactions.add(transactionId);
          return invocation.callRealMethod();
        })
        .when(workflowGraphValidator)
        .validate(ArgumentMatchers.anyLong());
    Mockito.doAnswer(invocation -> {
          String transactionId = jdbcTemplate.queryForObject(currentTransactionSql, String.class);
          waitTransactions.add(transactionId);
          return invocation.callRealMethod();
        })
        .when(waitingLaunchService)
        .defer(
            ArgumentMatchers.any(LaunchRequest.class),
            ArgumentMatchers.any(WorkflowLaunchWaitSnapshot.class));

    CapacityWaitSeed seed = seedCapacityWait();

    assertThat(admissionTransactions).hasSize(2);
    String admissionTransaction = admissionTransactions.getLast();
    assertThat(waitTransactions).containsExactly(admissionTransaction);
    String waitStatus =
        jdbcTemplate.queryForObject(waitStatusSql, String.class, testTenantId, seed.requestId());
    String requestStatus =
        jdbcTemplate.queryForObject(requestStatusSql, String.class, testTenantId, seed.requestId());
    assertThat(waitStatus).isEqualTo("WAITING");
    assertThat(requestStatus).isEqualTo("PROCESSING");
    String runCountSql = """
        select count(*) from batch.workflow_run where tenant_id = ? and trigger_request_id = (
            select id from batch.trigger_request where tenant_id = ? and request_id = ?)
        """;
    Long rootCount = jdbcTemplate.queryForObject(
        runCountSql, Long.class, testTenantId, testTenantId, seed.requestId());
    assertThat(rootCount).isZero();
  }

  @Test
  @DisplayName("容量等待写入后失败时原准入全部回滚，同一请求重投可正常等待")
  void capacityWait_shouldRollbackAdmissionWhenWaitPersistenceFails() {
    String requestId = "req-b197-rollback-" + System.nanoTime();
    String waitCountSql = """
        select count(*) from batch.workflow_launch_wait where tenant_id = ? and request_id = ?
        """;
    String requestStatusSql = """
        select request_status from batch.trigger_request where tenant_id = ? and request_id = ?
        """;
    String definitionSql = """
        select workflow_definition_id from batch.trigger_request where tenant_id = ? and request_id = ?
        """;
    String runCountSql = """
        select count(*) from batch.workflow_run where tenant_id = ? and trigger_request_id = (
            select id from batch.trigger_request where tenant_id = ? and request_id = ?)
        """;
    Mockito.doAnswer(invocation -> {
          invocation.callRealMethod();
          throw new IllegalStateException("injected failure after wait persistence");
        })
        .when(waitingLaunchService)
        .defer(
            ArgumentMatchers.any(LaunchRequest.class),
            ArgumentMatchers.any(WorkflowLaunchWaitSnapshot.class));

    assertThatThrownBy(() -> seedCapacityWait(testTenantId, requestId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("injected failure after wait persistence");

    Long waitCount = jdbcTemplate.queryForObject(waitCountSql, Long.class, testTenantId, requestId);
    Long rootCount =
        jdbcTemplate.queryForObject(runCountSql, Long.class, testTenantId, testTenantId, requestId);
    String requestStatus =
        jdbcTemplate.queryForObject(requestStatusSql, String.class, testTenantId, requestId);
    assertThat(waitCount).isZero();
    assertThat(rootCount).isZero();
    assertThat(requestStatus).isEqualTo("ACCEPTED");
    Mockito.doCallRealMethod()
        .when(waitingLaunchService)
        .defer(
            ArgumentMatchers.any(LaunchRequest.class),
            ArgumentMatchers.any(WorkflowLaunchWaitSnapshot.class));
    Long definitionId =
        jdbcTemplate.queryForObject(definitionSql, Long.class, testTenantId, requestId);
    LaunchRequest retried = LaunchRequest.builder()
        .tenantId(testTenantId)
        .requestId(requestId)
        .workflowDefinitionId(definitionId)
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.CATCH_UP)
        .build();

    workflowRunLaunchService.launch(retried);

    Long committedWaitCount =
        jdbcTemplate.queryForObject(waitCountSql, Long.class, testTenantId, requestId);
    String committedRequestStatus =
        jdbcTemplate.queryForObject(requestStatusSql, String.class, testTenantId, requestId);
    assertThat(committedWaitCount).isEqualTo(1L);
    assertThat(committedRequestStatus).isEqualTo("PROCESSING");
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("同槽并发启动只创建一个根运行，序列化失败由入口重试后保留唯一等待")
  void capacityWait_shouldSerializeConcurrentAdmissions(boolean sameRequest) throws Exception {
    DirectWorkflowSeed seed = seedDirectWorkflow(seedChildJob("WG_B197_RACE_" + System.nanoTime()));
    String secondRequestId = sameRequest ? seed.requestId() : seed.requestId() + "-second";
    String insertRequestSql = """
        insert into batch.trigger_request (
            tenant_id, request_id, trigger_type, workflow_definition_id, biz_date, dedup_key, request_status)
        values (?, ?, 'API', ?, ?, ?, 'ACCEPTED')
        """;
    String activeCountSql = """
        select count(*) from batch.workflow_run where tenant_id = ? and workflow_definition_id = ?
            and biz_date = ? and dry_run = false and run_status in ('CREATED', 'RUNNING')
        """;
    String waitCountSql = """
        select count(*) from batch.workflow_launch_wait where tenant_id = ? and workflow_definition_id = ?
            and wait_status = 'WAITING'
        """;
    String startCountSql = """
        select count(*) from batch.workflow_node_run node
        join batch.workflow_run root on root.id = node.workflow_run_id
        where root.tenant_id = ? and root.workflow_definition_id = ? and node.node_code = 'START'
        """;
    if (!sameRequest) {
      jdbcTemplate.update(
          insertRequestSql,
          testTenantId,
          secondRequestId,
          seed.workflowDefinitionId(),
          BIZ_DATE,
          secondRequestId);
    }
    CountDownLatch snapshotsReady = new CountDownLatch(sameRequest ? 0 : 2);
    Mockito.doAnswer(invocation -> {
          Object result = invocation.callRealMethod();
          snapshotsReady.countDown();
          boolean bothPrepared = snapshotsReady.await(10, TimeUnit.SECONDS);
          assertThat(bothPrepared).isTrue();
          return result;
        })
        .when(workflowGraphValidator)
        .validate(seed.workflowDefinitionId());
    LaunchRequest first = LaunchRequest.builder()
        .tenantId(testTenantId)
        .requestId(seed.requestId())
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.API)
        .build();
    LaunchRequest second = first.toBuilder().requestId(secondRequestId).build();
    List<LaunchRequest> requests = List.of(first, second);
    ExecutorService workers = Executors.newFixedThreadPool(2);
    try {
      List<Future<LaunchResponse>> results = new ArrayList<>();
      for (LaunchRequest request : requests) {
        results.add(workers.submit(() -> workflowRunLaunchService.launch(request)));
      }
      for (Future<LaunchResponse> result : results) {
        assertThat(result.get(20, TimeUnit.SECONDS)).isNotNull();
      }
    } finally {
      workers.shutdownNow();
      boolean stopped = workers.awaitTermination(5, TimeUnit.SECONDS);
      assertThat(stopped).isTrue();
    }
    Long activeCount = jdbcTemplate.queryForObject(
        activeCountSql, Long.class, testTenantId, seed.workflowDefinitionId(), BIZ_DATE);
    Long waitCount = jdbcTemplate.queryForObject(
        waitCountSql, Long.class, testTenantId, seed.workflowDefinitionId());
    Long startCount = jdbcTemplate.queryForObject(
        startCountSql, Long.class, testTenantId, seed.workflowDefinitionId());
    assertThat(activeCount).isEqualTo(1L);
    assertThat(waitCount).isEqualTo(sameRequest ? 0L : 1L);
    assertThat(startCount).isEqualTo(1L);
  }

  @Test
  @DisplayName("专用容量插入不得吞掉请求唯一键和外键约束错误")
  void capacityWait_shouldPropagateNonCapacityConstraints() {
    CapacityWaitSeed seed = seedCapacityWait();
    WorkflowRunEntity original = workflowRunMapper.selectById(testTenantId, seed.ownerRunId());
    finishCapacityOwner(seed);
    original.setRunStatus("CREATED");

    assertThatThrownBy(() -> workflowRunMapper.insertIfActiveSlotFree(original))
        .isInstanceOf(DuplicateKeyException.class)
        .hasMessageContaining("uk_workflow_run_trigger_request");
    original.setTriggerRequestId(Long.MAX_VALUE);
    assertThatThrownBy(() -> workflowRunMapper.insertIfActiveSlotFree(original))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("容量等待持久后修改图和定义版本，恢复仍使用首次冻结的执行输入")
  void capacityWait_shouldKeepOriginalExecutionSnapshotAfterDefinitionChanges() {
    String selectDefinitionSql = """
        select workflow_definition_id from batch.workflow_run where tenant_id = ? and id = ?
        """;
    String selectOriginalJobSql = """
        select execution_snapshot #>> '{jobDefinitions,JOB_NODE_1,jobCode}'
        from batch.workflow_run where tenant_id = ? and id = ?
        """;
    String replaceJobSql = """
        update batch.workflow_node set related_job_code = ?
        where tenant_id = ? and workflow_definition_id = ? and node_code = 'JOB_NODE_1'
        """;
    String bumpVersionSql = """
        update batch.workflow_definition set version = version + 1 where tenant_id = ? and id = ?
        """;
    String selectResumedVersionSql = """
        select workflow_version_snapshot from batch.workflow_run where tenant_id = ? and id = ?
        """;
    String selectResumedJobSql = """
        select execution_snapshot #>> '{jobDefinitions,JOB_NODE_1,jobCode}'
        from batch.workflow_run where tenant_id = ? and id = ?
        """;
    CapacityWaitSeed seed = seedCapacityWait();
    Long definitionId = jdbcTemplate.queryForObject(
        selectDefinitionSql, Long.class, seed.tenantId(), seed.ownerRunId());
    String originalJob = jdbcTemplate.queryForObject(
        selectOriginalJobSql, String.class, seed.tenantId(), seed.ownerRunId());
    String changedJob = seedChildJob("WG_CHANGED_WAIT_" + System.nanoTime());
    jdbcTemplate.update(replaceJobSql, changedJob, seed.tenantId(), definitionId);
    jdbcTemplate.update(bumpVersionSql, seed.tenantId(), definitionId);
    finishCapacityOwner(seed);

    launchWaitReconciler.reconcileTenant(seed.tenantId());

    assertCapacityWaitResumed(seed);
    Long resumedId = workflowRunId(seed.requestId());
    Integer frozenVersion = jdbcTemplate.queryForObject(
        selectResumedVersionSql, Integer.class, seed.tenantId(), resumedId);
    String frozenJob =
        jdbcTemplate.queryForObject(selectResumedJobSql, String.class, seed.tenantId(), resumedId);
    assertThat(frozenVersion).isEqualTo(3);
    assertThat(frozenJob).isEqualTo(originalJob).isNotEqualTo(changedJob);
  }

  @Test
  @DisplayName("容量等待保护原参数，重投修改参数不能覆盖首次持久输入")
  void capacityWait_shouldProtectParametersAndRejectChangedRedelivery() {
    String selectPayloadSql = """
        select launch_payload::text from batch.workflow_launch_wait where tenant_id = ? and request_id = ?
        """;
    String selectDefinitionSql = """
        select workflow_definition_id from batch.workflow_run where tenant_id = ? and id = ?
        """;
    String secret = UUID.randomUUID().toString();
    Map<String, Object> originalParams = Map.of("apiToken", secret);
    CapacityWaitSeed seed =
        seedCapacityWait(testTenantId, "req-b193-protected-" + System.nanoTime(), originalParams);
    String firstPayload = jdbcTemplate.queryForObject(
        selectPayloadSql, String.class, seed.tenantId(), seed.requestId());
    assertThat(firstPayload).doesNotContain(secret);
    Long definitionId = jdbcTemplate.queryForObject(
        selectDefinitionSql, Long.class, seed.tenantId(), seed.ownerRunId());
    Map<String, Object> changedParams = Map.of("apiToken", "replacement-secret");
    LaunchRequest changed = LaunchRequest.builder()
        .tenantId(seed.tenantId())
        .workflowDefinitionId(definitionId)
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.CATCH_UP)
        .requestId(seed.requestId())
        .params(changedParams)
        .build();

    assertThatThrownBy(() -> workflowRunLaunchService.launch(changed))
        .isInstanceOf(BizException.class);

    String retainedPayload = jdbcTemplate.queryForObject(
        selectPayloadSql, String.class, seed.tenantId(), seed.requestId());
    assertThat(retainedPayload).isEqualTo(firstPayload).doesNotContain("replacement-secret");
  }

  private record CapacityWaitSeed(String tenantId, String requestId, Long ownerRunId) {}

  private void insertRunnableTenant(String tenantId) {
    final String insertTenantSql = """
        insert into batch.tenant (tenant_id, tenant_name, status) values (?, ?, 'ACTIVE')\s\
        on conflict (tenant_id) do update set status = 'ACTIVE'\
        """;

    jdbcTemplate.update(insertTenantSql, tenantId, tenantId);
  }

  private CapacityWaitSeed seedCapacityWait() {
    return seedCapacityWait(testTenantId, "req-waiting-" + System.nanoTime());
  }

  private CapacityWaitSeed seedCapacityWait(String tenantId, String waitingRequestId) {
    return seedCapacityWait(tenantId, waitingRequestId, Map.of());
  }

  private CapacityWaitSeed seedCapacityWait(
      String tenantId, String waitingRequestId, Map<String, Object> waitingParams) {
    final String insertTriggerRequestSql = """
          insert into batch.trigger_request (tenant_id, request_id, trigger_type,
              workflow_definition_id, biz_date, dedup_key, request_status)
          values (?, ?, 'CATCH_UP', ?, ?, ?, 'ACCEPTED')
          """;
    final String selectIdSql = """
        select id from batch.workflow_run where tenant_id = ? and trigger_request_id = (select id\s\
        from batch.trigger_request where tenant_id = ? and request_id = ?)\
        """;

    DirectWorkflowSeed seed =
        seedDirectWorkflow(tenantId, seedChildJob(tenantId, "WG_WAIT_" + System.nanoTime()));
    LaunchRequest ownerLaunchRequest = LaunchRequest.builder()
        .tenantId(tenantId)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.API)
        .requestId(seed.requestId())
        .build();
    workflowRunLaunchService.launch(ownerLaunchRequest);
    jdbcTemplate.update(
        insertTriggerRequestSql,
        tenantId,
        waitingRequestId,
        seed.workflowDefinitionId(),
        BIZ_DATE,
        waitingRequestId);
    LaunchRequest waitingLaunchRequest = LaunchRequest.builder()
        .tenantId(tenantId)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.CATCH_UP)
        .requestId(waitingRequestId)
        .params(waitingParams)
        .build();
    workflowRunLaunchService.launch(waitingLaunchRequest);
    Long ownerRunId =
        jdbcTemplate.queryForObject(selectIdSql, Long.class, tenantId, tenantId, seed.requestId());
    return new CapacityWaitSeed(tenantId, waitingRequestId, ownerRunId);
  }

  private void finishCapacityOwner(CapacityWaitSeed seed) {
    final String updateWorkflowRunSql = """
        update batch.workflow_run set run_status = 'SUCCESS', finished_at = now() where id = ?
    """;

    jdbcTemplate.update(updateWorkflowRunSql, seed.ownerRunId());
  }

  private void assertRejectedCapacityWait(CapacityWaitSeed seed) {
    final String selectWaitStatusSql = """
        select wait_status from batch.workflow_launch_wait where tenant_id = ? and request_id = ?
    """;
    final String selectRequestStatusSql = """
        select request_status from batch.trigger_request where tenant_id = ? and request_id = ?
    """;
    final String selectCountSql = """
        select count(*) from batch.workflow_run where tenant_id = ? and trigger_request_id = (select id\s\
        from batch.trigger_request where tenant_id = ? and request_id = ?)\
        """;

    String selectedWaitStatus = jdbcTemplate.queryForObject(
        selectWaitStatusSql, String.class, seed.tenantId(), seed.requestId());
    assertThat(selectedWaitStatus).isEqualTo("REJECTED");
    String selectedRequestStatus = jdbcTemplate.queryForObject(
        selectRequestStatusSql, String.class, seed.tenantId(), seed.requestId());
    assertThat(selectedRequestStatus).isEqualTo("REJECTED");
    Integer selectedWorkflowRunCount = jdbcTemplate.queryForObject(
        selectCountSql, Integer.class, seed.tenantId(), seed.tenantId(), seed.requestId());
    assertThat(selectedWorkflowRunCount).isZero();
  }

  private void assertCapacityWaitResumed(CapacityWaitSeed seed) {
    final String selectWaitStatusSql = """
        select wait_status from batch.workflow_launch_wait where tenant_id = ? and request_id = ?
    """;
    final String selectRequestStatusSql = """
        select request_status from batch.trigger_request where tenant_id = ? and request_id = ?
    """;
    final String selectCountSql = """
        select count(*) from batch.workflow_run where tenant_id = ? and trigger_request_id = (select id\s\
        from batch.trigger_request where tenant_id = ? and request_id = ?)\
        """;

    String selectedWaitStatus = jdbcTemplate.queryForObject(
        selectWaitStatusSql, String.class, seed.tenantId(), seed.requestId());
    assertThat(selectedWaitStatus).isEqualTo("RESUMED");
    String selectedRequestStatus = jdbcTemplate.queryForObject(
        selectRequestStatusSql, String.class, seed.tenantId(), seed.requestId());
    assertThat(selectedRequestStatus).isEqualTo("LAUNCHED");
    Integer selectedWorkflowRunCount = jdbcTemplate.queryForObject(
        selectCountSql, Integer.class, seed.tenantId(), seed.tenantId(), seed.requestId());
    assertThat(selectedWorkflowRunCount).isEqualTo(1);
  }

  @Test
  @DisplayName("定时触发携带的 Workflow 版本已过期时拒绝按新定义启动")
  void scheduledWorkflowLaunch_shouldRejectStaleDefinitionVersion() {
    final String selectCountSql = """
        select count(*) from batch.workflow_run where tenant_id = ? and trigger_request_id = (select id\s\
        from batch.trigger_request where tenant_id = ? and request_id = ?)\
        """;

    String workerGroup = "WG_STALE_WF_" + System.nanoTime();
    DirectWorkflowSeed seed = seedDirectWorkflow(seedChildJob(workerGroup));
    LaunchRequest request = LaunchRequest.builder()
        .tenantId(testTenantId)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.SCHEDULED)
        .requestId(seed.requestId())
        .traceId("trace-stale-wf")
        .params(Map.of("workflowVersion", 2))
        .build();

    assertThatThrownBy(() -> workflowRunLaunchService.launch(request))
        .isInstanceOf(BizException.class);
    Long selectedWorkflowRunCount = jdbcTemplate.queryForObject(
        selectCountSql, Long.class, testTenantId, testTenantId, seed.requestId());
    assertThat(selectedWorkflowRunCount).isZero();
  }

  @Test
  @DisplayName("无根 Workflow 派发 JOB 节点时创建独立 Job Instance，不创建合成父实例或虚拟任务")
  void rootlessWorkflowDispatch_shouldCreateStandaloneNodeJobInstance() {
    final String selectStatementSql = """
          select ji.* from batch.job_instance ji
          join batch.workflow_node_run node_run on node_run.job_instance_id = ji.id
          where node_run.workflow_run_id = ? and node_run.node_code = 'JOB_NODE_1'
          """;
    final String selectCountSql = """
          select count(*) from batch.trigger_request tr
          join batch.job_instance ji on ji.trigger_request_id = tr.id
          where ji.id = ? and tr.parent_workflow_run_id = ?
            and tr.parent_node_code = 'JOB_NODE_1'
          """;

    String workerGroup = "WG_JN_ROOTLESS_" + System.nanoTime();
    String childCode = seedChildJob(workerGroup);
    DirectWorkflowSeed seed = seedDirectWorkflow(childCode);
    LaunchResponse launchResponse = workflowRunLaunchService.launch(LaunchRequest.builder()
        .tenantId(testTenantId)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.API)
        .requestId(seed.requestId())
        .traceId("trace-rootless-" + System.nanoTime())
        .params(Map.of())
        .build());
    assertThat(launchResponse.instanceNo()).isNotBlank();
    Long runId = workflowRunId(seed.requestId());
    WorkflowRunEntity workflowRun = workflowRunMapper.selectById(testTenantId, runId);
    JobInstanceEntity childInstance = jdbcTemplate.queryForObject(
        selectStatementSql,
        (rs, rowNum) -> {
          JobInstanceEntity entity = new JobInstanceEntity();
          entity.setId(rs.getLong("id"));
          entity.setJobCode(rs.getString("job_code"));
          entity.setTenantId(rs.getString("tenant_id"));
          Long parentInstanceId = rs.getObject("parent_instance_id", Long.class);
          entity.setParentInstanceId(parentInstanceId);
          return entity;
        },
        runId);
    assertThat(childInstance).isNotNull();
    assertThat(childInstance.getJobCode()).isEqualTo(childCode);
    assertThat(childInstance.getParentInstanceId()).isNull();
    assertThat(workflowRunMapper
            .selectById(testTenantId, workflowRun.getId())
            .getRelatedJobInstanceId())
        .isNull();
    Long selectedTriggerRequestCount =
        jdbcTemplate.queryForObject(selectCountSql, Long.class, childInstance.getId(), runId);
    assertThat(selectedTriggerRequestCount).isEqualTo(1L);
  }

  @Test
  @DisplayName("冻结快照中的 Job 节点不因 Workflow 定义后续编辑而被实时节点校验拒绝")
  void frozenWorkflowNodeLaunch_shouldNotDependOnCurrentNodeDefinition() {
    final String insertWorkflowNodeRunSql = """
          insert into batch.workflow_node_run (
              workflow_run_id, node_code, node_type, run_seq, node_status, retry_count,
              duration_ms, started_at
          ) values (?, 'JOB_NODE_1', 'JOB', 1, 'READY', 0, 0, now())
          """;
    final String updateWorkflowNodeSql = """
          update batch.workflow_node
          set related_job_code = 'REPLACED_JOB'
          where workflow_definition_id = ? and node_code = 'JOB_NODE_1'
          """;

    String workerGroup = "WG_FROZEN_NODE_" + System.nanoTime();
    String childCode = seedChildJob(workerGroup);
    WorkflowRunEntity workflowRun = seedRootlessWorkflow(childCode);
    jdbcTemplate.update(insertWorkflowNodeRunSql, workflowRun.getId());
    jdbcTemplate.update(updateWorkflowNodeSql, workflowRun.getWorkflowDefinitionId());

    assertThat(triggerRequestMapper.isValidParentWorkflowNodeLaunch(
            TriggerRequestIsValidParentWorkflowNodeLaunchParam.builder()
                .tenantId(testTenantId)
                .parentWorkflowRunId(workflowRun.getId())
                .parentNodeCode("JOB_NODE_1")
                .existingChildJobInstanceId(null)
                .frozenWorkflowSnapshot(true)
                .build()))
        .isTrue();
    assertThat(triggerRequestMapper.isValidParentWorkflowNodeLaunch(
            TriggerRequestIsValidParentWorkflowNodeLaunchParam.builder()
                .tenantId(testTenantId)
                .parentWorkflowRunId(workflowRun.getId())
                .parentNodeCode("JOB_NODE_1")
                .existingChildJobInstanceId(null)
                .frozenWorkflowSnapshot(false)
                .build()))
        .isFalse();
  }

  @Test
  @DisplayName("根 Workflow 将上游 JOB 成功产物解析为下游 JOB 参数")
  void directWorkflow_shouldPassUpstreamOutputToNextJob() {
    String insertNodeSql = """
        insert into batch.workflow_node (
            tenant_id, workflow_definition_id, node_code, node_name, node_type,
            related_job_code, node_order, node_params, retry_policy, retry_max_count,
            timeout_seconds, enabled
        ) values (?, ?, 'JOB_NODE_2', 'Second Job Node', 'JOB', ?, 2,
            '{"inputFileId":"$.nodes.JOB_NODE_1.output.fileId","upstreamStatus":"$.nodes.JOB_NODE_1.output.statusCode","optionalMode":"node-default"}'::jsonb, 'NONE', 0, 0, true)
        """;
    String deleteEdgeSql = """
        delete from batch.workflow_edge
        where workflow_definition_id = ? and from_node_code = 'JOB_NODE_1' and to_node_code = 'END'
        """;
    String insertEdgesSql = """
        insert into batch.workflow_edge (
            tenant_id, workflow_definition_id, from_node_code, to_node_code, edge_type, enabled
        ) values (?, ?, 'JOB_NODE_1', 'JOB_NODE_2', 'SUCCESS', true),
                 (?, ?, 'JOB_NODE_2', 'END', 'SUCCESS', true)
        """;
    String selectRunSql = """
        select id from batch.workflow_run where tenant_id = ? and trigger_request_id =
          (select id from batch.trigger_request where tenant_id = ? and request_id = ?)
        """;
    String selectTaskSql = """
        select id from batch.job_task where tenant_id = ? and job_instance_id = ?
        """;
    String startTaskSql = """
        update batch.job_task set task_status = 'RUNNING', started_at = ? where id = ?
        """;
    String selectOutputSql = """
        select output ->> 'fileId' from batch.workflow_node_run
        where workflow_run_id = ? and node_code = 'JOB_NODE_1'
        """;
    String selectParamsSql = """
        select params_snapshot -> 'effectiveParams' ->> 'inputFileId'
        from batch.job_instance where tenant_id = ? and id = ?
        """;
    String selectTaskPayloadSql = """
        select task_payload ->> 'inputFileId' from batch.job_task where tenant_id = ? and job_instance_id = ?
        """;
    String selectExplicitNullSql = """
        select jsonb_typeof(params_snapshot -> 'effectiveParams' -> 'optionalMode')
        from batch.job_instance where tenant_id = ? and id = ?
        """;
    String selectWorkerSql = """
        select assigned_worker_code from batch.job_task where tenant_id = ? and id = ?
        """;
    String workerGroup = "WG_OUTPUT_CHAIN_" + System.nanoTime();
    String firstJobCode = seedChildJob(workerGroup);
    String secondJobCode = seedChildJob(workerGroup);
    DirectWorkflowSeed seed = seedDirectWorkflow(firstJobCode);
    jdbcTemplate.update(insertNodeSql, testTenantId, seed.workflowDefinitionId(), secondJobCode);
    jdbcTemplate.update(deleteEdgeSql, seed.workflowDefinitionId());
    jdbcTemplate.update(
        insertEdgesSql,
        testTenantId,
        seed.workflowDefinitionId(),
        testTenantId,
        seed.workflowDefinitionId());
    Map<String, Object> rootParams = new LinkedHashMap<>();
    rootParams.put("optionalMode", null);
    workflowRunLaunchService.launch(LaunchRequest.builder()
        .tenantId(testTenantId)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.API)
        .requestId(seed.requestId())
        .traceId("trace-output-chain")
        .params(rootParams)
        .build());
    Long workflowRunId = jdbcTemplate.queryForObject(
        selectRunSql, Long.class, testTenantId, testTenantId, seed.requestId());
    Long firstInstanceId = linkedNodeJobInstance(workflowRunId, "JOB_NODE_1");
    Long taskId =
        jdbcTemplate.queryForObject(selectTaskSql, Long.class, testTenantId, firstInstanceId);
    jdbcTemplate.update(startTaskSql, Timestamp.from(BatchDateTimeSupport.utcNow()), taskId);
    taskOutcomeService.applyTaskOutcome(TaskOutcomeCommand.builder()
        .tenantId(testTenantId)
        .taskId(taskId)
        .success(true)
        .outputs(Map.of("fileId", 12345, "statusCode", 200))
        .build());

    assertThat(jdbcTemplate.queryForObject(selectOutputSql, String.class, workflowRunId))
        .isEqualTo("12345");
    Long secondInstanceId = linkedNodeJobInstance(workflowRunId, "JOB_NODE_2");
    assertThat(jdbcTemplate.queryForObject(
            selectParamsSql, String.class, testTenantId, secondInstanceId))
        .isEqualTo("12345");
    assertThat(jdbcTemplate.queryForObject(
            selectTaskPayloadSql, String.class, testTenantId, secondInstanceId))
        .isEqualTo("12345");
    assertThat(jdbcTemplate.queryForObject(
            selectExplicitNullSql, String.class, testTenantId, secondInstanceId))
        .isEqualTo("null");
    Long secondTaskId =
        jdbcTemplate.queryForObject(selectTaskSql, Long.class, testTenantId, secondInstanceId);
    String workerCode =
        jdbcTemplate.queryForObject(selectWorkerSql, String.class, testTenantId, secondTaskId);
    var claimed = taskControllerApplicationService.claim(
        secondTaskId, new TaskClaimCommand(testTenantId, workerCode, null));
    Map<?, ?> claimedPayload = JsonUtils.fromJson(claimed.payload(), Map.class);
    assertThat(claimedPayload.containsKey("optionalMode")).isTrue();
    assertThat(claimedPayload.get("optionalMode")).isNull();
    assertThat(claimedPayload.get("inputFileId")).isEqualTo(12345);
    assertThat(claimedPayload.get("upstreamStatus")).isEqualTo(200);
  }

  @Test
  @DisplayName("组合 Workflow 中 Job 报告成功后启动下一 Job，最后整体收敛")
  void directWorkflow_shouldAdvanceAcrossIndependentJobInstances() {
    final String updateWorkflowDefinitionSql = """
        update batch.workflow_definition set calendar_code = 'WF_CALENDAR' where id = ?
    """;
    final String updateJobDefinitionSql = """
        update batch.job_definition set calendar_code = 'JOB_CALENDAR' where job_code in (?, ?)
    """;
    final String insertWorkflowNodeSql = """
          insert into batch.workflow_node (
              tenant_id, workflow_definition_id, node_code, node_name, node_type,
              related_job_code, node_order, retry_policy, retry_max_count, timeout_seconds, enabled
          ) values (?, ?, 'JOB_NODE_2', 'Second Job Node', 'JOB', ?, 2, 'NONE', 0, 0, true)
          """;
    final String deleteWorkflowEdgeSql = """
          delete from batch.workflow_edge
          where workflow_definition_id = ? and from_node_code = 'JOB_NODE_1' and to_node_code = 'END'
          """;
    final String insertWorkflowEdgeSql = """
          insert into batch.workflow_edge (
              tenant_id, workflow_definition_id, from_node_code, to_node_code, edge_type, enabled
          ) values
              (?, ?, 'JOB_NODE_1', 'JOB_NODE_2', 'SUCCESS', true),
              (?, ?, 'JOB_NODE_2', 'END', 'SUCCESS', true)
          """;
    final String selectIdSql = """
        select id from batch.workflow_run where tenant_id = ? and trigger_request_id = (select id\s\
        from batch.trigger_request where tenant_id = ? and request_id = ?)\
        """;
    final String selectCalendarCodeSql = """
        select calendar_code from batch.job_instance where id = ?
    """;
    final String selectRunStatusSql = """
        select run_status from batch.workflow_run where id = ?
    """;
    final String selectCountSql = """
              select count(*) from batch.workflow_node_run
              where workflow_run_id = ? and node_code in ('JOB_NODE_1', 'JOB_NODE_2')
                and node_status = 'SUCCESS'
              """;

    String workerGroup = "WG_ROOT_CHAIN_" + System.nanoTime();
    String firstJobCode = seedChildJob(workerGroup);
    String secondJobCode = seedChildJob(workerGroup);
    DirectWorkflowSeed seed = seedDirectWorkflow(firstJobCode);
    jdbcTemplate.update(updateWorkflowDefinitionSql, seed.workflowDefinitionId());
    jdbcTemplate.update(updateJobDefinitionSql, firstJobCode, secondJobCode);
    jdbcTemplate.update(
        insertWorkflowNodeSql, testTenantId, seed.workflowDefinitionId(), secondJobCode);
    jdbcTemplate.update(deleteWorkflowEdgeSql, seed.workflowDefinitionId());
    jdbcTemplate.update(
        insertWorkflowEdgeSql,
        testTenantId,
        seed.workflowDefinitionId(),
        testTenantId,
        seed.workflowDefinitionId());

    LaunchRequest launchRequest = LaunchRequest.builder()
        .tenantId(testTenantId)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.API)
        .requestId(seed.requestId())
        .traceId("trace-root-chain")
        .params(Map.of())
        .build();
    workflowRunLaunchService.launch(launchRequest);
    Long workflowRunId = jdbcTemplate.queryForObject(
        selectIdSql, Long.class, testTenantId, testTenantId, seed.requestId());

    Long firstInstanceId = linkedNodeJobInstance(workflowRunId, "JOB_NODE_1");
    assertThat(firstInstanceId).isNotNull();
    String selectedCalendarCode =
        jdbcTemplate.queryForObject(selectCalendarCodeSql, String.class, firstInstanceId);
    assertThat(selectedCalendarCode).isEqualTo("WF_CALENDAR");
    reportJobSuccess(firstInstanceId);

    Long secondInstanceId = linkedNodeJobInstance(workflowRunId, "JOB_NODE_2");
    assertThat(secondInstanceId).isNotNull();
    String selectedCalendarCodeRead2 =
        jdbcTemplate.queryForObject(selectCalendarCodeSql, String.class, secondInstanceId);
    assertThat(selectedCalendarCodeRead2).isEqualTo("WF_CALENDAR");
    String selectedRunStatus =
        jdbcTemplate.queryForObject(selectRunStatusSql, String.class, workflowRunId);
    assertThat(selectedRunStatus).isEqualTo("RUNNING");
    reportJobSuccess(secondInstanceId);

    String selectedRunStatusRead2 =
        jdbcTemplate.queryForObject(selectRunStatusSql, String.class, workflowRunId);
    assertThat(selectedRunStatusRead2).isEqualTo("SUCCESS");
    Long selectedWorkflowNodeRunCount =
        jdbcTemplate.queryForObject(selectCountSql, Long.class, workflowRunId);
    assertThat(selectedWorkflowNodeRunCount).isEqualTo(2L);
  }

  @Test
  @DisplayName("组合 Workflow 并行 Job 汇合前不结束, 最后一个分支完成后只写入一个 END")
  void directWorkflow_shouldWaitForEveryParallelJobBeforeEnd() {
    final String insertTenantQuotaPolicySql = """
          insert into batch.tenant_quota_policy (
              tenant_id, policy_code, max_running_jobs_per_tenant, max_partitions_per_tenant,
              max_qps_per_tenant, fair_share_weight, enabled, description, burst_limit,
              partition_burst_limit, quota_reset_policy, group_shared_max_running_jobs,
              exceeded_strategy
          ) values (?, ?, 1, 0, 0, 1, true, 'R21 parallel JOB quota test',
                    0, 0, 'NONE', 0, 'QUEUE_DEFER')
          """;
    final String updateWorkflowNodeSql = """
          update batch.workflow_node
          set node_order = 3
          where workflow_definition_id = ? and node_code = 'END'
          """;
    final String insertWorkflowNodeSql = """
          insert into batch.workflow_node (
              tenant_id, workflow_definition_id, node_code, node_name, node_type,
              related_job_code, node_order, retry_policy, retry_max_count, timeout_seconds, enabled
          ) values (?, ?, 'JOB_NODE_2', 'Parallel Job Node', 'JOB', ?, 2, 'NONE', 0, 0, true)
          """;
    final String insertWorkflowEdgeSql = """
          insert into batch.workflow_edge (
              tenant_id, workflow_definition_id, from_node_code, to_node_code, edge_type, enabled
          ) values
              (?, ?, 'START', 'JOB_NODE_2', 'ALWAYS', true),
              (?, ?, 'JOB_NODE_2', 'END', 'SUCCESS', true)
          """;
    final String selectIdSql = """
        select id from batch.workflow_run where tenant_id = ? and trigger_request_id = (select id\s\
        from batch.trigger_request where tenant_id = ? and request_id = ?)\
        """;
    final String selectCountSql = """
          select count(*)
          from batch.workflow_node_run node_run
          join batch.job_instance instance
            on instance.id = node_run.job_instance_id
          where node_run.workflow_run_id = ?
            and instance.tenant_id = ?
            and node_run.node_code in ('JOB_NODE_1', 'JOB_NODE_2')
            and instance.instance_status in ('READY', 'RUNNING')
          """;
    final String selectInstanceStatusSql = """
      select instance_status from batch.job_instance where id = ?
  """;
    final String selectFinishedAtSql = """
      select finished_at from batch.job_instance where id = ?
  """;
    final String selectInstanceStatusSql2 = """
            select instance.instance_status as instance_status,
                   partition.partition_status as partition_status,
                   task.task_status as task_status
            from batch.job_instance instance
            join batch.job_partition partition
              on partition.tenant_id = instance.tenant_id
             and partition.job_instance_id = instance.id
            join batch.job_task task
              on task.tenant_id = partition.tenant_id
             and task.job_partition_id = partition.id
            where instance.tenant_id = ? and instance.id = ?
            """;
    final String selectCountSql2 = """
            select count(*)
            from batch.workflow_node_run node_run
            join batch.job_instance instance
              on instance.id = node_run.job_instance_id
            where node_run.workflow_run_id = ?
              and instance.tenant_id = ?
              and node_run.node_code in ('JOB_NODE_1', 'JOB_NODE_2')
              and instance.instance_status in ('READY', 'RUNNING')
            """;
    final String selectRunStatusSql = """
      select run_status from batch.workflow_run where id = ?
  """;
    final String selectFinishedAtSql2 = """
      select finished_at from batch.workflow_run where id = ?
  """;
    final String selectCountSql3 = """
        select count(*) from batch.workflow_node_run
        where workflow_run_id = ? and node_code = 'END'
        """;
    final String selectCountSql4 = """
        select count(*) from batch.workflow_node_run
        where workflow_run_id = ? and node_code = 'END' and node_status = 'SUCCESS'
        """;

    String workerGroup = "WG_ROOT_PARALLEL_" + System.nanoTime();
    String firstJobCode = seedChildJob(workerGroup);
    String secondJobCode = seedChildJob(workerGroup);
    DirectWorkflowSeed seed = seedDirectWorkflow(firstJobCode);
    String policyCode = "parallel-job-quota-" + System.nanoTime();
    jdbcTemplate.update(insertTenantQuotaPolicySql, testTenantId, policyCode);
    // 本用例直接写 SQL，须同步失效实际调度的负缓存，不能以绕过缓存的 live 快照代替准入配置。
    configCacheService.evictQuotaPolicies(testTenantId);
    TenantQuotaPolicyEntity schedulingPolicy =
        configCacheService.findEnabledQuotaPolicy(testTenantId);
    assertThat(schedulingPolicy).isNotNull();
    assertThat(schedulingPolicy.policyCode()).isEqualTo(policyCode);
    assertThat(schedulingPolicy.maxRunningJobsPerTenant()).isEqualTo(1);
    jdbcTemplate.update(updateWorkflowNodeSql, seed.workflowDefinitionId());
    jdbcTemplate.update(
        insertWorkflowNodeSql, testTenantId, seed.workflowDefinitionId(), secondJobCode);
    jdbcTemplate.update(
        insertWorkflowEdgeSql,
        testTenantId,
        seed.workflowDefinitionId(),
        testTenantId,
        seed.workflowDefinitionId());

    LaunchRequest launchRequest = LaunchRequest.builder()
        .tenantId(testTenantId)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.API)
        .requestId(seed.requestId())
        .traceId("trace-root-parallel")
        .params(Map.of())
        .build();
    var quotaSnapshotBeforeLaunch =
        tenantSchedulerSnapshotService.buildLive(testTenantId).policies().stream()
            .filter(policy -> policy.policyCode().equals(policyCode))
            .findFirst()
            .orElseThrow();
    var scheduleDecisions = new CopyOnWriteArrayList<Object>();
    Mockito.doAnswer(invocation -> {
          Object decision = invocation.callRealMethod();
          scheduleDecisions.add(decision);
          return decision;
        })
        .when(resourceScheduler)
        .schedule(Mockito.any(ResourceSchedulingRequest.class));
    Mockito.clearInvocations(resourceScheduler);
    workflowRunLaunchService.launch(launchRequest);
    Long workflowRunId = jdbcTemplate.queryForObject(
        selectIdSql, Long.class, testTenantId, testTenantId, seed.requestId());
    Long firstInstanceId = linkedNodeJobInstance(workflowRunId, "JOB_NODE_1");
    Long secondInstanceId = linkedNodeJobInstance(workflowRunId, "JOB_NODE_2");
    assertThat(firstInstanceId).isNotNull().isNotEqualTo(secondInstanceId);
    assertThat(taskNodeCode(firstInstanceId)).isEqualTo("JOB_NODE_1");
    assertThat(taskNodeCode(secondInstanceId)).isEqualTo("JOB_NODE_2");
    Long selectedWorkflowNodeRunCount =
        jdbcTemplate.queryForObject(selectCountSql, Long.class, workflowRunId, testTenantId);
    assertThat(selectedWorkflowNodeRunCount)
        .as("parallel JOB nodes admitted under a tenant cap of one")
        .isEqualTo(1L);
    var quotaSnapshotWhileSecondJobWaits =
        tenantSchedulerSnapshotService.buildLive(testTenantId).policies().stream()
            .filter(policy -> policy.policyCode().equals(policyCode))
            .findFirst()
            .orElseThrow();
    assertThat(quotaSnapshotWhileSecondJobWaits.activeJobs())
        .isEqualTo(quotaSnapshotBeforeLaunch.activeJobs() + 1);
    assertThat(quotaSnapshotWhileSecondJobWaits.activePartitions())
        .as("WAITING partitions do not consume active worker partition quota")
        .isEqualTo(quotaSnapshotBeforeLaunch.activePartitions() + 1);
    assertThat(activePartitionsForWorkflow(workflowRunId))
        .isEqualTo(quotaSnapshotWhileSecondJobWaits.activePartitions()
            - quotaSnapshotBeforeLaunch.activePartitions());
    Mockito.verify(resourceScheduler, Mockito.times(2))
        .schedule(Mockito.any(ResourceSchedulingRequest.class));
    assertThat(
            jobPartitionMapper.selectWaitingPartitionsGlobal(100, PartitionStatus.WAITING.code()))
        .extracting(JobPartitionEntity::getJobInstanceId)
        .contains(secondInstanceId);
    List<Long> waitingInstancesOnWorkerThread = CompletableFuture.supplyAsync(() ->
            jobPartitionMapper.selectWaitingPartitionsGlobal(100, PartitionStatus.WAITING.code()))
        .thenApply(partitions ->
            partitions.stream().map(JobPartitionEntity::getJobInstanceId).toList())
        .orTimeout(5, TimeUnit.SECONDS)
        .join();
    assertThat(waitingInstancesOnWorkerThread)
        .as("global WAITING scan is visible without the test thread's tenant context")
        .contains(secondInstanceId);

    // 启动空扫仍受最小持锁期保护；先确认真实锁已可用，再测提交后 kick，避免测试的长周期掩盖一次竞争。
    await().atMost(Duration.ofSeconds(10)).until(() -> {
      LockConfiguration probeConfiguration = new LockConfiguration(
          Instant.now(), "waiting_partition_dispatch", Duration.ofSeconds(2), Duration.ZERO);
      Optional<SimpleLock> probe = lockProvider.lock(probeConfiguration);
      if (probe.isEmpty()) {
        return false;
      }
      SimpleLock acquiredProbe = probe.get();
      try {
        return true;
      } finally {
        acquiredProbe.unlock();
      }
    });
    int scheduleDecisionCountBeforeRelease = scheduleDecisions.size();
    double requestedKicksBefore =
        meterRegistry.counter("batch.scheduler.waiting_dispatch.kick.requested").count();
    double executedKicksBefore =
        meterRegistry.counter("batch.scheduler.waiting_dispatch.kick.executed").count();
    reportJobSuccess(firstInstanceId);

    String selectedInstanceStatus =
        jdbcTemplate.queryForObject(selectInstanceStatusSql, String.class, firstInstanceId);
    assertThat(selectedInstanceStatus).isEqualTo("SUCCESS");
    Timestamp selectedFinishedAt =
        jdbcTemplate.queryForObject(selectFinishedAtSql, Timestamp.class, firstInstanceId);
    assertThat(selectedFinishedAt).isNotNull();
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(meterRegistry
                .counter("batch.scheduler.waiting_dispatch.kick.requested")
                .count())
            .as("a committed task outcome schedules a capacity-release kick")
            .isGreaterThan(requestedKicksBefore));
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(meterRegistry
                .counter("batch.scheduler.waiting_dispatch.kick.executed")
                .count())
            .as("the queued capacity-release kick executes")
            .isGreaterThan(executedKicksBefore));
    await()
        .atMost(Duration.ofSeconds(15))
        .untilAsserted(() -> assertThat(scheduleDecisions.size())
            .as(
                "capacity release should reevaluate the waiting candidate; decisions=%s;"
                    + " mapperWaitingInstances=%s",
                scheduleDecisions,
                jobPartitionMapper
                    .selectWaitingPartitionsGlobal(100, PartitionStatus.WAITING.code())
                    .stream()
                    .map(JobPartitionEntity::getJobInstanceId)
                    .toList())
            .isGreaterThan(scheduleDecisionCountBeforeRelease));
    await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
      Map<String, Object> secondJobState =
          jdbcTemplate.queryForMap(selectInstanceStatusSql2, testTenantId, secondInstanceId);
      Long selectedWorkflowNodeRunCountRead2 =
          jdbcTemplate.queryForObject(selectCountSql2, Long.class, workflowRunId, testTenantId);
      assertThat(selectedWorkflowNodeRunCountRead2)
          .as(
              "the waiting parallel JOB enters active quota after the first completes; state=%s,"
                  + " scheduleDecisions=%s",
              secondJobState, scheduleDecisions)
          .isEqualTo(1L);
      var quotaSnapshotAfterRelease =
          tenantSchedulerSnapshotService.buildLive(testTenantId).policies().stream()
              .filter(policy -> policy.policyCode().equals(policyCode))
              .findFirst()
              .orElseThrow();
      assertThat(quotaSnapshotAfterRelease.activeJobs())
          .isEqualTo(quotaSnapshotBeforeLaunch.activeJobs() + 1);
      assertThat(quotaSnapshotAfterRelease.activePartitions())
          .isEqualTo(quotaSnapshotBeforeLaunch.activePartitions() + 1);
      assertThat(activePartitionsForWorkflow(workflowRunId))
          .isEqualTo(quotaSnapshotAfterRelease.activePartitions()
              - quotaSnapshotBeforeLaunch.activePartitions());
      Mockito.verify(resourceScheduler, Mockito.atLeast(3))
          .schedule(Mockito.any(ResourceSchedulingRequest.class));
    });

    String selectedRunStatus =
        jdbcTemplate.queryForObject(selectRunStatusSql, String.class, workflowRunId);
    assertThat(selectedRunStatus).isEqualTo("RUNNING");
    Timestamp selectedFinishedAtRead2 =
        jdbcTemplate.queryForObject(selectFinishedAtSql2, Timestamp.class, workflowRunId);
    assertThat(selectedFinishedAtRead2).isNull();
    Long selectedWorkflowNodeRunCountRead3 =
        jdbcTemplate.queryForObject(selectCountSql3, Long.class, workflowRunId);
    assertThat(selectedWorkflowNodeRunCountRead3).isZero();

    reportJobSuccess(secondInstanceId);

    String selectedRunStatusRead2 =
        jdbcTemplate.queryForObject(selectRunStatusSql, String.class, workflowRunId);
    assertThat(selectedRunStatusRead2).isEqualTo("SUCCESS");
    Long selectedWorkflowNodeRunCountRead4 =
        jdbcTemplate.queryForObject(selectCountSql4, Long.class, workflowRunId);
    assertThat(selectedWorkflowNodeRunCountRead4).isEqualTo(1L);
  }

  @Test
  @DisplayName("同一 Workflow 两个并行节点引用同一 Job 时保留独立实例并各自推进")
  void directWorkflow_shouldKeepSameJobInstancesIndependentAcrossParallelNodes() {
    final String updateWorkflowNodeSql = """
          update batch.workflow_node
          set node_order = 3
          where workflow_definition_id = ? and node_code = 'END'
          """;
    final String insertWorkflowNodeSql = """
          insert into batch.workflow_node (
              tenant_id, workflow_definition_id, node_code, node_name, node_type,
              related_job_code, node_order, retry_policy, retry_max_count, timeout_seconds, enabled
          ) values (?, ?, 'JOB_NODE_2', 'Second Same Job Node', 'JOB', ?, 2, 'NONE', 0, 0, true)
          """;
    final String insertWorkflowEdgeSql = """
          insert into batch.workflow_edge (
              tenant_id, workflow_definition_id, from_node_code, to_node_code, edge_type, enabled
          ) values
              (?, ?, 'START', 'JOB_NODE_2', 'ALWAYS', true),
              (?, ?, 'JOB_NODE_2', 'END', 'SUCCESS', true)
          """;
    final String selectNodeCodeSql = """
          select node_code, job_instance_id
          from batch.workflow_node_run
          where workflow_run_id = ? and node_code in ('JOB_NODE_1', 'JOB_NODE_2')
          order by node_code
          """;
    final String selectCountSql = """
          select count(*) from batch.job_instance
          where tenant_id = ? and id in (?, ?) and job_code = ? and biz_date = ?
          """;
    final String selectCountSql2 = """
        select count(distinct job_instance_id) from batch.job_task
        where job_instance_id in (?, ?)
        """;
    final String selectVersionNoSql = """
          select version_no, status, job_instance_id
          from batch.result_version
          where tenant_id = ? and business_key = ?
          order by version_no
          """;
    final String selectRunStatusSql = """
      select run_status from batch.workflow_run where id = ?
  """;
    final String selectNodeStatusSql = """
          select node_status from batch.workflow_node_run
          where workflow_run_id = ? and node_code = 'JOB_NODE_2'
          """;
    final String selectCountSql3 = """
        select count(*) from batch.workflow_node_run
        where workflow_run_id = ? and node_code = 'END'
        """;
    final String selectCountSql4 = """
        select count(*) from batch.workflow_node_run
        where workflow_run_id = ? and node_code = 'END' and node_status = 'SUCCESS'
        """;

    String workerGroup = "WG_ROOT_SAME_JOB_PARALLEL_" + System.nanoTime();
    String jobCode = seedChildJob(workerGroup);
    DirectWorkflowSeed seed = seedDirectWorkflow(jobCode);
    jdbcTemplate.update(updateWorkflowNodeSql, seed.workflowDefinitionId());
    jdbcTemplate.update(insertWorkflowNodeSql, testTenantId, seed.workflowDefinitionId(), jobCode);
    jdbcTemplate.update(
        insertWorkflowEdgeSql,
        testTenantId,
        seed.workflowDefinitionId(),
        testTenantId,
        seed.workflowDefinitionId());

    LaunchRequest launchRequest = LaunchRequest.builder()
        .tenantId(testTenantId)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.API)
        .requestId(seed.requestId())
        .traceId("trace-root-same-job-parallel-" + System.nanoTime())
        .params(Map.of())
        .build();
    workflowRunLaunchService.launch(launchRequest);

    Long workflowRunId = workflowRunId(seed.requestId());
    Long firstInstanceId = linkedNodeJobInstance(workflowRunId, "JOB_NODE_1");
    Long secondInstanceId = linkedNodeJobInstance(workflowRunId, "JOB_NODE_2");
    assertThat(firstInstanceId).isNotNull().isNotEqualTo(secondInstanceId);
    var nodeRuns = jdbcTemplate.queryForList(selectNodeCodeSql, workflowRunId);
    assertThat(nodeRuns).hasSize(2);
    assertThat(nodeRuns)
        .extracting(row -> row.get("node_code"))
        .containsExactly("JOB_NODE_1", "JOB_NODE_2");
    assertThat(nodeRuns)
        .extracting(row -> row.get("job_instance_id"))
        .containsExactlyInAnyOrder(firstInstanceId, secondInstanceId);
    Long selectedJobInstanceCount = jdbcTemplate.queryForObject(
        selectCountSql,
        Long.class,
        testTenantId,
        firstInstanceId,
        secondInstanceId,
        jobCode,
        BIZ_DATE);
    assertThat(selectedJobInstanceCount).isEqualTo(2L);
    assertThat(taskNodeCode(firstInstanceId)).isEqualTo("JOB_NODE_1");
    assertThat(taskNodeCode(secondInstanceId)).isEqualTo("JOB_NODE_2");
    Long selectedCount =
        jdbcTemplate.queryForObject(selectCountSql2, Long.class, firstInstanceId, secondInstanceId);
    assertThat(selectedCount).isEqualTo(2L);

    reportJobSuccess(firstInstanceId);
    String resultBusinessKey = "job:" + jobCode + ":" + BIZ_DATE;
    List<Map<String, Object>> firstResultVersions =
        jdbcTemplate.queryForList(selectVersionNoSql, testTenantId, resultBusinessKey);
    assertThat(firstResultVersions).hasSize(1);
    assertThat(firstResultVersions).extracting(row -> row.get("version_no")).containsExactly(1);
    assertThat(firstResultVersions)
        .extracting(row -> row.get("status"))
        .containsExactly("EFFECTIVE");
    assertThat(firstResultVersions)
        .extracting(row -> row.get("job_instance_id"))
        .containsExactly(firstInstanceId);
    String selectedRunStatus =
        jdbcTemplate.queryForObject(selectRunStatusSql, String.class, workflowRunId);
    assertThat(selectedRunStatus).isEqualTo("RUNNING");
    String selectedNodeStatus =
        jdbcTemplate.queryForObject(selectNodeStatusSql, String.class, workflowRunId);
    assertThat(selectedNodeStatus).isEqualTo("READY");
    Long selectedWorkflowNodeRunCount =
        jdbcTemplate.queryForObject(selectCountSql3, Long.class, workflowRunId);
    assertThat(selectedWorkflowNodeRunCount).isZero();

    reportJobSuccess(secondInstanceId);
    List<Map<String, Object>> resultVersions =
        jdbcTemplate.queryForList(selectVersionNoSql, testTenantId, resultBusinessKey);
    assertThat(resultVersions).hasSize(2);
    assertThat(resultVersions).extracting(row -> row.get("version_no")).containsExactly(1, 2);
    assertThat(resultVersions)
        .extracting(row -> row.get("status"))
        .containsExactly("SUPERSEDED", "EFFECTIVE");
    assertThat(resultVersions)
        .extracting(row -> row.get("job_instance_id"))
        .containsExactly(firstInstanceId, secondInstanceId);
    String selectedRunStatusRead2 =
        jdbcTemplate.queryForObject(selectRunStatusSql, String.class, workflowRunId);
    assertThat(selectedRunStatusRead2).isEqualTo("SUCCESS");
    Long selectedWorkflowNodeRunCountRead2 =
        jdbcTemplate.queryForObject(selectCountSql4, Long.class, workflowRunId);
    assertThat(selectedWorkflowNodeRunCountRead2).isOne();
  }

  @ParameterizedTest(name = "{0} 时人工 skip 释放下游 JOB 与结算最终收敛")
  @ValueSource(strings = {"SETTLEMENT_FIRST", "SKIP_FIRST"})
  @DisplayName("人工 skip 释放下游 JOB 与业务日结算按两种提交顺序竞争")
  void directWorkflow_manualSkipReleasingJobRacingSettlementShouldConverge(String firstCommit)
      throws Exception {
    final String selectAcquireBatchDaySettlementFenceSql = """
            select batch.acquire_batch_day_settlement_fence(?, ?, ?)
        """;
    final String selectPgBackendPidSql = """
            select pg_backend_pid()
        """;

    final String insertBusinessCalendarSql = """
            insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone, enabled)\s\
            values (?, ?, ?, 'UTC', true)\
            """;
    final String updateWorkflowDefinitionSql = """
            update batch.workflow_definition set calendar_code = ? where id = ?
        """;
    final String updateWorkflowNodeSql = """
    update batch.workflow_node
    set node_order = 3
    where tenant_id = ? and workflow_definition_id = ? and node_code = 'END'
    """;
    final String insertWorkflowNodeSql = """
    insert into batch.workflow_node (
    tenant_id, workflow_definition_id, node_code, node_name, node_type,
    related_job_code, node_order, retry_policy, retry_max_count, timeout_seconds, enabled
    ) values (?, ?, 'JOB_NODE_2', 'Second Job Node', 'JOB', ?, 2, 'NONE', 0, 0, true)
    """;
    final String deleteWorkflowEdgeSql = """
    delete from batch.workflow_edge
    where tenant_id = ? and workflow_definition_id = ?
    and from_node_code = 'JOB_NODE_1' and to_node_code = 'END'
    """;
    final String insertWorkflowEdgeSql = """
    insert into batch.workflow_edge (
    tenant_id, workflow_definition_id, from_node_code, to_node_code, edge_type, enabled
    ) values
    (?, ?, 'JOB_NODE_1', 'JOB_NODE_2', 'SUCCESS', true),
    (?, ?, 'JOB_NODE_2', 'END', 'SUCCESS', true)
    """;
    final String updateWorkflowNodeRunSql = """
    update batch.workflow_node_run
    set node_status = 'FAILED', finished_at = current_timestamp
    where workflow_run_id = ? and node_code = 'JOB_NODE_1'
    """;
    final String updateBatchDayInstanceSql = """
    update batch.batch_day_instance
    set day_status = 'CUTOFF', cutoff_at = current_timestamp - interval '1 second',
    frozen = false, version = version + 1, updated_at = current_timestamp
    where tenant_id = ? and calendar_code = ? and biz_date = ?
    """;
    final String selectNodeStatusSql = """
            select node_status from batch.workflow_node_run where workflow_run_id = ?\s\
            and node_code = 'JOB_NODE_1'\
            """;
    final String selectRunStatusSql = """
            select run_status from batch.workflow_run where tenant_id = ? and id = ?
        """;
    final String selectCountSql = """
            select count(*) from batch.trigger_request where tenant_id = ? and parent_workflow_run_id = ?\s\
            and parent_node_code = 'JOB_NODE_2'\
            """;
    final String selectCountSql2 = """
            select count(*) from batch.workflow_node_run
            where workflow_run_id = ? and node_code = 'JOB_NODE_2'
              and node_status = 'READY' and job_instance_id is not null
            """;
    final String selectDayStatusSql = """
        select day_status from batch.batch_day_instance where tenant_id = ? and calendar_code = ?\s\
        and biz_date = ?\
        """;

    String suffix = Long.toUnsignedString(System.nanoTime());
    String calendarCode = "WF_SKIP_R11_" + suffix;
    String firstJobCode = seedChildJob("WG_SKIP_FIRST_R11_" + suffix);
    String secondJobCode = seedChildJob("WG_SKIP_SECOND_R11_" + suffix);
    DirectWorkflowSeed seed = seedDirectWorkflow(firstJobCode);
    jdbcTemplate.update(insertBusinessCalendarSql, testTenantId, calendarCode, calendarCode);
    jdbcTemplate.update(updateWorkflowDefinitionSql, calendarCode, seed.workflowDefinitionId());
    jdbcTemplate.update(updateWorkflowNodeSql, testTenantId, seed.workflowDefinitionId());
    jdbcTemplate.update(
        insertWorkflowNodeSql, testTenantId, seed.workflowDefinitionId(), secondJobCode);
    jdbcTemplate.update(deleteWorkflowEdgeSql, testTenantId, seed.workflowDefinitionId());
    jdbcTemplate.update(
        insertWorkflowEdgeSql,
        testTenantId,
        seed.workflowDefinitionId(),
        testTenantId,
        seed.workflowDefinitionId());
    LaunchRequest launchRequest = LaunchRequest.builder()
        .tenantId(testTenantId)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.API)
        .requestId(seed.requestId())
        .traceId("trace-manual-skip-r11-" + suffix)
        .params(Map.of())
        .build();
    workflowRunLaunchService.launch(launchRequest);

    Long workflowRunId = workflowRunId(seed.requestId());
    jdbcTemplate.update(updateWorkflowNodeRunSql, workflowRunId);
    jdbcTemplate.update(updateBatchDayInstanceSql, testTenantId, calendarCode, BIZ_DATE);

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try (Connection fence = jdbcTemplate.getDataSource().getConnection()) {
      fence.setAutoCommit(false);
      try (var statement = fence.prepareStatement(selectAcquireBatchDaySettlementFenceSql)) {
        statement.setString(1, testTenantId);
        statement.setString(2, calendarCode);
        statement.setObject(3, BIZ_DATE);
        statement.executeQuery().close();
      }
      int fencePid;
      try (var statement = fence.createStatement();
          var result = statement.executeQuery(selectPgBackendPidSql)) {
        result.next();
        fencePid = result.getInt(1);
      }

      Future<?> settlement;
      Future<?> skip;
      if ("SKIP_FIRST".equals(firstCommit)) {
        skip = executor.submit(() -> workflowRunManagementApplicationService.skipNode(
            testTenantId, workflowRunId, "JOB_NODE_1", "operator-r11", "approved manual recovery"));
        awaitBlockedBySql(fencePid, "acquire_batch_day_settlement_fence");
        settlement = executor.submit(batchDaySettleScheduler::settle);
        awaitBlockedBySql(fencePid, "acquire_batch_day_settlement_fence", 2);
      } else {
        settlement = executor.submit(batchDaySettleScheduler::settle);
        awaitBlockedBySql(fencePid, "acquire_batch_day_settlement_fence");
        skip = executor.submit(() -> workflowRunManagementApplicationService.skipNode(
            testTenantId, workflowRunId, "JOB_NODE_1", "operator-r11", "approved manual recovery"));
      }
      awaitBlockedBySql(fencePid, "acquire_batch_day_settlement_fence", 2);
      fence.commit();
      settlement.get(10, TimeUnit.SECONDS);
      skip.get(10, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }

    String selectedNodeStatus =
        jdbcTemplate.queryForObject(selectNodeStatusSql, String.class, workflowRunId);
    assertThat(selectedNodeStatus).isEqualTo("SKIPPED");
    String selectedRunStatus =
        jdbcTemplate.queryForObject(selectRunStatusSql, String.class, testTenantId, workflowRunId);
    assertThat(selectedRunStatus).isEqualTo("RUNNING");
    Long selectedTriggerRequestCount =
        jdbcTemplate.queryForObject(selectCountSql, Long.class, testTenantId, workflowRunId);
    assertThat(selectedTriggerRequestCount).isEqualTo(1L);
    Long selectedWorkflowNodeRunCount =
        jdbcTemplate.queryForObject(selectCountSql2, Long.class, workflowRunId);
    assertThat(selectedWorkflowNodeRunCount).isEqualTo(1L);
    String selectedDayStatus = jdbcTemplate.queryForObject(
        selectDayStatusSql, String.class, testTenantId, calendarCode, BIZ_DATE);
    assertThat(selectedDayStatus).isEqualTo("IN_FLIGHT");
  }

  @Test
  @DisplayName("并发人工 skip 同一个 FAILED 节点时只接纳一次并只推进一次后继")
  void concurrentManualSkip_shouldAdvanceDownstreamOnlyOnce() throws Exception {
    final String selectPgAdvisoryLockSql = """
        select pg_advisory_lock(?)
    """;
    final String selectPgAdvisoryUnlockSql = """
        select pg_advisory_unlock(?)
    """;

    final String updateWorkflowNodeSql = """
          update batch.workflow_node set node_order = 3
          where workflow_definition_id = ? and node_code = 'END'
          """;
    final String insertWorkflowNodeSql = """
          insert into batch.workflow_node (
              tenant_id, workflow_definition_id, node_code, node_name, node_type,
              related_job_code, node_order, retry_policy, retry_max_count, timeout_seconds, enabled
          ) values (?, ?, 'JOB_NODE_2', 'Next Job Node', 'JOB', ?, 2, 'NONE', 0, 0, true)
          """;
    final String updateWorkflowEdgeSql = """
          update batch.workflow_edge set to_node_code = 'JOB_NODE_2'
          where tenant_id = ? and workflow_definition_id = ?
            and from_node_code = 'JOB_NODE_1' and to_node_code = 'END'
          """;
    final String insertWorkflowEdgeSql = """
          insert into batch.workflow_edge (
              tenant_id, workflow_definition_id, from_node_code, to_node_code, edge_type, enabled
          ) values (?, ?, 'JOB_NODE_2', 'END', 'SUCCESS', true)
          """;
    final String selectIdSql = """
          select id from batch.workflow_node_run
          where workflow_run_id = ? and node_code = 'JOB_NODE_1'
          """;
    final String updateWorkflowNodeRunSql = """
          update batch.workflow_node_run
          set node_status = 'FAILED', finished_at = current_timestamp
          where id = ?
          """;
    final String selectPidSql = """
      select pid from pg_stat_activity where application_name = ?
  """;
    final String selectNodeStatusSql = """
          select node_status from batch.workflow_node_run where id = ?
          """;
    final String selectCountSql = """
          select count(*) from batch.trigger_request
          where tenant_id = ? and parent_workflow_run_id = ? and parent_node_code = 'JOB_NODE_2'
          """;
    final String selectCountSql2 = """
        select count(*) from batch.workflow_node_run
        where workflow_run_id = ? and node_code = 'JOB_NODE_2'
        """;
    final String selectCountSql3 = """
        select count(*) from batch.job_execution_log
        where tenant_id = ? and message = 'WORKFLOW_NODE_SKIP'
          and extra_json ->> 'workflowRunId' = ? and extra_json ->> 'nodeCode' = 'JOB_NODE_1'
        """;

    String suffix = Long.toUnsignedString(System.nanoTime());
    long advisoryKey = System.nanoTime();
    String applicationName = "r29-manual-skip-" + suffix;
    String functionName = "pause_r29_skip_" + suffix;
    String triggerName = "pause_r29_skip_" + suffix;
    String firstJobCode = seedChildJob("WG_R29_MANUAL_SKIP_FIRST_" + suffix);
    String nextJobCode = seedChildJob("WG_R29_MANUAL_SKIP_NEXT_" + suffix);
    DirectWorkflowSeed seed = seedDirectWorkflow(firstJobCode);
    jdbcTemplate.update(updateWorkflowNodeSql, seed.workflowDefinitionId());
    jdbcTemplate.update(
        insertWorkflowNodeSql, testTenantId, seed.workflowDefinitionId(), nextJobCode);
    jdbcTemplate.update(updateWorkflowEdgeSql, testTenantId, seed.workflowDefinitionId());
    jdbcTemplate.update(insertWorkflowEdgeSql, testTenantId, seed.workflowDefinitionId());
    LaunchRequest launchRequest = LaunchRequest.builder()
        .tenantId(testTenantId)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.API)
        .requestId(seed.requestId())
        .traceId("trace-r29-manual-skip-" + suffix)
        .params(Map.of())
        .build();
    workflowRunLaunchService.launch(launchRequest);

    Long workflowRunId = workflowRunId(seed.requestId());
    Long failedNodeRunId = jdbcTemplate.queryForObject(selectIdSql, Long.class, workflowRunId);
    jdbcTemplate.update(updateWorkflowNodeRunSql, failedNodeRunId);
    final String createFunctionSql =
        """
        create function batch.%s() returns trigger language plpgsql as $$
        begin
          if new.id = %d and new.node_status = 'SKIPPED' then
            perform set_config('application_name', '%s', true);
            perform pg_advisory_xact_lock(%d);
          end if;
          return new;
        end;
        $$
        """.formatted(functionName, failedNodeRunId, applicationName, advisoryKey);
    jdbcTemplate.execute(createFunctionSql);
    final String createTriggerSql = """
        create trigger %s before update of node_status on batch.workflow_node_run
        for each row execute function batch.%s()
        """.formatted(triggerName, functionName);
    jdbcTemplate.execute(createTriggerSql);

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try (Connection blocker = jdbcTemplate.getDataSource().getConnection()) {
      blocker.setAutoCommit(true);
      try (var lock = blocker.prepareStatement(selectPgAdvisoryLockSql)) {
        lock.setLong(1, advisoryKey);
        lock.execute();
      }
      int blockerPid = backendPid(blocker);
      Future<?> firstSkip = executor.submit(() -> workflowRunManagementApplicationService.skipNode(
          testTenantId, workflowRunId, "JOB_NODE_1", "operator-r29-first", "first approval"));
      try {
        awaitBlockedBySql(blockerPid, "update batch.workflow_node_run");
        Integer firstSkipPid =
            jdbcTemplate.queryForObject(selectPidSql, Integer.class, applicationName);
        assertThat(firstSkipPid).isNotNull();

        Future<?> competingSkip =
            executor.submit(() -> workflowRunManagementApplicationService.skipNode(
                testTenantId,
                workflowRunId,
                "JOB_NODE_1",
                "operator-r29-second",
                "second approval"));
        awaitBlockedBySql(firstSkipPid, "update batch.workflow_node_run");

        try (var unlock = blocker.prepareStatement(selectPgAdvisoryUnlockSql)) {
          unlock.setLong(1, advisoryKey);
          unlock.execute();
        }
        firstSkip.get(10, TimeUnit.SECONDS);
        assertThatThrownBy(() -> competingSkip.get(10, TimeUnit.SECONDS))
            .isInstanceOf(ExecutionException.class)
            .hasCauseInstanceOf(BizException.class)
            .satisfies(error -> assertThat(((BizException) error.getCause()).getCode())
                .isEqualTo(ResultCode.STATE_CONFLICT));
      } finally {
        try (var unlock = blocker.prepareStatement(selectPgAdvisoryUnlockSql)) {
          unlock.setLong(1, advisoryKey);
          unlock.execute();
        }
      }
    } finally {
      executor.shutdownNow();
      final String dropTriggerSql = """
          drop trigger if exists %s on batch.workflow_node_run\
          """.formatted(triggerName);
      jdbcTemplate.execute(dropTriggerSql);
      final String dropFunctionSql = """
          drop function if exists batch.%s()\
          """.formatted(functionName);
      jdbcTemplate.execute(dropFunctionSql);
    }

    String selectedNodeStatus =
        jdbcTemplate.queryForObject(selectNodeStatusSql, String.class, failedNodeRunId);
    assertThat(selectedNodeStatus).isEqualTo("SKIPPED");
    Long selectedTriggerRequestCount =
        jdbcTemplate.queryForObject(selectCountSql, Long.class, testTenantId, workflowRunId);
    assertThat(selectedTriggerRequestCount).isEqualTo(1L);
    Long selectedWorkflowNodeRunCount =
        jdbcTemplate.queryForObject(selectCountSql2, Long.class, workflowRunId);
    assertThat(selectedWorkflowNodeRunCount).isEqualTo(1L);
    Long selectedJobExecutionLogCount = jdbcTemplate.queryForObject(
        selectCountSql3, Long.class, testTenantId, String.valueOf(workflowRunId));
    assertThat(selectedJobExecutionLogCount).isEqualTo(1L);
  }

  @ParameterizedTest(name = "{0} 时父 Workflow 终止与活跃 JOB 子实例结算保持未结算")
  @ValueSource(strings = {"TERMINATE_FIRST", "SETTLEMENT_FIRST"})
  @DisplayName("父 Workflow 终止与活跃 JOB 子实例结算按两种提交顺序竞争")
  void terminatedWorkflowWithActiveChildJob_shouldKeepBatchDayInFlight(String firstCommit)
      throws Exception {
    final String selectIdSql = """
            select id from batch.batch_day_instance where tenant_id = ? and calendar_code = ?\s\
            and biz_date = ? for update\
            """;
    final String selectIdSql2 = """
            select id from batch.workflow_run where tenant_id = ? and id = ? for update
        """;

    final String insertBusinessCalendarSql = """
            insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone, enabled)\s\
            values (?, ?, ?, 'UTC', true)\
            """;
    final String updateWorkflowDefinitionSql = """
            update batch.workflow_definition set calendar_code = ? where id = ?
        """;
    final String selectInstanceStatusSql = """
            select instance_status from batch.job_instance where tenant_id = ? and id = ?
        """;
    final String updateBatchDayInstanceSql = """
        update batch.batch_day_instance\s\
        set day_status = 'CUTOFF', cutoff_at = current_timestamp - interval '1 second', frozen = false, version\s\
        = version + 1, updated_at = current_timestamp where tenant_id = ? and calendar_code = ? and biz_date = ?\
        """;
    final String selectRunStatusSql = """
        select run_status from batch.workflow_run where tenant_id = ? and id = ?
    """;
    final String selectDayStatusSql = """
        select day_status from batch.batch_day_instance where tenant_id = ? and calendar_code = ?\s\
        and biz_date = ?\
        """;

    String suffix = Long.toUnsignedString(System.nanoTime());
    String calendarCode = "WF_TERMINATE_CHILD_R11_" + suffix;
    String childJobCode = seedChildJob("WG_TERMINATE_CHILD_R11_" + suffix);
    DirectWorkflowSeed seed = seedDirectWorkflow(childJobCode);
    jdbcTemplate.update(insertBusinessCalendarSql, testTenantId, calendarCode, calendarCode);
    jdbcTemplate.update(updateWorkflowDefinitionSql, calendarCode, seed.workflowDefinitionId());
    LaunchRequest launchRequest = LaunchRequest.builder()
        .tenantId(testTenantId)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.API)
        .requestId(seed.requestId())
        .traceId("trace-terminate-child-r11-" + suffix)
        .params(Map.of())
        .build();
    workflowRunLaunchService.launch(launchRequest);

    Long workflowRunId = workflowRunId(seed.requestId());
    Long jobInstanceId = linkedNodeJobInstance(workflowRunId, "JOB_NODE_1");
    String childStatus = jdbcTemplate.queryForObject(
        selectInstanceStatusSql, String.class, testTenantId, jobInstanceId);
    assertThat(childStatus).isIn("CREATED", "WAITING", "READY", "RUNNING", "PAUSED");
    jdbcTemplate.update(updateBatchDayInstanceSql, testTenantId, calendarCode, BIZ_DATE);

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try (Connection blocker = jdbcTemplate.getDataSource().getConnection()) {
      blocker.setAutoCommit(false);
      if ("TERMINATE_FIRST".equals(firstCommit)) {
        try (var statement = blocker.prepareStatement(selectIdSql)) {
          statement.setString(1, testTenantId);
          statement.setString(2, calendarCode);
          statement.setObject(3, BIZ_DATE);
          statement.executeQuery().close();
        }
        int blockerPid = backendPid(blocker);
        Future<?> settlement = executor.submit(batchDaySettleScheduler::settle);
        awaitBlockedBySql(blockerPid, "update batch.batch_day_instance");
        workflowRunManagementApplicationService.terminate(testTenantId, workflowRunId);
        blocker.commit();
        settlement.get(10, TimeUnit.SECONDS);
      } else {
        try (var statement = blocker.prepareStatement(selectIdSql2)) {
          statement.setString(1, testTenantId);
          statement.setLong(2, workflowRunId);
          statement.executeQuery().close();
        }
        int blockerPid = backendPid(blocker);
        Future<?> termination = executor.submit(
            () -> workflowRunManagementApplicationService.terminate(testTenantId, workflowRunId));
        awaitBlockedBy(blockerPid);
        batchDaySettleScheduler.settle();
        blocker.commit();
        termination.get(10, TimeUnit.SECONDS);
        batchDaySettleScheduler.settle();
      }
    } finally {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    String selectedRunStatus =
        jdbcTemplate.queryForObject(selectRunStatusSql, String.class, testTenantId, workflowRunId);
    assertThat(selectedRunStatus).isEqualTo("TERMINATED");
    String selectedInstanceStatus = jdbcTemplate.queryForObject(
        selectInstanceStatusSql, String.class, testTenantId, jobInstanceId);
    assertThat(selectedInstanceStatus).isIn("CREATED", "WAITING", "READY", "RUNNING", "PAUSED");
    String selectedDayStatus = jdbcTemplate.queryForObject(
        selectDayStatusSql, String.class, testTenantId, calendarCode, BIZ_DATE);
    assertThat(selectedDayStatus).isEqualTo("IN_FLIGHT");

    reportJobSuccess(jobInstanceId);
    batchDaySettleScheduler.settle();
    String selectedInstanceStatusRead2 = jdbcTemplate.queryForObject(
        selectInstanceStatusSql, String.class, testTenantId, jobInstanceId);
    assertThat(selectedInstanceStatusRead2).isEqualTo("SUCCESS");
    String selectedRunStatusRead2 =
        jdbcTemplate.queryForObject(selectRunStatusSql, String.class, testTenantId, workflowRunId);
    assertThat(selectedRunStatusRead2).isEqualTo("TERMINATED");
    String selectedDayStatusRead2 = jdbcTemplate.queryForObject(
        selectDayStatusSql, String.class, testTenantId, calendarCode, BIZ_DATE);
    assertThat(selectedDayStatusRead2).isEqualTo("FAILED");
  }

  @ParameterizedTest(name = "Worker success={0} 时已终止 Workflow 保留终态")
  @ValueSource(booleans = {true, false})
  @DisplayName("终止后的活跃子 Job 成功或失败回报都不派发 DAG 后继 Job")
  void terminatedWorkflowActiveChildReport_shouldNotDispatchNextJob(boolean success) {
    final String updateWorkflowNodeSql = """
          update batch.workflow_node set node_order = 3
          where workflow_definition_id = ? and node_code = 'END'
          """;
    final String insertWorkflowNodeSql = """
          insert into batch.workflow_node (
              tenant_id, workflow_definition_id, node_code, node_name, node_type,
              related_job_code, node_order, retry_policy, retry_max_count, timeout_seconds, enabled
          ) values (?, ?, 'JOB_NODE_2', 'Next Job Node', 'JOB', ?, 2, 'NONE', 0, 0, true)
          """;
    final String updateWorkflowEdgeSql = """
          update batch.workflow_edge set to_node_code = 'JOB_NODE_2'
          where tenant_id = ? and workflow_definition_id = ?
            and from_node_code = 'JOB_NODE_1' and to_node_code = 'END'
          """;
    final String insertWorkflowEdgeSql = """
          insert into batch.workflow_edge (
              tenant_id, workflow_definition_id, from_node_code, to_node_code, edge_type, enabled
          ) values (?, ?, 'JOB_NODE_2', 'END', 'SUCCESS', true)
          """;
    final String updateWorkflowEdgeSql2 = """
        update batch.workflow_edge set edge_type = ?
        where tenant_id = ? and workflow_definition_id = ?
          and from_node_code = 'JOB_NODE_1' and to_node_code = 'JOB_NODE_2'
        """;
    final String selectInstanceStatusSql = """
        select instance_status from batch.job_instance where tenant_id = ? and id = ?
    """;
    final String selectCountSql = """
        select count(*) from batch.trigger_request where tenant_id = ? and parent_workflow_run_id = ?\s\
        and parent_node_code = 'JOB_NODE_2'\
        """;
    final String selectRunStatusSql = """
        select run_status from batch.workflow_run where tenant_id = ? and id = ?
    """;

    String suffix = Long.toUnsignedString(System.nanoTime());
    String firstJobCode = seedChildJob("WG_TERMINATED_REPORT_FIRST_" + suffix);
    String nextJobCode = seedChildJob("WG_TERMINATED_REPORT_NEXT_" + suffix);
    DirectWorkflowSeed seed = seedDirectWorkflow(firstJobCode);
    jdbcTemplate.update(updateWorkflowNodeSql, seed.workflowDefinitionId());
    jdbcTemplate.update(
        insertWorkflowNodeSql, testTenantId, seed.workflowDefinitionId(), nextJobCode);
    jdbcTemplate.update(updateWorkflowEdgeSql, testTenantId, seed.workflowDefinitionId());
    jdbcTemplate.update(insertWorkflowEdgeSql, testTenantId, seed.workflowDefinitionId());
    jdbcTemplate.update(
        updateWorkflowEdgeSql2,
        success ? "SUCCESS" : "FAILURE",
        testTenantId,
        seed.workflowDefinitionId());

    LaunchRequest launchRequest = LaunchRequest.builder()
        .tenantId(testTenantId)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.API)
        .requestId(seed.requestId())
        .traceId("trace-terminated-child-report-" + suffix)
        .params(Map.of())
        .build();
    workflowRunLaunchService.launch(launchRequest);
    Long workflowRunId = workflowRunId(seed.requestId());
    Long activeJobInstanceId = linkedNodeJobInstance(workflowRunId, "JOB_NODE_1");
    workflowRunManagementApplicationService.terminate(testTenantId, workflowRunId);

    if (success) {
      reportJobSuccess(activeJobInstanceId);
    } else {
      reportJobFailure(activeJobInstanceId);
    }

    String selectedInstanceStatus = jdbcTemplate.queryForObject(
        selectInstanceStatusSql, String.class, testTenantId, activeJobInstanceId);
    assertThat(selectedInstanceStatus).isEqualTo(success ? "SUCCESS" : "FAILED");
    Long selectedTriggerRequestCount =
        jdbcTemplate.queryForObject(selectCountSql, Long.class, testTenantId, workflowRunId);
    assertThat(selectedTriggerRequestCount).isZero();
    String selectedRunStatus =
        jdbcTemplate.queryForObject(selectRunStatusSql, String.class, testTenantId, workflowRunId);
    assertThat(selectedRunStatus).isEqualTo("TERMINATED");
  }

  @ParameterizedTest(name = "Worker success={0} 时终止先提交仍保留回报且不派发后继")
  @ValueSource(booleans = {true, false})
  @DisplayName("REPORT 读取活动父运行后终止先提交，成功或失败都不派发后继")
  void childReportRacingWorkflowTermination_shouldObserveCommittedTerminalParent(boolean success)
      throws Exception {
    final String acquireSettlementFenceSql = """
        select batch.acquire_batch_day_settlement_fence(?, ?, ?)
        """;
    final String selectCalendarCodeSql = """
        select calendar_code from batch.workflow_definition where tenant_id = ? and id = ?
        """;
    final String updateWorkflowNodeSql = """
        update batch.workflow_node set node_order = 3
        where workflow_definition_id = ? and node_code = 'END'
        """;
    final String insertWorkflowNodeSql = """
        insert into batch.workflow_node (
            tenant_id, workflow_definition_id, node_code, node_name, node_type,
            related_job_code, node_order, retry_policy, retry_max_count, timeout_seconds, enabled
        ) values (?, ?, 'JOB_NODE_2', 'Next Job Node', 'JOB', ?, 2, 'NONE', 0, 0, true)
        """;
    final String updateWorkflowEdgeSql = """
        update batch.workflow_edge set to_node_code = 'JOB_NODE_2'
        where tenant_id = ? and workflow_definition_id = ?
          and from_node_code = 'JOB_NODE_1' and to_node_code = 'END'
        """;
    final String insertWorkflowEdgeSql = """
        insert into batch.workflow_edge (
            tenant_id, workflow_definition_id, from_node_code, to_node_code, edge_type, enabled
        ) values (?, ?, 'JOB_NODE_2', 'END', 'SUCCESS', true)
        """;
    final String updateResultEdgeSql = """
        update batch.workflow_edge set edge_type = ?
        where tenant_id = ? and workflow_definition_id = ?
          and from_node_code = 'JOB_NODE_1' and to_node_code = 'JOB_NODE_2'
        """;
    final String selectInstanceStatusSql = """
        select instance_status from batch.job_instance where tenant_id = ? and id = ?
        """;
    final String selectSuccessorRequestCountSql = """
        select count(*) from batch.trigger_request
        where tenant_id = ? and parent_workflow_run_id = ? and parent_node_code = 'JOB_NODE_2'
        """;
    final String selectRunStatusSql = """
        select run_status from batch.workflow_run where tenant_id = ? and id = ?
        """;

    String suffix = Long.toUnsignedString(System.nanoTime());
    String firstJobCode = seedChildJob("WG_TERMINATED_RACE_FIRST_" + suffix);
    String nextJobCode = seedChildJob("WG_TERMINATED_RACE_NEXT_" + suffix);
    DirectWorkflowSeed seed = seedDirectWorkflow(firstJobCode);
    jdbcTemplate.update(updateWorkflowNodeSql, seed.workflowDefinitionId());
    jdbcTemplate.update(
        insertWorkflowNodeSql, testTenantId, seed.workflowDefinitionId(), nextJobCode);
    jdbcTemplate.update(updateWorkflowEdgeSql, testTenantId, seed.workflowDefinitionId());
    jdbcTemplate.update(insertWorkflowEdgeSql, testTenantId, seed.workflowDefinitionId());
    jdbcTemplate.update(
        updateResultEdgeSql,
        success ? "SUCCESS" : "FAILURE",
        testTenantId,
        seed.workflowDefinitionId());
    LaunchRequest launchRequest = LaunchRequest.builder()
        .tenantId(testTenantId)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.API)
        .requestId(seed.requestId())
        .traceId("trace-terminal-report-race-" + suffix)
        .params(Map.of())
        .build();
    workflowRunLaunchService.launch(launchRequest);
    Long workflowRunId = workflowRunId(seed.requestId());
    Long activeJobInstanceId = linkedNodeJobInstance(workflowRunId, "JOB_NODE_1");
    String calendarCode = jdbcTemplate.queryForObject(
        selectCalendarCodeSql, String.class, testTenantId, seed.workflowDefinitionId());
    assertThat(calendarCode).isNotBlank();

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try (Connection fence = jdbcTemplate.getDataSource().getConnection()) {
      fence.setAutoCommit(false);
      Future<?> report;
      try {
        try (PreparedStatement statement = fence.prepareStatement(acquireSettlementFenceSql)) {
          statement.setString(1, testTenantId);
          statement.setString(2, calendarCode);
          statement.setObject(3, BIZ_DATE);
          statement.executeQuery().close();
        }
        int fencePid = backendPid(fence);
        report = executor.submit(() -> {
          if (success) {
            reportJobSuccess(activeJobInstanceId);
          } else {
            reportJobFailure(activeJobInstanceId);
          }
        });
        // REPORT 此时已读过父运行，但尚未取得父行锁；不能在节点写入处暂停后再等待终止。
        awaitBlockedBySql(fencePid, "acquire_batch_day_settlement_fence");
        String statusBeforeTermination = jdbcTemplate.queryForObject(
            selectRunStatusSql, String.class, testTenantId, workflowRunId);
        assertThat(statusBeforeTermination).isEqualTo("RUNNING");

        Future<?> termination = executor.submit(
            () -> workflowRunManagementApplicationService.terminate(testTenantId, workflowRunId));
        termination.get(10, TimeUnit.SECONDS);
        String statusWhileReportIsBlocked = jdbcTemplate.queryForObject(
            selectRunStatusSql, String.class, testTenantId, workflowRunId);
        assertThat(statusWhileReportIsBlocked).isEqualTo("TERMINATED");
      } finally {
        // 事务级屏障必须先释放，随后才能等待工作线程；断言或终止超时也不能把 REPORT 留在数据库锁上。
        fence.rollback();
      }
      report.get(10, TimeUnit.SECONDS);

      String instanceStatus = jdbcTemplate.queryForObject(
          selectInstanceStatusSql, String.class, testTenantId, activeJobInstanceId);
      Long successorRequestCount = jdbcTemplate.queryForObject(
          selectSuccessorRequestCountSql, Long.class, testTenantId, workflowRunId);
      String workflowStatus = jdbcTemplate.queryForObject(
          selectRunStatusSql, String.class, testTenantId, workflowRunId);
      assertThat(instanceStatus).isEqualTo(success ? "SUCCESS" : "FAILED");
      assertThat(successorRequestCount).isZero();
      assertThat(workflowStatus).isEqualTo("TERMINATED");
    } finally {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  @ParameterizedTest(name = "Worker success={0} 时 REPORT 先取得父运行围栏")
  @ValueSource(booleans = {true, false})
  @DisplayName("REPORT 先取得父运行围栏时按结果边接纳后继，终止随后提交")
  void childReportWinningWorkflowTerminationRace_shouldCommitAdmissionBeforeTermination(
      boolean success) throws Exception {
    final String selectPgAdvisoryLockSql = """
            select pg_advisory_lock(?)
        """;
    final String selectPgAdvisoryUnlockSql = """
            select pg_advisory_unlock(?)
        """;

    final String updateWorkflowNodeSql = """
    update batch.workflow_node set node_order = 3
    where workflow_definition_id = ? and node_code = 'END'
    """;
    final String insertWorkflowNodeSql = """
    insert into batch.workflow_node (
    tenant_id, workflow_definition_id, node_code, node_name, node_type,
    related_job_code, node_order, retry_policy, retry_max_count, timeout_seconds, enabled
    ) values (?, ?, 'JOB_NODE_2', 'Next Job Node', 'JOB', ?, 2, 'NONE', 0, 0, true)
    """;
    final String updateWorkflowEdgeSql = """
    update batch.workflow_edge set to_node_code = 'JOB_NODE_2'
    where tenant_id = ? and workflow_definition_id = ?
    and from_node_code = 'JOB_NODE_1' and to_node_code = 'END'
    """;
    final String insertWorkflowEdgeSql = """
    insert into batch.workflow_edge (
    tenant_id, workflow_definition_id, from_node_code, to_node_code, edge_type, enabled
    ) values (?, ?, 'JOB_NODE_2', 'END', 'SUCCESS', true)
    """;
    final String updateWorkflowEdgeSql2 = """
        update batch.workflow_edge set edge_type = ?
        where tenant_id = ? and workflow_definition_id = ?
          and from_node_code = 'JOB_NODE_1' and to_node_code = 'JOB_NODE_2'
        """;
    final String selectPidSql = """
            select pid from pg_stat_activity where application_name = ?
        """;
    final String selectInstanceStatusSql = """
        select instance_status from batch.job_instance where tenant_id = ? and id = ?
    """;
    final String selectCountSql = """
        select count(*) from batch.trigger_request where tenant_id = ? and parent_workflow_run_id = ?\s\
        and parent_node_code = 'JOB_NODE_2'\
        """;
    final String selectRunStatusSql = """
        select run_status from batch.workflow_run where tenant_id = ? and id = ?
    """;

    String suffix = Long.toUnsignedString(System.nanoTime());
    long advisoryKey = System.nanoTime();
    String applicationName = "r11-report-first-" + suffix;
    String functionName = "pause_r11_report_first_" + suffix;
    String triggerName = "pause_r11_report_first_" + suffix;
    String firstJobCode = seedChildJob("WG_TERMINATED_REPORT_FIRST_WINNER_" + suffix);
    String nextJobCode = seedChildJob("WG_TERMINATED_REPORT_NEXT_WINNER_" + suffix);
    DirectWorkflowSeed seed = seedDirectWorkflow(firstJobCode);
    jdbcTemplate.update(updateWorkflowNodeSql, seed.workflowDefinitionId());
    jdbcTemplate.update(
        insertWorkflowNodeSql, testTenantId, seed.workflowDefinitionId(), nextJobCode);
    jdbcTemplate.update(updateWorkflowEdgeSql, testTenantId, seed.workflowDefinitionId());
    jdbcTemplate.update(insertWorkflowEdgeSql, testTenantId, seed.workflowDefinitionId());
    jdbcTemplate.update(
        updateWorkflowEdgeSql2,
        success ? "SUCCESS" : "FAILURE",
        testTenantId,
        seed.workflowDefinitionId());
    final String createFunctionSql = """
    create function batch.%s() returns trigger language plpgsql as $$
    begin
    if new.parent_node_code = 'JOB_NODE_2' then
    perform set_config('application_name', '%s', true);
    perform pg_advisory_xact_lock(%d);
    end if;
    return new;
    end;
    $$
    """.formatted(functionName, applicationName, advisoryKey);
    jdbcTemplate.execute(createFunctionSql);
    final String createTriggerSql = """
    create trigger %s before insert on batch.trigger_request
    for each row execute function batch.%s()
    """.formatted(triggerName, functionName);
    jdbcTemplate.execute(createTriggerSql);

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try (Connection blocker = jdbcTemplate.getDataSource().getConnection()) {
      blocker.setAutoCommit(true);
      try (var lock = blocker.prepareStatement(selectPgAdvisoryLockSql)) {
        lock.setLong(1, advisoryKey);
        lock.execute();
      }
      LaunchRequest launchRequest = LaunchRequest.builder()
          .tenantId(testTenantId)
          .workflowDefinitionId(seed.workflowDefinitionId())
          .bizDate(BIZ_DATE)
          .triggerType(TriggerType.API)
          .requestId(seed.requestId())
          .traceId("trace-terminal-report-first-" + suffix)
          .params(Map.of())
          .build();
      workflowRunLaunchService.launch(launchRequest);
      Long workflowRunId = workflowRunId(seed.requestId());
      Long activeJobInstanceId = linkedNodeJobInstance(workflowRunId, "JOB_NODE_1");
      int blockerPid = backendPid(blocker);
      Future<?> report = executor.submit(() -> {
        if (success) {
          reportJobSuccess(activeJobInstanceId);
        } else {
          reportJobFailure(activeJobInstanceId);
        }
      });
      awaitBlockedBySql(blockerPid, "insert into batch.trigger_request");
      Integer reportPid = jdbcTemplate.queryForObject(selectPidSql, Integer.class, applicationName);
      assertThat(reportPid).isNotNull();

      Future<?> termination = executor.submit(
          () -> workflowRunManagementApplicationService.terminate(testTenantId, workflowRunId));
      awaitBlockedBySql(reportPid, "update batch.workflow_run");
      try (var unlock = blocker.prepareStatement(selectPgAdvisoryUnlockSql)) {
        unlock.setLong(1, advisoryKey);
        unlock.execute();
      }
      report.get(10, TimeUnit.SECONDS);
      termination.get(10, TimeUnit.SECONDS);
      Long admittedChildJobInstanceId = linkedNodeJobInstance(workflowRunId, "JOB_NODE_2");

      String selectedInstanceStatus = jdbcTemplate.queryForObject(
          selectInstanceStatusSql, String.class, testTenantId, activeJobInstanceId);
      assertThat(selectedInstanceStatus).isEqualTo(success ? "SUCCESS" : "FAILED");
      Long selectedTriggerRequestCount =
          jdbcTemplate.queryForObject(selectCountSql, Long.class, testTenantId, workflowRunId);
      assertThat(selectedTriggerRequestCount).isEqualTo(1L);
      assertThat(admittedChildJobInstanceId).isNotNull();
      String selectedInstanceStatusRead2 = jdbcTemplate.queryForObject(
          selectInstanceStatusSql, String.class, testTenantId, admittedChildJobInstanceId);
      assertThat(selectedInstanceStatusRead2)
          .isNotIn("SUCCESS", "FAILED", "CANCELLED", "TERMINATED");
      String selectedRunStatus = jdbcTemplate.queryForObject(
          selectRunStatusSql, String.class, testTenantId, workflowRunId);
      assertThat(selectedRunStatus).isEqualTo("TERMINATED");
    } finally {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
      final String dropTriggerSql = """
          drop trigger if exists %s on batch.trigger_request\
          """.formatted(triggerName);
      jdbcTemplate.execute(dropTriggerSql);
      final String dropFunctionSql = """
          drop function if exists batch.%s()\
          """.formatted(functionName);
      jdbcTemplate.execute(dropFunctionSql);
    }
  }

  @ParameterizedTest(name = "关联 Job 状态 {0} 时仍阻止空日结算")
  @MethodSource("activeJobInstanceStatuses")
  @DisplayName("终态 Workflow 下每种活跃 Job 状态都阻止业务日结算")
  void terminatedWorkflowWithEachActiveChildJobStatus_shouldKeepBatchDayInFlight(
      String childStatus) {
    final String insertBusinessCalendarSql = """
            insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone, enabled)\s\
            values (?, ?, ?, 'UTC', true)\
            """;
    final String updateWorkflowDefinitionSql = """
            update batch.workflow_definition set calendar_code = ? where id = ?
        """;
    final String updateBatchDayInstanceSql = """
            update batch.batch_day_instance\s\
            set day_status = 'CUTOFF', cutoff_at = current_timestamp - interval '1 second', frozen = false,\s\
            version = version + 1, updated_at = current_timestamp where tenant_id = ? and calendar_code = ?\s\
            and biz_date = ?\
            """;
    final String updateJobInstanceSql = """
            update batch.job_instance set instance_status = ? where tenant_id = ? and id = ?
        """;
    final String selectRunStatusSql = """
        select run_status from batch.workflow_run where tenant_id = ? and id = ?
    """;
    final String selectInstanceStatusSql = """
        select instance_status from batch.job_instance where tenant_id = ? and id = ?
    """;
    final String selectDayStatusSql = """
        select day_status from batch.batch_day_instance where tenant_id = ? and calendar_code = ?\s\
        and biz_date = ?\
        """;

    String suffix = Long.toUnsignedString(System.nanoTime());
    String calendarCode = "WF_ACTIVE_CHILD_STATUS_R11_" + suffix;
    String childJobCode = seedChildJob("WG_ACTIVE_CHILD_STATUS_R11_" + suffix);
    DirectWorkflowSeed seed = seedDirectWorkflow(childJobCode);
    jdbcTemplate.update(insertBusinessCalendarSql, testTenantId, calendarCode, calendarCode);
    jdbcTemplate.update(updateWorkflowDefinitionSql, calendarCode, seed.workflowDefinitionId());
    LaunchRequest launchRequest = LaunchRequest.builder()
        .tenantId(testTenantId)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.API)
        .requestId(seed.requestId())
        .traceId("trace-active-child-status-r11-" + suffix)
        .params(Map.of())
        .build();
    workflowRunLaunchService.launch(launchRequest);

    Long workflowRunId = workflowRunId(seed.requestId());
    Long jobInstanceId = linkedNodeJobInstance(workflowRunId, "JOB_NODE_1");
    jdbcTemplate.update(updateBatchDayInstanceSql, testTenantId, calendarCode, BIZ_DATE);
    workflowRunManagementApplicationService.terminate(testTenantId, workflowRunId);
    jdbcTemplate.update(updateJobInstanceSql, childStatus, testTenantId, jobInstanceId);

    batchDaySettleScheduler.settle();

    String selectedRunStatus =
        jdbcTemplate.queryForObject(selectRunStatusSql, String.class, testTenantId, workflowRunId);
    assertThat(selectedRunStatus).isEqualTo("TERMINATED");
    String selectedInstanceStatus = jdbcTemplate.queryForObject(
        selectInstanceStatusSql, String.class, testTenantId, jobInstanceId);
    assertThat(selectedInstanceStatus).isEqualTo(childStatus);
    String selectedDayStatus = jdbcTemplate.queryForObject(
        selectDayStatusSql, String.class, testTenantId, calendarCode, BIZ_DATE);
    assertThat(selectedDayStatus).isEqualTo("IN_FLIGHT");
  }

  static Stream<String> activeJobInstanceStatuses() {
    return Arrays.stream(JobInstanceStatus.values())
        .map(JobInstanceStatus::code)
        .filter(JobInstanceStatus.activeCodes()::contains);
  }

  static Stream<Arguments> workerReportSettlementScenarios() {
    return Stream.of(
        Arguments.of("SETTLEMENT_FIRST", true, "SUCCESS", "SUCCESS", "SETTLED"),
        Arguments.of("REPORT_FIRST", true, "SUCCESS", "SUCCESS", "SETTLED"),
        Arguments.of("SETTLEMENT_FIRST", false, "FAILED", "FAILED", "FAILED"),
        Arguments.of("REPORT_FIRST", false, "FAILED", "FAILED", "FAILED"));
  }

  @ParameterizedTest(name = "{0} 时 Worker success={1} 与结算最终收敛")
  @MethodSource("workerReportSettlementScenarios")
  @DisplayName("最后一个 JOB 的 Worker 成功或失败 REPORT 与结算按两种顺序竞争")
  void directWorkflow_workerReportRacingSettlementShouldConverge(
      String firstCommit,
      boolean success,
      String expectedRunStatus,
      String expectedNodeStatus,
      String expectedBatchDayStatus)
      throws Exception {
    final String selectIdSql2 = """
            select id from batch.workflow_run where tenant_id = ? and id = ? for update
        """;
    final String selectPgBackendPidSql = """
            select pg_backend_pid()
        """;

    final String insertBusinessCalendarSql = """
            insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone, enabled)\s\
            values (?, ?, ?, 'UTC', true)\
            """;
    final String updateWorkflowDefinitionSql = """
            update batch.workflow_definition set calendar_code = ? where id = ?
        """;
    final String selectIdSql = """
            select id from batch.job_task where tenant_id = ? and job_instance_id = ?
        """;
    final String updateJobTaskSql = """
            update batch.job_task set task_status = 'RUNNING', started_at = ? where tenant_id = ? and id = ?
        """;
    final String updateBatchDayInstanceSql = """
    update batch.batch_day_instance
    set day_status = 'CUTOFF', open_at = current_timestamp - interval '1 day',
    cutoff_at = current_timestamp - interval '1 second', frozen = false,
    version = version + 1, updated_at = current_timestamp
    where tenant_id = ? and calendar_code = ? and biz_date = ?
    """;
    final String selectDayStatusSql = """
            select day_status from batch.batch_day_instance where tenant_id = ? and calendar_code = ?\s\
            and biz_date = ?\
            """;
    final String selectRunStatusSql = """
            select run_status from batch.workflow_run where tenant_id = ? and id = ?
        """;
    final String selectNodeStatusSql = """
            select node_status from batch.workflow_node_run where workflow_run_id = ?\s\
            and node_code = 'JOB_NODE_1'\
            """;
    final String selectDayStatusSql2 = """
      select day_status from batch.batch_day_instance where tenant_id = ? and calendar_code = ? and biz_date = ?\
      """;

    String suffix = Long.toUnsignedString(System.nanoTime());
    String calendarCode = "WF_REPORT_R11_" + suffix;
    String childCode = seedChildJob("WG_REPORT_R11_" + suffix);
    DirectWorkflowSeed seed = seedDirectWorkflow(childCode);
    jdbcTemplate.update(insertBusinessCalendarSql, testTenantId, calendarCode, calendarCode);
    jdbcTemplate.update(updateWorkflowDefinitionSql, calendarCode, seed.workflowDefinitionId());
    LaunchRequest launchRequest = LaunchRequest.builder()
        .tenantId(testTenantId)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.API)
        .requestId(seed.requestId())
        .traceId("trace-worker-report-r11-" + suffix)
        .params(Map.of())
        .build();
    workflowRunLaunchService.launch(launchRequest);

    Long workflowRunId = workflowRunId(seed.requestId());
    Long jobInstanceId = linkedNodeJobInstance(workflowRunId, "JOB_NODE_1");
    Long taskId = jdbcTemplate.queryForObject(selectIdSql, Long.class, testTenantId, jobInstanceId);
    jdbcTemplate.update(
        updateJobTaskSql, Timestamp.from(BatchDateTimeSupport.utcNow()), testTenantId, taskId);
    jdbcTemplate.update(updateBatchDayInstanceSql, testTenantId, calendarCode, BIZ_DATE);

    ExecutorService executor = Executors.newFixedThreadPool(2);
    TaskOutcomeCommand reportCommand = TaskOutcomeCommand.builder()
        .tenantId(testTenantId)
        .taskId(taskId)
        .success(success)
        .errorCode(success ? null : "WORKER_FAILED")
        .errorMessage(success ? null : "controlled R11 failure")
        .resultSummary("{}")
        .build();
    try {
      if ("SETTLEMENT_FIRST".equals(firstCommit)) {
        // 在 REPORT 开始前提交结算，覆盖第一种提交顺序，避免
        // 持有 WorkflowRun 行锁时，阻塞按正确顺序先取得 BatchDay 围栏的 REPORT。
        batchDaySettleScheduler.settle();
        String selectedDayStatus = jdbcTemplate.queryForObject(
            selectDayStatusSql, String.class, testTenantId, calendarCode, BIZ_DATE);
        assertThat(selectedDayStatus).isEqualTo("IN_FLIGHT");
        taskOutcomeService.applyTaskOutcome(reportCommand);
      } else {
        // 持有 WorkflowRun 行锁，使 REPORT 在已取得 BatchDay 围栏后暂停。
        // 此时结算必须排在 REPORT 之后等待；颠倒锁序会死锁。
        try (Connection lock = jdbcTemplate.getDataSource().getConnection()) {
          lock.setAutoCommit(false);
          try (var statement = lock.prepareStatement(selectIdSql2)) {
            statement.setString(1, testTenantId);
            statement.setLong(2, workflowRunId);
            statement.executeQuery().close();
          }
          int blockerPid;
          try (var statement = lock.createStatement();
              var result = statement.executeQuery(selectPgBackendPidSql)) {
            result.next();
            blockerPid = result.getInt(1);
          }
          Future<?> report =
              executor.submit(() -> taskOutcomeService.applyTaskOutcome(reportCommand));
          awaitBlockedBy(blockerPid);
          Future<?> settlement = executor.submit(batchDaySettleScheduler::settle);
          awaitSqlWaiting("acquire_batch_day_settlement_fence");
          lock.commit();
          report.get(10, TimeUnit.SECONDS);
          settlement.get(10, TimeUnit.SECONDS);
        }
      }
    } finally {
      executor.shutdownNow();
    }

    String selectedRunStatus =
        jdbcTemplate.queryForObject(selectRunStatusSql, String.class, testTenantId, workflowRunId);
    assertThat(selectedRunStatus).isEqualTo(expectedRunStatus);
    String selectedNodeStatus =
        jdbcTemplate.queryForObject(selectNodeStatusSql, String.class, workflowRunId);
    assertThat(selectedNodeStatus).isEqualTo(expectedNodeStatus);
    String selectedDayStatusRead2 = jdbcTemplate.queryForObject(
        selectDayStatusSql2, String.class, testTenantId, calendarCode, BIZ_DATE);
    assertThat(selectedDayStatusRead2)
        .isEqualTo("SETTLEMENT_FIRST".equals(firstCommit) ? "IN_FLIGHT" : expectedBatchDayStatus);

    batchDaySettleScheduler.settle();
    String selectedDayStatusRead3 = jdbcTemplate.queryForObject(
        selectDayStatusSql2, String.class, testTenantId, calendarCode, BIZ_DATE);
    assertThat(selectedDayStatusRead3).isEqualTo(expectedBatchDayStatus);
  }

  @ParameterizedTest(name = "{0}先取得业务日围栏时只派发一次")
  @ValueSource(strings = {"REPORT_FIRST", "SETTLEMENT_FIRST"})
  @DisplayName("Worker REPORT 经 GATEWAY 释放下游 JOB 与结算按两种顺序竞争")
  void directWorkflow_gatewayReleaseRacingSettlementShouldConverge(String firstLockWaiter)
      throws Exception {
    // 结算扫描按业务日倒序；给本场景独立日期，避免其它用例累计的候选日阻塞围栏竞态断言。
    LocalDate settlementBizDate = BIZ_DATE.plusYears(100);
    final String selectAcquireBatchDaySettlementFenceSql = """
            select batch.acquire_batch_day_settlement_fence(?, ?, ?)
        """;
    final String selectPgBackendPidSql = """
            select pg_backend_pid()
        """;

    final String insertBusinessCalendarSql = """
            insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone, enabled)\s\
            values (?, ?, ?, 'UTC', true)\
            """;
    final String updateWorkflowDefinitionSql = """
            update batch.workflow_definition set calendar_code = ? where id = ?
        """;
    final String updateWorkflowNodeSql = """
    update batch.workflow_node
    set node_order = 4
    where tenant_id = ? and workflow_definition_id = ? and node_code = 'END'
    """;
    final String insertWorkflowNodeSql = """
    insert into batch.workflow_node (
    tenant_id, workflow_definition_id, node_code, node_name, node_type,
    related_job_code, node_order, retry_policy, retry_max_count, timeout_seconds, enabled
    ) values
    (?, ?, 'ROUTE', 'Route', 'GATEWAY', null, 2, 'NONE', 0, 0, true),
    (?, ?, 'JOB_NODE_2', 'Second Job Node', 'JOB', ?, 3, 'NONE', 0, 0, true)
    """;
    final String deleteWorkflowEdgeSql = """
            delete from batch.workflow_edge where tenant_id = ? and workflow_definition_id = ?
        """;
    final String insertWorkflowEdgeSql = """
    insert into batch.workflow_edge (
    tenant_id, workflow_definition_id, from_node_code, to_node_code, edge_type, enabled
    ) values
    (?, ?, 'START', 'JOB_NODE_1', 'ALWAYS', true),
    (?, ?, 'JOB_NODE_1', 'ROUTE', 'SUCCESS', true),
    (?, ?, 'ROUTE', 'JOB_NODE_2', 'ALWAYS', true),
    (?, ?, 'JOB_NODE_2', 'END', 'SUCCESS', true)
    """;
    final String selectIdSql = """
            select id from batch.job_task where tenant_id = ? and job_instance_id = ?
        """;
    final String updateJobTaskSql = """
            update batch.job_task set task_status = 'RUNNING', started_at = ? where tenant_id = ? and id = ?
        """;
    final String updateBatchDayInstanceSql = """
    update batch.batch_day_instance
    set day_status = 'CUTOFF', open_at = current_timestamp - interval '1 day',
    cutoff_at = current_timestamp - interval '1 second', frozen = false,
    version = version + 1, updated_at = current_timestamp
    where tenant_id = ? and calendar_code = ? and biz_date = ?
    """;
    final String selectNodeStatusSql = """
            select node_status from batch.workflow_node_run where workflow_run_id = ?\s\
            and node_code = 'JOB_NODE_1'\
            """;
    final String selectNodeStatusSql2 = """
      select node_status from batch.workflow_node_run where workflow_run_id = ? and node_code = 'ROUTE'\
      """;
    final String selectCountSql = """
    select count(*) from batch.trigger_request
    where tenant_id = ? and parent_workflow_run_id = ? and parent_node_code = 'JOB_NODE_2'
    """;
    final String selectCountSql2 = """
            select count(*) from batch.workflow_node_run
            where workflow_run_id = ? and node_code = 'JOB_NODE_2'
              and node_status = 'READY' and job_instance_id is not null
            """;
    final String selectRunStatusSql = """
        select run_status from batch.workflow_run where tenant_id = ? and id = ?
    """;
    final String selectDayStatusSql = """
        select day_status from batch.batch_day_instance where tenant_id = ? and calendar_code = ?\s\
        and biz_date = ?\
        """;

    String suffix = Long.toUnsignedString(System.nanoTime());
    String calendarCode = "WF_GATEWAY_R11_" + suffix;
    String firstJobCode = seedChildJob("WG_GATEWAY_FIRST_R11_" + suffix);
    String secondJobCode = seedChildJob("WG_GATEWAY_SECOND_R11_" + suffix);
    DirectWorkflowSeed seed = seedDirectWorkflow(testTenantId, firstJobCode, settlementBizDate);
    jdbcTemplate.update(insertBusinessCalendarSql, testTenantId, calendarCode, calendarCode);
    jdbcTemplate.update(updateWorkflowDefinitionSql, calendarCode, seed.workflowDefinitionId());
    jdbcTemplate.update(updateWorkflowNodeSql, testTenantId, seed.workflowDefinitionId());
    jdbcTemplate.update(
        insertWorkflowNodeSql,
        testTenantId,
        seed.workflowDefinitionId(),
        testTenantId,
        seed.workflowDefinitionId(),
        secondJobCode);
    jdbcTemplate.update(deleteWorkflowEdgeSql, testTenantId, seed.workflowDefinitionId());
    jdbcTemplate.update(
        insertWorkflowEdgeSql,
        testTenantId,
        seed.workflowDefinitionId(),
        testTenantId,
        seed.workflowDefinitionId(),
        testTenantId,
        seed.workflowDefinitionId(),
        testTenantId,
        seed.workflowDefinitionId());
    LaunchRequest launchRequest = LaunchRequest.builder()
        .tenantId(testTenantId)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(settlementBizDate)
        .triggerType(TriggerType.API)
        .requestId(seed.requestId())
        .traceId("trace-worker-gateway-r11-" + suffix)
        .params(Map.of())
        .build();
    workflowRunLaunchService.launch(launchRequest);

    Long workflowRunId = workflowRunId(seed.requestId());
    Long firstInstanceId = linkedNodeJobInstance(workflowRunId, "JOB_NODE_1");
    Long taskId =
        jdbcTemplate.queryForObject(selectIdSql, Long.class, testTenantId, firstInstanceId);
    jdbcTemplate.update(
        updateJobTaskSql, Timestamp.from(BatchDateTimeSupport.utcNow()), testTenantId, taskId);
    jdbcTemplate.update(updateBatchDayInstanceSql, testTenantId, calendarCode, settlementBizDate);

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try (Connection fence = jdbcTemplate.getDataSource().getConnection()) {
      fence.setAutoCommit(false);
      try (var statement = fence.prepareStatement(selectAcquireBatchDaySettlementFenceSql)) {
        statement.setString(1, testTenantId);
        statement.setString(2, calendarCode);
        statement.setObject(3, settlementBizDate);
        statement.executeQuery().close();
      }
      int fencePid;
      try (var statement = fence.createStatement();
          var result = statement.executeQuery(selectPgBackendPidSql)) {
        result.next();
        fencePid = result.getInt(1);
      }
      Future<?> settlement;
      Future<?> report;
      if ("REPORT_FIRST".equals(firstLockWaiter)) {
        TaskOutcomeCommand reportCommand = TaskOutcomeCommand.builder()
            .tenantId(testTenantId)
            .taskId(taskId)
            .success(true)
            .resultSummary("{}")
            .build();
        report = executor.submit(() -> taskOutcomeService.applyTaskOutcome(reportCommand));
        awaitBlockedBySql(fencePid, "acquire_batch_day_settlement_fence");
        settlement = executor.submit(batchDaySettleScheduler::settle);
      } else {
        settlement = executor.submit(batchDaySettleScheduler::settle);
        awaitBlockedBySql(fencePid, "acquire_batch_day_settlement_fence");
        TaskOutcomeCommand reportCommand = TaskOutcomeCommand.builder()
            .tenantId(testTenantId)
            .taskId(taskId)
            .success(true)
            .resultSummary("{}")
            .build();
        report = executor.submit(() -> taskOutcomeService.applyTaskOutcome(reportCommand));
      }
      awaitBlockedBySql(fencePid, "acquire_batch_day_settlement_fence", 2);
      fence.commit();
      settlement.get(10, TimeUnit.SECONDS);
      report.get(10, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }

    String selectedNodeStatus =
        jdbcTemplate.queryForObject(selectNodeStatusSql, String.class, workflowRunId);
    assertThat(selectedNodeStatus).isEqualTo("SUCCESS");
    String selectedNodeStatusRead2 =
        jdbcTemplate.queryForObject(selectNodeStatusSql2, String.class, workflowRunId);
    assertThat(selectedNodeStatusRead2).isEqualTo("SUCCESS");
    Long selectedTriggerRequestCount =
        jdbcTemplate.queryForObject(selectCountSql, Long.class, testTenantId, workflowRunId);
    assertThat(selectedTriggerRequestCount).isEqualTo(1L);
    Long selectedWorkflowNodeRunCount =
        jdbcTemplate.queryForObject(selectCountSql2, Long.class, workflowRunId);
    assertThat(selectedWorkflowNodeRunCount).isEqualTo(1L);
    String selectedRunStatus =
        jdbcTemplate.queryForObject(selectRunStatusSql, String.class, testTenantId, workflowRunId);
    assertThat(selectedRunStatus).isEqualTo("RUNNING");
    String selectedDayStatus = jdbcTemplate.queryForObject(
        selectDayStatusSql, String.class, testTenantId, calendarCode, settlementBizDate);
    assertThat(selectedDayStatus).isEqualTo("IN_FLIGHT");
  }

  @Test
  @DisplayName("组合 Workflow 并行必需分支失败时不写成功 END 且根运行失败")
  void directWorkflow_shouldFailWhenRequiredParallelJobFails() {
    final String updateWorkflowNodeSql = """
          update batch.workflow_node
          set node_order = 3
          where workflow_definition_id = ? and node_code = 'END'
          """;
    final String insertWorkflowNodeSql = """
          insert into batch.workflow_node (
              tenant_id, workflow_definition_id, node_code, node_name, node_type,
              related_job_code, node_order, retry_policy, retry_max_count, timeout_seconds, enabled
          ) values (?, ?, 'JOB_NODE_2', 'Parallel Job Node', 'JOB', ?, 2, 'NONE', 0, 0, true)
          """;
    final String insertWorkflowEdgeSql = """
          insert into batch.workflow_edge (
              tenant_id, workflow_definition_id, from_node_code, to_node_code, edge_type, enabled
          ) values
              (?, ?, 'START', 'JOB_NODE_2', 'ALWAYS', true),
              (?, ?, 'JOB_NODE_2', 'END', 'SUCCESS', true)
          """;
    final String selectRunStatusSql = """
        select run_status from batch.workflow_run where id = ?
    """;
    final String selectInstanceStatusSql = """
        select instance_status from batch.job_instance where id = ?
    """;
    final String selectNodeStatusSql = """
        select node_status from batch.workflow_node_run where workflow_run_id = ? and node_code = 'JOB_NODE_2'\
        """;
    final String selectCountSql = """
          select count(*) from batch.workflow_node_run
          where workflow_run_id = ? and node_code = 'END' and node_status = 'SUCCESS'
          """;

    String workerGroup = "WG_ROOT_PARALLEL_FAIL_" + System.nanoTime();
    String firstJobCode = seedChildJob(workerGroup);
    String secondJobCode = seedChildJob(workerGroup);
    DirectWorkflowSeed seed = seedDirectWorkflow(firstJobCode);
    jdbcTemplate.update(updateWorkflowNodeSql, seed.workflowDefinitionId());
    jdbcTemplate.update(
        insertWorkflowNodeSql, testTenantId, seed.workflowDefinitionId(), secondJobCode);
    jdbcTemplate.update(
        insertWorkflowEdgeSql,
        testTenantId,
        seed.workflowDefinitionId(),
        testTenantId,
        seed.workflowDefinitionId());

    LaunchRequest launchRequest = LaunchRequest.builder()
        .tenantId(testTenantId)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.API)
        .requestId(seed.requestId())
        .traceId("trace-root-parallel-failure")
        .params(Map.of())
        .build();
    workflowRunLaunchService.launch(launchRequest);
    Long workflowRunId = workflowRunId(seed.requestId());
    Long firstInstanceId = linkedNodeJobInstance(workflowRunId, "JOB_NODE_1");
    Long secondInstanceId = linkedNodeJobInstance(workflowRunId, "JOB_NODE_2");
    assertThat(firstInstanceId).isNotNull().isNotEqualTo(secondInstanceId);

    reportJobSuccess(firstInstanceId);
    String selectedRunStatus =
        jdbcTemplate.queryForObject(selectRunStatusSql, String.class, workflowRunId);
    assertThat(selectedRunStatus).isEqualTo("RUNNING");

    reportJobFailure(secondInstanceId);

    String selectedInstanceStatus =
        jdbcTemplate.queryForObject(selectInstanceStatusSql, String.class, secondInstanceId);
    assertThat(selectedInstanceStatus).isEqualTo("FAILED");
    String selectedNodeStatus =
        jdbcTemplate.queryForObject(selectNodeStatusSql, String.class, workflowRunId);
    assertThat(selectedNodeStatus).isEqualTo("FAILED");
    String selectedRunStatusRead2 =
        jdbcTemplate.queryForObject(selectRunStatusSql, String.class, workflowRunId);
    assertThat(selectedRunStatusRead2).isEqualTo("FAILED");
    Long selectedWorkflowNodeRunCount =
        jdbcTemplate.queryForObject(selectCountSql, Long.class, workflowRunId);
    assertThat(selectedWorkflowNodeRunCount).isZero();
  }

  @Test
  @DisplayName("必需作业失败后清理分支和 END 成功仍保留根运行失败")
  void directWorkflow_shouldRunCleanupAfterRequiredJobFailureAndKeepRootFailed() {
    final String updateWorkflowNodeSql = """
          update batch.workflow_node
          set node_order = 5, node_params = '{"joinMode":"ANY"}'::jsonb
          where workflow_definition_id = ? and node_code = 'END'
          """;
    final String insertWorkflowNodeSql = """
          insert into batch.workflow_node (
              tenant_id, workflow_definition_id, node_code, node_name, node_type,
              related_job_code, node_order, retry_policy, retry_max_count, timeout_seconds, enabled
          ) values
              (?, ?, 'SUCCESS_ONLY', 'Success Branch', 'JOB', ?, 2, 'NONE', 0, 0, true),
              (?, ?, 'CONDITION_ONLY', 'Conditional Branch', 'JOB', ?, 3, 'NONE', 0, 0, true),
              (?, ?, 'CLEANUP_NODE', 'Cleanup Job', 'JOB', ?, 4, 'NONE', 0, 0, true)
          """;
    final String deleteWorkflowEdgeSql = """
        delete from batch.workflow_edge where workflow_definition_id = ?
    """;
    final String insertWorkflowEdgeSql = """
          insert into batch.workflow_edge (
              tenant_id, workflow_definition_id, from_node_code, to_node_code, edge_type,
              condition_expr, enabled
          ) values
              (?, ?, 'START', 'JOB_NODE_1', 'ALWAYS', null, true),
              (?, ?, 'JOB_NODE_1', 'END', 'SUCCESS', null, true),
              (?, ?, 'JOB_NODE_1', 'SUCCESS_ONLY', 'SUCCESS', null, true),
              (?, ?, 'JOB_NODE_1', 'CONDITION_ONLY', 'CONDITION', 'route = ''A''', true),
              (?, ?, 'JOB_NODE_1', 'CLEANUP_NODE', 'FAILURE', null, true),
              (?, ?, 'SUCCESS_ONLY', 'END', 'SUCCESS', null, true),
              (?, ?, 'CONDITION_ONLY', 'END', 'SUCCESS', null, true),
              (?, ?, 'CLEANUP_NODE', 'END', 'SUCCESS', null, true)
          """;
    final String selectCountSql = """
      select count(*) from batch.job_instance where tenant_id = ? and job_code = ?
  """;
    final String selectNodeCodeSql = """
          select node_code, node_status, skip_reason_code
          from batch.workflow_node_run
          where workflow_run_id = ? and node_code in ('SUCCESS_ONLY', 'CONDITION_ONLY')
          order by node_code
          """;
    final String selectCountSql2 = """
        select count(*) from batch.workflow_node_run
        where workflow_run_id = ? and node_code in ('SUCCESS_ONLY', 'CONDITION_ONLY')
          and job_instance_id is not null
        """;
    final String selectRunStatusSql = """
      select run_status from batch.workflow_run where id = ?
  """;
    final String selectNodeStatusSql = """
      select node_status from batch.workflow_node_run where workflow_run_id = ? and node_code = 'CLEANUP_NODE'\
      """;
    final String selectCountSql3 = """
        select count(*) from batch.workflow_node_run
        where workflow_run_id = ? and node_code = 'END' and node_status = 'SUCCESS'
        """;

    String workerGroup = "WG_ROOT_CLEANUP_" + System.nanoTime();
    String requiredJobCode = seedChildJob(workerGroup);
    String cleanupJobCode = seedChildJob(workerGroup);
    DirectWorkflowSeed seed = seedDirectWorkflow(requiredJobCode);
    jdbcTemplate.update(updateWorkflowNodeSql, seed.workflowDefinitionId());
    jdbcTemplate.update(
        insertWorkflowNodeSql,
        testTenantId,
        seed.workflowDefinitionId(),
        requiredJobCode,
        testTenantId,
        seed.workflowDefinitionId(),
        requiredJobCode,
        testTenantId,
        seed.workflowDefinitionId(),
        cleanupJobCode);
    jdbcTemplate.update(deleteWorkflowEdgeSql, seed.workflowDefinitionId());
    jdbcTemplate.update(
        insertWorkflowEdgeSql,
        testTenantId,
        seed.workflowDefinitionId(),
        testTenantId,
        seed.workflowDefinitionId(),
        testTenantId,
        seed.workflowDefinitionId(),
        testTenantId,
        seed.workflowDefinitionId(),
        testTenantId,
        seed.workflowDefinitionId(),
        testTenantId,
        seed.workflowDefinitionId(),
        testTenantId,
        seed.workflowDefinitionId(),
        testTenantId,
        seed.workflowDefinitionId());

    LaunchRequest launchRequest = LaunchRequest.builder()
        .tenantId(testTenantId)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.API)
        .requestId(seed.requestId())
        .traceId("trace-root-cleanup-failure")
        .params(Map.of())
        .build();
    workflowRunLaunchService.launch(launchRequest);
    Long workflowRunId = workflowRunId(seed.requestId());
    Long requiredInstanceId = linkedNodeJobInstance(workflowRunId, "JOB_NODE_1");
    assertThat(requiredInstanceId).isNotNull();
    Long selectedJobInstanceCount =
        jdbcTemplate.queryForObject(selectCountSql, Long.class, testTenantId, cleanupJobCode);
    assertThat(selectedJobInstanceCount).isZero();

    reportJobFailure(requiredInstanceId);

    Long cleanupInstanceId = linkedNodeJobInstance(workflowRunId, "CLEANUP_NODE");
    assertThat(cleanupInstanceId).isNotNull();
    List<Map<String, Object>> selectedNodeCodeValues =
        jdbcTemplate.queryForList(selectNodeCodeSql, workflowRunId);
    assertThat(selectedNodeCodeValues)
        .extracting(
            row -> row.get("node_code"),
            row -> row.get("node_status"),
            row -> row.get("skip_reason_code"))
        .containsExactly(
            Tuple.tuple("CONDITION_ONLY", "SKIPPED", "UPSTREAM_FAILED"),
            Tuple.tuple("SUCCESS_ONLY", "SKIPPED", "UPSTREAM_FAILED"));
    Long selectedWorkflowNodeRunCount =
        jdbcTemplate.queryForObject(selectCountSql2, Long.class, workflowRunId);
    assertThat(selectedWorkflowNodeRunCount).isZero();
    String selectedRunStatus =
        jdbcTemplate.queryForObject(selectRunStatusSql, String.class, workflowRunId);
    assertThat(selectedRunStatus).isEqualTo("RUNNING");
    reportJobSuccess(cleanupInstanceId);

    String selectedNodeStatus =
        jdbcTemplate.queryForObject(selectNodeStatusSql, String.class, workflowRunId);
    assertThat(selectedNodeStatus).isEqualTo("SUCCESS");
    Long selectedWorkflowNodeRunCountRead2 =
        jdbcTemplate.queryForObject(selectCountSql3, Long.class, workflowRunId);
    assertThat(selectedWorkflowNodeRunCountRead2).isEqualTo(1L);
    String selectedRunStatusRead2 =
        jdbcTemplate.queryForObject(selectRunStatusSql, String.class, workflowRunId);
    assertThat(selectedRunStatusRead2).isEqualTo("FAILED");
  }

  private long activePartitionsForWorkflow(Long workflowRunId) {
    final String selectCountSql = """
          select count(*)
          from batch.workflow_node_run node_run
          join batch.job_instance instance
            on instance.id = node_run.job_instance_id
          join batch.job_partition partition
            on partition.tenant_id = instance.tenant_id
           and partition.job_instance_id = instance.id
          where node_run.workflow_run_id = ?
            and partition.partition_status in ('READY', 'RUNNING', 'RETRYING')
          """;

    return jdbcTemplate.queryForObject(selectCountSql, Long.class, workflowRunId);
  }

  private Long linkedNodeJobInstance(Long workflowRunId, String nodeCode) {
    final String selectJobInstanceIdSql = """
          select job_instance_id from batch.workflow_node_run
          where workflow_run_id = ? and node_code = ? and job_instance_id is not null
          order by run_seq desc limit 1
          """;

    return jdbcTemplate.queryForObject(selectJobInstanceIdSql, Long.class, workflowRunId, nodeCode);
  }

  private String taskNodeCode(Long jobInstanceId) {
    final String selectTaskPayloadSql = """
          select task_payload ->> 'workflowNodeCode'
          from batch.job_task
          where job_instance_id = ?
          order by id
          limit 1
          """;

    return jdbcTemplate.queryForObject(selectTaskPayloadSql, String.class, jobInstanceId);
  }

  private void reportJobSuccess(Long jobInstanceId) {
    final String selectIdSql = """
        select id from batch.job_task where tenant_id = ? and job_instance_id = ?
    """;
    final String updateJobTaskSql = """
        update batch.job_task set task_status = 'RUNNING', started_at = ? where id = ?
    """;

    Long taskId = jdbcTemplate.queryForObject(selectIdSql, Long.class, testTenantId, jobInstanceId);
    jdbcTemplate.update(updateJobTaskSql, Timestamp.from(BatchDateTimeSupport.utcNow()), taskId);
    TaskOutcomeCommand outcome = TaskOutcomeCommand.builder()
        .tenantId(testTenantId)
        .taskId(taskId)
        .success(true)
        .resultSummary("{}")
        .build();
    taskOutcomeService.applyTaskOutcome(outcome);
  }

  private void reportJobFailure(Long jobInstanceId) {
    final String selectIdSql = """
        select id from batch.job_task where tenant_id = ? and job_instance_id = ?
    """;
    final String updateJobTaskSql = """
        update batch.job_task set task_status = 'RUNNING', started_at = ? where id = ?
    """;

    Long taskId = jdbcTemplate.queryForObject(selectIdSql, Long.class, testTenantId, jobInstanceId);
    jdbcTemplate.update(updateJobTaskSql, Timestamp.from(BatchDateTimeSupport.utcNow()), taskId);
    TaskOutcomeCommand outcome = TaskOutcomeCommand.builder()
        .tenantId(testTenantId)
        .taskId(taskId)
        .errorCode("ERR_PARALLEL_BRANCH")
        .errorMessage("required parallel branch failed")
        .resultSummary("{}")
        .build();
    taskOutcomeService.applyTaskOutcome(outcome);
  }

  private void awaitBlockedBy(int blockerPid) throws Exception {
    awaitBlockedBy(blockerPid, 1);
  }

  private void awaitBlockedBy(int blockerPid, int expectedWaiters) throws Exception {
    final String selectCountSql = """
        select count(*) from pg_stat_activity where ? = any(pg_blocking_pids(pid))
    """;

    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      Integer waiting = jdbcTemplate.queryForObject(selectCountSql, Integer.class, blockerPid);
      if (waiting != null && waiting >= expectedWaiters) return;
      Thread.sleep(10);
    }
    throw new AssertionError(
        "Expected " + expectedWaiters + " PostgreSQL transactions blocked by " + blockerPid);
  }

  private void awaitBlockedBySql(int blockerPid, String sqlFragment) throws Exception {
    awaitBlockedBySql(blockerPid, sqlFragment, 1);
  }

  private void awaitBlockedBySql(int blockerPid, String sqlFragment, int expectedWaiters)
      throws Exception {
    final String selectCountSql = """
        select count(*) from pg_stat_activity where ? = any(pg_blocking_pids(pid))\s\
        and lower(query) like '%%' || lower(?) || '%%'\
        """;

    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      Integer waiting =
          jdbcTemplate.queryForObject(selectCountSql, Integer.class, blockerPid, sqlFragment);
      if (waiting != null && waiting >= expectedWaiters) return;
      Thread.sleep(10);
    }
    throw new AssertionError("Expected " + expectedWaiters + " PostgreSQL transactions blocked by "
        + blockerPid + " executing " + sqlFragment);
  }

  private void awaitSqlWaiting(String sqlFragment) throws Exception {
    final String selectWaitingCountSql = """
        select count(*) from pg_stat_activity
        where state = 'active' and wait_event_type = 'Lock'
          and lower(query) like '%%' || lower(?) || '%%'
        """;

    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      Integer waiting =
          jdbcTemplate.queryForObject(selectWaitingCountSql, Integer.class, sqlFragment);
      if (waiting != null && waiting > 0) return;
      Thread.sleep(10);
    }
    throw new AssertionError("Expected a PostgreSQL transaction waiting on " + sqlFragment);
  }

  private static int backendPid(Connection connection) throws Exception {
    final String selectPgBackendPidSql = """
        select pg_backend_pid()
    """;

    try (var statement = connection.createStatement();
        var result = statement.executeQuery(selectPgBackendPidSql)) {
      assertThat(result.next()).isTrue();
      return result.getInt(1);
    }
  }

  @Test
  @DisplayName("子任务成功后回传信号, 子实例与父实例均收敛为成功, 节点运行状态同步为成功")
  void childJobCompletion_shouldSignalParentAndCompleteParentJobAsSuccess() {
    final String selectIdSql = """
      select id from batch.job_instance where tenant_id = ? and job_code = ?
  """;
    final String selectIdSql2 = """
            select id from batch.job_task where tenant_id = ? and job_instance_id = ?
            """;
    final String updateJobTaskSql = """
        update batch.job_task set task_status = 'RUNNING', started_at = ? where id = ?
    """;
    final String selectInstanceStatusSql = """
        select instance_status from batch.job_instance where id = ?
    """;
    final String selectRunStatusSql = """
        select run_status from batch.workflow_run where id = ?
    """;
    final String selectNodeStatusSql = """
              select wnr.node_status
              from batch.workflow_node_run wnr
              join batch.workflow_run wr on wr.id = wnr.workflow_run_id
              where wr.id = ?
                and wnr.node_code = 'JOB_NODE_1'
              """;

    String workerGroup = "WG_JN_COMP_" + System.nanoTime();
    String childCode = seedChildJob(workerGroup);
    ParentSeed parent = seedParentJob(childCode);

    LaunchRequest launchRequest2 = LaunchRequest.builder()
        .tenantId(testTenantId)
        .workflowDefinitionId(parent.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.API)
        .requestId(parent.requestId())
        .traceId("trace-job-node-2")
        .params(Map.of())
        .build();
    workflowRunLaunchService.launch(launchRequest2);
    Long parentWorkflowRunId = workflowRunId(parent.requestId());
    assertThat(parentWorkflowRunId).isNotNull();

    // 查找子 job instance
    Long childInstanceId =
        jdbcTemplate.queryForObject(selectIdSql, Long.class, testTenantId, childCode);
    assertThat(childInstanceId).isNotNull();

    // 查找子任务（属于子 job instance）
    Long childTaskId =
        jdbcTemplate.queryForObject(selectIdSql2, Long.class, testTenantId, childInstanceId);
    assertThat(childTaskId).isNotNull();

    // 模拟 Worker 拾取子任务：转为 RUNNING
    jdbcTemplate.update(
        updateJobTaskSql, Timestamp.from(BatchDateTimeSupport.utcNow()), childTaskId);

    // 模拟子任务成功完成
    TaskOutcomeCommand childSuccessOutcome = TaskOutcomeCommand.builder()
        .tenantId(testTenantId)
        .taskId(childTaskId)
        .success(true)
        .resultSummary("{}")
        .build();
    taskOutcomeService.applyTaskOutcome(childSuccessOutcome);

    // 子 job instance 应为 SUCCESS
    String childStatus =
        jdbcTemplate.queryForObject(selectInstanceStatusSql, String.class, childInstanceId);
    assertThat(childStatus).isEqualTo("SUCCESS");

    // 父 WorkflowRun 直接由 NodeRun 终态推进。
    String parentStatus =
        jdbcTemplate.queryForObject(selectRunStatusSql, String.class, parentWorkflowRunId);
    assertThat(parentStatus).isEqualTo("SUCCESS");

    // 父 workflow_node_run 中 JOB_NODE_1 应为 SUCCESS
    String nodeRunStatus =
        jdbcTemplate.queryForObject(selectNodeStatusSql, String.class, parentWorkflowRunId);
    assertThat(nodeRunStatus).isEqualTo("SUCCESS");
  }

  @Test
  @DisplayName("没有根 Job Instance 的 Workflow 通过节点 Job Instance 关联推进到终态")
  void childJobCompletion_shouldAdvanceDirectWorkflowThroughNodeInstanceLink() {
    final String selectIdSql = """
      select id from batch.job_instance where tenant_id = ? and job_code = ?
  """;
    final String selectIdSql2 = """
      select id from batch.job_task where tenant_id = ? and job_instance_id = ?
  """;
    final String selectIdSql3 = """
        select workflow_run.id
        from batch.workflow_run workflow_run
        join batch.workflow_node_run node_run on node_run.workflow_run_id = workflow_run.id
        where node_run.job_instance_id = ?
        """;
    final String updateJobTaskSql = """
        update batch.job_task set task_status = 'RUNNING', started_at = ? where id = ?
    """;
    final String selectNodeStatusSql = """
          select node_status from batch.workflow_node_run
          where workflow_run_id = ? and node_code = 'JOB_NODE_1'
          """;
    final String selectRunStatusSql = """
        select run_status from batch.workflow_run where id = ?
    """;

    String workerGroup = "WG_JN_DIRECT_" + System.nanoTime();
    String childCode = seedChildJob(workerGroup);
    ParentSeed parent = seedParentJob(childCode);

    LaunchRequest launchRequest = LaunchRequest.builder()
        .tenantId(testTenantId)
        .workflowDefinitionId(parent.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.API)
        .requestId(parent.requestId())
        .traceId("trace-job-node-direct")
        .params(Map.of())
        .build();
    workflowRunLaunchService.launch(launchRequest);

    Long childInstanceId =
        jdbcTemplate.queryForObject(selectIdSql, Long.class, testTenantId, childCode);
    Long childTaskId =
        jdbcTemplate.queryForObject(selectIdSql2, Long.class, testTenantId, childInstanceId);
    Long workflowRunId = jdbcTemplate.queryForObject(selectIdSql3, Long.class, childInstanceId);

    // 为直接写入的 Job Instance 设置与运行时启动相同的 Workflow 父级链。
    jdbcTemplate.update(
        updateJobTaskSql, Timestamp.from(BatchDateTimeSupport.utcNow()), childTaskId);

    TaskOutcomeCommand outcome = TaskOutcomeCommand.builder()
        .tenantId(testTenantId)
        .taskId(childTaskId)
        .success(true)
        .resultSummary("{}")
        .build();
    taskOutcomeService.applyTaskOutcome(outcome);

    String nodeRunStatus =
        jdbcTemplate.queryForObject(selectNodeStatusSql, String.class, workflowRunId);
    String workflowRunStatus =
        jdbcTemplate.queryForObject(selectRunStatusSql, String.class, workflowRunId);
    assertThat(nodeRunStatus).isEqualTo("SUCCESS");
    assertThat(workflowRunStatus).isEqualTo("SUCCESS");
  }

  @Test
  @DisplayName("子任务失败且无重试时回传信号, 父实例收敛为失败, 节点运行状态同步为失败")
  void childJobFailure_shouldSignalParentAndCompleteParentJobAsFailed() {
    final String selectIdSql = """
      select id from batch.job_instance where tenant_id = ? and job_code = ?
  """;
    final String selectIdSql2 = """
      select id from batch.job_task where tenant_id = ? and job_instance_id = ?
  """;
    final String updateJobTaskSql = """
        update batch.job_task set task_status = 'RUNNING', started_at = ? where id = ?
    """;
    final String selectRunStatusSql = """
        select run_status from batch.workflow_run where id = ?
    """;
    final String selectNodeStatusSql = """
              select wnr.node_status
              from batch.workflow_node_run wnr
              join batch.workflow_run wr on wr.id = wnr.workflow_run_id
              where wr.id = ?
                and wnr.node_code = 'JOB_NODE_1'
              """;

    String workerGroup = "WG_JN_FAIL_" + System.nanoTime();
    String childCode = seedChildJob(workerGroup);
    ParentSeed parent = seedParentJob(childCode);

    LaunchRequest launchRequest3 = LaunchRequest.builder()
        .tenantId(testTenantId)
        .workflowDefinitionId(parent.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.API)
        .requestId(parent.requestId())
        .traceId("trace-job-node-3")
        .params(Map.of())
        .build();
    workflowRunLaunchService.launch(launchRequest3);
    Long parentWorkflowRunId = workflowRunId(parent.requestId());

    Long childInstanceId =
        jdbcTemplate.queryForObject(selectIdSql, Long.class, testTenantId, childCode);

    Long childTaskId =
        jdbcTemplate.queryForObject(selectIdSql2, Long.class, testTenantId, childInstanceId);

    jdbcTemplate.update(
        updateJobTaskSql, Timestamp.from(BatchDateTimeSupport.utcNow()), childTaskId);

    TaskOutcomeCommand childFailureOutcome = TaskOutcomeCommand.builder()
        .tenantId(testTenantId)
        .taskId(childTaskId)
        .resultSummary("{}")
        .errorCode("ERR_CHILD")
        .errorMessage("child task failed")
        .build();
    taskOutcomeService.applyTaskOutcome(childFailureOutcome);

    // 父 WorkflowRun 应为 FAILED（子任务失败，无重试策略）。
    String parentStatus =
        jdbcTemplate.queryForObject(selectRunStatusSql, String.class, parentWorkflowRunId);
    assertThat(parentStatus).isEqualTo("FAILED");

    // 父 workflow_node_run 中 JOB_NODE_1 应为 FAILED
    String nodeRunStatus =
        jdbcTemplate.queryForObject(selectNodeStatusSql, String.class, parentWorkflowRunId);
    assertThat(nodeRunStatus).isEqualTo("FAILED");
  }
}
