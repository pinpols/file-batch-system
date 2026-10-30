package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.application.config.ConsoleConfigSyncApplicationService;
import io.github.pinpols.batch.console.application.config.ConsoleTenantConfigCopyService;
import io.github.pinpols.batch.console.application.config.ConsoleTenantConfigInitApplicationService;
import io.github.pinpols.batch.console.application.contract.request.config.ConfigSyncBundlePayload;
import io.github.pinpols.batch.console.application.contract.request.config.ConfigSyncImportRequest;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.BusinessCalendarSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.JobDefinitionSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigCopyRequest;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigCopyRequest.ConfigType;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigBatchInitResponse;
import io.github.pinpols.batch.console.domain.config.param.ConfigSyncLogUpdateParam;
import io.github.pinpols.batch.console.domain.job.application.contract.request.JobDependencyRuleRequest;
import io.github.pinpols.batch.console.domain.rbac.service.ConsoleResourceTagService;
import io.github.pinpols.batch.console.domain.rbac.service.ResourceTagUpsertInput;
import io.github.pinpols.batch.console.mapper.ConfigSyncLogMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Import(TenantConfigDependencyRlsIntegrationTest.RoleTransactions.class)
@DisplayName("租户配置依赖在普通数据库角色下导出及跨租户应用")
class TenantConfigDependencyRlsIntegrationTest extends AbstractIntegrationTest {

  private static final ThreadLocal<Boolean> APPLICATION_ROLE = new ThreadLocal<>();
  private static final ThreadLocal<SourceReadBarrier> SOURCE_READ_BARRIER = new ThreadLocal<>();

  @Autowired
  private JdbcTemplate jdbc;

  @Autowired
  private PlatformTransactionManager transactions;

  @Autowired
  private ConsoleTenantConfigInitApplicationService initService;

  @Autowired
  private ConsoleTenantConfigCopyService copyService;

  @Autowired
  private ConsoleConfigSyncApplicationService syncService;

  @Autowired
  private DataSource dataSource;

  @Autowired
  private ConfigSyncLogMapper syncLogMapper;

  @Autowired
  private ConsoleResourceTagService resourceTagService;

  private String sourceTenant;
  private List<String> targets;
  private String upstreamA;
  private String upstreamB;
  private String downstream;

  @BeforeEach
  void prepareTenantsAndApplicationRole() {
    final String insertTenantSql = """
          insert into batch.tenant (tenant_id, tenant_name, status) values (?, ?, 'ACTIVE')
          """;
    final String doStatementSql = """
        DO $$ BEGIN
          IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'config_dependency_app_test') THEN
            CREATE ROLE config_dependency_app_test NOSUPERUSER NOBYPASSRLS;
          END IF;
        END $$
        """;
    final String grantUsageSql = """
        GRANT USAGE ON SCHEMA batch TO config_dependency_app_test
    """;
    final String grantSelectSql = """
        GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA batch TO config_dependency_app_test\
        """;
    final String grantUsageSql2 = """
        GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA batch TO config_dependency_app_test
        """;

    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    sourceTenant = "dependency-source-" + suffix;
    targets = List.of("dependency-target-a-" + suffix, "dependency-target-b-" + suffix);
    upstreamA = "PARENT_A_" + suffix;
    upstreamB = "PARENT_B_" + suffix;
    downstream = "CHILD_" + suffix;
    for (String tenant : List.of(sourceTenant, targets.getFirst(), targets.getLast())) {
      jdbc.update(insertTenantSql, tenant, tenant);
      TestTenantCalendarFixtures.ensureDefaultCalendar(jdbc, tenant);
    }
    jdbc.execute(doStatementSql);
    jdbc.execute(grantUsageSql);
    jdbc.execute(grantSelectSql);
    jdbc.execute(grantUsageSql2);
  }

