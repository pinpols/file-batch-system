package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.pinpols.batch.common.dto.DependencyResultReference;
import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.dto.LaunchResponse;
import io.github.pinpols.batch.common.enums.TriggerRequestStatus;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.persistence.entity.TriggerRequestEntity;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunLaunchService;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.infrastructure.scheduler.BatchDaySettleScheduler;
import io.github.pinpols.batch.orchestrator.infrastructure.scheduler.JobDependencyWaitingLaunchReconciler;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture.WorkflowLaunchSeed;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.TriggerRequestMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowRunMapper;
import io.github.pinpols.batch.orchestrator.service.JobDependencyWaitingLaunchService;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.LocalDate;
import java.time.Month;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 集成测试：每种 {@link TriggerType} 生成匹配 {@code trigger_type} 的 {@code job_instance}， 并在存在 Worker
 * 时（调度计划路径）至少派发一条 outbox 行。
 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("按触发类型发起作业:每种触发类型都生成对应实例记录,并至少派发一条导入事件")
class TriggerTypeLaunchIntegrationTest extends AbstractIntegrationTest {

  private static final String TENANT = "t1-trigger-type-launch";

  @Autowired
  private WorkflowRunLaunchService workflowRunLaunchService;

  @Autowired
  private JobInstanceMapper jobInstanceMapper;

  private final TriggerRequestMapper triggerRequestMapper;

  private final WorkflowRunMapper workflowRunMapper;

  private final JobDependencyWaitingLaunchReconciler dependencyWaitingLaunchReconciler;

  private final JobDependencyWaitingLaunchService jobDependencyWaitingLaunchService;

  private final BatchDaySettleScheduler batchDaySettleScheduler;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  private final PlatformTransactionManager transactionManager;

  @Autowired
  TriggerTypeLaunchIntegrationTest(
      TriggerRequestMapper triggerRequestMapper,
      WorkflowRunMapper workflowRunMapper,
      JobDependencyWaitingLaunchReconciler dependencyWaitingLaunchReconciler,
      JobDependencyWaitingLaunchService jobDependencyWaitingLaunchService,
      BatchDaySettleScheduler batchDaySettleScheduler,
      PlatformTransactionManager transactionManager) {
    this.triggerRequestMapper = triggerRequestMapper;
    this.workflowRunMapper = workflowRunMapper;
    this.dependencyWaitingLaunchReconciler = dependencyWaitingLaunchReconciler;
    this.jobDependencyWaitingLaunchService = jobDependencyWaitingLaunchService;
    this.batchDaySettleScheduler = batchDaySettleScheduler;
    this.transactionManager = transactionManager;
  }

  @Test
  @DisplayName("TriggerRequest 插入拒绝其他租户的 WorkflowDefinition 与父 WorkflowRun 引用")
  void shouldRejectCrossTenantWorkflowReferencesBeforeTriggerRequestInsert() {
    final String selectCountSql = """
        select count(*) from batch.trigger_request where tenant_id = ? and request_id in (?, ?)
    """;

    String tenantA = "r20-trigger-write-a-" + UUID.randomUUID();
    String tenantB = "r20-trigger-write-b-" + UUID.randomUUID();
    LocalDate bizDate = LocalDate.of(2026, 12, 28);
    WorkflowLaunchSeed workflowA = LaunchIntegrationFixture.prepareWorkflowLaunchWithoutWorker(
        jdbcTemplate, tenantA, "DISPATCH", "R20_TRIGGER_WRITE_A", TriggerType.API, bizDate);
    WorkflowLaunchSeed workflowB = LaunchIntegrationFixture.prepareWorkflowLaunchWithoutWorker(
        jdbcTemplate, tenantB, "DISPATCH", "R20_TRIGGER_WRITE_B", TriggerType.API, bizDate);
    LaunchResponse responseB = workflowRunLaunchService.launch(
        launchRequest(tenantB, workflowB, bizDate, TriggerType.API));
    Long foreignWorkflowRunId = Long.valueOf(responseB.instanceNo());

    TriggerRequestEntity foreignDefinition = triggerRequest(
        tenantA,
        "r20-cross-definition-" + UUID.randomUUID(),
        workflowB.workflowDefinitionId(),
        null);
    TriggerRequestEntity foreignParent = triggerRequest(
        tenantA, "r20-cross-parent-" + UUID.randomUUID(), null, foreignWorkflowRunId);

    assertThat(triggerRequestMapper.insert(foreignDefinition)).isZero();
    assertThat(triggerRequestMapper.insertIfAbsent(foreignParent)).isZero();
    Integer selectedTriggerRequestCount = jdbcTemplate.queryForObject(
        selectCountSql,
        Integer.class,
        tenantA,
        foreignDefinition.getRequestId(),
        foreignParent.getRequestId());
    assertThat(selectedTriggerRequestCount).isZero();
    assertThat(workflowA.workflowDefinitionId()).isNotEqualTo(workflowB.workflowDefinitionId());
  }

  @Test
  @DisplayName("WorkflowRun 数据库约束拒绝其他租户的 WorkflowDefinition")
  void shouldRejectCrossTenantWorkflowDefinitionOnWorkflowRunInsert() {
    final String selectCountSql = """
        select count(*) from batch.workflow_run where tenant_id = ? and workflow_definition_id = ?
    """;

    String tenantA = "r20-workflow-run-tenant-a-" + UUID.randomUUID();
    String tenantB = "r20-workflow-run-tenant-b-" + UUID.randomUUID();
    LocalDate bizDate = LocalDate.of(2026, 12, 29);
    WorkflowLaunchSeed workflowA = LaunchIntegrationFixture.prepareWorkflowLaunchWithoutWorker(
        jdbcTemplate, tenantA, "DISPATCH", "R20_WORKFLOW_RUN_A", TriggerType.API, bizDate);
    WorkflowLaunchSeed workflowB = LaunchIntegrationFixture.prepareWorkflowLaunchWithoutWorker(
        jdbcTemplate, tenantB, "DISPATCH", "R20_WORKFLOW_RUN_B", TriggerType.API, bizDate);

    WorkflowRunEntity validRun = workflowRun(tenantA, workflowA.workflowDefinitionId(), bizDate);
    workflowRunMapper.insert(validRun);
    assertThat(validRun.getId()).isNotNull();

    WorkflowRunEntity foreignRun = workflowRun(tenantA, workflowB.workflowDefinitionId(), bizDate);
    assertThatThrownBy(() -> workflowRunMapper.insert(foreignRun))
        .isInstanceOf(DataIntegrityViolationException.class);
    Integer selectedWorkflowRunCount = jdbcTemplate.queryForObject(
        selectCountSql, Integer.class, tenantA, workflowB.workflowDefinitionId());
    assertThat(selectedWorkflowRunCount).isZero();
  }

