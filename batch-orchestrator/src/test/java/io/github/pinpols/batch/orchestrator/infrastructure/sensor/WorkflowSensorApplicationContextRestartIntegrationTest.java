package io.github.pinpols.batch.orchestrator.infrastructure.sensor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.service.task.WorkflowRunResumeProgressor;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowGraphValidator;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunLaunchService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunManagementApplicationService;
import io.github.pinpols.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.infrastructure.file.FileGovernanceScheduler;
import io.github.pinpols.batch.orchestrator.infrastructure.scheduler.BatchDaySettleScheduler;
import io.github.pinpols.batch.orchestrator.infrastructure.timeout.WorkflowRunStuckReconciler;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture.WorkflowLaunchSeed;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowRunMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/** 验证持久化 WAIT 传感器恢复以及通过 Orchestrator 路由到业务数据库。 */
@DisplayName("Workflow 文件等待:完整 Orchestrator 上下文重建后恢复持久化 WAIT")
class WorkflowSensorApplicationContextRestartIntegrationTest extends AbstractIntegrationTest {

  private final String tenantId = "t1-wf-sensor-" + UUID.randomUUID().toString().replace("-", "");

  @Test
  @DisplayName("DB_ROW_EXISTS WAIT 从租户业务库命中后推进 Workflow")
  void shouldAdvanceWorkflow_whenDbRowExistsSensorFindsBusinessRow() {
    final String createSchemaSql = """
        create schema if not exists biz
    """;
    final String createIfSql = """
        create table if not exists biz.workflow_sensor_probe_test (
            tenant_id text not null, marker text not null)
        """;
    final String insertWorkflowSensorProbeTestSql = """
        insert into biz.workflow_sensor_probe_test (tenant_id, marker) values (?, ?)
    """;
    final String updateWorkflowNodeSql = """
        update batch.workflow_node set node_params = ?::jsonb where tenant_id = ? and workflow_definition_id = ?\s\
        and node_code = 'WAIT_FILE'\
        """;
    final String selectIdSql = """
        select id from batch.workflow_run where tenant_id = ? and workflow_definition_id = ? and biz_date = ?\
        """;
    final String updateWorkflowNodeRunSql = """
        update batch.workflow_node_run set sensor_next_probe_at = current_timestamp - interval '1 second'\s\
        where workflow_run_id = ? and node_code = 'WAIT_FILE'\
        """;
    final String selectNodeStatusSql = """
        select node_status from batch.workflow_node_run where workflow_run_id = ? and node_code = 'WAIT_FILE'\
        """;
    final String selectOutputSql = """
        select output::text from batch.workflow_node_run where workflow_run_id = ? and node_code = 'WAIT_FILE'\
        """;
    final String selectRunStatusSql = """
        select run_status from batch.workflow_run where tenant_id = ? and id = ?
    """;
    final String deleteWorkflowSensorProbeTestSql = """
        delete from biz.workflow_sensor_probe_test where tenant_id = ? and marker = ?
    """;

    String marker = "workflow-sensor-business-" + System.nanoTime();
    long suffix = System.nanoTime();
    String workflowCode = "WF_DB_SENSOR_" + suffix;
    String requestId = "req-db-sensor-" + suffix;
    LocalDate bizDate = LocalDate.of(2026, 10, 24);
    DriverManagerDataSource businessDataSource = new DriverManagerDataSource(
        businessJdbcUrl(), businessJdbcUsername(), businessJdbcPassword());
    JdbcTemplate businessJdbc = new JdbcTemplate(businessDataSource);
    businessJdbc.execute(createSchemaSql);
    businessJdbc.execute(createIfSql);
    businessJdbc.update(insertWorkflowSensorProbeTestSql, tenantId, marker);

    try (ConfigurableApplicationContext application = applicationContext(true)) {
      JdbcTemplate jdbc = application.getBean(JdbcTemplate.class);
      Long workflowDefinitionId =
          seedWorkflow(jdbc, workflowCode, requestId, "dedup-" + suffix, requestId, bizDate);
      String dbSensorParams = """
          {"sensor_type":"DB_ROW_EXISTS",
           "sensor_spec":{"schema":"biz",
             "sql":"select marker from biz.workflow_sensor_probe_test where tenant_id = :tenantId and marker = :marker limit 1",
             "params":{"tenantId":"%s","marker":"%s"}},
           "timeout_seconds":3600,"poll_interval_seconds":1,"on_timeout":"FAIL"}
          """.formatted(tenantId, marker);
      jdbc.update(updateWorkflowNodeSql, dbSensorParams, tenantId, workflowDefinitionId);
      LaunchRequest launchRequest = LaunchRequest.builder()
          .tenantId(tenantId)
          .workflowDefinitionId(workflowDefinitionId)
          .bizDate(bizDate)
          .triggerType(TriggerType.API)
          .requestId(requestId)
          .traceId(requestId)
          .params(Map.of())
          .build();
      RlsTenantContextHolder.runWithTenant(
          tenantId,
          () -> application.getBean(WorkflowRunLaunchService.class).launch(launchRequest));
      Long workflowRunId =
          jdbc.queryForObject(selectIdSql, Long.class, tenantId, workflowDefinitionId, bizDate);
      jdbc.update(updateWorkflowNodeRunSql, workflowRunId);

      RlsTenantContextHolder.runWithTenant(
          tenantId, () -> application.getBean(SensorPollScheduler.class).scan());

      String selectedNodeStatus =
          jdbc.queryForObject(selectNodeStatusSql, String.class, workflowRunId);
      assertThat(selectedNodeStatus).isEqualTo("SUCCESS");
      String selectedOutput = jdbc.queryForObject(selectOutputSql, String.class, workflowRunId);
      assertThat(selectedOutput).contains(marker);
      String selectedRunStatus =
          jdbc.queryForObject(selectRunStatusSql, String.class, tenantId, workflowRunId);
      assertThat(selectedRunStatus).isEqualTo("SUCCESS");
    } finally {
      businessJdbc.update(deleteWorkflowSensorProbeTestSql, tenantId, marker);
    }
  }

  @Test
  @DisplayName("数据库传感器异常的模拟凭据不写入 Workflow 节点错误参数")
  void shouldPersistSanitizedDatabaseSensorFailure() {
    String createSchema = "create schema if not exists biz";
    String createProbe =
        "create table if not exists biz.sensor_error_probe (tenant_id text not null)";
    String insertProbe = "insert into biz.sensor_error_probe (tenant_id) values (?)";
    String updateNode =
        "update batch.workflow_node set node_params = ?::jsonb where tenant_id = ? and workflow_definition_id = ? and node_code = 'WAIT_FILE'";
    String selectRun =
        "select id from batch.workflow_run where tenant_id = ? and workflow_definition_id = ? and biz_date = ?";
    String forceProbe =
        "update batch.workflow_node_run set sensor_next_probe_at = current_timestamp - interval '1 second' where workflow_run_id = ? and node_code = 'WAIT_FILE'";
    String selectStatus =
        "select run_status from batch.workflow_run where tenant_id = ? and id = ?";
    String deleteProbe = "delete from biz.sensor_error_probe where tenant_id = ?";
    String selectError = """
        select node_status, error_code, error_key, error_args::text as error_args
        from batch.workflow_node_run where workflow_run_id = ? and node_code = 'WAIT_FILE'
        """;
    long suffix = System.nanoTime();
    String requestId = "req-db-error-" + suffix;
    LocalDate bizDate = LocalDate.of(2026, 10, 24);
    DriverManagerDataSource businessDataSource = new DriverManagerDataSource(
        businessJdbcUrl(), businessJdbcUsername(), businessJdbcPassword());
    JdbcTemplate businessJdbc = new JdbcTemplate(businessDataSource);
    businessJdbc.execute(createSchema);
    businessJdbc.execute(createProbe);
    businessJdbc.update(insertProbe, tenantId);
    try (ConfigurableApplicationContext application = applicationContext(true)) {
      JdbcTemplate jdbc = application.getBean(JdbcTemplate.class);
      Long definitionId = seedWorkflow(
          jdbc, "WF_DB_ERROR_" + suffix, requestId, "dedup-db-error-" + suffix, requestId, bizDate);
      String params = """
          {"sensor_type":"DB_ROW_EXISTS",
           "sensor_spec":{"schema":"biz",
             "sql":"select cast('password=sample-secret' as integer) as marker from biz.sensor_error_probe where tenant_id = :tenantId",
             "params":{"tenantId":"%s"}},
           "timeout_seconds":3600,"poll_interval_seconds":1,"on_timeout":"FAIL"}
          """.formatted(tenantId);
      jdbc.update(updateNode, params, tenantId, definitionId);
      LaunchRequest request = LaunchRequest.builder()
          .tenantId(tenantId)
          .workflowDefinitionId(definitionId)
          .bizDate(bizDate)
          .triggerType(TriggerType.API)
          .requestId(requestId)
          .traceId(requestId)
          .params(Map.of())
          .build();
      RlsTenantContextHolder.runWithTenant(
          tenantId, () -> application.getBean(WorkflowRunLaunchService.class).launch(request));
      Long runId = jdbc.queryForObject(selectRun, Long.class, tenantId, definitionId, bizDate);
      for (int attempt = 0; attempt < 3; attempt++) {
        jdbc.update(forceProbe, runId);
        RlsTenantContextHolder.runWithTenant(
            tenantId, () -> application.getBean(SensorPollScheduler.class).scan());
      }
      Map<String, Object> row = jdbc.queryForMap(selectError, runId);
      assertThat(row)
          .containsEntry("node_status", "FAILED")
          .containsEntry("error_code", "SENSOR_FAIL")
          .containsEntry("error_key", "error.workflow.sensor_probe_failed");
      assertThat(row.get("error_args").toString())
          .contains("DB_ROW_EXISTS", "password=")
          .doesNotContain("sample-secret");
      assertThat(jdbc.queryForObject(selectStatus, String.class, tenantId, runId))
          .isEqualTo("FAILED");
    } finally {
      businessJdbc.update(deleteProbe, tenantId);
    }
  }

  @ParameterizedTest(name = "{0}先遇到 DB_ROW_EXISTS WAIT 释放时只派发一次")
  @ValueSource(strings = {"SETTLEMENT_FIRST", "WAIT_RELEASE_FIRST"})
  @DisplayName("DB_ROW_EXISTS WAIT 释放 JOB 与业务日结算竞争时保留唯一执行")
  void dbRowExistsWaitReleaseRacingSettlement_shouldKeepBatchDayInFlight(String firstLockWaiter)
      throws Exception {
    final String selectAcquireBatchDaySettlementFenceSql = """
            select batch.acquire_batch_day_settlement_fence(?, ?, ?)
        """;
    final String selectPgBackendPidSql = """
            select pg_backend_pid()
        """;

    final String createSchemaSql = """
            create schema if not exists biz
        """;
    final String createIfSql = """
        create table if not exists biz.workflow_sensor_probe_test (
            tenant_id text not null, marker text not null)
        """;
    final String insertWorkflowSensorProbeTestSql = """
            insert into biz.workflow_sensor_probe_test (tenant_id, marker) values (?, ?)
        """;
    final String insertJobDefinitionSql = """
          insert into batch.job_definition (
              tenant_id, job_code, job_name, job_type, biz_type, schedule_type, timezone,
              calendar_code, priority, queue_code, worker_group, trigger_mode, dag_enabled,
              shard_strategy, retry_policy, retry_max_count, timeout_seconds, enabled, version
          ) values (?, ?, ?, 'GENERAL', 'IT', 'MANUAL', 'UTC', 'JOB_CALENDAR',
              5, 'q-it', ?, 'API', false, 'NONE', 'NONE', 0, 0, true, 1)
          """;
    final String insertWorkerRegistrySql = """
          insert into batch.worker_registry (
              tenant_id, worker_code, worker_group, capability_tags, status, heartbeat_at, current_load
          ) values (?, ?, ?, '[]'::jsonb, 'ONLINE', now(), 0)
          """;
    final String updateWorkflowNodeSql = """
            update batch.workflow_node set node_params = ?::jsonb where tenant_id = ?\s\
            and workflow_definition_id = ? and node_code = 'WAIT_FILE'\
            """;
    final String updateWorkflowNodeSql2 = """
          update batch.workflow_node
          set node_code = 'DB_JOB', node_name = 'business row follow-up', node_type = 'JOB',
              related_job_code = ?, node_order = 2
          where tenant_id = ? and workflow_definition_id = ? and node_code = 'END'
          """;
    final String insertWorkflowNodeSql = """
          insert into batch.workflow_node (
              tenant_id, workflow_definition_id, node_code, node_name, node_type, node_order,
              retry_policy, retry_max_count, timeout_seconds, enabled
          ) values (?, ?, 'END', 'end', 'END', 3, 'NONE', 0, 0, true)
          """;
    final String deleteWorkflowEdgeSql = """
            delete from batch.workflow_edge where tenant_id = ? and workflow_definition_id = ?\
            """;
    final String insertWorkflowEdgeSql = """
          insert into batch.workflow_edge (
              tenant_id, workflow_definition_id, from_node_code, to_node_code, edge_type, enabled
          ) values (?, ?, 'START', 'WAIT_FILE', 'ALWAYS', true),
                   (?, ?, 'WAIT_FILE', 'DB_JOB', 'SUCCESS', true),
                   (?, ?, 'DB_JOB', 'END', 'SUCCESS', true)
          """;
    final String selectIdSql = """
            select id from batch.workflow_run where tenant_id = ? and trigger_request_id = (select id\s\
            from batch.trigger_request where tenant_id = ? and request_id = ?)\
            """;
    final String updateWorkflowNodeRunSql = """
            update batch.workflow_node_run set sensor_next_probe_at = current_timestamp - interval '1 second'\s\
            where workflow_run_id = ? and node_code = 'WAIT_FILE'\
            """;
    final String selectNodeStatusSql = """
            select node_status from batch.workflow_node_run where workflow_run_id = ?\s\
            and node_code = 'WAIT_FILE'\
            """;
    final String selectRunStatusSql = """
            select run_status from batch.workflow_run where tenant_id = ? and id = ?
        """;
    final String selectDayStatusSql = """
            select day_status from batch.batch_day_instance where tenant_id = ?\s\
            and calendar_code = 'JOB_CALENDAR' and biz_date = ?\
            """;
    final String selectCountSql = """
              select count(*) from batch.trigger_request
              where tenant_id = ? and parent_workflow_run_id = ? and parent_node_code = 'DB_JOB'
              """;
    final String selectJobInstanceIdSql = """
          select job_instance_id from batch.workflow_node_run
          where workflow_run_id = ? and node_code = 'DB_JOB'
          """;
    final String selectCountSql2 = """
              select count(*) from batch.job_instance
              where tenant_id = ? and id = ? and job_code = ? and biz_date = ?
              """;
    final String deleteWorkflowSensorProbeTestSql = """
        delete from biz.workflow_sensor_probe_test where tenant_id = ? and marker = ?
    """;

    String marker = "workflow-sensor-r11-" + System.nanoTime();
    long suffix = System.nanoTime();
    String workflowCode = "WF_DB_SENSOR_R11_" + suffix;
    String requestId = "req-db-sensor-r11-" + suffix;
    String jobCode = "JOB_DB_SENSOR_R11_" + suffix;
    String workerGroup = "WG_DB_SENSOR_R11_" + suffix;
    LocalDate bizDate = "SETTLEMENT_FIRST".equals(firstLockWaiter)
        ? LocalDate.of(2026, 10, 25)
        : LocalDate.of(2026, 10, 26);
    DriverManagerDataSource businessDataSource = new DriverManagerDataSource(
        businessJdbcUrl(), businessJdbcUsername(), businessJdbcPassword());
    JdbcTemplate businessJdbc = new JdbcTemplate(businessDataSource);
    businessJdbc.execute(createSchemaSql);
    businessJdbc.execute(createIfSql);
    businessJdbc.update(insertWorkflowSensorProbeTestSql, tenantId, marker);

    try (ConfigurableApplicationContext application = applicationContext(true, false)) {
      JdbcTemplate jdbc = application.getBean(JdbcTemplate.class);
      Long workflowDefinitionId =
          seedWorkflow(jdbc, workflowCode, requestId, "dedup-" + suffix, requestId, bizDate);
      jdbc.update(insertJobDefinitionSql, tenantId, jobCode, jobCode, workerGroup);
      jdbc.update(insertWorkerRegistrySql, tenantId, "wk-db-sensor-r11-" + suffix, workerGroup);

      String dbSensorParams = """
          {"sensor_type":"DB_ROW_EXISTS",
           "sensor_spec":{"schema":"biz",
             "sql":"select marker from biz.workflow_sensor_probe_test where tenant_id = :tenantId and marker = :marker limit 1",
             "params":{"tenantId":"%s","marker":"%s"}},
           "timeout_seconds":3600,"poll_interval_seconds":1,"on_timeout":"FAIL"}
          """.formatted(tenantId, marker);
      jdbc.update(updateWorkflowNodeSql, dbSensorParams, tenantId, workflowDefinitionId);
      jdbc.update(updateWorkflowNodeSql2, jobCode, tenantId, workflowDefinitionId);
      jdbc.update(insertWorkflowNodeSql, tenantId, workflowDefinitionId);
      jdbc.update(deleteWorkflowEdgeSql, tenantId, workflowDefinitionId);
      jdbc.update(
          insertWorkflowEdgeSql,
          tenantId,
          workflowDefinitionId,
          tenantId,
          workflowDefinitionId,
          tenantId,
          workflowDefinitionId);

      LaunchRequest launchRequest = LaunchRequest.builder()
          .tenantId(tenantId)
          .workflowDefinitionId(workflowDefinitionId)
          .bizDate(bizDate)
          .triggerType(TriggerType.API)
          .requestId(requestId)
          .traceId(requestId)
          .params(Map.of())
          .build();
      RlsTenantContextHolder.runWithTenant(
          tenantId,
          () -> application.getBean(WorkflowRunLaunchService.class).launch(launchRequest));
      Long workflowRunId =
          jdbc.queryForObject(selectIdSql, Long.class, tenantId, tenantId, requestId);
      insertCutoffBatchDay(jdbc, bizDate);
      jdbc.update(updateWorkflowNodeRunSql, workflowRunId);

      ExecutorService executor = Executors.newFixedThreadPool(2);
      try (Connection fence = jdbc.getDataSource().getConnection()) {
        fence.setAutoCommit(false);
        try (var statement = fence.prepareStatement(selectAcquireBatchDaySettlementFenceSql)) {
          statement.setString(1, tenantId);
          statement.setString(2, "JOB_CALENDAR");
          statement.setObject(3, bizDate);
          statement.executeQuery().close();
        }
        int blockerPid;
        try (var statement = fence.createStatement();
            var result = statement.executeQuery(selectPgBackendPidSql)) {
          assertThat(result.next()).isTrue();
          blockerPid = result.getInt(1);
        }

        Future<?> settlement;
        Future<?> sensorRelease;
        if ("SETTLEMENT_FIRST".equals(firstLockWaiter)) {
          settlement = executor.submit(
              () -> application.getBean(BatchDaySettleScheduler.class).settle());
          awaitBlockedBy(jdbc, blockerPid, 1);
          sensorRelease = executor.submit(() -> RlsTenantContextHolder.runWithTenant(
              tenantId, () -> application.getBean(SensorPollScheduler.class).scan()));
        } else {
          sensorRelease = executor.submit(() -> RlsTenantContextHolder.runWithTenant(
              tenantId, () -> application.getBean(SensorPollScheduler.class).scan()));
          awaitBlockedBy(jdbc, blockerPid, 1);
          settlement = executor.submit(
              () -> application.getBean(BatchDaySettleScheduler.class).settle());
        }
        awaitBlockedBy(jdbc, blockerPid, 2);
        fence.commit();
        settlement.get(30, TimeUnit.SECONDS);
        sensorRelease.get(30, TimeUnit.SECONDS);
      } finally {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
      }

      String selectedNodeStatus =
          jdbc.queryForObject(selectNodeStatusSql, String.class, workflowRunId);
      assertThat(selectedNodeStatus).isEqualTo("SUCCESS");
      String selectedRunStatus =
          jdbc.queryForObject(selectRunStatusSql, String.class, tenantId, workflowRunId);
      assertThat(selectedRunStatus).isEqualTo("RUNNING");
      String selectedDayStatus =
          jdbc.queryForObject(selectDayStatusSql, String.class, tenantId, bizDate);
      assertThat(selectedDayStatus).isEqualTo("IN_FLIGHT");
      Integer selectedTriggerRequestCount =
          jdbc.queryForObject(selectCountSql, Integer.class, tenantId, workflowRunId);
      assertThat(selectedTriggerRequestCount).isEqualTo(1);
      Long jobInstanceId = jdbc.queryForObject(selectJobInstanceIdSql, Long.class, workflowRunId);
      assertThat(jobInstanceId).isNotNull();
      Integer selectedJobInstanceCount = jdbc.queryForObject(
          selectCountSql2, Integer.class, tenantId, jobInstanceId, jobCode, bizDate);
      assertThat(selectedJobInstanceCount).isEqualTo(1);
    } finally {
      businessJdbc.update(deleteWorkflowSensorProbeTestSql, tenantId, marker);
    }
  }