  @Test
  @DisplayName("导出在无继承数据库租户上下文的普通角色下保留全部依赖及逐边业务日对齐")
  void shouldExportAllDependencyRulesUnderNonBypassRole() {
    assertThat(initService
            .batchInit(requestFor(List.of(sourceTenant)), "test", "seed")
            .successTenants())
        .isOne();

    ConfigSyncBundlePayload bundle = asApplicationRole(
        () -> copyService.buildBundle(sourceTenant, Set.of(ConfigType.JOB_DEFINITION)));

    assertThat(bundle.getJobDefinitions()).hasSize(3);
    assertThat(bundle.getJobDefinitions())
        .filteredOn(job -> downstream.equals(job.getJobCode()))
        .singleElement()
        .satisfies(job -> assertThat(job.getDependencyRules()).containsExactlyElementsOf(rules()));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("源配置并发修改时导出同一快照且不继承调用方READ COMMITTED隔离级别")
  void shouldExportOneSourceSnapshotAcrossConcurrentEdits(boolean outerReadOnly) throws Exception {
    final String selectWorkflowCodeSql = """
        select workflow.workflow_code from batch.workflow_definition workflow
        join batch.job_definition job on job.tenant_id = workflow.tenant_id
          and job.id = workflow.single_job_definition_id
        where job.tenant_id = ? and job.job_code = ?
        """;
    final String selectWorkflowNameSql = """
        select workflow_name from batch.workflow_definition where tenant_id = ? and workflow_code = ?
        """;
    final String updateJobDefinitionSql = """
            update batch.job_definition set job_name = ? where tenant_id = ? and job_code = ?
            """;
    final String updateWorkflowDefinitionSql = """
            update batch.workflow_definition set workflow_name = ?
            where tenant_id = ? and workflow_code = ?
            """;

    assertThat(initService
            .batchInit(requestFor(List.of(sourceTenant)), "test", "seed-snapshot")
            .successTenants())
        .isOne();
    String workflowCode =
        jdbc.queryForObject(selectWorkflowCodeSql, String.class, sourceTenant, downstream);
    String originalWorkflowName =
        jdbc.queryForObject(selectWorkflowNameSql, String.class, sourceTenant, workflowCode);
    SourceReadBarrier barrier =
        new SourceReadBarrier(sourceTenant, new CountDownLatch(1), new CountDownLatch(1));
    var executor = Executors.newSingleThreadExecutor();
    ConfigSyncBundlePayload bundle;
    try {
      var reading = executor.submit(() -> {
        SOURCE_READ_BARRIER.set(barrier);
        try {
          return asApplicationRole(
              outerReadOnly,
              () -> copyService.buildBundle(
                  sourceTenant, Set.of(ConfigType.JOB_DEFINITION, ConfigType.WORKFLOW_DEFINITION)));
        } finally {
          SOURCE_READ_BARRIER.remove();
        }
      });
      assertThat(barrier.jobsRead().await(20, TimeUnit.SECONDS)).isTrue();
      // 真实查询已读完Job，同一独立事务提交两种配置的新版本，再放行后续Workflow查询。
      new TransactionTemplate(transactions).executeWithoutResult(status -> {
        int affectedJobDefinitionRows =
            jdbc.update(updateJobDefinitionSql, "edited-job-name", sourceTenant, downstream);
        assertThat(affectedJobDefinitionRows).isOne();
        int affectedWorkflowDefinitionRows = jdbc.update(
            updateWorkflowDefinitionSql, "edited-workflow-name", sourceTenant, workflowCode);
        assertThat(affectedWorkflowDefinitionRows).isOne();
      });
      barrier.continueReading().countDown();
      bundle = reading.get(30, TimeUnit.SECONDS);
    } finally {
      barrier.continueReading().countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
    }
    assertThat(bundle.getJobDefinitions())
        .filteredOn(job -> downstream.equals(job.getJobCode()))
        .singleElement()
        .satisfies(job -> {
          assertThat(job.getJobName()).isEqualTo(downstream);
          assertThat(job.getDependencyRules()).containsExactlyElementsOf(rules());
        });
    assertThat(bundle.getWorkflowDefinitions())
        .filteredOn(workflow -> workflowCode.equals(workflow.getWorkflowCode()))
        .singleElement()
        .satisfies(
            workflow -> assertThat(workflow.getWorkflowName()).isEqualTo(originalWorkflowName));
    String selectedWorkflowName =
        jdbc.queryForObject(selectWorkflowNameSql, String.class, sourceTenant, workflowCode);
    assertThat(selectedWorkflowName).isEqualTo("edited-workflow-name");
  }

  @Test
  @DisplayName("Config Sync 在普通角色下为两个目标租户保存完整依赖且按六项记录日志")
  void shouldImportDependencyBundleIntoTwoTenantsUnderNonBypassRole() {
    final String selectUpstreamJobCodeSql = """
          select upstream_job_code || ':' || biz_date_alignment from batch.job_dependency
          where tenant_id = ? and downstream_job_code = ? order by dependency_order
          """;
    final String selectTotalItemsSql = """
        select total_items, success_items, failed_items, skipped_items, sync_status
        from batch.config_sync_log where tenant_id = ? and source_env = ?
        """;

    ConfigSyncBundlePayload bundle = new ConfigSyncBundlePayload();
    bundle.setJobDefinitions(requestFor(targets).getJobDefinitions());
    ConfigSyncImportRequest request = new ConfigSyncImportRequest();
    request.setTenantId(sourceTenant);
    request.setSourceEnv(sourceTenant);
    request.setTargetEnv("integration-test");
    request.setTargetTenantIds(targets);
    request.setBundle(bundle);
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
    try {
      var response = asApplicationRole(() -> syncService.importBundle(request));
      assertThat(response.result().successTenants()).isEqualTo(2);
      assertThat(response.result().failureTenants()).isZero();
    } finally {
      RequestContextHolder.resetRequestAttributes();
    }
    for (String target : targets) {
      List<String> selectedUpstreamJobCodeValues =
          jdbc.queryForList(selectUpstreamJobCodeSql, String.class, target, downstream);
      assertThat(selectedUpstreamJobCodeValues)
          .containsExactly(upstreamA + ":PREVIOUS_BIZ_DAY", upstreamB + ":SAME_DAY");
    }
    Map<String, Object> selectedTotalItemsRow =
        jdbc.queryForMap(selectTotalItemsSql, sourceTenant, sourceTenant);
    assertThat(selectedTotalItemsRow)
        .containsEntry("total_items", 6)
        .containsEntry("success_items", 6)
        .containsEntry("failed_items", 0)
        .containsEntry("skipped_items", 0)
        .containsEntry("sync_status", "SUCCESS");
  }

  private TenantConfigBatchInitRequest requestFor(List<String> tenants) {
    JobDefinitionSpec child = job(downstream);
    child.setDependencyRules(rules());
    TenantConfigBatchInitRequest request = new TenantConfigBatchInitRequest();
    request.setTargetTenantIds(tenants);
    request.setStrict(true);
    request.setJobDefinitions(List.of(child, job(upstreamB), job(upstreamA)));
    return request;
  }

  @Test
  @DisplayName("Config Sync一个目标后置失败时保留成功租户并准确记录三项成功三项失败")
  void shouldRecordPartialSyncWhenOneTenantPostprocessingFails() {
    final String deleteBusinessCalendarSql = """
        delete from batch.business_calendar where tenant_id = ?
    """;
    final String selectCountSql = """
        select count(*) from batch.job_definition where tenant_id = ?
    """;
    final String selectCountSql2 = """
        select count(*) from batch.workflow_definition where tenant_id = ?
    """;
    final String selectCountSql3 = """
        select count(*) from batch.job_dependency where tenant_id = ?
    """;
    final String selectTotalItemsSql = """
        select total_items, success_items, failed_items, skipped_items, sync_status
        from batch.config_sync_log where tenant_id = ? and source_env = ?
        """;

    String failedTenant = targets.getLast();
    jdbc.update(deleteBusinessCalendarSql, failedTenant);
    ConfigSyncBundlePayload bundle = new ConfigSyncBundlePayload();
    bundle.setJobDefinitions(requestFor(targets).getJobDefinitions());
    ConfigSyncImportRequest request = new ConfigSyncImportRequest();
    request.setTenantId(sourceTenant);
    request.setSourceEnv(sourceTenant);
    request.setTargetEnv("integration-test");
    request.setTargetTenantIds(targets);
    request.setBundle(bundle);
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
    try {
      var response = asApplicationRole(() -> syncService.importBundle(request));
      assertThat(response.result().successTenants()).isOne();
      assertThat(response.result().failureTenants()).isOne();
    } finally {
      RequestContextHolder.resetRequestAttributes();
    }
    Integer selectedJobDefinitionCount =
        jdbc.queryForObject(selectCountSql, Integer.class, targets.getFirst());
    assertThat(selectedJobDefinitionCount).isEqualTo(3);
    Integer selectedJobDefinitionCountRead2 =
        jdbc.queryForObject(selectCountSql, Integer.class, failedTenant);
    assertThat(selectedJobDefinitionCountRead2).isZero();
    Integer selectedWorkflowDefinitionCount =
        jdbc.queryForObject(selectCountSql2, Integer.class, failedTenant);
    assertThat(selectedWorkflowDefinitionCount).isZero();
    Integer selectedJobDependencyCount =
        jdbc.queryForObject(selectCountSql3, Integer.class, failedTenant);
    assertThat(selectedJobDependencyCount).isZero();
    Map<String, Object> selectedTotalItemsRow =
        jdbc.queryForMap(selectTotalItemsSql, sourceTenant, sourceTenant);
    assertThat(selectedTotalItemsRow)
        .containsEntry("total_items", 6)
        .containsEntry("success_items", 3)
        .containsEntry("failed_items", 3)
        .containsEntry("skipped_items", 0)
        .containsEntry("sync_status", "PARTIAL_FAILED");
    String selectReceiptOutcomeSql = """
        select tenant_id, result_json->>'success' as success
        from batch.config_sync_target_receipt where audit_tenant_id = ? order by tenant_id
        """;
    List<Map<String, Object>> receipts = jdbc.queryForList(selectReceiptOutcomeSql, sourceTenant);
    assertThat(receipts).hasSize(2);
    assertThat(receipts)
        .anySatisfy(receipt -> assertThat(receipt)
            .containsEntry("tenant_id", targets.getFirst())
            .containsEntry("success", "true"));
    assertThat(receipts)
        .anySatisfy(receipt -> assertThat(receipt)
            .containsEntry("tenant_id", failedTenant)
            .containsEntry("success", "false"));
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @DisplayName("目标已提交后日志写入失败仍保留审计且瞬态重试不重复执行配置")
  void shouldRetainAuditWhenFinalLogWriteFails(boolean transientFailure) {
    final String selectCountSql = """
        select count(*) from batch.job_definition where tenant_id = ?
    """;
    final String selectCountSql2 = """
        select count(*) from batch.job_dependency where tenant_id = ?
    """;
    final String selectTotalItemsSql = """
          select total_items, success_items, failed_items, skipped_items, sync_status,
            detail_json::text as detail
          from batch.config_sync_log where tenant_id = ? and source_env = ?
          """;

    ConfigSyncBundlePayload bundle = new ConfigSyncBundlePayload();
    bundle.setJobDefinitions(requestFor(targets).getJobDefinitions());
    ConfigSyncImportRequest request = new ConfigSyncImportRequest();
    request.setTenantId(sourceTenant);
    request.setSourceEnv(sourceTenant);
    request.setTargetEnv("audit-fault-test");
    request.setTargetTenantIds(targets);
    request.setBundle(bundle);
    String suffix = UUID.randomUUID().toString().replace("-", "");
    String function = "test_sync_audit_fault_" + suffix;
    String sequence = "test_sync_audit_attempt_" + suffix;
    String trigger = "aa_" + function;
    final String createSequenceSql = """
        create sequence batch.%s\
        """.formatted(sequence);
    jdbc.execute(createSequenceSql);
    final String grantUsageSql = """
        grant usage, select on sequence batch.%s to config_dependency_app_test\
        """.formatted(sequence);
    jdbc.execute(grantUsageSql);
    final String createFunctionSql =
        """
        create function batch.%s() returns trigger language plpgsql as $$
        begin
          if not %s or nextval('batch.%s') = 1 then
            raise exception 'SQL /private/TestMapper.xml audit persistence fault'
              using errcode = '%s', schema = 'batch', table = 'config_sync_log',
                constraint = 'test_config_sync_audit_fault';
          end if;
          return NEW;
        end;
        $$
        """.formatted(function, transientFailure, sequence, transientFailure ? "40001" : "23514");
    jdbc.execute(createFunctionSql);
    final String createTriggerSql = """
        create trigger %s before update on batch.config_sync_log for each row
        when (NEW.tenant_id = '%s' and NEW.source_env = '%s')
        execute function batch.%s()
        """.formatted(trigger, sourceTenant, sourceTenant, function);
    jdbc.execute(createTriggerSql);
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
    try {
      Throwable failure =
          catchThrowable(() -> asApplicationRole(() -> syncService.importBundle(request)));
      if (EmptyChecks.isNotNull(failure)) {
        assertThat(NestedExceptionUtils.getMostSpecificCause(failure))
            .isInstanceOfSatisfying(
                SQLException.class,
                sql ->
                    assertThat(sql.getSQLState()).isEqualTo(transientFailure ? "40001" : "23514"));
      }
      for (String target : targets) {
        Integer selectedJobDefinitionCount =
            jdbc.queryForObject(selectCountSql, Integer.class, target);
        assertThat(selectedJobDefinitionCount).isEqualTo(3);
        Integer selectedJobDependencyCount =
            jdbc.queryForObject(selectCountSql2, Integer.class, target);
        assertThat(selectedJobDependencyCount).isEqualTo(2);
      }
      List<Map<String, Object>> selectedTotalItemsValues =
          jdbc.queryForList(selectTotalItemsSql, sourceTenant, sourceTenant);
      assertThat(selectedTotalItemsValues).singleElement().satisfies(log -> {
        assertThat(log)
            .containsEntry("total_items", 6)
            .containsEntry("failed_items", 0)
            .containsEntry("skipped_items", 0);
        assertThat(log)
            .containsEntry("sync_status", transientFailure ? "SUCCESS" : "RUNNING")
            .containsEntry("success_items", transientFailure ? 6 : 0);
        assertThat(log.get("detail").toString()).doesNotContain("TestMapper.xml", "SQL ");
      });
      if (transientFailure) {
        assertThat(failure).isNull();
        // 若重跑了业务导入，SKIP_EXISTING会把第一次创建的6项改记为skipped。
        final String selectLastValueSql = """
            select last_value from batch.%s\
            """.formatted(sequence);
        Long selectedLastValue = jdbc.queryForObject(selectLastValueSql, Long.class);
        assertThat(selectedLastValue).isEqualTo(2);
      } else {
        assertThat(failure)
            .isInstanceOfSatisfying(
                BizException.class,
                error -> assertThat(error.getCode()).isEqualTo(ResultCode.SYSTEM_ERROR));
      }
    } finally {
      RequestContextHolder.resetRequestAttributes();
      final String dropTriggerSql = """
          drop trigger if exists %s on batch.config_sync_log\
          """.formatted(trigger);
      jdbc.execute(dropTriggerSql);
      final String dropFunctionSql = """
          drop function if exists batch.%s()\
          """.formatted(function);
      jdbc.execute(dropFunctionSql);
      final String dropSequenceSql = """
          drop sequence if exists batch.%s\
          """.formatted(sequence);
      jdbc.execute(dropSequenceSql);
    }
    if (!transientFailure) {
      String selectLogIdSql = "select id from batch.config_sync_log where tenant_id = ?";
      Long logId = jdbc.queryForObject(selectLogIdSql, Long.class, sourceTenant);
      RequestContextHolder.setRequestAttributes(
          new ServletRequestAttributes(new MockHttpServletRequest()));
      try {
        var recovered = asApplicationRole(() -> syncService.reconcile(sourceTenant, logId));
        assertThat(recovered.syncStatus()).isEqualTo("SUCCESS");
        assertThat(recovered.successItems()).isEqualTo(6);
        assertThat(recovered.skippedItems()).isZero();
        assertThat(recovered.failedItems()).isZero();
      } finally {
        RequestContextHolder.resetRequestAttributes();
      }
      String selectReceiptsSql = """
          select count(*) from batch.config_sync_target_receipt where audit_tenant_id = ?
          """;
      Integer receipts = jdbc.queryForObject(selectReceiptsSql, Integer.class, sourceTenant);
      assertThat(receipts).isEqualTo(2);
    }
  }

  @Test
  @DisplayName("未知结果审计补充错误时保留初始摘要与批次关联身份")
  void shouldPreserveInitialAuditMetadataWhenOutcomeIsUnknown() {
    final String insertConfigSyncLogSql = """
        insert into batch.config_sync_log
          (tenant_id, sync_direction, source_env, target_env, config_types, total_items, detail_json)
        values (?, 'IMPORT', ?, 'audit-test', 'jobDefinitions', 3,
          '{"batchOperationId":"audit-operation","summary":{"total":3},"dryRun":false}'::jsonb)
        returning id
        """;
    final String selectTotalItemsSql = """
        select total_items, sync_status, detail_json->>'batchOperationId' as operation,
          detail_json->'summary'->>'total' as summary_total,
          detail_json->>'error' as error, detail_json->>'outcomeKnown' as outcome_known
        from batch.config_sync_log where tenant_id = ? and id = ?
        """;

    Long logId =
        jdbc.queryForObject(insertConfigSyncLogSql, Long.class, sourceTenant, sourceTenant);
    ConfigSyncLogUpdateParam update = ConfigSyncLogUpdateParam.builder()
        .tenantId(sourceTenant)
        .id(logId)
        .syncStatus("RUNNING")
        .successItems(0)
        .failedItems(0)
        .skippedItems(0)
        .mergeDetail(true)
        .detailJson("{\"error\":\"error.common.system_error\",\"outcomeKnown\":false}")
        .build();

    assertThat(asApplicationRole(() -> {
          syncLogMapper.setTenantContext(sourceTenant);
          return syncLogMapper.updateResult(update);
        }))
        .isOne();

    Map<String, Object> selectedTotalItemsRow =
        jdbc.queryForMap(selectTotalItemsSql, sourceTenant, logId);
    assertThat(selectedTotalItemsRow)
        .containsEntry("total_items", 3)
        .containsEntry("sync_status", "RUNNING")
        .containsEntry("operation", "audit-operation")
        .containsEntry("summary_total", "3")
        .containsEntry("error", "error.common.system_error")
        .containsEntry("outcome_known", "false");
  }

  @Test
  @DisplayName("只读源事务中的严格复制仅回滚失败租户并保留已成功租户的依赖")
  void shouldIsolateTargetTransactionsFromReadOnlySourceAndOtherTenantFailure() {
    final String insertJobDependencySql = """
        insert into batch.job_dependency
          (tenant_id, downstream_job_code, upstream_job_code, dependency_order, biz_date_alignment)
        values (?, ?, ?, 0, 'SAME_DAY')
        """;
    final String selectUpstreamJobCodeSql = """
        select upstream_job_code || ':' || biz_date_alignment from batch.job_dependency
        where tenant_id = ? and downstream_job_code = ? order by dependency_order
        """;
    final String selectDownstreamJobCodeSql = """
        select downstream_job_code || ':' || upstream_job_code from batch.job_dependency
        where tenant_id = ?
        """;

    assertThat(initService
            .batchInit(requestFor(List.of(sourceTenant)), "test", "seed-source")
            .successTenants())
        .isOne();
    TenantConfigBatchInitRequest initialTargets = requestFor(targets);
    initialTargets.getJobDefinitions().getFirst().setDependencyRules(List.of());
    assertThat(initService.batchInit(initialTargets, "test", "seed-targets").successTenants())
        .isEqualTo(2);
    jdbc.update(insertJobDependencySql, targets.getLast(), upstreamA, downstream);
    TenantConfigCopyRequest request = new TenantConfigCopyRequest();
    request.setSourceTenantId(sourceTenant);
    request.setTargetTenantIds(targets);
    request.setConfigTypes(Set.of(ConfigType.JOB_DEFINITION));
    request.setJobCodes(List.of(downstream));
    request.setMode(TenantConfigBatchInitRequest.InitMode.UPSERT);

    var response = asApplicationRole(true, () -> copyService.copy(request, "test", "copy-targets"));

    assertThat(response.successTenants()).isOne();
    assertThat(response.failureTenants()).isOne();
    List<String> selectedUpstreamJobCodeValues =
        jdbc.queryForList(selectUpstreamJobCodeSql, String.class, targets.getFirst(), downstream);
    assertThat(selectedUpstreamJobCodeValues)
        .containsExactly(upstreamA + ":PREVIOUS_BIZ_DAY", upstreamB + ":SAME_DAY");
    List<String> selectedDownstreamJobCodeValues =
        jdbc.queryForList(selectDownstreamJobCodeSql, String.class, targets.getLast());
    assertThat(selectedDownstreamJobCodeValues).containsExactly(upstreamA + ":" + downstream);
  }

  private List<JobDependencyRuleRequest> rules() {
    return List.of(
        new JobDependencyRuleRequest(upstreamA, "PREVIOUS_BIZ_DAY"),
        new JobDependencyRuleRequest(upstreamB, "SAME_DAY"));
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @DisplayName("上游在应用预检后被并发删除时按稳定错误拒绝且严格及部分成功边界不变")
  void shouldRejectConcurrentUpstreamDeletionWithoutLeakingConstraintDetails(boolean strict)
      throws Exception {
    final String selectPgAdvisoryXactLockSql = """
            select pg_advisory_xact_lock(?)
        """;

    final String selectCountSql = """
                select count(*) from pg_locks where locktype = 'advisory' and not granted
                  and classid::bigint = ? and objid::bigint = ? and objsubid = 1
                """;
    final String selectIdSql = """
              select id from batch.job_definition where tenant_id = ? and job_code = ?
              """;
    final String selectIdSql2 = """
              select id from batch.workflow_definition
              where tenant_id = ? and single_job_definition_id = ?
              """;
    final String deleteWorkflowEdgeSql = """
            delete from batch.workflow_edge where tenant_id = ? and workflow_definition_id = ?
        """;
    final String deleteWorkflowNodeSql = """
            delete from batch.workflow_node where tenant_id = ? and workflow_definition_id = ?
        """;
    final String deleteWorkflowDefinitionSql = """
            delete from batch.workflow_definition where tenant_id = ? and id = ?
        """;
    final String deleteJobDefinitionSql = """
            delete from batch.job_definition where tenant_id = ? and id = ?
        """;
    final String selectCountSql2 = """
            select count(*) from batch.job_definition where tenant_id = ? and job_code = ?
        """;
    final String selectCountSql3 = """
            select count(*) from batch.job_dependency where tenant_id = ? and downstream_job_code = ?
        """;

    String tenant = targets.getFirst();
    TenantConfigBatchInitRequest parents = new TenantConfigBatchInitRequest();
    parents.setTargetTenantIds(List.of(tenant));
    parents.setStrict(true);
    parents.setJobDefinitions(List.of(job(upstreamA), job(upstreamB)));
    assertThat(initService.batchInit(parents, "test", "seed-parents").successTenants())
        .isOne();
    String sibling = "SIBLING_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    TenantConfigBatchInitRequest request = requestFor(List.of(tenant));
    request.setStrict(strict);
    request.setJobDefinitions(List.of(request.getJobDefinitions().getFirst(), job(sibling)));
    String function = "test_dependency_pause_" + UUID.randomUUID().toString().replace("-", "");
    String trigger = "aa_" + function;
    long barrierKey = UUID.randomUUID().getLeastSignificantBits() & Long.MAX_VALUE;
    final String createFunctionSql = """
        create function batch.%s() returns trigger language plpgsql as $$
        begin
          perform pg_advisory_xact_lock(%d);
          return NEW;
        end;
        $$
        """.formatted(function, barrierKey);
    jdbc.execute(createFunctionSql);
    final String createTriggerSql = """
        create trigger %s before insert on batch.job_definition for each row
        when (NEW.tenant_id = '%s' and NEW.job_code = '%s')
        execute function batch.%s()
        """.formatted(trigger, tenant, downstream, function);
    jdbc.execute(createTriggerSql);
    var executor = Executors.newSingleThreadExecutor();
    TenantConfigBatchInitResponse response;
    try (var blocker = dataSource.getConnection()) {
      blocker.setAutoCommit(false);
      try (var lock = blocker.prepareStatement(selectPgAdvisoryXactLockSql)) {
        lock.setLong(1, barrierKey);
        lock.execute();
      }
      var applying = executor.submit(
          () -> asApplicationRole(() -> initService.batchInit(request, "test", "parent-delete")));
      try {
        await()
            .atMost(Duration.ofSeconds(20))
            .pollInterval(Duration.ofMillis(50))
            .untilAsserted(() -> {
              Integer selectedPgLocksCount = jdbc.queryForObject(
                  selectCountSql, Integer.class, barrierKey >>> 32, barrierKey & 0xffff_ffffL);
              assertThat(selectedPgLocksCount).isOne();
            });
        // 独立事务在应用预检通过后、数据库外键核对前提交父对象删除。
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
          Long definitionId = jdbc.queryForObject(selectIdSql, Long.class, tenant, upstreamA);
          for (Long workflowId :
              jdbc.queryForList(selectIdSql2, Long.class, tenant, definitionId)) {
            jdbc.update(deleteWorkflowEdgeSql, tenant, workflowId);
            jdbc.update(deleteWorkflowNodeSql, tenant, workflowId);
            jdbc.update(deleteWorkflowDefinitionSql, tenant, workflowId);
          }
          int affectedJobDefinitionRows = jdbc.update(deleteJobDefinitionSql, tenant, definitionId);
          assertThat(affectedJobDefinitionRows).isOne();
        });
        blocker.commit();
        response = applying.get(30, TimeUnit.SECONDS);
      } finally {
        blocker.rollback();
      }
    } finally {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
      final String dropTriggerSql = """
          drop trigger if exists %s on batch.job_definition\
          """.formatted(trigger);
      jdbc.execute(dropTriggerSql);
      final String dropFunctionSql = """
          drop function if exists batch.%s()\
          """.formatted(function);
      jdbc.execute(dropFunctionSql);
    }
    assertThat(response.failureTenants()).isEqualTo(strict ? 1 : 0);
    if (!strict) {
      assertThat(response.results()).singleElement().satisfies(result -> {
        assertThat(result.jobDefinitions().failed()).isOne();
        assertThat(result.jobDefinitions().created()).isOne();
        assertThat(result.jobDefinitions().details())
            .filteredOn(detail -> "FAILED".equals(detail.action()))
            .singleElement()
            .satisfies(detail ->
                assertThat(detail.errorMessage()).isEqualTo("error.common.invalid_argument"));
      });
    }
    Integer selectedJobDefinitionCount =
        jdbc.queryForObject(selectCountSql2, Integer.class, tenant, sibling);
    assertThat(selectedJobDefinitionCount).isEqualTo(strict ? 0 : 1);
    Integer selectedJobDefinitionCountRead2 =
        jdbc.queryForObject(selectCountSql2, Integer.class, tenant, downstream);
    assertThat(selectedJobDefinitionCountRead2).isZero();
    Integer selectedJobDependencyCount =
        jdbc.queryForObject(selectCountSql3, Integer.class, tenant, downstream);
    assertThat(selectedJobDependencyCount).isZero();
    Integer selectedJobDefinitionCountRead3 =
        jdbc.queryForObject(selectCountSql2, Integer.class, tenant, upstreamA);
    assertThat(selectedJobDefinitionCountRead3).isZero();
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @DisplayName("后置受管Workflow创建失败时回滚该租户全部写入并保留已成功租户")
  void shouldRollbackTenantWhenManagedWorkflowProvisioningFails(boolean strict) {
    final String deleteBusinessCalendarSql = """
        delete from batch.business_calendar where tenant_id = ?
    """;
    final String selectCountSql = """
        select count(*) from batch.job_definition where tenant_id = ?
    """;
    final String selectCountSql2 = """
        select count(*) from batch.workflow_definition where tenant_id = ?
    """;
    final String selectCountSql3 = """
        select count(*) from batch.job_dependency where tenant_id = ?
    """;

    String failedTenant = targets.getLast();
    jdbc.update(deleteBusinessCalendarSql, failedTenant);
    TenantConfigBatchInitRequest request = requestFor(targets);
    request.setStrict(strict);

    var response = asApplicationRole(() -> initService.batchInit(request, "test", "fatal-target"));

    assertThat(response.successTenants()).isOne();
    assertThat(response.failureTenants()).isOne();
    Integer selectedJobDefinitionCount =
        jdbc.queryForObject(selectCountSql, Integer.class, targets.getFirst());
    assertThat(selectedJobDefinitionCount).isEqualTo(3);
    Integer selectedWorkflowDefinitionCount =
        jdbc.queryForObject(selectCountSql2, Integer.class, targets.getFirst());
    assertThat(selectedWorkflowDefinitionCount).isEqualTo(3);
    Integer selectedJobDefinitionCountRead2 =
        jdbc.queryForObject(selectCountSql, Integer.class, failedTenant);
    assertThat(selectedJobDefinitionCountRead2).isZero();
    Integer selectedJobDependencyCount =
        jdbc.queryForObject(selectCountSql3, Integer.class, failedTenant);
    assertThat(selectedJobDependencyCount).isZero();
    Integer selectedWorkflowDefinitionCountRead2 =
        jdbc.queryForObject(selectCountSql2, Integer.class, failedTenant);
    assertThat(selectedWorkflowDefinitionCountRead2).isZero();
  }

  @Test
  @DisplayName("空租户同包创建日历及多依赖Job时先落实日历再生成受管Workflow")
  void shouldApplyBundledCalendarBeforeJobsAndManagedWorkflows() {
    final String deleteBusinessCalendarSql = """
        delete from batch.business_calendar where tenant_id = ?
    """;
    final String selectCountSql = """
        select count(*) from batch.job_definition where tenant_id = ?
    """;
    final String selectCountSql2 = """
        select count(*) from batch.workflow_definition workflow
        join batch.business_calendar calendar on calendar.tenant_id = workflow.tenant_id
          and calendar.calendar_code = workflow.calendar_code
        where workflow.tenant_id = ? and workflow.single_job_definition_id is not null
        """;
    final String selectUpstreamJobCodeSql = """
        select upstream_job_code || ':' || biz_date_alignment from batch.job_dependency
        where tenant_id = ? and downstream_job_code = ? order by dependency_order
        """;

    String target = targets.getLast();
    jdbc.update(deleteBusinessCalendarSql, target);
    TenantConfigBatchInitRequest request = requestFor(List.of(target));
    BusinessCalendarSpec calendar = new BusinessCalendarSpec();
    calendar.setCalendarCode("default_calendar");
    calendar.setCalendarName("Bundled calendar");
    calendar.setTimezone("Asia/Shanghai");
    calendar.setEnabled(true);
    request.setBusinessCalendars(List.of(calendar));

    var response = asApplicationRole(() -> initService.batchInit(request, "test", "cold-target"));

    assertThat(response.successTenants()).isOne();
    assertThat(response.failureTenants()).isZero();
    assertThat(response.results())
        .singleElement()
        .satisfies(result -> assertThat(result.businessCalendars().created()).isOne());
    Integer selectedJobDefinitionCount = jdbc.queryForObject(selectCountSql, Integer.class, target);
    assertThat(selectedJobDefinitionCount).isEqualTo(3);
    Integer selectedWorkflowDefinitionCount =
        jdbc.queryForObject(selectCountSql2, Integer.class, target);
    assertThat(selectedWorkflowDefinitionCount).isEqualTo(3);
    List<String> selectedUpstreamJobCodeValues =
        jdbc.queryForList(selectUpstreamJobCodeSql, String.class, target, downstream);
    assertThat(selectedUpstreamJobCodeValues)
        .containsExactly(upstreamA + ":PREVIOUS_BIZ_DAY", upstreamB + ":SAME_DAY");
  }

  @Test
  @DisplayName("资源标签应用校验与数据库多态引用及父资源清理保持一致")
  void shouldGuardPolymorphicResourceTagReferences() {
    final String selectCountSql = """
          select count(*) from batch.resource_tag
          where tenant_id = ? and resource_type = ? and resource_code = ?
          """;
    final String insertResourceTagSql = """
          insert into batch.resource_tag
            (tenant_id, resource_type, resource_code, tag_key, tag_value)
          values (?, ?, ?, 'missing', '')
          """;

    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    List<ResourceTagFixture> fixtures = List.of(
        new ResourceTagFixture("JOB", "JOB_TAG_" + suffix, "job_definition", "job_code"),
        new ResourceTagFixture(
            "WORKFLOW", "WORKFLOW_TAG_" + suffix, "workflow_definition", "workflow_code"),
        new ResourceTagFixture(
            "FILE_CHANNEL", "CHANNEL_TAG_" + suffix, "file_channel_config", "channel_code"),
        new ResourceTagFixture(
            "FILE_TEMPLATE", "TEMPLATE_TAG_" + suffix, "file_template_config", "template_code"));

    for (ResourceTagFixture fixture : fixtures) {
      insertResourceTagTarget(sourceTenant, fixture);
      asApplicationRole(() -> {
        disableResourceTagTarget(sourceTenant, fixture);
        ResourceTagUpsertInput tagInput = ResourceTagUpsertInput.builder()
            .tenantId(sourceTenant)
            .resourceType(fixture.resourceType())
            .resourceCode(fixture.resourceCode())
            .tagKey("purpose")
            .tagValue("test")
            .operator("test")
            .build();
        resourceTagService.upsert(tagInput);
        return null;
      });
      Integer selectedResourceTagCount = jdbc.queryForObject(
          selectCountSql,
          Integer.class,
          sourceTenant,
          fixture.resourceType(),
          fixture.resourceCode());
      assertThat(selectedResourceTagCount).isOne();

      assertThatThrownBy(() -> asApplicationRole(() -> {
            ResourceTagUpsertInput tagInput2 = ResourceTagUpsertInput.builder()
                .tenantId(targets.getFirst())
                .resourceType(fixture.resourceType())
                .resourceCode(fixture.resourceCode())
                .tagKey("purpose")
                .tagValue("test")
                .operator("test")
                .build();
            resourceTagService.upsert(tagInput2);
            return null;
          }))
          .isInstanceOf(BizException.class);

      assertThatThrownBy(() -> asApplicationRole(() -> jdbc.update(
              insertResourceTagSql,
              sourceTenant,
              fixture.resourceType(),
              "MISSING_" + fixture.resourceCode())))
          .satisfies(failure -> {
            Throwable sqlFailure = NestedExceptionUtils.getMostSpecificCause(failure);
            assertThat(sqlFailure).isInstanceOf(SQLException.class);
            assertThat(((SQLException) sqlFailure).getSQLState()).isEqualTo("23503");
          });

      asApplicationRole(() -> {
        deleteResourceTagTarget(sourceTenant, fixture);
        return null;
      });
      Integer selectedResourceTagCountRead2 = jdbc.queryForObject(
          selectCountSql,
          Integer.class,
          sourceTenant,
          fixture.resourceType(),
          fixture.resourceCode());
      assertThat(selectedResourceTagCountRead2).isZero();
      deleteResourceTagTarget(
          sourceTenant,
          new ResourceTagFixture(
              fixture.resourceType(),
              "RENAMED_" + fixture.resourceCode(),
              fixture.tableName(),
              fixture.codeColumn()));
    }
  }

  @Test
  @DisplayName("父资源自然键改名后按同一租户和类型清理失效标签")
  void shouldRemoveResourceTagWhenParentIdentityIsRenamed() {
    final String insertWorkflowDefinitionSql = """
            insert into batch.workflow_definition
              (tenant_id, workflow_code, workflow_name, workflow_type, version)
            values (?, ?, ?, 'DAG', 2)
            """;
    final String selectCountSql = """
            select count(*) from batch.resource_tag
            where tenant_id = ? and resource_type = ? and resource_code = ?
            """;
    final String selectCountSql2 = """
          select count(*) from batch.resource_tag
          where tenant_id = ? and resource_type = ? and resource_code = ?
          """;
    final String updateFileTemplateConfigSql = """
          update batch.file_template_config set is_deleted = true
          where tenant_id = ? and template_code = ?
          """;
    final String selectCountSql3 = """
          select count(*) from batch.resource_tag
          where tenant_id = ? and resource_type = 'FILE_TEMPLATE' and resource_code = ?
            and tag_value = 'before-soft-delete'
          """;
    final String selectCountSql4 = """
        select count(*) from batch.resource_tag
        where tenant_id = ? and resource_type = 'FILE_TEMPLATE' and resource_code = ?
        """;
    final String updateFileTemplateConfigSql2 = """
        update batch.file_template_config set is_deleted = false
        where tenant_id = ? and template_code = ?
        """;
    final String selectTagValueSql = """
        select tag_value from batch.resource_tag
        where tenant_id = ? and resource_type = 'FILE_TEMPLATE' and resource_code = ?
          and tag_key = 'purpose'
        """;

    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    List<ResourceTagFixture> fixtures = List.of(
        new ResourceTagFixture("JOB", "RENAME_JOB_" + suffix, "job_definition", "job_code"),
        new ResourceTagFixture(
            "WORKFLOW", "RENAME_WORKFLOW_" + suffix, "workflow_definition", "workflow_code"),
        new ResourceTagFixture(
            "FILE_CHANNEL", "RENAME_CHANNEL_" + suffix, "file_channel_config", "channel_code"),
        new ResourceTagFixture(
            "FILE_TEMPLATE", "RENAME_TEMPLATE_" + suffix, "file_template_config", "template_code"));

    for (ResourceTagFixture fixture : fixtures) {
      insertResourceTagTarget(sourceTenant, fixture);
      if (fixture.resourceType().equals("WORKFLOW")) {
        jdbc.update(
            insertWorkflowDefinitionSql,
            sourceTenant,
            fixture.resourceCode(),
            fixture.resourceCode());
      }
      asApplicationRole(() -> {
        ResourceTagUpsertInput tagInput3 = ResourceTagUpsertInput.builder()
            .tenantId(sourceTenant)
            .resourceType(fixture.resourceType())
            .resourceCode(fixture.resourceCode())
            .tagKey("purpose")
            .tagValue("rename")
            .operator("test")
            .build();
        resourceTagService.upsert(tagInput3);
        renameResourceTagTarget(sourceTenant, fixture, "RENAMED_" + fixture.resourceCode());
        return null;
      });

      if (fixture.resourceType().equals("WORKFLOW")) {
        Integer selectedResourceTagCount = jdbc.queryForObject(
            selectCountSql,
            Integer.class,
            sourceTenant,
            fixture.resourceType(),
            fixture.resourceCode());
        assertThat(selectedResourceTagCount).isOne();
        asApplicationRole(() -> {
          renameResourceTagTarget(sourceTenant, fixture, "RENAMED_" + fixture.resourceCode());
          return null;
        });
      }

      Integer selectedResourceTagCountRead2 = jdbc.queryForObject(
          selectCountSql2,
          Integer.class,
          sourceTenant,
          fixture.resourceType(),
          fixture.resourceCode());
      assertThat(selectedResourceTagCountRead2).isZero();
    }

    ResourceTagFixture softDeletedTemplate = new ResourceTagFixture(
        "FILE_TEMPLATE",
        "SOFT_DELETED_TEMPLATE_" + suffix,
        "file_template_config",
        "template_code");
    insertResourceTagTarget(sourceTenant, softDeletedTemplate);
    asApplicationRole(() -> {
      ResourceTagUpsertInput tagInput4 = ResourceTagUpsertInput.builder()
          .tenantId(sourceTenant)
          .resourceType(softDeletedTemplate.resourceType())
          .resourceCode(softDeletedTemplate.resourceCode())
          .tagKey("purpose")
          .tagValue("before-soft-delete")
          .operator("test")
          .build();
      resourceTagService.upsert(tagInput4);
      return null;
    });
    asApplicationRole(() -> {
      jdbc.update(updateFileTemplateConfigSql, sourceTenant, softDeletedTemplate.resourceCode());
      Integer selectedResourceTagCountRead3 = jdbc.queryForObject(
          selectCountSql3, Integer.class, sourceTenant, softDeletedTemplate.resourceCode());
      assertThat(selectedResourceTagCountRead3).isOne();
      ResourceTagUpsertInput tagInput5 = ResourceTagUpsertInput.builder()
          .tenantId(sourceTenant)
          .resourceType(softDeletedTemplate.resourceType())
          .resourceCode(softDeletedTemplate.resourceCode())
          .tagKey("purpose")
          .tagValue("retained")
          .operator("test")
          .build();
      resourceTagService.upsert(tagInput5);
      return null;
    });
    Integer selectedResourceTagCountRead4 = jdbc.queryForObject(
        selectCountSql4, Integer.class, sourceTenant, softDeletedTemplate.resourceCode());
    assertThat(selectedResourceTagCountRead4).isOne();
    asApplicationRole(() -> jdbc.update(
        updateFileTemplateConfigSql2, sourceTenant, softDeletedTemplate.resourceCode()));
    String selectedTagValue = jdbc.queryForObject(
        selectTagValueSql, String.class, sourceTenant, softDeletedTemplate.resourceCode());
    assertThat(selectedTagValue).isEqualTo("retained");
    deleteResourceTagTarget(sourceTenant, softDeletedTemplate);
  }

  @Test
  @DisplayName("标签附加与父资源删除通过同一身份锁串行化")
  void shouldSerializeResourceTagAttachmentWithParentDelete() throws Exception {
    final String selectLockResourceTagIdentitySql = """
          select batch.lock_resource_tag_identity(?, 'JOB', ?)
          """;
    final String deleteJobDefinitionSql2 = """
        delete from batch.job_definition where tenant_id = ? and job_code = ?
    """;

    final String selectResourceTagIdentityLockKeySql = """
        select batch.resource_tag_identity_lock_key(?, 'JOB', ?)
        """;
    final String selectCountSql = """
              select count(*) from pg_locks where locktype = 'advisory' and not granted
                and classid::bigint = ? and objid::bigint = ? and objsubid = 1
              """;
    final String selectCountSql2 = """
          select count(*) from batch.resource_tag
          where tenant_id = ? and resource_type = 'JOB' and resource_code = ?
          """;
    final String deleteJobDefinitionSql = """
        delete from batch.job_definition where tenant_id = ? and job_code = ?
    """;

    String jobCode = "TAG_RACE_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    insertResourceTagTarget(
        sourceTenant, new ResourceTagFixture("JOB", jobCode, "job_definition", "job_code"));
    Long lockKey =
        jdbc.queryForObject(selectResourceTagIdentityLockKeySql, Long.class, sourceTenant, jobCode);
    var executor = Executors.newSingleThreadExecutor();
    try (var parentTransaction = dataSource.getConnection()) {
      parentTransaction.setAutoCommit(false);
      try (var lock = parentTransaction.prepareStatement(selectLockResourceTagIdentitySql)) {
        lock.setString(1, sourceTenant);
        lock.setString(2, jobCode);
        lock.execute();
      }

      var attachment = executor.submit(() -> asApplicationRole(() -> {
        ResourceTagUpsertInput tagInput6 = ResourceTagUpsertInput.builder()
            .tenantId(sourceTenant)
            .resourceType("JOB")
            .resourceCode(jobCode)
            .tagKey("purpose")
            .tagValue("race")
            .operator("test")
            .build();
        resourceTagService.upsert(tagInput6);
        return null;
      }));
      await()
          .atMost(Duration.ofSeconds(20))
          .pollInterval(Duration.ofMillis(50))
          .untilAsserted(() -> {
            Integer selectedPgLocksCount = jdbc.queryForObject(
                selectCountSql, Integer.class, lockKey >>> 32, lockKey & 0xffff_ffffL);
            assertThat(selectedPgLocksCount).isOne();
          });

      try (var delete = parentTransaction.prepareStatement(deleteJobDefinitionSql2)) {
        delete.setString(1, sourceTenant);
        delete.setString(2, jobCode);
        assertThat(delete.executeUpdate()).isOne();
      }
      parentTransaction.commit();

      assertThatThrownBy(() -> attachment.get(20, TimeUnit.SECONDS))
          .hasCauseInstanceOf(BizException.class);
      Integer selectedResourceTagCount =
          jdbc.queryForObject(selectCountSql2, Integer.class, sourceTenant, jobCode);
      assertThat(selectedResourceTagCount).isZero();
    } finally {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
      jdbc.update(deleteJobDefinitionSql, sourceTenant, jobCode);
    }
  }

  @Test
  @DisplayName("标签附加与父资源改名通过同一身份锁串行化")
  void shouldSerializeResourceTagAttachmentWithParentRename() throws Exception {
    final String selectLockResourceTagIdentitySql = """
          select batch.lock_resource_tag_identity(?, 'JOB', ?)
          """;
    final String updateJobDefinitionSql = """
          update batch.job_definition set job_code = ? where tenant_id = ? and job_code = ?
          """;

    final String selectResourceTagIdentityLockKeySql = """
        select batch.resource_tag_identity_lock_key(?, 'JOB', ?)
        """;
    final String selectCountSql = """
              select count(*) from pg_locks where locktype = 'advisory' and not granted
                and classid::bigint = ? and objid::bigint = ? and objsubid = 1
              """;
    final String selectCountSql2 = """
          select count(*) from batch.resource_tag
          where tenant_id = ? and resource_type = 'JOB' and resource_code in (?, ?)
          """;
    final String deleteJobDefinitionSql = """
        delete from batch.job_definition where tenant_id = ? and job_code = ?
    """;

    String jobCode =
        "TAG_RENAME_RACE_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    String renamedJobCode = "RENAMED_" + jobCode;
    ResourceTagFixture fixture =
        new ResourceTagFixture("JOB", jobCode, "job_definition", "job_code");
    insertResourceTagTarget(sourceTenant, fixture);
    Long lockKey =
        jdbc.queryForObject(selectResourceTagIdentityLockKeySql, Long.class, sourceTenant, jobCode);
    var executor = Executors.newSingleThreadExecutor();
    try (var parentTransaction = dataSource.getConnection()) {
      parentTransaction.setAutoCommit(false);
      try (var lock = parentTransaction.prepareStatement(selectLockResourceTagIdentitySql)) {
        lock.setString(1, sourceTenant);
        lock.setString(2, jobCode);
        lock.execute();
      }

      var attachment = executor.submit(() -> asApplicationRole(() -> {
        ResourceTagUpsertInput tagInput7 = ResourceTagUpsertInput.builder()
            .tenantId(sourceTenant)
            .resourceType("JOB")
            .resourceCode(jobCode)
            .tagKey("purpose")
            .tagValue("race")
            .operator("test")
            .build();
        resourceTagService.upsert(tagInput7);
        return null;
      }));
      await()
          .atMost(Duration.ofSeconds(20))
          .pollInterval(Duration.ofMillis(50))
          .untilAsserted(() -> {
            Integer selectedPgLocksCount = jdbc.queryForObject(
                selectCountSql, Integer.class, lockKey >>> 32, lockKey & 0xffff_ffffL);
            assertThat(selectedPgLocksCount).isOne();
          });

      try (var rename = parentTransaction.prepareStatement(updateJobDefinitionSql)) {
        rename.setString(1, renamedJobCode);
        rename.setString(2, sourceTenant);
        rename.setString(3, jobCode);
        assertThat(rename.executeUpdate()).isOne();
      }
      parentTransaction.commit();

      assertThatThrownBy(() -> attachment.get(20, TimeUnit.SECONDS))
          .hasCauseInstanceOf(BizException.class);
      Integer selectedResourceTagCount = jdbc.queryForObject(
          selectCountSql2, Integer.class, sourceTenant, jobCode, renamedJobCode);
      assertThat(selectedResourceTagCount).isZero();
    } finally {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
      jdbc.update(deleteJobDefinitionSql, sourceTenant, renamedJobCode);
    }
  }

  @Test
  @DisplayName("Workflow 版本增加与旧版本删除并发时保留逻辑资源标签")
  void shouldPreserveWorkflowTagWhenVersionIsAddedBeforeConcurrentDelete() throws Exception {
    final String selectSetConfigSql = """
          select set_config('app.tenant_id', ?, true)
          """;
    final String insertWorkflowDefinitionSql = """
          insert into batch.workflow_definition
            (tenant_id, workflow_code, workflow_name, workflow_type, version)
          values (?, ?, ?, 'DAG', 2)
          """;

    final String selectResourceTagIdentityLockKeySql = """
        select batch.resource_tag_identity_lock_key(?, 'WORKFLOW', ?)
        """;
    final String setLocalSql = """
        SET LOCAL ROLE config_dependency_app_test
    """;
    final String deleteWorkflowDefinitionSql = """
          delete from batch.workflow_definition
          where tenant_id = ? and workflow_code = ? and version = 1
          """;
    final String selectCountSql = """
              select count(*) from pg_locks where locktype = 'advisory' and not granted
                and classid::bigint = ? and objid::bigint = ? and objsubid = 1
              """;
    final String selectCountSql2 = """
          select count(*) from batch.resource_tag
          where tenant_id = ? and resource_type = 'WORKFLOW' and resource_code = ?
          """;
    final String deleteWorkflowDefinitionSql2 = """
        delete from batch.workflow_definition where tenant_id = ? and workflow_code = ?
    """;
    final String selectCountSql3 = """
        select count(*) from batch.resource_tag
        where tenant_id = ? and resource_type = 'WORKFLOW' and resource_code = ?
        """;

    String workflowCode =
        "TAG_VERSION_RACE_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    ResourceTagFixture fixture =
        new ResourceTagFixture("WORKFLOW", workflowCode, "workflow_definition", "workflow_code");
    insertResourceTagTarget(sourceTenant, fixture);
    asApplicationRole(() -> {
      ResourceTagUpsertInput tagInput8 = ResourceTagUpsertInput.builder()
          .tenantId(sourceTenant)
          .resourceType("WORKFLOW")
          .resourceCode(workflowCode)
          .tagKey("purpose")
          .tagValue("preserve")
          .operator("test")
          .build();
      resourceTagService.upsert(tagInput8);
      return null;
    });

    Long lockKey = jdbc.queryForObject(
        selectResourceTagIdentityLockKeySql, Long.class, sourceTenant, workflowCode);
    var executor = Executors.newSingleThreadExecutor();
    try (var versionTransaction = dataSource.getConnection()) {
      versionTransaction.setAutoCommit(false);
      try (var role = versionTransaction.createStatement()) {
        role.execute(setLocalSql);
      }
      try (var tenant = versionTransaction.prepareStatement(selectSetConfigSql)) {
        tenant.setString(1, sourceTenant);
        tenant.execute();
      }
      try (var addVersion = versionTransaction.prepareStatement(insertWorkflowDefinitionSql)) {
        addVersion.setString(1, sourceTenant);
        addVersion.setString(2, workflowCode);
        addVersion.setString(3, workflowCode);
        assertThat(addVersion.executeUpdate()).isOne();
      }

      var deleteOldVersion = executor.submit(() -> asApplicationRole(
          () -> jdbc.update(deleteWorkflowDefinitionSql, sourceTenant, workflowCode)));
      await()
          .atMost(Duration.ofSeconds(20))
          .pollInterval(Duration.ofMillis(50))
          .untilAsserted(() -> {
            Integer selectedPgLocksCount = jdbc.queryForObject(
                selectCountSql, Integer.class, lockKey >>> 32, lockKey & 0xffff_ffffL);
            assertThat(selectedPgLocksCount).isOne();
          });

      versionTransaction.commit();
      assertThat(deleteOldVersion.get(20, TimeUnit.SECONDS)).isOne();
      Integer selectedResourceTagCount =
          jdbc.queryForObject(selectCountSql2, Integer.class, sourceTenant, workflowCode);
      assertThat(selectedResourceTagCount).isOne();
    } finally {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
      jdbc.update(deleteWorkflowDefinitionSql2, sourceTenant, workflowCode);
    }
    Integer selectedResourceTagCountRead2 =
        jdbc.queryForObject(selectCountSql3, Integer.class, sourceTenant, workflowCode);
    assertThat(selectedResourceTagCountRead2).isZero();
  }

  @Test
  @DisplayName("旧 Workflow 最后版本删除先提交时后续新版本不继承旧资源标签")
  void shouldNotCarryWorkflowTagWhenConcurrentVersionInsertFollowsLastDelete() throws Exception {
    final String selectSetConfigSql = """
          select set_config('app.tenant_id', ?, true)
          """;
    final String deleteWorkflowDefinitionSql2 = """
          delete from batch.workflow_definition
          where tenant_id = ? and workflow_code = ? and version = 1
          """;

    final String selectResourceTagIdentityLockKeySql = """
        select batch.resource_tag_identity_lock_key(?, 'WORKFLOW', ?)
        """;
    final String setLocalSql = """
        SET LOCAL ROLE config_dependency_app_test
    """;
    final String insertWorkflowDefinitionSql = """
          insert into batch.workflow_definition
            (tenant_id, workflow_code, workflow_name, workflow_type, version)
          values (?, ?, ?, 'DAG', 2)
          """;
    final String selectCountSql = """
              select count(*) from pg_locks where locktype = 'advisory' and not granted
                and classid::bigint = ? and objid::bigint = ? and objsubid = 1
              """;
    final String selectCountSql2 = """
          select count(*) from batch.workflow_definition
          where tenant_id = ? and workflow_code = ? and version = 2
          """;
    final String selectCountSql3 = """
          select count(*) from batch.resource_tag
          where tenant_id = ? and resource_type = 'WORKFLOW' and resource_code = ?
          """;
    final String deleteWorkflowDefinitionSql = """
        delete from batch.workflow_definition where tenant_id = ? and workflow_code = ?
    """;

    String workflowCode =
        "TAG_DELETE_RACE_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    ResourceTagFixture fixture =
        new ResourceTagFixture("WORKFLOW", workflowCode, "workflow_definition", "workflow_code");
    insertResourceTagTarget(sourceTenant, fixture);
    asApplicationRole(() -> {
      ResourceTagUpsertInput tagInput9 = ResourceTagUpsertInput.builder()
          .tenantId(sourceTenant)
          .resourceType("WORKFLOW")
          .resourceCode(workflowCode)
          .tagKey("purpose")
          .tagValue("remove")
          .operator("test")
          .build();
      resourceTagService.upsert(tagInput9);
      return null;
    });

    Long lockKey = jdbc.queryForObject(
        selectResourceTagIdentityLockKeySql, Long.class, sourceTenant, workflowCode);
    var executor = Executors.newSingleThreadExecutor();
    try (var deleteTransaction = dataSource.getConnection()) {
      deleteTransaction.setAutoCommit(false);
      try (var role = deleteTransaction.createStatement()) {
        role.execute(setLocalSql);
      }
      try (var tenant = deleteTransaction.prepareStatement(selectSetConfigSql)) {
        tenant.setString(1, sourceTenant);
        tenant.execute();
      }
      try (var deleteLastVersion =
          deleteTransaction.prepareStatement(deleteWorkflowDefinitionSql2)) {
        deleteLastVersion.setString(1, sourceTenant);
        deleteLastVersion.setString(2, workflowCode);
        assertThat(deleteLastVersion.executeUpdate()).isOne();
      }

      var addNewVersion = executor.submit(() -> asApplicationRole(() ->
          jdbc.update(insertWorkflowDefinitionSql, sourceTenant, workflowCode, workflowCode)));
      await()
          .atMost(Duration.ofSeconds(20))
          .pollInterval(Duration.ofMillis(50))
          .untilAsserted(() -> {
            Integer selectedPgLocksCount = jdbc.queryForObject(
                selectCountSql, Integer.class, lockKey >>> 32, lockKey & 0xffff_ffffL);
            assertThat(selectedPgLocksCount).isOne();
          });

      deleteTransaction.commit();
      assertThat(addNewVersion.get(20, TimeUnit.SECONDS)).isOne();
      Integer selectedWorkflowDefinitionCount =
          jdbc.queryForObject(selectCountSql2, Integer.class, sourceTenant, workflowCode);
      assertThat(selectedWorkflowDefinitionCount).isOne();
      Integer selectedResourceTagCount =
          jdbc.queryForObject(selectCountSql3, Integer.class, sourceTenant, workflowCode);
      assertThat(selectedResourceTagCount).isZero();
    } finally {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
      jdbc.update(deleteWorkflowDefinitionSql, sourceTenant, workflowCode);
    }
  }

  @Test
  @DisplayName("多个 Workflow 版本并发删除时标签保留到最后版本删除")
  void shouldKeepWorkflowTagUntilLastOfMultipleConcurrentVersionsIsDeleted() throws Exception {
    final String insertWorkflowDefinitionSql = """
        insert into batch.workflow_definition
          (tenant_id, workflow_code, workflow_name, workflow_type, version)
        values (?, ?, ?, 'DAG', 2), (?, ?, ?, 'DAG', 3)
        """;
    final String selectCountSql = """
          select count(*) from batch.workflow_definition
          where tenant_id = ? and workflow_code = ?
          """;
    final String selectCountSql2 = """
          select count(*) from batch.workflow_definition
          where tenant_id = ? and workflow_code = ? and version = 3
          """;
    final String selectCountSql3 = """
          select count(*) from batch.resource_tag
          where tenant_id = ? and resource_type = 'WORKFLOW' and resource_code = ?
          """;
    final String deleteWorkflowDefinitionSql = """
          delete from batch.workflow_definition
          where tenant_id = ? and workflow_code = ? and version = 3
          """;
    final String deleteWorkflowDefinitionSql2 = """
        delete from batch.workflow_definition where tenant_id = ? and workflow_code = ?
    """;

    String workflowCode =
        "TAG_MULTI_DELETE_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    ResourceTagFixture fixture =
        new ResourceTagFixture("WORKFLOW", workflowCode, "workflow_definition", "workflow_code");
    insertResourceTagTarget(sourceTenant, fixture);
    jdbc.update(
        insertWorkflowDefinitionSql,
        sourceTenant,
        workflowCode,
        workflowCode,
        sourceTenant,
        workflowCode,
        workflowCode);
    asApplicationRole(() -> {
      ResourceTagUpsertInput tagInput10 = ResourceTagUpsertInput.builder()
          .tenantId(sourceTenant)
          .resourceType("WORKFLOW")
          .resourceCode(workflowCode)
          .tagKey("purpose")
          .tagValue("keep-until-last")
          .operator("test")
          .build();
      resourceTagService.upsert(tagInput10);
      return null;
    });

    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var deleteV1 = executor.submit(
          () -> deleteWorkflowVersionAtGate(sourceTenant, workflowCode, 1, ready, start));
      var deleteV2 = executor.submit(
          () -> deleteWorkflowVersionAtGate(sourceTenant, workflowCode, 2, ready, start));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(deleteV1.get(20, TimeUnit.SECONDS)).isOne();
      assertThat(deleteV2.get(20, TimeUnit.SECONDS)).isOne();

      Integer selectedWorkflowDefinitionCount =
          jdbc.queryForObject(selectCountSql, Integer.class, sourceTenant, workflowCode);
      assertThat(selectedWorkflowDefinitionCount).isOne();
      Integer selectedWorkflowDefinitionCountRead2 =
          jdbc.queryForObject(selectCountSql2, Integer.class, sourceTenant, workflowCode);
      assertThat(selectedWorkflowDefinitionCountRead2).isOne();
      Integer selectedResourceTagCount =
          jdbc.queryForObject(selectCountSql3, Integer.class, sourceTenant, workflowCode);
      assertThat(selectedResourceTagCount).isOne();

      int affectedWorkflowDefinitionRows =
          jdbc.update(deleteWorkflowDefinitionSql, sourceTenant, workflowCode);
      assertThat(affectedWorkflowDefinitionRows).isOne();
      Integer selectedResourceTagCountRead2 =
          jdbc.queryForObject(selectCountSql3, Integer.class, sourceTenant, workflowCode);
      assertThat(selectedResourceTagCountRead2).isZero();
    } finally {
      start.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
      jdbc.update(deleteWorkflowDefinitionSql2, sourceTenant, workflowCode);
    }
  }

