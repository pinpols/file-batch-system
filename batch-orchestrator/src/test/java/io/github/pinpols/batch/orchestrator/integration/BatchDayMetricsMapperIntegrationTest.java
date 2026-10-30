package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.dto.LaunchResponse;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.persistence.entity.TriggerRequestEntity;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.service.batchday.ExpectedWorkRegistrationService;
import io.github.pinpols.batch.orchestrator.application.service.batchday.ExternalEventExpectationService;
import io.github.pinpols.batch.orchestrator.application.service.batchday.FileGroupExpectationService;
import io.github.pinpols.batch.orchestrator.application.service.task.LaunchApplicationService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowNodeTerminalFinalizer;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.param.ExpectedWorkOccurrenceInsertIfAbsentParam;
import io.github.pinpols.batch.orchestrator.domain.param.ExpectedWorkOccurrenceTransitionStatusByIdentityParam;
import io.github.pinpols.batch.orchestrator.domain.view.BatchDayInstanceMetrics;
import io.github.pinpols.batch.orchestrator.infrastructure.scheduler.BatchDaySettleScheduler;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayPlanCalendarMapper;
import io.github.pinpols.batch.orchestrator.mapper.ExpectedWorkOccurrenceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobDependencyMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.TriggerRequestMapper;
import io.github.pinpols.batch.orchestrator.service.BatchDayOperateCommand;
import io.github.pinpols.batch.orchestrator.service.BatchDayOperationService;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("批量日实例指标与终态门禁查询,校验真实数据库上的计数口径与批量日写入默认值")
class BatchDayMetricsMapperIntegrationTest extends AbstractIntegrationTest {

  private static final String RLS_ROLE = "expected_work_rls_test";

  @MockitoSpyBean
  private LaunchApplicationService launchApplicationService;

  @MockitoSpyBean
  private TriggerRequestMapper concurrentTriggerRequestMapper;

  private final DataSource dataSource;

  private final SqlSessionTemplate sqlSessionTemplate;

  private final JdbcTemplate jdbcTemplate;
  private final TransactionTemplate transactionTemplate;
  private final JobInstanceMapper mapper;
  private final BatchDayInstanceMapper batchDayInstanceMapper;
  private final BatchDayPlanCalendarMapper planCalendarMapper;
  private final JobDependencyMapper jobDependencyMapper;
  private final ExpectedWorkOccurrenceMapper expectedWorkOccurrenceMapper;
  private final ExpectedWorkRegistrationService expectedWorkRegistrationService;
  private final FileGroupExpectationService fileGroupExpectationService;
  private final ExternalEventExpectationService externalEventExpectationService;
  private final TriggerRequestMapper triggerRequestMapper;
  private final BatchDaySettleScheduler batchDaySettleScheduler;
  private final WorkflowNodeTerminalFinalizer workflowNodeTerminalFinalizer;
  private final BatchDayOperationService batchDayOperationService;

  @Autowired
  BatchDayMetricsMapperIntegrationTest(
      JdbcTemplate jdbcTemplate,
      JobInstanceMapper mapper,
      BatchDayInstanceMapper batchDayInstanceMapper,
      BatchDayPlanCalendarMapper planCalendarMapper,
      JobDependencyMapper jobDependencyMapper,
      ExpectedWorkOccurrenceMapper expectedWorkOccurrenceMapper,
      ExpectedWorkRegistrationService expectedWorkRegistrationService,
      FileGroupExpectationService fileGroupExpectationService,
      ExternalEventExpectationService externalEventExpectationService,
      TriggerRequestMapper triggerRequestMapper,
      BatchDaySettleScheduler batchDaySettleScheduler,
      WorkflowNodeTerminalFinalizer workflowNodeTerminalFinalizer,
      BatchDayOperationService batchDayOperationService,
      PlatformTransactionManager transactionManager,
      DataSource dataSource,
      SqlSessionTemplate sqlSessionTemplate) {
    this.jdbcTemplate = jdbcTemplate;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
    this.mapper = mapper;
    this.batchDayInstanceMapper = batchDayInstanceMapper;
    this.planCalendarMapper = planCalendarMapper;
    this.jobDependencyMapper = jobDependencyMapper;
    this.expectedWorkOccurrenceMapper = expectedWorkOccurrenceMapper;
    this.expectedWorkRegistrationService = expectedWorkRegistrationService;
    this.fileGroupExpectationService = fileGroupExpectationService;
    this.externalEventExpectationService = externalEventExpectationService;
    this.triggerRequestMapper = triggerRequestMapper;
    this.batchDaySettleScheduler = batchDaySettleScheduler;
    this.workflowNodeTerminalFinalizer = workflowNodeTerminalFinalizer;
    this.batchDayOperationService = batchDayOperationService;
    this.dataSource = dataSource;
    this.sqlSessionTemplate = sqlSessionTemplate;
  }

  @Test
  @DisplayName("两个恢复器同时读取空补跑键时只创建一个根请求")
  void shouldCreateOneCatchUpRootWhenRecoveryScansOverlap() throws Exception {
    final String insertCalendarSql = """
        insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone, cutoff_time, catch_up_policy)
        values (?, ?, 'test', 'UTC', '00:00:00', 'AUTO')
        """;
    final String markDaySql = """
        update batch.batch_day_instance set day_status='FAILED' where tenant_id=? and calendar_code=? and biz_date=?
        """;
    final String setJobCalendarSql = """
        update batch.job_instance set calendar_code=? where tenant_id=? and job_code=?
        """;
    final String countRootsSql = """
        select count(*) from batch.trigger_request where tenant_id=? and dedup_key=?
          and trigger_type='CATCH_UP' and parent_workflow_run_id is null
        """;
    final String disableFixtureCatchUpSql = """
        update batch.business_calendar set catch_up_policy='NONE' where tenant_id=? and calendar_code=?
        """;
    String tenant = unique("race-tenant");
    String calendar = unique("race-calendar");
    String jobCode = unique("race-job");
    LocalDate day = LocalDate.of(2026, 9, 10);
    String dedup = tenant + ":batch-day-catchup:" + calendar + ":" + day + ":" + jobCode;
    CountDownLatch bothRead = new CountDownLatch(2);
    AtomicInteger launchCalls = new AtomicInteger();
    doAnswer(invocation -> {
          Object found = sqlSessionTemplate.selectOne(
              "io.github.pinpols.batch.orchestrator.mapper.TriggerRequestMapper.selectCatchUpRootByDedupKey",
              Map.of("tenantId", invocation.getArgument(0), "dedupKey", invocation.getArgument(1)));
          if (tenant.equals(invocation.getArgument(0)) && dedup.equals(invocation.getArgument(1))) {
            bothRead.countDown();
            if (!bothRead.await(10, TimeUnit.SECONDS))
              throw new IllegalStateException("second scan did not arrive");
          }
          return found;
        })
        .when(concurrentTriggerRequestMapper)
        .selectCatchUpRootByDedupKey(anyString(), anyString());
    doAnswer(invocation -> {
          LaunchRequest request = invocation.getArgument(0);
          if (tenant.equals(request.tenantId())) {
            launchCalls.incrementAndGet();
            return new LaunchResponse("accepted", "trace");
          }
          return invocation.callRealMethod();
        })
        .when(launchApplicationService)
        .launchFromTrustedQueue(any());
    jdbcTemplate.update(insertCalendarSql, tenant, calendar);
    insertOpenBatchDay(tenant, calendar, day);
    jdbcTemplate.update(markDaySql, tenant, calendar, day);
    Long jobDefinitionId = insertJobDefinition(tenant, calendar, jobCode, null);
    insertJobInstance(tenant, jobDefinitionId, jobCode, day, "FAILED", false);
    jdbcTemplate.update(setJobCalendarSql, calendar, tenant, jobCode);
    insertManagedSingleJobWorkflow(tenant, calendar, jobCode, jobDefinitionId);

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<?> first = pool.submit(batchDaySettleScheduler::settle);
      Future<?> second = pool.submit(batchDaySettleScheduler::settle);
      first.get(30, TimeUnit.SECONDS);
      second.get(30, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
      assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
      jdbcTemplate.update(disableFixtureCatchUpSql, tenant, calendar);
    }
    assertThat(jdbcTemplate.queryForObject(countRootsSql, Integer.class, tenant, dedup))
        .isEqualTo(1);
    assertThat(launchCalls.get()).isEqualTo(1);
  }

  @Test
  @DisplayName("失败日提交后启动补跑时原请求已对独立连接可见")
  void shouldCommitCatchUpRequestBeforePostCommitLaunch() {
    final String insertCalendarSql = """
        insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone, cutoff_time, catch_up_policy)
        values (?, ?, 'test', 'UTC', '00:00:00', 'AUTO')
        """;
    final String markDaySql = """
        update batch.batch_day_instance set day_status='IN_FLIGHT' where tenant_id=? and calendar_code=? and biz_date=?
        """;
    final String setJobCalendarSql = """
        update batch.job_instance set calendar_code=? where tenant_id=? and job_code=?
        """;
    final String countRequestSql = """
        select count(*) from batch.trigger_request where tenant_id=? and request_id=?
        """;
    String tenant = unique("commit-tenant");
    String calendar = unique("commit-calendar");
    String jobCode = unique("commit-job");
    LocalDate day = LocalDate.of(2026, 9, 9);
    AtomicInteger firstObservedCount = new AtomicInteger(-1);
    AtomicReference<Object> firstBatchDayStatus = new AtomicReference<>();
    doAnswer(invocation -> {
          LaunchRequest request = invocation.getArgument(0);
          if (!tenant.equals(request.tenantId())) return invocation.callRealMethod();
          firstBatchDayStatus.compareAndSet(null, request.params().get("batchDayStatus"));
          try (Connection connection = dataSource.getConnection();
              PreparedStatement statement = connection.prepareStatement(countRequestSql)) {
            statement.setString(1, tenant);
            statement.setString(2, request.requestId());
            try (var results = statement.executeQuery()) {
              results.next();
              firstObservedCount.compareAndSet(-1, results.getInt(1));
            }
          }
          return new LaunchResponse("accepted", "trace");
        })
        .when(launchApplicationService)
        .launchFromTrustedQueue(any());
    jdbcTemplate.update(insertCalendarSql, tenant, calendar);
    insertOpenBatchDay(tenant, calendar, day);
    jdbcTemplate.update(markDaySql, tenant, calendar, day);
    Long jobDefinitionId = insertJobDefinition(tenant, calendar, jobCode, null);
    insertJobInstance(tenant, jobDefinitionId, jobCode, day, "FAILED", false);
    jdbcTemplate.update(setJobCalendarSql, calendar, tenant, jobCode);
    Long workflowId = insertManagedSingleJobWorkflow(tenant, calendar, jobCode, jobDefinitionId);
    insertWorkflowRun(tenant, workflowId, day, "FAILED", false);

    batchDaySettleScheduler.settle();

    assertThat(firstObservedCount.get()).isEqualTo(1);
    assertThat(firstBatchDayStatus.get()).isEqualTo("SETTLING");
  }

  @Test
  @DisplayName("受管 Workflow 变化后真实数据库中的已接纳补跑仍使用原目标")
  void shouldReusePersistedCatchUpAfterManagedWorkflowChanges() {
    final String insertCalendarSql = """
        insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone, cutoff_time, catch_up_policy)
        values (?, ?, 'test', 'UTC', '00:00:00', 'AUTO')
        """;
    final String markDaySql = """
        update batch.batch_day_instance set day_status='FAILED' where tenant_id=? and calendar_code=? and biz_date=?
        """;
    final String setJobCalendarSql = """
        update batch.job_instance set calendar_code=? where tenant_id=? and job_code=?
        """;
    final String setVersionSql = """
        update batch.workflow_definition set version=2 where tenant_id=? and id=?
        """;
    final String detachWorkflowSql = """
        update batch.workflow_definition set single_job_definition_id=null where tenant_id=? and id=?
        """;
    final String selectStatusSql = """
        select request_status from batch.trigger_request where tenant_id=? and request_id=?
        """;
    final String setCalendarPolicySql = """
        update batch.business_calendar set catch_up_policy=? where tenant_id=? and calendar_code=?
        """;
    final String setRequestStatusSql = """
        update batch.trigger_request set request_status=? where tenant_id=? and request_id=?
        """;
    String tenant = unique("catchup-tenant");
    String calendar = unique("catchup-calendar");
    String jobCode = unique("catchup-job");
    LocalDate day = LocalDate.of(2026, 9, 8);
    String requestId = unique("catchup-request");
    AtomicReference<LaunchRequest> received = new AtomicReference<>();
    doAnswer(invocation -> {
          LaunchRequest request = invocation.getArgument(0);
          if (!tenant.equals(request.tenantId())) {
            return invocation.callRealMethod();
          }
          received.set(request);
          return new LaunchResponse("accepted", "trace");
        })
        .when(launchApplicationService)
        .launchFromTrustedQueue(any());

    jdbcTemplate.update(insertCalendarSql, tenant, calendar);
    insertOpenBatchDay(tenant, calendar, day);
    jdbcTemplate.update(markDaySql, tenant, calendar, day);
    Long jobDefinitionId = insertJobDefinition(tenant, calendar, jobCode, null);
    insertJobInstance(tenant, jobDefinitionId, jobCode, day, "FAILED", false);
    jdbcTemplate.update(setJobCalendarSql, calendar, tenant, jobCode);
    Long originalWorkflow =
        insertManagedSingleJobWorkflow(tenant, calendar, jobCode, jobDefinitionId);
    LaunchRequest frozen = LaunchRequest.builder()
        .tenantId(tenant)
        .requestId(requestId)
        .workflowDefinitionId(originalWorkflow)
        .bizDate(day)
        .triggerType(TriggerType.CATCH_UP)
        .build();
    TriggerRequestEntity accepted = new TriggerRequestEntity();
    accepted.setTenantId(tenant);
    accepted.setRequestId(requestId);
    accepted.setTriggerType("CATCH_UP");
    accepted.setWorkflowDefinitionId(originalWorkflow);
    accepted.setBizDate(day);
    accepted.setDedupKey(tenant + ":batch-day-catchup:" + calendar + ":" + day + ":" + jobCode);
    accepted.setRequestStatus("ACCEPTED");
    accepted.setLaunchPayload(JsonUtils.toJson(frozen));
    assertThat(triggerRequestMapper.insert(accepted)).isEqualTo(1);
    jdbcTemplate.update(detachWorkflowSql, tenant, originalWorkflow);
    Long currentWorkflow =
        insertManagedSingleJobWorkflow(tenant, calendar, jobCode, jobDefinitionId);
    jdbcTemplate.update(setVersionSql, tenant, currentWorkflow);

    assertThat(batchDayInstanceMapper.selectFailedDaysNeedingCatchUp(1000))
        .extracting(BatchDayInstanceEntity::tenantId)
        .contains(tenant);
    jdbcTemplate.update(setCalendarPolicySql, "MANUAL_APPROVAL", tenant, calendar);
    assertThat(batchDayInstanceMapper.selectFailedDaysNeedingCatchUp(1000))
        .extracting(BatchDayInstanceEntity::tenantId)
        .doesNotContain(tenant);
    jdbcTemplate.update(setCalendarPolicySql, "AUTO", tenant, calendar);

    batchDaySettleScheduler.settle();

    assertThat(received.get()).isEqualTo(frozen);
    assertThat(jdbcTemplate.queryForObject(selectStatusSql, String.class, tenant, requestId))
        .isEqualTo("ACCEPTED");
    for (String terminalStatus : List.of("LAUNCHED", "REJECTED", "DUPLICATE", "GIVE_UP")) {
      jdbcTemplate.update(setRequestStatusSql, terminalStatus, tenant, requestId);
      assertThat(batchDayInstanceMapper.selectFailedDaysNeedingCatchUp(1000))
          .extracting(BatchDayInstanceEntity::tenantId)
          .doesNotContain(tenant);
    }
  }

  @Test
  @DisplayName("失败批日补跑恢复按实例冻结日历筛选并排除演练")
  void shouldRecoverFailedDaysByFrozenCalendarWithoutDryRun() {
    final String insertCalendarSql = """
        insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone, cutoff_time, catch_up_policy)
        values (?, ?, 'test', 'UTC', '00:00:00', 'AUTO')
        """;
    final String markDaySql =
        "update batch.batch_day_instance set day_status='FAILED' where tenant_id=? and calendar_code=? and biz_date=?";
    final String setCalendarSql =
        "update batch.job_instance set calendar_code=? where tenant_id=? and job_code=?";
    final String changeDefinitionSql =
        "update batch.job_definition set calendar_code=? where tenant_id=? and id=?";
    String formalTenant = unique("formal");
    String dryTenant = unique("dry");
    String calendar = unique("calendar");
    LocalDate day = LocalDate.of(2026, 9, 8);
    String formalJob = unique("formal-job");
    String dryJob = unique("dry-job");
    jdbcTemplate.update(insertCalendarSql, formalTenant, calendar);
    jdbcTemplate.update(insertCalendarSql, dryTenant, calendar);
    insertOpenBatchDay(formalTenant, calendar, day);
    insertOpenBatchDay(dryTenant, calendar, day);
    jdbcTemplate.update(markDaySql, formalTenant, calendar, day);
    jdbcTemplate.update(markDaySql, dryTenant, calendar, day);
    Long formalDefinition = insertJobDefinition(formalTenant, calendar, formalJob, null);
    Long dryDefinition = insertJobDefinition(dryTenant, calendar, dryJob, null);
    insertJobInstance(formalTenant, formalDefinition, formalJob, day, "FAILED", false);
    insertJobInstance(dryTenant, dryDefinition, dryJob, day, "FAILED", true);
    jdbcTemplate.update(setCalendarSql, calendar, formalTenant, formalJob);
    jdbcTemplate.update(setCalendarSql, calendar, dryTenant, dryJob);
    jdbcTemplate.update(
        changeDefinitionSql, unique("other-calendar"), formalTenant, formalDefinition);

    var days = batchDayInstanceMapper.selectFailedDaysNeedingCatchUp(1000);
    assertThat(days)
        .extracting(dayRow -> dayRow.tenantId())
        .contains(formalTenant)
        .doesNotContain(dryTenant);
  }

  @BeforeAll
  static void grantExpectedWorkRlsTestRole(@Autowired JdbcTemplate jdbcTemplate) {
    final String doStatementSql = """
        DO $$
        BEGIN
          IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'expected_work_rls_test') THEN
            CREATE ROLE expected_work_rls_test NOSUPERUSER NOBYPASSRLS;
          END IF;
        END
        $$
        """;

    jdbcTemplate.execute(doStatementSql);
    final String grantUsageSql = """
        GRANT USAGE ON SCHEMA batch TO %s
    """.formatted(RLS_ROLE);
    jdbcTemplate.execute(grantUsageSql);
    final String grantSelectSql = """
        GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA batch TO %s
    """.formatted(RLS_ROLE);
    jdbcTemplate.execute(grantSelectSql);
    final String grantSequenceUsageSql = """
        GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA batch TO %s
    """.formatted(RLS_ROLE);
    jdbcTemplate.execute(grantSequenceUsageSql);
  }

  @Test
  @DisplayName("按 Workflow 根运行汇总指标,排除试运行并把暂停计入活跃")
  void shouldAggregateWorkflowRunsAndExcludeDryRun_whenAggregatingMetrics() {
    String tenantId = unique("tenant");
    String calendarCode = unique("calendar");
    LocalDate bizDate = LocalDate.of(2026, 9, 8);
    Long workflowDefinitionId = insertWorkflowDefinition(tenantId, calendarCode);

    insertWorkflowRun(tenantId, workflowDefinitionId, bizDate, "SUCCESS", false);
    insertWorkflowRun(tenantId, workflowDefinitionId, bizDate, "FAILED", false);
    insertWorkflowRun(tenantId, workflowDefinitionId, bizDate, "PAUSED", false);
    insertWorkflowRun(tenantId, workflowDefinitionId, bizDate, "SUCCESS_DRY_RUN", true);
    insertWorkflowRun(tenantId, workflowDefinitionId, bizDate, "FAILED_DRY_RUN", true);

    BatchDayInstanceMetrics metrics = mapper.selectBatchDayMetrics(tenantId, calendarCode, bizDate);

    assertThat(metrics.getTotalCount()).isEqualTo(3);
    assertThat(metrics.getSuccessCount()).isEqualTo(1);
    assertThat(metrics.getFailedCount()).isEqualTo(1);
    assertThat(metrics.getActiveCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("数据库拒绝 WorkflowRun 引用另一租户的 WorkflowDefinition")
  void shouldRejectWorkflowRunUsingAnotherTenantsDefinition() {
    final String selectCountSql = """
        select count(*) from batch.workflow_run where tenant_id = ? and biz_date = ?
    """;

    String runTenant = unique("run-tenant");
    String definitionTenant = unique("definition-tenant");
    String foreignCalendarCode = unique("foreign-calendar");
    LocalDate bizDate = LocalDate.of(2026, 10, 17);
    Long foreignDefinitionId = insertWorkflowDefinition(definitionTenant, foreignCalendarCode);

    assertThatThrownBy(
            () -> insertWorkflowRun(runTenant, foreignDefinitionId, bizDate, "RUNNING", false))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("fk_workflow_run_workflow_definition_tenant");
    Integer selectedWorkflowRunCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, runTenant, bizDate);
    assertThat(selectedWorkflowRunCount).isZero();
  }

  @Test
  @DisplayName("数据库拒绝 JobInstance 引用另一租户的 JobDefinition")
  void shouldRejectJobInstanceUsingAnotherTenantsDefinition() {
    final String insertTenantSql = """
        insert into batch.tenant (tenant_id, tenant_name, status) values (?, ?, 'ACTIVE')\s\
        on conflict (tenant_id) do nothing\
        """;
    final String selectCountSql = """
        select count(*) from batch.job_instance where tenant_id = ? and biz_date = ?
    """;

    String runTenant = unique("run-tenant");
    String definitionTenant = unique("definition-tenant");
    String calendarCode = unique("shared-calendar-code");
    String jobCode = unique("foreign-definition-job");
    LocalDate bizDate = LocalDate.of(2026, 10, 17);
    for (String tenantId : List.of(runTenant, definitionTenant)) {
      jdbcTemplate.update(insertTenantSql, tenantId, tenantId);
    }
    Long foreignDefinitionId =
        insertJobDefinition(definitionTenant, calendarCode, jobCode, unique("group"));

    assertThatThrownBy(() -> insertJobInstance(
            runTenant, foreignDefinitionId, jobCode, bizDate, "MANUAL", null, "FAILED", false))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("fk_job_instance_job_definition_tenant");
    Integer selectedJobInstanceCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, runTenant, bizDate);
    assertThat(selectedJobInstanceCount).isZero();
  }