  @ParameterizedTest(name = "批日结算等待围栏时 WAIT timeout={0} 后正确结算")
  @ValueSource(strings = {"FAIL", "SKIP_DOWNSTREAM"})
  void shouldSettleTimedOutWaitAccordingToAction_whenSettlementIsBlockedOnBusinessDayFence(
      String timeoutAction) throws Exception {
    final String selectAcquireBatchDaySettlementFenceSql = """
            select batch.acquire_batch_day_settlement_fence(?, ?, ?)
        """;
    final String selectPgBackendPidSql = """
            select pg_backend_pid()
        """;

    final String updateWorkflowNodeSql = """
            update batch.workflow_node set node_params = jsonb_set(node_params, '{on_timeout}',\s\
            to_jsonb(?::text)) where tenant_id = ? and workflow_definition_id = ? and node_code = 'WAIT_FILE'\
            """;
    final String selectIdSql = """
            select id from batch.workflow_run where tenant_id = ? and trigger_request_id = (select id\s\
            from batch.trigger_request where tenant_id = ? and request_id = ?)\
            """;
    final String updateWorkflowNodeRunSql = """
            update batch.workflow_node_run set started_at = current_timestamp - interval '2 hours',\s\
            sensor_next_probe_at = current_timestamp - interval '1 second' where workflow_run_id = ?\s\
            and node_code = 'WAIT_FILE'\
            """;
    final String selectCountSql = """
        select count(*) from pg_stat_activity where ? = any(pg_blocking_pids(pid))
    """;
    final String selectNodeStatusSql = """
        select node_status from batch.workflow_node_run where workflow_run_id = ? and node_code = 'WAIT_FILE'\
        """;
    final String selectRunStatusSql = """
        select run_status from batch.workflow_run where tenant_id = ? and id = ?
    """;

    long suffix = System.nanoTime();
    String workflowCode = "WF_CONTEXT_TIMEOUT_R11_" + suffix;
    String requestId = "req-context-timeout-r11-" + suffix;
    LocalDate bizDate =
        "FAIL".equals(timeoutAction) ? LocalDate.of(2026, 10, 22) : LocalDate.of(2026, 10, 23);

    try (ConfigurableApplicationContext application = applicationContext(true)) {
      JdbcTemplate jdbc = application.getBean(JdbcTemplate.class);
      Long workflowDefinitionId =
          seedWorkflow(jdbc, workflowCode, requestId, "dedup-" + suffix, requestId, bizDate);
      jdbc.update(updateWorkflowNodeSql, timeoutAction, tenantId, workflowDefinitionId);
      LaunchRequest launchRequest = LaunchRequest.builder()
          .tenantId(tenantId)
          .workflowDefinitionId(workflowDefinitionId)
          .bizDate(bizDate)
          .triggerType(TriggerType.API)
          .requestId(requestId)
          .traceId(requestId)
          .params(Map.of())
          .build();
      RlsTenantContextHolder.runWithTenant(
          tenantId,
          () -> application.getBean(WorkflowRunLaunchService.class).launch(launchRequest));
      Long workflowRunId =
          jdbc.queryForObject(selectIdSql, Long.class, tenantId, tenantId, requestId);
      insertCutoffBatchDay(jdbc, bizDate);
      jdbc.update(updateWorkflowNodeRunSql, workflowRunId);

      ExecutorService executor = Executors.newSingleThreadExecutor();
      try (Connection fence = jdbc.getDataSource().getConnection()) {
        fence.setAutoCommit(false);
        try (var statement = fence.prepareStatement(selectAcquireBatchDaySettlementFenceSql)) {
          statement.setString(1, tenantId);
          statement.setString(2, "JOB_CALENDAR");
          statement.setObject(3, bizDate);
          statement.executeQuery().close();
        }
        int blockerPid;
        try (var statement = fence.createStatement();
            var result = statement.executeQuery(selectPgBackendPidSql)) {
          assertThat(result.next()).isTrue();
          blockerPid = result.getInt(1);
        }

        var settlement = executor.submit(
            () -> application.getBean(BatchDaySettleScheduler.class).settle());
        Awaitility.await()
            .atMost(Duration.ofSeconds(10))
            .pollInterval(Duration.ofMillis(25))
            .untilAsserted(() -> {
              Integer selectedPgStatActivityCount =
                  jdbc.queryForObject(selectCountSql, Integer.class, blockerPid);
              assertThat(selectedPgStatActivityCount).isGreaterThanOrEqualTo(1);
            });

        RlsTenantContextHolder.runWithTenant(
            tenantId, () -> application.getBean(SensorPollScheduler.class).scan());
        String expectedWaitStatus = "FAIL".equals(timeoutAction) ? "FAILED" : "SKIPPED";
        String expectedRunStatus = "FAIL".equals(timeoutAction) ? "FAILED" : "SUCCESS";
        String selectedNodeStatus =
            jdbc.queryForObject(selectNodeStatusSql, String.class, workflowRunId);
        assertThat(selectedNodeStatus).isEqualTo(expectedWaitStatus);
        String selectedRunStatus =
            jdbc.queryForObject(selectRunStatusSql, String.class, tenantId, workflowRunId);
        assertThat(selectedRunStatus).isEqualTo(expectedRunStatus);

        fence.commit();
        settlement.get(30, TimeUnit.SECONDS);
      } finally {
        executor.shutdownNow();
      }

      String expectedBatchDayStatus = "FAIL".equals(timeoutAction) ? "FAILED" : "SETTLED";
      assertThat(batchDayStatus(jdbc, bizDate)).isEqualTo(expectedBatchDayStatus);
    }
  }

  @Test
  @DisplayName("WAIT 超时配置跳过下游时级联跳过并成功收口")
  void shouldSkipDownstreamAndSucceed_whenWaitTimeoutActionIsSkipDownstream() {
    final String deleteWorkflowEdgeSql = """
        delete from batch.workflow_edge where tenant_id = ? and workflow_definition_id = ?\s\
        and from_node_code = 'WAIT_FILE'\
        """;
    final String updateWorkflowNodeSql = """
        update batch.workflow_node set node_order = 3 where tenant_id = ? and workflow_definition_id = ?\s\
        and node_code = 'END'\
        """;
    final String insertWorkflowNodeSql = """
          insert into batch.workflow_node (
              tenant_id, workflow_definition_id, node_code, node_name, node_type, node_order,
              node_params, retry_policy, retry_max_count, timeout_seconds, enabled
          ) values (?, ?, 'SKIPPED_GATEWAY', 'skip downstream gateway', 'GATEWAY', 2,
              '{}'::jsonb, 'NONE', 0, 0, true)
          """;
    final String insertWorkflowEdgeSql = """
          insert into batch.workflow_edge (
              tenant_id, workflow_definition_id, from_node_code, to_node_code, edge_type, enabled
          ) values (?, ?, 'WAIT_FILE', 'SKIPPED_GATEWAY', 'SUCCESS', true),
                   (?, ?, 'SKIPPED_GATEWAY', 'END', 'SUCCESS', true)
          """;
    final String updateWorkflowNodeSql2 = """
        update batch.workflow_node set node_params = jsonb_set(node_params, '{on_timeout}',\s\
        '"SKIP_DOWNSTREAM"'::jsonb) where tenant_id = ? and workflow_definition_id = ?\s\
        and node_code = 'WAIT_FILE'\
        """;
    final String selectIdSql = """
        select id from batch.workflow_run where tenant_id = ? and trigger_request_id = (select id\s\
        from batch.trigger_request where tenant_id = ? and request_id = ?)\
        """;
    final String updateWorkflowNodeRunSql = """
        update batch.workflow_node_run set started_at = current_timestamp - interval '2 hours',\s\
        sensor_next_probe_at = current_timestamp - interval '1 second' where workflow_run_id = ?\s\
        and node_code = 'WAIT_FILE'\
        """;
    final String selectNodeStatusSql = """
        select node_status from batch.workflow_node_run where workflow_run_id = ? and node_code = 'WAIT_FILE'\
        """;
    final String selectSkipReasonCodeSql = """
        select skip_reason_code from batch.workflow_node_run where workflow_run_id = ?\s\
        and node_code = 'WAIT_FILE'\
        """;
    final String selectNodeStatusSql2 = """
        select node_status from batch.workflow_node_run where workflow_run_id = ?\s\
        and node_code = 'SKIPPED_GATEWAY'\
        """;
    final String selectCountSql = """
        select count(*) from batch.workflow_node_run where workflow_run_id = ? and node_code = 'END'\
        """;
    final String selectRunStatusSql = """
        select run_status from batch.workflow_run where tenant_id = ? and id = ?
    """;

    long suffix = System.nanoTime();
    String workflowCode = "WF_CONTEXT_SKIP_TIMEOUT_R08_" + suffix;
    String requestId = "req-context-skip-timeout-r08-" + suffix;
    LocalDate bizDate = LocalDate.of(2026, 10, 20);

    try (ConfigurableApplicationContext application = applicationContext(true)) {
      JdbcTemplate jdbc = application.getBean(JdbcTemplate.class);
      Long workflowDefinitionId =
          seedWorkflow(jdbc, workflowCode, requestId, "dedup-" + suffix, requestId, bizDate);
      jdbc.update(deleteWorkflowEdgeSql, tenantId, workflowDefinitionId);
      jdbc.update(updateWorkflowNodeSql, tenantId, workflowDefinitionId);
      jdbc.update(insertWorkflowNodeSql, tenantId, workflowDefinitionId);
      jdbc.update(
          insertWorkflowEdgeSql, tenantId, workflowDefinitionId, tenantId, workflowDefinitionId);
      jdbc.update(updateWorkflowNodeSql2, tenantId, workflowDefinitionId);
      LaunchRequest launchRequest = LaunchRequest.builder()
          .tenantId(tenantId)
          .workflowDefinitionId(workflowDefinitionId)
          .bizDate(bizDate)
          .triggerType(TriggerType.API)
          .requestId(requestId)
          .traceId(requestId)
          .params(Map.of())
          .build();
      RlsTenantContextHolder.runWithTenant(
          tenantId,
          () -> application.getBean(WorkflowRunLaunchService.class).launch(launchRequest));
      Long workflowRunId =
          jdbc.queryForObject(selectIdSql, Long.class, tenantId, tenantId, requestId);
      insertCutoffBatchDay(jdbc, bizDate);
      jdbc.update(updateWorkflowNodeRunSql, workflowRunId);

      RlsTenantContextHolder.runWithTenant(
          tenantId, () -> application.getBean(SensorPollScheduler.class).scan());

      String selectedNodeStatus =
          jdbc.queryForObject(selectNodeStatusSql, String.class, workflowRunId);
      assertThat(selectedNodeStatus).isEqualTo("SKIPPED");
      String selectedSkipReasonCode =
          jdbc.queryForObject(selectSkipReasonCodeSql, String.class, workflowRunId);
      assertThat(selectedSkipReasonCode).isEqualTo("SENSOR_TIMEOUT_SKIP_DOWNSTREAM");
      String selectedNodeStatusRead2 =
          jdbc.queryForObject(selectNodeStatusSql2, String.class, workflowRunId);
      assertThat(selectedNodeStatusRead2).isEqualTo("SKIPPED");
      Integer selectedWorkflowNodeRunCount =
          jdbc.queryForObject(selectCountSql, Integer.class, workflowRunId);
      assertThat(selectedWorkflowNodeRunCount).isZero();
      String selectedRunStatus =
          jdbc.queryForObject(selectRunStatusSql, String.class, tenantId, workflowRunId);
      assertThat(selectedRunStatus).isEqualTo("SUCCESS");

      application.getBean(BatchDaySettleScheduler.class).settle();
      assertThat(batchDayStatus(jdbc, bizDate)).isEqualTo("SETTLED");
    }
  }

