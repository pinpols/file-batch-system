package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.dto.LaunchResponse;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.persistence.entity.TriggerRequestEntity;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.service.SecretPayloadProtector;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowLaunchWaitService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowLaunchWaitFixtures;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowLaunchWaitSnapshot;
import io.github.pinpols.batch.orchestrator.domain.query.WorkflowLaunchWaitClaim;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunLaunchService;
import io.github.pinpols.batch.orchestrator.domain.entity.JobDependencyWaitingLaunchEntity;
import io.github.pinpols.batch.orchestrator.domain.param.WorkflowLaunchWaitInsertParam;
import io.github.pinpols.batch.orchestrator.infrastructure.scheduler.JobDependencyWaitingLaunchReconciler;
import io.github.pinpols.batch.orchestrator.infrastructure.scheduler.WorkflowLaunchWaitReconciler;
import io.github.pinpols.batch.orchestrator.mapper.JobDependencyWaitingLaunchMapper;
import io.github.pinpols.batch.orchestrator.mapper.TriggerRequestMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowLaunchWaitMapper;
import io.github.pinpols.batch.orchestrator.service.JobDependencyWaitingLaunchService;
import io.github.pinpols.batch.orchestrator.service.LaunchService;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.sql.Connection;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 从实际 Service/Mapper 验证身份、行锁与事务回滚，测试数据只用于建立原请求。 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("Job 与 Workflow 等待记录绑定原请求的 PostgreSQL 契约")
class WaitingLaunchIdentityIntegrationTest extends AbstractIntegrationTest {

  private static final LocalDate BIZ_DATE = LocalDate.of(2026, 10, 18);

  @Autowired
  private JdbcTemplate jdbc;

  @Autowired
  private DataSource dataSource;

  @Autowired
  private PlatformTransactionManager transactionManager;

  @Autowired
  private JobDependencyWaitingLaunchService jobService;

  @Autowired
  private WorkflowLaunchWaitService workflowService;

  @MockitoSpyBean
  private JobDependencyWaitingLaunchMapper jobMapper;

  @Autowired
  private WorkflowLaunchWaitMapper workflowMapper;

  @Autowired
  private SecretPayloadProtector secretPayloadProtector;

  @MockitoSpyBean
  private TriggerRequestMapper requestMapper;

  @MockitoSpyBean
  private WorkflowRunLaunchService runLaunchService;

  @Autowired
  private WorkflowLaunchWaitReconciler reconciler;

  @Autowired
  private JobDependencyWaitingLaunchReconciler jobReconciler;

  @MockitoSpyBean
  private LaunchService jobLaunchService;

  private String tenant;
  private String otherTenant;
  private Long workflowId;
  private Long parentRunId;

  @BeforeEach
  void seedAcceptedRequests() {
    final String insertBusinessCalendarSql = """
        insert into batch.business_calendar(tenant_id,calendar_code,calendar_name,timezone,enabled)
        values (?,'CAL_WAIT','Wait calendar','UTC',true)
        """;
    final String insertJobDefinitionSql = """
        insert into batch.job_definition(tenant_id,job_code,job_name,job_type,schedule_type,timezone,calendar_code)
        values (?,'DOWNSTREAM','Downstream','GENERAL','MANUAL','UTC','CAL_WAIT'),
               (?,'UPSTREAM','Upstream','GENERAL','MANUAL','UTC','CAL_WAIT')
        """;
    final String insertJobDependencySql = """
        insert into batch.job_dependency(tenant_id,downstream_job_code,upstream_job_code,dependency_order)
        values (?,'DOWNSTREAM','UPSTREAM',0)
        """;
    final String insertWorkflowDefinitionSql = """
        insert into batch.workflow_definition(tenant_id,workflow_code,workflow_name,calendar_code)
        values (?,'WAIT_WORKFLOW','Wait workflow','CAL_WAIT') returning id
        """;
    final String insertWorkflowRunSql = """
        insert into batch.workflow_run(tenant_id,workflow_definition_id,biz_date,run_status)
        values (?, ?, ?, 'RUNNING') returning id
        """;
    final String insertTriggerRequestSql = """
        insert into batch.trigger_request(tenant_id,request_id,trigger_type,job_code,biz_date,
          parent_workflow_run_id,parent_node_code,dedup_key,request_status,dry_run)
        values (?,'job-request','API','DOWNSTREAM',?,?,'JOB','job-request','ACCEPTED',false)
        """;
    final String insertTriggerRequestSql2 = """
        insert into batch.trigger_request(tenant_id,request_id,trigger_type,workflow_definition_id,
          biz_date,dedup_key,request_status,dry_run)
        values (?,'workflow-request','API',?,?,'workflow-request','ACCEPTED',false)
        """;

    tenant = "wait-identity-" + UUID.randomUUID();
    otherTenant = "wait-other-" + UUID.randomUUID();
    jdbc.update(insertBusinessCalendarSql, tenant);
    jdbc.update(insertJobDefinitionSql, tenant, tenant);
    jdbc.update(insertJobDependencySql, tenant);
    workflowId = jdbc.queryForObject(insertWorkflowDefinitionSql, Long.class, tenant);
    parentRunId = jdbc.queryForObject(insertWorkflowRunSql, Long.class, tenant, workflowId, BIZ_DATE);
    jdbc.update(insertTriggerRequestSql, tenant, BIZ_DATE, parentRunId);
    jdbc.update(insertTriggerRequestSql2, tenant, workflowId, BIZ_DATE);
  }