  @Test
  @DisplayName("多 Workflow 版本并发增删交错时标签只随最后版本清理")
  void shouldKeepWorkflowTagAcrossConcurrentVersionAddsAndDeletes() throws Exception {
    final String insertWorkflowDefinitionSql = """
        insert into batch.workflow_definition
          (tenant_id, workflow_code, workflow_name, workflow_type, version)
        values (?, ?, ?, 'DAG', 2), (?, ?, ?, 'DAG', 3)
        """;
    final String selectVersionSql = """
          select version from batch.workflow_definition
          where tenant_id = ? and workflow_code = ? order by version
          """;
    final String selectCountSql = """
          select count(*) from batch.resource_tag
          where tenant_id = ? and resource_type = 'WORKFLOW' and resource_code = ?
          """;
    final String selectCountSql2 = """
          select count(*) from batch.workflow_definition
          where tenant_id = ? and workflow_code = ?
          """;
    final String deleteWorkflowDefinitionSql = """
        delete from batch.workflow_definition where tenant_id = ? and workflow_code = ?
    """;

    String workflowCode =
        "TAG_MIXED_RACE_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    ResourceTagFixture fixture =
        new ResourceTagFixture("WORKFLOW", workflowCode, "workflow_definition", "workflow_code");
    insertResourceTagTarget(sourceTenant, fixture);
    jdbc.update(
        insertWorkflowDefinitionSql,
        sourceTenant,
        workflowCode,
        workflowCode,
        sourceTenant,
        workflowCode,
        workflowCode);
    asApplicationRole(() -> {
      ResourceTagUpsertInput tagInput11 = ResourceTagUpsertInput.builder()
          .tenantId(sourceTenant)
          .resourceType("WORKFLOW")
          .resourceCode(workflowCode)
          .tagKey("purpose")
          .tagValue("keep-until-last")
          .operator("test")
          .build();
      resourceTagService.upsert(tagInput11);
      return null;
    });

    CountDownLatch ready = new CountDownLatch(4);
    CountDownLatch start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(4);
    try {
      var deleteV1 = executor.submit(
          () -> deleteWorkflowVersionAtGate(sourceTenant, workflowCode, 1, ready, start));
      var deleteV2 = executor.submit(
          () -> deleteWorkflowVersionAtGate(sourceTenant, workflowCode, 2, ready, start));
      var addV4 = executor.submit(
          () -> insertWorkflowVersionAtGate(sourceTenant, workflowCode, 4, ready, start));
      var addV5 = executor.submit(
          () -> insertWorkflowVersionAtGate(sourceTenant, workflowCode, 5, ready, start));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(deleteV1.get(20, TimeUnit.SECONDS)).isOne();
      assertThat(deleteV2.get(20, TimeUnit.SECONDS)).isOne();
      assertThat(addV4.get(20, TimeUnit.SECONDS)).isOne();
      assertThat(addV5.get(20, TimeUnit.SECONDS)).isOne();

      List<Integer> selectedVersionValues =
          jdbc.queryForList(selectVersionSql, Integer.class, sourceTenant, workflowCode);
      assertThat(selectedVersionValues).containsExactly(3, 4, 5);
      Integer selectedResourceTagCount =
          jdbc.queryForObject(selectCountSql, Integer.class, sourceTenant, workflowCode);
      assertThat(selectedResourceTagCount).isOne();

      CountDownLatch deleteReady = new CountDownLatch(3);
      CountDownLatch deleteStart = new CountDownLatch(1);
      var deleteV3 = executor.submit(() ->
          deleteWorkflowVersionAtGate(sourceTenant, workflowCode, 3, deleteReady, deleteStart));
      var deleteV4 = executor.submit(() ->
          deleteWorkflowVersionAtGate(sourceTenant, workflowCode, 4, deleteReady, deleteStart));
      var deleteV5 = executor.submit(() ->
          deleteWorkflowVersionAtGate(sourceTenant, workflowCode, 5, deleteReady, deleteStart));
      assertThat(deleteReady.await(10, TimeUnit.SECONDS)).isTrue();
      deleteStart.countDown();
      assertThat(deleteV3.get(20, TimeUnit.SECONDS)).isOne();
      assertThat(deleteV4.get(20, TimeUnit.SECONDS)).isOne();
      assertThat(deleteV5.get(20, TimeUnit.SECONDS)).isOne();
      Integer selectedWorkflowDefinitionCount =
          jdbc.queryForObject(selectCountSql2, Integer.class, sourceTenant, workflowCode);
      assertThat(selectedWorkflowDefinitionCount).isZero();
      Integer selectedResourceTagCountRead2 =
          jdbc.queryForObject(selectCountSql, Integer.class, sourceTenant, workflowCode);
      assertThat(selectedResourceTagCountRead2).isZero();
    } finally {
      start.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
      jdbc.update(deleteWorkflowDefinitionSql, sourceTenant, workflowCode);
    }
  }