  @Test
  @DisplayName("WAIT 超时跳过时保留多前驱 ANY 汇聚,另一 WAIT 命中后仍可继续")
  void shouldKeepAnyJoinReachable_whenAnotherWaitSucceedsAfterSkipDownstreamTimeout() {
    final String deleteWorkflowEdgeSql = """
        delete from batch.workflow_edge where tenant_id = ? and workflow_definition_id = ?
    """;
    final String updateWorkflowNodeSql = """
        update batch.workflow_node set node_code = 'ANY_JOIN', node_name = 'any join', node_type = 'GATEWAY',\s\
        node_order = 3, node_params = '{"joinMode":"ANY"}'::jsonb where tenant_id = ?\s\
        and workflow_definition_id = ? and node_code = 'END'\
        """;
    final String updateWorkflowNodeSql2 = """
        update batch.workflow_node set node_order = 1 where tenant_id = ? and workflow_definition_id = ?\s\
        and node_code = 'WAIT_FILE'\
        """;
    final String updateWorkflowNodeSql3 = """
        update batch.workflow_node set node_params = jsonb_set(node_params, '{on_timeout}',\s\
        '"SKIP_DOWNSTREAM"'::jsonb) where tenant_id = ? and workflow_definition_id = ?\s\
        and node_code = 'WAIT_FILE'\
        """;
    final String updateWorkflowNodeSql4 = """
        update batch.workflow_node set node_order = 4 where tenant_id = ? and workflow_definition_id = ?\s\
        and node_code = 'ANY_JOIN'\
        """;
    final String updateWorkflowNodeSql5 = """
        update batch.workflow_node set node_code = 'END', node_name = 'end', node_type = 'END', node_order = 4,\s\
        node_params = '{}'::jsonb where tenant_id = ? and workflow_definition_id = ? and node_code = 'ANY_JOIN'\
        """;
    final String insertWorkflowNodeSql = """
          insert into batch.workflow_node (
              tenant_id, workflow_definition_id, node_code, node_name, node_type, node_order,
              node_params, retry_policy, retry_max_count, timeout_seconds, enabled
          ) values (?, ?, 'WAIT_OTHER', 'wait for alternate file', 'WAIT', 2, ?::jsonb,
              'NONE', 0, 3600, true)
          """;
    final String insertWorkflowNodeSql2 = """
          insert into batch.workflow_node (
              tenant_id, workflow_definition_id, node_code, node_name, node_type, node_order,
              node_params, retry_policy, retry_max_count, timeout_seconds, enabled
          ) values (?, ?, 'ANY_JOIN', 'any join', 'GATEWAY', 3, '{"joinMode":"ANY"}'::jsonb,
              'NONE', 0, 0, true)
          """;
    final String insertWorkflowEdgeSql = """
          insert into batch.workflow_edge (
              tenant_id, workflow_definition_id, from_node_code, to_node_code, edge_type, enabled
          ) values (?, ?, 'START', 'WAIT_FILE', 'ALWAYS', true),
                   (?, ?, 'START', 'WAIT_OTHER', 'ALWAYS', true),
                   (?, ?, 'WAIT_FILE', 'ANY_JOIN', 'SUCCESS', true),
                   (?, ?, 'WAIT_OTHER', 'ANY_JOIN', 'SUCCESS', true),
                   (?, ?, 'ANY_JOIN', 'END', 'SUCCESS', true)
          """;
    final String selectIdSql = """
        select id from batch.workflow_run where tenant_id = ? and trigger_request_id = (select id\s\
        from batch.trigger_request where tenant_id = ? and request_id = ?)\
        """;
    final String updateWorkflowNodeRunSql = """
        update batch.workflow_node_run set started_at = current_timestamp - interval '2 hours',\s\
        sensor_next_probe_at = current_timestamp - interval '1 second' where workflow_run_id = ?\s\
        and node_code = 'WAIT_FILE'\
        """;
    final String selectNodeStatusSql = """
        select node_status from batch.workflow_node_run where workflow_run_id = ? and node_code = 'WAIT_FILE'\
        """;
    final String selectCountSql = """
        select count(*) from batch.workflow_node_run where workflow_run_id = ? and node_code = 'ANY_JOIN'\
        """;
    final String selectRunStatusSql = """
        select run_status from batch.workflow_run where tenant_id = ? and id = ?
    """;
    final String insertFileRecordSql = """
          insert into batch.file_record (
              tenant_id, file_category, file_name, file_format_type, storage_type, storage_path,
              source_type, file_status, biz_date, metadata_json, created_at
          ) values (?, 'INPUT', ?, 'DELIMITED', 'S3', ?, 'SFTP', 'RECEIVED', ?,
              jsonb_build_object('fileGroupCode', 'CONTEXT_RESTART_ANY', 'arrivalState', 'TRIGGERED'), now())
          """;
    final String updateWorkflowNodeRunSql2 = """
        update batch.workflow_node_run set sensor_next_probe_at = current_timestamp - interval '1 second'\s\
        where workflow_run_id = ? and node_code = 'WAIT_OTHER'\
        """;
    final String selectNodeStatusSql2 = """
        select node_status from batch.workflow_node_run where workflow_run_id = ? and node_code = 'WAIT_OTHER'\
        """;
    final String selectNodeStatusSql3 = """
        select node_status from batch.workflow_node_run where workflow_run_id = ? and node_code = 'ANY_JOIN'\
        """;
    final String selectNodeStatusSql4 = """
        select node_status from batch.workflow_node_run where workflow_run_id = ? and node_code = 'END'\
        """;

    long suffix = System.nanoTime();
    String workflowCode = "WF_CONTEXT_SKIP_TIMEOUT_ANY_R08_" + suffix;
    String requestId = "req-context-skip-timeout-any-r08-" + suffix;
    LocalDate bizDate = LocalDate.of(2026, 10, 21);

    try (ConfigurableApplicationContext application = applicationContext(true)) {
      JdbcTemplate jdbc = application.getBean(JdbcTemplate.class);
      Long workflowDefinitionId =
          seedWorkflow(jdbc, workflowCode, requestId, "dedup-" + suffix, requestId, bizDate);
      jdbc.update(deleteWorkflowEdgeSql, tenantId, workflowDefinitionId);
      jdbc.update(updateWorkflowNodeSql, tenantId, workflowDefinitionId);
      jdbc.update(updateWorkflowNodeSql2, tenantId, workflowDefinitionId);
      jdbc.update(updateWorkflowNodeSql3, tenantId, workflowDefinitionId);
      jdbc.update(updateWorkflowNodeSql4, tenantId, workflowDefinitionId);
      jdbc.update(updateWorkflowNodeSql5, tenantId, workflowDefinitionId);
      String secondWaitParams = """
          {"sensor_type":"FILE_ARRIVAL",
           "sensor_spec":{"pattern":"context-restart-any-*.csv","maxAgeSeconds":3600,
             "bindWorkflowBizDate":true,"fileGroupCode":"CONTEXT_RESTART_ANY","requireCompleteGroup":true},
           "timeout_seconds":3600,"poll_interval_seconds":1,"on_timeout":"FAIL"}
          """;
      jdbc.update(insertWorkflowNodeSql, tenantId, workflowDefinitionId, secondWaitParams);
      jdbc.update(insertWorkflowNodeSql2, tenantId, workflowDefinitionId);
      jdbc.update(
          insertWorkflowEdgeSql,
          tenantId,
          workflowDefinitionId,
          tenantId,
          workflowDefinitionId,
          tenantId,
          workflowDefinitionId,
          tenantId,
          workflowDefinitionId,
          tenantId,
          workflowDefinitionId);

      LaunchRequest launchRequest = LaunchRequest.builder()
          .tenantId(tenantId)
          .workflowDefinitionId(workflowDefinitionId)
          .bizDate(bizDate)
          .triggerType(TriggerType.API)
          .requestId(requestId)
          .traceId(requestId)
          .params(Map.of())
          .build();
      RlsTenantContextHolder.runWithTenant(
          tenantId,
          () -> application.getBean(WorkflowRunLaunchService.class).launch(launchRequest));
      Long workflowRunId =
          jdbc.queryForObject(selectIdSql, Long.class, tenantId, tenantId, requestId);
      jdbc.update(updateWorkflowNodeRunSql, workflowRunId);

      RlsTenantContextHolder.runWithTenant(
          tenantId, () -> application.getBean(SensorPollScheduler.class).scan());

      String selectedNodeStatus =
          jdbc.queryForObject(selectNodeStatusSql, String.class, workflowRunId);
      assertThat(selectedNodeStatus).isEqualTo("SKIPPED");
      Integer selectedWorkflowNodeRunCount =
          jdbc.queryForObject(selectCountSql, Integer.class, workflowRunId);
      assertThat(selectedWorkflowNodeRunCount).isZero();
      String selectedRunStatus =
          jdbc.queryForObject(selectRunStatusSql, String.class, tenantId, workflowRunId);
      assertThat(selectedRunStatus).isEqualTo("RUNNING");

      String fileName = "context-restart-any-" + suffix + ".csv";
      jdbc.update(insertFileRecordSql, tenantId, fileName, "s3://test/" + fileName, bizDate);
      jdbc.update(updateWorkflowNodeRunSql2, workflowRunId);
      RlsTenantContextHolder.runWithTenant(
          tenantId, () -> application.getBean(SensorPollScheduler.class).scan());

      String selectedNodeStatusRead2 =
          jdbc.queryForObject(selectNodeStatusSql2, String.class, workflowRunId);
      assertThat(selectedNodeStatusRead2).isEqualTo("SUCCESS");
      String selectedNodeStatusRead3 =
          jdbc.queryForObject(selectNodeStatusSql3, String.class, workflowRunId);
      assertThat(selectedNodeStatusRead3).isEqualTo("SUCCESS");
      String selectedNodeStatusRead4 =
          jdbc.queryForObject(selectNodeStatusSql4, String.class, workflowRunId);
      assertThat(selectedNodeStatusRead4).isEqualTo("SUCCESS");
      String selectedRunStatusRead2 =
          jdbc.queryForObject(selectRunStatusSql, String.class, tenantId, workflowRunId);
      assertThat(selectedRunStatusRead2).isEqualTo("SUCCESS");
    }
  }

  @Test
  @DisplayName("N_OF 数学整数阈值在一条分支跳过后仍能由另一条分支释放")
  void shouldReleaseNOfIntegralThresholdAfterOneBranchSkips() {
    String deleteEdges =
        "delete from batch.workflow_edge where tenant_id = ? and workflow_definition_id = ?";
    String updateEnd =
        "update batch.workflow_node set node_order = 4 where tenant_id = ? and workflow_definition_id = ? and node_code = 'END'";
    String updateFirstWait = """
        update batch.workflow_node set node_params = jsonb_set(node_params, '{on_timeout}', '"SKIP_DOWNSTREAM"'::jsonb)
        where tenant_id = ? and workflow_definition_id = ? and node_code = 'WAIT_FILE'
        """;
    String insertWait = """
        insert into batch.workflow_node (tenant_id,workflow_definition_id,node_code,node_name,node_type,node_order,
          node_params,retry_policy,retry_max_count,timeout_seconds,enabled)
        values (?, ?, 'WAIT_OTHER', 'other wait', 'WAIT', 2, ?::jsonb, 'NONE', 0, 3600, true)
        """;
    String insertJoin = """
        insert into batch.workflow_node (tenant_id,workflow_definition_id,node_code,node_name,node_type,node_order,
          node_params,retry_policy,retry_max_count,timeout_seconds,enabled)
        values (?, ?, 'JOIN', 'join', 'GATEWAY', 3, '{"joinMode":"N_OF","joinThreshold":1.0}'::jsonb,
          'NONE', 0, 0, true)
        """;
    String insertEdges = """
        insert into batch.workflow_edge (tenant_id,workflow_definition_id,from_node_code,to_node_code,edge_type,enabled)
        values (?, ?, 'START', 'WAIT_FILE', 'ALWAYS', true), (?, ?, 'START', 'WAIT_OTHER', 'ALWAYS', true),
          (?, ?, 'WAIT_FILE', 'JOIN', 'SUCCESS', true), (?, ?, 'WAIT_OTHER', 'JOIN', 'SUCCESS', true),
          (?, ?, 'JOIN', 'END', 'SUCCESS', true)
        """;
    String selectRun =
        "select id from batch.workflow_run where tenant_id = ? and workflow_definition_id = ? and biz_date = ?";
    String expireWait = """
        update batch.workflow_node_run set started_at = current_timestamp - interval '2 hours',
          sensor_next_probe_at = current_timestamp - interval '1 second'
        where workflow_run_id = ? and node_code = 'WAIT_FILE'
        """;
    String setProbeTime = """
        update batch.workflow_node_run set sensor_next_probe_at = current_timestamp + (? * interval '1 second')
        where workflow_run_id = ? and node_code = 'WAIT_OTHER'
        """;
    String selectNode =
        "select node_status from batch.workflow_node_run where workflow_run_id = ? and node_code = ?";
    String countJoin =
        "select count(*) from batch.workflow_node_run where workflow_run_id = ? and node_code = 'JOIN'";
    String insertFile = """
        insert into batch.file_record (tenant_id,file_category,file_name,file_format_type,storage_type,
          storage_path,source_type,file_status,biz_date)
        values (?, 'INPUT', ?, 'DELIMITED', 'S3', ?, 'SFTP', 'RECEIVED', ?)
        """;
    String selectRunStatus =
        "select run_status from batch.workflow_run where tenant_id = ? and id = ?";
    String countTerminal = """
        select count(*) from batch.outbox_event where tenant_id = ? and aggregate_type = 'WORKFLOW_RUN'
          and aggregate_id = ? and event_type = 'WORKFLOW_TERMINAL'
        """;
    long suffix = System.nanoTime();
    String requestId = "req-n-of-" + suffix;
    LocalDate bizDate = LocalDate.of(2026, 10, 21);
    try (ConfigurableApplicationContext application = applicationContext(true)) {
      JdbcTemplate jdbc = application.getBean(JdbcTemplate.class);
      Long definitionId = seedWorkflow(
          jdbc, "WF_N_OF_" + suffix, requestId, "dedup-n-of-" + suffix, requestId, bizDate);
      jdbc.update(deleteEdges, tenantId, definitionId);
      jdbc.update(updateEnd, tenantId, definitionId);
      jdbc.update(updateFirstWait, tenantId, definitionId);
      String sensorParams = """
          {"sensor_type":"FILE_ARRIVAL","sensor_spec":{"pattern":"n-of-ready-*.csv","maxAgeSeconds":3600},
            "timeout_seconds":3600,"poll_interval_seconds":1,"on_timeout":"FAIL"}
          """;
      jdbc.update(insertWait, tenantId, definitionId, sensorParams);
      jdbc.update(insertJoin, tenantId, definitionId);
      jdbc.update(
          insertEdges,
          tenantId,
          definitionId,
          tenantId,
          definitionId,
          tenantId,
          definitionId,
          tenantId,
          definitionId,
          tenantId,
          definitionId);
      LaunchRequest request = LaunchRequest.builder()
          .tenantId(tenantId)
          .workflowDefinitionId(definitionId)
          .bizDate(bizDate)
          .triggerType(TriggerType.API)
          .requestId(requestId)
          .traceId(requestId)
          .params(Map.of())
          .build();
      RlsTenantContextHolder.runWithTenant(
          tenantId, () -> application.getBean(WorkflowRunLaunchService.class).launch(request));
      Long runId = jdbc.queryForObject(selectRun, Long.class, tenantId, definitionId, bizDate);
      jdbc.update(expireWait, runId);
      jdbc.update(setProbeTime, 3600, runId);
      RlsTenantContextHolder.runWithTenant(
          tenantId, () -> application.getBean(SensorPollScheduler.class).scan());
      assertThat(jdbc.queryForObject(selectNode, String.class, runId, "WAIT_FILE"))
          .isEqualTo("SKIPPED");
      assertThat(jdbc.queryForObject(countJoin, Long.class, runId)).isZero();
      assertThat(jdbc.queryForObject(selectRunStatus, String.class, tenantId, runId))
          .isEqualTo("RUNNING");
      String fileName = "n-of-ready-" + suffix + ".csv";
      jdbc.update(insertFile, tenantId, fileName, "s3://test/" + fileName, bizDate);
      jdbc.update(setProbeTime, -1, runId);
      RlsTenantContextHolder.runWithTenant(
          tenantId, () -> application.getBean(SensorPollScheduler.class).scan());
      assertThat(jdbc.queryForObject(selectNode, String.class, runId, "WAIT_OTHER"))
          .isEqualTo("SUCCESS");
      assertThat(jdbc.queryForObject(selectNode, String.class, runId, "JOIN")).isEqualTo("SUCCESS");
      assertThat(jdbc.queryForObject(selectNode, String.class, runId, "END")).isEqualTo("SUCCESS");
      assertThat(jdbc.queryForObject(selectRunStatus, String.class, tenantId, runId))
          .isEqualTo("SUCCESS");
      assertThat(jdbc.queryForObject(countTerminal, Long.class, tenantId, runId))
          .isEqualTo(1L);
    }
  }