  @AfterEach
  void removeOwnedFixture() {
    final String deleteJobDependencyWaitingLaunchSql = """
        delete from batch.job_dependency_waiting_launch where tenant_id=?
    """;
    final String deleteWorkflowLaunchWaitSql = """
        delete from batch.workflow_launch_wait where tenant_id=?
    """;
    final String deleteJobInstanceSql = """
        delete from batch.job_instance where tenant_id=?
    """;
    final String deleteTriggerRequestSql = """
        delete from batch.trigger_request where tenant_id=?
    """;
    final String deleteJobDependencySql = """
        delete from batch.job_dependency where tenant_id=?
    """;
    final String deleteWorkflowRunSql = """
        delete from batch.workflow_run where id=?
    """;
    final String deleteWorkflowDefinitionSql = """
        delete from batch.workflow_definition where id=?
    """;
    final String deleteJobDefinitionSql = """
        delete from batch.job_definition where tenant_id=?
    """;
    final String deleteBatchDayCatchupControlSql = """
        delete from batch.batch_day_catchup_control where tenant_id=?
    """;
    final String deleteBusinessCalendarSql = """
        delete from batch.business_calendar where tenant_id=?
    """;

    for (String scopedTenant : List.of(tenant, otherTenant)) {
      jdbc.update(
          deleteJobDependencyWaitingLaunchSql, scopedTenant);
      jdbc.update(deleteWorkflowLaunchWaitSql, scopedTenant);
      jdbc.update(deleteJobInstanceSql, scopedTenant);
      jdbc.update(deleteTriggerRequestSql, scopedTenant);
      jdbc.update(deleteJobDependencySql, scopedTenant);
    }
    jdbc.update(deleteWorkflowRunSql, parentRunId);
    jdbc.update(deleteWorkflowDefinitionSql, workflowId);
    jdbc.update(deleteJobDefinitionSql, tenant);
    jdbc.update(deleteBatchDayCatchupControlSql, tenant);
    jdbc.update(deleteBusinessCalendarSql, tenant);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "missing",
        "foreign",
        "job",
        "date",
        "dryRun",
        "terminal",
        "payloadJob",
        "payloadDate",
        "payloadTenant",
        "payloadRequest"
      })
  @DisplayName("Job 等待 Mapper 不能旁路原请求的存在性、目标、日期及载荷身份")
  void shouldRejectInvalidJobWaitAtMapperBoundary(String scenario) {
    LaunchRequest request = jobRequest();
    LaunchRequest payload = request;
    String rowTenant = tenant;
    String requestId = request.requestId();
    String jobCode = request.jobCode();
    LocalDate date = BIZ_DATE;
    switch (scenario) {
      case "missing" -> {
        requestId = "missing-request";
        payload = request.toBuilder().requestId(requestId).build();
      }
      case "foreign" -> {
        rowTenant = otherTenant;
        payload = request.toBuilder().tenantId(rowTenant).build();
      }
      case "job" -> {
        jobCode = "OTHER_JOB";
        payload = request.toBuilder().jobCode(jobCode).build();
      }
      case "date" -> {
        date = BIZ_DATE.plusDays(1);
        payload = request.toBuilder().bizDate(date).build();
      }
      case "dryRun" -> payload = request.toBuilder().dryRun(true).build();
      case "terminal" -> rejectOriginal(request.requestId());
      case "payloadJob" -> payload = request.toBuilder().jobCode("OTHER_JOB").build();
      case "payloadDate" ->
        payload = request.toBuilder().bizDate(BIZ_DATE.plusDays(1)).build();
      case "payloadTenant" ->
        payload = request.toBuilder().tenantId(otherTenant).build();
      case "payloadRequest" ->
        payload = request.toBuilder().requestId("other-request").build();
      default -> throw new IllegalArgumentException(scenario);
    }
    JobDependencyWaitingLaunchEntity candidate = JobDependencyWaitingLaunchEntity.builder()
        .tenantId(rowTenant)
        .requestId(requestId)
        .jobCode(jobCode)
        .bizDate(date)
        .dependencySnapshot(
            "[{\"upstreamJobCode\":\"UPSTREAM\",\"bizDateAlignment\":\"SAME_DAY\"}]")
        .launchPayload(JsonUtils.toJson(payload))
        .build();
    int inserted = RlsTenantContextHolder.runWithTenant(
        candidate.tenantId(),
        () -> new TransactionTemplate(transactionManager).execute(status -> {
          jobMapper.setTenantContext(candidate.tenantId());
          return jobMapper.insertIfAbsent(candidate);
        }));

    assertThat(inserted).isZero();
    assertThat(waitCount("job")).isZero();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "missing",
        "foreign",
        "date",
        "dryRun",
        "terminal",
        "payloadDefinition",
        "payloadDate",
        "payloadTenant",
        "payloadRequest",
        "payloadJob"
      })
  @DisplayName("Workflow 等待 Mapper 拒绝日期、演练属性和持久载荷身份漂移")
  void shouldRejectInvalidWorkflowWaitAtMapperBoundary(String scenario) {
    LaunchRequest request = workflowRequest();
    LaunchRequest payload = request;
    switch (scenario) {
      case "missing" ->
        request = request.toBuilder().requestId("missing-request").build();
      case "foreign" -> request = request.toBuilder().tenantId(otherTenant).build();
      case "date" -> request = request.toBuilder().bizDate(BIZ_DATE.plusDays(1)).build();
      case "dryRun" -> request = request.toBuilder().dryRun(true).build();
      case "terminal" -> rejectOriginal(request.requestId());
      case "payloadDefinition" ->
        payload = request.toBuilder().workflowDefinitionId(workflowId + 1000).build();
      case "payloadDate" ->
        payload = request.toBuilder().bizDate(BIZ_DATE.plusDays(1)).build();
      case "payloadTenant" ->
        payload = request.toBuilder().tenantId(otherTenant).build();
      case "payloadRequest" ->
        payload = request.toBuilder().requestId("other-request").build();
      case "payloadJob" -> payload = request.toBuilder().jobCode("DOWNSTREAM").build();
      default -> throw new IllegalArgumentException(scenario);
    }
    if (List.of("missing", "foreign", "date", "dryRun").contains(scenario)) {
      payload = request;
    }
    WorkflowLaunchWaitInsertParam candidate = new WorkflowLaunchWaitInsertParam(
        request.tenantId(),
        request.requestId(),
        request.workflowDefinitionId(),
        request.bizDate(),
        Boolean.TRUE.equals(request.dryRun()),
        protectedWorkflowPayload(payload));
    int inserted = RlsTenantContextHolder.runWithTenant(
        candidate.tenantId(),
        () -> new TransactionTemplate(transactionManager).execute(status -> {
          workflowMapper.setTenantContext(candidate.tenantId());
          return workflowMapper.defer(candidate);
        }));

    assertThat(inserted).isZero();
    assertThat(waitCount("workflow")).isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {"job", "job-wrapper", "workflow"})
  @DisplayName("原请求状态更新为零行时，Service 事务回滚此前等待插入与检查记录")
  void shouldRollbackWaitWhenRequestTransitionIsRejected(String target) {
    final String selectRequestStatusSql = """
        select request_status from batch.trigger_request where tenant_id=? and request_id=?
    """;

    LaunchRequest request = "workflow".equals(target) ? workflowRequest() : jobRequest();
    doReturn(0)
        .when(requestMapper)
        .updateAcceptance(
            tenant,
            request.requestId(),
            "workflow".equals(target) ? "PROCESSING" : "WAITING",
            null);

    assertThatThrownBy(() -> defer(target)).isInstanceOf(BizException.class);

    assertThat(waitCount(target)).isZero();
    String selectedRequestStatus =
        jdbc.queryForObject(selectRequestStatusSql, String.class, tenant, request.requestId());
    assertThat(selectedRequestStatus).isEqualTo("ACCEPTED");
  }

  @ParameterizedTest
  @ValueSource(strings = {"job", "workflow"})
  @DisplayName("重复延期只保留首次载荷和一条等待，原请求进入对应等待状态")
  void shouldPreserveFirstPayloadOnDuplicateDefer(String target) {
    defer(target);
    LaunchRequest original = "workflow".equals(target) ? workflowRequest() : jobRequest();
    LaunchRequest retry = original.toBuilder()
        .traceId("retry-trace")
        .params(Map.of("retryPayload", "must-not-overwrite-first"))
        .build();
    RlsTenantContextHolder.runWithTenant(tenant, () -> {
      if ("workflow".equals(target)) {
        WorkflowLaunchWaitSnapshot retrySnapshot = WorkflowLaunchWaitFixtures.snapshot(retry);
        assertThatThrownBy(() -> workflowService.defer(retry, retrySnapshot))
            .isInstanceOf(BizException.class);
      } else {
        assertThat(jobService.captureOrDefer(retry)).isEmpty();
      }
    });
    assertThat(waitCount(target)).isEqualTo(1);
    String table = waitTable(target);
    final String selectLaunchPayloadSql = """
        select launch_payload::text from batch.%s where tenant_id=?\
        """.formatted(table);
    String storedPayload = jdbc.queryForObject(selectLaunchPayloadSql, String.class, tenant);
    LaunchRequest frozen;
    if ("workflow".equals(target)) {
      WorkflowLaunchWaitClaim claim = new WorkflowLaunchWaitClaim(
          tenant, original.requestId(), workflowId, BIZ_DATE, false, storedPayload);
      frozen = workflowService.restoreRequest(tenant, claim);
    } else {
      frozen = JsonUtils.fromJson(storedPayload, LaunchRequest.class);
    }
    assertThat(frozen).isEqualTo(original);
  }

  @Test
  @DisplayName("容量等待拒绝的原请求 CAS 零行时回滚等待终态，保留可恢复状态")
  void shouldRollbackWaitRejectionWhenOriginalTransitionFails() {
    final String selectWaitStatusSql = """
        select wait_status from batch.workflow_launch_wait where tenant_id=?
    """;
    final String selectRequestStatusSql = """
        select request_status from batch.trigger_request where tenant_id=? and request_id='workflow-request'
    """;

    defer("workflow");
    doReturn(0).when(requestMapper).updateAcceptance(tenant, "workflow-request", "REJECTED", null);

    assertThatThrownBy(() -> RlsTenantContextHolder.runWithTenant(
            tenant, () -> workflowService.reject(tenant, "workflow-request")))
        .isInstanceOf(BizException.class);

    String selectedWaitStatus = jdbc.queryForObject(selectWaitStatusSql, String.class, tenant);
    assertThat(selectedWaitStatus).isEqualTo("WAITING");
    String selectedRequestStatus =
        jdbc.queryForObject(selectRequestStatusSql, String.class, tenant);
    assertThat(selectedRequestStatus).isEqualTo("PROCESSING");
  }

  @Test
  @DisplayName("同请求号的容量等待若日期已损坏，重投不得静默接受另一槽位的旧记录")
  void shouldRejectCorruptExistingWorkflowWaitWithoutReinterpretingSlot() {
    final String updateWorkflowLaunchWaitSql = """
        update batch.workflow_launch_wait set biz_date=? where tenant_id=?
    """;
    final String selectBizDateSql = """
        select biz_date from batch.workflow_launch_wait where tenant_id=?
    """;

    defer("workflow");
    jdbc.update(
        updateWorkflowLaunchWaitSql,
        BIZ_DATE.plusDays(1),
        tenant);

    assertThatThrownBy(() -> defer("workflow")).isInstanceOf(BizException.class);

    LocalDate selectedBizDate = jdbc.queryForObject(selectBizDateSql, LocalDate.class, tenant);
    assertThat(selectedBizDate).isEqualTo(BIZ_DATE.plusDays(1));
  }

  @ParameterizedTest
  @ValueSource(strings = {"job", "workflow"})
  @DisplayName("原请求终结与等待准入交错时，准入等待行锁后拒绝且没有孤立等待")
  void shouldSerializeParentTerminalTransitionAndWaitAdmission(String target) throws Exception {
    final String selectPgBackendPidSql = """
        select pg_backend_pid()
    """;
    final String updateTriggerRequestSql = """
        update batch.trigger_request set request_status='REJECTED' where tenant_id=? and request_id=?
    """;
    final String selectCoalesceSql = """
        select coalesce(? = any(pg_blocking_pids(?)),false)
    """;

    LaunchRequest request = "workflow".equals(target) ? workflowRequest() : jobRequest();
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try (Connection parent = dataSource.getConnection()) {
      parent.setAutoCommit(false);
      JdbcTemplate parentJdbc = new JdbcTemplate(new SingleConnectionDataSource(parent, true));
      Integer parentPid = parentJdbc.queryForObject(selectPgBackendPidSql, Integer.class);
      parentJdbc.update(
          updateTriggerRequestSql,
          tenant,
          request.requestId());
      AtomicInteger childPid = new AtomicInteger();
      CountDownLatch childReady = new CountDownLatch(1);
      Future<?> admission =
          executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
            childPid.set(jdbc.queryForObject(selectPgBackendPidSql, Integer.class));
            childReady.countDown();
            defer(target);
            return null;
          }));
      try {
        assertThat(childReady.await(10, TimeUnit.SECONDS)).isTrue();
        Awaitility.await()
            .atMost(Duration.ofSeconds(5))
            .untilAsserted(() -> {
              Integer childBackendPid = childPid.get();
              Boolean parentBlockedByChild = jdbc.queryForObject(
                  selectCoalesceSql,
                  Boolean.class,
                  parentPid,
                  childBackendPid);
              assertThat(parentBlockedByChild).isTrue();
            });
        parent.commit();
        assertThatThrownBy(() -> admission.get(10, TimeUnit.SECONDS))
            .hasRootCauseInstanceOf(BizException.class);
      } finally {
        parent.rollback();
        executor.shutdown();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
      }
    } finally {
      executor.shutdownNow();
    }
    assertThat(waitCount(target)).isZero();
  }

  private void defer(String target) {
    RlsTenantContextHolder.runWithTenant(tenant, () -> {
      if ("workflow".equals(target)) {
        LaunchRequest request = workflowRequest();
        WorkflowLaunchWaitSnapshot snapshot = WorkflowLaunchWaitFixtures.snapshot(request);
        workflowService.defer(request, snapshot);
      } else if ("job-wrapper".equals(target)) {
        assertThat(jobService.deferIfNotReady(jobRequest())).isTrue();
      } else {
        assertThat(jobService.captureOrDefer(jobRequest())).isEmpty();
      }
    });
  }

  private String protectedWorkflowPayload(LaunchRequest request) {
    WorkflowLaunchWaitSnapshot snapshot = WorkflowLaunchWaitFixtures.snapshot(request);
    String snapshotJson = JsonUtils.toJson(snapshot);
    String sealedSnapshot = secretPayloadProtector.protect(snapshotJson);
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("tenantId", request.tenantId());
    envelope.put("requestId", request.requestId());
    envelope.put("workflowDefinitionId", request.workflowDefinitionId());
    envelope.put("bizDate", request.bizDate());
    envelope.put("dryRun", Boolean.TRUE.equals(request.dryRun()));
    envelope.put("jobCode", request.jobCode());
    envelope.put("format", "WORKFLOW_CAPACITY_WAIT_V1");
    envelope.put("sealedSnapshotPayload", sealedSnapshot);
    return JsonUtils.toJson(envelope);
  }

  @ParameterizedTest
  @ValueSource(strings = {"definition", "date", "dryRun", "job"})
  @DisplayName("容量恢复在调用启动服务前比对等待行的目标、日期与演练身份")
  void shouldRejectCorruptCapacityPayloadBeforeLaunch(String field) {
    final String updateWorkflowRunSql = """
        update batch.workflow_run set run_status='SUCCESS',finished_at=now() where id=?
    """;
    final String updateWorkflowLaunchWaitSql = """
        update batch.workflow_launch_wait set launch_payload=?::jsonb where tenant_id=?
    """;
    final String selectWaitStatusSql = """
        select wait_status from batch.workflow_launch_wait where tenant_id=?
    """;

    defer("workflow");
    jdbc.update(
        updateWorkflowRunSql,
        parentRunId);
    LaunchRequest original = workflowRequest();
    LaunchRequest corrupted =
        switch (field) {
          case "definition" ->
            original.toBuilder().workflowDefinitionId(workflowId + 1000).build();
          case "date" -> original.toBuilder().bizDate(BIZ_DATE.plusDays(1)).build();
          case "dryRun" -> original.toBuilder().dryRun(true).build();
          case "job" -> original.toBuilder().jobCode("DOWNSTREAM").build();
          default -> throw new IllegalArgumentException(field);
        };
    jdbc.update(
        updateWorkflowLaunchWaitSql,
        protectedWorkflowPayload(corrupted),
        tenant);
    doReturn(LaunchResponse.skipped("fixture")).when(runLaunchService).launch(any());

    reconciler.reconcileTenant(tenant);

    String selectedWaitStatus = jdbc.queryForObject(selectWaitStatusSql, String.class, tenant);
    assertThat(selectedWaitStatus).isEqualTo("REJECTED");
    verify(runLaunchService, never()).launch(any());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "job",
        "date",
        "dryRun",
        "workflow",
        "tenant",
        "request",
        "malformed",
        "null",
        "rowJob",
        "rowDate"
      })
  @DisplayName("上游尚未就绪时也先拒绝损坏的Job等待身份，原请求与等待同时终结")
  void shouldRejectCorruptJobWaitBeforeReadiness(String field) {
    final String selectCountSql = """
        select count(*) from batch.job_instance where tenant_id=?
    """;

    defer("job");
    corruptJobWait(field);

    jobReconciler.reconcileTenant(tenant);

    assertJobWaitState("CANCELLED", "REJECTED");
    verify(jobLaunchService, never()).launch(any());
    Long selectedJobInstanceCount = jdbc.queryForObject(selectCountSql, Long.class, tenant);
    assertThat(selectedJobInstanceCount).isZero();
  }

  @Test
  @DisplayName("坏载荷的原请求拒绝写入零行时保留等待及原状态，不能只取消一边")
  void shouldRollbackCorruptJobWaitRejectionWhenOriginalTransitionFails() {
    defer("job");
    corruptJobWait("date");
    doReturn(0).when(requestMapper).updateAcceptance(tenant, "job-request", "REJECTED", null);

    jobReconciler.reconcileTenant(tenant);

    assertJobWaitState("WAITING", "WAITING");
    verify(requestMapper).updateAcceptance(tenant, "job-request", "REJECTED", null);
    verify(jobLaunchService, never()).launch(any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"LAUNCHED", "REJECTED", "DUPLICATE", "GIVE_UP"})
  @DisplayName("等待载荷损坏时保留原请求既有终态，已派发释放等待、其它终态取消等待")
  void shouldPreserveOriginalTerminalStateWhenJobWaitPayloadIsCorrupt(String state) {
    final String updateTriggerRequestSql = """
        update batch.trigger_request set request_status=? where tenant_id=? and request_id='job-request'
    """;

    defer("job");
    corruptJobWait("job");
    jdbc.update(
        updateTriggerRequestSql,
        state,
        tenant);

    jobReconciler.reconcileTenant(tenant);

    assertJobWaitState("LAUNCHED".equals(state) ? "RELEASED" : "CANCELLED", state);
    verify(jobLaunchService, never()).launch(any());
  }

  @Test
  @DisplayName("已提交Job实例但尚未派发时，坏等待不得拒绝原请求或切断CREATED恢复")
  void shouldKeepPreparedJobRecoverableInsteadOfRejectingItsOriginalRequest() {
    final String selectLastNotReadyReasonSql = """
        select last_not_ready_reason from batch.job_dependency_waiting_launch where tenant_id=?
    """;
    final String selectInstanceStatusSql = """
        select instance_status from batch.job_instance where tenant_id=?
    """;

    defer("job");
    corruptJobWait("date");
    insertPreparedJob(jdbc);

    jobReconciler.reconcileTenant(tenant);

    assertJobWaitState("WAITING", "WAITING");
    String selectedLastNotReadyReason =
        jdbc.queryForObject(selectLastNotReadyReasonSql, String.class, tenant);
    assertThat(selectedLastNotReadyReason).isEqualTo("LAUNCH_ALREADY_PREPARED");
    String selectedInstanceStatus =
        jdbc.queryForObject(selectInstanceStatusSql, String.class, tenant);
    assertThat(selectedInstanceStatus).isEqualTo("CREATED");
    verify(jobLaunchService, never()).launch(any());
  }

  @Test
  @DisplayName("Job恢复读取原请求须在已绑定租户的事务中执行，普通RLS角色可见同租户原请求")
  void shouldReadRecoveryOriginalInsideTenantBoundTransaction() {
    final String doStatementSql = """
        DO $$ BEGIN
          IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='job_wait_recovery_rls_test') THEN
            CREATE ROLE job_wait_recovery_rls_test NOSUPERUSER NOBYPASSRLS;
          END IF;
        END $$
        """;
    final String grantUsageSql = """
        GRANT USAGE ON SCHEMA batch TO job_wait_recovery_rls_test
    """;
    final String grantSelectSql = """
        GRANT SELECT ON batch.trigger_request TO job_wait_recovery_rls_test
    """;
    final String selectCurrentSettingSql = """
        select current_setting('app.tenant_id',true)
    """;
    final String setLocalSql = """
        SET LOCAL ROLE job_wait_recovery_rls_test
    """;

    defer("job");
    jdbc.execute(doStatementSql);
    jdbc.execute(grantUsageSql);
    jdbc.execute(grantSelectSql);
    Mockito.doAnswer(invocation -> {
          assertThat(TransactionSynchronizationManager.isActualTransactionActive())
              .isTrue();
          String selectedCurrentSetting = jdbc.queryForObject(selectCurrentSettingSql, String.class);
          assertThat(selectedCurrentSetting).isEqualTo(tenant);
          jdbc.execute(setLocalSql);
          Object original = invocation.callRealMethod();
          assertThat(original).isInstanceOf(TriggerRequestEntity.class);
          return original;
        })
        .when(requestMapper)
        .selectByTenantAndRequestId(tenant, "job-request");

    jobReconciler.reconcileTenant(tenant);

    assertJobWaitState("WAITING", "WAITING");
    verify(requestMapper).selectByTenantAndRequestId(tenant, "job-request");
    verify(jobLaunchService, never()).launch(any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"payload", "original"})
  @DisplayName("扫描后载荷或原请求已修复时，过期拒绝决定不得终结合法等待")
  void shouldIgnoreStaleJobWaitRejectionAfterIdentityRepair(String repaired) {
    final String updateTriggerRequestSql = """
        update batch.trigger_request set dry_run=true where tenant_id=? and request_id='job-request'
    """;
    final String updateJobDependencyWaitingLaunchSql = """
        update batch.job_dependency_waiting_launch set launch_payload=?::jsonb where tenant_id=?
    """;
    final String updateTriggerRequestSql2 = """
        update batch.trigger_request set dry_run=false where tenant_id=? and request_id='job-request'
    """;

    defer("job");
    if ("payload".equals(repaired)) {
      corruptJobWait("date");
    } else {
      jdbc.update(
          updateTriggerRequestSql,
          tenant);
    }
    JobDependencyWaitingLaunchEntity observed = RlsTenantContextHolder.runWithTenant(
        tenant, () -> jobService.selectWaiting(tenant, 10).getFirst());
    if ("payload".equals(repaired)) {
      jdbc.update(
          updateJobDependencyWaitingLaunchSql,
          JsonUtils.toJson(jobRequest()),
          tenant);
    } else {
      jdbc.update(
          updateTriggerRequestSql2,
          tenant);
    }

    RlsTenantContextHolder.runWithTenant(tenant, () -> jobService.rejectInvalidWait(observed));

    assertJobWaitState("WAITING", "WAITING");
  }

  @Test
  @DisplayName("取消等待CAS零行时回滚检查记录和原请求，不能产生部分终结")
  void shouldRollbackInvalidJobWaitWhenWaitTransitionFails() {
    final String selectLastNotReadyReasonSql = """
        select last_not_ready_reason from batch.job_dependency_waiting_launch where tenant_id=?
    """;

    defer("job");
    corruptJobWait("null");
    doReturn(0).when(jobMapper).markCancelled(tenant, "job-request");

    jobReconciler.reconcileTenant(tenant);

    assertJobWaitState("WAITING", "WAITING");
    String selectedLastNotReadyReason =
        jdbc.queryForObject(selectLastNotReadyReasonSql, String.class, tenant);
    assertThat(selectedLastNotReadyReason).isEqualTo("UPSTREAM_NOT_EFFECTIVE:UPSTREAM");
    verify(jobMapper).markCancelled(tenant, "job-request");
    verify(jobLaunchService, never()).launch(any());
  }

  @Test
  @DisplayName("一个坏等待的状态转换失败不阻断同租户下一条等待的独立拒绝事务")
  void shouldContinueOtherJobWaitsAfterOneRejectionTransactionFails() {
    final String insertTriggerRequestSql = """
        insert into batch.trigger_request(tenant_id,request_id,trigger_type,job_code,biz_date,
          parent_workflow_run_id,parent_node_code,dedup_key,request_status,dry_run)
        values (?,'second-job-request','API','DOWNSTREAM',?,?,'JOB','second-job-request','ACCEPTED',false)
        """;
    final String selectWaitStatusSql = """
        select wait_status from batch.job_dependency_waiting_launch
        where tenant_id=? and request_id='job-request'
        """;
    final String selectWaitStatusSql2 = """
        select wait_status from batch.job_dependency_waiting_launch
        where tenant_id=? and request_id='second-job-request'
        """;
    final String selectRequestStatusSql = """
        select request_status from batch.trigger_request
        where tenant_id=? and request_id='second-job-request'
        """;

    defer("job");
    jdbc.update(insertTriggerRequestSql, tenant, BIZ_DATE, parentRunId);
    LaunchRequest second =
        jobRequest().toBuilder().requestId("second-job-request").build();
    RlsTenantContextHolder.runWithTenant(tenant, () -> jobService.captureOrDefer(second));
    corruptJobWait("date");
    doReturn(0).when(requestMapper).updateAcceptance(tenant, "job-request", "REJECTED", null);

    jobReconciler.reconcileTenant(tenant);

    String selectedWaitStatus = jdbc.queryForObject(selectWaitStatusSql, String.class, tenant);
    assertThat(selectedWaitStatus).isEqualTo("WAITING");
    String selectedWaitStatusRead2 =
        jdbc.queryForObject(selectWaitStatusSql2, String.class, tenant);
    assertThat(selectedWaitStatusRead2).isEqualTo("CANCELLED");
    String selectedRequestStatus =
        jdbc.queryForObject(selectRequestStatusSql, String.class, tenant);
    assertThat(selectedRequestStatus).isEqualTo("REJECTED");
    verify(jobLaunchService, never()).launch(any());
  }

  @Test
  @DisplayName("实例准备先持原请求锁提交时，取消者真实等待该锁并保留已准备实例的恢复状态")
  void shouldWaitForPreparedJobCommitBeforeRejectingInvalidWait() throws Exception {
    final String selectPgBackendPidSql = """
        select pg_backend_pid()
    """;
    final String selectIdSql = """
          select id from batch.trigger_request where tenant_id=? and request_id='job-request' for update
          """;
    final String selectCoalesceSql = """
        select coalesce(?=any(pg_blocking_pids(?)),false)
    """;
    final String selectLastNotReadyReasonSql = """
        select last_not_ready_reason from batch.job_dependency_waiting_launch where tenant_id=?
    """;

    defer("job");
    corruptJobWait("date");
    JobDependencyWaitingLaunchEntity observed = RlsTenantContextHolder.runWithTenant(
        tenant, () -> jobService.selectWaiting(tenant, 10).getFirst());
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try (Connection prepared = dataSource.getConnection()) {
      prepared.setAutoCommit(false);
      JdbcTemplate preparedJdbc = new JdbcTemplate(new SingleConnectionDataSource(prepared, true));
      Integer parentPid = preparedJdbc.queryForObject(selectPgBackendPidSql, Integer.class);
      preparedJdbc.queryForObject(selectIdSql, Long.class, tenant);
      insertPreparedJob(preparedJdbc);
      AtomicInteger rejectionPid = new AtomicInteger();
      CountDownLatch rejectionStarted = new CountDownLatch(1);
      Future<?> rejection = executor.submit(() -> RlsTenantContextHolder.runWithTenant(
          tenant,
          () -> new TransactionTemplate(transactionManager).execute(tx -> {
            rejectionPid.set(jdbc.queryForObject(selectPgBackendPidSql, Integer.class));
            rejectionStarted.countDown();
            jobService.rejectInvalidWait(observed);
            return null;
          })));
      try {
        assertThat(rejectionStarted.await(10, TimeUnit.SECONDS)).isTrue();
        Awaitility.await()
            .atMost(Duration.ofSeconds(5))
            .untilAsserted(() -> {
              Integer rejectionBackendPid = rejectionPid.get();
              Boolean parentBlockedByRejection = jdbc.queryForObject(
                  selectCoalesceSql,
                  Boolean.class,
                  parentPid,
                  rejectionBackendPid);
              assertThat(parentBlockedByRejection).isTrue();
            });
        prepared.commit();
        rejection.get(10, TimeUnit.SECONDS);
      } finally {
        prepared.rollback();
      }
    } finally {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
    assertJobWaitState("WAITING", "WAITING");
    String selectedLastNotReadyReason =
        jdbc.queryForObject(selectLastNotReadyReasonSql, String.class, tenant);
    assertThat(selectedLastNotReadyReason).isEqualTo("LAUNCH_ALREADY_PREPARED");
  }

  private void insertPreparedJob(JdbcTemplate target) {
    final String insertJobInstanceSql = """
        insert into batch.job_instance(tenant_id,job_definition_id,trigger_request_id,job_code,
          instance_no,biz_date,trigger_type,instance_status,worker_group,dedup_key,run_attempt)
        select ?,job.id,request.id,job.job_code,?,?,'EVENT','CREATED','wait-fixture',?,1
        from batch.job_definition job join batch.trigger_request request
          on request.tenant_id=job.tenant_id and request.job_code=job.job_code
        where job.tenant_id=? and job.job_code='DOWNSTREAM' and request.request_id='job-request'
        """;

    target.update(insertJobInstanceSql, tenant, "prepared-" + tenant, BIZ_DATE, "prepared-" + tenant, tenant);
  }

  @Test
  @DisplayName("普通非绕过RLS角色在租户事务内完成坏等待与原请求原子拒绝")
  void shouldRejectInvalidJobWaitUnderNonBypassRlsRole() {
    final String doStatementSql = """
        DO $$ BEGIN
          IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='job_wait_reject_rls_test') THEN
            CREATE ROLE job_wait_reject_rls_test NOSUPERUSER NOBYPASSRLS;
          END IF;
        END $$
        """;
    final String grantUsageSql = """
        GRANT USAGE ON SCHEMA batch TO job_wait_reject_rls_test
    """;
    final String grantSelectSql = """
        GRANT SELECT ON ALL TABLES IN SCHEMA batch TO job_wait_reject_rls_test
    """;
    final String grantUpdateSql = """
        GRANT UPDATE ON batch.trigger_request,batch.job_dependency_waiting_launch TO job_wait_reject_rls_test
    """;
    final String setLocalSql = """
        SET LOCAL ROLE job_wait_reject_rls_test
    """;
    final String selectNotSql = """
          select not rolsuper and not rolbypassrls from pg_roles where rolname=current_user
          """;
    final String selectLastNotReadyReasonSql = """
        select last_not_ready_reason from batch.job_dependency_waiting_launch where tenant_id=?
    """;

    defer("job");
    corruptJobWait("job");
    JobDependencyWaitingLaunchEntity observed = RlsTenantContextHolder.runWithTenant(
        tenant, () -> jobService.selectWaiting(tenant, 10).getFirst());
    jdbc.execute(doStatementSql);
    jdbc.execute(grantUsageSql);
    jdbc.execute(grantSelectSql);
    jdbc.execute(
        grantUpdateSql);

    new TransactionTemplate(transactionManager).execute(tx -> {
      jdbc.execute(setLocalSql);
      Boolean currentRoleHasNoRlsBypassPrivilege = jdbc.queryForObject(selectNotSql, Boolean.class);
      assertThat(currentRoleHasNoRlsBypassPrivilege).isTrue();
      RlsTenantContextHolder.runWithTenant(tenant, () -> jobService.rejectInvalidWait(observed));
      return null;
    });

    assertJobWaitState("CANCELLED", "REJECTED");
    String selectedLastNotReadyReason =
        jdbc.queryForObject(selectLastNotReadyReasonSql, String.class, tenant);
    assertThat(selectedLastNotReadyReason).isEqualTo("LAUNCH_PAYLOAD_INVALID");
  }

  private void corruptJobWait(String field) {
    final String updateJobDependencyWaitingLaunchSql = """
        update batch.job_dependency_waiting_launch set job_code='UPSTREAM' where tenant_id=?
    """;
    final String updateJobDependencyWaitingLaunchSql2 = """
        update batch.job_dependency_waiting_launch set biz_date=? where tenant_id=?
    """;
    final String updateJobDependencyWaitingLaunchSql3 = """
        update batch.job_dependency_waiting_launch set launch_payload=?::jsonb where tenant_id=?
    """;

    if ("rowJob".equals(field) || "rowDate".equals(field)) {
      if ("rowJob".equals(field)) {
        jdbc.update(
            updateJobDependencyWaitingLaunchSql,
            tenant);
      } else {
        jdbc.update(
            updateJobDependencyWaitingLaunchSql2,
            BIZ_DATE.plusDays(1),
            tenant);
      }
      return;
    }
    LaunchRequest request = jobRequest();
    String corrupted =
        switch (field) {
          case "job" -> JsonUtils.toJson(request.toBuilder().jobCode("UPSTREAM").build());
          case "date" ->
            JsonUtils.toJson(request.toBuilder().bizDate(BIZ_DATE.plusDays(1)).build());
          case "dryRun" -> JsonUtils.toJson(request.toBuilder().dryRun(true).build());
          case "workflow" ->
            JsonUtils.toJson(
                request.toBuilder().workflowDefinitionId(workflowId).build());
          case "tenant" ->
            JsonUtils.toJson(request.toBuilder().tenantId(otherTenant).build());
          case "request" ->
            JsonUtils.toJson(request.toBuilder().requestId("other-request").build());
          case "malformed" -> "[]";
          case "null" -> "null";
          default -> throw new IllegalArgumentException(field);
        };
    jdbc.update(
        updateJobDependencyWaitingLaunchSql3,
        corrupted,
        tenant);
  }

  private void assertJobWaitState(String waitStatus, String requestStatus) {
    final String selectWaitStatusSql = """
        select wait_status from batch.job_dependency_waiting_launch where tenant_id=?
    """;
    final String selectRequestStatusSql = """
        select request_status from batch.trigger_request where tenant_id=? and request_id='job-request'
    """;

    String selectedWaitStatus = jdbc.queryForObject(selectWaitStatusSql, String.class, tenant);
    assertThat(selectedWaitStatus).isEqualTo(waitStatus);
    String selectedRequestStatus =
        jdbc.queryForObject(selectRequestStatusSql, String.class, tenant);
    assertThat(selectedRequestStatus).isEqualTo(requestStatus);
  }

  private LaunchRequest jobRequest() {
    return LaunchRequest.builder()
        .tenantId(tenant)
        .jobCode("DOWNSTREAM")
        .bizDate(BIZ_DATE)
        .requestId("job-request")
        .triggerType(TriggerType.API)
        .traceId("job-trace")
        .params(Map.of())
        .build();
  }

  private LaunchRequest workflowRequest() {
    return LaunchRequest.builder()
        .tenantId(tenant)
        .workflowDefinitionId(workflowId)
        .bizDate(BIZ_DATE)
        .requestId("workflow-request")
        .triggerType(TriggerType.API)
        .traceId("workflow-trace")
        .build();
  }

  private void rejectOriginal(String requestId) {
    final String updateTriggerRequestSql = """
        update batch.trigger_request set request_status='REJECTED' where tenant_id=? and request_id=?
    """;

    jdbc.update(
        updateTriggerRequestSql,
        tenant,
        requestId);
  }

  private long waitCount(String target) {
    final String selectCountSql = """
        select count(*) from batch.%s where tenant_id in (?,?)\
        """.formatted(waitTable(target));
    return jdbc.queryForObject(
        selectCountSql,
        Long.class,
        tenant,
        otherTenant);
  }

  private static String waitTable(String target) {
    return "workflow".equals(target) ? "workflow_launch_wait" : "job_dependency_waiting_launch";
  }
}