  private Integer insertWorkflowVersionAtGate(
      String tenantId, String workflowCode, int version, CountDownLatch ready, CountDownLatch start)
      throws InterruptedException {
    final String insertWorkflowDefinitionSql = """
        insert into batch.workflow_definition
          (tenant_id, workflow_code, workflow_name, workflow_type, version)
        values (?, ?, ?, 'DAG', ?)
        """;

    ready.countDown();
    if (!start.await(10, TimeUnit.SECONDS)) {
      throw new IllegalStateException("concurrent Workflow insert gate timed out");
    }
    return asApplicationRole(() ->
        jdbc.update(insertWorkflowDefinitionSql, tenantId, workflowCode, workflowCode, version));
  }

  private Integer deleteWorkflowVersionAtGate(
      String tenantId, String workflowCode, int version, CountDownLatch ready, CountDownLatch start)
      throws InterruptedException {
    final String deleteWorkflowDefinitionSql = """
        delete from batch.workflow_definition
        where tenant_id = ? and workflow_code = ? and version = ?
        """;

    ready.countDown();
    if (!start.await(10, TimeUnit.SECONDS)) {
      throw new IllegalStateException("concurrent Workflow delete gate timed out");
    }
    return asApplicationRole(
        () -> jdbc.update(deleteWorkflowDefinitionSql, tenantId, workflowCode, version));
  }