  @ParameterizedTest(name = "{0}时 FILE_ARRIVAL WAIT 终结 Workflow 后结算空日")
  @ValueSource(strings = {"SETTLEMENT_FIRST", "WAIT_RELEASE_FIRST"})
  @DisplayName("FILE_ARRIVAL WAIT 终结无 Job Workflow 与空日结算两种顺序均收敛")
  void fileArrivalWaitFinalizationRacingSettlement_shouldSettleOnlyAfterWorkflowCompletes(
      String firstAction) {
    final String selectIdSql = """
            select id from batch.workflow_run where tenant_id = ? and workflow_definition_id = ?\s\
            and biz_date = ?\
            """;
    final String insertFileRecordSql = """
          insert into batch.file_record (
              tenant_id, file_category, file_name, file_format_type, storage_type, storage_path,
              source_type, file_status, biz_date, metadata_json, created_at
          ) values (?, 'INPUT', ?, 'DELIMITED', 'S3', ?, 'SFTP', 'RECEIVED', ?,
              jsonb_build_object('fileGroupCode', 'CONTEXT_RESTART', 'arrivalState', 'TRIGGERED'), now())
          """;
    final String updateWorkflowNodeRunSql = """
        update batch.workflow_node_run set sensor_next_probe_at = current_timestamp - interval '1 second'\s\
        where workflow_run_id = ? and node_code = 'WAIT_FILE'\
        """;
    final String selectNodeStatusSql = """
        select node_status from batch.workflow_node_run where workflow_run_id = ? and node_code = 'WAIT_FILE'\
        """;
    final String selectCountSql = """
        select count(*) from batch.outbox_event where tenant_id = ? and aggregate_type = 'WORKFLOW_RUN'\s\
        and aggregate_id = ? and event_type = 'WORKFLOW_TERMINAL'\
        """;

    long suffix = System.nanoTime();
    String workflowCode = "WF_FILE_SENSOR_R11_" + suffix;
    String requestId = "req-file-sensor-r11-" + suffix;
    String fileName = "context-restart-r11-" + suffix + ".csv";
    LocalDate bizDate =
        LocalDate.now(ZoneOffset.UTC).plusDays(1_000 + Math.floorMod(suffix, 100_000));

    try (ConfigurableApplicationContext application = applicationContext(true)) {
      JdbcTemplate jdbc = application.getBean(JdbcTemplate.class);
      Long workflowDefinitionId = seedWorkflow(
          jdbc, workflowCode, requestId, "dedup-file-sensor-r11-" + suffix, requestId, bizDate);
      LaunchRequest launchRequest = LaunchRequest.builder()
          .tenantId(tenantId)
          .workflowDefinitionId(workflowDefinitionId)
          .bizDate(bizDate)
          .triggerType(TriggerType.API)
          .requestId(requestId)
          .traceId(requestId)
          .params(Map.of())
          .build();
      RlsTenantContextHolder.runWithTenant(
          tenantId,
          () -> application.getBean(WorkflowRunLaunchService.class).launch(launchRequest));
      Long workflowRunId =
          jdbc.queryForObject(selectIdSql, Long.class, tenantId, workflowDefinitionId, bizDate);
      insertCutoffBatchDay(jdbc, bizDate);
      jdbc.update(insertFileRecordSql, tenantId, fileName, "s3://test/" + fileName, bizDate);
      jdbc.update(updateWorkflowNodeRunSql, workflowRunId);

      if ("SETTLEMENT_FIRST".equals(firstAction)) {
        application.getBean(BatchDaySettleScheduler.class).settle();
        assertThat(batchDayStatus(jdbc, bizDate)).isEqualTo("IN_FLIGHT");
        assertThat(workflowRunStatus(jdbc, workflowRunId)).isEqualTo("RUNNING");
        RlsTenantContextHolder.runWithTenant(
            tenantId, () -> application.getBean(SensorPollScheduler.class).scan());
      } else {
        RlsTenantContextHolder.runWithTenant(
            tenantId, () -> application.getBean(SensorPollScheduler.class).scan());
      }

      String selectedNodeStatus =
          jdbc.queryForObject(selectNodeStatusSql, String.class, workflowRunId);
      assertThat(selectedNodeStatus).isEqualTo("SUCCESS");
      assertThat(workflowRunStatus(jdbc, workflowRunId)).isEqualTo("SUCCESS");
      application.getBean(BatchDaySettleScheduler.class).settle();
      Long selectedOutboxEventCount =
          jdbc.queryForObject(selectCountSql, Long.class, tenantId, workflowRunId);
      assertThat(selectedOutboxEventCount).isEqualTo(1L);
      assertThat(batchDayStatus(jdbc, bizDate)).isEqualTo("SETTLED");
    }
  }

  @ParameterizedTest(name = "{0}时 stuck Workflow 终态恢复与结算正确收敛")
  @ValueSource(strings = {"SETTLEMENT_FIRST", "RECONCILE_FIRST"})
  @DisplayName("stuck reconciler 终结已完成节点的 Workflow 与空日结算两种顺序均正确")
  void stuckWorkflowFinalizationRacingSettlement_shouldSettleAfterPersistedNodesComplete(
      String firstAction) {
    final String selectIdSql = """
            select id from batch.workflow_run where tenant_id = ? and workflow_definition_id = ?\s\
            and biz_date = ?\
            """;
    final String updateWorkflowNodeRunSql = """
          update batch.workflow_node_run
          set node_status = 'SUCCESS', finished_at = current_timestamp
          where workflow_run_id = ?
          """;
    final String updateWorkflowRunSql = """
        update batch.workflow_run set updated_at = current_timestamp - interval '100 years' where tenant_id = ?\s\
        and id = ?\
        """;
    final String selectNodeStatusSql = """
        select node_status from batch.workflow_node_run where workflow_run_id = ?
    """;
    final String selectCountSql = """
        select count(*) from batch.outbox_event where tenant_id = ? and aggregate_type = 'WORKFLOW_RUN'\s\
        and aggregate_id = ? and event_type = 'WORKFLOW_TERMINAL'\
        """;

    long suffix = System.nanoTime();
    String workflowCode = "WF_STUCK_R11_" + suffix;
    String requestId = "req-stuck-r11-" + suffix;
    LocalDate bizDate =
        LocalDate.now(ZoneOffset.UTC).plusDays(1_000 + Math.floorMod(suffix, 100_000));

    try (ConfigurableApplicationContext application = applicationContext(true)) {
      JdbcTemplate jdbc = application.getBean(JdbcTemplate.class);
      Long workflowDefinitionId = seedWorkflow(
          jdbc, workflowCode, requestId, "dedup-stuck-r11-" + suffix, requestId, bizDate);
      LaunchRequest launchRequest = LaunchRequest.builder()
          .tenantId(tenantId)
          .workflowDefinitionId(workflowDefinitionId)
          .bizDate(bizDate)
          .triggerType(TriggerType.API)
          .requestId(requestId)
          .traceId(requestId)
          .params(Map.of())
          .build();
      RlsTenantContextHolder.runWithTenant(
          tenantId,
          () -> application.getBean(WorkflowRunLaunchService.class).launch(launchRequest));
      Long workflowRunId =
          jdbc.queryForObject(selectIdSql, Long.class, tenantId, workflowDefinitionId, bizDate);
      insertCutoffBatchDay(jdbc, bizDate);
      // 模拟已持久化的崩溃窗口：所有 DAG 节点都已提交终态，
      // 但进程尚未来得及结束仍为 RUNNING 的根 WorkflowRun。
      jdbc.update(updateWorkflowNodeRunSql, workflowRunId);
      jdbc.update(updateWorkflowRunSql, tenantId, workflowRunId);
      assertThat(application
              .getBean(WorkflowRunMapper.class)
              .selectStuckRunningCandidates(Instant.now().minusSeconds(1_800), 100)
              .stream()
              .map(run -> run.getId())
              .toList())
          .contains(workflowRunId);
      List<String> selectedNodeStatusValues =
          jdbc.queryForList(selectNodeStatusSql, String.class, workflowRunId);
      assertThat(selectedNodeStatusValues)
          .isNotEmpty()
          .allMatch(status -> Set.of("SUCCESS", "FAILED", "SKIPPED").contains(status));

      if ("SETTLEMENT_FIRST".equals(firstAction)) {
        application.getBean(BatchDaySettleScheduler.class).settle();
        assertThat(batchDayStatus(jdbc, bizDate)).isEqualTo("IN_FLIGHT");
        assertThat(workflowRunStatus(jdbc, workflowRunId)).isEqualTo("RUNNING");
        reconcileStuckWorkflow(application);
      } else {
        reconcileStuckWorkflow(application);
        assertThat(workflowRunStatus(jdbc, workflowRunId)).isEqualTo("SUCCESS");
      }

      assertThat(workflowRunStatus(jdbc, workflowRunId)).isEqualTo("SUCCESS");
      application.getBean(BatchDaySettleScheduler.class).settle();
      Long selectedOutboxEventCount =
          jdbc.queryForObject(selectCountSql, Long.class, tenantId, workflowRunId);
      assertThat(selectedOutboxEventCount).isEqualTo(1L);
      assertThat(batchDayStatus(jdbc, bizDate)).isEqualTo("SETTLED");
    }
  }

  @Test
  @DisplayName("绕过 Console 写入的无效分支汇聚模式不能启动 Workflow")
  void shouldRejectInvalidBranchJoinModeBeforeLaunch() {
    long suffix = System.nanoTime();
    String requestId = "req-invalid-join-" + suffix;
    LocalDate bizDate = LocalDate.of(2026, 10, 13);
    String updateNode = """
        update batch.workflow_node set node_type = 'GATEWAY', node_params = '{"joinMode":"BAD"}'::jsonb
        where tenant_id = ? and workflow_definition_id = ? and node_code = 'WAIT_FILE'
        """;
    String countRuns =
        "select count(*) from batch.workflow_run where tenant_id = ? and workflow_definition_id = ?";
    try (ConfigurableApplicationContext application = applicationContext(true)) {
      JdbcTemplate jdbc = application.getBean(JdbcTemplate.class);
      Long definitionId = seedWorkflow(
          jdbc,
          "WF_INVALID_JOIN_" + suffix,
          requestId,
          "dedup-invalid-join-" + suffix,
          requestId,
          bizDate);
      jdbc.update(updateNode, tenantId, definitionId);
      LaunchRequest request = LaunchRequest.builder()
          .tenantId(tenantId)
          .workflowDefinitionId(definitionId)
          .bizDate(bizDate)
          .triggerType(TriggerType.API)
          .requestId(requestId)
          .traceId(requestId)
          .params(Map.of())
          .build();
      assertThatThrownBy(() -> RlsTenantContextHolder.runWithTenant(
              tenantId,
              () -> application.getBean(WorkflowRunLaunchService.class).launch(request)))
          .isInstanceOfSatisfying(
              BizException.class,
              error ->
                  assertThat(error.getMessageKey()).isEqualTo("error.workflow.definition_invalid"));
      assertThat(jdbc.queryForObject(countRuns, Long.class, tenantId, definitionId))
          .isZero();
    }
  }

  @Test
  @DisplayName("绕过 Console 写入的普通节点无效汇聚模式不能启动 Workflow")
  void shouldRejectInvalidTaskJoinModeBeforeLaunch() {
    long suffix = System.nanoTime();
    String requestId = "req-invalid-task-join-" + suffix;
    LocalDate bizDate = LocalDate.of(2026, 10, 13);
    String updateNode = """
        update batch.workflow_node set node_type = 'TASK', node_params = '{"joinMode":"BAD"}'::jsonb
        where tenant_id = ? and workflow_definition_id = ? and node_code = 'WAIT_FILE'
        """;
    String countRuns =
        "select count(*) from batch.workflow_run where tenant_id = ? and workflow_definition_id = ?";
    try (ConfigurableApplicationContext application = applicationContext(true)) {
      JdbcTemplate jdbc = application.getBean(JdbcTemplate.class);
      Long definitionId = seedWorkflow(
          jdbc,
          "WF_INVALID_TASK_JOIN_" + suffix,
          requestId,
          "dedup-invalid-task-join-" + suffix,
          requestId,
          bizDate);
      jdbc.update(updateNode, tenantId, definitionId);
      var validation = RlsTenantContextHolder.runWithTenant(
          tenantId, () -> application.getBean(WorkflowGraphValidator.class).validate(definitionId));
      assertThat(validation.errors())
          .anySatisfy(issue -> assertThat(issue.code()).isEqualTo("V9"));
      LaunchRequest request = LaunchRequest.builder()
          .tenantId(tenantId)
          .workflowDefinitionId(definitionId)
          .bizDate(bizDate)
          .triggerType(TriggerType.API)
          .requestId(requestId)
          .traceId(requestId)
          .params(Map.of())
          .build();
      assertThatThrownBy(() -> RlsTenantContextHolder.runWithTenant(
              tenantId,
              () -> application.getBean(WorkflowRunLaunchService.class).launch(request)))
          .isInstanceOfSatisfying(
              BizException.class,
              error ->
                  assertThat(error.getMessageKey()).isEqualTo("error.workflow.definition_invalid"));
      assertThat(jdbc.queryForObject(countRuns, Long.class, tenantId, definitionId))
          .isZero();
    }
  }