  @Test
  @DisplayName("Workflow 根参数不能让新 JobInstance 指向其他租户的 rerun 父实例")
  void shouldRejectCrossTenantJobInstanceParentReferenceBeforeInsert() {
    final String selectCountSql = """
      select count(*) from batch.job_instance where tenant_id = ? and job_code = ?
  """;
    final String selectCountSql2 = """
      select count(*) from batch.job_instance where tenant_id = ? and parent_instance_id = ?
  """;
    final String selectCountSql3 = """
      select count(*) from batch.workflow_run where tenant_id = ? and trigger_request_id = (select id\s\
      from batch.trigger_request where tenant_id = ? and request_id = ?)
  """;
    final String selectRequestStatusSql = """
        select request_status from batch.trigger_request where tenant_id = ? and request_id = ?
    """;
    final String selectTenantIdSql = """
        select tenant_id from batch.job_instance where id = ?
    """;

    String tenantA = "r20-job-parent-tenant-a-" + UUID.randomUUID();
    String tenantB = "r20-job-parent-tenant-b-" + UUID.randomUUID();
    LocalDate bizDate = LocalDate.of(2026, 12, 30);
    WorkflowLaunchSeed workflowA = LaunchIntegrationFixture.prepareWorkflowLaunchWithoutWorker(
        jdbcTemplate, tenantA, "DISPATCH", "R20_JOB_PARENT_A", TriggerType.API, bizDate);
    WorkflowLaunchSeed workflowB = LaunchIntegrationFixture.prepareWorkflowLaunchWithoutWorker(
        jdbcTemplate, tenantB, "DISPATCH", "R20_JOB_PARENT_B", TriggerType.API, bizDate);
    LaunchResponse responseB = workflowRunLaunchService.launch(
        launchRequest(tenantB, workflowB, bizDate, TriggerType.API));
    Long foreignParentId = LaunchIntegrationFixture.selectDispatchedJobInstanceId(
        jdbcTemplate, tenantB, responseB.instanceNo());

    LaunchRequest forgedRequest =
        launchRequest(tenantA, workflowA, bizDate, TriggerType.API).toBuilder()
            .params(Map.of("parentInstanceId", foreignParentId))
            .build();

    assertThatThrownBy(() -> workflowRunLaunchService.launch(forgedRequest))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("error.job.instance_not_found");
    Integer selectedJobInstanceCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, tenantA, workflowA.jobCode());
    assertThat(selectedJobInstanceCount).isZero();
    Integer selectedJobInstanceCountRead2 =
        jdbcTemplate.queryForObject(selectCountSql2, Integer.class, tenantA, foreignParentId);
    assertThat(selectedJobInstanceCountRead2).isZero();
    Integer selectedJobInstanceCountRead3 =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, tenantA, workflowA.jobCode());
    assertThat(selectedJobInstanceCountRead3).isZero();
    Integer selectedWorkflowRunCount = jdbcTemplate.queryForObject(
        selectCountSql3, Integer.class, tenantA, tenantA, workflowA.requestId());
    assertThat(selectedWorkflowRunCount).isZero();
    String selectedRequestStatus = jdbcTemplate.queryForObject(
        selectRequestStatusSql, String.class, tenantA, workflowA.requestId());
    assertThat(selectedRequestStatus).isEqualTo(TriggerRequestStatus.REJECTED.code());
    String selectedTenantId =
        jdbcTemplate.queryForObject(selectTenantIdSql, String.class, foreignParentId);
    assertThat(selectedTenantId).isEqualTo(tenantB);
  }

  @Test
  @DisplayName("WorkflowNodeRun 数据库边界拒绝关联其他租户的 JobInstance")
  void shouldRejectCrossTenantJobInstanceOnWorkflowNodeRunInsert() {
    final String selectJobInstanceIdSql = """
        select job_instance_id from batch.workflow_node_run where workflow_run_id = ? and node_type = 'JOB'\s\
        and job_instance_id is not null\
        """;
    final String selectIdSql = """
        select id from batch.job_definition where tenant_id = ? and job_code = ?
    """;
    final String insertJobInstanceSql = """
        insert into batch.job_instance (tenant_id, job_definition_id, job_code, instance_no, biz_date,\s\
        trigger_type, instance_status, worker_group, dedup_key, run_attempt)\s\
        values (?, ?, ?, ?, ?, 'MANUAL', 'SUCCESS', 'R20_NODE_RUN_B', ?, 1) returning id\
        """;
    final String selectTenantIdSql = """
        select tenant_id from batch.job_instance where id = ?
    """;
    final String insertWorkflowNodeRunSql = """
        insert into batch.workflow_node_run (workflow_run_id, job_instance_id, node_code, node_type, run_seq,\s\
        node_status, retry_count) values (?, ?, 'R20_TENANT_GUARD', 'JOB', 1, 'READY', 0)\
        """;
    final String selectCountSql = """
        select count(*) from batch.workflow_node_run where workflow_run_id = ?\s\
        and node_code = 'R20_TENANT_GUARD'\
        """;

    String tenantA = "r20-node-run-tenant-a-" + UUID.randomUUID();
    String tenantB = "r20-node-run-tenant-b-" + UUID.randomUUID();
    LocalDate bizDate = LocalDate.of(2026, 12, 30);
    WorkflowLaunchSeed workflowA = LaunchIntegrationFixture.prepareWorkflowLaunchWithoutWorker(
        jdbcTemplate, tenantA, "DISPATCH", "R20_NODE_RUN_A", TriggerType.API, bizDate);
    WorkflowLaunchSeed workflowB = LaunchIntegrationFixture.prepareWorkflowLaunchWithoutWorker(
        jdbcTemplate, tenantB, "DISPATCH", "R20_NODE_RUN_B", TriggerType.API, bizDate);
    LaunchResponse responseA = workflowRunLaunchService.launch(
        launchRequest(tenantA, workflowA, bizDate, TriggerType.API));
    LaunchResponse responseB = workflowRunLaunchService.launch(
        launchRequest(tenantB, workflowB, bizDate, TriggerType.API));
    Long workflowRunAId = Long.valueOf(responseA.instanceNo());
    Long workflowRunBId = Long.valueOf(responseB.instanceNo());
    Long jobInstanceAId =
        jdbcTemplate.queryForObject(selectJobInstanceIdSql, Long.class, workflowRunAId);
    Long jobDefinitionBId =
        jdbcTemplate.queryForObject(selectIdSql, Long.class, tenantB, workflowB.jobCode());
    Long unlinkedJobInstanceBId = jdbcTemplate.queryForObject(
        insertJobInstanceSql,
        Long.class,
        tenantB,
        jobDefinitionBId,
        workflowB.jobCode(),
        "r20-unlinked-instance-" + UUID.randomUUID(),
        bizDate,
        "r20-unlinked-dedup-" + UUID.randomUUID());
    String selectedTenantId =
        jdbcTemplate.queryForObject(selectTenantIdSql, String.class, jobInstanceAId);
    assertThat(selectedTenantId).isEqualTo(tenantA);
    String selectedTenantIdRead2 =
        jdbcTemplate.queryForObject(selectTenantIdSql, String.class, unlinkedJobInstanceBId);
    assertThat(selectedTenantIdRead2).isEqualTo(tenantB);

    assertThatThrownBy(() ->
            jdbcTemplate.update(insertWorkflowNodeRunSql, workflowRunAId, unlinkedJobInstanceBId))
        .isInstanceOf(DataIntegrityViolationException.class);
    Integer selectedWorkflowNodeRunCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, workflowRunAId);
    assertThat(selectedWorkflowNodeRunCount).isZero();
  }

  @ParameterizedTest
  @EnumSource(TriggerType.class)
  @DisplayName("每种触发类型都保留在Workflow触发请求上,子Job作为Workflow派发事件并至少产生一条导入派发事件")
  void shouldLaunchAndPersistTriggerTypeForEachTriggerType(TriggerType triggerType) {
    final String selectTriggerTypeSql = """
        select trigger_type from batch.trigger_request where tenant_id = ? and request_id = ?
    """;

    LocalDate bizDate = LocalDate.of(2026, 12, 1).plusDays(triggerType.ordinal());
    WorkflowLaunchSeed seed = LaunchIntegrationFixture.prepareWorkflowLaunchWithWorker(
        jdbcTemplate, TENANT, "DISPATCH", "DISPATCH", triggerType, bizDate);

    LaunchRequest launchRequest = launchRequest(seed, bizDate, triggerType);
    LaunchResponse response = workflowRunLaunchService.launch(launchRequest);

    assertThat(response.instanceNo()).isNotBlank();

    String selectedTriggerType =
        jdbcTemplate.queryForObject(selectTriggerTypeSql, String.class, TENANT, seed.requestId());
    assertThat(selectedTriggerType).isEqualTo(triggerType.code());
    Long jobInstanceId = LaunchIntegrationFixture.selectDispatchedJobInstanceId(
        jdbcTemplate, TENANT, response.instanceNo());
    JobInstanceEntity ji = jobInstanceMapper.selectById(TENANT, jobInstanceId);
    assertThat(ji).isNotNull();
    assertThat(ji.getTriggerType()).isEqualTo(TriggerType.EVENT.code());

    assertThat(LaunchIntegrationFixture.countOutboxForJobInstance(jdbcTemplate, TENANT, ji.getId()))
        .isGreaterThanOrEqualTo(1L);
  }

  @Test
  @DisplayName("任一上游未就绪时所有触发类型共用持久化等待并保留原业务日")
  void shouldPersistDependencyWaitAtUnifiedLaunchEntry() {
    final String insertJobDependencySql = """
      insert into batch.job_dependency (tenant_id, downstream_job_code, upstream_job_code, dependency_order)\s\
      values (?, ?, ?, 0), (?, ?, ?, 1)\
      """;
    final String selectRequestStatusSql = """
      select request_status from batch.trigger_request where tenant_id = ? and request_id = ?
  """;
    final String selectCountSql = """
      select count(*) from batch.job_dependency_waiting_launch where tenant_id = ? and request_id = ?\s\
      and biz_date = ? and wait_status = 'WAITING'\
      """;
    final String selectCountSql2 = """
      select count(*) from batch.job_instance where tenant_id = ? and job_code = ?
  """;

    LocalDate bizDate = LocalDate.of(2026, 12, 15);
    WorkflowLaunchSeed downstream = LaunchIntegrationFixture.prepareWorkflowLaunchWithoutWorker(
        jdbcTemplate, TENANT, "DISPATCH", "IT_DEP_WAIT", TriggerType.EVENT, bizDate);
    WorkflowLaunchSeed upstreamOne = LaunchIntegrationFixture.prepareWorkflowLaunchWithoutWorker(
        jdbcTemplate, TENANT, "DISPATCH", "IT_DEP_WAIT", TriggerType.API, bizDate);
    WorkflowLaunchSeed upstreamTwo = LaunchIntegrationFixture.prepareWorkflowLaunchWithoutWorker(
        jdbcTemplate, TENANT, "DISPATCH", "IT_DEP_WAIT", TriggerType.API, bizDate);
    jdbcTemplate.update(
        insertJobDependencySql,
        TENANT,
        downstream.jobCode(),
        upstreamOne.jobCode(),
        TENANT,
        downstream.jobCode(),
        upstreamTwo.jobCode());
    LaunchResponse response =
        workflowRunLaunchService.launch(launchRequest(downstream, bizDate, TriggerType.EVENT));
    String childRequestId = childRequestId(response);
    assertThat(response.instanceNo()).isNotBlank();
    String selectedRequestStatus =
        jdbcTemplate.queryForObject(selectRequestStatusSql, String.class, TENANT, childRequestId);
    assertThat(selectedRequestStatus).isEqualTo("WAITING");
    Integer selectedJobDependencyWaitingLaunchCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT, childRequestId, bizDate);
    assertThat(selectedJobDependencyWaitingLaunchCount).isEqualTo(1);
    Integer selectedJobInstanceCount =
        jdbcTemplate.queryForObject(selectCountSql2, Integer.class, TENANT, downstream.jobCode());
    assertThat(selectedJobInstanceCount).isZero();
    assertThat(jobDependencyWaitingLaunchService.listRecent(
            TENANT, "WAITING", downstream.jobCode(), 10))
        .singleElement()
        .satisfies(summary -> {
          assertThat(summary.requestId()).isEqualTo(childRequestId);
          assertThat(summary.bizDate()).isEqualTo(bizDate);
          assertThat(summary.dependencySnapshot()).contains(upstreamOne.jobCode());
        });
  }

  @Test
  @DisplayName("依赖准入与恢复检查在非绕过 RLS 角色下读取同租户依赖及生效结果")
  void shouldReadDependencyAndPersistWaitUnderNonBypassRlsRole() {
    final String doStatementSql = """
        DO $$ BEGIN
          IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'job_dependency_wait_rls_test') THEN
            CREATE ROLE job_dependency_wait_rls_test NOSUPERUSER NOBYPASSRLS;
          END IF;
        END $$
        """;

    final String grantUsageSql = """
      GRANT USAGE ON SCHEMA batch TO job_dependency_wait_rls_test
  """;
    final String grantSelectSql = """
      GRANT SELECT ON ALL TABLES IN SCHEMA batch TO job_dependency_wait_rls_test
      """;
    final String grantInsertSql = """
      GRANT INSERT, UPDATE, DELETE ON batch.job_dependency_waiting_launch, batch.trigger_request TO\s\
      job_dependency_wait_rls_test\
      """;
    final String grantUsageSql2 = """
      GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA batch TO job_dependency_wait_rls_test\
      """;
    final String setLocalSql = """
      SET LOCAL ROLE job_dependency_wait_rls_test
  """;
    final String setLocalSql2 = """
      SET LOCAL app.tenant_id = ''
  """;

    final String insertJobDependencySql = """
        insert into batch.job_dependency (tenant_id, downstream_job_code, upstream_job_code, dependency_order)\s\
        values (?, ?, ?, 0)\
        """;
    final String selectCountSql = """
        select count(*) from batch.job_dependency_waiting_launch where tenant_id = ? and request_id = ?\s\
        and wait_status = 'WAITING'\
        """;
    final String selectDependencySnapshotSql = """
          select dependency_snapshot::text from batch.job_dependency_waiting_launch
          where tenant_id = ? and request_id = ?
          """;
    final String selectCurrentUserSql = """
        select current_user
    """;

    LocalDate bizDate = LocalDate.of(2026, 12, 18);
    WorkflowLaunchSeed downstream = LaunchIntegrationFixture.prepareWorkflowLaunchWithoutWorker(
        jdbcTemplate, TENANT, "DISPATCH", "IT_DEP_RLS", TriggerType.EVENT, bizDate);
    WorkflowLaunchSeed upstream = LaunchIntegrationFixture.prepareWorkflowLaunchWithoutWorker(
        jdbcTemplate, TENANT, "DISPATCH", "IT_DEP_RLS", TriggerType.API, bizDate);
    jdbcTemplate.update(insertJobDependencySql, TENANT, downstream.jobCode(), upstream.jobCode());
    String requestId = "rls-dependency-wait-" + UUID.randomUUID();
    LaunchRequest request = LaunchRequest.builder()
        .tenantId(TENANT)
        .jobCode(downstream.jobCode())
        .bizDate(bizDate)
        .triggerType(TriggerType.EVENT)
        .requestId(requestId)
        .traceId("trace-" + requestId)
        .params(Map.of())
        .build();
    LaunchIntegrationFixture.WorkflowChildTriggerSeed parent =
        LaunchIntegrationFixture.prepareWorkflowChildTriggerRequest(
            jdbcTemplate, TENANT, downstream, bizDate, TriggerType.EVENT);
    TriggerRequestEntity original = triggerRequest(TENANT, requestId, null, parent.workflowRunId());
    original.setJobCode(downstream.jobCode());
    original.setBizDate(bizDate);
    assertThat(triggerRequestMapper.insert(original)).isEqualTo(1);
    jdbcTemplate.execute(doStatementSql);
    jdbcTemplate.execute(grantUsageSql);
    jdbcTemplate.execute(grantSelectSql);
    jdbcTemplate.execute(grantInsertSql);
    jdbcTemplate.execute(grantUsageSql2);

    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    Optional<?> admission = transaction.execute(status -> {
      jdbcTemplate.execute(setLocalSql);
      return RlsTenantContextHolder.runWithTenant(
          TENANT, () -> jobDependencyWaitingLaunchService.captureOrDefer(request));
    });

    assertThat(admission).isEmpty();
    List<?> visibleWait = transaction.execute(status -> {
      jdbcTemplate.execute(setLocalSql);
      return RlsTenantContextHolder.runWithTenant(TENANT, () -> {
        List<?> waiting = jobDependencyWaitingLaunchService.selectWaiting(TENANT, 10);
        assertThat(jobDependencyWaitingLaunchService.recordWaitCheck(
                TENANT, requestId, "UPSTREAM_NOT_READY"))
            .isEqualTo(1);
        return waiting;
      });
    });
    assertThat(visibleWait).hasSize(1);
    Integer selectedJobDependencyWaitingLaunchCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT, requestId);
    assertThat(selectedJobDependencyWaitingLaunchCount).isEqualTo(1);

    makeUpstreamEffective(upstream, bizDate);
    String snapshot =
        jdbcTemplate.queryForObject(selectDependencySnapshotSql, String.class, TENANT, requestId);
    String notReady = transaction.execute(status -> {
      jdbcTemplate.execute(setLocalSql);
      jdbcTemplate.execute(setLocalSql2);
      String selectedCurrentUser = jdbcTemplate.queryForObject(selectCurrentUserSql, String.class);
      assertThat(selectedCurrentUser).isEqualTo("job_dependency_wait_rls_test");
      return RlsTenantContextHolder.runWithTenant(
          TENANT,
          () -> jobDependencyWaitingLaunchService.findNotReadyReason(
              TENANT, downstream.jobCode(), bizDate, snapshot));
    });
    assertThat(notReady).isNull();
    String foreignTenant = TENANT + "-foreign";
    String foreignReadiness = transaction.execute(status -> {
      jdbcTemplate.execute(setLocalSql);
      jdbcTemplate.execute(setLocalSql2);
      return RlsTenantContextHolder.runWithTenant(
          foreignTenant,
          () -> jobDependencyWaitingLaunchService.findNotReadyReason(
              foreignTenant, downstream.jobCode(), bizDate, snapshot));
    });
    assertThat(foreignReadiness).isEqualTo("UPSTREAM_NOT_EFFECTIVE:" + upstream.jobCode());
  }

  @Test
  @DisplayName("所有上游同业务日结果生效后恢复原请求并释放等待记录")
  void shouldResumePersistedLaunchWhenAllUpstreamsBecomeEffective() {
    final String insertTenantSql = """
        insert into batch.tenant (tenant_id, tenant_name, status) values (?, ?, 'ACTIVE')\s\
        on conflict (tenant_id) do nothing\
        """;
    final String insertJobDependencySql = """
        insert into batch.job_dependency (tenant_id, downstream_job_code, upstream_job_code, dependency_order)\s\
        values (?, ?, ?, 0), (?, ?, ?, 1)\
        """;
    final String selectWaitStatusSql = """
        select wait_status from batch.job_dependency_waiting_launch where tenant_id = ? and request_id = ?\
        """;
    final String selectRequestStatusSql = """
        select request_status from batch.trigger_request where tenant_id = ? and request_id = ?
    """;
    final String selectIdSql = """
          select id from batch.result_version
          where tenant_id = ? and business_key in (?, ?) and status = 'EFFECTIVE'
          order by id
          """;
    final String selectStatementSql = """
          select (reference->>'resultVersionId')::bigint
          from batch.job_instance instance
          cross join lateral jsonb_array_elements(
            instance.params_snapshot->'dependencyResultSnapshot') reference
          where instance.tenant_id = ? and instance.id = ?
          order by (reference->>'resultVersionId')::bigint
          """;

    jdbcTemplate.update(insertTenantSql, TENANT, TENANT);
    LocalDate bizDate = LocalDate.of(2026, 12, 16);
    WorkflowLaunchSeed downstream = LaunchIntegrationFixture.prepareWorkflowLaunchWithoutWorker(
        jdbcTemplate, TENANT, "DISPATCH", "IT_DEP_RESUME", TriggerType.EVENT, bizDate);
    WorkflowLaunchSeed upstreamOne = LaunchIntegrationFixture.prepareWorkflowLaunchWithoutWorker(
        jdbcTemplate, TENANT, "DISPATCH", "IT_DEP_RESUME", TriggerType.API, bizDate);
    WorkflowLaunchSeed upstreamTwo = LaunchIntegrationFixture.prepareWorkflowLaunchWithoutWorker(
        jdbcTemplate, TENANT, "DISPATCH", "IT_DEP_RESUME", TriggerType.API, bizDate);
    jdbcTemplate.update(
        insertJobDependencySql,
        TENANT,
        downstream.jobCode(),
        upstreamOne.jobCode(),
        TENANT,
        downstream.jobCode(),
        upstreamTwo.jobCode());
    LaunchRequest downstreamRequest = launchRequest(downstream, bizDate, TriggerType.EVENT);

    LaunchResponse deferred = workflowRunLaunchService.launch(downstreamRequest);
    String childRequestId = childRequestId(deferred);
    assertThat(deferred.instanceNo()).isNotBlank();

    makeUpstreamEffective(upstreamOne, bizDate);
    makeUpstreamEffective(upstreamTwo, bizDate);
    dependencyWaitingLaunchReconciler.reconcileTenant(TENANT);

    String selectedWaitStatus =
        jdbcTemplate.queryForObject(selectWaitStatusSql, String.class, TENANT, childRequestId);
    assertThat(selectedWaitStatus).isEqualTo("RELEASED");
    String selectedRequestStatus =
        jdbcTemplate.queryForObject(selectRequestStatusSql, String.class, TENANT, childRequestId);
    assertThat(selectedRequestStatus).isEqualTo("LAUNCHED");
    Long launchedInstanceId = LaunchIntegrationFixture.selectDispatchedJobInstanceId(
        jdbcTemplate, TENANT, deferred.instanceNo());
    JobInstanceEntity launched = jobInstanceMapper.selectById(TENANT, launchedInstanceId);
    assertThat(launched).isNotNull();
    assertThat(launched.getBizDate()).isEqualTo(bizDate);
    List<Long> expectedVersionIds = jdbcTemplate.queryForList(
        selectIdSql,
        Long.class,
        TENANT,
        "job:" + upstreamOne.jobCode() + ":" + bizDate,
        "job:" + upstreamTwo.jobCode() + ":" + bizDate);
    List<Long> observedVersionIds =
        jdbcTemplate.queryForList(selectStatementSql, Long.class, TENANT, launchedInstanceId);
    assertThat(expectedVersionIds).hasSize(2);
    assertThat(observedVersionIds).containsExactlyElementsOf(expectedVersionIds);
  }

  @Test
  @DisplayName("系统依赖恢复逐租户释放请求且跳过已停用租户")
  void shouldReconcileWaitingLaunchesAcrossRunnableTenantsWithoutMixingTenantState()
      throws Exception {
    final String updateTenantSql = """
        update batch.tenant set status = 'SUSPENDED' where tenant_id = ?
    """;

    String tenantA = "r20-system-a-" + UUID.randomUUID();
    String tenantB = "r20-system-b-" + UUID.randomUUID();
    String suspendedTenant = "r20-system-suspended-" + UUID.randomUUID();
    LocalDate bizDate = LocalDate.of(2026, 12, 22);
    insertRunnableTenant(tenantA);
    insertRunnableTenant(tenantB);
    insertRunnableTenant(suspendedTenant);

    DependencyWaitFixture waitA = RlsTenantContextHolder.runWithTenant(
        tenantA, () -> prepareRunnableDependencyWait(tenantA, "A", bizDate));
    DependencyWaitFixture waitB = RlsTenantContextHolder.runWithTenant(
        tenantB, () -> prepareRunnableDependencyWait(tenantB, "B", bizDate));
    DependencyWaitFixture suspendedWait = RlsTenantContextHolder.runWithTenant(
        suspendedTenant, () -> prepareRunnableDependencyWait(suspendedTenant, "S", bizDate));
    jdbcTemplate.update(updateTenantSql, suspendedTenant);

    JobDependencyWaitingLaunchReconciler target =
        AopTestUtils.getUltimateTargetObject(dependencyWaitingLaunchReconciler);
    target.reconcile();

    assertWaitReleased(waitA);
    assertWaitReleased(waitB);
    assertWaitRemainsPending(suspendedWait);
  }

  @Test
  @DisplayName("两个依赖恢复器并发释放同一请求时只启动一个下游实例")
  void shouldLaunchOnlyOnceWhenDependencyWaitIsReleasedConcurrently() throws Exception {
    final String insertTenantSql = """
        insert into batch.tenant (tenant_id, tenant_name, status) values (?, ?, 'ACTIVE')\s\
        on conflict (tenant_id) do nothing\
        """;
    final String insertJobDependencySql = """
        insert into batch.job_dependency (tenant_id, downstream_job_code, upstream_job_code, dependency_order)\s\
        values (?, ?, ?, 0)\
        """;
    final String selectWaitStatusSql = """
        select wait_status from batch.job_dependency_waiting_launch where tenant_id = ? and request_id = ?\
        """;
    final String selectRequestStatusSql = """
        select request_status from batch.trigger_request where tenant_id = ? and request_id = ?
    """;
    final String selectCountSql = """
        select count(*) from batch.job_instance where tenant_id = ? and job_code = ?
    """;

    jdbcTemplate.update(insertTenantSql, TENANT, TENANT);
    LocalDate bizDate = LocalDate.of(2026, 12, 19);
    WorkflowLaunchSeed downstream = LaunchIntegrationFixture.prepareWorkflowLaunchWithoutWorker(
        jdbcTemplate, TENANT, "DISPATCH", "IT_DEP_CONCURRENT_RELEASE", TriggerType.EVENT, bizDate);
    WorkflowLaunchSeed upstream = LaunchIntegrationFixture.prepareWorkflowLaunchWithoutWorker(
        jdbcTemplate, TENANT, "DISPATCH", "IT_DEP_CONCURRENT_RELEASE", TriggerType.API, bizDate);
    jdbcTemplate.update(insertJobDependencySql, TENANT, downstream.jobCode(), upstream.jobCode());
    LaunchResponse deferred =
        workflowRunLaunchService.launch(launchRequest(downstream, bizDate, TriggerType.EVENT));
    String childRequestId = childRequestId(deferred);
    assertThat(deferred.instanceNo()).isNotBlank();
    makeUpstreamEffective(upstream, bizDate);

    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> first = executor.submit(() -> {
        await(start);
        dependencyWaitingLaunchReconciler.reconcileTenant(TENANT);
      });
      Future<?> second = executor.submit(() -> {
        await(start);
        dependencyWaitingLaunchReconciler.reconcileTenant(TENANT);
      });
      start.countDown();
      first.get(30, TimeUnit.SECONDS);
      second.get(30, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    String selectedWaitStatus =
        jdbcTemplate.queryForObject(selectWaitStatusSql, String.class, TENANT, childRequestId);
    assertThat(selectedWaitStatus).isEqualTo("RELEASED");
    String selectedRequestStatus =
        jdbcTemplate.queryForObject(selectRequestStatusSql, String.class, TENANT, childRequestId);
    assertThat(selectedRequestStatus).isEqualTo("LAUNCHED");
    Integer selectedJobInstanceCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT, downstream.jobCode());
    assertThat(selectedJobInstanceCount).isEqualTo(1);
  }

  private static void await(CountDownLatch start) {
    try {
      if (!start.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("concurrent dependency release start timed out");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("concurrent dependency release interrupted", interrupted);
    }
  }

  @Test
  @DisplayName("依赖恢复器释放原请求后批日仍等待根 Workflow 终态才结算")
  void dependencyRecoveryAndBatchDaySettlement_shouldConvergeInOneBusinessDay() {
    final String insertJobDependencySql = """
        insert into batch.job_dependency (tenant_id, downstream_job_code, upstream_job_code, dependency_order)\s\
        values (?, ?, ?, 0)\
        """;
    final String selectWaitStatusSql = """
        select wait_status from batch.job_dependency_waiting_launch where tenant_id = ? and request_id = ?\
        """;
    final String insertBatchDayInstanceSql = """
      insert into batch.batch_day_instance (tenant_id, calendar_code, biz_date, day_status, open_at, late_count,\s\
      catchup_count, timezone_snapshot, dst_policy_snapshot, frozen, version, created_at, updated_at, dry_run)\s\
      values (?, 'JOB_CALENDAR', ?, 'CUTOFF', current_timestamp, 0, 0, 'UTC',\s\
      'gap=RUN_AT_NEXT_VALID_TIME;overlap=RUN_ONCE_EARLIER_OFFSET', false, 0, current_timestamp,\s\
      current_timestamp, false)\
      """;
    final String updateWorkflowRunSql = """
      update batch.workflow_run set run_status = 'SUCCESS', finished_at = current_timestamp where tenant_id = ?\s\
      and trigger_request_id = (select id from batch.trigger_request where tenant_id = ? and request_id = ?)\
      """;
    final String selectRequestStatusSql = """
      select request_status from batch.trigger_request where tenant_id = ? and request_id = ?\
      """;
    final String updateWorkflowRunSql2 = """
      update batch.workflow_run set run_status = 'SUCCESS', finished_at = current_timestamp where tenant_id = ?\s\
      and id = ?\
      """;
    final String completeLaunchedJobsSql = """
      update batch.job_instance set instance_status = 'SUCCESS', finished_at = current_timestamp
      where tenant_id = ? and id in (?, ?)
      """;

    LocalDate bizDate = LocalDate.of(2026, 12, 20);
    WorkflowLaunchSeed downstream = LaunchIntegrationFixture.prepareWorkflowLaunchWithoutWorker(
        jdbcTemplate, TENANT, "DISPATCH", "IT_DEP_SETTLE", TriggerType.EVENT, bizDate);
    WorkflowLaunchSeed upstream = LaunchIntegrationFixture.prepareWorkflowLaunchWithoutWorker(
        jdbcTemplate, TENANT, "DISPATCH", "IT_DEP_SETTLE", TriggerType.API, bizDate);
    jdbcTemplate.update(insertJobDependencySql, TENANT, downstream.jobCode(), upstream.jobCode());

    LaunchResponse deferred =
        workflowRunLaunchService.launch(launchRequest(downstream, bizDate, TriggerType.EVENT));
    String childRequestId = childRequestId(deferred);
    Long workflowRunId = Long.valueOf(deferred.instanceNo());
    String selectedWaitStatus =
        jdbcTemplate.queryForObject(selectWaitStatusSql, String.class, TENANT, childRequestId);
    assertThat(selectedWaitStatus).isEqualTo("WAITING");

    jdbcTemplate.update(insertBatchDayInstanceSql, TENANT, bizDate);

    batchDaySettleScheduler.settle();
    assertThat(batchDayStatus(bizDate)).isEqualTo("IN_FLIGHT");
    String selectedWaitStatusRead2 =
        jdbcTemplate.queryForObject(selectWaitStatusSql, String.class, TENANT, childRequestId);
    assertThat(selectedWaitStatusRead2).isEqualTo("WAITING");

    JobInstanceEntity upstreamInstance = makeUpstreamEffective(upstream, bizDate);
    jdbcTemplate.update(updateWorkflowRunSql, TENANT, TENANT, upstream.requestId());
    dependencyWaitingLaunchReconciler.reconcileTenant(TENANT);

    String selectedWaitStatusRead3 =
        jdbcTemplate.queryForObject(selectWaitStatusSql, String.class, TENANT, childRequestId);
    assertThat(selectedWaitStatusRead3).isEqualTo("RELEASED");
    String selectedRequestStatus =
        jdbcTemplate.queryForObject(selectRequestStatusSql, String.class, TENANT, childRequestId);
    assertThat(selectedRequestStatus).isEqualTo("LAUNCHED");
    assertThat(batchDayStatus(bizDate)).isEqualTo("IN_FLIGHT");

    // 此夹具没有 Worker；显式完成上游与下游 Job，批日才具备结算资格。
    Long downstreamInstanceId = LaunchIntegrationFixture.selectDispatchedJobInstanceId(
        jdbcTemplate, TENANT, deferred.instanceNo());
    assertThat(jdbcTemplate.update(
            completeLaunchedJobsSql, TENANT, upstreamInstance.getId(), downstreamInstanceId))
        .isEqualTo(2);
    jdbcTemplate.update(updateWorkflowRunSql2, TENANT, workflowRunId);
    batchDaySettleScheduler.settle();

    assertThat(batchDayStatus(bizDate)).isEqualTo("SETTLED");
  }

  @ParameterizedTest(name = "{0}后执行时,Job 依赖恢复仍只派发一次且义务不丢失")
  @ValueSource(strings = {"SETTLEMENT_FIRST", "RECOVERY_FIRST"})
  @DisplayName("Job 依赖恢复在结算扫描前后执行都保留业务日义务")
  void dependencyRecoveryBeforeOrAfterSettlement_shouldKeepBatchDayInFlight(String order) {
    final String insertJobDependencySql = """
        insert into batch.job_dependency (tenant_id, downstream_job_code, upstream_job_code, dependency_order)\s\
        values (?, ?, ?, 0)\
        """;
    final String updateWorkflowRunSql = """
        update batch.workflow_run set run_status = 'SUCCESS', finished_at = current_timestamp\s\
        where tenant_id = ? and trigger_request_id = (select id from batch.trigger_request where tenant_id = ?\s\
        and request_id = ?)\
        """;
    final String insertBatchDayInstanceSql = """
        insert into batch.batch_day_instance (tenant_id, calendar_code, biz_date, day_status, open_at,\s\
        late_count, catchup_count, timezone_snapshot, dst_policy_snapshot, frozen, version, created_at,\s\
        updated_at, dry_run) values (?, 'JOB_CALENDAR', ?, 'CUTOFF', current_timestamp, 0, 0, 'UTC',\s\
        'gap=RUN_AT_NEXT_VALID_TIME;overlap=RUN_ONCE_EARLIER_OFFSET', false, 0, current_timestamp,\s\
        current_timestamp, false) on conflict (tenant_id, calendar_code, biz_date) do update\s\
        set day_status = 'CUTOFF', cutoff_at = current_timestamp - interval '1 second', updated_at =\s\
        current_timestamp\
        """;
    final String selectWaitStatusSql = """
        select wait_status from batch.job_dependency_waiting_launch where tenant_id = ? and request_id = ?\
        """;
    final String selectRequestStatusSql = """
        select request_status from batch.trigger_request where tenant_id = ? and request_id = ?\
        """;
    final String selectCountSql = """
        select count(*) from batch.job_instance where tenant_id = ? and job_code = ?
    """;

    LocalDate bizDate = LocalDate.of(2026, 12, 21);
    WorkflowLaunchSeed downstream = LaunchIntegrationFixture.prepareWorkflowLaunchWithoutWorker(
        jdbcTemplate, TENANT, "DISPATCH", "IT_DEP_SETTLE_RACE", TriggerType.EVENT, bizDate);
    WorkflowLaunchSeed upstream = LaunchIntegrationFixture.prepareWorkflowLaunchWithoutWorker(
        jdbcTemplate, TENANT, "DISPATCH", "IT_DEP_SETTLE_RACE", TriggerType.API, bizDate);
    jdbcTemplate.update(insertJobDependencySql, TENANT, downstream.jobCode(), upstream.jobCode());

    LaunchResponse deferred =
        workflowRunLaunchService.launch(launchRequest(downstream, bizDate, TriggerType.EVENT));
    String childRequestId = childRequestId(deferred);
    makeUpstreamEffective(upstream, bizDate);
    jdbcTemplate.update(updateWorkflowRunSql, TENANT, TENANT, upstream.requestId());
    jdbcTemplate.update(insertBatchDayInstanceSql, TENANT, bizDate);

    if ("SETTLEMENT_FIRST".equals(order)) {
      batchDaySettleScheduler.settle();
      assertThat(batchDayStatus(bizDate)).isEqualTo("IN_FLIGHT");
      dependencyWaitingLaunchReconciler.reconcileTenant(TENANT);
    } else {
      dependencyWaitingLaunchReconciler.reconcileTenant(TENANT);
      batchDaySettleScheduler.settle();
    }

    String selectedWaitStatus =
        jdbcTemplate.queryForObject(selectWaitStatusSql, String.class, TENANT, childRequestId);
    assertThat(selectedWaitStatus).isEqualTo("RELEASED");
    String selectedRequestStatus =
        jdbcTemplate.queryForObject(selectRequestStatusSql, String.class, TENANT, childRequestId);
    assertThat(selectedRequestStatus).isEqualTo("LAUNCHED");
    Integer selectedJobInstanceCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT, downstream.jobCode());
    assertThat(selectedJobInstanceCount).isEqualTo(1);
    assertThat(batchDayStatus(bizDate)).isEqualTo("IN_FLIGHT");
  }

  private String batchDayStatus(LocalDate bizDate) {
    final String selectDayStatusSql = """
        select day_status from batch.batch_day_instance where tenant_id = ? and calendar_code = 'JOB_CALENDAR'\s\
        and biz_date = ?\
        """;

    return jdbcTemplate.queryForObject(selectDayStatusSql, String.class, TENANT, bizDate);
  }

  @ParameterizedTest
  @ValueSource(strings = {"[null]", "[{}]", "null", "[\"UPSTREAM_A\"]", """
    [{"upstreamJobCode":"UPSTREAM_A","bizDateAlignment":"PREVIOUS_BIZ_DAY",
      "upstreamBizDate":"invalid-date"}]
    """})
  @DisplayName("损坏或旧格式依赖快照保持等待且记录原因,同租户后续合法等待仍能恢复并创建唯一实例")
  void shouldIsolateInvalidDependencyWaitAndContinueOtherRecovery(String invalidSnapshot) {
    final String insertJobDependencySql = """
          insert into batch.job_dependency
            (tenant_id, downstream_job_code, upstream_job_code, dependency_order)
          values (?, ?, ?, 0), (?, ?, ?, 0)
          """;
    final String updateJobDependencyWaitingLaunchSql = """
              update batch.job_dependency_waiting_launch
              set dependency_snapshot = ?::jsonb, updated_at = current_timestamp - interval '1 day'
              where tenant_id = ? and request_id = ? and wait_status = 'WAITING'
              """;
    final String selectWaitStatusSql = """
              select wait_status, last_not_ready_reason, dependency_snapshot::text as snapshot
              from batch.job_dependency_waiting_launch where tenant_id = ? and request_id = ?
              """;
    final String selectDependencySnapshotSql = """
              select dependency_snapshot = ?::jsonb from batch.job_dependency_waiting_launch
              where tenant_id = ? and request_id = ?
              """;
    final String selectRequestStatusSql = """
      select request_status from batch.trigger_request where tenant_id = ? and request_id = ?
  """;
    final String selectCountSql = """
      select count(*) from batch.job_instance where tenant_id = ? and job_code = ?
  """;
    final String selectWaitStatusSql2 = """
            select wait_status from batch.job_dependency_waiting_launch
            where tenant_id = ? and request_id = ?
            """;

    LocalDate bizDate = LocalDate.of(2026, 12, 19);
    WorkflowLaunchSeed invalid = LaunchIntegrationFixture.prepareWorkflowLaunchWithoutWorker(
        jdbcTemplate, TENANT, "DISPATCH", "IT_DEP_INVALID", TriggerType.EVENT, bizDate);
    WorkflowLaunchSeed healthy = LaunchIntegrationFixture.prepareWorkflowLaunchWithoutWorker(
        jdbcTemplate, TENANT, "DISPATCH", "IT_DEP_VALID", TriggerType.EVENT, bizDate);
    WorkflowLaunchSeed upstream = LaunchIntegrationFixture.prepareWorkflowLaunchWithoutWorker(
        jdbcTemplate, TENANT, "DISPATCH", "IT_DEP_UPSTREAM", TriggerType.API, bizDate);
    jdbcTemplate.update(
        insertJobDependencySql,
        TENANT,
        invalid.jobCode(),
        upstream.jobCode(),
        TENANT,
        healthy.jobCode(),
        upstream.jobCode());
    LaunchResponse invalidRun =
        workflowRunLaunchService.launch(launchRequest(invalid, bizDate, TriggerType.EVENT));
    LaunchResponse healthyRun =
        workflowRunLaunchService.launch(launchRequest(healthy, bizDate, TriggerType.EVENT));
    String invalidRequestId = childRequestId(invalidRun);
    String healthyRequestId = childRequestId(healthyRun);
    int affectedJobDependencyWaitingLaunchRows = jdbcTemplate.update(
        updateJobDependencyWaitingLaunchSql, invalidSnapshot, TENANT, invalidRequestId);
    assertThat(affectedJobDependencyWaitingLaunchRows).isEqualTo(1);
    makeUpstreamEffective(upstream, bizDate);

    dependencyWaitingLaunchReconciler.reconcileTenant(TENANT);
    dependencyWaitingLaunchReconciler.reconcileTenant(TENANT);

    Map<String, Object> selectedWaitStatusRow =
        jdbcTemplate.queryForMap(selectWaitStatusSql, TENANT, invalidRequestId);
    assertThat(selectedWaitStatusRow)
        .containsEntry("wait_status", "WAITING")
        .containsEntry("last_not_ready_reason", "DEPENDENCY_SNAPSHOT_INVALID");
    Boolean selectedDependencySnapshot = jdbcTemplate.queryForObject(
        selectDependencySnapshotSql, Boolean.class, invalidSnapshot, TENANT, invalidRequestId);
    assertThat(selectedDependencySnapshot).isTrue();
    String selectedRequestStatus =
        jdbcTemplate.queryForObject(selectRequestStatusSql, String.class, TENANT, invalidRequestId);
    assertThat(selectedRequestStatus).isEqualTo("WAITING");
    Integer selectedJobInstanceCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT, invalid.jobCode());
    assertThat(selectedJobInstanceCount).isZero();
    String selectedWaitStatus =
        jdbcTemplate.queryForObject(selectWaitStatusSql2, String.class, TENANT, healthyRequestId);
    assertThat(selectedWaitStatus).isEqualTo("RELEASED");
    Integer selectedJobInstanceCountRead2 =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT, healthy.jobCode());
    assertThat(selectedJobInstanceCountRead2).isEqualTo(1);
    Long healthyInstanceId = LaunchIntegrationFixture.selectDispatchedJobInstanceId(
        jdbcTemplate, TENANT, healthyRun.instanceNo());
    assertThat(jobInstanceMapper.selectById(TENANT, healthyInstanceId).getBizDate())
        .isEqualTo(bizDate);
  }

  @Test
  @DisplayName("上游重跑产生更高 PENDING 版本时旧 EFFECTIVE 不放行等待中的下游")
  void shouldKeepPersistedLaunchWaitingWhenNewerUpstreamVersionIsPending() {
    final String insertTenantSql = """
        insert into batch.tenant (tenant_id, tenant_name, status) values (?, ?, 'ACTIVE')\s\
        on conflict (tenant_id) do nothing\
        """;
    final String insertJobDependencySql = """
        insert into batch.job_dependency (tenant_id, downstream_job_code, upstream_job_code, dependency_order)\s\
        values (?, ?, ?, 0)\
        """;
    final String selectDependencySnapshotSql = """
        select dependency_snapshot::text from batch.job_dependency_waiting_launch where tenant_id = ?\s\
        and request_id = ?\
        """;
    final String insertResultVersionSql = """
        insert into batch.result_version (tenant_id, business_key, version_no, job_instance_id, status,\s\
        effective_at, payload_storage, payload_json, generated_at, generated_by, promotion_policy)\s\
        values (?, ?, 2, ?, 'PENDING', null, 'INLINE_JSON', '{}'::jsonb, current_timestamp,\s\
        'integration-test-rerun', 'MANUAL_APPROVAL')\
        """;
    final String selectLaunchPayloadSql = """
        select launch_payload::text from batch.job_dependency_waiting_launch where tenant_id = ?\s\
        and request_id = ?\
        """;
    final String selectWaitStatusSql = """
        select wait_status from batch.job_dependency_waiting_launch where tenant_id = ? and request_id = ?\
        """;
    final String selectCountSql = """
        select count(*) from batch.job_instance where tenant_id = ? and job_code = ?
    """;

    jdbcTemplate.update(insertTenantSql, TENANT, TENANT);
    LocalDate bizDate = LocalDate.of(2026, 12, 17);
    WorkflowLaunchSeed downstream = LaunchIntegrationFixture.prepareWorkflowLaunchWithoutWorker(
        jdbcTemplate, TENANT, "DISPATCH", "IT_DEP_PENDING_VERSION", TriggerType.EVENT, bizDate);
    WorkflowLaunchSeed upstream = LaunchIntegrationFixture.prepareWorkflowLaunchWithoutWorker(
        jdbcTemplate, TENANT, "DISPATCH", "IT_DEP_PENDING_VERSION", TriggerType.API, bizDate);
    jdbcTemplate.update(insertJobDependencySql, TENANT, downstream.jobCode(), upstream.jobCode());
    LaunchResponse downstreamRun =
        workflowRunLaunchService.launch(launchRequest(downstream, bizDate, TriggerType.EVENT));
    String childRequestId = childRequestId(downstreamRun);
    JobInstanceEntity upstreamInstance = makeUpstreamEffective(upstream, bizDate);
    String dependencySnapshot = jdbcTemplate.queryForObject(
        selectDependencySnapshotSql, String.class, TENANT, childRequestId);
    assertThat(jobDependencyWaitingLaunchService.findNotReadyReason(
            TENANT, downstream.jobCode(), bizDate, dependencySnapshot))
        .isNull();
    jdbcTemplate.update(
        insertResultVersionSql,
        TENANT,
        "job:" + upstream.jobCode() + ":" + bizDate,
        upstreamInstance.getId());

    String launchPayload =
        jdbcTemplate.queryForObject(selectLaunchPayloadSql, String.class, TENANT, childRequestId);
    LaunchRequest persistedLaunch = JsonUtils.fromJson(launchPayload, LaunchRequest.class);
    assertThat(jobDependencyWaitingLaunchService.captureOrDefer(persistedLaunch))
        .isEmpty();

    dependencyWaitingLaunchReconciler.reconcileTenant(TENANT);

    String selectedWaitStatus =
        jdbcTemplate.queryForObject(selectWaitStatusSql, String.class, TENANT, childRequestId);
    assertThat(selectedWaitStatus).isEqualTo("WAITING");
    Integer selectedJobInstanceCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT, downstream.jobCode());
    assertThat(selectedJobInstanceCount).isZero();
  }

  @Test
  @DisplayName("PREVIOUS_BIZ_DAY 依赖按上游前一工作日等待并在该日结果生效后恢复")
  void shouldResumePreviousBusinessDayDependencyAgainstFridayResult() {
    final String insertTenantSql = """
        insert into batch.tenant (tenant_id, tenant_name, status) values (?, ?, 'ACTIVE')\s\
        on conflict (tenant_id) do nothing\
        """;
    final String insertJobDependencySql = """
        insert into batch.job_dependency (tenant_id, downstream_job_code, upstream_job_code, dependency_order,\s\
        biz_date_alignment) values (?, ?, ?, 0, 'PREVIOUS_BIZ_DAY')\
        """;
    final String selectWaitStatusSql = """
        select wait_status from batch.job_dependency_waiting_launch where tenant_id = ? and request_id = ?\
        """;

    jdbcTemplate.update(insertTenantSql, TENANT, TENANT);
    LocalDate downstreamBizDate = LocalDate.of(2026, Month.DECEMBER, 14);
    LocalDate upstreamBizDate = LocalDate.of(2026, Month.DECEMBER, 11);
    WorkflowLaunchSeed downstream = LaunchIntegrationFixture.prepareWorkflowLaunchWithoutWorker(
        jdbcTemplate, TENANT, "DISPATCH", "IT_DEP_PREV_DAY", TriggerType.EVENT, downstreamBizDate);
    WorkflowLaunchSeed upstream = LaunchIntegrationFixture.prepareWorkflowLaunchWithoutWorker(
        jdbcTemplate, TENANT, "DISPATCH", "IT_DEP_PREV_DAY", TriggerType.API, upstreamBizDate);
    jdbcTemplate.update(insertJobDependencySql, TENANT, downstream.jobCode(), upstream.jobCode());
    LaunchResponse deferred = workflowRunLaunchService.launch(
        launchRequest(downstream, downstreamBizDate, TriggerType.EVENT));
    String childRequestId = childRequestId(deferred);

    assertThat(deferred.instanceNo()).isNotBlank();
    assertThat(jobDependencyWaitingLaunchService.listRecent(
            TENANT, "WAITING", downstream.jobCode(), 10))
        .singleElement()
        .satisfies(summary -> assertThat(summary.dependencySnapshot())
            .contains("PREVIOUS_BIZ_DAY")
            .contains(upstream.jobCode()));

    makeUpstreamEffective(upstream, upstreamBizDate);
    dependencyWaitingLaunchReconciler.reconcileTenant(TENANT);

    String selectedWaitStatus =
        jdbcTemplate.queryForObject(selectWaitStatusSql, String.class, TENANT, childRequestId);
    assertThat(selectedWaitStatus).isEqualTo("RELEASED");
    Long launchedInstanceId = LaunchIntegrationFixture.selectDispatchedJobInstanceId(
        jdbcTemplate, TENANT, deferred.instanceNo());
    JobInstanceEntity launched = jobInstanceMapper.selectById(TENANT, launchedInstanceId);
    assertThat(launched).isNotNull();
    assertThat(launched.getBizDate()).isEqualTo(downstreamBizDate);
  }

  @ParameterizedTest
  @EnumSource(
      value = TriggerType.class,
      names = {"EVENT", "SCHEDULED", "CATCH_UP", "RERUN"})
  @DisplayName("混合业务日依赖等待恢复固定原规则与日期并记录实际结果版本")
  void shouldRestoreMixedDateDependenciesFromFrozenSnapshot(TriggerType triggerType) {
    final String insertBusinessCalendarSql = """
          insert into batch.business_calendar
            (tenant_id, calendar_code, calendar_name, timezone, enabled)
          values (?, ?, 'Mixed dependency calendar', 'UTC', true) returning id
          """;
    final String insertCalendarHolidaySql = """
          insert into batch.calendar_holiday
            (tenant_id, calendar_id, biz_date, day_type, holiday_name, scope)
          values (?, ?, ?, 'WORKDAY_OVERRIDE', 'Saturday workday', 'CALENDAR'),
                 (?, ?, ?, 'HOLIDAY', 'Friday holiday', 'CALENDAR')
          """;
    final String updateJobDefinitionSql = """
          update batch.job_definition set calendar_code = ?
          where tenant_id = ? and job_code = ?
          """;
    final String insertJobDependencySql = """
          insert into batch.job_dependency
            (tenant_id, downstream_job_code, upstream_job_code, dependency_order, biz_date_alignment)
          values (?, ?, ?, 0, 'PREVIOUS_BIZ_DAY'), (?, ?, ?, 1, 'SAME_DAY')
          """;
    final String selectDependencySnapshotSql = """
          select dependency_snapshot::text from batch.job_dependency_waiting_launch
          where tenant_id = ? and request_id = ? and wait_status = 'WAITING'
          """;
    final String updateCalendarHolidaySql = """
          update batch.calendar_holiday set day_type = 'HOLIDAY'
          where tenant_id = ? and calendar_id = ? and biz_date = ?
          """;
    final String deleteJobDependencySql = """
          delete from batch.job_dependency where tenant_id = ? and downstream_job_code = ?
          """;
    final String selectWaitStatusSql = """
          select wait_status from batch.job_dependency_waiting_launch
          where tenant_id = ? and request_id = ?
          """;
    final String selectCountSql = """
          select count(*) from batch.job_instance where tenant_id = ? and job_code = ?
          """;
    final String selectRequestStatusSql = """
          select request_status from batch.trigger_request where tenant_id = ? and request_id = ?
          """;
    final String selectDependencySnapshotSql2 = """
        select dependency_snapshot::text from batch.job_dependency_waiting_launch
        where tenant_id = ? and request_id = ?
        """;
    final String selectStatementSql = """
          select (params_snapshot->'dependencyResultSnapshot')::text
          from batch.job_instance where tenant_id = ? and id = ?
          """;
    final String selectIdSql = """
            select id from batch.result_version where tenant_id = ? and business_key = ?
            and version_no = 1
            """;

    insertRunnableTenant(TENANT);
    LocalDate downstreamDate = LocalDate.of(2026, 12, 14);
    LocalDate priorWorkday = LocalDate.of(2026, 12, 12);
    String calendarCode = "MIXED_" + UUID.randomUUID().toString().replace("-", "");
    WorkflowLaunchSeed downstream = LaunchIntegrationFixture.prepareWorkflowLaunchWithoutWorker(
        jdbcTemplate, TENANT, "DISPATCH", "IT_DEP_MIXED", triggerType, downstreamDate);
    WorkflowLaunchSeed previous = LaunchIntegrationFixture.prepareWorkflowLaunchWithoutWorker(
        jdbcTemplate, TENANT, "DISPATCH", "IT_DEP_MIXED", TriggerType.API, priorWorkday);
    WorkflowLaunchSeed sameDay = LaunchIntegrationFixture.prepareWorkflowLaunchWithoutWorker(
        jdbcTemplate, TENANT, "DISPATCH", "IT_DEP_MIXED", TriggerType.API, downstreamDate);
    Long calendarId =
        jdbcTemplate.queryForObject(insertBusinessCalendarSql, Long.class, TENANT, calendarCode);
    jdbcTemplate.update(
        insertCalendarHolidaySql,
        TENANT,
        calendarId,
        priorWorkday,
        TENANT,
        calendarId,
        priorWorkday.minusDays(1));
    jdbcTemplate.update(updateJobDefinitionSql, calendarCode, TENANT, downstream.jobCode());
    jdbcTemplate.update(
        insertJobDependencySql,
        TENANT,
        downstream.jobCode(),
        previous.jobCode(),
        TENANT,
        downstream.jobCode(),
        sameDay.jobCode());

    LaunchResponse root =
        workflowRunLaunchService.launch(launchRequest(downstream, downstreamDate, triggerType));
    String requestId = childRequestId(root);
    String snapshot =
        jdbcTemplate.queryForObject(selectDependencySnapshotSql, String.class, TENANT, requestId);
    assertThat(snapshot).contains(priorWorkday.toString(), previous.jobCode(), sameDay.jobCode());

    // 从 DO_NOTHING cron 回调合并掉的遗漏触发。AUTO 模式使用持久化
    jdbcTemplate.update(updateCalendarHolidaySql, TENANT, calendarId, priorWorkday);
    jdbcTemplate.update(deleteJobDependencySql, TENANT, downstream.jobCode());
    JobInstanceEntity previousInstance = makeUpstreamEffective(previous, priorWorkday);
    dependencyWaitingLaunchReconciler.reconcileTenant(TENANT);
    String selectedWaitStatus =
        jdbcTemplate.queryForObject(selectWaitStatusSql, String.class, TENANT, requestId);
    assertThat(selectedWaitStatus).isEqualTo("WAITING");
    Integer selectedJobInstanceCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT, downstream.jobCode());
    assertThat(selectedJobInstanceCount).isZero();

    JobInstanceEntity sameDayInstance = makeUpstreamEffective(sameDay, downstreamDate);
    dependencyWaitingLaunchReconciler.reconcileTenant(TENANT);
    dependencyWaitingLaunchReconciler.reconcileTenant(TENANT);
    String selectedWaitStatusRead2 =
        jdbcTemplate.queryForObject(selectWaitStatusSql, String.class, TENANT, requestId);
    assertThat(selectedWaitStatusRead2).isEqualTo("RELEASED");
    String selectedRequestStatus =
        jdbcTemplate.queryForObject(selectRequestStatusSql, String.class, TENANT, requestId);
    assertThat(selectedRequestStatus).isEqualTo("LAUNCHED");
    String selectedDependencySnapshot =
        jdbcTemplate.queryForObject(selectDependencySnapshotSql2, String.class, TENANT, requestId);
    assertThat(selectedDependencySnapshot).isEqualTo(snapshot);
    Integer selectedJobInstanceCountRead2 =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT, downstream.jobCode());
    assertThat(selectedJobInstanceCountRead2).isOne();
    Long instanceId = LaunchIntegrationFixture.selectDispatchedJobInstanceId(
        jdbcTemplate, TENANT, root.instanceNo());
    assertThat(jobInstanceMapper.selectById(TENANT, instanceId).getBizDate())
        .isEqualTo(downstreamDate);
    DependencyResultReference[] references = JsonUtils.fromJson(
        jdbcTemplate.queryForObject(selectStatementSql, String.class, TENANT, instanceId),
        DependencyResultReference[].class);
    assertThat(references)
        .extracting(
            DependencyResultReference::upstreamJobCode,
            DependencyResultReference::bizDateAlignment,
            DependencyResultReference::upstreamBizDate,
            DependencyResultReference::businessKey,
            DependencyResultReference::sourceJobInstanceId)
        .containsExactly(
            tuple(
                previous.jobCode(),
                "PREVIOUS_BIZ_DAY",
                priorWorkday,
                "job:" + previous.jobCode() + ":" + priorWorkday,
                previousInstance.getId()),
            tuple(
                sameDay.jobCode(),
                "SAME_DAY",
                downstreamDate,
                "job:" + sameDay.jobCode() + ":" + downstreamDate,
                sameDayInstance.getId()));
    assertThat(references).allSatisfy(reference -> {
      assertThat(reference.versionNo()).isEqualTo(1);
      Long selectedId =
          jdbcTemplate.queryForObject(selectIdSql, Long.class, TENANT, reference.businessKey());
      assertThat(reference.resultVersionId()).isEqualTo(selectedId);
      assertThat(reference.effectiveAt()).isNotNull();
    });
  }

  @Test
  @DisplayName("依赖等待超过阈值仅发送一次告警并继续保持等待")
  void shouldAlertOnceForLongDependencyWaitWithoutChangingWaitStatus() {
    final String insertTenantSql = """
        insert into batch.tenant (tenant_id, tenant_name, status) values (?, ?, 'ACTIVE')\s\
        on conflict (tenant_id) do nothing\
        """;
    final String insertJobDependencySql = """
        insert into batch.job_dependency (tenant_id, downstream_job_code, upstream_job_code, dependency_order)\s\
        values (?, ?, ?, 0)\
        """;
    final String updateJobDependencyWaitingLaunchSql = """
        update batch.job_dependency_waiting_launch set created_at = current_timestamp - interval '2 days'\s\
        where tenant_id = ? and request_id = ?\
        """;
    final String selectWaitStatusSql = """
        select wait_status from batch.job_dependency_waiting_launch where tenant_id = ? and request_id = ?\
        """;
    final String selectTimeoutAlertedAtSql = """
        select timeout_alerted_at from batch.job_dependency_waiting_launch where tenant_id = ?\s\
        and request_id = ?\
        """;
    final String selectCountSql = """
        select count(*) from batch.alert_event where tenant_id = ?\s\
        and alert_type = 'BATCH_JOB_DEPENDENCY_WAIT_TIMEOUT'\
        """;
    final String selectAlertContextSql = """
        select detail_json::text as detail_json, trace_id
        from batch.alert_event
        where tenant_id = ? and alert_type = 'BATCH_JOB_DEPENDENCY_WAIT_TIMEOUT'
          and detail_json ->> 'requestId' = ?
        """;

    jdbcTemplate.update(insertTenantSql, TENANT, TENANT);
    LocalDate bizDate = LocalDate.of(2026, 12, 18);
    WorkflowLaunchSeed downstream = LaunchIntegrationFixture.prepareWorkflowLaunchWithoutWorker(
        jdbcTemplate, TENANT, "DISPATCH", "IT_DEP_TIMEOUT", TriggerType.EVENT, bizDate);
    WorkflowLaunchSeed upstream = LaunchIntegrationFixture.prepareWorkflowLaunchWithoutWorker(
        jdbcTemplate, TENANT, "DISPATCH", "IT_DEP_TIMEOUT", TriggerType.API, bizDate);
    jdbcTemplate.update(insertJobDependencySql, TENANT, downstream.jobCode(), upstream.jobCode());
    LaunchResponse response =
        workflowRunLaunchService.launch(launchRequest(downstream, bizDate, TriggerType.EVENT));
    String childRequestId = childRequestId(response);
    jdbcTemplate.update(updateJobDependencyWaitingLaunchSql, TENANT, childRequestId);

    dependencyWaitingLaunchReconciler.reconcileTenant(TENANT);
    dependencyWaitingLaunchReconciler.reconcileTenant(TENANT);

    String selectedWaitStatus =
        jdbcTemplate.queryForObject(selectWaitStatusSql, String.class, TENANT, childRequestId);
    assertThat(selectedWaitStatus).isEqualTo("WAITING");
    OffsetDateTime selectedTimeoutAlertedAt = jdbcTemplate.queryForObject(
        selectTimeoutAlertedAtSql, OffsetDateTime.class, TENANT, childRequestId);
    assertThat(selectedTimeoutAlertedAt).isNotNull();
    Integer selectedAlertEventCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT);
    assertThat(selectedAlertEventCount).isEqualTo(1);
    Map<String, Object> alertRow =
        jdbcTemplate.queryForMap(selectAlertContextSql, TENANT, childRequestId);
    JsonNode alertDetail = JsonUtils.fromJson((String) alertRow.get("detail_json"), JsonNode.class);
    assertThat(alertDetail.path("workflowRunId").asLong())
        .isEqualTo(Long.parseLong(response.instanceNo()));
    assertThat(alertDetail.path("nodeCode").asText()).isEqualTo("JOB");
    assertThat(alertDetail.path("jobCode").asText()).isEqualTo(downstream.jobCode());
    assertThat(alertDetail.path("bizDate").asText()).isEqualTo(bizDate.toString());
    assertThat(alertRow.get("trace_id")).isEqualTo(response.traceId());
  }

  private LaunchRequest launchRequest(
      WorkflowLaunchSeed seed, LocalDate bizDate, TriggerType triggerType) {
    return launchRequest(TENANT, seed, bizDate, triggerType);
  }

  private LaunchRequest launchRequest(
      String tenantId, WorkflowLaunchSeed seed, LocalDate bizDate, TriggerType triggerType) {
    return LaunchRequest.builder()
        .tenantId(tenantId)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(bizDate)
        .triggerType(triggerType)
        .requestId(seed.requestId())
        .traceId("trace-" + seed.requestId())
        .params(Map.of())
        .build();
  }

  private TriggerRequestEntity triggerRequest(
      String tenantId, String requestId, Long workflowDefinitionId, Long parentWorkflowRunId) {
    TriggerRequestEntity request = new TriggerRequestEntity();
    request.setTenantId(tenantId);
    request.setRequestId(requestId);
    request.setTriggerType(TriggerType.EVENT.code());
    request.setJobCode(workflowDefinitionId == null ? "R20_CHILD_JOB" : null);
    request.setWorkflowDefinitionId(workflowDefinitionId);
    request.setBizDate(LocalDate.of(2026, 12, 28));
    request.setDedupKey(tenantId + ":" + requestId);
    request.setRequestStatus(TriggerRequestStatus.ACCEPTED.code());
    request.setParentWorkflowRunId(parentWorkflowRunId);
    request.setParentNodeCode(parentWorkflowRunId == null ? null : "JOB");
    request.setTraceId("trace-" + requestId);
    return request;
  }

  private WorkflowRunEntity workflowRun(
      String tenantId, Long workflowDefinitionId, LocalDate bizDate) {
    WorkflowRunEntity run = new WorkflowRunEntity();
    run.setTenantId(tenantId);
    run.setWorkflowDefinitionId(workflowDefinitionId);
    run.setBizDate(bizDate);
    run.setRunStatus("RUNNING");
    run.setTraceId("trace-r20-workflow-run-" + UUID.randomUUID());
    run.setDryRun(false);
    return run;
  }

  private JobInstanceEntity makeUpstreamEffective(WorkflowLaunchSeed upstream, LocalDate bizDate) {
    return makeUpstreamEffective(TENANT, upstream, bizDate);
  }

  private JobInstanceEntity makeUpstreamEffective(
      String tenantId, WorkflowLaunchSeed upstream, LocalDate bizDate) {
    final String insertResultVersionSql = """
        insert into batch.result_version (tenant_id, business_key, version_no, job_instance_id, status,\s\
        effective_at, payload_storage, payload_json, generated_at, generated_by, promotion_policy)\s\
        values (?, ?, 1, ?, 'EFFECTIVE', current_timestamp, 'INLINE_JSON', '{}'::jsonb, current_timestamp,\s\
        'integration-test', 'AUTO_LATEST')\
        """;

    LaunchResponse response = workflowRunLaunchService.launch(
        launchRequest(tenantId, upstream, bizDate, TriggerType.API));
    Long instanceId = LaunchIntegrationFixture.selectDispatchedJobInstanceId(
        jdbcTemplate, tenantId, response.instanceNo());
    jdbcTemplate.update(
        insertResultVersionSql, tenantId, "job:" + upstream.jobCode() + ":" + bizDate, instanceId);
    return jobInstanceMapper.selectById(tenantId, instanceId);
  }

  private DependencyWaitFixture prepareRunnableDependencyWait(
      String tenantId, String label, LocalDate bizDate) {
    final String insertJobDependencySql = """
        insert into batch.job_dependency (tenant_id, downstream_job_code, upstream_job_code, dependency_order)\s\
        values (?, ?, ?, 0)\
        """;

    WorkflowLaunchSeed downstream = LaunchIntegrationFixture.prepareWorkflowLaunchWithoutWorker(
        jdbcTemplate, tenantId, "DISPATCH", "R20_SYSTEM_" + label, TriggerType.EVENT, bizDate);
    WorkflowLaunchSeed upstream = LaunchIntegrationFixture.prepareWorkflowLaunchWithoutWorker(
        jdbcTemplate, tenantId, "DISPATCH", "R20_SYSTEM_" + label, TriggerType.API, bizDate);
    jdbcTemplate.update(insertJobDependencySql, tenantId, downstream.jobCode(), upstream.jobCode());
    LaunchResponse response = workflowRunLaunchService.launch(
        launchRequest(tenantId, downstream, bizDate, TriggerType.EVENT));
    String childRequestId = childRequestId(tenantId, response);
    makeUpstreamEffective(tenantId, upstream, bizDate);
    return new DependencyWaitFixture(tenantId, downstream.jobCode(), childRequestId);
  }

  private void insertRunnableTenant(String tenantId) {
    final String insertTenantSql = """
        insert into batch.tenant (tenant_id, tenant_name, status) values (?, ?, 'ACTIVE')\s\
        on conflict (tenant_id) do update set status = 'ACTIVE'\
        """;

    jdbcTemplate.update(insertTenantSql, tenantId, tenantId);
  }

  private void assertWaitReleased(DependencyWaitFixture fixture) {
    final String selectWaitStatusSql = """
      select wait_status from batch.job_dependency_waiting_launch where tenant_id = ? and request_id = ?\
      """;
    final String selectCountSql = """
      select count(*) from batch.job_instance where tenant_id = ? and job_code = ?
  """;
    final String selectCountSql2 = """
      select count(*) from batch.job_instance where tenant_id = ? and job_code <> ?\s\
      and job_code like 'IT_DISPATCH_%%'\
      """;

    String selectedWaitStatus = jdbcTemplate.queryForObject(
        selectWaitStatusSql, String.class, fixture.tenantId(), fixture.requestId());
    assertThat(selectedWaitStatus).isEqualTo("RELEASED");
    Integer selectedJobInstanceCount = jdbcTemplate.queryForObject(
        selectCountSql, Integer.class, fixture.tenantId(), fixture.downstreamJobCode());
    assertThat(selectedJobInstanceCount).isEqualTo(1);
    Integer selectedJobInstanceCountRead2 = jdbcTemplate.queryForObject(
        selectCountSql2, Integer.class, fixture.tenantId(), fixture.downstreamJobCode());
    assertThat(selectedJobInstanceCountRead2).isEqualTo(1);
  }

  private void assertWaitRemainsPending(DependencyWaitFixture fixture) {
    final String selectWaitStatusSql = """
        select wait_status from batch.job_dependency_waiting_launch where tenant_id = ? and request_id = ?\
        """;
    final String selectCountSql = """
        select count(*) from batch.job_instance where tenant_id = ? and job_code = ?
    """;

    String selectedWaitStatus = jdbcTemplate.queryForObject(
        selectWaitStatusSql, String.class, fixture.tenantId(), fixture.requestId());
    assertThat(selectedWaitStatus).isEqualTo("WAITING");
    Integer selectedJobInstanceCount = jdbcTemplate.queryForObject(
        selectCountSql, Integer.class, fixture.tenantId(), fixture.downstreamJobCode());
    assertThat(selectedJobInstanceCount).isZero();
  }

  private String childRequestId(String tenantId, LaunchResponse workflowRunResponse) {
    final String selectRequestIdSql = """
          select request.request_id
          from batch.trigger_request request
          where request.tenant_id = ?
            and request.parent_workflow_run_id = ?
            and request.parent_node_code = 'JOB'
          """;

    Long parentWorkflowRunId = Long.valueOf(workflowRunResponse.instanceNo());
    return jdbcTemplate.queryForObject(
        selectRequestIdSql, String.class, tenantId, parentWorkflowRunId);
  }

  private record DependencyWaitFixture(
      String tenantId, String downstreamJobCode, String requestId) {}

  private String childRequestId(LaunchResponse workflowRunResponse) {
    final String selectRequestIdSql = """
          select request.request_id
          from batch.trigger_request request
          where request.tenant_id = ?
            and request.parent_workflow_run_id = ?
            and request.parent_node_code = 'JOB'
          """;

    Long parentWorkflowRunId = Long.valueOf(workflowRunResponse.instanceNo());
    return jdbcTemplate.queryForObject(
        selectRequestIdSql, String.class, TENANT, parentWorkflowRunId);
  }
}