  private void insertResourceTagTarget(String tenantId, ResourceTagFixture fixture) {
    final String insertJobDefinitionSql = """
          insert into batch.job_definition
            (tenant_id, job_code, job_name, job_type, schedule_type, timezone, trigger_mode)
          values (?, ?, ?, 'IMPORT', 'MANUAL', 'UTC', 'API')
          """;
    final String insertWorkflowDefinitionSql = """
          insert into batch.workflow_definition
            (tenant_id, workflow_code, workflow_name, workflow_type, version)
          values (?, ?, ?, 'DAG', 1)
          """;
    final String insertFileChannelConfigSql = """
          insert into batch.file_channel_config
            (tenant_id, channel_code, channel_name, channel_type, config_json)
          values (?, ?, ?, 'SFTP', '{}'::jsonb)
          """;
    final String insertFileTemplateConfigSql = """
          insert into batch.file_template_config
            (tenant_id, template_code, template_name, template_type, file_format_type)
          values (?, ?, ?, 'IMPORT', 'DELIMITED')
          """;

    switch (fixture.resourceType()) {
      case "JOB" ->
        jdbc.update(
            insertJobDefinitionSql, tenantId, fixture.resourceCode(), fixture.resourceCode());
      case "WORKFLOW" ->
        jdbc.update(
            insertWorkflowDefinitionSql, tenantId, fixture.resourceCode(), fixture.resourceCode());
      case "FILE_CHANNEL" ->
        jdbc.update(
            insertFileChannelConfigSql, tenantId, fixture.resourceCode(), fixture.resourceCode());
      case "FILE_TEMPLATE" ->
        jdbc.update(
            insertFileTemplateConfigSql, tenantId, fixture.resourceCode(), fixture.resourceCode());
      default ->
        throw new IllegalArgumentException("Unsupported fixture type: " + fixture.resourceType());
    }
  }