  @Test
  @DisplayName("下一次文件探测跨过截止时间时按时选中并持久化超时")
  void shouldWakeAtDeadline_whenNextFileProbeWouldBeLater() {
    long suffix = System.nanoTime();
    String requestId = "req-deadline-" + suffix;
    LocalDate bizDate = LocalDate.of(2026, 10, 13);
    try (ConfigurableApplicationContext application = applicationContext(true)) {
      JdbcTemplate jdbc = application.getBean(JdbcTemplate.class);
      Long definitionId = seedWorkflow(
          jdbc, "WF_DEADLINE_" + suffix, requestId, "dedup-deadline-" + suffix, requestId, bizDate);
      String updateParamsSql = """
          update batch.workflow_node set node_params = ?::jsonb
          where tenant_id = ? and workflow_definition_id = ? and node_code = 'WAIT_FILE'
          """;
      String sensorParams = """
          {"sensor_type":"FILE_ARRIVAL","sensor_spec":{"pattern":"missing-deadline-*.csv","maxAgeSeconds":3600},
           "timeout_seconds":120,"poll_interval_seconds":30,"on_timeout":"FAIL"}
          """;
      jdbc.update(updateParamsSql, sensorParams, tenantId, definitionId);
      LaunchRequest request = LaunchRequest.builder()
          .tenantId(tenantId)
          .workflowDefinitionId(definitionId)
          .bizDate(bizDate)
          .triggerType(TriggerType.API)
          .requestId(requestId)
          .traceId(requestId)
          .params(Map.of())
          .build();
      RlsTenantContextHolder.runWithTenant(
          tenantId, () -> application.getBean(WorkflowRunLaunchService.class).launch(request));
      String selectRunSql = """
          select id from batch.workflow_run where tenant_id = ? and trigger_request_id =
            (select id from batch.trigger_request where tenant_id = ? and request_id = ?)
          """;
      Long runId = jdbc.queryForObject(selectRunSql, Long.class, tenantId, tenantId, requestId);
      String setStartSql = """
          update batch.workflow_node_run
          set started_at = current_timestamp - interval '100 seconds',
              sensor_next_probe_at = current_timestamp - interval '1 second'
          where workflow_run_id = ? and node_code = 'WAIT_FILE'
          """;
      jdbc.update(setStartSql, runId);
      RlsTenantContextHolder.runWithTenant(
          tenantId, () -> application.getBean(SensorPollScheduler.class).scan());
      String atDeadlineSql = """
          select sensor_next_probe_at = started_at + interval '120 seconds'
          from batch.workflow_node_run where workflow_run_id = ? and node_code = 'WAIT_FILE'
          """;
      assertThat(jdbc.queryForObject(atDeadlineSql, Boolean.class, runId)).isTrue();
      String dueSql = """
          select sensor_next_probe_at <= current_timestamp
          from batch.workflow_node_run where workflow_run_id = ? and node_code = 'WAIT_FILE'
          """;
      Awaitility.await()
          .atMost(Duration.ofSeconds(90))
          .pollInterval(Duration.ofMillis(200))
          .until(() -> Boolean.TRUE.equals(jdbc.queryForObject(dueSql, Boolean.class, runId)));
      RlsTenantContextHolder.runWithTenant(
          tenantId, () -> application.getBean(SensorPollScheduler.class).scan());

      WorkflowNodeRunEntity wait = RlsTenantContextHolder.runWithTenant(
          tenantId,
          () -> application
              .getBean(WorkflowNodeRunMapper.class)
              .selectLatestByWorkflowRunIdAndNodeCode(runId, "WAIT_FILE"));
      assertThat(wait.getNodeStatus()).isEqualTo("FAILED");
      assertThat(wait.getErrorKey()).isEqualTo("error.workflow.sensor_timeout");
      assertThat(wait.getSensorNextProbeAt()).isNull();
    }
  }

  @Test
  @DisplayName("超范围 WAIT 下次探测转为配置错误并持久化")
  void shouldPersistFailure_whenNextSensorProbeExceedsTimestampRange() {
    long suffix = System.nanoTime();
    String requestId = "req-probe-range-" + suffix;
    LocalDate bizDate = LocalDate.of(2026, 10, 13);
    try (ConfigurableApplicationContext application = applicationContext(true)) {
      JdbcTemplate jdbc = application.getBean(JdbcTemplate.class);
      String checkBoundarySql = "select ?::timestamptz = timestamptz '294275-12-31 23:59:59+00'";
      Instant lastAcceptedSecond = Instant.parse("+294276-01-01T00:00:00Z").minusSeconds(1);
      assertThat(jdbc.queryForObject(
              checkBoundarySql, Boolean.class, Timestamp.from(lastAcceptedSecond)))
          .isTrue();
      Boolean storedBoundaryMatches = jdbc.execute((ConnectionCallback<Boolean>) connection -> {
        String createProbeTableSql =
            "create temporary table probe_time_boundary (probe_at timestamptz)";
        String insertProbeTimeSql = "insert into probe_time_boundary (probe_at) values (?)";
        String compareProbeTimeSql =
            "select probe_at = timestamptz '294275-12-31 23:59:59+00' from probe_time_boundary";
        try (var statement = connection.createStatement()) {
          statement.execute(createProbeTableSql);
        }
        try (var statement = connection.prepareStatement(insertProbeTimeSql)) {
          statement.setTimestamp(1, Timestamp.from(lastAcceptedSecond));
          statement.executeUpdate();
        }
        try (var statement = connection.createStatement();
            var result = statement.executeQuery(compareProbeTimeSql)) {
          return result.next() && result.getBoolean(1);
        }
      });
      assertThat(storedBoundaryMatches).isTrue();
      Long definitionId = seedWorkflow(
          jdbc, "WF_RANGE_" + suffix, requestId, "dedup-range-" + suffix, requestId, bizDate);
      String updateParamsSql = """
          update batch.workflow_node set node_params = ?::jsonb
          where tenant_id = ? and workflow_definition_id = ? and node_code = 'WAIT_FILE'
          """;
      String sensorParams = """
          {"sensor_type":"FILE_ARRIVAL","sensor_spec":{"pattern":"missing-range-*.csv","maxAgeSeconds":3600},
           "timeout_seconds":10000000000001,"poll_interval_seconds":10000000000000,
           "on_timeout":"FAIL"}
          """;
      jdbc.update(updateParamsSql, sensorParams, tenantId, definitionId);
      LaunchRequest request = LaunchRequest.builder()
          .tenantId(tenantId)
          .workflowDefinitionId(definitionId)
          .bizDate(bizDate)
          .triggerType(TriggerType.API)
          .requestId(requestId)
          .traceId(requestId)
          .params(Map.of())
          .build();
      RlsTenantContextHolder.runWithTenant(
          tenantId, () -> application.getBean(WorkflowRunLaunchService.class).launch(request));
      String selectRunSql = """
          select id from batch.workflow_run where tenant_id = ? and trigger_request_id =
            (select id from batch.trigger_request where tenant_id = ? and request_id = ?)
          """;
      Long runId = jdbc.queryForObject(selectRunSql, Long.class, tenantId, tenantId, requestId);
      String forceProbeSql = """
          update batch.workflow_node_run set sensor_next_probe_at = current_timestamp - interval '1 second'
          where workflow_run_id = ? and node_code = 'WAIT_FILE'
          """;
      jdbc.update(forceProbeSql, runId);
      RlsTenantContextHolder.runWithTenant(
          tenantId, () -> application.getBean(SensorPollScheduler.class).scan());

      WorkflowNodeRunEntity wait = RlsTenantContextHolder.runWithTenant(
          tenantId,
          () -> application
              .getBean(WorkflowNodeRunMapper.class)
              .selectLatestByWorkflowRunIdAndNodeCode(runId, "WAIT_FILE"));
      assertThat(wait.getNodeStatus()).isEqualTo("FAILED");
      assertThat(wait.getErrorKey()).isEqualTo("error.workflow.sensor_spec_invalid");
      assertThat(wait.getSensorNextProbeAt()).isNull();
    }
  }

  @Test
  @DisplayName("文件名特殊字符按字面匹配,错误文件不能释放 WAIT")
  void shouldReleaseFileWaitOnlyForLiteralPatternMatch() {
    long suffix = System.nanoTime();
    String requestId = "req-literal-file-" + suffix;
    LocalDate bizDate = LocalDate.of(2026, 10, 13);
    String decoy = "invoiceX100wrong.csv";
    String matching = "invoice_100%ok.csv";
    Long runId;
    String insert = """
        insert into batch.file_record (tenant_id,file_category,file_name,file_format_type,storage_type,
          storage_path,source_type,file_status,biz_date)
        values (?, 'INPUT', ?, 'DELIMITED', 'S3', ?, 'SFTP', 'RECEIVED', ?)
        """;
    String forceProbe = """
        update batch.workflow_node_run set sensor_next_probe_at = current_timestamp - interval '1 second'
        where workflow_run_id = ? and node_code = 'WAIT_FILE'
        """;
    String state = """
        select node_status from batch.workflow_node_run where workflow_run_id = ? and node_code = ?
        """;
    String terminalCount = """
        select count(*) from batch.outbox_event where tenant_id = ? and aggregate_type = 'WORKFLOW_RUN'
        and aggregate_id = ? and event_type = 'WORKFLOW_TERMINAL'
        """;
    try (ConfigurableApplicationContext application = applicationContext(true)) {
      JdbcTemplate jdbc = application.getBean(JdbcTemplate.class);
      Long definitionId = seedWorkflow(
          jdbc, "WF_LITERAL_" + suffix, requestId, "dedup-literal-" + suffix, requestId, bizDate);
      String updateNodeParamsSql = """
          update batch.workflow_node set node_params = ?::jsonb
          where tenant_id = ? and workflow_definition_id = ? and node_code = 'WAIT_FILE'
          """;
      jdbc.update(updateNodeParamsSql, """
          {"sensor_type":"FILE_ARRIVAL",
           "sensor_spec":{"pattern":"invoice_100%*.csv","maxAgeSeconds":3600},
           "timeout_seconds":3600,"poll_interval_seconds":1,"on_timeout":"FAIL"}
          """, tenantId, definitionId);
      LaunchRequest request = LaunchRequest.builder()
          .tenantId(tenantId)
          .workflowDefinitionId(definitionId)
          .bizDate(bizDate)
          .triggerType(TriggerType.API)
          .requestId(requestId)
          .traceId(requestId)
          .params(Map.of())
          .build();
      RlsTenantContextHolder.runWithTenant(
          tenantId, () -> application.getBean(WorkflowRunLaunchService.class).launch(request));
      String selectRunSql = """
          select id from batch.workflow_run where tenant_id = ? and trigger_request_id =
            (select id from batch.trigger_request where tenant_id = ? and request_id = ?)
          """;
      runId = jdbc.queryForObject(selectRunSql, Long.class, tenantId, tenantId, requestId);
      jdbc.update(insert, tenantId, decoy, "s3://test/" + decoy, bizDate);
      jdbc.update(forceProbe, runId);
      RlsTenantContextHolder.runWithTenant(
          tenantId, () -> application.getBean(SensorPollScheduler.class).scan());
      assertThat(jdbc.queryForObject(state, String.class, runId, "WAIT_FILE")).isEqualTo("RUNNING");
      String countEndSql =
          "select count(*) from batch.workflow_node_run where workflow_run_id = ? and node_code = 'END'";
      assertThat(jdbc.queryForObject(countEndSql, Long.class, runId)).isZero();
      assertThat(jdbc.queryForObject(terminalCount, Long.class, tenantId, runId))
          .isZero();
    }
    try (ConfigurableApplicationContext application = applicationContext(true)) {
      JdbcTemplate jdbc = application.getBean(JdbcTemplate.class);
      jdbc.update(insert, tenantId, matching, "s3://test/" + matching, bizDate);
      jdbc.update(forceProbe, runId);
      RlsTenantContextHolder.runWithTenant(
          tenantId, () -> application.getBean(SensorPollScheduler.class).scan());
      WorkflowNodeRunEntity wait = RlsTenantContextHolder.runWithTenant(
          tenantId,
          () -> application
              .getBean(WorkflowNodeRunMapper.class)
              .selectLatestByWorkflowRunIdAndNodeCode(runId, "WAIT_FILE"));
      assertThat(wait.getNodeStatus()).isEqualTo("SUCCESS");
      assertThat(wait.getOutput()).contains(matching).doesNotContain(decoy);
      assertThat(jdbc.queryForObject(state, String.class, runId, "END")).isEqualTo("SUCCESS");
      String selectRunStatusSql =
          "select run_status from batch.workflow_run where tenant_id = ? and id = ?";
      assertThat(jdbc.queryForObject(selectRunStatusSql, String.class, tenantId, runId))
          .isEqualTo("SUCCESS");
      assertThat(jdbc.queryForObject(terminalCount, Long.class, tenantId, runId))
          .isEqualTo(1L);
    }
  }

