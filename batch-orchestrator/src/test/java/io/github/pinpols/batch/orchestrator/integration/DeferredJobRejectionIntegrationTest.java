package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowNodeDispatchService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunLaunchService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunManagementApplicationService;
import io.github.pinpols.batch.orchestrator.domain.entity.JobDependencyWaitingLaunchEntity;
import io.github.pinpols.batch.orchestrator.infrastructure.scheduler.JobDependencyWaitingLaunchReconciler;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture.WorkflowLaunchSeed;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import io.github.pinpols.batch.orchestrator.service.JobDependencyWaitingLaunchService;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 用真实根启动与等待恢复证明子请求拒绝的DAG终态和重扫恢复，不用SQL代替核心状态推进。 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("延期子Job拒绝推进所属Workflow节点及根终态")
class DeferredJobRejectionIntegrationTest extends AbstractIntegrationTest {
  private static final LocalDate BIZ_DATE = LocalDate.of(2026, 10, 18);

  @Autowired
  private JdbcTemplate jdbc;

  @Autowired
  private WorkflowRunLaunchService launchService;

  @Autowired
  private JobDependencyWaitingLaunchReconciler reconciler;

  @Autowired
  private JobDependencyWaitingLaunchService waitingService;

  @Autowired
  private WorkflowRunManagementApplicationService management;

  @MockitoSpyBean
  private WorkflowNodeRunMapper nodeMapper;

  private String tenant;
  private WorkflowLaunchSeed downstream;
  private long runId;
  private long nodeRunId;
  private String childRequestId;

  private void startRealDeferredJob() {
    startRealDeferredJob(false);
  }

  private void startRealDeferredJob(boolean failureHandler) {
    final String insertJobDependencySql = """
        insert into batch.job_dependency(tenant_id,downstream_job_code,upstream_job_code,dependency_order)
        values (?,?,?,0)
        """;
    final String updateWorkflowNodeSql = """
        update batch.workflow_node set node_order=3 where workflow_definition_id=? and node_code='END'
    """;
    final String insertWorkflowNodeSql = """
          insert into batch.workflow_node(tenant_id,workflow_definition_id,node_code,node_name,node_type,node_order,enabled)
          values (?,?,'REJECTION_HANDLER','Rejection handler','GATEWAY',2,true)
          """;
    final String updateWorkflowEdgeSql = """
          update batch.workflow_edge set to_node_code='REJECTION_HANDLER',edge_type='FAILURE'
          where workflow_definition_id=? and from_node_code='JOB' and to_node_code='END'
          """;
    final String insertWorkflowEdgeSql = """
          insert into batch.workflow_edge(tenant_id,workflow_definition_id,from_node_code,to_node_code,edge_type,enabled)
          values (?,?,'REJECTION_HANDLER','END','ALWAYS',true)
          """;
    final String selectRequestIdSql = """
        select request_id from batch.trigger_request where tenant_id=? and parent_workflow_run_id=?
        order by id desc limit 1
        """;
    final String selectIdSql = """
        select id from batch.workflow_node_run where workflow_run_id=? and node_code='JOB'
    """;
    final String selectWaitStatusSql = """
        select wait_status from batch.job_dependency_waiting_launch where tenant_id=?
    """;

    tenant = "deferred-reject-" + UUID.randomUUID();
    downstream = LaunchIntegrationFixture.prepareWorkflowLaunchWithoutWorker(
        jdbc, tenant, "DISPATCH", "DEFERRED_REJECT", TriggerType.EVENT, BIZ_DATE);
    WorkflowLaunchSeed upstream = LaunchIntegrationFixture.prepareWorkflowLaunchWithoutWorker(
        jdbc, tenant, "DISPATCH", "DEFERRED_UPSTREAM", TriggerType.API, BIZ_DATE);
    jdbc.update(insertJobDependencySql, tenant, downstream.jobCode(), upstream.jobCode());
    if (failureHandler) {
      jdbc.update(
          updateWorkflowNodeSql,
          downstream.workflowDefinitionId());
      jdbc.update(insertWorkflowNodeSql, tenant, downstream.workflowDefinitionId());
      jdbc.update(updateWorkflowEdgeSql, downstream.workflowDefinitionId());
      jdbc.update(insertWorkflowEdgeSql, tenant, downstream.workflowDefinitionId());
    }
    LaunchRequest request = LaunchRequest.builder()
        .tenantId(tenant)
        .workflowDefinitionId(downstream.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.EVENT)
        .requestId(downstream.requestId())
        .params(
            Map.of("_parentNodeRunId", -7, "_parentWorkflowRunId", -9, "_parentNodeCode", "forged"))
        .build();
    runId = Long.parseLong(
        RlsTenantContextHolder.runWithTenant(tenant, () -> launchService.launch(request))
            .instanceNo());
    childRequestId = jdbc.queryForObject(selectRequestIdSql, String.class, tenant, runId);
    nodeRunId = jdbc.queryForObject(
        selectIdSql,
        Long.class,
        runId);
    String selectedWaitStatus = jdbc.queryForObject(selectWaitStatusSql, String.class, tenant);
    assertThat(selectedWaitStatus).isEqualTo("WAITING");
  }