  @Test
  @DisplayName("无 JobInstance 的失败 Workflow 根运行不会被误判为空日")
  void shouldSettleFailedWorkflowDayAsFailed_whenNoJobInstanceExists() {
    final String updateBatchDayInstanceSql = """
        update batch.batch_day_instance\s\
        set day_status = 'CUTOFF', cutoff_at = current_timestamp - interval '1 second' where tenant_id = ?\s\
        and calendar_code = ? and biz_date = ?\
        """;
    final String selectCountSql = """
        select count(*) from batch.job_instance where tenant_id = ? and biz_date = ?
    """;
    final String updateWorkflowRunSql = """
        update batch.workflow_run set run_status = 'FAILED' where tenant_id = ? and workflow_definition_id = ?\s\
        and biz_date = ?\
        """;

    String tenantId = unique("tenant");
    String calendarCode = unique("calendar");
    LocalDate bizDate = LocalDate.of(2026, 9, 10);
    Long workflowDefinitionId = insertWorkflowDefinition(tenantId, calendarCode);
    insertOpenBatchDay(tenantId, calendarCode, bizDate);
    jdbcTemplate.update(updateBatchDayInstanceSql, tenantId, calendarCode, bizDate);
    insertWorkflowRun(tenantId, workflowDefinitionId, bizDate, "RUNNING", false);

    Integer selectedJobInstanceCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, tenantId, bizDate);
    assertThat(selectedJobInstanceCount).isZero();
    batchDaySettleScheduler.settle();
    assertThat(dayStatus(tenantId, calendarCode, bizDate)).isEqualTo("IN_FLIGHT");

    jdbcTemplate.update(updateWorkflowRunSql, tenantId, workflowDefinitionId, bizDate);
    batchDaySettleScheduler.settle();