  private void deleteResourceTagTarget(String tenantId, ResourceTagFixture fixture) {
    final String deleteStatementSql = "delete from batch.%s where tenant_id = ? and %s = ?"
        .formatted(fixture.tableName(), fixture.codeColumn());
    jdbc.update(deleteStatementSql, tenantId, fixture.resourceCode());
  }

  private void disableResourceTagTarget(String tenantId, ResourceTagFixture fixture) {
    final String updateStatementSql =
        "update batch.%s set enabled = false where tenant_id = ? and %s = ?"
            .formatted(fixture.tableName(), fixture.codeColumn());
    jdbc.update(updateStatementSql, tenantId, fixture.resourceCode());
  }

  private void renameResourceTagTarget(
      String tenantId, ResourceTagFixture fixture, String newResourceCode) {
    final String updateStatementSql = """
        update batch.%s set %s = ?
        where id = (select id from batch.%s where tenant_id = ? and %s = ? order by id limit 1)
        """.formatted(
            fixture.tableName(), fixture.codeColumn(), fixture.tableName(), fixture.codeColumn());
    jdbc.update(updateStatementSql, newResourceCode, tenantId, fixture.resourceCode());
  }

  private record ResourceTagFixture(
      String resourceType, String resourceCode, String tableName, String codeColumn) {}