  @Test
  @DisplayName("内部子请求在原有快照中固定具体节点尝试，不能由根参数覆盖")
  void shouldFreezeExactParentNodeAttemptInOriginalChildRequest() {
    final String selectLaunchPayloadSql = """
        select launch_payload #>> '{params,_parentNodeRunId}' from batch.trigger_request
        where tenant_id=? and request_id=?
        """;

    startRealDeferredJob();
    String selectedLaunchPayload =
        jdbc.queryForObject(selectLaunchPayloadSql, String.class, tenant, childRequestId);
    assertThat(selectedLaunchPayload).isEqualTo(Long.toString(nodeRunId));
  }

  @Test
  @DisplayName("坏等待被拒绝后节点失败、根运行失败且只写一次终态Outbox")
  void shouldPropagateRejectedWaitToNodeAndRootExactlyOnce() {
    startRealDeferredJob();
    corruptWait();
    reconciler.reconcileTenant(tenant);
    assertRejectedRoot();
    reconciler.reconcileTenant(tenant);
    reconciler.reconcileTenant(tenant);
    assertRejectedRoot();
  }

  @Test
  @DisplayName("取消提交后节点推进事务故障回滚，下一轮仅靠已取消等待即可恢复推进")
  void shouldRecoverProgressionAfterCancellationCommitsButNodeWriteFails() {
    final String selectWaitStatusSql = """
        select wait_status from batch.job_dependency_waiting_launch where tenant_id=?
    """;
    final String selectNodeStatusSql = """
        select node_status from batch.workflow_node_run where id=?
    """;

    startRealDeferredJob();
    corruptWait();
    Mockito.doThrow(new DataAccessResourceFailureException("owned node-write fault"))
        .when(nodeMapper)
        .updateStatus(any());
    reconciler.reconcileTenant(tenant);
    String selectedWaitStatus = jdbc.queryForObject(selectWaitStatusSql, String.class, tenant);
    assertThat(selectedWaitStatus).isEqualTo("CANCELLED");
    String selectedNodeStatus = jdbc.queryForObject(selectNodeStatusSql, String.class, nodeRunId);
    assertThat(selectedNodeStatus).isEqualTo("READY");
    Mockito.verify(nodeMapper).updateStatus(any());
    assertThat(terminalEvents()).isZero();
    Mockito.reset(nodeMapper);
    reconciler.reconcileTenant(tenant);
    assertRejectedRoot();
  }

