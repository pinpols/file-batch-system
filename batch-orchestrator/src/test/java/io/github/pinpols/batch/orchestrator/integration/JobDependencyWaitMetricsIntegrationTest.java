package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.domain.query.JobDependencyWaitBacklog;
import io.github.pinpols.batch.orchestrator.infrastructure.metrics.JobDependencyWaitMetricsQuery;
import io.github.pinpols.batch.orchestrator.infrastructure.metrics.JobDependencyWaitMetricsScheduler;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture;
import io.github.pinpols.batch.orchestrator.mapper.JobDependencyWaitingLaunchMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
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
@DisplayName("依赖等待指标按租户日历与业务日隔离")
class JobDependencyWaitMetricsIntegrationTest extends AbstractIntegrationTest {

  private static final String RLS_ROLE = "dependency_wait_metrics_rls_test";
  private final Set<String> ownedTenants = new HashSet<>();

  @Autowired
  private JobDependencyWaitingLaunchMapper waitingMapper;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private JobDependencyWaitMetricsScheduler metricsScheduler;

  @Autowired
  private JobDependencyWaitMetricsQuery metricsQuery;

  @Autowired
  private MeterRegistry meterRegistry;

  @Autowired
  private PlatformTransactionManager transactionManager;

  @BeforeAll
  static void createRlsRole(@Autowired JdbcTemplate jdbcTemplate) {
    final String doStatementSql = """
        DO $$
        BEGIN
          IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'dependency_wait_metrics_rls_test') THEN
            CREATE ROLE dependency_wait_metrics_rls_test NOSUPERUSER NOBYPASSRLS;
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
        GRANT SELECT ON batch.job_dependency_waiting_launch TO %s
    """.formatted(RLS_ROLE);
    jdbcTemplate.execute(grantSelectSql);
  }

  @DisplayName("聚合等待记录并排除已释放记录")
  @Test
  void shouldAggregateWaitingRowsAndExcludeReleasedRows() {
    String tenantId = "it-metrics-" + UUID.randomUUID();
    RlsTenantContextHolder.runWithTenant(tenantId, () -> {
      insertWaitingRow(
          tenantId,
          "wait-" + UUID.randomUUID(),
          "DOWNSTREAM",
          LocalDate.of(2026, 10, 12),
          "WAITING",
          2);
      insertWaitingRow(
          tenantId,
          "released-" + UUID.randomUUID(),
          "DONE",
          LocalDate.of(2026, 10, 14),
          "RELEASED",
          0);

      JobDependencyWaitBacklog backlog = waitingMapper.selectBacklogMetrics(tenantId);
      assertThat(backlog.waitingCount()).isEqualTo(1);
      assertThat(backlog.oldestAgeSeconds()).isBetween(172_700L, 173_000L);
    });
  }

  @DisplayName("只统计指定日历与业务日的等待启动")
  @Test
  void shouldCountOnlyWaitingLaunchesForTheRequestedCalendarAndBizDate() {
    final String insertJobDefinitionSql = """
            insert into batch.job_definition (
                tenant_id, job_code, job_name, job_type, schedule_type, timezone,
                calendar_code, version, enabled
            ) values (?, ?, 'downstream', 'GENERAL', 'MANUAL', 'UTC', ?, 1, true)
            """;

    String tenantId = "it-empty-settle-wait-" + UUID.randomUUID();
    String calendarCode = "CAL-" + UUID.randomUUID();
    String jobCode = "DOWNSTREAM-" + UUID.randomUUID();
    LocalDate bizDate = LocalDate.of(2026, 10, 14);
    RlsTenantContextHolder.runWithTenant(tenantId, () -> {
      jdbcTemplate.update(insertJobDefinitionSql, tenantId, jobCode, calendarCode);
      insertWaitingRow(tenantId, "wait-" + UUID.randomUUID(), jobCode, bizDate, "WAITING", 0);

      assertThat(waitingMapper.countWaitingForCalendarBizDate(tenantId, calendarCode, bizDate))
          .isOne();
      assertThat(waitingMapper.countWaitingForCalendarBizDate(
              tenantId, calendarCode, bizDate.plusDays(1)))
          .isZero();
    });
  }