  private static JobDefinitionSpec job(String code) {
    JobDefinitionSpec spec = new JobDefinitionSpec();
    spec.setJobCode(code);
    spec.setJobName(code);
    spec.setJobType("IMPORT");
    spec.setScheduleType("MANUAL");
    spec.setStartGraceSeconds(0);
    spec.setEnabled(true);
    return spec;
  }

  private <T> T asApplicationRole(Supplier<T> operation) {
    return asApplicationRole(false, operation);
  }

  private <T> T asApplicationRole(boolean readOnly, Supplier<T> operation) {
    final String selectCurrentUserSql = """
        select current_user
    """;
    final String selectCurrentSettingSql = """
        select current_setting('app.tenant_id', true)
    """;

    APPLICATION_ROLE.set(true);
    try {
      TransactionTemplate transaction = new TransactionTemplate(transactions);
      transaction.setReadOnly(readOnly);
      return transaction.execute(status -> {
        String selectedCurrentUser = jdbc.queryForObject(selectCurrentUserSql, String.class);
        assertThat(selectedCurrentUser).isEqualTo("config_dependency_app_test");
        return RlsTenantContextHolder.runWithTenant(sourceTenant, () -> {
          T result = operation.get();
          assertThat(RlsTenantContextHolder.get()).isEqualTo(sourceTenant);
          String selectedCurrentSetting =
              jdbc.queryForObject(selectCurrentSettingSql, String.class);
          assertThat(selectedCurrentSetting).isNotIn(targets);
          return result;
        });
      });
    } finally {
      APPLICATION_ROLE.remove();
    }
  }