    assertThat(dayStatus(tenantId, calendarCode, bizDate)).isEqualTo("FAILED");
    Integer selectedJobInstanceCountRead2 =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, tenantId, bizDate);
    assertThat(selectedJobInstanceCountRead2).isZero();
  }

  @Test
  @DisplayName("无 JobInstance 的终止 Workflow 根运行应将批日结算为失败")
  void shouldSettleTerminatedWorkflowDayAsFailed_whenNoJobInstanceExists() {
    final String updateBatchDayInstanceSql = """
        update batch.batch_day_instance\s\
        set day_status = 'CUTOFF', cutoff_at = current_timestamp - interval '1 second' where tenant_id = ?\s\
        and calendar_code = ? and biz_date = ?\
        """;
    final String selectCountSql = """
        select count(*) from batch.job_instance where tenant_id = ? and biz_date = ?
    """;

    String tenantId = unique("tenant");
    String calendarCode = unique("calendar");
    LocalDate bizDate = LocalDate.of(2026, 9, 12);
    Long workflowDefinitionId = insertWorkflowDefinition(tenantId, calendarCode);
    insertOpenBatchDay(tenantId, calendarCode, bizDate);
    jdbcTemplate.update(updateBatchDayInstanceSql, tenantId, calendarCode, bizDate);
    insertWorkflowRun(tenantId, workflowDefinitionId, bizDate, "TERMINATED", false);

    Integer selectedJobInstanceCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, tenantId, bizDate);
    assertThat(selectedJobInstanceCount).isZero();

    batchDaySettleScheduler.settle();

    assertThat(dayStatus(tenantId, calendarCode, bizDate)).isEqualTo("FAILED");
    Integer selectedJobInstanceCountRead2 =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, tenantId, bizDate);
    assertThat(selectedJobInstanceCountRead2).isZero();
  }

  @Test
  @DisplayName("无 JobInstance 的成功 Workflow 根运行应正常结算批日")
  void shouldSettleSuccessfulWorkflowDay_whenNoJobInstanceExists() {
    final String updateBatchDayInstanceSql = """
        update batch.batch_day_instance\s\
        set day_status = 'CUTOFF', cutoff_at = current_timestamp - interval '1 second' where tenant_id = ?\s\
        and calendar_code = ? and biz_date = ?\
        """;
    final String selectCountSql = """
        select count(*) from batch.job_instance where tenant_id = ? and biz_date = ?
    """;

    String tenantId = unique("tenant");
    String calendarCode = unique("calendar");
    LocalDate bizDate = LocalDate.of(2026, 9, 13);
    Long workflowDefinitionId = insertWorkflowDefinition(tenantId, calendarCode);
    insertOpenBatchDay(tenantId, calendarCode, bizDate);
    jdbcTemplate.update(updateBatchDayInstanceSql, tenantId, calendarCode, bizDate);
    insertWorkflowRun(tenantId, workflowDefinitionId, bizDate, "SUCCESS", false);

    Integer selectedJobInstanceCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, tenantId, bizDate);
    assertThat(selectedJobInstanceCount).isZero();

    batchDaySettleScheduler.settle();

    assertThat(dayStatus(tenantId, calendarCode, bizDate)).isEqualTo("SETTLED");
    Integer selectedJobInstanceCountRead2 =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, tenantId, bizDate);
    assertThat(selectedJobInstanceCountRead2).isZero();
  }

  @Test
  @DisplayName("并发结算扫描共享业务日围栏且不会重复推进终态")
  void shouldNotReopenSettledDay_whenConcurrentSettlementScansRace() throws Exception {
    final String updateBatchDayInstanceSql = """
        update batch.batch_day_instance\s\
        set day_status = 'CUTOFF', cutoff_at = current_timestamp - interval '1 second' where tenant_id = ?\s\
        and calendar_code = ? and biz_date = ?\
        """;
    final String selectVersionSql = """
        select version from batch.batch_day_instance where tenant_id = ? and calendar_code = ? and biz_date = ?\
        """;

    String tenantId = unique("tenant");
    String calendarCode = unique("calendar");
    LocalDate bizDate = LocalDate.of(2026, 9, 11);
    Long workflowDefinitionId = insertWorkflowDefinition(tenantId, calendarCode);
    insertOpenBatchDay(tenantId, calendarCode, bizDate);
    jdbcTemplate.update(updateBatchDayInstanceSql, tenantId, calendarCode, bizDate);
    insertWorkflowRun(tenantId, workflowDefinitionId, bizDate, "SUCCESS", false);

    try (Connection fence = jdbcTemplate.getDataSource().getConnection();
        ExecutorService executor = Executors.newFixedThreadPool(2)) {
      fence.setAutoCommit(false);
      acquireSettlementFence(fence, tenantId, calendarCode, bizDate);
      int blockerPid = backendPid(fence);

      Future<?> firstScan = executor.submit(batchDaySettleScheduler::settle);
      awaitBlockedBy(blockerPid);
      Future<?> secondScan = executor.submit(batchDaySettleScheduler::settle);
      awaitBlockedByCount(blockerPid, 2);

      assertThat(dayStatus(tenantId, calendarCode, bizDate)).isEqualTo("CUTOFF");
      fence.commit();
      firstScan.get(10, TimeUnit.SECONDS);
      secondScan.get(10, TimeUnit.SECONDS);
    }

    assertThat(dayStatus(tenantId, calendarCode, bizDate)).isEqualTo("SETTLED");
    Long selectedVersion =
        jdbcTemplate.queryForObject(selectVersionSql, Long.class, tenantId, calendarCode, bizDate);
    assertThat(selectedVersion).isEqualTo(2L);
  }

  private Long insertWorkflowDefinition(String tenantId, String calendarCode) {
    final String insertTenantSql = """
        insert into batch.tenant (tenant_id, tenant_name, status) values (?, ?, 'ACTIVE')\s\
        on conflict (tenant_id) do nothing\
        """;
    final String insertWorkflowDefinitionSql = """
        insert into batch.workflow_definition (tenant_id, workflow_code, workflow_name, calendar_code)\s\
        values (?, ?, ?, ?) returning id\
        """;

    jdbcTemplate.update(insertTenantSql, tenantId, tenantId);
    return jdbcTemplate.queryForObject(
        insertWorkflowDefinitionSql,
        Long.class,
        tenantId,
        unique("workflow"),
        "Batch day metric workflow",
        calendarCode);
  }

  private void insertWorkflowRun(
      String tenantId,
      Long workflowDefinitionId,
      LocalDate bizDate,
      String status,
      boolean dryRun) {
    final String insertWorkflowRunSql = """
            insert into batch.workflow_run (tenant_id, workflow_definition_id, biz_date, run_status, dry_run)\s\
            values (?, ?, ?, ?, ?)\
            """;

    jdbcTemplate.update(
        insertWorkflowRunSql, tenantId, workflowDefinitionId, bizDate, status, dryRun);
  }

  @Test
  @DisplayName("门禁把部分失败与试运行等已终结状态都算作完成,只有暂停实例会同时抬高作业与分组的未终结计数")
  void shouldTreatEveryTerminalStatusAsComplete_whenCountingNonTerminal() {
    String tenantId = unique("tenant");
    String calendarCode = unique("calendar");
    String jobCode = unique("gate-job");
    String jobGroupCode = unique("group");
    LocalDate bizDate = LocalDate.of(2026, 9, 7);
    Long definitionId = insertJobDefinition(tenantId, calendarCode, jobCode, jobGroupCode);

    insertJobInstance(tenantId, definitionId, jobCode, bizDate, "PARTIAL_FAILED", false);
    insertJobInstance(tenantId, definitionId, jobCode, bizDate, "SUCCESS_DRY_RUN", true);
    insertJobInstance(tenantId, definitionId, jobCode, bizDate, "FAILED_DRY_RUN", true);

    assertThat(mapper.countNonTerminalByJobCodeAndBizDate(tenantId, jobCode, bizDate))
        .isZero();
    assertThat(mapper.countNonTerminalByJobGroupAndBizDate(tenantId, jobGroupCode, bizDate))
        .isZero();

    insertJobInstance(tenantId, definitionId, jobCode, bizDate, "PAUSED", false);

    assertThat(mapper.countNonTerminalByJobCodeAndBizDate(tenantId, jobCode, bizDate))
        .isOne();
    assertThat(mapper.countNonTerminalByJobGroupAndBizDate(tenantId, jobGroupCode, bizDate))
        .isOne();
  }

  @Test
  @DisplayName("空日门禁把尚未派发的 Quartz 计划 occurrence 作为待处理工作")
  void shouldCountPlannedScheduledOccurrenceAsBlockingEmptySettlement() {
    final String insertTenantSql = """
        insert into batch.tenant (tenant_id, tenant_name, status) values (?, ?, 'ACTIVE')
    """;
    final String insertWorkflowDefinitionSql = """
        insert into batch.workflow_definition (tenant_id, workflow_code, workflow_name) values (?, ?, ?)\s\
        returning id\
        """;
    final String insertScheduledOccurrenceSql = """
        insert into batch.scheduled_occurrence (tenant_id, workflow_definition_id, workflow_code,\s\
        schedule_version, calendar_code, scheduled_fire_time, biz_date, occurrence_status)\s\
        values (?, ?, ?, 1, ?, ?, ?, 'PLANNED')\
        """;

    String tenantId = unique("tenant");
    String calendarCode = unique("calendar");
    LocalDate bizDate = LocalDate.of(2026, 9, 7);
    jdbcTemplate.update(insertTenantSql, tenantId, tenantId);
    Long workflowDefinitionId = jdbcTemplate.queryForObject(
        insertWorkflowDefinitionSql,
        Long.class,
        tenantId,
        unique("scheduled-workflow"),
        "Scheduled occurrence fixture");
    jdbcTemplate.update(
        insertScheduledOccurrenceSql,
        tenantId,
        workflowDefinitionId,
        unique("scheduled-workflow"),
        calendarCode,
        Timestamp.from(Instant.parse("2026-09-07T09:00:00Z")),
        bizDate);

    assertThat(batchDayInstanceMapper.countBlockingScheduledOccurrences(
            tenantId, calendarCode, bizDate))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("DST cutoff 按 occurrence bizDate 阻塞对应业务日结算")
  void shouldSettleDstOccurrenceAgainstPersistedBusinessDate() {
    final String selectWorkflowCodeSql = """
        select workflow_code from batch.workflow_definition where id = ?
    """;
    final String insertBusinessCalendarSql = """
        insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone, cutoff_time)\s\
        values (?, ?, 'DST calendar', 'America/New_York', '02:30:00')\
        """;
    final String updateBatchDayInstanceSql = """
        update batch.batch_day_instance set day_status = 'CUTOFF', cutoff_at = current_timestamp\s\
        where tenant_id = ? and calendar_code = ? and biz_date = ?\
        """;
    final String insertScheduledOccurrenceSql = """
        insert into batch.scheduled_occurrence (tenant_id, workflow_definition_id, workflow_code,\s\
        schedule_version, calendar_code, scheduled_fire_time, biz_date, occurrence_status)\s\
        values (?, ?, ?, 1, ?, ?, ?, 'PLANNED')\
        """;
    final String updateScheduledOccurrenceSql = """
        update batch.scheduled_occurrence set occurrence_status = 'MISFIRE_SKIPPED' where tenant_id = ?\s\
        and workflow_definition_id = ? and biz_date = ?\
        """;

    String tenantId = unique("tenant");
    String calendarCode = unique("dst-calendar");
    LocalDate bizDate = LocalDate.of(2026, 3, 7);
    Long workflowDefinitionId = insertWorkflowDefinition(tenantId, calendarCode);
    String workflowCode =
        jdbcTemplate.queryForObject(selectWorkflowCodeSql, String.class, workflowDefinitionId);
    jdbcTemplate.update(insertBusinessCalendarSql, tenantId, calendarCode);
    insertOpenBatchDay(tenantId, calendarCode, bizDate);
    jdbcTemplate.update(updateBatchDayInstanceSql, tenantId, calendarCode, bizDate);
    jdbcTemplate.update(
        insertScheduledOccurrenceSql,
        tenantId,
        workflowDefinitionId,
        workflowCode,
        calendarCode,
        Timestamp.from(Instant.parse("2026-03-08T06:59:59Z")),
        bizDate);

    batchDaySettleScheduler.settle();
    assertThat(dayStatus(tenantId, calendarCode, bizDate)).isEqualTo("CUTOFF");

    jdbcTemplate.update(updateScheduledOccurrenceSql, tenantId, workflowDefinitionId, bizDate);
    batchDaySettleScheduler.settle();

    assertThat(dayStatus(tenantId, calendarCode, bizDate)).isEqualTo("SETTLED");
  }

  @Test
  @DisplayName("超期且未到达的预期输入只告警一次并持续阻止空日结算")
  void shouldAlertOnceForOverdueExpectedInputWithoutUnblockingSettlement() {
    final String updateBatchDaySql = """
        update batch.batch_day_instance
           set day_status = 'CUTOFF', cutoff_at = current_timestamp - interval '1 minute'
         where tenant_id = ? and calendar_code = ? and biz_date = ?
        """;
    final String selectMissingAlertClaimSql = """
        select missing_input_alerted_at from batch.expected_work_occurrence
         where tenant_id = ? and calendar_code = ? and biz_date = ? and source_key = ?
        """;
    final String countMissingAlertsSql = """
        select count(*) from batch.alert_event
         where tenant_id = ? and alert_type = 'BATCH_EXPECTED_WORK_MISSING'
        """;
    final String selectOccurrenceStatusSql = """
        select occurrence_status from batch.expected_work_occurrence
         where tenant_id = ? and calendar_code = ? and biz_date = ? and source_key = ?
        """;
    final String selectMissingAlertSql = """
        select alert_type, severity, detail_json::text as detail_json, occurrence_count
          from batch.alert_event
         where tenant_id = ? and alert_type = 'BATCH_EXPECTED_WORK_MISSING'
        """;
    String tenantId = unique("tenant");
    String calendarCode = unique("calendar");
    String jobCode = unique("missing-input-job");
    String sourceKey = unique("missing-file-group");
    LocalDate bizDate = LocalDate.now();
    Long jobDefinitionId = insertJobDefinition(tenantId, calendarCode, jobCode, unique("group"));
    Long workflowDefinitionId =
        insertManagedSingleJobWorkflow(tenantId, calendarCode, jobCode, jobDefinitionId);
    insertOpenBatchDay(tenantId, calendarCode, bizDate);
    jdbcTemplate.update(updateBatchDaySql, tenantId, calendarCode, bizDate);
    Instant dueAt = Instant.now().minusSeconds(60);
    expectedWorkRegistrationService.register(
        new ExpectedWorkRegistrationService.FileGroupExpectation(
            tenantId,
            calendarCode,
            workflowDefinitionId,
            sourceKey,
            bizDate,
            dueAt.minusSeconds(300),
            dueAt));

    final String createAlertFailureFunctionSql = """
        create function batch.reject_missing_expected_alert_for_test() returns trigger
        language plpgsql as $$
        begin
          if new.alert_type = 'BATCH_EXPECTED_WORK_MISSING' then
            raise exception 'test missing-input alert persistence failure';
          end if;
          return new;
        end
        $$
        """;
    final String createAlertFailureTriggerSql = """
        create trigger reject_missing_expected_alert_for_test
        before insert on batch.alert_event
        for each row execute function batch.reject_missing_expected_alert_for_test()
        """;
    final String dropAlertFailureTriggerSql = """
        drop trigger reject_missing_expected_alert_for_test on batch.alert_event
        """;
    final String dropAlertFailureFunctionSql = """
        drop function batch.reject_missing_expected_alert_for_test()
        """;
    jdbcTemplate.execute(createAlertFailureFunctionSql);
    jdbcTemplate.execute(createAlertFailureTriggerSql);
    try {
      assertThatThrownBy(batchDaySettleScheduler::settle)
          .isInstanceOf(RuntimeException.class)
          .hasMessageContaining("test missing-input alert persistence failure");
      Object selectedMissingInputAlertedAt = jdbcTemplate.queryForObject(
          selectMissingAlertClaimSql, Object.class, tenantId, calendarCode, bizDate, sourceKey);
      assertThat(selectedMissingInputAlertedAt).isNull();
      Integer selectedAlertEventCount =
          jdbcTemplate.queryForObject(countMissingAlertsSql, Integer.class, tenantId);
      assertThat(selectedAlertEventCount).isZero();
    } finally {
      jdbcTemplate.execute(dropAlertFailureTriggerSql);
      jdbcTemplate.execute(dropAlertFailureFunctionSql);
    }

    batchDaySettleScheduler.settle();
    batchDaySettleScheduler.settle();

    assertThat(dayStatus(tenantId, calendarCode, bizDate)).isEqualTo("CUTOFF");
    String selectedOccurrenceStatus = jdbcTemplate.queryForObject(
        selectOccurrenceStatusSql, String.class, tenantId, calendarCode, bizDate, sourceKey);
    assertThat(selectedOccurrenceStatus).isEqualTo("EXPECTED");
    Map<String, Object> alert = jdbcTemplate.queryForMap(selectMissingAlertSql, tenantId);
    assertThat(alert)
        .containsEntry("alert_type", "BATCH_EXPECTED_WORK_MISSING")
        .containsEntry("severity", "ERROR")
        .containsEntry("occurrence_count", 1);
    assertThat(String.valueOf(alert.get("detail_json")))
        .contains(workflowDefinitionId.toString(), sourceKey, bizDate.toString(), "FILE_GROUP");
  }

  @Test
  @DisplayName("生产结算器在计划 occurrence 和文件/事件预期未终结时不结空日")
  void shouldKeepEmptyDayOpenUntilAllExpectedWorkAndOccurrenceAreTerminal() {
    final String updateWorkflowDefinitionSql = """
        update batch.workflow_definition set trigger_mode = 'SCHEDULED', schedule_enabled = false\s\
        where tenant_id = ? and id = ?\
        """;
    final String updateBatchDayInstanceSql = """
        update batch.batch_day_instance set day_status = 'CUTOFF' where tenant_id = ? and calendar_code = ?\s\
        and biz_date = ?\
        """;
    final String insertScheduledOccurrenceSql = """
        insert into batch.scheduled_occurrence (tenant_id, workflow_definition_id, workflow_code,\s\
        schedule_version, calendar_code, scheduled_fire_time, biz_date, occurrence_status)\s\
        values (?, ?, ?, 1, ?, ?, ?, 'MISFIRE_PENDING')\
        """;
    final String insertExpectedWorkOccurrenceSql = """
      insert into batch.expected_work_occurrence (tenant_id, calendar_code, biz_date, workflow_definition_id,\s\
      source_type, source_key, occurrence_status)\s\
      values (?, ?, ?, ?, 'FILE_GROUP', ?, 'ARRIVED'), (?, ?, ?, ?, 'EXTERNAL_EVENT', ?, 'EXPECTED')\
      """;
    final String updateScheduledOccurrenceSql = """
      update batch.scheduled_occurrence set occurrence_status = 'MISFIRE_SKIPPED' where tenant_id = ?\s\
      and workflow_definition_id = ? and biz_date = ?\
      """;
    final String updateExpectedWorkOccurrenceSql = """
      update batch.expected_work_occurrence set occurrence_status = 'SATISFIED' where tenant_id = ?\s\
      and calendar_code = ? and biz_date = ? and source_type = 'FILE_GROUP'\
      """;
    final String updateExpectedWorkOccurrenceSql2 = """
      update batch.expected_work_occurrence set occurrence_status = 'SATISFIED' where tenant_id = ?\s\
      and calendar_code = ? and biz_date = ? and source_type = 'EXTERNAL_EVENT'\
      """;

    String tenantId = unique("tenant");
    String calendarCode = unique("calendar");
    String jobCode = unique("scheduled-gate-job");
    LocalDate bizDate = LocalDate.of(2026, 9, 13);
    Long jobDefinitionId = insertJobDefinition(tenantId, calendarCode, jobCode, unique("group"));
    Long workflowDefinitionId =
        insertManagedSingleJobWorkflow(tenantId, calendarCode, jobCode, jobDefinitionId);
    jdbcTemplate.update(updateWorkflowDefinitionSql, tenantId, workflowDefinitionId);
    insertOpenBatchDay(tenantId, calendarCode, bizDate);
    jdbcTemplate.update(updateBatchDayInstanceSql, tenantId, calendarCode, bizDate);
    jdbcTemplate.update(
        insertScheduledOccurrenceSql,
        tenantId,
        workflowDefinitionId,
        unique("scheduled-workflow"),
        calendarCode,
        Timestamp.from(Instant.parse("2026-09-13T09:00:00Z")),
        bizDate);
    jdbcTemplate.update(
        insertExpectedWorkOccurrenceSql,
        tenantId,
        calendarCode,
        bizDate,
        workflowDefinitionId,
        unique("file-group"),
        tenantId,
        calendarCode,
        bizDate,
        workflowDefinitionId,
        unique("event-code"));

    batchDaySettleScheduler.settle();
    assertThat(dayStatus(tenantId, calendarCode, bizDate)).isEqualTo("CUTOFF");

    jdbcTemplate.update(updateScheduledOccurrenceSql, tenantId, workflowDefinitionId, bizDate);
    batchDaySettleScheduler.settle();
    assertThat(dayStatus(tenantId, calendarCode, bizDate)).isEqualTo("CUTOFF");

    jdbcTemplate.update(updateExpectedWorkOccurrenceSql, tenantId, calendarCode, bizDate);
    batchDaySettleScheduler.settle();
    assertThat(dayStatus(tenantId, calendarCode, bizDate)).isEqualTo("CUTOFF");

    jdbcTemplate.update(updateExpectedWorkOccurrenceSql2, tenantId, calendarCode, bizDate);
    batchDaySettleScheduler.settle();
    assertThat(dayStatus(tenantId, calendarCode, bizDate)).isEqualTo("SETTLED");
  }

  @Test
  @DisplayName("REQUESTED occurrence 在根 WorkflowRun 建立前阻塞,建立后由根运行指标接管")
  void shouldTransferRequestedOccurrenceToWorkflowRunSettlement() {
    final String selectWorkflowCodeSql = """
        select workflow_code from batch.workflow_definition where id = ?
    """;
    final String insertTriggerRequestSql = """
        insert into batch.trigger_request (tenant_id, request_id, trigger_type, workflow_definition_id,\s\
        biz_date, dedup_key, request_status) values (?, ?, 'SCHEDULED', ?, ?, ?, 'ACCEPTED') returning id\
        """;
    final String insertScheduledOccurrenceSql = """
        insert into batch.scheduled_occurrence (tenant_id, workflow_definition_id, workflow_code,\s\
        schedule_version, calendar_code, scheduled_fire_time, biz_date, occurrence_status, request_id)\s\
        values (?, ?, ?, 1, ?, ?, ?, 'REQUESTED', ?)\
        """;
    final String updateWorkflowRunSql = """
        update batch.workflow_run set trigger_request_id = ? where tenant_id = ? and workflow_definition_id = ?\s\
        and biz_date = ?\
        """;

    String tenantId = unique("tenant");
    String calendarCode = unique("calendar");
    LocalDate bizDate = LocalDate.of(2026, 9, 7);
    Long workflowDefinitionId = insertWorkflowDefinition(tenantId, calendarCode);
    String workflowCode =
        jdbcTemplate.queryForObject(selectWorkflowCodeSql, String.class, workflowDefinitionId);
    String requestId = unique("request");
    Long triggerRequestId = jdbcTemplate.queryForObject(
        insertTriggerRequestSql,
        Long.class,
        tenantId,
        requestId,
        workflowDefinitionId,
        bizDate,
        unique("dedup"));
    jdbcTemplate.update(
        insertScheduledOccurrenceSql,
        tenantId,
        workflowDefinitionId,
        workflowCode,
        calendarCode,
        Timestamp.from(Instant.parse("2026-09-07T09:00:00Z")),
        bizDate,
        requestId);

    assertThat(batchDayInstanceMapper.countBlockingScheduledOccurrences(
            tenantId, calendarCode, bizDate))
        .isOne();

    insertWorkflowRun(tenantId, workflowDefinitionId, bizDate, "RUNNING", false);
    jdbcTemplate.update(
        updateWorkflowRunSql, triggerRequestId, tenantId, workflowDefinitionId, bizDate);

    assertThat(batchDayInstanceMapper.countBlockingScheduledOccurrences(
            tenantId, calendarCode, bizDate))
        .isZero();
    BatchDayInstanceMetrics metrics = mapper.selectBatchDayMetrics(tenantId, calendarCode, bizDate);
    assertThat(metrics.getTotalCount()).isOne();
    assertThat(metrics.getActiveCount()).isOne();
  }

  @Test
  @DisplayName("同一租户日历业务日的结算围栏按 PostgreSQL 事务锁串行化")
  void shouldSerializeSettlementFenceForSameBusinessDay() throws Exception {
    final String selectPgBackendPidSql = """
        select pg_backend_pid()
    """;

    final String selectWaitEventTypeSql = """
        select wait_event_type = 'Lock' from pg_stat_activity where pid = ?
    """;

    String tenantId = unique("tenant");
    String calendarCode = unique("calendar");
    LocalDate bizDate = LocalDate.of(2026, 9, 7);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try (Connection owner = jdbcTemplate.getDataSource().getConnection();
        Connection contender = jdbcTemplate.getDataSource().getConnection()) {
      owner.setAutoCommit(false);
      contender.setAutoCommit(false);
      long ownerKey = acquireFence(owner, tenantId, calendarCode, bizDate);
      int contenderPid;
      try (var statement = contender.createStatement();
          var result = statement.executeQuery(selectPgBackendPidSql)) {
        result.next();
        contenderPid = result.getInt(1);
      }
      Future<Long> waiting =
          executor.submit(() -> acquireFence(contender, tenantId, calendarCode, bizDate));

      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      boolean waitingOnLock = false;
      while (System.nanoTime() < deadline) {
        Boolean lockWait =
            jdbcTemplate.queryForObject(selectWaitEventTypeSql, Boolean.class, contenderPid);
        if (Boolean.TRUE.equals(lockWait)) {
          waitingOnLock = true;
          break;
        }
        Thread.sleep(10);
      }
      assertThat(waitingOnLock).isTrue();
      assertThat(waiting.isDone()).isFalse();

      owner.commit();
      assertThat(waiting.get(5, TimeUnit.SECONDS)).isEqualTo(ownerKey);
      contender.commit();
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  @DisplayName("持久化中的 Workflow launch wait 继续阻止空日结算")
  void shouldKeepCutoffOpenWhileWorkflowLaunchWaitIsPending() {
    final String updateBatchDayInstanceSql = """
        update batch.batch_day_instance set day_status = 'CUTOFF' where tenant_id = ? and calendar_code = ?\s\
        and biz_date = ?\
        """;
    final String insertTriggerRequestSql = """
        insert into batch.trigger_request (tenant_id, request_id, trigger_type, workflow_definition_id,\s\
        biz_date, dedup_key, request_status) values (?, ?, 'CATCH_UP', ?, ?, ?, 'PROCESSING')\
        """;
    final String insertWorkflowLaunchWaitSql = """
        insert into batch.workflow_launch_wait (tenant_id, request_id, workflow_definition_id, biz_date,\s\
        dry_run, launch_payload) values (?, ?, ?, ?, false, '{}'::jsonb)\
        """;

    String tenantId = unique("tenant");
    String calendarCode = unique("calendar");
    LocalDate bizDate = LocalDate.of(2026, 9, 14);
    Long workflowDefinitionId = insertWorkflowDefinition(tenantId, calendarCode);
    String requestId = unique("waiting-request");
    insertOpenBatchDay(tenantId, calendarCode, bizDate);
    jdbcTemplate.update(updateBatchDayInstanceSql, tenantId, calendarCode, bizDate);
    jdbcTemplate.update(
        insertTriggerRequestSql,
        tenantId,
        requestId,
        workflowDefinitionId,
        bizDate,
        unique("dedup"));
    jdbcTemplate.update(
        insertWorkflowLaunchWaitSql, tenantId, requestId, workflowDefinitionId, bizDate);

    batchDaySettleScheduler.settle();

    assertThat(dayStatus(tenantId, calendarCode, bizDate)).isEqualTo("CUTOFF");
    assertThat(triggerRequestMapper.countUnlaunchedForCalendarBizDate(
            tenantId, calendarCode, bizDate, List.of("PROCESSING")))
        .isOne();
  }

  @Test
  @DisplayName("readiness 超时已放弃本次触发,不应永久阻塞空日结算")
  void shouldSettleEmptyDayAfterScheduledOccurrenceReadinessTimeout() {
    final String selectWorkflowCodeSql = """
        select workflow_code from batch.workflow_definition where id = ?
    """;
    final String updateBatchDayInstanceSql = """
        update batch.batch_day_instance set day_status = 'CUTOFF' where tenant_id = ? and calendar_code = ?\s\
        and biz_date = ?\
        """;
    final String insertScheduledOccurrenceSql = """
        insert into batch.scheduled_occurrence (tenant_id, workflow_definition_id, workflow_code,\s\
        schedule_version, calendar_code, scheduled_fire_time, biz_date, occurrence_status)\s\
        values (?, ?, ?, 1, ?, ?, ?, 'READINESS_TIMEOUT')\
        """;

    String tenantId = unique("tenant");
    String calendarCode = unique("calendar");
    LocalDate bizDate = LocalDate.of(2026, 9, 14);
    Long workflowDefinitionId = insertWorkflowDefinition(tenantId, calendarCode);
    String workflowCode =
        jdbcTemplate.queryForObject(selectWorkflowCodeSql, String.class, workflowDefinitionId);
    insertOpenBatchDay(tenantId, calendarCode, bizDate);
    jdbcTemplate.update(updateBatchDayInstanceSql, tenantId, calendarCode, bizDate);
    jdbcTemplate.update(
        insertScheduledOccurrenceSql,
        tenantId,
        workflowDefinitionId,
        workflowCode,
        calendarCode,
        Timestamp.from(Instant.parse("2026-09-14T09:00:00Z")),
        bizDate);

    batchDaySettleScheduler.settle();

    assertThat(dayStatus(tenantId, calendarCode, bizDate)).isEqualTo("SETTLED");
  }

  @Test
  @DisplayName("空日结算等待定时 Workflow 的 occurrence coverage 覆盖到 cutoff")
  void shouldKeepEmptyDayOpenUntilScheduleCoverageReachesCutoff() {
    final String insertTenantSql = """
        insert into batch.tenant (tenant_id, tenant_name, status) values (?, ?, 'ACTIVE')
    """;
    final String insertBusinessCalendarSql = """
        insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone,\s\
        holiday_roll_rule) values (?, ?, 'Coverage Calendar', 'UTC', 'SKIP')\
        """;
    final String insertWorkflowDefinitionSql = """
        insert into batch.workflow_definition (tenant_id, workflow_code, workflow_name, workflow_type, version,\s\
        schedule_type, schedule_expr, timezone, trigger_mode, calendar_code, schedule_enabled)\s\
        values (?, ?, 'Coverage Workflow', 'DAG', 1, 'CRON', '0 0 2 * * ?', 'UTC', 'SCHEDULED', ?, true)\s\
        returning id\
        """;
    final String updateBatchDayInstanceSql = """
        update batch.batch_day_instance set day_status = 'CUTOFF', cutoff_at = ? where tenant_id = ?\s\
        and calendar_code = ? and biz_date = ?\
        """;
    final String insertScheduledOccurrenceCoverageSql = """
        insert into batch.scheduled_occurrence_coverage (tenant_id, workflow_definition_id, schedule_version,\s\
        calendar_code, coverage_from_at, coverage_through_at) values (?, ?, 1, ?, ?, ?)\
        """;

    String tenantId = unique("tenant");
    String calendarCode = unique("calendar");
    String workflowCode = unique("scheduled-workflow");
    LocalDate bizDate = LocalDate.of(2026, 9, 15);
    Instant cutoff = Instant.parse("2026-09-15T08:00:00Z");
    jdbcTemplate.update(insertTenantSql, tenantId, tenantId);
    jdbcTemplate.update(insertBusinessCalendarSql, tenantId, calendarCode);
    Long workflowDefinitionId = jdbcTemplate.queryForObject(
        insertWorkflowDefinitionSql, Long.class, tenantId, workflowCode, calendarCode);
    insertOpenBatchDay(tenantId, calendarCode, bizDate);
    jdbcTemplate.update(
        updateBatchDayInstanceSql, Timestamp.from(cutoff), tenantId, calendarCode, bizDate);

    batchDaySettleScheduler.settle();
    assertThat(dayStatus(tenantId, calendarCode, bizDate)).isEqualTo("CUTOFF");

    jdbcTemplate.update(
        insertScheduledOccurrenceCoverageSql,
        tenantId,
        workflowDefinitionId,
        calendarCode,
        Timestamp.from(cutoff.minusSeconds(3600)),
        Timestamp.from(cutoff.plusSeconds(1)));
    batchDaySettleScheduler.settle();

    assertThat(dayStatus(tenantId, calendarCode, bizDate)).isEqualTo("SETTLED");
  }

  @Test
  @DisplayName("计划账本与 coverage 尚未提交时不把定时日误结算为空日")
  void shouldNotSettleEmptyDayWhileOccurrenceCoveragePublicationIsUncommitted() throws Exception {
    final String insertScheduledOccurrenceSql = """
        insert into batch.scheduled_occurrence (tenant_id, workflow_definition_id, workflow_code,\s\
        schedule_version, calendar_code, scheduled_fire_time, biz_date, occurrence_status)\s\
        values (?, ?, ?, 1, ?, ?, ?, 'PLANNED')\
        """;
    final String insertScheduledOccurrenceCoverageSql = """
        insert into batch.scheduled_occurrence_coverage (tenant_id, workflow_definition_id, schedule_version,\s\
        calendar_code, coverage_from_at, coverage_through_at, last_success_at)\s\
        values (?, ?, 1, ?, ?, ?, current_timestamp)\
        """;

    final String insertTenantSql = """
        insert into batch.tenant (tenant_id, tenant_name, status) values (?, ?, 'ACTIVE')
    """;
    final String insertBusinessCalendarSql = """
        insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone,\s\
        holiday_roll_rule) values (?, ?, 'Coverage Race Calendar', 'UTC', 'SKIP')\
        """;
    final String insertWorkflowDefinitionSql = """
        insert into batch.workflow_definition (tenant_id, workflow_code, workflow_name, workflow_type, version,\s\
        schedule_type, schedule_expr, timezone, trigger_mode, calendar_code, schedule_enabled)\s\
        values (?, ?, 'Coverage Race Workflow', 'DAG', 1, 'CRON', '0 30 7 * * ?', 'UTC', 'SCHEDULED', ?, true)\s\
        returning id\
        """;
    final String updateBatchDayInstanceSql = """
        update batch.batch_day_instance set day_status = 'CUTOFF', cutoff_at = ? where tenant_id = ?\s\
        and calendar_code = ? and biz_date = ?\
        """;
    final String selectCountSql = """
        select count(*) from batch.scheduled_occurrence where tenant_id = ? and workflow_definition_id = ?\s\
        and biz_date = ? and occurrence_status = 'PLANNED'\
        """;
    final String updateScheduledOccurrenceSql = """
        update batch.scheduled_occurrence set occurrence_status = 'READINESS_TIMEOUT' where tenant_id = ?\s\
        and workflow_definition_id = ? and biz_date = ?\
        """;
    final String updateBusinessCalendarSql = """
        update batch.business_calendar set cutoff_time = '05:00:00' where tenant_id = ? and calendar_code = ?\
        """;
    final String updateScheduledOccurrenceCoverageSql = """
        update batch.scheduled_occurrence_coverage set last_success_at = clock_timestamp() where tenant_id = ?\s\
        and workflow_definition_id = ?\
        """;

    String tenantId = unique("tenant");
    String calendarCode = unique("calendar");
    String workflowCode = unique("scheduled-workflow");
    LocalDate bizDate = LocalDate.of(2026, 9, 15);
    Instant cutoff = Instant.parse("2026-09-15T08:00:00Z");
    Instant fireTime = Instant.parse("2026-09-15T07:30:00Z");
    jdbcTemplate.update(insertTenantSql, tenantId, tenantId);
    jdbcTemplate.update(insertBusinessCalendarSql, tenantId, calendarCode);
    Long workflowDefinitionId = jdbcTemplate.queryForObject(
        insertWorkflowDefinitionSql, Long.class, tenantId, workflowCode, calendarCode);
    insertOpenBatchDay(tenantId, calendarCode, bizDate);
    jdbcTemplate.update(
        updateBatchDayInstanceSql, Timestamp.from(cutoff), tenantId, calendarCode, bizDate);

    try (Connection publication = jdbcTemplate.getDataSource().getConnection()) {
      publication.setAutoCommit(false);
      try (PreparedStatement occurrence =
              publication.prepareStatement(insertScheduledOccurrenceSql);
          PreparedStatement coverage =
              publication.prepareStatement(insertScheduledOccurrenceCoverageSql)) {
        occurrence.setString(1, tenantId);
        occurrence.setLong(2, workflowDefinitionId);
        occurrence.setString(3, workflowCode);
        occurrence.setString(4, calendarCode);
        occurrence.setTimestamp(5, Timestamp.from(fireTime));
        occurrence.setObject(6, bizDate);
        assertThat(occurrence.executeUpdate()).isOne();

        coverage.setString(1, tenantId);
        coverage.setLong(2, workflowDefinitionId);
        coverage.setString(3, calendarCode);
        coverage.setTimestamp(4, Timestamp.from(cutoff.minusSeconds(3600)));
        coverage.setTimestamp(5, Timestamp.from(cutoff.plusSeconds(1)));
        assertThat(coverage.executeUpdate()).isOne();

        batchDaySettleScheduler.settle();
        assertThat(dayStatus(tenantId, calendarCode, bizDate)).isEqualTo("CUTOFF");
      }
      publication.commit();
    }

    batchDaySettleScheduler.settle();
    assertThat(dayStatus(tenantId, calendarCode, bizDate)).isNotEqualTo("SETTLED");
    Integer selectedScheduledOccurrenceCount = jdbcTemplate.queryForObject(
        selectCountSql, Integer.class, tenantId, workflowDefinitionId, bizDate);
    assertThat(selectedScheduledOccurrenceCount).isOne();

    jdbcTemplate.update(updateScheduledOccurrenceSql, tenantId, workflowDefinitionId, bizDate);

    jdbcTemplate.update(updateBusinessCalendarSql, tenantId, calendarCode);
    batchDaySettleScheduler.settle();
    assertThat(dayStatus(tenantId, calendarCode, bizDate)).isEqualTo("CUTOFF");

    jdbcTemplate.update(updateScheduledOccurrenceCoverageSql, tenantId, workflowDefinitionId);
    batchDaySettleScheduler.settle();
    assertThat(dayStatus(tenantId, calendarCode, bizDate)).isEqualTo("SETTLED");
  }

  @Test
  @DisplayName("REQUESTED occurrence 将结算阻塞责任交给根 WorkflowRun")
  void shouldTransferRequestedOccurrenceToRootRunSettlement() {
    final String selectWorkflowCodeSql = """
        select workflow_code from batch.workflow_definition where id = ?
    """;
    final String insertTriggerRequestSql = """
        insert into batch.trigger_request (tenant_id, request_id, trigger_type, workflow_definition_id,\s\
        biz_date, dedup_key, request_status) values (?, ?, 'SCHEDULED', ?, ?, ?, 'ACCEPTED') returning id\
        """;
    final String insertScheduledOccurrenceSql = """
        insert into batch.scheduled_occurrence (tenant_id, workflow_definition_id, workflow_code,\s\
        schedule_version, calendar_code, scheduled_fire_time, biz_date, occurrence_status, request_id)\s\
        values (?, ?, ?, 1, ?, ?, ?, 'REQUESTED', ?)\
        """;
    final String updateBatchDayInstanceSql = """
      update batch.batch_day_instance set day_status = 'CUTOFF' where tenant_id = ? and calendar_code = ?\s\
      and biz_date = ?\
      """;
    final String updateWorkflowRunSql = """
      update batch.workflow_run set trigger_request_id = ? where tenant_id = ? and workflow_definition_id = ?\s\
      and biz_date = ?\
      """;
    final String updateTriggerRequestSql = """
      update batch.trigger_request set request_status = 'LAUNCHED' where tenant_id = ? and id = ?\
      """;
    final String updateWorkflowRunSql2 = """
      update batch.workflow_run set run_status = 'SUCCESS' where tenant_id = ? and workflow_definition_id = ?\s\
      and biz_date = ?\
      """;

    String tenantId = unique("tenant");
    String calendarCode = unique("calendar");
    LocalDate bizDate = LocalDate.of(2026, 9, 16);
    Long workflowDefinitionId = insertWorkflowDefinition(tenantId, calendarCode);
    String workflowCode =
        jdbcTemplate.queryForObject(selectWorkflowCodeSql, String.class, workflowDefinitionId);
    String requestId = unique("request");
    Long triggerRequestId = jdbcTemplate.queryForObject(
        insertTriggerRequestSql,
        Long.class,
        tenantId,
        requestId,
        workflowDefinitionId,
        bizDate,
        unique("dedup"));
    jdbcTemplate.update(
        insertScheduledOccurrenceSql,
        tenantId,
        workflowDefinitionId,
        workflowCode,
        calendarCode,
        Timestamp.from(Instant.parse("2026-09-16T09:00:00Z")),
        bizDate,
        requestId);
    insertOpenBatchDay(tenantId, calendarCode, bizDate);
    jdbcTemplate.update(updateBatchDayInstanceSql, tenantId, calendarCode, bizDate);

    batchDaySettleScheduler.settle();
    assertThat(dayStatus(tenantId, calendarCode, bizDate)).isEqualTo("CUTOFF");

    insertWorkflowRun(tenantId, workflowDefinitionId, bizDate, "RUNNING", false);
    jdbcTemplate.update(
        updateWorkflowRunSql, triggerRequestId, tenantId, workflowDefinitionId, bizDate);
    jdbcTemplate.update(updateTriggerRequestSql, tenantId, triggerRequestId);
    batchDaySettleScheduler.settle();
    assertThat(dayStatus(tenantId, calendarCode, bizDate)).isEqualTo("IN_FLIGHT");

    jdbcTemplate.update(updateWorkflowRunSql2, tenantId, workflowDefinitionId, bizDate);
    batchDaySettleScheduler.settle();
    assertThat(dayStatus(tenantId, calendarCode, bizDate)).isEqualTo("SETTLED");
  }

  @Test
  @DisplayName("成功 dry-run 根运行不能接管正式定时 occurrence 义务")
  void shouldNotTransferRequestedOccurrenceToDryRunWorkflowRun() {
    final String selectWorkflowCodeSql = """
        select workflow_code from batch.workflow_definition where id = ?
    """;
    final String insertTriggerRequestSql = """
        insert into batch.trigger_request (tenant_id, request_id, trigger_type, workflow_definition_id,\s\
        biz_date, dedup_key, request_status) values (?, ?, 'SCHEDULED', ?, ?, ?, 'LAUNCHED') returning id\
        """;
    final String insertScheduledOccurrenceSql = """
        insert into batch.scheduled_occurrence (tenant_id, workflow_definition_id, workflow_code,\s\
        schedule_version, calendar_code, scheduled_fire_time, biz_date, occurrence_status, request_id)\s\
        values (?, ?, ?, 1, ?, ?, ?, 'REQUESTED', ?)\
        """;
    final String updateBatchDayInstanceSql = """
      update batch.batch_day_instance set day_status = 'CUTOFF' where tenant_id = ? and calendar_code = ?\s\
      and biz_date = ?\
      """;
    final String insertWorkflowRunSql = """
      insert into batch.workflow_run (tenant_id, workflow_definition_id, biz_date, run_status, dry_run,\s\
      trigger_request_id) values (?, ?, ?, 'SUCCESS_DRY_RUN', true, ?)\
      """;
    final String updateScheduledOccurrenceSql = """
      update batch.scheduled_occurrence set request_id = ? where tenant_id = ? and workflow_definition_id = ?\s\
      and biz_date = ?\
      """;
    final String insertWorkflowRunSql2 = """
      insert into batch.workflow_run (tenant_id, workflow_definition_id, biz_date, run_status, dry_run,\s\
      trigger_request_id) values (?, ?, ?, 'RUNNING', false, ?)\
      """;
    final String updateWorkflowRunSql = """
        update batch.workflow_run set run_status = 'SUCCESS' where tenant_id = ? and trigger_request_id = ?\
        """;

    String tenantId = unique("tenant");
    String calendarCode = unique("calendar");
    LocalDate bizDate = LocalDate.of(2026, 9, 21);
    Long workflowDefinitionId = insertWorkflowDefinition(tenantId, calendarCode);
    String workflowCode =
        jdbcTemplate.queryForObject(selectWorkflowCodeSql, String.class, workflowDefinitionId);
    String requestId = unique("scheduled-request");
    Long triggerRequestId = jdbcTemplate.queryForObject(
        insertTriggerRequestSql,
        Long.class,
        tenantId,
        requestId,
        workflowDefinitionId,
        bizDate,
        unique("dedup"));
    jdbcTemplate.update(
        insertScheduledOccurrenceSql,
        tenantId,
        workflowDefinitionId,
        workflowCode,
        calendarCode,
        Timestamp.from(Instant.parse("2026-09-21T09:00:00Z")),
        bizDate,
        requestId);
    insertOpenBatchDay(tenantId, calendarCode, bizDate);
    jdbcTemplate.update(updateBatchDayInstanceSql, tenantId, calendarCode, bizDate);
    jdbcTemplate.update(
        insertWorkflowRunSql, tenantId, workflowDefinitionId, bizDate, triggerRequestId);

    batchDaySettleScheduler.settle();

    assertThat(batchDayInstanceMapper.countBlockingScheduledOccurrences(
            tenantId, calendarCode, bizDate))
        .isOne();
    assertThat(dayStatus(tenantId, calendarCode, bizDate)).isEqualTo("CUTOFF");

    String formalRequestId = unique("formal-request");
    Long formalTriggerRequestId = jdbcTemplate.queryForObject(
        insertTriggerRequestSql,
        Long.class,
        tenantId,
        formalRequestId,
        workflowDefinitionId,
        bizDate,
        unique("formal-dedup"));
    jdbcTemplate.update(
        updateScheduledOccurrenceSql, formalRequestId, tenantId, workflowDefinitionId, bizDate);
    jdbcTemplate.update(
        insertWorkflowRunSql2, tenantId, workflowDefinitionId, bizDate, formalTriggerRequestId);

    batchDaySettleScheduler.settle();

    assertThat(batchDayInstanceMapper.countBlockingScheduledOccurrences(
            tenantId, calendarCode, bizDate))
        .isZero();
    assertThat(dayStatus(tenantId, calendarCode, bizDate)).isEqualTo("IN_FLIGHT");

    jdbcTemplate.update(updateWorkflowRunSql, tenantId, formalTriggerRequestId);
    batchDaySettleScheduler.settle();
    assertThat(dayStatus(tenantId, calendarCode, bizDate)).isEqualTo("SETTLED");
  }

  @Test
  @DisplayName("dry-run 成功与结算扫描竞争时仍保留正式 occurrence blocker")
  void shouldKeepFormalOccurrenceBlocking_whenDryRunCommitsDuringSettlementWait() throws Exception {
    final String insertWorkflowRunSql = """
        insert into batch.workflow_run (tenant_id, workflow_definition_id, biz_date, run_status, dry_run,\s\
        trigger_request_id) values (?, ?, ?, 'SUCCESS_DRY_RUN', true, ?)\
        """;

    final String selectWorkflowCodeSql = """
        select workflow_code from batch.workflow_definition where id = ?
    """;
    final String insertTriggerRequestSql = """
        insert into batch.trigger_request (tenant_id, request_id, trigger_type, workflow_definition_id,\s\
        biz_date, dedup_key, request_status) values (?, ?, 'SCHEDULED', ?, ?, ?, 'LAUNCHED') returning id\
        """;
    final String insertScheduledOccurrenceSql = """
        insert into batch.scheduled_occurrence (tenant_id, workflow_definition_id, workflow_code,\s\
        schedule_version, calendar_code, scheduled_fire_time, biz_date, occurrence_status, request_id)\s\
        values (?, ?, ?, 1, ?, ?, ?, 'REQUESTED', ?)\
        """;
    final String updateBatchDayInstanceSql = """
        update batch.batch_day_instance set day_status = 'CUTOFF' where tenant_id = ? and calendar_code = ?\s\
        and biz_date = ?\
        """;

    String tenantId = unique("tenant");
    String calendarCode = unique("calendar");
    LocalDate bizDate = LocalDate.of(2026, 9, 22);
    Long workflowDefinitionId = insertWorkflowDefinition(tenantId, calendarCode);
    String workflowCode =
        jdbcTemplate.queryForObject(selectWorkflowCodeSql, String.class, workflowDefinitionId);
    String requestId = unique("scheduled-request");
    Long triggerRequestId = jdbcTemplate.queryForObject(
        insertTriggerRequestSql,
        Long.class,
        tenantId,
        requestId,
        workflowDefinitionId,
        bizDate,
        unique("dedup"));
    jdbcTemplate.update(
        insertScheduledOccurrenceSql,
        tenantId,
        workflowDefinitionId,
        workflowCode,
        calendarCode,
        Timestamp.from(Instant.parse("2026-09-22T09:00:00Z")),
        bizDate,
        requestId);
    insertOpenBatchDay(tenantId, calendarCode, bizDate);
    jdbcTemplate.update(updateBatchDayInstanceSql, tenantId, calendarCode, bizDate);

    try (Connection fence = jdbcTemplate.getDataSource().getConnection();
        ExecutorService executor = Executors.newFixedThreadPool(1)) {
      fence.setAutoCommit(false);
      acquireSettlementFence(fence, tenantId, calendarCode, bizDate);
      int blockerPid = backendPid(fence);
      Future<?> settlement = executor.submit(batchDaySettleScheduler::settle);
      awaitBlockedBy(blockerPid);

      try (Connection dryRunCommit = jdbcTemplate.getDataSource().getConnection()) {
        dryRunCommit.setAutoCommit(false);
        try (PreparedStatement statement = dryRunCommit.prepareStatement(insertWorkflowRunSql)) {
          statement.setString(1, tenantId);
          statement.setLong(2, workflowDefinitionId);
          statement.setObject(3, bizDate);
          statement.setLong(4, triggerRequestId);
          assertThat(statement.executeUpdate()).isEqualTo(1);
        }
        dryRunCommit.commit();
      }

      assertThat(dayStatus(tenantId, calendarCode, bizDate)).isEqualTo("CUTOFF");
      fence.commit();
      settlement.get(10, TimeUnit.SECONDS);
    }

    assertThat(batchDayInstanceMapper.countBlockingScheduledOccurrences(
            tenantId, calendarCode, bizDate))
        .isOne();
    assertThat(dayStatus(tenantId, calendarCode, bizDate)).isEqualTo("CUTOFF");
  }

  @Test
  @DisplayName("CUTOFF 日仍有未终结计划 occurrence 时不结空日")
  void shouldKeepEmptyDayOpenWhilePlannedOccurrenceIsUnresolved() {
    final String selectWorkflowCodeSql = """
        select workflow_code from batch.workflow_definition where id = ?
    """;
    final String updateBatchDayInstanceSql = """
        update batch.batch_day_instance set day_status = 'CUTOFF' where tenant_id = ? and calendar_code = ?\s\
        and biz_date = ?\
        """;
    final String insertScheduledOccurrenceSql = """
        insert into batch.scheduled_occurrence (tenant_id, workflow_definition_id, workflow_code,\s\
        schedule_version, calendar_code, scheduled_fire_time, biz_date, occurrence_status)\s\
        values (?, ?, ?, 1, ?, ?, ?, 'PLANNED')\
        """;
    final String updateScheduledOccurrenceSql = """
        update batch.scheduled_occurrence set occurrence_status = 'MISFIRE_SKIPPED' where tenant_id = ?\s\
        and workflow_definition_id = ? and biz_date = ?\
        """;

    String tenantId = unique("tenant");
    String calendarCode = unique("calendar");
    LocalDate bizDate = LocalDate.of(2026, 9, 16);
    Long workflowDefinitionId = insertWorkflowDefinition(tenantId, calendarCode);
    String workflowCode =
        jdbcTemplate.queryForObject(selectWorkflowCodeSql, String.class, workflowDefinitionId);
    insertOpenBatchDay(tenantId, calendarCode, bizDate);
    jdbcTemplate.update(updateBatchDayInstanceSql, tenantId, calendarCode, bizDate);
    jdbcTemplate.update(
        insertScheduledOccurrenceSql,
        tenantId,
        workflowDefinitionId,
        workflowCode,
        calendarCode,
        Timestamp.from(Instant.parse("2026-09-16T23:59:00Z")),
        bizDate);

    batchDaySettleScheduler.settle();

    assertThat(dayStatus(tenantId, calendarCode, bizDate)).isEqualTo("CUTOFF");

    jdbcTemplate.update(updateScheduledOccurrenceSql, tenantId, workflowDefinitionId, bizDate);
    batchDaySettleScheduler.settle();
    assertThat(dayStatus(tenantId, calendarCode, bizDate)).isEqualTo("SETTLED");
  }

  @Test
  @DisplayName("待审批的 misfire 阻塞空日,拒绝处置终态后允许结算")
  void shouldSettleOnlyAfterPendingMisfireApprovalIsResolved() {
    final String selectWorkflowCodeSql = """
        select workflow_code from batch.workflow_definition where id = ?
    """;
    final String updateBatchDayInstanceSql = """
        update batch.batch_day_instance set day_status = 'CUTOFF' where tenant_id = ? and calendar_code = ?\s\
        and biz_date = ?\
        """;
    final String insertScheduledOccurrenceSql = """
        insert into batch.scheduled_occurrence (tenant_id, workflow_definition_id, workflow_code,\s\
        schedule_version, calendar_code, scheduled_fire_time, biz_date, occurrence_status)\s\
        values (?, ?, ?, 1, ?, ?, ?, 'MISFIRE_PENDING')\
        """;
    final String updateScheduledOccurrenceSql = """
        update batch.scheduled_occurrence set occurrence_status = 'MISFIRE_SKIPPED' where tenant_id = ?\s\
        and workflow_definition_id = ? and biz_date = ?\
        """;

    String tenantId = unique("tenant");
    String calendarCode = unique("calendar");
    LocalDate bizDate = LocalDate.of(2026, 9, 17);
    Long workflowDefinitionId = insertWorkflowDefinition(tenantId, calendarCode);
    String workflowCode =
        jdbcTemplate.queryForObject(selectWorkflowCodeSql, String.class, workflowDefinitionId);
    insertOpenBatchDay(tenantId, calendarCode, bizDate);
    jdbcTemplate.update(updateBatchDayInstanceSql, tenantId, calendarCode, bizDate);
    jdbcTemplate.update(
        insertScheduledOccurrenceSql,
        tenantId,
        workflowDefinitionId,
        workflowCode,
        calendarCode,
        Timestamp.from(Instant.parse("2026-09-17T09:00:00Z")),
        bizDate);

    batchDaySettleScheduler.settle();
    assertThat(dayStatus(tenantId, calendarCode, bizDate)).isEqualTo("CUTOFF");

    jdbcTemplate.update(updateScheduledOccurrenceSql, tenantId, workflowDefinitionId, bizDate);
    batchDaySettleScheduler.settle();
    assertThat(dayStatus(tenantId, calendarCode, bizDate)).isEqualTo("SETTLED");
  }

  @Test
  @DisplayName("跨日历依赖仍在等待时不结算,批量日开启后允许空日收口")
  void shouldKeepEmptyDayOpenWhileCalendarDependencyIsWaiting() {
    final String insertTenantSql = """
        insert into batch.tenant (tenant_id, tenant_name, status) values (?, ?, 'ACTIVE')
    """;
    final String insertBusinessCalendarSql = """
        insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone,\s\
        holiday_roll_rule) values (?, ?, 'Dependency Calendar', 'UTC', 'SKIP')\
        """;
    final String updateBatchDayInstanceSql = """
        update batch.batch_day_instance set day_status = 'CUTOFF' where tenant_id = ? and calendar_code = ?\s\
        and biz_date = ?\
        """;
    final String insertCalendarDependencyWaitSql = """
        insert into batch.calendar_dependency_wait (tenant_id, calendar_code, biz_date, wait_status,\s\
        blocked_reason, first_waiting_at, last_checked_at, timezone_snapshot, dst_policy_snapshot)\s\
        values (?, ?, ?, 'WAITING', 'UPSTREAM_BATCH_DAY_NOT_SETTLED', current_timestamp, current_timestamp,\s\
        'UTC', 'gap=RUN_AT_NEXT_VALID_TIME;overlap=RUN_ONCE_EARLIER_OFFSET')\
        """;
    final String updateCalendarDependencyWaitSql = """
        update batch.calendar_dependency_wait set wait_status = 'OPENED' where tenant_id = ?\s\
        and calendar_code = ? and biz_date = ?\
        """;

    String tenantId = unique("tenant");
    String calendarCode = unique("calendar");
    LocalDate bizDate = LocalDate.of(2026, 9, 18);
    jdbcTemplate.update(insertTenantSql, tenantId, tenantId);
    jdbcTemplate.update(insertBusinessCalendarSql, tenantId, calendarCode);
    insertOpenBatchDay(tenantId, calendarCode, bizDate);
    jdbcTemplate.update(updateBatchDayInstanceSql, tenantId, calendarCode, bizDate);
    jdbcTemplate.update(insertCalendarDependencyWaitSql, tenantId, calendarCode, bizDate);

    batchDaySettleScheduler.settle();
    assertThat(dayStatus(tenantId, calendarCode, bizDate)).isEqualTo("CUTOFF");

    jdbcTemplate.update(updateCalendarDependencyWaitSql, tenantId, calendarCode, bizDate);
    batchDaySettleScheduler.settle();
    assertThat(dayStatus(tenantId, calendarCode, bizDate)).isEqualTo("SETTLED");
  }

  @Test
  @DisplayName("作业依赖 launch wait 阻塞结算,释放后的根 WorkflowRun 成功后收口")
  void shouldKeepEmptyDayOpenWhileJobDependencyLaunchIsWaiting() {
    final String insertTenantSql = """
        insert into batch.tenant (tenant_id, tenant_name, status) values (?, ?, 'ACTIVE')
    """;
    final String insertTriggerRequestSql = """
        insert into batch.trigger_request (tenant_id, request_id, trigger_type, workflow_definition_id,\s\
        biz_date, dedup_key, request_status) values (?, ?, 'SCHEDULED', ?, ?, ?, 'WAITING') returning id\
        """;
    final String insertJobDependencyWaitingLaunchSql = """
        insert into batch.job_dependency_waiting_launch (tenant_id, request_id, job_code, biz_date,\s\
        dependency_snapshot, launch_payload, wait_status)\s\
        values (?, ?, ?, ?, '[{"upstreamJobCode":"UPSTREAM","bizDateAlignment":"SAME_DAY"}]'::jsonb,\s\
        '{}'::jsonb, 'WAITING')\
        """;
    final String updateBatchDayInstanceSql = """
        update batch.batch_day_instance set day_status = 'CUTOFF' where tenant_id = ? and calendar_code = ?\s\
        and biz_date = ?\
        """;
    final String updateWorkflowRunSql = """
        update batch.workflow_run set trigger_request_id = ? where tenant_id = ? and workflow_definition_id = ?\s\
        and biz_date = ?\
        """;
    final String updateTriggerRequestSql = """
        update batch.trigger_request set request_status = 'LAUNCHED' where tenant_id = ? and id = ?\
        """;
    final String updateJobDependencyWaitingLaunchSql = """
        update batch.job_dependency_waiting_launch set wait_status = 'RELEASED', released_at = current_timestamp\s\
        where tenant_id = ? and request_id = ?\
        """;

    String tenantId = unique("tenant");
    String calendarCode = unique("calendar");
    String jobCode = unique("dependent-job");
    LocalDate bizDate = LocalDate.of(2026, 9, 19);
    jdbcTemplate.update(insertTenantSql, tenantId, tenantId);
    Long jobDefinitionId = insertJobDefinition(tenantId, calendarCode, jobCode, unique("group"));
    Long workflowDefinitionId =
        insertManagedSingleJobWorkflow(tenantId, calendarCode, jobCode, jobDefinitionId);
    String requestId = unique("dependency-request");
    Long triggerRequestId = jdbcTemplate.queryForObject(
        insertTriggerRequestSql,
        Long.class,
        tenantId,
        requestId,
        workflowDefinitionId,
        bizDate,
        unique("dedup"));
    jdbcTemplate.update(insertJobDependencyWaitingLaunchSql, tenantId, requestId, jobCode, bizDate);
    insertOpenBatchDay(tenantId, calendarCode, bizDate);
    jdbcTemplate.update(updateBatchDayInstanceSql, tenantId, calendarCode, bizDate);

    batchDaySettleScheduler.settle();
    assertThat(dayStatus(tenantId, calendarCode, bizDate)).isEqualTo("CUTOFF");

    insertWorkflowRun(tenantId, workflowDefinitionId, bizDate, "SUCCESS", false);
    jdbcTemplate.update(
        updateWorkflowRunSql, triggerRequestId, tenantId, workflowDefinitionId, bizDate);
    jdbcTemplate.update(updateTriggerRequestSql, tenantId, triggerRequestId);
    jdbcTemplate.update(updateJobDependencyWaitingLaunchSql, tenantId, requestId);
    batchDaySettleScheduler.settle();

    assertThat(dayStatus(tenantId, calendarCode, bizDate)).isEqualTo("SETTLED");
  }

  @Test
  @DisplayName("Trigger 接纳与空日结算的两个真实事务顺序都收敛到唯一业务结果")
  void shouldSerializeTriggerAdmissionAndSettlementInBothCommitOrders() throws Exception {
    final String updateBatchDayInstanceSql = """
        update batch.batch_day_instance set day_status = 'CUTOFF' where tenant_id = ? and calendar_code = ?\s\
        and biz_date = ?\
        """;

    String tenantId = unique("tenant");
    String calendarCode = unique("calendar");
    String jobCode = unique("job");
    insertJobDefinition(tenantId, calendarCode, jobCode, unique("group"));
    Long workflowDefinitionId = insertWorkflowDefinition(tenantId, calendarCode);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      LocalDate requestFirstDate = LocalDate.of(2026, 9, 11);
      insertOpenBatchDay(tenantId, calendarCode, requestFirstDate);
      jdbcTemplate.update(updateBatchDayInstanceSql, tenantId, calendarCode, requestFirstDate);
      try (Connection admission = jdbcTemplate.getDataSource().getConnection()) {
        admission.setAutoCommit(false);
        insertWorkflowRequest(admission, tenantId, workflowDefinitionId, requestFirstDate);
        int admissionPid = backendPid(admission);
        Future<?> settlementAttempt = executor.submit(batchDaySettleScheduler::settle);
        awaitBlockedBy(admissionPid);
        admission.commit();
        settlementAttempt.get(10, TimeUnit.SECONDS);
        assertThat(dayStatus(tenantId, calendarCode, requestFirstDate)).isEqualTo("CUTOFF");
        assertThat(triggerRequestMapper.countUnlaunchedForCalendarBizDate(
                tenantId,
                calendarCode,
                requestFirstDate,
                List.of("PENDING", "PROCESSING", "ACCEPTED", "WAITING")))
            .isOne();
      }

      LocalDate settlementFirstDate = LocalDate.of(2026, 9, 12);
      insertOpenBatchDay(tenantId, calendarCode, settlementFirstDate);
      jdbcTemplate.update(updateBatchDayInstanceSql, tenantId, calendarCode, settlementFirstDate);
      Future<?> settlementAttempt = executor.submit(batchDaySettleScheduler::settle);
      settlementAttempt.get(10, TimeUnit.SECONDS);
      assertThat(dayStatus(tenantId, calendarCode, settlementFirstDate)).isEqualTo("SETTLED");
      try (Connection admission = jdbcTemplate.getDataSource().getConnection()) {
        admission.setAutoCommit(false);
        boolean rejectedAsClosed = false;
        try {
          insertWorkflowRequest(admission, tenantId, workflowDefinitionId, settlementFirstDate);
        } catch (SQLException expectedAfterSettlement) {
          rejectedAsClosed = "BDA01".equals(expectedAfterSettlement.getSQLState());
        }
        assertThat(rejectedAsClosed).isTrue();
        assertThat(triggerRequestMapper.countUnlaunchedForCalendarBizDate(
                tenantId,
                calendarCode,
                settlementFirstDate,
                List.of("PENDING", "PROCESSING", "ACCEPTED", "WAITING")))
            .isZero();
        admission.rollback();
      }
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  @DisplayName("节点释放等待结算围栏后必须重新读取父 Workflow 状态")
  void shouldRejectChildAdmissionWhenParentTerminatesWhileWaitingForSettlementFence()
      throws Exception {
    final String insertTriggerRequestSql = """
            insert into batch.trigger_request (tenant_id, request_id, trigger_type, job_code, biz_date,\s\
            dedup_key, request_status, parent_workflow_run_id, parent_node_code)\s\
            values (?, ?, 'EVENT', ?, ?, ?, 'ACCEPTED', ?, 'WAIT_NODE')\
            """;

    final String insertWorkflowRunSql = """
            insert into batch.workflow_run (tenant_id, workflow_definition_id, biz_date, run_status, dry_run)\s\
            values (?, ?, ?, 'RUNNING', false) returning id\
            """;
    final String updateBatchDayInstanceSql = """
            update batch.batch_day_instance set day_status = 'CUTOFF' where tenant_id = ? and calendar_code = ?\s\
            and biz_date = ?\
            """;
    final String updateWorkflowRunSql = """
            update batch.workflow_run set run_status = 'SUCCESS', finished_at = current_timestamp\s\
            where tenant_id = ? and id = ?\
            """;
    final String updateBatchDayInstanceSql2 = """
      update batch.batch_day_instance set day_status = 'SETTLED', settled_at = current_timestamp\s\
      where tenant_id = ? and calendar_code = ? and biz_date = ?\
      """;
    final String selectCountSql = """
        select count(*) from batch.trigger_request where tenant_id = ? and parent_workflow_run_id = ?\
        """;

    String tenantId = unique("tenant");
    String calendarCode = unique("calendar");
    String jobCode = unique("job");
    LocalDate bizDate = LocalDate.of(2026, 9, 13);
    insertJobDefinition(tenantId, calendarCode, jobCode, unique("group"));
    Long workflowDefinitionId = insertWorkflowDefinition(tenantId, calendarCode);
    Long workflowRunId = jdbcTemplate.queryForObject(
        insertWorkflowRunSql, Long.class, tenantId, workflowDefinitionId, bizDate);
    insertOpenBatchDay(tenantId, calendarCode, bizDate);
    jdbcTemplate.update(updateBatchDayInstanceSql, tenantId, calendarCode, bizDate);

    ExecutorService executor = Executors.newSingleThreadExecutor();
    try (Connection settlement = jdbcTemplate.getDataSource().getConnection()) {
      settlement.setAutoCommit(false);
      acquireSettlementFence(settlement, tenantId, calendarCode, bizDate);
      int settlementPid = backendPid(settlement);
      Future<String> childAdmission = executor.submit(() -> {
        try (Connection connection = jdbcTemplate.getDataSource().getConnection()) {
          connection.setAutoCommit(false);
          try (var statement = connection.prepareStatement(insertTriggerRequestSql)) {
            statement.setString(1, tenantId);
            statement.setString(2, unique("child-request"));
            statement.setString(3, jobCode);
            statement.setObject(4, bizDate);
            statement.setString(5, unique("dedup"));
            statement.setLong(6, workflowRunId);
            statement.executeUpdate();
          }
          connection.commit();
          return "accepted";
        } catch (SQLException failure) {
          return failure.getSQLState();
        }
      });
      awaitBlockedBy(settlementPid);
      jdbcTemplate.update(updateWorkflowRunSql, tenantId, workflowRunId);
      jdbcTemplate.update(updateBatchDayInstanceSql2, tenantId, calendarCode, bizDate);
      settlement.commit();

      assertThat(childAdmission.get(10, TimeUnit.SECONDS)).isEqualTo("BDA01");
      Integer selectedTriggerRequestCount =
          jdbcTemplate.queryForObject(selectCountSql, Integer.class, tenantId, workflowRunId);
      assertThat(selectedTriggerRequestCount).isZero();
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  @DisplayName("业务日仍开放时数据库也拒绝已终态父 Workflow 的子 Job 接纳")
  void shouldRejectChildAdmissionForTerminalParentWhileBatchDayIsOpen() throws Exception {
    final String insertTriggerRequestSql = """
        insert into batch.trigger_request (tenant_id, request_id, trigger_type, job_code, biz_date, dedup_key,\s\
        request_status, parent_workflow_run_id, parent_node_code)\s\
        values (?, ?, 'EVENT', ?, ?, ?, 'ACCEPTED', ?, 'WAIT_NODE')\
        """;

    final String insertWorkflowRunSql = """
        insert into batch.workflow_run (tenant_id, workflow_definition_id, biz_date, run_status, dry_run)\s\
        values (?, ?, ?, 'TERMINATED', false) returning id\
        """;
    final String selectCountSql = """
        select count(*) from batch.trigger_request where tenant_id = ? and parent_workflow_run_id = ?\
        """;

    String tenantId = unique("tenant");
    String calendarCode = unique("calendar");
    String jobCode = unique("child-job");
    LocalDate bizDate = LocalDate.of(2026, 9, 15);
    insertJobDefinition(tenantId, calendarCode, jobCode, unique("group"));
    Long workflowDefinitionId = insertWorkflowDefinition(tenantId, calendarCode);
    Long workflowRunId = jdbcTemplate.queryForObject(
        insertWorkflowRunSql, Long.class, tenantId, workflowDefinitionId, bizDate);
    insertOpenBatchDay(tenantId, calendarCode, bizDate);

    try (Connection connection = jdbcTemplate.getDataSource().getConnection()) {
      connection.setAutoCommit(false);
      String sqlState = null;
      try (PreparedStatement statement = connection.prepareStatement(insertTriggerRequestSql)) {
        statement.setString(1, tenantId);
        statement.setString(2, unique("terminal-parent-child"));
        statement.setString(3, jobCode);
        statement.setObject(4, bizDate);
        statement.setString(5, unique("dedup"));
        statement.setLong(6, workflowRunId);
        statement.executeUpdate();
      } catch (SQLException rejected) {
        sqlState = rejected.getSQLState();
      } finally {
        connection.rollback();
      }
      assertThat(sqlState).isEqualTo("BDA02");
    }

    Integer selectedTriggerRequestCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, tenantId, workflowRunId);
    assertThat(selectedTriggerRequestCount).isZero();
    assertThat(dayStatus(tenantId, calendarCode, bizDate)).isEqualTo("OPEN");
  }

  @Test
  @DisplayName("Workflow 终态收口重读节点集合,不覆盖并发接纳的子节点")
  void shouldNotFinalizeWorkflowFromStaleNodeSnapshotDuringChildAdmission() throws Exception {
    final String selectIdSql = """
        select id from batch.workflow_run where tenant_id = ? and id = ? for update
    """;

    final String updateBatchDayInstanceSql = """
        update batch.batch_day_instance set day_status = 'SETTLED' where tenant_id = ? and calendar_code = ?\s\
        and biz_date = ?\
        """;
    final String insertWorkflowRunSql = """
        insert into batch.workflow_run (tenant_id, workflow_definition_id, biz_date, run_status, dry_run)\s\
        values (?, ?, ?, 'RUNNING', false) returning id\
        """;
    final String insertWorkflowNodeRunSql = """
        insert into batch.workflow_node_run (workflow_run_id, node_code, node_type, node_status)\s\
        values (?, 'START', 'START', 'SUCCESS')\
        """;
    final String selectRunStatusSql = """
        select run_status from batch.workflow_run where tenant_id = ? and id = ?
    """;
    final String selectNodeStatusSql = """
        select node_status from batch.workflow_node_run where workflow_run_id = ? and node_code = ?\
        """;

    String tenantId = unique("tenant");
    String calendarCode = unique("calendar");
    String jobCode = unique("child-job");
    LocalDate bizDate = LocalDate.of(2026, 9, 14);
    Long workflowDefinitionId = insertWorkflowDefinition(tenantId, calendarCode);
    insertOpenBatchDay(tenantId, calendarCode, bizDate);
    jdbcTemplate.update(updateBatchDayInstanceSql, tenantId, calendarCode, bizDate);
    Long workflowRunId = jdbcTemplate.queryForObject(
        insertWorkflowRunSql, Long.class, tenantId, workflowDefinitionId, bizDate);
    jdbcTemplate.update(insertWorkflowNodeRunSql, workflowRunId);

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try (Connection rootLock = jdbcTemplate.getDataSource().getConnection()) {
      rootLock.setAutoCommit(false);
      try (var lock = rootLock.prepareStatement(selectIdSql)) {
        lock.setString(1, tenantId);
        lock.setLong(2, workflowRunId);
        lock.executeQuery().close();
      }
      int blockerPid = backendPid(rootLock);
      Future<?> childAdmission = executor.submit(() -> {
        try (Connection connection = jdbcTemplate.getDataSource().getConnection()) {
          connection.setAutoCommit(false);
          insertChildWorkflowRequestAndReadyNode(
              connection, tenantId, workflowRunId, jobCode, bizDate);
          connection.commit();
        } catch (Exception failure) {
          throw new RuntimeException(failure);
        }
      });
      awaitBlockedBy(blockerPid);

      WorkflowRunEntity run = new WorkflowRunEntity();
      run.setTenantId(tenantId);
      run.setId(workflowRunId);
      run.setRunStatus("RUNNING");
      Future<?> finalization = executor.submit(() -> RlsTenantContextHolder.runWithTenant(
          tenantId,
          () -> transactionTemplate.executeWithoutResult(
              status -> workflowNodeTerminalFinalizer.finalizeIfAllNodesTerminal(run))));
      awaitBlockedCountBy(blockerPid, 2);
      rootLock.commit();

      childAdmission.get(10, TimeUnit.SECONDS);
      finalization.get(10, TimeUnit.SECONDS);
      String selectedRunStatus =
          jdbcTemplate.queryForObject(selectRunStatusSql, String.class, tenantId, workflowRunId);
      assertThat(selectedRunStatus).isEqualTo("RUNNING");
      String selectedNodeStatus =
          jdbcTemplate.queryForObject(selectNodeStatusSql, String.class, workflowRunId, jobCode);
      assertThat(selectedNodeStatus).isEqualTo("READY");
    } finally {
      executor.shutdownNow();
    }
  }

  private void insertChildWorkflowRequestAndReadyNode(
      Connection connection,
      String tenantId,
      Long workflowRunId,
      String nodeCode,
      LocalDate bizDate)
      throws SQLException {
    final String insertWorkflowNodeRunSql = """
        insert into batch.workflow_node_run (workflow_run_id, node_code, node_type, node_status)\s\
        values (?, ?, 'TASK', 'READY')\
        """;
    final String insertTriggerRequestSql = """
        insert into batch.trigger_request (tenant_id, request_id, trigger_type, job_code, biz_date, dedup_key,\s\
        request_status, parent_workflow_run_id, parent_node_code)\s\
        values (?, ?, 'EVENT', ?, ?, ?, 'ACCEPTED', ?, ?)\
        """;

    try (var node = connection.prepareStatement(insertWorkflowNodeRunSql)) {
      node.setLong(1, workflowRunId);
      node.setString(2, nodeCode);
      node.executeUpdate();
    }
    try (var request = connection.prepareStatement(insertTriggerRequestSql)) {
      request.setString(1, tenantId);
      request.setString(2, unique("child-request"));
      request.setString(3, nodeCode);
      request.setObject(4, bizDate);
      request.setString(5, unique("child-dedup"));
      request.setLong(6, workflowRunId);
      request.setString(7, nodeCode);
      request.executeUpdate();
    }
  }

  private void awaitBlockedCountBy(int blockerPid, int expectedCount) throws Exception {
    final String withLockChainSql = """
        with recursive lock_chain(waiting_pid, blocking_pid, depth) as ( select activity.pid, blocker.pid, 1\s\
        from pg_stat_activity activity cross\s\
        join lateral unnest(pg_blocking_pids(activity.pid)) blocker(pid) union all select chain.waiting_pid,\s\
        blocker.pid, chain.depth + 1 from lock_chain chain cross\s\
        join lateral unnest(pg_blocking_pids(chain.blocking_pid)) blocker(pid)\s\
        where chain.depth < 8) select count(distinct waiting_pid) from lock_chain where blocking_pid = ?\
        """;

    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      Integer waiting = jdbcTemplate.queryForObject(withLockChainSql, Integer.class, blockerPid);
      if (waiting != null && waiting >= expectedCount) return;
      Thread.sleep(10);
    }
    throw new AssertionError("PostgreSQL did not show " + expectedCount
        + " transactions blocked by the parent row lock");
  }

  private void acquireSettlementFence(
      Connection connection, String tenantId, String calendarCode, LocalDate bizDate)
      throws SQLException {
    final String selectAcquireBatchDaySettlementFenceSql = """
        select batch.acquire_batch_day_settlement_fence(?, ?, ?)
    """;

    try (var statement = connection.prepareStatement(selectAcquireBatchDaySettlementFenceSql)) {
      statement.setString(1, tenantId);
      statement.setString(2, calendarCode);
      statement.setObject(3, bizDate);
      statement.executeQuery().close();
    }
  }

  private void insertOpenBatchDay(String tenantId, String calendarCode, LocalDate bizDate) {
    final String insertBatchDayInstanceSql = """
        insert into batch.batch_day_instance (tenant_id, calendar_code, biz_date, day_status, open_at,\s\
        late_count, catchup_count, timezone_snapshot, dst_policy_snapshot, frozen, version, created_at,\s\
        updated_at, dry_run) values (?, ?, ?, 'OPEN', current_timestamp, 0, 0, 'Asia/Shanghai',\s\
        'gap=RUN_AT_NEXT_VALID_TIME;overlap=RUN_ONCE_EARLIER_OFFSET', false, 0, current_timestamp,\s\
        current_timestamp, false)\
        """;

    jdbcTemplate.update(insertBatchDayInstanceSql, tenantId, calendarCode, bizDate);
  }

  private void insertWorkflowRequest(
      Connection connection, String tenantId, Long workflowDefinitionId, LocalDate bizDate)
      throws SQLException {
    final String insertTriggerRequestSql = """
        insert into batch.trigger_request (tenant_id, request_id, trigger_type, workflow_definition_id,\s\
        biz_date, dedup_key, request_status) values (?, ?, 'EVENT', ?, ?, ?, 'ACCEPTED')\
        """;

    try (var statement = connection.prepareStatement(insertTriggerRequestSql)) {
      statement.setString(1, tenantId);
      statement.setString(2, unique("request"));
      statement.setLong(3, workflowDefinitionId);
      statement.setObject(4, bizDate);
      statement.setString(5, unique("dedup"));
      statement.executeUpdate();
    }
  }

  private void awaitBlockedBy(int blockerPid) throws Exception {
    final String selectCountSql = """
        select count(*) from pg_stat_activity where ? = any(pg_blocking_pids(pid))
    """;

    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      Integer waiting = jdbcTemplate.queryForObject(selectCountSql, Integer.class, blockerPid);
      if (waiting != null && waiting > 0) return;
      Thread.sleep(10);
    }
    throw new AssertionError("PostgreSQL transaction did not reach the expected lock barrier");
  }

  private void awaitBlockedByCount(int blockerPid, int expectedCount) throws Exception {
    final String selectCountSql = """
        select count(*) from pg_stat_activity where ? = any(pg_blocking_pids(pid))
    """;

    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      Integer waiting = jdbcTemplate.queryForObject(selectCountSql, Integer.class, blockerPid);
      if (waiting != null && waiting >= expectedCount) return;
      Thread.sleep(10);
    }
    throw new AssertionError(
        "Expected " + expectedCount + " PostgreSQL transactions to reach the lock barrier");
  }

  private String dayStatus(String tenantId, String calendarCode, LocalDate bizDate) {
    final String selectDayStatusSql = """
        select day_status from batch.batch_day_instance where tenant_id = ? and calendar_code = ?\s\
        and biz_date = ?\
        """;

    return jdbcTemplate.queryForObject(
        selectDayStatusSql, String.class, tenantId, calendarCode, bizDate);
  }

  @ParameterizedTest
  @ValueSource(strings = {"SKIP", "CLOSE", "RELEASE"})
  @DisplayName("人工终结批量日必须与 Trigger 接纳共享结算围栏")
  void shouldSerializeManualTerminalDispositionWithTriggerAdmission(String actionName)
      throws Exception {
    final String selectPgAdvisoryLockSql = """
            select pg_advisory_lock(?)
        """;
    final String selectPgAdvisoryUnlockSql = """
            select pg_advisory_unlock(?)
        """;

    final String updateBatchDayInstanceSql = """
        update batch.batch_day_instance set day_status = 'CUTOFF' where tenant_id = ? and calendar_code = ?\s\
        and biz_date = ?\
        """;
    final String selectPidSql = """
        select pid from pg_stat_activity where ? = any(pg_blocking_pids(pid))\s\
        and lower(query) like '%%update batch.batch_day_instance%%' limit 1\
        """;
    final String selectCountSql = """
        select count(*) from batch.trigger_request where tenant_id = ? and biz_date = ?
    """;

    String tenantId = unique("tenant");
    String calendarCode = unique("calendar");
    LocalDate bizDate = LocalDate.of(2026, 9, 14);
    Long workflowDefinitionId = insertWorkflowDefinition(tenantId, calendarCode);
    insertOpenBatchDay(tenantId, calendarCode, bizDate);
    jdbcTemplate.update(updateBatchDayInstanceSql, tenantId, calendarCode, bizDate);

    String triggerName = unique("wait_for_terminal_disposition").replace('-', '_');
    String functionName = unique("wait_for_terminal_disposition_fn").replace('-', '_');
    long advisoryKey = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
    final String createFunctionSql = """
    create function batch.%s() returns trigger language plpgsql as $$
    begin
    if new.day_status in ('SKIPPED', 'SETTLED', 'MANUAL_RELEASED') then
    perform pg_advisory_xact_lock(%d);
    end if;
    return new;
    end;
    $$
    """.formatted(functionName, advisoryKey);
    jdbcTemplate.execute(createFunctionSql);
    final String createTriggerSql = """
    create trigger %s before update of day_status on batch.batch_day_instance
    for each row execute function batch.%s()
    """.formatted(triggerName, functionName);
    jdbcTemplate.execute(createTriggerSql);

    try (ExecutorService executor = Executors.newFixedThreadPool(2);
        Connection blocker = jdbcTemplate.getDataSource().getConnection()) {
      blocker.setAutoCommit(true);
      try (var lock = blocker.prepareStatement(selectPgAdvisoryLockSql)) {
        lock.setLong(1, advisoryKey);
        lock.execute();
      }
      int blockerPid = backendPid(blocker);
      Future<?> operation =
          executor.submit(() -> batchDayOperationService.operate(BatchDayOperateCommand.builder()
              .tenantId(tenantId)
              .calendarCode(calendarCode)
              .bizDate(bizDate)
              .action(BatchDayOperationService.BatchDayOperation.valueOf(actionName))
              .operatorId("r11-test")
              .reason("serialize terminal disposition")
              .build()));
      Future<String> admission = null;
      try {
        awaitBlockedBy(blockerPid);
        Integer operationPid = jdbcTemplate.queryForObject(selectPidSql, Integer.class, blockerPid);
        assertThat(operationPid).isNotNull();

        admission = executor.submit(
            () -> tryInsertWorkflowRequest(tenantId, workflowDefinitionId, bizDate));
        boolean admissionWaitedForOperation =
            awaitAdmissionBlockOrCompletion(admission, operationPid);

        try (var unlock = blocker.prepareStatement(selectPgAdvisoryUnlockSql)) {
          unlock.setLong(1, advisoryKey);
          unlock.execute();
        }
        operation.get(10, TimeUnit.SECONDS);
        String admissionResult = admission.get(10, TimeUnit.SECONDS);

        assertThat(admissionWaitedForOperation)
            .as("Trigger insert must wait for the batch-day terminal transition")
            .isTrue();
        assertThat(admissionResult).isEqualTo("BDA01");
        assertThat(dayStatus(tenantId, calendarCode, bizDate))
            .isEqualTo(
                switch (actionName) {
                  case "SKIP" -> "SKIPPED";
                  case "CLOSE" -> "SETTLED";
                  case "RELEASE" -> "MANUAL_RELEASED";
                  default -> throw new IllegalArgumentException(actionName);
                });
        Integer selectedTriggerRequestCount =
            jdbcTemplate.queryForObject(selectCountSql, Integer.class, tenantId, bizDate);
        assertThat(selectedTriggerRequestCount).isZero();
      } finally {
        try (var unlock = blocker.prepareStatement(selectPgAdvisoryUnlockSql)) {
          unlock.setLong(1, advisoryKey);
          unlock.execute();
        }
        operation.get(10, TimeUnit.SECONDS);
        if (admission != null) {
          admission.get(10, TimeUnit.SECONDS);
        }
      }
    } finally {
      final String dropTriggerSql = """
          drop trigger if exists %s on batch.batch_day_instance\
          """.formatted(triggerName);
      jdbcTemplate.execute(dropTriggerSql);
      final String dropFunctionSql = """
          drop function if exists batch.%s()\
          """.formatted(functionName);
      jdbcTemplate.execute(dropFunctionSql);
    }
  }

  @ParameterizedTest
  @CsvSource({
    "SKIP, ROOT",
    "SKIP, CHILD",
    "CLOSE, ROOT",
    "CLOSE, CHILD",
    "RELEASE, ROOT",
    "RELEASE, CHILD"
  })
  @DisplayName("人工终态操作不得覆盖先前接纳但尚未派发的顶层或子 Job 请求")
  void shouldKeepManualTerminalDispositionOpenWhileAcceptedRequestIsUnlaunched(
      String actionName, String requestKind) throws Exception {
    final String updateBatchDayInstanceSql = """
        update batch.batch_day_instance set day_status = 'CUTOFF' where tenant_id = ? and calendar_code = ?\s\
        and biz_date = ?\
        """;
    final String insertWorkflowRunSql = """
        insert into batch.workflow_run (tenant_id, workflow_definition_id, biz_date, run_status, dry_run)\s\
        values (?, ?, ?, 'RUNNING', false) returning id\
        """;
    final String selectCountSql = """
        select count(*) from batch.batch_day_operation_audit where tenant_id = ? and calendar_code = ?\s\
        and biz_date = ?\
        """;

    String tenantId = unique("tenant");
    String calendarCode = unique("calendar");
    String jobCode = unique("job");
    LocalDate bizDate = LocalDate.of(2026, 9, 15);
    Long workflowDefinitionId = insertWorkflowDefinition(tenantId, calendarCode);
    insertOpenBatchDay(tenantId, calendarCode, bizDate);
    jdbcTemplate.update(updateBatchDayInstanceSql, tenantId, calendarCode, bizDate);

    try (Connection admission = jdbcTemplate.getDataSource().getConnection()) {
      admission.setAutoCommit(false);
      if ("ROOT".equals(requestKind)) {
        insertWorkflowRequest(admission, tenantId, workflowDefinitionId, bizDate);
      } else {
        Long workflowRunId = jdbcTemplate.queryForObject(
            insertWorkflowRunSql, Long.class, tenantId, workflowDefinitionId, bizDate);
        insertChildWorkflowRequestAndReadyNode(
            admission, tenantId, workflowRunId, jobCode, bizDate);
      }
      admission.commit();
    }

    assertThatThrownBy(() -> batchDayOperationService.operate(BatchDayOperateCommand.builder()
            .tenantId(tenantId)
            .calendarCode(calendarCode)
            .bizDate(bizDate)
            .action(BatchDayOperationService.BatchDayOperation.valueOf(actionName))
            .operatorId("r11-test")
            .reason("preserve accepted launch obligation")
            .build()))
        .as(
            "%s must not terminalize a day with an accepted unlaunched %s request",
            actionName, requestKind)
        .isInstanceOfSatisfying(BizException.class, failure -> {
          assertThat(failure.getCode()).isEqualTo(ResultCode.STATE_CONFLICT);
          assertThat(failure.getMessageKey())
              .isEqualTo("error.batch_day.terminal_has_unlaunched_requests");
        });

    assertThat(dayStatus(tenantId, calendarCode, bizDate)).isEqualTo("CUTOFF");
    assertThat(triggerRequestMapper.countUnlaunchedForCalendarBizDate(
            tenantId,
            calendarCode,
            bizDate,
            List.of("PENDING", "PROCESSING", "ACCEPTED", "WAITING")))
        .isOne();
    Integer selectedBatchDayOperationAuditCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, tenantId, calendarCode, bizDate);
    assertThat(selectedBatchDayOperationAuditCount).isZero();
  }

  private String tryInsertWorkflowRequest(
      String tenantId, Long workflowDefinitionId, LocalDate bizDate) throws Exception {
    try (Connection connection = jdbcTemplate.getDataSource().getConnection()) {
      connection.setAutoCommit(false);
      try {
        insertWorkflowRequest(connection, tenantId, workflowDefinitionId, bizDate);
        connection.commit();
        return "ACCEPTED";
      } catch (SQLException failure) {
        connection.rollback();
        return failure.getSQLState();
      }
    }
  }

  private boolean awaitAdmissionBlockOrCompletion(Future<String> admission, int operationPid)
      throws Exception {
    final String selectCountSql = """
        select count(*) from pg_stat_activity where ? = any(pg_blocking_pids(pid))\s\
        and lower(query) like '%%insert into batch.trigger_request%%'\
        """;

    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      if (admission.isDone()) {
        return false;
      }
      Integer blocked = jdbcTemplate.queryForObject(selectCountSql, Integer.class, operationPid);
      if (blocked != null && blocked > 0) {
        return true;
      }
      Thread.sleep(10);
    }
    throw new AssertionError("Trigger admission neither waited for terminalization nor completed");
  }

  private int backendPid(Connection connection) throws SQLException {
    final String selectPgBackendPidSql = """
        select pg_backend_pid()
    """;

    try (var statement = connection.createStatement();
        var result = statement.executeQuery(selectPgBackendPidSql)) {
      result.next();
      return result.getInt(1);
    }
  }

  private void awaitLockWait(int backendPid) throws Exception {
    final String selectWaitEventTypeSql = """
        select wait_event_type = 'Lock' from pg_stat_activity where pid = ?
    """;

    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      Boolean waiting =
          jdbcTemplate.queryForObject(selectWaitEventTypeSql, Boolean.class, backendPid);
      if (Boolean.TRUE.equals(waiting)) {
        return;
      }
      Thread.sleep(10);
    }
    throw new AssertionError("PostgreSQL transaction did not reach the expected lock barrier");
  }

  @Test
  @DisplayName("已结算业务日拒绝普通 Trigger 并允许显式补跑请求")
  void shouldRejectImplicitTriggerAfterSettlement() {
    final String insertTenantSql = """
        insert into batch.tenant (tenant_id, tenant_name, status) values (?, ?, 'ACTIVE')
    """;

    String tenantId = unique("tenant");
    String calendarCode = unique("calendar");
    String jobCode = unique("job");
    LocalDate bizDate = LocalDate.of(2026, 9, 10);
    jdbcTemplate.update(insertTenantSql, tenantId, tenantId);
    insertJobDefinition(tenantId, calendarCode, jobCode, unique("group"));
    Long workflowDefinitionId = insertWorkflowDefinition(tenantId, calendarCode);
    BatchDayInstanceEntity settled = BatchDayInstanceEntity.builder()
        .tenantId(tenantId)
        .calendarCode(calendarCode)
        .bizDate(bizDate)
        .dayStatus("SETTLED")
        .openAt(Instant.parse("2026-09-10T00:00:00Z"))
        .settledAt(Instant.parse("2026-09-10T06:00:00Z"))
        .lateCount(0)
        .catchupCount(0)
        .timezoneSnapshot("Asia/Shanghai")
        .dstPolicySnapshot("gap=RUN_AT_NEXT_VALID_TIME;overlap=RUN_ONCE_EARLIER_OFFSET")
        .frozen(false)
        .version(0L)
        .createdAt(Instant.parse("2026-09-10T00:00:00Z"))
        .updatedAt(Instant.parse("2026-09-10T06:00:00Z"))
        .dryRun(false)
        .build();
    assertThat(batchDayInstanceMapper.insert(settled)).isOne();

    TriggerRequestEntity legacyJobRoot = triggerRequest(tenantId, null, jobCode, bizDate, "EVENT");
    assertThatThrownBy(() -> triggerRequestMapper.insert(legacyJobRoot))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("valid Workflow identity");

    TriggerRequestEntity missingBizDate =
        triggerRequest(tenantId, workflowDefinitionId, jobCode, bizDate, "EVENT");
    missingBizDate.setBizDate(null);
    assertThatThrownBy(() -> triggerRequestMapper.insert(missingBizDate))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("explicit business date");

    TriggerRequestEntity ordinary =
        triggerRequest(tenantId, workflowDefinitionId, jobCode, bizDate, "EVENT");
    assertThatThrownBy(() -> triggerRequestMapper.insert(ordinary))
        .isInstanceOf(UncategorizedSQLException.class)
        .hasMessageContaining("SQL state [BDA01]");

    TriggerRequestEntity catchUp =
        triggerRequest(tenantId, workflowDefinitionId, jobCode, bizDate, "CATCH_UP");
    assertThat(triggerRequestMapper.insert(catchUp)).isOne();
  }

  private TriggerRequestEntity triggerRequest(
      String tenantId,
      Long workflowDefinitionId,
      String jobCode,
      LocalDate bizDate,
      String triggerType) {
    TriggerRequestEntity request = new TriggerRequestEntity();
    request.setTenantId(tenantId);
    request.setRequestId(unique("request"));
    request.setTriggerType(triggerType);
    if (workflowDefinitionId == null) {
      request.setJobCode(jobCode);
    } else {
      request.setWorkflowDefinitionId(workflowDefinitionId);
    }
    request.setBizDate(bizDate);
    request.setDedupKey(unique("dedup"));
    request.setRequestStatus("ACCEPTED");
    request.setTraceId(unique("trace"));
    return request;
  }

  private static long acquireFence(
      Connection connection, String tenantId, String calendarCode, LocalDate bizDate)
      throws Exception {
    final String selectAcquireBatchDaySettlementFenceSql = """
        select batch.acquire_batch_day_settlement_fence(?, ?, ?)
    """;

    try (PreparedStatement statement =
        connection.prepareStatement(selectAcquireBatchDaySettlementFenceSql)) {
      statement.setString(1, tenantId);
      statement.setString(2, calendarCode);
      statement.setObject(3, bizDate);
      try (var result = statement.executeQuery()) {
        result.next();
        return result.getLong(1);
      }
    }
  }

  @Test
  @DisplayName("自动空日结算必须对未建立预期清单的 EVENT 与 MIXED 作业失败关闭")
  void shouldCountEnabledEventAndMixedJobsAsUntrackedEmptyDayExpectations() {
    final String updateWorkflowDefinitionSql = """
      update batch.workflow_definition set trigger_mode = 'EVENT', enabled = true where tenant_id = ? and id = ?\
      """;
    final String updateWorkflowDefinitionSql2 = """
      update batch.workflow_definition set trigger_mode = 'MIXED', enabled = true where tenant_id = ? and id = ?\
      """;

    String tenantId = unique("tenant");
    String calendarCode = unique("calendar");
    Long eventWorkflowId = insertWorkflowDefinition(tenantId, calendarCode);
    Long mixedWorkflowId = insertWorkflowDefinition(tenantId, calendarCode);
    insertWorkflowDefinition(tenantId, calendarCode);
    jdbcTemplate.update(updateWorkflowDefinitionSql, tenantId, eventWorkflowId);
    jdbcTemplate.update(updateWorkflowDefinitionSql2, tenantId, mixedWorkflowId);

    assertThat(batchDayInstanceMapper.countUntrackedTriggerDefinitions(tenantId, calendarCode))
        .isEqualTo(2);
  }

  @Test
  @DisplayName("空日门禁只统计指定日历业务日尚未终结的显式事件/文件预期项")
  void shouldCountOpenExpectedWorkForExactCalendarAndBizDate() {
    String tenantId = unique("tenant");
    String calendarCode = unique("calendar");
    String fileGroup = unique("file-group");
    Long workflowDefinitionId = insertWorkflowDefinition(tenantId, calendarCode);
    LocalDate bizDate = LocalDate.of(2026, 10, 13);
    assertThat(expectedWorkOccurrenceMapper.insertIfAbsent(
            ExpectedWorkOccurrenceInsertIfAbsentParam.builder()
                .tenantId(tenantId)
                .calendarCode(calendarCode)
                .bizDate(bizDate)
                .workflowDefinitionId(workflowDefinitionId)
                .sourceType("FILE_GROUP")
                .sourceKey(fileGroup)
                .expectedAt(null)
                .dueAt(null)
                .build()))
        .isEqualTo(1);
    assertThat(expectedWorkOccurrenceMapper.insertIfAbsent(
            ExpectedWorkOccurrenceInsertIfAbsentParam.builder()
                .tenantId(tenantId)
                .calendarCode(calendarCode)
                .bizDate(bizDate)
                .workflowDefinitionId(workflowDefinitionId)
                .sourceType("FILE_GROUP")
                .sourceKey(fileGroup)
                .expectedAt(null)
                .dueAt(null)
                .build()))
        .isZero();
    assertThat(expectedWorkOccurrenceMapper.countBlockingByCalendarBizDate(
            tenantId, calendarCode, bizDate))
        .isOne();
    assertThat(expectedWorkOccurrenceMapper.transitionStatus(
            ExpectedWorkOccurrenceTransitionStatusByIdentityParam.builder()
                .tenantId(tenantId)
                .calendarCode(calendarCode)
                .workflowDefinitionId(workflowDefinitionId)
                .sourceType("FILE_GROUP")
                .sourceKey(fileGroup)
                .bizDate(bizDate)
                .expectedStatus("EXPECTED")
                .newStatus("ARRIVED")
                .triggerRequestId(null)
                .build()))
        .isOne();
    assertThat(expectedWorkOccurrenceMapper.countBlockingByCalendarBizDate(
            tenantId, calendarCode, bizDate))
        .isOne();
    TriggerRequestEntity request = new TriggerRequestEntity();
    request.setTenantId(tenantId);
    request.setRequestId("request-1");
    request.setTriggerType("API");
    request.setWorkflowDefinitionId(workflowDefinitionId);
    request.setBizDate(bizDate);
    request.setDedupKey(unique("dedup"));
    request.setRequestStatus("ACCEPTED");
    triggerRequestMapper.insert(request);
    assertThat(expectedWorkOccurrenceMapper.transitionStatus(
            ExpectedWorkOccurrenceTransitionStatusByIdentityParam.builder()
                .tenantId(tenantId)
                .calendarCode(calendarCode)
                .workflowDefinitionId(workflowDefinitionId)
                .sourceType("FILE_GROUP")
                .sourceKey(fileGroup)
                .bizDate(bizDate)
                .expectedStatus("ARRIVED")
                .newStatus("SATISFIED")
                .triggerRequestId("request-1")
                .build()))
        .isOne();
    assertThat(expectedWorkOccurrenceMapper.countBlockingByCalendarBizDate(
            tenantId, calendarCode, bizDate))
        .isZero();
  }

  @Test
  @DisplayName("人工确认空日会取消并审计未履行文件/事件义务")
  void shouldCancelAndAuditExpectedWorkWhenExplicitlySettlingEmptyDay() {
    final String insertBusinessCalendarSql = """
        insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone)\s\
        values (?, ?, ?, 'Asia/Shanghai')\
        """;
    final String updateWorkflowDefinitionSql = """
        update batch.workflow_definition set trigger_mode = 'EVENT', enabled = false where tenant_id = ?\s\
        and id = ?\
        """;
    final String updateBatchDayInstanceSql = """
        update batch.batch_day_instance set day_status = 'CUTOFF', cutoff_at = current_timestamp\s\
        where tenant_id = ? and calendar_code = ? and biz_date = ?\
        """;
    final String selectOccurrenceStatusSql = """
        select occurrence_status from batch.expected_work_occurrence where tenant_id = ? and calendar_code = ?\s\
        and biz_date = ? order by source_type\
        """;
    final String selectRequestPayloadSql = """
        select request_payload::text from batch.batch_day_operation_audit where tenant_id = ?\s\
        and calendar_code = ? and biz_date = ? and operation_type = 'BATCH_DAY_SETTLE_EMPTY'\
        """;

    String tenantId = unique("tenant");
    String calendarCode = unique("calendar");
    LocalDate bizDate = LocalDate.of(2026, 10, 15);
    String fileGroupCode = unique("file-group");
    String eventCode = unique("event");
    Long workflowDefinitionId = insertWorkflowDefinition(tenantId, calendarCode);
    jdbcTemplate.update(insertBusinessCalendarSql, tenantId, calendarCode, calendarCode);
    jdbcTemplate.update(updateWorkflowDefinitionSql, tenantId, workflowDefinitionId);
    insertOpenBatchDay(tenantId, calendarCode, bizDate);
    jdbcTemplate.update(updateBatchDayInstanceSql, tenantId, calendarCode, bizDate);
    expectedWorkOccurrenceMapper.insertIfAbsent(ExpectedWorkOccurrenceInsertIfAbsentParam.builder()
        .tenantId(tenantId)
        .calendarCode(calendarCode)
        .bizDate(bizDate)
        .workflowDefinitionId(workflowDefinitionId)
        .sourceType("FILE_GROUP")
        .sourceKey(fileGroupCode)
        .expectedAt(Instant.parse("2026-10-15T01:00:00Z"))
        .dueAt(Instant.parse("2026-10-15T03:00:00Z"))
        .build());
    expectedWorkOccurrenceMapper.insertIfAbsent(ExpectedWorkOccurrenceInsertIfAbsentParam.builder()
        .tenantId(tenantId)
        .calendarCode(calendarCode)
        .bizDate(bizDate)
        .workflowDefinitionId(workflowDefinitionId)
        .sourceType("EXTERNAL_EVENT")
        .sourceKey(eventCode)
        .expectedAt(Instant.parse("2026-10-15T01:00:00Z"))
        .dueAt(Instant.parse("2026-10-15T03:00:00Z"))
        .build());
    expectedWorkOccurrenceMapper.transitionStatus(
        ExpectedWorkOccurrenceTransitionStatusByIdentityParam.builder()
            .tenantId(tenantId)
            .calendarCode(calendarCode)
            .workflowDefinitionId(workflowDefinitionId)
            .sourceType("FILE_GROUP")
            .sourceKey(fileGroupCode)
            .bizDate(bizDate)
            .expectedStatus("EXPECTED")
            .newStatus("ARRIVED")
            .triggerRequestId(null)
            .build());

    BatchDayOperationService.BatchDayOperationResult result =
        transactionTemplate.execute(status -> {
          final String setLocalSql = """
              SET LOCAL ROLE %s\
              """.formatted(RLS_ROLE);
          jdbcTemplate.execute(setLocalSql);
          return batchDayOperationService.operate(BatchDayOperateCommand.builder()
              .tenantId(tenantId)
              .calendarCode(calendarCode)
              .bizDate(bizDate)
              .action(BatchDayOperationService.BatchDayOperation.SETTLE_EMPTY)
              .operatorId("ops-user")
              .reason("upstream confirmed no delivery is expected")
              .build());
        });

    assertThat(result.batchDay().dayStatus()).isEqualTo("SETTLED");
    assertThat(expectedWorkOccurrenceMapper.countBlockingByCalendarBizDate(
            tenantId, calendarCode, bizDate))
        .isZero();
    List<String> selectedOccurrenceStatusValues = jdbcTemplate.queryForList(
        selectOccurrenceStatusSql, String.class, tenantId, calendarCode, bizDate);
    assertThat(selectedOccurrenceStatusValues).containsExactly("CANCELLED", "CANCELLED");
    String auditPayload = jdbcTemplate.queryForObject(
        selectRequestPayloadSql, String.class, tenantId, calendarCode, bizDate);
    assertThat(auditPayload).contains("waivedExpectedWork", fileGroupCode, eventCode, "ops-user");
  }

  @Test
  @DisplayName("重开空日保留被豁免终态并为原声明义务创建新代次")
  void shouldCreateNewExpectedWorkGenerationWhenReopeningSettledEmptyDay() {
    final String updateBatchDayInstanceSql = """
        update batch.batch_day_instance set day_status = 'CUTOFF', cutoff_at = current_timestamp\s\
        where tenant_id = ? and calendar_code = ? and biz_date = ?\
        """;
    final String selectGenerationSql = """
        select generation || ':' || occurrence_status from batch.expected_work_occurrence where tenant_id = ?\s\
        and calendar_code = ? and biz_date = ? and workflow_definition_id = ? and source_key = ?\s\
        order by generation\
        """;
    final String selectRequestPayloadSql = """
        select request_payload::text from batch.batch_day_operation_audit where tenant_id = ?\s\
        and calendar_code = ? and biz_date = ? and operation_type = 'BATCH_DAY_REOPEN'\
        """;

    String tenantId = unique("tenant");
    String calendarCode = unique("calendar");
    LocalDate bizDate = LocalDate.of(2026, 10, 16);
    String fileGroupCode = unique("file-group");
    Long workflowDefinitionId = insertWorkflowDefinition(tenantId, calendarCode);
    insertOpenBatchDay(tenantId, calendarCode, bizDate);
    jdbcTemplate.update(updateBatchDayInstanceSql, tenantId, calendarCode, bizDate);
    expectedWorkOccurrenceMapper.insertIfAbsent(ExpectedWorkOccurrenceInsertIfAbsentParam.builder()
        .tenantId(tenantId)
        .calendarCode(calendarCode)
        .bizDate(bizDate)
        .workflowDefinitionId(workflowDefinitionId)
        .sourceType("FILE_GROUP")
        .sourceKey(fileGroupCode)
        .expectedAt(Instant.parse("2026-10-16T01:00:00Z"))
        .dueAt(Instant.parse("2026-10-16T03:00:00Z"))
        .build());

    transactionTemplate.execute(status -> {
      final String setLocalSql = """
          SET LOCAL ROLE %s\
          """.formatted(RLS_ROLE);
      jdbcTemplate.execute(setLocalSql);
      return batchDayOperationService.operate(BatchDayOperateCommand.builder()
          .tenantId(tenantId)
          .calendarCode(calendarCode)
          .bizDate(bizDate)
          .action(BatchDayOperationService.BatchDayOperation.SETTLE_EMPTY)
          .operatorId("ops-user")
          .reason("confirmed empty")
          .build());
    });
    BatchDayOperationService.BatchDayOperationResult reopened =
        transactionTemplate.execute(status -> {
          final String setLocalSql = """
              SET LOCAL ROLE %s\
              """.formatted(RLS_ROLE);
          jdbcTemplate.execute(setLocalSql);
          return batchDayOperationService.operate(BatchDayOperateCommand.builder()
              .tenantId(tenantId)
              .calendarCode(calendarCode)
              .bizDate(bizDate)
              .action(BatchDayOperationService.BatchDayOperation.REOPEN)
              .operatorId("ops-user")
              .reason("late source correction")
              .build());
        });

    assertThat(reopened.batchDay().dayStatus()).isEqualTo("IN_FLIGHT");
    assertThat(expectedWorkOccurrenceMapper.countBlockingByCalendarBizDate(
            tenantId, calendarCode, bizDate))
        .isOne();
    List<String> selectedGenerationValues = jdbcTemplate.queryForList(
        selectGenerationSql,
        String.class,
        tenantId,
        calendarCode,
        bizDate,
        workflowDefinitionId,
        fileGroupCode);
    assertThat(selectedGenerationValues).containsExactly("1:CANCELLED", "2:EXPECTED");
    String reopenAudit = jdbcTemplate.queryForObject(
        selectRequestPayloadSql, String.class, tenantId, calendarCode, bizDate);
    assertThat(reopenAudit)
        .contains(
            "reopenedExpectedWorkCount",
            "1",
            "preservedTerminalOccurrences",
            "openExpectedWorkAfterReopen",
            fileGroupCode,
            "generation",
            "2");
  }

  @Test
  @DisplayName("预期项 Mapper 在不可绕过 RLS 的角色下设置租户并隔离跨租户更新")
  void shouldApplyExpectedWorkTenantContextForNonBypassRlsRole() {
    final String updateExpectedWorkOccurrenceSql = """
        update batch.expected_work_occurrence set occurrence_status = 'CANCELLED' where tenant_id = ?\s\
        and calendar_code = ? and biz_date = ?\
        """;

    String tenantA = unique("tenant-a");
    String tenantB = unique("tenant-b");
    String calendarA = unique("calendar-a");
    String calendarB = unique("calendar-b");
    LocalDate bizDate = LocalDate.of(2026, 10, 15);
    Long workflowA = insertWorkflowDefinition(tenantA, calendarA);
    Long workflowB = insertWorkflowDefinition(tenantB, calendarB);
    String sourceA = unique("source-a");
    String sourceB = unique("source-b");
    Instant expectedAt = Instant.parse("2026-10-15T01:00:00Z");
    expectedWorkOccurrenceMapper.insertIfAbsent(ExpectedWorkOccurrenceInsertIfAbsentParam.builder()
        .tenantId(tenantA)
        .calendarCode(calendarA)
        .bizDate(bizDate)
        .workflowDefinitionId(workflowA)
        .sourceType("FILE_GROUP")
        .sourceKey(sourceA)
        .expectedAt(expectedAt)
        .dueAt(null)
        .build());
    expectedWorkOccurrenceMapper.insertIfAbsent(ExpectedWorkOccurrenceInsertIfAbsentParam.builder()
        .tenantId(tenantB)
        .calendarCode(calendarB)
        .bizDate(bizDate)
        .workflowDefinitionId(workflowB)
        .sourceType("FILE_GROUP")
        .sourceKey(sourceB)
        .expectedAt(expectedAt)
        .dueAt(null)
        .build());

    int[] observed = transactionTemplate.execute(status -> {
      final String setLocalSql = """
          SET LOCAL ROLE %s\
          """.formatted(RLS_ROLE);
      jdbcTemplate.execute(setLocalSql);
      expectedWorkOccurrenceMapper.setTenantContext(tenantA);
      int visibleForTenantA =
          expectedWorkOccurrenceMapper.countBlockingByCalendarBizDate(tenantA, calendarA, bizDate);
      int crossTenantUpdates =
          jdbcTemplate.update(updateExpectedWorkOccurrenceSql, tenantB, calendarB, bizDate);
      int ownTenantUpdates =
          jdbcTemplate.update(updateExpectedWorkOccurrenceSql, tenantA, calendarA, bizDate);
      return new int[] {visibleForTenantA, crossTenantUpdates, ownTenantUpdates};
    });

    assertThat(observed).containsExactly(1, 0, 1);
  }

  @Test
  @DisplayName("到达前登记入口在真实数据库中验证作业日历并使未到文件阻塞空日")
  void shouldRegisterPreArrivalExpectationAndBlockEmptySettlement() {
    String tenantId = unique("tenant");
    String calendarCode = unique("calendar");
    String jobCode = unique("file-job");
    String fileGroupCode = unique("expected-file-group");
    LocalDate bizDate = LocalDate.of(2026, 10, 14);
    Long jobDefinitionId = insertJobDefinition(tenantId, calendarCode, jobCode, null);
    Long workflowDefinitionId =
        insertManagedSingleJobWorkflow(tenantId, calendarCode, jobCode, jobDefinitionId);

    var request = new ExpectedWorkRegistrationService.FileGroupExpectation(
        tenantId,
        calendarCode,
        workflowDefinitionId,
        fileGroupCode,
        bizDate,
        Instant.parse("2026-10-14T01:00:00Z"),
        Instant.parse("2026-10-14T03:00:00Z"));
    assertThat(expectedWorkRegistrationService.register(request).inserted()).isTrue();
    assertThat(expectedWorkRegistrationService.register(request).inserted()).isFalse();
    assertThat(expectedWorkOccurrenceMapper.countBlockingByCalendarBizDate(
            tenantId, calendarCode, bizDate))
        .isOne();
    assertThatThrownBy(() -> expectedWorkRegistrationService.register(
            new ExpectedWorkRegistrationService.FileGroupExpectation(
                tenantId,
                unique("wrong-calendar"),
                workflowDefinitionId,
                unique("wrong-group"),
                bizDate,
                Instant.parse("2026-10-14T01:00:00Z"),
                null)))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  @DisplayName("开启业务日时按已配置文件组规则幂等生成当天预期项")
  void shouldMaterializeRecurringFileExpectationsForOpenedBusinessDay() {
    final String selectExpectedAtSql = """
        select expected_at, due_at, occurrence_status from batch.expected_work_occurrence where tenant_id = ?\s\
        and calendar_code = ? and biz_date = ? and workflow_definition_id = ? and source_type = 'FILE_GROUP'\s\
        and source_key = ?\
        """;

    String tenantId = unique("tenant");
    String calendarCode = unique("calendar");
    String jobCode = unique("file-job");
    String fileGroupCode = unique("daily-group");
    LocalDate bizDate = LocalDate.of(2026, 10, 14);
    Long jobDefinitionId = insertJobDefinition(tenantId, calendarCode, jobCode, null);
    Long workflowDefinitionId =
        insertManagedSingleJobWorkflow(tenantId, calendarCode, jobCode, jobDefinitionId);
    fileGroupExpectationService.configure(new FileGroupExpectationService.DefinitionRequest(
        tenantId,
        calendarCode,
        workflowDefinitionId,
        fileGroupCode,
        LocalTime.of(9, 15),
        LocalTime.of(10, 0),
        true));

    assertThat(fileGroupExpectationService.materializeForOpenedBusinessDay(
            tenantId, calendarCode, bizDate, "Asia/Shanghai"))
        .isEqualTo(1);
    assertThat(fileGroupExpectationService.materializeForOpenedBusinessDay(
            tenantId, calendarCode, bizDate, "Asia/Shanghai"))
        .isZero();
    Map<String, Object> expected = jdbcTemplate.queryForMap(
        selectExpectedAtSql, tenantId, calendarCode, bizDate, workflowDefinitionId, fileGroupCode);
    assertThat(expected)
        .containsEntry("expected_at", Timestamp.from(Instant.parse("2026-10-14T01:15:00Z")))
        .containsEntry("due_at", Timestamp.from(Instant.parse("2026-10-14T02:00:00Z")))
        .containsEntry("occurrence_status", "EXPECTED");
  }

  @Test
  @DisplayName("禁用 Workflow 不为未来业务日创建文件或事件预期,重新启用后可恢复生成")
  void shouldMaterializeRecurringExpectationsOnlyForEnabledWorkflow() {
    final String updateWorkflowDefinitionSql = """
      update batch.workflow_definition set trigger_mode = 'EVENT', schedule_enabled = false where tenant_id = ?\s\
      and id = ?\
      """;
    final String updateWorkflowDefinitionSql2 = """
      update batch.workflow_definition set enabled = false where tenant_id = ? and id = ?
  """;
    final String selectCountSql = """
      select count(*) from batch.expected_work_occurrence where tenant_id = ? and calendar_code = ?\s\
      and biz_date = ?\
      """;
    final String updateWorkflowDefinitionSql3 = """
      update batch.workflow_definition set enabled = true where tenant_id = ? and id = ?
  """;

    String tenantId = unique("tenant");
    String calendarCode = unique("calendar");
    String jobCode = unique("expectation-job");
    String fileGroupCode = unique("disabled-file-group");
    String eventCode = unique("disabled-event");
    LocalDate bizDate = LocalDate.of(2026, 10, 15);
    Long jobDefinitionId = insertJobDefinition(tenantId, calendarCode, jobCode, null);
    Long workflowDefinitionId =
        insertManagedSingleJobWorkflow(tenantId, calendarCode, jobCode, jobDefinitionId);
    jdbcTemplate.update(updateWorkflowDefinitionSql, tenantId, workflowDefinitionId);
    fileGroupExpectationService.configure(new FileGroupExpectationService.DefinitionRequest(
        tenantId,
        calendarCode,
        workflowDefinitionId,
        fileGroupCode,
        LocalTime.of(9, 15),
        LocalTime.of(10, 0),
        true));
    externalEventExpectationService.configure(new ExternalEventExpectationService.DefinitionRequest(
        tenantId,
        calendarCode,
        workflowDefinitionId,
        eventCode,
        LocalTime.of(9, 15),
        LocalTime.of(10, 0),
        true));
    assertThat(fileGroupExpectationService.materializeForOpenedBusinessDay(
            tenantId, calendarCode, bizDate, "Asia/Shanghai"))
        .isOne();
    assertThat(externalEventExpectationService.materializeForOpenedBusinessDay(
            tenantId, calendarCode, bizDate, "Asia/Shanghai"))
        .isOne();
    jdbcTemplate.update(updateWorkflowDefinitionSql2, tenantId, workflowDefinitionId);

    LocalDate disabledBizDate = bizDate.plusDays(1);
    assertThat(fileGroupExpectationService.materializeForOpenedBusinessDay(
            tenantId, calendarCode, disabledBizDate, "Asia/Shanghai"))
        .isZero();
    assertThat(externalEventExpectationService.materializeForOpenedBusinessDay(
            tenantId, calendarCode, disabledBizDate, "Asia/Shanghai"))
        .isZero();
    Integer selectedExpectedWorkOccurrenceCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, tenantId, calendarCode, bizDate);
    assertThat(selectedExpectedWorkOccurrenceCount).isEqualTo(2);
    Integer selectedExpectedWorkOccurrenceCountRead2 = jdbcTemplate.queryForObject(
        selectCountSql, Integer.class, tenantId, calendarCode, disabledBizDate);
    assertThat(selectedExpectedWorkOccurrenceCountRead2).isZero();

    jdbcTemplate.update(updateWorkflowDefinitionSql3, tenantId, workflowDefinitionId);
    assertThat(fileGroupExpectationService.materializeForOpenedBusinessDay(
            tenantId, calendarCode, disabledBizDate, "Asia/Shanghai"))
        .isOne();
    assertThat(externalEventExpectationService.materializeForOpenedBusinessDay(
            tenantId, calendarCode, disabledBizDate, "Asia/Shanghai"))
        .isOne();
    Integer selectedExpectedWorkOccurrenceCountRead3 = jdbcTemplate.queryForObject(
        selectCountSql, Integer.class, tenantId, calendarCode, disabledBizDate);
    assertThat(selectedExpectedWorkOccurrenceCountRead3).isEqualTo(2);
  }

  @Test
  @DisplayName("定义停用后，已启动的关联事件请求仍能结清既有每日预期")
  void shouldMaterializeAndSatisfyRecurringEventExpectationAfterLaunch() {
    final String updateWorkflowDefinitionSql = """
      update batch.workflow_definition set trigger_mode = 'EVENT' where tenant_id = ? and id = ?
  """;
    final String updateWorkflowDefinitionSql2 = """
      update batch.workflow_definition set enabled = false where tenant_id = ? and id = ?
  """;

    String tenantId = unique("tenant");
    String calendarCode = unique("calendar");
    String jobCode = unique("event-job");
    String eventCode = unique("daily-event");
    String requestId = unique("request");
    LocalDate bizDate = LocalDate.of(2026, 10, 14);
    Long jobDefinitionId = insertJobDefinition(tenantId, calendarCode, jobCode, null);
    Long workflowDefinitionId =
        insertManagedSingleJobWorkflow(tenantId, calendarCode, jobCode, jobDefinitionId);
    jdbcTemplate.update(updateWorkflowDefinitionSql, tenantId, workflowDefinitionId);
    assertThat(batchDayInstanceMapper.countUntrackedTriggerDefinitions(tenantId, calendarCode))
        .isOne();

    // 已持久化的外部事件可能在周期性预期物化之前到达。
    TriggerRequestEntity request = new TriggerRequestEntity();
    request.setTenantId(tenantId);
    request.setRequestId(requestId);
    request.setTriggerType("EVENT");
    request.setExternalEventCode(eventCode);
    request.setWorkflowDefinitionId(workflowDefinitionId);
    request.setBizDate(bizDate);
    request.setDedupKey(unique("dedup"));
    request.setRequestStatus("ACCEPTED");
    request.setTraceId(unique("trace"));
    triggerRequestMapper.insert(request);

    externalEventExpectationService.configure(new ExternalEventExpectationService.DefinitionRequest(
        tenantId,
        calendarCode,
        workflowDefinitionId,
        eventCode,
        LocalTime.of(9, 15),
        LocalTime.of(10, 0),
        true));
    assertThat(batchDayInstanceMapper.countUntrackedTriggerDefinitions(tenantId, calendarCode))
        .isZero();

    assertThat(externalEventExpectationService.materializeForOpenedBusinessDay(
            tenantId, calendarCode, bizDate, "Asia/Shanghai"))
        .isEqualTo(1);
    assertThat(externalEventExpectationService.materializeForOpenedBusinessDay(
            tenantId, calendarCode, bizDate, "Asia/Shanghai"))
        .isZero();
    assertThat(expectedWorkOccurrenceMapper.countBlockingByCalendarBizDate(
            tenantId, calendarCode, bizDate))
        .isOne();

    assertThatThrownBy(() -> externalEventExpectationService.markSatisfied(
            new ExternalEventExpectationService.SatisfactionRequest(
                tenantId, calendarCode, workflowDefinitionId, eventCode, bizDate, requestId)))
        .isInstanceOf(ResponseStatusException.class);

    Long launchedJobInstanceId = insertJobInstance(
        tenantId, jobDefinitionId, jobCode, bizDate, "EVENT", request.getId(), "RUNNING", false);
    assertThat(triggerRequestMapper.updateAcceptance(
            tenantId, requestId, "LAUNCHED", launchedJobInstanceId))
        .isOne();
    jdbcTemplate.update(updateWorkflowDefinitionSql2, tenantId, workflowDefinitionId);
    assertThatThrownBy(() -> externalEventExpectationService.markSatisfied(
            new ExternalEventExpectationService.SatisfactionRequest(
                tenantId,
                calendarCode,
                workflowDefinitionId,
                unique("other-event"),
                bizDate,
                requestId)))
        .isInstanceOf(ResponseStatusException.class);
    assertThat(externalEventExpectationService.markSatisfied(
            new ExternalEventExpectationService.SatisfactionRequest(
                tenantId, calendarCode, workflowDefinitionId, eventCode, bizDate, requestId)))
        .isTrue();
    assertThat(externalEventExpectationService.markSatisfied(
            new ExternalEventExpectationService.SatisfactionRequest(
                tenantId, calendarCode, workflowDefinitionId, eventCode, bizDate, requestId)))
        .isFalse();
    assertThat(expectedWorkOccurrenceMapper.countBlockingByCalendarBizDate(
            tenantId, calendarCode, bizDate))
        .isZero();
    assertThat(batchDayInstanceMapper.countUntrackedTriggerDefinitions(tenantId, calendarCode))
        .isZero();
  }

  @Test
  @DisplayName("外部事件先于业务日预期物化完成启动时,物化后直接结清对应预期")
  void shouldSatisfyMaterializedEventWhenLaunchPrecedesExpectation() {
    final String updateWorkflowDefinitionSql = """
        update batch.workflow_definition set trigger_mode = 'EVENT' where tenant_id = ? and id = ?
    """;
    final String selectOccurrenceStatusSql = """
        select occurrence_status, trigger_request_id from batch.expected_work_occurrence where tenant_id = ?\s\
        and calendar_code = ? and biz_date = ? and workflow_definition_id = ? and source_type = 'EXTERNAL_EVENT'\s\
        and source_key = ?\
        """;

    String tenantId = unique("tenant");
    String calendarCode = unique("calendar");
    String jobCode = unique("event-job");
    String eventCode = unique("daily-event");
    String requestId = unique("request");
    LocalDate bizDate = LocalDate.of(2026, 10, 23);
    Long jobDefinitionId = insertJobDefinition(tenantId, calendarCode, jobCode, null);
    Long workflowDefinitionId =
        insertManagedSingleJobWorkflow(tenantId, calendarCode, jobCode, jobDefinitionId);
    jdbcTemplate.update(updateWorkflowDefinitionSql, tenantId, workflowDefinitionId);
    externalEventExpectationService.configure(new ExternalEventExpectationService.DefinitionRequest(
        tenantId,
        calendarCode,
        workflowDefinitionId,
        eventCode,
        LocalTime.of(9, 15),
        LocalTime.of(10, 0),
        true));
    TriggerRequestEntity launched = new TriggerRequestEntity();
    launched.setTenantId(tenantId);
    launched.setRequestId(requestId);
    launched.setTriggerType("EVENT");
    launched.setExternalEventCode(eventCode);
    launched.setWorkflowDefinitionId(workflowDefinitionId);
    launched.setBizDate(bizDate);
    launched.setDedupKey(unique("dedup"));
    launched.setRequestStatus("LAUNCHED");
    launched.setTraceId(unique("trace"));
    triggerRequestMapper.insert(launched);

    assertThat(externalEventExpectationService.materializeForOpenedBusinessDay(
            tenantId, calendarCode, bizDate, "Asia/Shanghai"))
        .isOne();

    assertThat(expectedWorkOccurrenceMapper.countBlockingByCalendarBizDate(
            tenantId, calendarCode, bizDate))
        .isZero();
    Map<String, Object> selectedOccurrenceStatusRow = jdbcTemplate.queryForMap(
        selectOccurrenceStatusSql,
        tenantId,
        calendarCode,
        bizDate,
        workflowDefinitionId,
        eventCode);
    assertThat(selectedOccurrenceStatusRow)
        .containsEntry("occurrence_status", "SATISFIED")
        .containsEntry("trigger_request_id", requestId);
  }

  @ParameterizedTest(name = "{0} 先等待业务日围栏时事件预期与结算最终收敛")
  @ValueSource(strings = {"SETTLEMENT_FIRST", "SATISFACTION_FIRST"})
  @DisplayName("外部事件满足与空日结算按两种围栏顺序竞争")
  void shouldSerializeEventSatisfactionWithEmptyDaySettlement(String firstWaiter) throws Exception {
    final String updateWorkflowDefinitionSql = """
        update batch.workflow_definition set trigger_mode = 'EVENT' where tenant_id = ? and id = ?
    """;
    final String updateBatchDayInstanceSql = """
        update batch.batch_day_instance set day_status = 'CUTOFF' where tenant_id = ? and calendar_code = ?\s\
        and biz_date = ?\
        """;

    String tenantId = unique("tenant");
    String calendarCode = unique("calendar");
    String jobCode = unique("event-job");
    String eventCode = unique("daily-event");
    String requestId = unique("request");
    LocalDate bizDate = LocalDate.of(2026, 10, 22);
    Long jobDefinitionId = insertJobDefinition(tenantId, calendarCode, jobCode, null);
    Long workflowDefinitionId =
        insertManagedSingleJobWorkflow(tenantId, calendarCode, jobCode, jobDefinitionId);
    jdbcTemplate.update(updateWorkflowDefinitionSql, tenantId, workflowDefinitionId);
    insertOpenBatchDay(tenantId, calendarCode, bizDate);
    jdbcTemplate.update(updateBatchDayInstanceSql, tenantId, calendarCode, bizDate);
    externalEventExpectationService.configure(new ExternalEventExpectationService.DefinitionRequest(
        tenantId,
        calendarCode,
        workflowDefinitionId,
        eventCode,
        LocalTime.of(9, 15),
        LocalTime.of(10, 0),
        true));
    externalEventExpectationService.materializeForOpenedBusinessDay(
        tenantId, calendarCode, bizDate, "Asia/Shanghai");
    TriggerRequestEntity request = new TriggerRequestEntity();
    request.setTenantId(tenantId);
    request.setRequestId(requestId);
    request.setTriggerType("EVENT");
    request.setExternalEventCode(eventCode);
    request.setWorkflowDefinitionId(workflowDefinitionId);
    request.setBizDate(bizDate);
    request.setDedupKey(unique("dedup"));
    request.setRequestStatus("LAUNCHED");
    request.setTraceId(unique("trace"));
    triggerRequestMapper.insert(request);

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try (Connection fence = jdbcTemplate.getDataSource().getConnection()) {
      fence.setAutoCommit(false);
      acquireSettlementFence(fence, tenantId, calendarCode, bizDate);
      int fencePid = backendPid(fence);
      Future<?> settlement;
      Future<Boolean> satisfaction;
      if ("SETTLEMENT_FIRST".equals(firstWaiter)) {
        settlement = executor.submit(batchDaySettleScheduler::settle);
        awaitBlockedBy(fencePid);
        satisfaction = executor.submit(() -> externalEventExpectationService.markSatisfied(
            new ExternalEventExpectationService.SatisfactionRequest(
                tenantId, calendarCode, workflowDefinitionId, eventCode, bizDate, requestId)));
      } else {
        satisfaction = executor.submit(() -> externalEventExpectationService.markSatisfied(
            new ExternalEventExpectationService.SatisfactionRequest(
                tenantId, calendarCode, workflowDefinitionId, eventCode, bizDate, requestId)));
        awaitBlockedBy(fencePid);
        settlement = executor.submit(batchDaySettleScheduler::settle);
      }
      awaitBlockedByCount(fencePid, 2);
      fence.commit();
      settlement.get(10, TimeUnit.SECONDS);
      assertThat(satisfaction.get(10, TimeUnit.SECONDS)).isTrue();

      // 结算和预期满足会争抢围栏：结算可能先观察到仍开放的预期，也可能由满足操作
      // 先获胜并允许同一轮完成结算。之后的扫描无论发生哪种顺序都必须收敛。
      batchDaySettleScheduler.settle();
      assertThat(expectedWorkOccurrenceMapper.countBlockingByCalendarBizDate(
              tenantId, calendarCode, bizDate))
          .isZero();
      assertThat(dayStatus(tenantId, calendarCode, bizDate)).isEqualTo("SETTLED");
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  @DisplayName("文件与事件预期登记共享结算围栏并拒绝终态后的迟到登记")
  void shouldSerializeExpectedWorkMaterializationWithSettlement() throws Exception {
    final String updateWorkflowDefinitionSql = """
      update batch.workflow_definition set trigger_mode = 'EVENT' where tenant_id = ? and id = ?
  """;
    final String updateBatchDayInstanceSql = """
      update batch.batch_day_instance set day_status = 'CUTOFF' where tenant_id = ? and calendar_code = ?\s\
      and biz_date = ?\
      """;
    final String updateBatchDayInstanceSql2 = """
      update batch.batch_day_instance set day_status = 'CUTOFF' where tenant_id = ? and calendar_code = ?\s\
      and biz_date = ?\
      """;
    final String selectCountSql = """
        select count(*) from batch.expected_work_occurrence where tenant_id = ? and calendar_code = ?\s\
        and biz_date = ?\
        """;

    String tenantId = unique("tenant");
    String calendarCode = unique("calendar");
    String jobCode = unique("event-job");
    String eventCode = unique("daily-event");
    LocalDate expectationFirstDate = LocalDate.of(2026, 10, 20);
    LocalDate settlementFirstDate = expectationFirstDate.plusDays(1);
    Long jobDefinitionId = insertJobDefinition(tenantId, calendarCode, jobCode, null);
    Long workflowDefinitionId =
        insertManagedSingleJobWorkflow(tenantId, calendarCode, jobCode, jobDefinitionId);
    jdbcTemplate.update(updateWorkflowDefinitionSql, tenantId, workflowDefinitionId);
    externalEventExpectationService.configure(new ExternalEventExpectationService.DefinitionRequest(
        tenantId,
        calendarCode,
        workflowDefinitionId,
        eventCode,
        LocalTime.of(9, 15),
        LocalTime.of(10, 0),
        true));
    fileGroupExpectationService.configure(new FileGroupExpectationService.DefinitionRequest(
        tenantId,
        calendarCode,
        workflowDefinitionId,
        unique("daily-file-group"),
        LocalTime.of(9, 0),
        LocalTime.of(10, 0),
        true));
    insertOpenBatchDay(tenantId, calendarCode, expectationFirstDate);
    insertOpenBatchDay(tenantId, calendarCode, settlementFirstDate);
    jdbcTemplate.update(updateBatchDayInstanceSql, tenantId, calendarCode, expectationFirstDate);

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      try (Connection fence = jdbcTemplate.getDataSource().getConnection()) {
        fence.setAutoCommit(false);
        acquireSettlementFence(fence, tenantId, calendarCode, expectationFirstDate);
        int fencePid = backendPid(fence);
        Future<Integer> materialization =
            executor.submit(() -> externalEventExpectationService.materializeForOpenedBusinessDay(
                tenantId, calendarCode, expectationFirstDate, "Asia/Shanghai"));
        awaitBlockedBy(fencePid);
        fence.commit();
        assertThat(materialization.get(10, TimeUnit.SECONDS)).isOne();
        assertThat(fileGroupExpectationService.materializeForOpenedBusinessDay(
                tenantId, calendarCode, expectationFirstDate, "Asia/Shanghai"))
            .isOne();
      }
      batchDaySettleScheduler.settle();
      assertThat(dayStatus(tenantId, calendarCode, expectationFirstDate)).isEqualTo("CUTOFF");
      assertThat(expectedWorkOccurrenceMapper.countBlockingByCalendarBizDate(
              tenantId, calendarCode, expectationFirstDate))
          .isEqualTo(2);

      jdbcTemplate.update(updateBatchDayInstanceSql2, tenantId, calendarCode, settlementFirstDate);
      try (Connection fence = jdbcTemplate.getDataSource().getConnection()) {
        fence.setAutoCommit(false);
        acquireSettlementFence(fence, tenantId, calendarCode, settlementFirstDate);
        int fencePid = backendPid(fence);
        Future<?> settlement = executor.submit(batchDaySettleScheduler::settle);
        awaitBlockedBy(fencePid);
        fence.commit();
        settlement.get(10, TimeUnit.SECONDS);
      }
      assertThat(dayStatus(tenantId, calendarCode, settlementFirstDate)).isEqualTo("SETTLED");
      assertThatThrownBy(() -> externalEventExpectationService.materializeForOpenedBusinessDay(
              tenantId, calendarCode, settlementFirstDate, "Asia/Shanghai"))
          .isInstanceOf(ResponseStatusException.class);
      assertThatThrownBy(() -> fileGroupExpectationService.materializeForOpenedBusinessDay(
              tenantId, calendarCode, settlementFirstDate, "Asia/Shanghai"))
          .isInstanceOf(ResponseStatusException.class);
      Integer selectedExpectedWorkOccurrenceCount = jdbcTemplate.queryForObject(
          selectCountSql, Integer.class, tenantId, calendarCode, settlementFirstDate);
      assertThat(selectedExpectedWorkOccurrenceCount).isZero();
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  @DisplayName("EVENT 作业使用逐日文件组预期时不再被当作无跟踪定义")
  void shouldTreatRecurringFileGroupAsTrackingForEventJob() {
    final String updateWorkflowDefinitionSql = """
        update batch.workflow_definition set trigger_mode = 'EVENT' where tenant_id = ? and id = ?
    """;

    String tenantId = unique("tenant");
    String calendarCode = unique("calendar");
    String jobCode = unique("event-file-job");
    Long jobDefinitionId = insertJobDefinition(tenantId, calendarCode, jobCode, null);
    Long workflowDefinitionId =
        insertManagedSingleJobWorkflow(tenantId, calendarCode, jobCode, jobDefinitionId);
    jdbcTemplate.update(updateWorkflowDefinitionSql, tenantId, workflowDefinitionId);
    assertThat(batchDayInstanceMapper.countUntrackedTriggerDefinitions(tenantId, calendarCode))
        .isOne();

    fileGroupExpectationService.configure(new FileGroupExpectationService.DefinitionRequest(
        tenantId,
        calendarCode,
        workflowDefinitionId,
        unique("daily-group"),
        LocalTime.of(9, 0),
        LocalTime.of(10, 0),
        true));
    assertThat(batchDayInstanceMapper.countUntrackedTriggerDefinitions(tenantId, calendarCode))
        .isZero();
  }

  @Test
  @DisplayName("前一业务日查询跳过节假日并接受周末工作日覆盖")
  void shouldResolveCalendarAwarePreviousBusinessDate() {
    final String insertBusinessCalendarSql = """
      insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone)\s\
      values (?, ?, ?, 'Asia/Shanghai')\
      """;
    final String selectIdSql = """
      select id from batch.business_calendar where tenant_id = ? and calendar_code = ?
  """;
    final String insertCalendarHolidaySql = """
      insert into batch.calendar_holiday (tenant_id, calendar_id, biz_date, day_type, scope)\s\
      values (?, ?, ?, 'HOLIDAY', 'CALENDAR')\
      """;
    final String insertCalendarHolidaySql2 = """
      insert into batch.calendar_holiday (tenant_id, calendar_id, biz_date, day_type, scope)\s\
      values (?, ?, ?, 'WORKDAY_OVERRIDE', 'CALENDAR')\
      """;

    String tenantId = unique("tenant");
    String calendarCode = unique("calendar");
    String jobCode = unique("downstream");
    LocalDate monday = LocalDate.of(2026, 10, 12);
    jdbcTemplate.update(insertBusinessCalendarSql, tenantId, calendarCode, calendarCode);
    Long calendarId = jdbcTemplate.queryForObject(selectIdSql, Long.class, tenantId, calendarCode);
    insertJobDefinition(tenantId, calendarCode, jobCode, null);
    jdbcTemplate.update(insertCalendarHolidaySql, tenantId, calendarId, LocalDate.of(2026, 10, 9));
    String overrideCalendarCode = unique("calendar-override");
    jdbcTemplate.update(
        insertBusinessCalendarSql, tenantId, overrideCalendarCode, overrideCalendarCode);
    Long overrideCalendarId =
        jdbcTemplate.queryForObject(selectIdSql, Long.class, tenantId, overrideCalendarCode);
    LocalDate workdayOverride = LocalDate.of(2026, 10, 10);
    jdbcTemplate.update(insertCalendarHolidaySql2, tenantId, overrideCalendarId, workdayOverride);

    assertThat(jobDependencyMapper.selectCalendarCodeByTenantAndJob(tenantId, jobCode))
        .isEqualTo(calendarCode);
    assertThat(planCalendarMapper.selectPreviousBusinessDate(tenantId, calendarCode, monday))
        .isEqualTo(LocalDate.of(2026, 10, 8));
    assertThat(
            planCalendarMapper.selectPreviousBusinessDate(tenantId, overrideCalendarCode, monday))
        .isEqualTo(workdayOverride);
  }

  @Test
  @DisplayName("空日结算只接受当前定义版本已覆盖至 cutoff 的所有启用定时作业")
  void shouldRequireCurrentScheduleCoverageThroughCutoff() {
    final String insertTenantSql = """
        insert into batch.tenant (tenant_id, tenant_name, status) values (?, ?, 'ACTIVE')
    """;
    final String insertBusinessCalendarSql = """
        insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone,\s\
        holiday_roll_rule) values (?, ?, 'Coverage Calendar', 'UTC', 'SKIP')\
        """;
    final String insertJobDefinitionSql = """
          insert into batch.job_definition(
            tenant_id, job_code, job_name, job_type, schedule_type, schedule_expr, timezone,
            calendar_code, trigger_mode, version, enabled
          ) values (?, ?, 'Coverage Test', 'GENERAL', 'CRON', '0 0 2 * * ?',
            'Asia/Shanghai', ?, 'SCHEDULED', 7, true)
          returning id
          """;
    final String insertWorkflowDefinitionSql = """
          insert into batch.workflow_definition(
            tenant_id, workflow_code, workflow_name, workflow_type, version, enabled,
            schedule_type, schedule_expr, timezone, trigger_mode, calendar_code, schedule_enabled
          ) values (?, ?, 'Coverage Workflow', 'DAG', 7, true, 'CRON', '0 0 2 * * ?',
            'Asia/Shanghai', 'SCHEDULED', ?, true)
          returning id
          """;
    final String insertScheduledOccurrenceCoverageSql = """
          insert into batch.scheduled_occurrence_coverage(
            tenant_id, workflow_definition_id, schedule_version, calendar_code,
            coverage_from_at, coverage_through_at
          ) values (?, ?, 6, ?, ?, ?)
          """;
    final String updateScheduledOccurrenceCoverageSql = """
      update batch.scheduled_occurrence_coverage set schedule_version = 7 where tenant_id = ?\s\
      and workflow_definition_id = ?\
      """;
    final String updateBusinessCalendarSql = """
      update batch.business_calendar set cutoff_time = '05:00:00' where tenant_id = ? and calendar_code = ?\
      """;
    final String updateScheduledOccurrenceCoverageSql2 = """
      update batch.scheduled_occurrence_coverage set last_success_at = clock_timestamp() where tenant_id = ?\s\
      and workflow_definition_id = ?\
      """;
    final String updateScheduledOccurrenceCoverageSql3 = """
      update batch.scheduled_occurrence_coverage set coverage_from_at = ? where tenant_id = ?\s\
      and workflow_definition_id = ?\
      """;

    String tenantId = unique("tenant");
    String calendarCode = unique("calendar");
    String jobCode = unique("scheduled-job");
    jdbcTemplate.update(insertTenantSql, tenantId, tenantId);
    jdbcTemplate.update(insertBusinessCalendarSql, tenantId, calendarCode);
    Long definitionId = jdbcTemplate.queryForObject(
        insertJobDefinitionSql, Long.class, tenantId, jobCode, calendarCode);
    Long workflowDefinitionId = jdbcTemplate.queryForObject(
        insertWorkflowDefinitionSql, Long.class, tenantId, "wf-" + jobCode, calendarCode);
    Instant cutoff = Instant.parse("2026-09-08T08:00:00Z");

    assertThat(batchDayInstanceMapper.countUncoveredScheduledDefinitions(
            tenantId, calendarCode, cutoff))
        .isEqualTo(1);

    jdbcTemplate.update(
        insertScheduledOccurrenceCoverageSql,
        tenantId,
        workflowDefinitionId,
        calendarCode,
        Timestamp.from(cutoff.minusSeconds(3600)),
        Timestamp.from(cutoff.plusSeconds(1)));
    assertThat(batchDayInstanceMapper.countUncoveredScheduledDefinitions(
            tenantId, calendarCode, cutoff))
        .isEqualTo(1);

    jdbcTemplate.update(updateScheduledOccurrenceCoverageSql, tenantId, workflowDefinitionId);
    assertThat(batchDayInstanceMapper.countUncoveredScheduledDefinitions(
            tenantId, calendarCode, cutoff))
        .isZero();

    jdbcTemplate.update(updateBusinessCalendarSql, tenantId, calendarCode);
    assertThat(batchDayInstanceMapper.countUncoveredScheduledDefinitions(
            tenantId, calendarCode, cutoff))
        .isEqualTo(1);
    jdbcTemplate.update(updateScheduledOccurrenceCoverageSql2, tenantId, workflowDefinitionId);
    assertThat(batchDayInstanceMapper.countUncoveredScheduledDefinitions(
            tenantId, calendarCode, cutoff))
        .isZero();
    jdbcTemplate.update(
        updateScheduledOccurrenceCoverageSql3,
        Timestamp.from(cutoff.plusSeconds(1)),
        tenantId,
        workflowDefinitionId);
    assertThat(batchDayInstanceMapper.countUncoveredScheduledDefinitions(
            tenantId, calendarCode, cutoff))
        .isEqualTo(1);
    assertThat(definitionId).isNotNull();
  }

  @Test
  @DisplayName("写入批量日实例时由应用侧提供的状态,计数与快照默认值如实落库")
  void shouldPersistJavaProvidedDefaults_whenInsertingBatchDay() {
    final String selectDayStatusSql = """
          select day_status, late_count, catchup_count, timezone_snapshot, frozen, version, dry_run
            from batch.batch_day_instance
           where tenant_id = ? and calendar_code = ? and biz_date = ?
          """;

    String tenantId = unique("tenant");
    String calendarCode = unique("calendar");
    LocalDate bizDate = LocalDate.of(2026, 9, 9);
    Instant now = Instant.parse("2026-09-09T00:00:00Z");
    BatchDayInstanceEntity entity = BatchDayInstanceEntity.builder()
        .tenantId(tenantId)
        .calendarCode(calendarCode)
        .bizDate(bizDate)
        .dayStatus("OPEN")
        .openAt(now)
        .lateCount(0)
        .catchupCount(0)
        .timezoneSnapshot("Asia/Shanghai")
        .dstPolicySnapshot("gap=RUN_AT_NEXT_VALID_TIME;overlap=RUN_ONCE_EARLIER_OFFSET")
        .frozen(false)
        .version(0L)
        .createdAt(now)
        .updatedAt(now)
        .dryRun(false)
        .build();

    assertThat(batchDayInstanceMapper.insert(entity)).isOne();

    Map<String, Object> stored =
        jdbcTemplate.queryForMap(selectDayStatusSql, tenantId, calendarCode, bizDate);
    assertThat(stored)
        .containsEntry("day_status", "OPEN")
        .containsEntry("late_count", 0)
        .containsEntry("catchup_count", 0)
        .containsEntry("timezone_snapshot", "Asia/Shanghai")
        .containsEntry("frozen", false)
        .containsEntry("version", 0L)
        .containsEntry("dry_run", false);
  }

  private Long insertJobDefinition(
      String tenantId, String calendarCode, String jobCode, String jobGroupCode) {
    final String insertJobDefinitionSql = """
    insert into batch.job_definition(
    tenant_id, job_code, job_name, job_type, schedule_type, timezone, calendar_code,
    job_group_code
    ) values (?, ?, 'Batch Day Metrics Test', 'GENERAL', 'MANUAL', 'Asia/Shanghai', ?, ?)
    returning id
    """;

    return jdbcTemplate.queryForObject(
        insertJobDefinitionSql, Long.class, tenantId, jobCode, calendarCode, jobGroupCode);
  }

  private Long insertManagedSingleJobWorkflow(
      String tenantId, String calendarCode, String jobCode, Long jobDefinitionId) {
    final String insertWorkflowDefinitionSql = """
        insert into batch.workflow_definition (tenant_id, workflow_code, workflow_name, workflow_type,\s\
        single_job_definition_id, calendar_code) values (?, ?, ?, 'DAG', ?, ?) returning id\
        """;

    return jdbcTemplate.queryForObject(
        insertWorkflowDefinitionSql,
        Long.class,
        tenantId,
        unique("managed-workflow-" + jobCode),
        "Managed Workflow for " + jobCode,
        jobDefinitionId,
        calendarCode);
  }

  private void insertJobInstance(
      String tenantId,
      Long definitionId,
      String jobCode,
      LocalDate bizDate,
      String status,
      boolean dryRun) {
    insertJobInstance(tenantId, definitionId, jobCode, bizDate, "MANUAL", null, status, dryRun);
  }

  private Long insertJobInstance(
      String tenantId,
      Long definitionId,
      String jobCode,
      LocalDate bizDate,
      String triggerType,
      Long triggerRequestDatabaseId,
      String status,
      boolean dryRun) {
    final String insertJobInstanceSql = """
    insert into batch.job_instance(
    tenant_id, job_definition_id, trigger_request_id, job_code, instance_no, biz_date,
    trigger_type,
    instance_status, priority, dedup_key, expected_partition_count,
    success_partition_count, failed_partition_count, trace_id, dry_run
    ) values (?, ?, ?, ?, ?, ?, ?, ?, 5, ?, 0, 0, 0, ?, ?)
    returning id
    """;

    return jdbcTemplate.queryForObject(
        insertJobInstanceSql,
        Long.class,
        tenantId,
        definitionId,
        triggerRequestDatabaseId,
        jobCode,
        unique("instance"),
        bizDate,
        triggerType,
        status,
        unique("dedup"),
        unique("trace"),
        dryRun);
  }

  private String unique(String prefix) {
    return prefix + "-" + UUID.randomUUID();
  }
}