  @Test
  @DisplayName("新上下文从 PostgreSQL 恢复 WAIT 并在文件到达后推进到 END")
  void shouldRecoverPersistedFileWaitAfterApplicationContextRestart() throws Exception {
    final String selectIdSql = """
        select id from batch.workflow_run where tenant_id = ? and trigger_request_id = (select id\s\
        from batch.trigger_request where tenant_id = ? and request_id = ?)\
        """;
    final String updateWorkflowNodeRunSql = """
        update batch.workflow_node_run set sensor_next_probe_at = current_timestamp - interval '1 second'\s\
        where id = ?\
        """;
    final String selectCountSql = """
        select count(*) from batch.job_task where tenant_id = ?\s\
        and job_instance_id in (select related_job_instance_id from batch.workflow_run where id = ?)\
        """;
    final String insertFileRecordSql = """
          insert into batch.file_record (
              tenant_id, file_category, file_name, file_format_type, storage_type, storage_path,
              source_type, file_status, biz_date, metadata_json, created_at
          ) values (?, 'INPUT', ?, 'DELIMITED', 'S3', ?, 'SFTP', 'RECEIVED', ?,
              jsonb_build_object('fileGroupCode', 'CONTEXT_RESTART', 'arrivalState', 'TRIGGERED'), now())
          """;
    final String updateWorkflowNodeRunSql2 = """
        update batch.workflow_node_run set sensor_next_probe_at = current_timestamp - interval '1 second'\s\
        where workflow_run_id = ? and node_code = 'WAIT_FILE'\
        """;
    final String selectRunStatusSql = """
        select run_status from batch.workflow_run where tenant_id = ? and id = ?
    """;
    final String selectRelatedJobInstanceIdSql = """
        select related_job_instance_id from batch.workflow_run where tenant_id = ? and id = ?
    """;
    final String selectCountSql2 = """
        select count(*) from batch.outbox_event where tenant_id = ? and aggregate_type = 'WORKFLOW_RUN'\s\
        and aggregate_id = ? and event_type = 'WORKFLOW_TERMINAL'\
        """;

    long suffix = System.nanoTime();
    String jobCode = "WF_CONTEXT_RESTART_" + suffix;
    String requestId = "req-context-restart-" + suffix;
    String dedupKey = "dedup-context-restart-" + suffix;
    String fileName = "context-restart-" + suffix + ".csv";
    String traceId = "trace-context-restart-" + suffix;
    LocalDate bizDate = LocalDate.of(2026, 10, 13);
    Long workflowRunId;
    Long workflowDefinitionId;

    try (ConfigurableApplicationContext first = applicationContext(false)) {
      JdbcTemplate jdbc = first.getBean(JdbcTemplate.class);
      workflowDefinitionId = seedWorkflow(jdbc, jobCode, requestId, dedupKey, traceId, bizDate);
      WorkflowRunLaunchService launchService = first.getBean(WorkflowRunLaunchService.class);
      LaunchRequest launchRequest = LaunchRequest.builder()
          .tenantId(tenantId)
          .workflowDefinitionId(workflowDefinitionId)
          .bizDate(bizDate)
          .triggerType(TriggerType.API)
          .requestId(requestId)
          .traceId(traceId)
          .params(Map.of())
          .build();
      RlsTenantContextHolder.runWithTenant(tenantId, () -> launchService.launch(launchRequest));

      workflowRunId = jdbc.queryForObject(selectIdSql, Long.class, tenantId, tenantId, requestId);
      insertCutoffBatchDay(jdbc, bizDate);
      first.getBean(BatchDaySettleScheduler.class).settle();
      assertThat(batchDayStatus(jdbc, bizDate)).isEqualTo("IN_FLIGHT");
      WorkflowNodeRunEntity waitRun = RlsTenantContextHolder.runWithTenant(
          tenantId,
          () -> first
              .getBean(WorkflowNodeRunMapper.class)
              .selectLatestByWorkflowRunIdAndNodeCode(workflowRunId, "WAIT_FILE"));
      assertThat(waitRun).isNotNull();
      assertThat(waitRun.getNodeStatus()).isEqualTo("RUNNING");
      jdbc.update(updateWorkflowNodeRunSql, waitRun.getId());
      Long selectedJobTaskCount =
          jdbc.queryForObject(selectCountSql, Long.class, tenantId, workflowRunId);
      assertThat(selectedJobTaskCount).isZero();
    }

    // 首个 Spring 上下文、调度器、事务管理器、连接池和应用 Bean
    // 均已关闭。第二个上下文继续使用同一外部 PostgreSQL 状态。
    try (ConfigurableApplicationContext restarted = applicationContext(true)) {
      JdbcTemplate jdbc = restarted.getBean(JdbcTemplate.class);
      jdbc.update(insertFileRecordSql, tenantId, fileName, "s3://test/" + fileName, bizDate);
      jdbc.update(updateWorkflowNodeRunSql2, workflowRunId);

      ExecutorService executor = Executors.newFixedThreadPool(2);
      CountDownLatch start = new CountDownLatch(1);
      try {
        var sensorScan = executor.submit(() -> {
          start.await();
          RlsTenantContextHolder.runWithTenant(
              tenantId, () -> restarted.getBean(SensorPollScheduler.class).scan());
          return null;
        });
        var settlementScan = executor.submit(() -> {
          start.await();
          restarted.getBean(BatchDaySettleScheduler.class).settle();
          return null;
        });
        start.countDown();
        sensorScan.get(30, TimeUnit.SECONDS);
        settlementScan.get(30, TimeUnit.SECONDS);
      } finally {
        executor.shutdownNow();
      }

      WorkflowNodeRunMapper nodeRunMapper = restarted.getBean(WorkflowNodeRunMapper.class);
      RlsTenantContextHolder.runWithTenant(tenantId, () -> {
        WorkflowNodeRunEntity waitRun =
            nodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(workflowRunId, "WAIT_FILE");
        WorkflowNodeRunEntity endRun =
            nodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(workflowRunId, "END");
        assertThat(waitRun.getNodeStatus()).isEqualTo("SUCCESS");
        assertThat(waitRun.getOutput()).contains(fileName);
        assertThat(endRun.getNodeStatus()).isEqualTo("SUCCESS");
      });
      String selectedRunStatus =
          jdbc.queryForObject(selectRunStatusSql, String.class, tenantId, workflowRunId);
      assertThat(selectedRunStatus).isEqualTo("SUCCESS");
      Long selectedRelatedJobInstanceId =
          jdbc.queryForObject(selectRelatedJobInstanceIdSql, Long.class, tenantId, workflowRunId);
      assertThat(selectedRelatedJobInstanceId).isNull();
      Long selectedOutboxEventCount =
          jdbc.queryForObject(selectCountSql2, Long.class, tenantId, workflowRunId);
      assertThat(selectedOutboxEventCount).isEqualTo(1L);
      restarted.getBean(BatchDaySettleScheduler.class).settle();
      assertThat(batchDayStatus(jdbc, bizDate)).isEqualTo("SETTLED");
    }
  }

  @Test
  @DisplayName("父 Workflow 终止后命中的 WAIT 不得派发新的 JOB")
  void terminatedWorkflowWaitHit_shouldNotDispatchChildJob() {
    final String insertJobDefinitionSql = """
          insert into batch.job_definition (
              tenant_id, job_code, job_name, job_type, biz_type, schedule_type, timezone,
              calendar_code, priority, queue_code, worker_group, trigger_mode, dag_enabled,
              shard_strategy, retry_policy, retry_max_count, timeout_seconds, enabled, version
          ) values (?, ?, ?, 'GENERAL', 'IT', 'MANUAL', 'UTC', 'JOB_CALENDAR',
              5, 'q-it', ?, 'API', false, 'NONE', 'NONE', 0, 0, true, 1)
          """;
    final String updateWorkflowNodeSql = """
          update batch.workflow_node
             set node_code = 'JOB_AFTER_WAIT', node_name = 'child job', node_type = 'JOB',
                 node_order = 2, related_job_code = ?
           where tenant_id = ? and workflow_definition_id = ? and node_code = 'END'
          """;
    final String insertWorkflowNodeSql = """
          insert into batch.workflow_node (
              tenant_id, workflow_definition_id, node_code, node_name, node_type, node_order,
              retry_policy, retry_max_count, timeout_seconds, enabled
          ) values (?, ?, 'END', 'end', 'END', 3, 'NONE', 0, 0, true)
          """;
    final String updateWorkflowEdgeSql = """
          update batch.workflow_edge set to_node_code = 'JOB_AFTER_WAIT'
           where tenant_id = ? and workflow_definition_id = ?
             and from_node_code = 'WAIT_FILE' and to_node_code = 'END'
          """;
    final String insertWorkflowEdgeSql = """
          insert into batch.workflow_edge (
              tenant_id, workflow_definition_id, from_node_code, to_node_code, edge_type, enabled
          ) values (?, ?, 'JOB_AFTER_WAIT', 'END', 'SUCCESS', true)
          """;
    final String updateWorkflowNodeSql2 = """
          update batch.workflow_node set node_params = ?::jsonb
           where tenant_id = ? and workflow_definition_id = ? and node_code = 'WAIT_FILE'
          """;
    final String selectIdSql = """
        select id from batch.workflow_run where tenant_id = ? and trigger_request_id = (select id\s\
        from batch.trigger_request where tenant_id = ? and request_id = ?)\
        """;
    final String insertFileRecordSql = """
          insert into batch.file_record (
              tenant_id, file_category, file_name, file_format_type, storage_type, storage_path,
              source_type, file_status, biz_date, metadata_json, created_at
          ) values (?, 'INPUT', ?, 'DELIMITED', 'S3', ?, 'SFTP', 'RECEIVED', ?,
              jsonb_build_object('fileGroupCode', 'CONTEXT_RESTART',
                  'arrivalState', 'TRIGGERED'), now())
          """;
    final String updateWorkflowNodeRunSql = """
          update batch.workflow_node_run set sensor_next_probe_at = current_timestamp - interval '1 second'
           where workflow_run_id = ? and node_code = 'WAIT_FILE'
          """;
    final String selectRunStatusSql = """
        select run_status from batch.workflow_run where tenant_id = ? and id = ?
    """;
    final String selectCountSql = """
        select count(*) from batch.trigger_request where tenant_id = ? and parent_workflow_run_id = ?\s\
        and trigger_type = 'EVENT'\
        """;
    final String selectNodeStatusSql = """
        select node_status from batch.workflow_node_run where workflow_run_id = ? and node_code = 'WAIT_FILE'\
        """;
    final String selectSkipReasonCodeSql = """
        select skip_reason_code from batch.workflow_node_run where workflow_run_id = ?\s\
        and node_code = 'WAIT_FILE'\
        """;
    final String selectSensorNextProbeAtSql = """
        select sensor_next_probe_at from batch.workflow_node_run where workflow_run_id = ?\s\
        and node_code = 'WAIT_FILE'\
        """;

    long suffix = System.nanoTime();
    String workflowCode = "WF_TERMINATED_WAIT_R11_" + suffix;
    String requestId = "req-terminated-wait-r11-" + suffix;
    String rootJobCode = "ROOT_TERMINATED_WAIT_R11_" + suffix;
    String childJobCode = "CHILD_TERMINATED_WAIT_R11_" + suffix;
    String childWorkerGroup = "WG_TERMINATED_WAIT_R11_" + suffix;
    String fileName = "terminated-wait-r11-" + suffix + ".csv";
    LocalDate bizDate = LocalDate.of(2026, 11, 2);

    try (ConfigurableApplicationContext application = applicationContext(true)) {
      JdbcTemplate jdbc = application.getBean(JdbcTemplate.class);
      Long workflowDefinitionId = seedWorkflow(
          jdbc, rootJobCode, requestId, "dedup-terminated-wait-" + suffix, requestId, bizDate);
      jdbc.update(insertJobDefinitionSql, tenantId, childJobCode, childJobCode, childWorkerGroup);
      jdbc.update(updateWorkflowNodeSql, childJobCode, tenantId, workflowDefinitionId);
      jdbc.update(insertWorkflowNodeSql, tenantId, workflowDefinitionId);
      jdbc.update(updateWorkflowEdgeSql, tenantId, workflowDefinitionId);
      jdbc.update(insertWorkflowEdgeSql, tenantId, workflowDefinitionId);
      jdbc.update(updateWorkflowNodeSql2, """
          {"sensor_type":"FILE_ARRIVAL",
           "sensor_spec":{"pattern":"terminated-wait-r11-*.csv","maxAgeSeconds":3600,
             "bindWorkflowBizDate":true,"fileGroupCode":"CONTEXT_RESTART",
             "requireCompleteGroup":true},
           "timeout_seconds":3600,"poll_interval_seconds":1,"on_timeout":"FAIL"}
          """, tenantId, workflowDefinitionId);

      LaunchRequest launchRequest = LaunchRequest.builder()
          .tenantId(tenantId)
          .workflowDefinitionId(workflowDefinitionId)
          .bizDate(bizDate)
          .triggerType(TriggerType.API)
          .requestId(requestId)
          .traceId(requestId)
          .params(Map.of())
          .build();
      RlsTenantContextHolder.runWithTenant(
          tenantId,
          () -> application.getBean(WorkflowRunLaunchService.class).launch(launchRequest));
      Long workflowRunId =
          jdbc.queryForObject(selectIdSql, Long.class, tenantId, tenantId, requestId);
      insertCutoffBatchDay(jdbc, bizDate);
      application.getBean(BatchDaySettleScheduler.class).settle();
      assertThat(batchDayStatus(jdbc, bizDate)).isEqualTo("IN_FLIGHT");

      RlsTenantContextHolder.runWithTenant(
          tenantId,
          () -> application
              .getBean(WorkflowRunManagementApplicationService.class)
              .terminate(tenantId, workflowRunId));
      jdbc.update(insertFileRecordSql, tenantId, fileName, "s3://test/" + fileName, bizDate);
      jdbc.update(updateWorkflowNodeRunSql, workflowRunId);

      RlsTenantContextHolder.runWithTenant(
          tenantId, () -> application.getBean(SensorPollScheduler.class).scan());

      String selectedRunStatus =
          jdbc.queryForObject(selectRunStatusSql, String.class, tenantId, workflowRunId);
      assertThat(selectedRunStatus).isEqualTo("TERMINATED");
      Integer selectedTriggerRequestCount =
          jdbc.queryForObject(selectCountSql, Integer.class, tenantId, workflowRunId);
      assertThat(selectedTriggerRequestCount).isZero();
      String selectedNodeStatus =
          jdbc.queryForObject(selectNodeStatusSql, String.class, workflowRunId);
      assertThat(selectedNodeStatus).isEqualTo("SKIPPED");
      String selectedSkipReasonCode =
          jdbc.queryForObject(selectSkipReasonCodeSql, String.class, workflowRunId);
      assertThat(selectedSkipReasonCode).isEqualTo("PARENT_WORKFLOW_TERMINAL");
      Timestamp selectedSensorNextProbeAt =
          jdbc.queryForObject(selectSensorNextProbeAtSql, Timestamp.class, workflowRunId);
      assertThat(selectedSensorNextProbeAt).isNull();
      assertThat(batchDayStatus(jdbc, bizDate)).isEqualTo("IN_FLIGHT");
    }
  }

