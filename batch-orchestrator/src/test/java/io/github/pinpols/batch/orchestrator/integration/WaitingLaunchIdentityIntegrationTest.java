package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.dto.LaunchResponse;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowLaunchWaitService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunLaunchService;
import io.github.pinpols.batch.orchestrator.infrastructure.scheduler.WorkflowLaunchWaitReconciler;
import io.github.pinpols.batch.orchestrator.domain.entity.JobDependencyWaitingLaunchEntity;
import io.github.pinpols.batch.orchestrator.domain.param.WorkflowLaunchWaitInsertParam;
import io.github.pinpols.batch.orchestrator.mapper.JobDependencyWaitingLaunchMapper;
import io.github.pinpols.batch.orchestrator.mapper.TriggerRequestMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowLaunchWaitMapper;
import io.github.pinpols.batch.orchestrator.service.JobDependencyWaitingLaunchService;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.sql.Connection;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 从实际 Service/Mapper 验证身份、行锁与事务回滚，测试数据只用于建立原请求。 */
@SpringBootTest(classes = BatchOrchestratorApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("Job 与 Workflow 等待记录绑定原请求的 PostgreSQL 契约")
class WaitingLaunchIdentityIntegrationTest extends AbstractIntegrationTest {

  private static final LocalDate BIZ_DATE = LocalDate.of(2026, 10, 18);

  @Autowired private JdbcTemplate jdbc;
  @Autowired private DataSource dataSource;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private JobDependencyWaitingLaunchService jobService;
  @Autowired private WorkflowLaunchWaitService workflowService;
  @Autowired private JobDependencyWaitingLaunchMapper jobMapper;
  @Autowired private WorkflowLaunchWaitMapper workflowMapper;
  @MockitoSpyBean private TriggerRequestMapper requestMapper;
  @MockitoSpyBean private WorkflowRunLaunchService runLaunchService;
  @Autowired private WorkflowLaunchWaitReconciler reconciler;

  private String tenant;
  private String otherTenant;
  private Long workflowId;
  private Long parentRunId;

  @BeforeEach
  void seedAcceptedRequests() {
    tenant = "wait-identity-" + UUID.randomUUID();
    otherTenant = "wait-other-" + UUID.randomUUID();
    jdbc.update("""
        insert into batch.business_calendar(tenant_id,calendar_code,calendar_name,timezone,enabled)
        values (?,'CAL_WAIT','Wait calendar','UTC',true)
        """, tenant);
    jdbc.update("""
        insert into batch.job_definition(tenant_id,job_code,job_name,job_type,schedule_type,timezone,calendar_code)
        values (?,'DOWNSTREAM','Downstream','GENERAL','MANUAL','UTC','CAL_WAIT'),
               (?,'UPSTREAM','Upstream','GENERAL','MANUAL','UTC','CAL_WAIT')
        """, tenant, tenant);
    jdbc.update("""
        insert into batch.job_dependency(tenant_id,downstream_job_code,upstream_job_code,dependency_order)
        values (?,'DOWNSTREAM','UPSTREAM',0)
        """, tenant);
    workflowId = jdbc.queryForObject("""
        insert into batch.workflow_definition(tenant_id,workflow_code,workflow_name,calendar_code)
        values (?,'WAIT_WORKFLOW','Wait workflow','CAL_WAIT') returning id
        """, Long.class, tenant);
    parentRunId = jdbc.queryForObject("""
        insert into batch.workflow_run(tenant_id,workflow_definition_id,biz_date,run_status)
        values (?, ?, ?, 'RUNNING') returning id
        """, Long.class, tenant, workflowId, BIZ_DATE);
    jdbc.update("""
        insert into batch.trigger_request(tenant_id,request_id,trigger_type,job_code,biz_date,
          parent_workflow_run_id,parent_node_code,dedup_key,request_status,dry_run)
        values (?,'job-request','API','DOWNSTREAM',?,?,'JOB','job-request','ACCEPTED',false)
        """, tenant, BIZ_DATE, parentRunId);
    jdbc.update("""
        insert into batch.trigger_request(tenant_id,request_id,trigger_type,workflow_definition_id,
          biz_date,dedup_key,request_status,dry_run)
        values (?,'workflow-request','API',?,?,'workflow-request','ACCEPTED',false)
        """, tenant, workflowId, BIZ_DATE);
  }

  @AfterEach
  void removeOwnedFixture() {
    for (String scopedTenant : List.of(tenant, otherTenant)) {
      jdbc.update("delete from batch.job_dependency_waiting_launch where tenant_id=?", scopedTenant);
      jdbc.update("delete from batch.workflow_launch_wait where tenant_id=?", scopedTenant);
      jdbc.update("delete from batch.trigger_request where tenant_id=?", scopedTenant);
      jdbc.update("delete from batch.job_dependency where tenant_id=?", scopedTenant);
    }
    jdbc.update("delete from batch.workflow_run where id=?", parentRunId);
    jdbc.update("delete from batch.workflow_definition where id=?", workflowId);
    jdbc.update("delete from batch.job_definition where tenant_id=?", tenant);
    jdbc.update("delete from batch.business_calendar where tenant_id=?", tenant);
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing", "foreign", "job", "date", "dryRun", "terminal",
      "payloadJob", "payloadDate", "payloadTenant", "payloadRequest"})
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
      case "payloadDate" -> payload = request.toBuilder().bizDate(BIZ_DATE.plusDays(1)).build();
      case "payloadTenant" -> payload = request.toBuilder().tenantId(otherTenant).build();
      case "payloadRequest" -> payload = request.toBuilder().requestId("other-request").build();
      default -> throw new IllegalArgumentException(scenario);
    }
    JobDependencyWaitingLaunchEntity candidate = JobDependencyWaitingLaunchEntity.builder()
        .tenantId(rowTenant).requestId(requestId).jobCode(jobCode).bizDate(date)
        .dependencySnapshot("[{\"upstreamJobCode\":\"UPSTREAM\",\"bizDateAlignment\":\"SAME_DAY\"}]")
        .launchPayload(JsonUtils.toJson(payload)).build();
    int inserted = RlsTenantContextHolder.runWithTenant(candidate.tenantId(), () ->
        new TransactionTemplate(transactionManager).execute(status -> {
          jobMapper.setTenantContext(candidate.tenantId());
          return jobMapper.insertIfAbsent(candidate);
        }));

    assertThat(inserted).isZero();
    assertThat(waitCount("job")).isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing", "foreign", "date", "dryRun", "terminal", "payloadDefinition",
      "payloadDate", "payloadTenant", "payloadRequest", "payloadJob"})
  @DisplayName("Workflow 等待 Mapper 拒绝日期、演练属性和持久载荷身份漂移")
  void shouldRejectInvalidWorkflowWaitAtMapperBoundary(String scenario) {
    LaunchRequest request = workflowRequest();
    LaunchRequest payload = request;
    switch (scenario) {
      case "missing" -> request = request.toBuilder().requestId("missing-request").build();
      case "foreign" -> request = request.toBuilder().tenantId(otherTenant).build();
      case "date" -> request = request.toBuilder().bizDate(BIZ_DATE.plusDays(1)).build();
      case "dryRun" -> request = request.toBuilder().dryRun(true).build();
      case "terminal" -> rejectOriginal(request.requestId());
      case "payloadDefinition" -> payload = request.toBuilder().workflowDefinitionId(workflowId + 1000).build();
      case "payloadDate" -> payload = request.toBuilder().bizDate(BIZ_DATE.plusDays(1)).build();
      case "payloadTenant" -> payload = request.toBuilder().tenantId(otherTenant).build();
      case "payloadRequest" -> payload = request.toBuilder().requestId("other-request").build();
      case "payloadJob" -> payload = request.toBuilder().jobCode("DOWNSTREAM").build();
      default -> throw new IllegalArgumentException(scenario);
    }
    if (List.of("missing", "foreign", "date", "dryRun").contains(scenario)) {
      payload = request;
    }
    WorkflowLaunchWaitInsertParam candidate = new WorkflowLaunchWaitInsertParam(
        request.tenantId(), request.requestId(), request.workflowDefinitionId(), request.bizDate(),
        Boolean.TRUE.equals(request.dryRun()), JsonUtils.toJson(payload));
    int inserted = RlsTenantContextHolder.runWithTenant(candidate.tenantId(), () ->
        new TransactionTemplate(transactionManager).execute(status -> {
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
    LaunchRequest request = "workflow".equals(target) ? workflowRequest() : jobRequest();
    doReturn(0).when(requestMapper).updateAcceptance(
        tenant, request.requestId(), "workflow".equals(target) ? "PROCESSING" : "WAITING", null);

    assertThatThrownBy(() -> defer(target)).isInstanceOf(BizException.class);

    assertThat(waitCount(target)).isZero();
    assertThat(jdbc.queryForObject(
        "select request_status from batch.trigger_request where tenant_id=? and request_id=?",
        String.class, tenant, request.requestId())).isEqualTo("ACCEPTED");
  }

  @ParameterizedTest
  @ValueSource(strings = {"job", "workflow"})
  @DisplayName("重复延期只保留首次载荷和一条等待，原请求进入对应等待状态")
  void shouldPreserveFirstPayloadOnDuplicateDefer(String target) {
    defer(target);
    LaunchRequest original = "workflow".equals(target) ? workflowRequest() : jobRequest();
    LaunchRequest retry = original.toBuilder().traceId("retry-trace")
        .params(Map.of("retryPayload", "must-not-overwrite-first")).build();
    RlsTenantContextHolder.runWithTenant(tenant, () -> {
      if ("workflow".equals(target)) {
        workflowService.defer(retry);
      } else {
        assertThat(jobService.captureOrDefer(retry)).isEmpty();
      }
    });
    assertThat(waitCount(target)).isEqualTo(1);
    String table = waitTable(target);
    LaunchRequest frozen = JsonUtils.fromJson(jdbc.queryForObject(
        "select launch_payload::text from batch." + table + " where tenant_id=?",
        String.class, tenant), LaunchRequest.class);
    assertThat(frozen).isEqualTo(original);
  }

  @Test
  @DisplayName("容量等待拒绝的原请求 CAS 零行时回滚等待终态，保留可恢复状态")
  void shouldRollbackWaitRejectionWhenOriginalTransitionFails() {
    defer("workflow");
    doReturn(0).when(requestMapper)
        .updateAcceptance(tenant, "workflow-request", "REJECTED", null);

    assertThatThrownBy(() -> RlsTenantContextHolder.runWithTenant(tenant, () ->
        workflowService.reject(tenant, "workflow-request")))
        .isInstanceOf(BizException.class);

    assertThat(jdbc.queryForObject(
        "select wait_status from batch.workflow_launch_wait where tenant_id=?", String.class, tenant))
        .isEqualTo("WAITING");
    assertThat(jdbc.queryForObject(
        "select request_status from batch.trigger_request where tenant_id=? and request_id='workflow-request'",
        String.class, tenant)).isEqualTo("PROCESSING");
  }

  @Test
  @DisplayName("同请求号的容量等待若日期已损坏，重投不得静默接受另一槽位的旧记录")
  void shouldRejectCorruptExistingWorkflowWaitWithoutReinterpretingSlot() {
    defer("workflow");
    jdbc.update("update batch.workflow_launch_wait set biz_date=? where tenant_id=?",
        BIZ_DATE.plusDays(1), tenant);

    assertThatThrownBy(() -> defer("workflow")).isInstanceOf(BizException.class);

    assertThat(jdbc.queryForObject(
        "select biz_date from batch.workflow_launch_wait where tenant_id=?", LocalDate.class, tenant))
        .isEqualTo(BIZ_DATE.plusDays(1));
  }

  @ParameterizedTest
  @ValueSource(strings = {"job", "workflow"})
  @DisplayName("原请求终结与等待准入交错时，准入等待行锁后拒绝且没有孤立等待")
  void shouldSerializeParentTerminalTransitionAndWaitAdmission(String target) throws Exception {
    LaunchRequest request = "workflow".equals(target) ? workflowRequest() : jobRequest();
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try (Connection parent = dataSource.getConnection()) {
      parent.setAutoCommit(false);
      JdbcTemplate parentJdbc = new JdbcTemplate(new SingleConnectionDataSource(parent, true));
      Integer parentPid = parentJdbc.queryForObject("select pg_backend_pid()", Integer.class);
      parentJdbc.update("update batch.trigger_request set request_status='REJECTED' where tenant_id=? and request_id=?",
          tenant, request.requestId());
      AtomicInteger childPid = new AtomicInteger();
      CountDownLatch childReady = new CountDownLatch(1);
      Future<?> admission = executor.submit(() ->
          new TransactionTemplate(transactionManager).execute(status -> {
            childPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
            childReady.countDown();
            defer(target);
            return null;
          }));
      try {
        assertThat(childReady.await(10, TimeUnit.SECONDS)).isTrue();
        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
            assertThat(jdbc.queryForObject("select coalesce(? = any(pg_blocking_pids(?)),false)",
                Boolean.class, parentPid, childPid.get())).isTrue());
        parent.commit();
        assertThatThrownBy(() -> admission.get(10, TimeUnit.SECONDS)).hasRootCauseInstanceOf(BizException.class);
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
        workflowService.defer(workflowRequest());
      } else if ("job-wrapper".equals(target)) {
        assertThat(jobService.deferIfNotReady(jobRequest())).isTrue();
      } else {
        assertThat(jobService.captureOrDefer(jobRequest())).isEmpty();
      }
    });
  }

  @ParameterizedTest
  @ValueSource(strings = {"definition", "date", "dryRun", "job"})
  @DisplayName("容量恢复在调用启动服务前比对等待行的目标、日期与演练身份")
  void shouldRejectCorruptCapacityPayloadBeforeLaunch(String field) {
    defer("workflow");
    jdbc.update("update batch.workflow_run set run_status='SUCCESS',finished_at=now() where id=?", parentRunId);
    LaunchRequest original = workflowRequest();
    LaunchRequest corrupted = switch (field) {
      case "definition" -> original.toBuilder().workflowDefinitionId(workflowId + 1000).build();
      case "date" -> original.toBuilder().bizDate(BIZ_DATE.plusDays(1)).build();
      case "dryRun" -> original.toBuilder().dryRun(true).build();
      case "job" -> original.toBuilder().jobCode("DOWNSTREAM").build();
      default -> throw new IllegalArgumentException(field);
    };
    jdbc.update("update batch.workflow_launch_wait set launch_payload=?::jsonb where tenant_id=?",
        JsonUtils.toJson(corrupted), tenant);
    doReturn(LaunchResponse.skipped("fixture")).when(runLaunchService).launch(any());

    reconciler.reconcileTenant(tenant);

    assertThat(jdbc.queryForObject(
        "select wait_status from batch.workflow_launch_wait where tenant_id=?", String.class, tenant))
        .isEqualTo("REJECTED");
    verify(runLaunchService, never()).launch(any());
  }

  private LaunchRequest jobRequest() {
    return LaunchRequest.builder().tenantId(tenant).jobCode("DOWNSTREAM").bizDate(BIZ_DATE)
        .requestId("job-request").triggerType(TriggerType.API).traceId("job-trace").params(Map.of()).build();
  }

  private LaunchRequest workflowRequest() {
    return LaunchRequest.builder().tenantId(tenant).workflowDefinitionId(workflowId).bizDate(BIZ_DATE)
        .requestId("workflow-request").triggerType(TriggerType.API).traceId("workflow-trace").build();
  }

  private void rejectOriginal(String requestId) {
    jdbc.update("update batch.trigger_request set request_status='REJECTED' where tenant_id=? and request_id=?",
        tenant, requestId);
  }

  private long waitCount(String target) {
    return jdbc.queryForObject("select count(*) from batch." + waitTable(target)
        + " where tenant_id in (?,?)", Long.class, tenant, otherTenant);
  }

  private static String waitTable(String target) {
    return "workflow".equals(target) ? "workflow_launch_wait" : "job_dependency_waiting_launch";
  }
}