  @DisplayName("全租户汇总依赖等待积压且包含暂停租户")
  @Test
  void shouldPublishGlobalBacklogAcrossActiveAndSuspendedTenants() {
    final String insertTenantSql = """
      insert into batch.tenant (tenant_id, tenant_name, status) values (?, ?, 'ACTIVE')
  """;
    final String insertTenantSql2 = """
      insert into batch.tenant (tenant_id, tenant_name, status) values (?, ?, 'SUSPENDED')
  """;

    String activeTenant = "it-metrics-active-" + UUID.randomUUID();
    String suspendedTenant = "it-metrics-suspended-" + UUID.randomUUID();
    jdbcTemplate.update(insertTenantSql, activeTenant, activeTenant);
    jdbcTemplate.update(insertTenantSql2, suspendedTenant, suspendedTenant);
    insertWaitingRow(activeTenant, "active-wait-" + UUID.randomUUID(), 3);
    insertWaitingRow(suspendedTenant, "suspended-wait-" + UUID.randomUUID(), 1);

    AopTestUtils.<JobDependencyWaitMetricsScheduler>getTargetObject(metricsScheduler)
        .sample();

    assertThat(meterRegistry.get("batch.job_dependency.waiting").gauge().value())
        .isGreaterThanOrEqualTo(2.0);
    assertThat(meterRegistry
            .get("batch.job_dependency.wait.oldest_age_seconds")
            .gauge()
            .value())
        .isGreaterThanOrEqualTo(259_000.0);
    assertThat(meterRegistry.get("batch.job_dependency.waiting").gauge().getId().getTags())
        .isEmpty();
  }

  @DisplayName("强制 RLS 下查询服务设置租户 GUC 后才能读取等待记录")
  @Test
  void shouldApplyTenantGucForNonBypassRlsRole() {
    final String selectCurrentSettingSql = """
        select current_setting('app.tenant_id', true)
    """;

    String tenantId = "it-metrics-rls-" + UUID.randomUUID();
    insertWaitingRow(tenantId, "rls-wait-" + UUID.randomUUID(), 1);
    TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
    Long hiddenRows = transactionTemplate.execute(status -> {
      final String setLocalSql = """
          SET LOCAL ROLE %s\
          """.formatted(RLS_ROLE);
      jdbcTemplate.execute(setLocalSql);
      return waitingMapper.selectBacklogMetrics(tenantId).waitingCount();
    });
    assertThat(hiddenRows).isZero();

    Map.Entry<Long, String> visibleRows = transactionTemplate.execute(status -> {
      final String setLocalSql = """
          SET LOCAL ROLE %s\
          """.formatted(RLS_ROLE);
      jdbcTemplate.execute(setLocalSql);
      return RlsTenantContextHolder.runWithTenant(tenantId, () -> {
        long waitingCount = metricsQuery.selectForTenant(tenantId).waitingCount();
        String sessionTenant = jdbcTemplate.queryForObject(selectCurrentSettingSql, String.class);
        return Map.entry(waitingCount, sessionTenant);
      });
    });

    assertThat(visibleRows.getValue()).isEqualTo(tenantId);
    assertThat(visibleRows.getKey()).isEqualTo(1L);
  }

  private void insertWaitingRow(String tenantId, String requestId, int ageDays) {
    insertWaitingRow(
        tenantId, requestId, "DOWNSTREAM", LocalDate.of(2026, 10, 15), "WAITING", ageDays);
  }