  @Test
  @DisplayName("WAIT-only 流程跨上下文重建后超时、人工 skip 并结算失败业务日")
  void shouldTimeoutAndManuallySkipPersistedWaitAfterApplicationContextRestart() {
    final String selectIdSql = """
        select id from batch.workflow_run where tenant_id = ? and trigger_request_id = (select id\s\
        from batch.trigger_request where tenant_id = ? and request_id = ?)\
        """;
    final String updateWorkflowNodeRunSql = """
        update batch.workflow_node_run set started_at = current_timestamp - interval '2 hours',\s\
        sensor_next_probe_at = current_timestamp - interval '1 second' where workflow_run_id = ?\s\
        and node_code = 'WAIT_FILE'\
        """;
    final String selectCountSql = """
        select count(*) from batch.workflow_node_run where workflow_run_id = ? and node_type = 'WAIT'\s\
        and node_status = 'RUNNING'\
        """;
    final String selectCountSql2 = """
        select count(*) from batch.job_instance where tenant_id = ? and job_code = ?
    """;
    final String selectRunStatusSql = """
        select run_status from batch.workflow_run where tenant_id = ? and id = ?
    """;
    final String selectCountSql3 = """
        select count(*) from batch.outbox_event where tenant_id = ? and aggregate_type = 'WORKFLOW_RUN'\s\
        and aggregate_id = ? and event_type = 'WORKFLOW_TERMINAL'\
        """;

    long suffix = System.nanoTime();
    String jobCode = "WF_CONTEXT_TIMEOUT_" + suffix;
    String requestId = "req-context-timeout-" + suffix;
    String dedupKey = "dedup-context-timeout-" + suffix;
    String traceId = "trace-context-timeout-" + suffix;
    LocalDate bizDate = LocalDate.of(2026, 10, 14);
    Long workflowRunId;

    try (ConfigurableApplicationContext first = applicationContext(false)) {
      JdbcTemplate jdbc = first.getBean(JdbcTemplate.class);
      Long workflowDefinitionId =
          seedWorkflow(jdbc, jobCode, requestId, dedupKey, traceId, bizDate);
      LaunchRequest launchRequest = LaunchRequest.builder()
          .tenantId(tenantId)
          .workflowDefinitionId(workflowDefinitionId)
          .bizDate(bizDate)
          .triggerType(TriggerType.API)
          .requestId(requestId)
          .traceId(traceId)
          .params(Map.of())
          .build();
      RlsTenantContextHolder.runWithTenant(
          tenantId, () -> first.getBean(WorkflowRunLaunchService.class).launch(launchRequest));
      workflowRunId = jdbc.queryForObject(selectIdSql, Long.class, tenantId, tenantId, requestId);
      insertCutoffBatchDay(jdbc, bizDate);
      jdbc.update(updateWorkflowNodeRunSql, workflowRunId);
      Integer selectedWorkflowNodeRunCount =
          jdbc.queryForObject(selectCountSql, Integer.class, workflowRunId);
      assertThat(selectedWorkflowNodeRunCount).isEqualTo(1);
      Integer selectedJobInstanceCount =
          jdbc.queryForObject(selectCountSql2, Integer.class, tenantId, jobCode);
      assertThat(selectedJobInstanceCount).isZero();
    }

    try (ConfigurableApplicationContext restarted = applicationContext(true)) {
      JdbcTemplate jdbc = restarted.getBean(JdbcTemplate.class);
      RlsTenantContextHolder.runWithTenant(
          tenantId, () -> restarted.getBean(SensorPollScheduler.class).scan());

      WorkflowNodeRunMapper nodeRunMapper = restarted.getBean(WorkflowNodeRunMapper.class);
      WorkflowNodeRunEntity timedOut = RlsTenantContextHolder.runWithTenant(
          tenantId,
          () -> nodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(workflowRunId, "WAIT_FILE"));
      assertThat(timedOut.getNodeStatus()).isEqualTo("FAILED");
      assertThat(timedOut.getErrorCode()).isEqualTo("SENSOR_FAIL");
      String selectedRunStatus =
          jdbc.queryForObject(selectRunStatusSql, String.class, tenantId, workflowRunId);
      assertThat(selectedRunStatus).isEqualTo("FAILED");

      RlsTenantContextHolder.runWithTenant(
          tenantId,
          () -> restarted
              .getBean(WorkflowRunManagementApplicationService.class)
              .skipNode(tenantId, workflowRunId, "WAIT_FILE", "operator-r08", "approved timeout"));
      WorkflowNodeRunEntity skipped = RlsTenantContextHolder.runWithTenant(
          tenantId,
          () -> nodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(workflowRunId, "WAIT_FILE"));
      assertThat(skipped.getNodeStatus()).isEqualTo("SKIPPED");
      assertThat(skipped.getSkipReasonCode()).isEqualTo("OPERATOR_SKIPPED");
      assertThat(skipped.getSkipReason()).isEqualTo("approved timeout");
      String selectedRunStatusRead2 =
          jdbc.queryForObject(selectRunStatusSql, String.class, tenantId, workflowRunId);
      assertThat(selectedRunStatusRead2).isEqualTo("FAILED");

      restarted.getBean(BatchDaySettleScheduler.class).settle();
      assertThat(batchDayStatus(jdbc, bizDate)).isEqualTo("FAILED");
      Long selectedOutboxEventCount =
          jdbc.queryForObject(selectCountSql3, Long.class, tenantId, workflowRunId);
      assertThat(selectedOutboxEventCount).isEqualTo(1L);
      Integer selectedJobInstanceCountRead2 =
          jdbc.queryForObject(selectCountSql2, Integer.class, tenantId, jobCode);
      assertThat(selectedJobInstanceCountRead2).isZero();
    }
  }

  @Test
  @DisplayName("文件束请求持久化后 Orchestrator 重启仍只启动一个 Workflow")
  void shouldResumePersistedBundleRequestAfterApplicationContextRestart() {
    final String createFunctionSql = """
        create function batch.fail_workflow_run_insert_for_restart_test() returns trigger language plpgsql as $$\s\
        begin raise exception 'simulated process interruption after durable bundle request'; end $$\
        """;
    final String createTriggerSql = """
        create trigger fail_workflow_run_insert_for_restart_test before insert on batch.workflow_run for each\s\
        row execute function batch.fail_workflow_run_insert_for_restart_test()\
        """;
    final String selectRequestStatusSql = """
        select request_status from batch.trigger_request where tenant_id = ? and request_id = ?
    """;
    final String selectCountSql = """
        select count(*) from batch.workflow_run where tenant_id = ? and trigger_request_id = (select id\s\
        from batch.trigger_request where tenant_id = ? and request_id = ?)\
        """;
    final String selectCoalesceSql = """
        select coalesce(metadata_json->>'arrivalState', 'WAITING_ARRIVAL') from batch.file_record\s\
        where tenant_id = ? and file_name = ?\
        """;
    final String dropTriggerSql = """
        drop trigger if exists fail_workflow_run_insert_for_restart_test on batch.workflow_run\
        """;
    final String dropFunctionSql = """
        drop function if exists batch.fail_workflow_run_insert_for_restart_test()
    """;
    final String selectCountSql2 = """
        select count(*) from batch.trigger_request where tenant_id = ? and request_id = ?
    """;
    final String selectCountSql3 = """
        select count(*) from batch.workflow_run where tenant_id = ? and trigger_request_id = (select id\s\
        from batch.trigger_request where tenant_id = ? and request_id = ?)\
        """;
    final String selectCountSql4 = """
        select count(*) from batch.job_instance where tenant_id = ? and job_code = ?
    """;
    final String selectMetadataJsonSql = """
        select metadata_json->>'arrivalState' from batch.file_record where tenant_id = ? and file_name = ?\
        """;
    final String selectOccurrenceStatusSql = """
        select occurrence_status from batch.expected_work_occurrence where tenant_id = ?\s\
        and workflow_definition_id = ? and source_type = 'FILE_GROUP' and source_key = ? and biz_date = ?\
        """;

    long suffix = System.nanoTime();
    String groupCode = "bundle-restart-" + suffix;
    String fileName = "bundle-restart-" + suffix + ".csv";
    LocalDate bizDate = LocalDate.of(2026, 10, 30);
    String templateCode = "TPL_RESTART_" + suffix;
    WorkflowLaunchSeed seed;
    String requestId;

    try (ConfigurableApplicationContext first = applicationContext(true)) {
      JdbcTemplate jdbc = first.getBean(JdbcTemplate.class);
      seed = LaunchIntegrationFixture.prepareBundleLaunchWithWorker(
          jdbc, tenantId, "BUNDLE_IMPORT", "IMPORT");
      String workflowCode = "WF-" + seed.jobCode();
      requestId = bundleRequestId(tenantId, seed.workflowDefinitionId(), groupCode, bizDate);
      insertBundleArrivalFile(
          first, jdbc, groupCode, fileName, workflowCode, templateCode, bizDate);
      jdbc.execute(createFunctionSql);
      jdbc.execute(createTriggerSql);
      try {
        first.getBean(FileGovernanceScheduler.class).manageFileArrivalGroups();
        String selectedRequestStatus =
            jdbc.queryForObject(selectRequestStatusSql, String.class, tenantId, requestId);
        assertThat(selectedRequestStatus).isEqualTo("ACCEPTED");
        Integer selectedWorkflowRunCount =
            jdbc.queryForObject(selectCountSql, Integer.class, tenantId, tenantId, requestId);
        assertThat(selectedWorkflowRunCount).isZero();
        String selectedCoalesce =
            jdbc.queryForObject(selectCoalesceSql, String.class, tenantId, fileName);
        assertThat(selectedCoalesce).isEqualTo("WAITING_ARRIVAL");
      } finally {
        jdbc.execute(dropTriggerSql);
        jdbc.execute(dropFunctionSql);
      }
    }

    try (ConfigurableApplicationContext restarted = applicationContext(true)) {
      JdbcTemplate jdbc = restarted.getBean(JdbcTemplate.class);
      restarted.getBean(FileGovernanceScheduler.class).manageFileArrivalGroups();

      Integer selectedTriggerRequestCount =
          jdbc.queryForObject(selectCountSql2, Integer.class, tenantId, requestId);
      assertThat(selectedTriggerRequestCount).isEqualTo(1);
      Integer selectedWorkflowRunCountRead2 =
          jdbc.queryForObject(selectCountSql3, Integer.class, tenantId, tenantId, requestId);
      assertThat(selectedWorkflowRunCountRead2).isEqualTo(1);
      Integer selectedJobInstanceCount =
          jdbc.queryForObject(selectCountSql4, Integer.class, tenantId, seed.jobCode());
      assertThat(selectedJobInstanceCount).isEqualTo(1);
      String selectedMetadataJson =
          jdbc.queryForObject(selectMetadataJsonSql, String.class, tenantId, fileName);
      assertThat(selectedMetadataJson).isEqualTo("TRIGGERED");
      String selectedOccurrenceStatus = jdbc.queryForObject(
          selectOccurrenceStatusSql,
          String.class,
          tenantId,
          seed.workflowDefinitionId(),
          groupCode,
          bizDate);
      assertThat(selectedOccurrenceStatus).isEqualTo("SATISFIED");
    }
  }

  @Test
  @DisplayName("文件束扫描与 FILE_ARRIVAL sensor 并发时只在 TRIGGERED 后推进一次")
  void shouldAdvanceSensorOnlyAfterBundleScannerCommitsTriggeredState() throws Exception {
    final String selectPgAdvisoryLockSql = """
        select pg_advisory_lock(?)
    """;
    final String selectPgBackendPidSql = """
        select pg_backend_pid()
    """;
    final String selectPgAdvisoryUnlockSql = """
        select pg_advisory_unlock(?)
    """;

    final String updateWorkflowNodeSql = """
        update batch.workflow_node set node_order = 2 where tenant_id = ? and workflow_definition_id = ?\s\
        and node_code = 'BUNDLE_JOB'\
        """;
    final String updateWorkflowNodeSql2 = """
        update batch.workflow_node set node_order = 3 where tenant_id = ? and workflow_definition_id = ?\s\
        and node_code = 'END'\
        """;
    final String insertWorkflowNodeSql = """
          insert into batch.workflow_node (
              tenant_id, workflow_definition_id, node_code, node_name, node_type, node_order,
              node_params, retry_policy, retry_max_count, timeout_seconds, enabled
          ) values (?, ?, 'WAIT_BUNDLE', 'wait for bundle arrival', 'WAIT', 1, ?::jsonb,
              'NONE', 0, 3600, true)
          """;
    final String deleteWorkflowEdgeSql = """
        delete from batch.workflow_edge where tenant_id = ? and workflow_definition_id = ?
    """;
    final String insertWorkflowEdgeSql = """
          insert into batch.workflow_edge (
              tenant_id, workflow_definition_id, from_node_code, to_node_code, edge_type, enabled
          ) values (?, ?, 'START', 'WAIT_BUNDLE', 'ALWAYS', true),
                   (?, ?, 'WAIT_BUNDLE', 'BUNDLE_JOB', 'SUCCESS', true),
                   (?, ?, 'BUNDLE_JOB', 'END', 'SUCCESS', true)
          """;
    final String selectCountSql = """
        select count(*) from pg_stat_activity where ? = any(pg_blocking_pids(pid))
    """;
    final String selectIdSql = """
        select id from batch.workflow_run where tenant_id = ? and workflow_definition_id = ? and biz_date = ?\
        """;
    final String selectNodeStatusSql = """
        select node_status from batch.workflow_node_run where workflow_run_id = ? and node_code = 'WAIT_BUNDLE'\
        """;
    final String selectCountSql2 = """
        select count(*) from batch.workflow_node_run where workflow_run_id = ? and node_code = 'BUNDLE_JOB'\
        """;
    final String selectCountSql3 = """
        select count(*) from batch.job_instance where tenant_id = ? and job_code = ?
    """;
    final String selectMetadataJsonSql = """
        select metadata_json->>'arrivalState' from batch.file_record where tenant_id = ? and file_name = ?\
        """;
    final String updateWorkflowNodeRunSql = """
        update batch.workflow_node_run set sensor_next_probe_at = current_timestamp - interval '1 second'\s\
        where workflow_run_id = ? and node_code = 'WAIT_BUNDLE'\
        """;
    final String selectCountSql4 = """
                select count(*)
                from batch.outbox_event event
                where event.tenant_id = ?
                  and event.event_key in (
                    select ? || ':dispatch:' || task.id
                    from batch.job_task task
                    join batch.job_instance instance
                      on instance.tenant_id = task.tenant_id
                     and instance.id = task.job_instance_id
                    join batch.workflow_node_run node_run
                      on node_run.job_instance_id = instance.id
                    join batch.workflow_run workflow_run
                      on workflow_run.id = node_run.workflow_run_id
                    where workflow_run.tenant_id = ? and workflow_run.id = ?
                  )
                """;
    final String selectCountSql5 = """
        select count(*) from batch.expected_work_occurrence where tenant_id = ? and workflow_definition_id = ?\s\
        and source_type = 'FILE_GROUP' and source_key = ? and biz_date = ? and occurrence_status = 'SATISFIED'\
        """;

    long suffix = System.nanoTime();
    String groupCode = "bundle-sensor-race-" + suffix;
    String fileName = "bundle-sensor-race-" + suffix + ".csv";
    String templateCode = "TPL_SENSOR_RACE_" + suffix;
    LocalDate bizDate = LocalDate.of(2026, 10, 31);
    long advisoryKey = 7_314_200_000_000L + Math.floorMod(suffix, 1_000_000L);
    String functionName = "block_bundle_arrival_" + suffix;
    String triggerName = "block_bundle_arrival_" + suffix;

    try (ConfigurableApplicationContext application = applicationContext(true)) {
      JdbcTemplate jdbc = application.getBean(JdbcTemplate.class);
      WorkflowLaunchSeed seed = LaunchIntegrationFixture.prepareBundleLaunchWithWorker(
          jdbc, tenantId, "BUNDLE_IMPORT", "IMPORT");
      String workflowCode = "WF-" + seed.jobCode();
      String sensorParams = """
          {"sensor_type":"FILE_ARRIVAL",
           "sensor_spec":{"pattern":"bundle-sensor-race-*.csv","maxAgeSeconds":3600,
             "bindWorkflowBizDate":true,"fileGroupCode":"%s","requireCompleteGroup":true},
           "timeout_seconds":3600,"poll_interval_seconds":1,"on_timeout":"FAIL"}
          """.formatted(groupCode);
      jdbc.update(updateWorkflowNodeSql, tenantId, seed.workflowDefinitionId());
      jdbc.update(updateWorkflowNodeSql2, tenantId, seed.workflowDefinitionId());
      jdbc.update(insertWorkflowNodeSql, tenantId, seed.workflowDefinitionId(), sensorParams);
      jdbc.update(deleteWorkflowEdgeSql, tenantId, seed.workflowDefinitionId());
      jdbc.update(
          insertWorkflowEdgeSql,
          tenantId,
          seed.workflowDefinitionId(),
          tenantId,
          seed.workflowDefinitionId(),
          tenantId,
          seed.workflowDefinitionId());
      insertBundleArrivalFile(
          application, jdbc, groupCode, fileName, workflowCode, templateCode, bizDate);
      final String createFunctionSql =
          """
          create function batch.%s() returns trigger language plpgsql as $$ begin if new.file_name = '%s' then\s\
          perform pg_advisory_lock(%s); perform pg_advisory_unlock(%s); end if; return new; end $$\
          """.formatted(functionName, fileName, advisoryKey, advisoryKey);
      jdbc.execute(createFunctionSql);
      final String createTriggerSql = """
          create trigger %s before update on batch.file_record for each row execute function batch.%s()\
          """.formatted(triggerName, functionName);
      jdbc.execute(createTriggerSql);

      ExecutorService executor = Executors.newSingleThreadExecutor();
      try (Connection fence = jdbc.getDataSource().getConnection()) {
        fence.setAutoCommit(true);
        try (var statement = fence.prepareStatement(selectPgAdvisoryLockSql)) {
          statement.setLong(1, advisoryKey);
          statement.executeQuery().close();
        }
        int blockerPid;
        try (var statement = fence.createStatement();
            var result = statement.executeQuery(selectPgBackendPidSql)) {
          assertThat(result.next()).isTrue();
          blockerPid = result.getInt(1);
        }

        var scanner = executor.submit(
            () -> application.getBean(FileGovernanceScheduler.class).manageFileArrivalGroups());
        Awaitility.await()
            .atMost(Duration.ofSeconds(10))
            .pollInterval(Duration.ofMillis(25))
            .untilAsserted(() -> {
              Integer selectedPgStatActivityCount =
                  jdbc.queryForObject(selectCountSql, Integer.class, blockerPid);
              assertThat(selectedPgStatActivityCount).isGreaterThanOrEqualTo(1);
            });

        RlsTenantContextHolder.runWithTenant(
            tenantId, () -> application.getBean(SensorPollScheduler.class).scan());
        Long workflowRunId = jdbc.queryForObject(
            selectIdSql, Long.class, tenantId, seed.workflowDefinitionId(), bizDate);
        String selectedNodeStatus =
            jdbc.queryForObject(selectNodeStatusSql, String.class, workflowRunId);
        assertThat(selectedNodeStatus).isEqualTo("RUNNING");
        Integer selectedWorkflowNodeRunCount =
            jdbc.queryForObject(selectCountSql2, Integer.class, workflowRunId);
        assertThat(selectedWorkflowNodeRunCount).isZero();
        Integer selectedJobInstanceCount =
            jdbc.queryForObject(selectCountSql3, Integer.class, tenantId, seed.jobCode());
        assertThat(selectedJobInstanceCount).isZero();

        try (var statement = fence.prepareStatement(selectPgAdvisoryUnlockSql)) {
          statement.setLong(1, advisoryKey);
          statement.executeQuery().close();
        }
        scanner.get(30, TimeUnit.SECONDS);

        String selectedMetadataJson =
            jdbc.queryForObject(selectMetadataJsonSql, String.class, tenantId, fileName);
        assertThat(selectedMetadataJson).isEqualTo("TRIGGERED");
        assertThat(application
                .getBean(S3Client.class)
                .headObject(HeadObjectRequest.builder()
                    .bucket(s3Bucket())
                    .key("incoming/" + groupCode + "/" + fileName)
                    .build())
                .contentLength())
            .isPositive();
        jdbc.update(updateWorkflowNodeRunSql, workflowRunId);
        RlsTenantContextHolder.runWithTenant(
            tenantId, () -> application.getBean(SensorPollScheduler.class).scan());
        RlsTenantContextHolder.runWithTenant(
            tenantId, () -> application.getBean(SensorPollScheduler.class).scan());

        String selectedNodeStatusRead2 =
            jdbc.queryForObject(selectNodeStatusSql, String.class, workflowRunId);
        assertThat(selectedNodeStatusRead2).isEqualTo("SUCCESS");
        Integer selectedWorkflowNodeRunCountRead2 =
            jdbc.queryForObject(selectCountSql2, Integer.class, workflowRunId);
        assertThat(selectedWorkflowNodeRunCountRead2).isEqualTo(1);
        Integer selectedJobInstanceCountRead2 =
            jdbc.queryForObject(selectCountSql3, Integer.class, tenantId, seed.jobCode());
        assertThat(selectedJobInstanceCountRead2).isEqualTo(1);
        Integer selectedOutboxEventCount = jdbc.queryForObject(
            selectCountSql4, Integer.class, tenantId, tenantId, tenantId, workflowRunId);
        assertThat(selectedOutboxEventCount).isEqualTo(1);
        Integer selectedExpectedWorkOccurrenceCount = jdbc.queryForObject(
            selectCountSql5,
            Integer.class,
            tenantId,
            seed.workflowDefinitionId(),
            groupCode,
            bizDate);
        assertThat(selectedExpectedWorkOccurrenceCount).isEqualTo(1);
      } finally {
        executor.shutdownNow();
        final String dropTriggerSql = """
            drop trigger if exists %s on batch.file_record\
            """.formatted(triggerName);
        jdbc.execute(dropTriggerSql);
        final String dropFunctionSql = """
            drop function if exists batch.%s()\
            """.formatted(functionName);
        jdbc.execute(dropFunctionSql);
      }
    }
  }