  @Test
  @DisplayName("暂停根运行时保留拒绝待推进，恢复后周期扫描收敛节点与根")
  void shouldDeferProgressionWhileRootIsPausedAndRecoverAfterResume() {
    final String selectRunStatusSql = """
        select run_status from batch.workflow_run where id=?
    """;
    final String selectNodeStatusSql = """
        select node_status from batch.workflow_node_run where id=?
    """;

    startRealDeferredJob();
    RlsTenantContextHolder.runWithTenant(tenant, () -> management.pause(tenant, runId));
    corruptWait();
    reconciler.reconcileTenant(tenant);
    String selectedRunStatus = jdbc.queryForObject(selectRunStatusSql, String.class, runId);
    assertThat(selectedRunStatus).isEqualTo("PAUSED");
    String selectedNodeStatus = jdbc.queryForObject(selectNodeStatusSql, String.class, nodeRunId);
    assertThat(selectedNodeStatus).isEqualTo("READY");
    assertThat(terminalEvents()).isZero();
    RlsTenantContextHolder.runWithTenant(tenant, () -> management.resume(tenant, runId));
    reconciler.reconcileTenant(tenant);
    assertRejectedRoot();
  }

  @Test
  @DisplayName("旧尝试的拒绝不能失败同节点编码下新建的重试尝试")
  void shouldNotApplyOldRejectionToNewNodeAttempt() {
    final String updateWorkflowNodeRunSql = """
        update batch.workflow_node_run set node_status='FAILED',finished_at=now() where id=?
    """;
    final String insertWorkflowNodeRunSql = """
        insert into batch.workflow_node_run(workflow_run_id,node_code,node_type,run_seq,node_status,retry_count)
        values (?,'JOB','JOB',2,'READY',1) returning id
        """;
    final String selectNodeStatusSql = """
        select node_status from batch.workflow_node_run where id=?
    """;
    final String selectRunStatusSql = """
        select run_status from batch.workflow_run where id=?
    """;

    startRealDeferredJob();
    corruptWait();
    // 建立已进入下一尝试的前置事实；核心动作仍是被测恢复器处理旧请求。
    jdbc.update(
        updateWorkflowNodeRunSql,
        nodeRunId);
    Long newer = jdbc.queryForObject(insertWorkflowNodeRunSql, Long.class, runId);
    reconciler.reconcileTenant(tenant);
    String selectedNodeStatus = jdbc.queryForObject(selectNodeStatusSql, String.class, newer);
    assertThat(selectedNodeStatus).isEqualTo("READY");
    String selectedRunStatus = jdbc.queryForObject(selectRunStatusSql, String.class, runId);
    assertThat(selectedRunStatus).isEqualTo("RUNNING");
    assertThat(terminalEvents()).isZero();
  }

  @Test
  @DisplayName("恢复拒绝复用冻结DAG的失败分支，失败处理节点被执行且根保留失败")
  void shouldAdvanceConfiguredFailureEdgeAfterDeferredRejection() {
    final String selectNodeStatusSql = """
        select node_status from batch.workflow_node_run where workflow_run_id=?\s\
        and node_code='REJECTION_HANDLER'
        """;

    startRealDeferredJob(true);
    corruptWait();
    reconciler.reconcileTenant(tenant);
    assertRejectedRoot();
    String selectedNodeStatus = jdbc.queryForObject(selectNodeStatusSql, String.class, runId);
    assertThat(selectedNodeStatus).isEqualTo("SUCCESS");
  }