  private void insertWaitingRow(
      String tenantId,
      String requestId,
      String jobCode,
      LocalDate bizDate,
      String state,
      int ageDays) {
    final String insertBusinessCalendarSql = """
    insert into batch.business_calendar(tenant_id,calendar_code,calendar_name,timezone,enabled)
    values (?,'JOB_CALENDAR','Metrics','UTC',true) on conflict (tenant_id,calendar_code) do nothing
    """;
    final String selectIdSql = """
            select id from batch.job_definition where tenant_id=? and job_code=?
        """;
    final String insertJobDefinitionSql = """
    insert into batch.job_definition(tenant_id,job_code,job_name,job_type,schedule_type,timezone,calendar_code)
    values (?,?,'Metrics','GENERAL','MANUAL','UTC','JOB_CALENDAR') returning id
    """;
    final String insertWorkflowNodeRunSql = """
    insert into batch.workflow_node_run(workflow_run_id,node_code,node_type,run_seq,node_status,retry_count)
    values (?,'JOB','JOB',1,?,0) returning id
    """;
    final String updateTriggerRequestSql = """
    update batch.trigger_request set request_id=?,request_status=?,launch_payload=?::jsonb where id=?
    """;
    final String insertJobDependencyWaitingLaunchSql = """
    insert into batch.job_dependency_waiting_launch(tenant_id,request_id,job_code,biz_date,
    dependency_snapshot,launch_payload,wait_status,created_at)
    values (?,?,?,?,'[]'::jsonb,?::jsonb,?,current_timestamp-(?*interval '1 day'))
    """;

    ownedTenants.add(tenantId);
    jdbcTemplate.update(insertBusinessCalendarSql, tenantId);
    List<Long> existing = jdbcTemplate.queryForList(selectIdSql, Long.class, tenantId, jobCode);
    Long jobId = existing.isEmpty()
        ? jdbcTemplate.queryForObject(insertJobDefinitionSql, Long.class, tenantId, jobCode)
        : existing.getFirst();
    LaunchIntegrationFixture.WorkflowChildTriggerSeed parent =
        LaunchIntegrationFixture.prepareWorkflowChildTriggerRequestForDefinition(
            jdbcTemplate,
            tenantId,
            jobId,
            jobCode,
            "metrics-" + UUID.randomUUID(),
            bizDate,
            TriggerType.API);
    Long nodeId = jdbcTemplate.queryForObject(
        insertWorkflowNodeRunSql,
        Long.class,
        parent.workflowRunId(),
        "WAITING".equals(state) ? "READY" : "SUCCESS");
    String payload = JsonUtils.toJson(LaunchRequest.builder()
        .tenantId(tenantId)
        .requestId(requestId)
        .jobCode(jobCode)
        .bizDate(bizDate)
        .triggerType(TriggerType.EVENT)
        .dryRun(false)
        .params(Map.of(
            "_parentWorkflowRunId",
            parent.workflowRunId(),
            "_parentNodeCode",
            "JOB",
            "_parentNodeRunId",
            nodeId))
        .build());
    jdbcTemplate.update(
        updateTriggerRequestSql,
        requestId,
        "WAITING".equals(state) ? "WAITING" : "LAUNCHED",
        payload,
        parent.triggerRequestId());
    RlsTenantContextHolder.runWithTenant(
        tenantId,
        () -> jdbcTemplate.update(
            insertJobDependencyWaitingLaunchSql,
            tenantId,
            requestId,
            jobCode,
            bizDate,
            payload,
            state,
            ageDays));
  }

  @AfterEach
  void removeOwnedFixture() {
    final String deleteJobDependencyWaitingLaunchSql = """
        delete from batch.job_dependency_waiting_launch where tenant_id=?
    """;
    final String deleteWorkflowNodeRunSql = """
        delete from batch.workflow_node_run where workflow_run_id in (select id from batch.workflow_run\s\
        where tenant_id=?)
    """;
    final String deleteWorkflowRunSql = """
        delete from batch.workflow_run where tenant_id=?
    """;
    final String deleteTriggerRequestSql = """
        delete from batch.trigger_request where tenant_id=?
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
    final String deleteBatchDayCatchupControlSql = """
        delete from batch.batch_day_catchup_control where tenant_id=?
    """;
    final String deleteBusinessCalendarSql = """
        delete from batch.business_calendar where tenant_id=?
    """;
    final String deleteTenantSql = """
        delete from batch.tenant where tenant_id=?
    """;

    for (String tenant : ownedTenants) {
      jdbcTemplate.update(deleteJobDependencyWaitingLaunchSql, tenant);
      jdbcTemplate.update(deleteWorkflowNodeRunSql, tenant);
      jdbcTemplate.update(deleteWorkflowRunSql, tenant);
      jdbcTemplate.update(deleteTriggerRequestSql, tenant);
      jdbcTemplate.update(deleteWorkflowEdgeSql, tenant);
      jdbcTemplate.update(deleteWorkflowNodeSql, tenant);
      jdbcTemplate.update(deleteWorkflowDefinitionSql, tenant);
      jdbcTemplate.update(deleteJobDefinitionSql, tenant);
      jdbcTemplate.update(deleteBatchDayCatchupControlSql, tenant);
      jdbcTemplate.update(deleteBusinessCalendarSql, tenant);
      jdbcTemplate.update(deleteTenantSql, tenant);
    }
  }
}