  /** 每个新事务都使用普通角色，包括挂起调用方后开启的 REQUIRES_NEW。 */
  @TestConfiguration(proxyBeanMethods = false)
  static class RoleTransactions {
    @Bean
    SourceSnapshotBarrier sourceSnapshotBarrier() {
      return new SourceSnapshotBarrier();
    }

    @Bean(name = {"configDependencyTransactions", "transactionManager"})
    @Primary
    PlatformTransactionManager configDependencyTransactions(DataSource dataSource) {
      return new JdbcTransactionManager(dataSource) {
        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
          final String setLocalSql = """
              SET LOCAL ROLE config_dependency_app_test
          """;
          final String setLocalSql2 = """
              SET LOCAL app.tenant_id = ''
          """;

          super.doBegin(transaction, definition);
          if (Boolean.TRUE.equals(APPLICATION_ROLE.get())) {
            var connection = DataSourceUtils.getConnection(dataSource);
            try (var statement = connection.createStatement()) {
              statement.execute(setLocalSql);
              statement.execute(setLocalSql2);
            } catch (SQLException failure) {
              throw new CannotCreateTransactionException("Could not apply test role", failure);
            } finally {
              DataSourceUtils.releaseConnection(connection, dataSource);
            }
          }
        }
      };
    }
  }

  private record SourceReadBarrier(
      String tenantId, CountDownLatch jobsRead, CountDownLatch continueReading) {}

  /** 仅在真实Job查询返回后插入线程屏障；不替换SQL、Mapper结果或生产事务。 */
  @Intercepts(
      @Signature(
          type = Executor.class,
          method = "query",
          args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}))
  static class SourceSnapshotBarrier implements Interceptor {
    @Override
    public Object intercept(Invocation invocation) throws Throwable {
      Object result = invocation.proceed();
      SourceReadBarrier barrier = SOURCE_READ_BARRIER.get();
      MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
      boolean statementMatchesTenantBarrier = EmptyChecks.isNotNull(barrier)
          && barrier.tenantId().equals(RlsTenantContextHolder.get())
          && statement
              .getId()
              .equals(
                  "io.github.pinpols.batch.console.domain.job.mapper.JobDefinitionMapper.selectByQuery");
      if (statementMatchesTenantBarrier) {
        barrier.jobsRead().countDown();
        if (!barrier.continueReading().await(30, TimeUnit.SECONDS)) {
          throw new IllegalStateException("Source snapshot test barrier timed out");
        }
      }
      return result;
    }
  }
}