  @Test
  @DisplayName("两个恢复器并发处理已提交的拒绝时，节点和终态Outbox只收敛一次")
  void shouldConvergeConcurrentRejectionScansToOneTerminalEvent() throws Exception {
    startRealDeferredJob();
    corruptWait();
    JobDependencyWaitingLaunchEntity waiting = RlsTenantContextHolder.runWithTenant(
        tenant, () -> waitingService.selectWaiting(tenant, 10).getFirst());
    RlsTenantContextHolder.runWithTenant(tenant, () -> waitingService.rejectInvalidWait(waiting));
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      Future<?> first = executor.submit(() -> {
        try {
          start.await();
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(ex);
        }
        reconciler.reconcileTenant(tenant);
      });
      Future<?> second = executor.submit(() -> {
        try {
          start.await();
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(ex);
        }
        reconciler.reconcileTenant(tenant);
      });
      start.countDown();
      first.get(15, TimeUnit.SECONDS);
      second.get(15, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
    assertRejectedRoot();
  }

  @Autowired
  private PlatformTransactionManager transactionManager;

  @Autowired
  private WorkflowNodeDispatchService dispatchService;

  @Test
  @DisplayName("普通非绕过RLS角色完成拒绝传播和终态Outbox事务")
  void shouldProgressRejectedWaitUnderNonBypassRlsRole() {
    final String doStatementSql = """
        DO $$ BEGIN
          IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='deferred_rejection_progress_rls') THEN
            CREATE ROLE deferred_rejection_progress_rls NOSUPERUSER NOBYPASSRLS;
          END IF;
        END $$
        """;
    final String grantUsageSql = """
        GRANT USAGE ON SCHEMA batch TO deferred_rejection_progress_rls
    """;
    final String grantSelectSql = """
        GRANT SELECT,INSERT,UPDATE ON ALL TABLES IN SCHEMA batch TO deferred_rejection_progress_rls
    """;
    final String grantUsageSql2 = """
        GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA batch TO deferred_rejection_progress_rls
    """;
    final String setLocalSql = """
        SET LOCAL ROLE deferred_rejection_progress_rls
    """;
    final String selectNotSql = """
          select not rolsuper and not rolbypassrls from pg_roles where rolname=current_user
          """;

    startRealDeferredJob();
    corruptWait();
    JobDependencyWaitingLaunchEntity waiting = RlsTenantContextHolder.runWithTenant(
        tenant, () -> waitingService.selectWaiting(tenant, 10).getFirst());
    RlsTenantContextHolder.runWithTenant(tenant, () -> waitingService.rejectInvalidWait(waiting));
    jdbc.execute(doStatementSql);
    jdbc.execute(grantUsageSql);
    jdbc.execute(
        grantSelectSql);
    jdbc.execute(
        grantUsageSql2);
    new TransactionTemplate(transactionManager).execute(tx -> {
      jdbc.execute(setLocalSql);
      Boolean currentRoleHasNoRlsBypassPrivilege = jdbc.queryForObject(selectNotSql, Boolean.class);
      assertThat(currentRoleHasNoRlsBypassPrivilege).isTrue();
      RlsTenantContextHolder.runWithTenant(
          tenant, () -> dispatchService.rejectDeferredChildRequest(tenant, childRequestId));
      return null;
    });
    assertRejectedRoot();
  }

  @AfterEach
  void removeOwnedRows() {
    final String deleteOutboxEventSql = """
        delete from batch.outbox_event where tenant_id=?
    """;
    final String deleteOutboxEventDedupKeySql = """
        delete from batch.outbox_event_dedup_key where tenant_id=?
    """;
    final String deleteJobDependencyWaitingLaunchSql = """
        delete from batch.job_dependency_waiting_launch where tenant_id=?
    """;
    final String deleteTriggerRequestSql = """
        delete from batch.trigger_request where tenant_id=? and parent_workflow_run_id is not null
    """;
    final String deleteWorkflowNodeRunSql = """
        delete from batch.workflow_node_run where workflow_run_id in
          (select id from batch.workflow_run where tenant_id=?)
        """;
    final String deleteWorkflowRunSql = """
        delete from batch.workflow_run where tenant_id=?
    """;
    final String deleteTriggerRequestSql2 = """
        delete from batch.trigger_request where tenant_id=?
    """;
    final String deleteJobDependencySql = """
        delete from batch.job_dependency where tenant_id=?
    """;
    final String deleteWorkflowEdgeSql = """
        delete from batch.workflow_edge where tenant_id=?
    """;
    final String deleteWorkflowNodeSql = """
        delete from batch.workflow_node where tenant_id=?
    """;
    final String deleteWorkflowDefinitionSql = """
        delete from batch.workflow_definition where tenant_id=?
    """;
    final String deleteJobDefinitionSql = """
        delete from batch.job_definition where tenant_id=?
    """;
    final String deleteBatchDayInstanceSql = """
        delete from batch.batch_day_instance where tenant_id=?
    """;
    final String deleteBatchDayCatchupControlSql = """
        delete from batch.batch_day_catchup_control where tenant_id=?
    """;
    final String deleteBusinessCalendarSql = """
        delete from batch.business_calendar where tenant_id=?
    """;

    jdbc.update(deleteOutboxEventSql, tenant);
    jdbc.update(deleteOutboxEventDedupKeySql, tenant);
    jdbc.update(deleteJobDependencyWaitingLaunchSql, tenant);
    jdbc.update(
        deleteTriggerRequestSql,
        tenant);
    jdbc.update(deleteWorkflowNodeRunSql, tenant);
    jdbc.update(deleteWorkflowRunSql, tenant);
    jdbc.update(deleteTriggerRequestSql2, tenant);
    jdbc.update(deleteJobDependencySql, tenant);
    jdbc.update(deleteWorkflowEdgeSql, tenant);
    jdbc.update(deleteWorkflowNodeSql, tenant);
    jdbc.update(deleteWorkflowDefinitionSql, tenant);
    jdbc.update(deleteJobDefinitionSql, tenant);
    jdbc.update(deleteBatchDayInstanceSql, tenant);
    jdbc.update(deleteBatchDayCatchupControlSql, tenant);
    jdbc.update(deleteBusinessCalendarSql, tenant);
  }

  private void corruptWait() {
    final String updateJobDependencyWaitingLaunchSql = """
        update batch.job_dependency_waiting_launch
        set launch_payload=launch_payload || '{"dryRun":true}'::jsonb where tenant_id=?
        """;

    jdbc.update(updateJobDependencyWaitingLaunchSql, tenant);
  }

  private void assertRejectedRoot() {
    final String selectRequestStatusSql = """
        select request_status from batch.trigger_request where tenant_id=? and request_id=?
    """;
    final String selectNodeStatusSql = """
        select node_status from batch.workflow_node_run where id=?
    """;
    final String selectRunStatusSql = """
        select run_status from batch.workflow_run where tenant_id=? and id=?
    """;
    final String selectCountSql = """
        select count(*) from batch.job_task where tenant_id=?
    """;
    final String selectCountSql2 = """
        select count(*) from batch.job_instance where tenant_id=? and job_code=?
    """;

    String selectedRequestStatus =
        jdbc.queryForObject(selectRequestStatusSql, String.class, tenant, childRequestId);
    assertThat(selectedRequestStatus).isEqualTo("REJECTED");
    String selectedNodeStatus = jdbc.queryForObject(selectNodeStatusSql, String.class, nodeRunId);
    assertThat(selectedNodeStatus).isEqualTo("FAILED");
    String selectedRunStatus = jdbc.queryForObject(selectRunStatusSql, String.class, tenant, runId);
    assertThat(selectedRunStatus).isEqualTo("FAILED");
    assertThat(terminalEvents()).isEqualTo(1);
    Long selectedJobTaskCount = jdbc.queryForObject(selectCountSql, Long.class, tenant);
    assertThat(selectedJobTaskCount).isZero();
    Long selectedJobInstanceCount =
        jdbc.queryForObject(selectCountSql2, Long.class, tenant, downstream.jobCode());
    assertThat(selectedJobInstanceCount).isZero();
  }

  private long terminalEvents() {
    final String selectCountSql = """
        select count(*) from batch.outbox_event where tenant_id=? and aggregate_id=?\s\
        and event_type='WORKFLOW_TERMINAL'
        """;

    return jdbc.queryForObject(selectCountSql, Long.class, tenant, runId);
  }
}