  private void insertCutoffBatchDay(JdbcTemplate jdbc, LocalDate bizDate) {
    final String insertBatchDayInstanceSql = """
        insert into batch.batch_day_instance (tenant_id, calendar_code, biz_date, day_status, open_at,\s\
        cutoff_at, late_count, catchup_count, timezone_snapshot, dst_policy_snapshot, frozen, version,\s\
        created_at, updated_at, dry_run)\s\
        values (?, 'JOB_CALENDAR', ?, 'CUTOFF', current_timestamp, current_timestamp, 0, 0, 'UTC',\s\
        'gap=RUN_AT_NEXT_VALID_TIME;overlap=RUN_ONCE_EARLIER_OFFSET', false, 0, current_timestamp,\s\
        current_timestamp, false)\
        on conflict (tenant_id, calendar_code, biz_date) do update\s\
        set day_status = 'CUTOFF', cutoff_at = current_timestamp, updated_at = current_timestamp\
        """;

    jdbc.update(insertBatchDayInstanceSql, tenantId, bizDate);
  }

  private void reconcileStuckWorkflow(ConfigurableApplicationContext application) {
    new WorkflowRunStuckReconciler(
            application.getBean(WorkflowRunMapper.class),
            application.getBean(WorkflowNodeRunMapper.class),
            application.getBean(WorkflowRunResumeProgressor.class),
            application.getBean(BatchOrchestratorGovernanceProperties.class),
            application.getBean(OrchestratorGracefulShutdown.class),
            application.getBean(MeterRegistry.class))
        .reconcile();
  }

  private void awaitBlockedBy(JdbcTemplate jdbc, int blockerPid, int waiterCount) {
    final String selectCountSql = """
        select count(*) from pg_stat_activity where ? = any(pg_blocking_pids(pid))
    """;

    Awaitility.await()
        .atMost(Duration.ofSeconds(10))
        .pollInterval(Duration.ofMillis(25))
        .untilAsserted(() -> {
          Integer selectedPgStatActivityCount =
              jdbc.queryForObject(selectCountSql, Integer.class, blockerPid);
          assertThat(selectedPgStatActivityCount).isGreaterThanOrEqualTo(waiterCount);
        });
  }

  private String batchDayStatus(JdbcTemplate jdbc, LocalDate bizDate) {
    final String selectDayStatusSql = """
        select day_status from batch.batch_day_instance where tenant_id = ? and calendar_code = 'JOB_CALENDAR'\s\
        and biz_date = ?\
        """;

    return jdbc.queryForObject(selectDayStatusSql, String.class, tenantId, bizDate);
  }

  private String workflowRunStatus(JdbcTemplate jdbc, Long workflowRunId) {
    final String selectRunStatusSql = """
        select run_status from batch.workflow_run where tenant_id = ? and id = ?
    """;

    return jdbc.queryForObject(selectRunStatusSql, String.class, tenantId, workflowRunId);
  }

  private ConfigurableApplicationContext applicationContext(boolean sensorEnabled) {
    return applicationContext(sensorEnabled, true);
  }

  private ConfigurableApplicationContext applicationContext(
      boolean sensorEnabled, boolean stuckReconcileEnabled) {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("spring.datasource.url", platformJdbcUrl());
    properties.put("spring.datasource.username", platformJdbcUsername());
    properties.put("spring.datasource.password", platformJdbcPassword());
    properties.put("spring.datasource.driver-class-name", "org.postgresql.Driver");
    properties.put("spring.datasource.hikari.maximum-pool-size", 20);
    properties.put("spring.datasource.hikari.minimum-idle", 1);
    properties.put("batch.datasource.business.url", businessJdbcUrl());
    properties.put("batch.datasource.business.username", businessJdbcUsername());
    properties.put("batch.datasource.business.password", businessJdbcPassword());
    properties.put("batch.datasource.business.schema", "biz");
    properties.put("spring.kafka.bootstrap-servers", kafkaBootstrapServers());
    properties.put("batch.storage.backend", "s3");
    properties.put("batch.storage.s3.endpoint", s3Endpoint());
    properties.put("batch.storage.s3.access-key", s3AccessKey());
    properties.put("batch.storage.s3.secret-key", s3SecretKey());
    properties.put("batch.storage.s3.bucket", s3Bucket());
    properties.put("batch.storage.s3.region", s3Region());
    properties.put("batch.storage.s3.path-style-enabled", s3PathStyleEnabled());
    properties.put("spring.data.redis.host", redisHost());
    properties.put("spring.data.redis.port", redisPort());
    properties.put("batch.security.bypass-mode", true);
    properties.put("batch.security.kms.default-key-ref", "DEFAULT_TEST");
    properties.put("batch.security.kms.keys.DEFAULT_TEST", "AAAAAAAAAAAAAAAAAAAAAA==");
    properties.put("batch.sensor.enabled", sensorEnabled);
    properties.put("batch.workflow.stuck-reconcile.enabled", stuckReconcileEnabled);
    properties.put("batch.sensor.scan-interval", "PT1H");
    properties.put("batch.file-governance.arrival.enabled", true);
    properties.put("batch.file-governance.arrival.poll-interval-millis", 3_600_000);
    properties.put("batch.batch-day.settle-scan-interval-millis", 3_600_000);
    properties.put("batch.batch-day.open-scan-interval-millis", 3_600_000);
    properties.put("batch.worker.drain.heartbeat-timeout-scheduler-enabled", false);
    properties.put("batch.partition-lease.reclaim-scheduler-enabled", false);
    properties.put("spring.flyway.enabled", true);
    properties.put("spring.flyway.default-schema", "batch");
    properties.put("spring.flyway.schemas", "batch,quartz");
    return new SpringApplicationBuilder(BatchOrchestratorApplication.class)
        .profiles("test")
        .web(WebApplicationType.NONE)
        .run(properties.entrySet().stream()
            .map(entry -> "--" + entry.getKey() + "=" + entry.getValue())
            .toArray(String[]::new));
  }

  private Long seedWorkflow(
      JdbcTemplate jdbc,
      String jobCode,
      String requestId,
      String dedupKey,
      String traceId,
      LocalDate bizDate) {
    final String insertBusinessCalendarSql = """
        insert into batch.business_calendar (
            tenant_id, calendar_code, calendar_name, timezone, enabled
        ) values (?, 'JOB_CALENDAR', 'Integration Job Calendar', 'UTC', true)
        on conflict (tenant_id, calendar_code) do nothing
        """;
    final String insertWorkflowDefinitionSql = """
        insert into batch.workflow_definition (
            tenant_id, workflow_code, workflow_name, workflow_type, version, enabled, calendar_code
        ) values (?, ?, ?, 'DAG', 1, true, 'JOB_CALENDAR') returning id
        """;
    final String insertWorkflowNodeSql = """
        insert into batch.workflow_node (
            tenant_id, workflow_definition_id, node_code, node_name, node_type, node_order,
            retry_policy, retry_max_count, timeout_seconds, enabled
        ) values (?, ?, 'START', 'start', 'START', 0, 'NONE', 0, 0, true)
        """;
    final String insertWorkflowNodeSql2 = """
        insert into batch.workflow_node (
            tenant_id, workflow_definition_id, node_code, node_name, node_type, node_order,
            node_params, retry_policy, retry_max_count, timeout_seconds, enabled
        ) values (?, ?, 'WAIT_FILE', 'wait for file', 'WAIT', 1, ?::jsonb, 'NONE', 0, 3600, true)
        """;
    final String insertWorkflowNodeSql3 = """
        insert into batch.workflow_node (
            tenant_id, workflow_definition_id, node_code, node_name, node_type, node_order,
            retry_policy, retry_max_count, timeout_seconds, enabled
        ) values (?, ?, 'END', 'end', 'END', 2, 'NONE', 0, 0, true)
        """;
    final String insertWorkflowEdgeSql = """
        insert into batch.workflow_edge (
            tenant_id, workflow_definition_id, from_node_code, to_node_code, edge_type, enabled
        ) values (?, ?, 'START', 'WAIT_FILE', 'ALWAYS', true),
                 (?, ?, 'WAIT_FILE', 'END', 'SUCCESS', true)
        """;
    final String insertTriggerRequestSql = """
        insert into batch.trigger_request (
            tenant_id, request_id, trigger_type, workflow_definition_id, biz_date, dedup_key,
            request_status, trace_id
        ) values (?, ?, 'API', ?, ?, ?, 'ACCEPTED', ?)
        """;

    jdbc.update(insertBusinessCalendarSql, tenantId);
    Long definitionId =
        jdbc.queryForObject(insertWorkflowDefinitionSql, Long.class, tenantId, jobCode, jobCode);
    jdbc.update(insertWorkflowNodeSql, tenantId, definitionId);
    String nodeParams = """
        {"sensor_type":"FILE_ARRIVAL",
         "sensor_spec":{"pattern":"context-restart-*.csv","maxAgeSeconds":3600,
           "bindWorkflowBizDate":true,"fileGroupCode":"CONTEXT_RESTART","requireCompleteGroup":true},
         "timeout_seconds":3600,"poll_interval_seconds":1,"on_timeout":"FAIL"}
        """;
    jdbc.update(insertWorkflowNodeSql2, tenantId, definitionId, nodeParams);
    jdbc.update(insertWorkflowNodeSql3, tenantId, definitionId);
    jdbc.update(insertWorkflowEdgeSql, tenantId, definitionId, tenantId, definitionId);
    jdbc.update(
        insertTriggerRequestSql, tenantId, requestId, definitionId, bizDate, dedupKey, traceId);
    return definitionId;
  }

  private void insertBundleArrivalFile(
      ConfigurableApplicationContext application,
      JdbcTemplate jdbc,
      String groupCode,
      String fileName,
      String workflowCode,
      String templateCode,
      LocalDate bizDate) {
    final String insertFileRecordSql = """
        insert into batch.file_record (
            tenant_id, file_category, file_name, original_file_name, file_ext, file_format_type,
            charset, mime_type, file_size_bytes, checksum_type, storage_type, storage_path,
            storage_bucket, file_version, file_generation_no, is_latest, source_type, source_ref, file_status,
            biz_date, trace_id, metadata_json, created_at, updated_at
        ) values (?, 'INPUT', ?, ?, 'csv', 'DELIMITED', 'UTF-8', 'text/csv', 1, 'NONE',
            'S3', ?, ?, 'v1', 1, true, 'SYSTEM', 'bundle-restart-test', 'RECEIVED', ?, ?,
            ?::jsonb, current_timestamp, current_timestamp)
        """;

    String storageKey = "incoming/" + groupCode + "/" + fileName;
    application
        .getBean(S3Client.class)
        .putObject(
            PutObjectRequest.builder()
                .bucket(s3Bucket())
                .key(storageKey)
                .contentType("text/csv")
                .build(),
            RequestBody.fromString("id,value\n1,ready\n"));
    String metadata = """
        {"fileGroupCode":"%s","waitFileGroupMode":"ALL_OF",
         "requiredFileSet":"%s","arrivalTimeoutAction":"MANUAL_CONFIRM",
         "triggerOnComplete":true,"bundleWorkflowCode":"%s",
         "bundleTemplateCode":"%s"}
        """.formatted(groupCode, fileName, workflowCode, templateCode);
    jdbc.update(
        insertFileRecordSql,
        tenantId,
        fileName,
        fileName,
        storageKey,
        s3Bucket(),
        bizDate,
        "trace-" + groupCode,
        metadata);
  }

  private String bundleRequestId(
      String tenantId, Long workflowDefinitionId, String groupCode, LocalDate bizDate) {
    return "bundle-arrival-" + tenantId + "-" + workflowDefinitionId + "-" + groupCode + "-"
        + bizDate;
  }
}
