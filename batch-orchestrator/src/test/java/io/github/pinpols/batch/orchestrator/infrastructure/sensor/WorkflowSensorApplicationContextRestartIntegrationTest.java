package io.github.pinpols.batch.orchestrator.infrastructure.sensor;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.service.task.WorkflowRunResumeProgressor;
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
import java.util.Map;
import java.util.Set;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/** 验证持久化 WAIT 传感器恢复以及通过 Orchestrator 路由到业务数据库。 */
@DisplayName("Workflow 文件等待:完整 Orchestrator 上下文重建后恢复持久化 WAIT")
class WorkflowSensorApplicationContextRestartIntegrationTest extends AbstractIntegrationTest {

  private static final String TENANT = "t1-workflow-sensor-context-restart";

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
    businessJdbc.update(
        insertWorkflowSensorProbeTestSql,
        TENANT,
        marker);

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
          """.formatted(TENANT, marker);
      jdbc.update(
          updateWorkflowNodeSql,
          dbSensorParams,
          TENANT,
          workflowDefinitionId);
      LaunchRequest launchRequest = LaunchRequest.builder()
          .tenantId(TENANT)
          .workflowDefinitionId(workflowDefinitionId)
          .bizDate(bizDate)
          .triggerType(TriggerType.API)
          .requestId(requestId)
          .traceId(requestId)
          .params(Map.of())
          .build();
      RlsTenantContextHolder.runWithTenant(
          TENANT, () -> application.getBean(WorkflowRunLaunchService.class).launch(launchRequest));
      Long workflowRunId = jdbc.queryForObject(
          selectIdSql,
          Long.class,
          TENANT,
          workflowDefinitionId,
          bizDate);
      jdbc.update(
          updateWorkflowNodeRunSql,
          workflowRunId);

      RlsTenantContextHolder.runWithTenant(
          TENANT, () -> application.getBean(SensorPollScheduler.class).scan());

      assertThat(jdbc.queryForObject(
              selectNodeStatusSql,
              String.class,
              workflowRunId))
          .isEqualTo("SUCCESS");
      assertThat(jdbc.queryForObject(
              selectOutputSql,
              String.class,
              workflowRunId))
          .contains(marker);
      assertThat(jdbc.queryForObject(
              selectRunStatusSql,
              String.class,
              TENANT,
              workflowRunId))
          .isEqualTo("SUCCESS");
    } finally {
      businessJdbc.update(
          deleteWorkflowSensorProbeTestSql,
          TENANT,
          marker);
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
    businessJdbc.update(
        insertWorkflowSensorProbeTestSql,
        TENANT,
        marker);

    try (ConfigurableApplicationContext application = applicationContext(true, false)) {
      JdbcTemplate jdbc = application.getBean(JdbcTemplate.class);
      Long workflowDefinitionId =
          seedWorkflow(jdbc, workflowCode, requestId, "dedup-" + suffix, requestId, bizDate);
      jdbc.update(insertJobDefinitionSql, TENANT, jobCode, jobCode, workerGroup);
      jdbc.update(insertWorkerRegistrySql, TENANT, "wk-db-sensor-r11-" + suffix, workerGroup);

      String dbSensorParams = """
          {"sensor_type":"DB_ROW_EXISTS",
           "sensor_spec":{"schema":"biz",
             "sql":"select marker from biz.workflow_sensor_probe_test where tenant_id = :tenantId and marker = :marker limit 1",
             "params":{"tenantId":"%s","marker":"%s"}},
           "timeout_seconds":3600,"poll_interval_seconds":1,"on_timeout":"FAIL"}
          """.formatted(TENANT, marker);
      jdbc.update(
          updateWorkflowNodeSql,
          dbSensorParams,
          TENANT,
          workflowDefinitionId);
      jdbc.update(updateWorkflowNodeSql2, jobCode, TENANT, workflowDefinitionId);
      jdbc.update(insertWorkflowNodeSql, TENANT, workflowDefinitionId);
      jdbc.update(
          deleteWorkflowEdgeSql,
          TENANT,
          workflowDefinitionId);
      jdbc.update(
          insertWorkflowEdgeSql,
          TENANT,
          workflowDefinitionId,
          TENANT,
          workflowDefinitionId,
          TENANT,
          workflowDefinitionId);

      LaunchRequest launchRequest = LaunchRequest.builder()
          .tenantId(TENANT)
          .workflowDefinitionId(workflowDefinitionId)
          .bizDate(bizDate)
          .triggerType(TriggerType.API)
          .requestId(requestId)
          .traceId(requestId)
          .params(Map.of())
          .build();
      RlsTenantContextHolder.runWithTenant(
          TENANT, () -> application.getBean(WorkflowRunLaunchService.class).launch(launchRequest));
      Long workflowRunId = jdbc.queryForObject(
          selectIdSql,
          Long.class,
          TENANT,
          TENANT,
          requestId);
      insertCutoffBatchDay(jdbc, bizDate);
      jdbc.update(
          updateWorkflowNodeRunSql,
          workflowRunId);

      ExecutorService executor = Executors.newFixedThreadPool(2);
      try (Connection fence = jdbc.getDataSource().getConnection()) {
        fence.setAutoCommit(false);
        try (var statement =
            fence.prepareStatement(selectAcquireBatchDaySettlementFenceSql)) {
          statement.setString(1, TENANT);
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
              TENANT, () -> application.getBean(SensorPollScheduler.class).scan()));
        } else {
          sensorRelease = executor.submit(() -> RlsTenantContextHolder.runWithTenant(
              TENANT, () -> application.getBean(SensorPollScheduler.class).scan()));
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

      assertThat(jdbc.queryForObject(
              selectNodeStatusSql,
              String.class,
              workflowRunId))
          .isEqualTo("SUCCESS");
      assertThat(jdbc.queryForObject(
              selectRunStatusSql,
              String.class,
              TENANT,
              workflowRunId))
          .isEqualTo("RUNNING");
      assertThat(jdbc.queryForObject(
              selectDayStatusSql,
              String.class,
              TENANT,
              bizDate))
          .isEqualTo("IN_FLIGHT");
      assertThat(jdbc.queryForObject(selectCountSql, Integer.class, TENANT, workflowRunId)).isEqualTo(1);
      Long jobInstanceId = jdbc.queryForObject(selectJobInstanceIdSql, Long.class, workflowRunId);
      assertThat(jobInstanceId).isNotNull();
      assertThat(jdbc.queryForObject(selectCountSql2, Integer.class, TENANT, jobInstanceId, jobCode, bizDate))
          .isEqualTo(1);
    } finally {
      businessJdbc.update(
          deleteWorkflowSensorProbeTestSql,
          TENANT,
          marker);
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
      jdbc.update(
          updateWorkflowNodeSql,
          timeoutAction,
          TENANT,
          workflowDefinitionId);
      LaunchRequest launchRequest = LaunchRequest.builder()
          .tenantId(TENANT)
          .workflowDefinitionId(workflowDefinitionId)
          .bizDate(bizDate)
          .triggerType(TriggerType.API)
          .requestId(requestId)
          .traceId(requestId)
          .params(Map.of())
          .build();
      RlsTenantContextHolder.runWithTenant(
          TENANT, () -> application.getBean(WorkflowRunLaunchService.class).launch(launchRequest));
      Long workflowRunId = jdbc.queryForObject(
          selectIdSql,
          Long.class,
          TENANT,
          TENANT,
          requestId);
      insertCutoffBatchDay(jdbc, bizDate);
      jdbc.update(
          updateWorkflowNodeRunSql,
          workflowRunId);

      ExecutorService executor = Executors.newSingleThreadExecutor();
      try (Connection fence = jdbc.getDataSource().getConnection()) {
        fence.setAutoCommit(false);
        try (var statement =
            fence.prepareStatement(selectAcquireBatchDaySettlementFenceSql)) {
          statement.setString(1, TENANT);
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
            .untilAsserted(() -> assertThat(jdbc.queryForObject(
                    selectCountSql,
                    Integer.class,
                    blockerPid))
                .isGreaterThanOrEqualTo(1));

        RlsTenantContextHolder.runWithTenant(
            TENANT, () -> application.getBean(SensorPollScheduler.class).scan());
        String expectedWaitStatus = "FAIL".equals(timeoutAction) ? "FAILED" : "SKIPPED";
        String expectedRunStatus = "FAIL".equals(timeoutAction) ? "FAILED" : "SUCCESS";
        assertThat(jdbc.queryForObject(
                selectNodeStatusSql,
                String.class,
                workflowRunId))
            .isEqualTo(expectedWaitStatus);
        assertThat(jdbc.queryForObject(
                selectRunStatusSql,
                String.class,
                TENANT,
                workflowRunId))
            .isEqualTo(expectedRunStatus);

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
      jdbc.update(
          deleteWorkflowEdgeSql,
          TENANT,
          workflowDefinitionId);
      jdbc.update(
          updateWorkflowNodeSql,
          TENANT,
          workflowDefinitionId);
      jdbc.update(insertWorkflowNodeSql, TENANT, workflowDefinitionId);
      jdbc.update(insertWorkflowEdgeSql, TENANT, workflowDefinitionId, TENANT, workflowDefinitionId);
      jdbc.update(
          updateWorkflowNodeSql2,
          TENANT,
          workflowDefinitionId);
      LaunchRequest launchRequest = LaunchRequest.builder()
          .tenantId(TENANT)
          .workflowDefinitionId(workflowDefinitionId)
          .bizDate(bizDate)
          .triggerType(TriggerType.API)
          .requestId(requestId)
          .traceId(requestId)
          .params(Map.of())
          .build();
      RlsTenantContextHolder.runWithTenant(
          TENANT, () -> application.getBean(WorkflowRunLaunchService.class).launch(launchRequest));
      Long workflowRunId = jdbc.queryForObject(
          selectIdSql,
          Long.class,
          TENANT,
          TENANT,
          requestId);
      insertCutoffBatchDay(jdbc, bizDate);
      jdbc.update(
          updateWorkflowNodeRunSql,
          workflowRunId);

      RlsTenantContextHolder.runWithTenant(
          TENANT, () -> application.getBean(SensorPollScheduler.class).scan());

      assertThat(jdbc.queryForObject(
              selectNodeStatusSql,
              String.class,
              workflowRunId))
          .isEqualTo("SKIPPED");
      assertThat(jdbc.queryForObject(
              selectSkipReasonCodeSql,
              String.class,
              workflowRunId))
          .isEqualTo("SENSOR_TIMEOUT_SKIP_DOWNSTREAM");
      assertThat(jdbc.queryForObject(
              selectNodeStatusSql2,
              String.class,
              workflowRunId))
          .isEqualTo("SKIPPED");
      assertThat(jdbc.queryForObject(
              selectCountSql,
              Integer.class,
              workflowRunId))
          .isZero();
      assertThat(jdbc.queryForObject(
              selectRunStatusSql,
              String.class,
              TENANT,
              workflowRunId))
          .isEqualTo("SUCCESS");

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
      jdbc.update(
          deleteWorkflowEdgeSql,
          TENANT,
          workflowDefinitionId);
      jdbc.update(
          updateWorkflowNodeSql,
          TENANT,
          workflowDefinitionId);
      jdbc.update(
          updateWorkflowNodeSql2,
          TENANT,
          workflowDefinitionId);
      jdbc.update(
          updateWorkflowNodeSql3,
          TENANT,
          workflowDefinitionId);
      jdbc.update(
          updateWorkflowNodeSql4,
          TENANT,
          workflowDefinitionId);
      jdbc.update(
          updateWorkflowNodeSql5,
          TENANT,
          workflowDefinitionId);
      String secondWaitParams = """
          {"sensor_type":"FILE_ARRIVAL",
           "sensor_spec":{"pattern":"context-restart-any-*.csv","maxAgeSeconds":3600,
             "bindWorkflowBizDate":true,"fileGroupCode":"CONTEXT_RESTART_ANY","requireCompleteGroup":true},
           "timeout_seconds":3600,"poll_interval_seconds":1,"on_timeout":"FAIL"}
          """;
      jdbc.update(insertWorkflowNodeSql, TENANT, workflowDefinitionId, secondWaitParams);
      jdbc.update(insertWorkflowNodeSql2, TENANT, workflowDefinitionId);
      jdbc.update(
          insertWorkflowEdgeSql,
          TENANT,
          workflowDefinitionId,
          TENANT,
          workflowDefinitionId,
          TENANT,
          workflowDefinitionId,
          TENANT,
          workflowDefinitionId,
          TENANT,
          workflowDefinitionId);

      LaunchRequest launchRequest = LaunchRequest.builder()
          .tenantId(TENANT)
          .workflowDefinitionId(workflowDefinitionId)
          .bizDate(bizDate)
          .triggerType(TriggerType.API)
          .requestId(requestId)
          .traceId(requestId)
          .params(Map.of())
          .build();
      RlsTenantContextHolder.runWithTenant(
          TENANT, () -> application.getBean(WorkflowRunLaunchService.class).launch(launchRequest));
      Long workflowRunId = jdbc.queryForObject(
          selectIdSql,
          Long.class,
          TENANT,
          TENANT,
          requestId);
      jdbc.update(
          updateWorkflowNodeRunSql,
          workflowRunId);

      RlsTenantContextHolder.runWithTenant(
          TENANT, () -> application.getBean(SensorPollScheduler.class).scan());

      assertThat(jdbc.queryForObject(
              selectNodeStatusSql,
              String.class,
              workflowRunId))
          .isEqualTo("SKIPPED");
      assertThat(jdbc.queryForObject(
              selectCountSql,
              Integer.class,
              workflowRunId))
          .isZero();
      assertThat(jdbc.queryForObject(
              selectRunStatusSql,
              String.class,
              TENANT,
              workflowRunId))
          .isEqualTo("RUNNING");

      String fileName = "context-restart-any-" + suffix + ".csv";
      jdbc.update(insertFileRecordSql, TENANT, fileName, "s3://test/" + fileName, bizDate);
      jdbc.update(
          updateWorkflowNodeRunSql2,
          workflowRunId);
      RlsTenantContextHolder.runWithTenant(
          TENANT, () -> application.getBean(SensorPollScheduler.class).scan());

      assertThat(jdbc.queryForObject(
              selectNodeStatusSql2,
              String.class,
              workflowRunId))
          .isEqualTo("SUCCESS");
      assertThat(jdbc.queryForObject(
              selectNodeStatusSql3,
              String.class,
              workflowRunId))
          .isEqualTo("SUCCESS");
      assertThat(jdbc.queryForObject(
              selectNodeStatusSql4,
              String.class,
              workflowRunId))
          .isEqualTo("SUCCESS");
      assertThat(jdbc.queryForObject(
              selectRunStatusSql,
              String.class,
              TENANT,
              workflowRunId))
          .isEqualTo("SUCCESS");
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
          .tenantId(TENANT)
          .workflowDefinitionId(workflowDefinitionId)
          .bizDate(bizDate)
          .triggerType(TriggerType.API)
          .requestId(requestId)
          .traceId(requestId)
          .params(Map.of())
          .build();
      RlsTenantContextHolder.runWithTenant(
          TENANT,
          () -> application
              .getBean(WorkflowRunLaunchService.class)
              .launch(launchRequest));
      Long workflowRunId = jdbc.queryForObject(
          selectIdSql,
          Long.class,
          TENANT,
          workflowDefinitionId,
          bizDate);
      insertCutoffBatchDay(jdbc, bizDate);
      jdbc.update(insertFileRecordSql, TENANT, fileName, "s3://test/" + fileName, bizDate);
      jdbc.update(
          updateWorkflowNodeRunSql,
          workflowRunId);

      if ("SETTLEMENT_FIRST".equals(firstAction)) {
        application.getBean(BatchDaySettleScheduler.class).settle();
        assertThat(batchDayStatus(jdbc, bizDate)).isEqualTo("IN_FLIGHT");
        assertThat(workflowRunStatus(jdbc, workflowRunId)).isEqualTo("RUNNING");
        RlsTenantContextHolder.runWithTenant(
            TENANT, () -> application.getBean(SensorPollScheduler.class).scan());
      } else {
        RlsTenantContextHolder.runWithTenant(
            TENANT, () -> application.getBean(SensorPollScheduler.class).scan());
      }

      assertThat(jdbc.queryForObject(
              selectNodeStatusSql,
              String.class,
              workflowRunId))
          .isEqualTo("SUCCESS");
      assertThat(workflowRunStatus(jdbc, workflowRunId)).isEqualTo("SUCCESS");
      application.getBean(BatchDaySettleScheduler.class).settle();
      assertThat(jdbc.queryForObject(
              selectCountSql,
              Long.class,
              TENANT,
              workflowRunId))
          .isEqualTo(1L);
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
          .tenantId(TENANT)
          .workflowDefinitionId(workflowDefinitionId)
          .bizDate(bizDate)
          .triggerType(TriggerType.API)
          .requestId(requestId)
          .traceId(requestId)
          .params(Map.of())
          .build();
      RlsTenantContextHolder.runWithTenant(
          TENANT,
          () -> application
              .getBean(WorkflowRunLaunchService.class)
              .launch(launchRequest));
      Long workflowRunId = jdbc.queryForObject(
          selectIdSql,
          Long.class,
          TENANT,
          workflowDefinitionId,
          bizDate);
      insertCutoffBatchDay(jdbc, bizDate);
      // 模拟已持久化的崩溃窗口：所有 DAG 节点都已提交终态，
      // 但进程尚未来得及结束仍为 RUNNING 的根 WorkflowRun。
      jdbc.update(updateWorkflowNodeRunSql, workflowRunId);
      jdbc.update(
          updateWorkflowRunSql,
          TENANT,
          workflowRunId);
      assertThat(application
              .getBean(WorkflowRunMapper.class)
              .selectStuckRunningCandidates(Instant.now().minusSeconds(1_800), 100)
              .stream()
              .map(run -> run.getId())
              .toList())
          .contains(workflowRunId);
      assertThat(jdbc.queryForList(
              selectNodeStatusSql,
              String.class,
              workflowRunId))
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
      assertThat(jdbc.queryForObject(
              selectCountSql,
              Long.class,
              TENANT,
              workflowRunId))
          .isEqualTo(1L);
      assertThat(batchDayStatus(jdbc, bizDate)).isEqualTo("SETTLED");
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
          .tenantId(TENANT)
          .workflowDefinitionId(workflowDefinitionId)
          .bizDate(bizDate)
          .triggerType(TriggerType.API)
          .requestId(requestId)
          .traceId(traceId)
          .params(Map.of())
          .build();
      RlsTenantContextHolder.runWithTenant(TENANT, () -> launchService.launch(launchRequest));

      workflowRunId = jdbc.queryForObject(
          selectIdSql,
          Long.class,
          TENANT,
          TENANT,
          requestId);
      insertCutoffBatchDay(jdbc, bizDate);
      first.getBean(BatchDaySettleScheduler.class).settle();
      assertThat(batchDayStatus(jdbc, bizDate)).isEqualTo("IN_FLIGHT");
      WorkflowNodeRunEntity waitRun = RlsTenantContextHolder.runWithTenant(
          TENANT,
          () -> first
              .getBean(WorkflowNodeRunMapper.class)
              .selectLatestByWorkflowRunIdAndNodeCode(workflowRunId, "WAIT_FILE"));
      assertThat(waitRun).isNotNull();
      assertThat(waitRun.getNodeStatus()).isEqualTo("RUNNING");
      jdbc.update(
          updateWorkflowNodeRunSql,
          waitRun.getId());
      assertThat(jdbc.queryForObject(
              selectCountSql,
              Long.class,
              TENANT,
              workflowRunId))
          .isZero();
    }

    // 首个 Spring 上下文、调度器、事务管理器、连接池和应用 Bean
    // 均已关闭。第二个上下文继续使用同一外部 PostgreSQL 状态。
    try (ConfigurableApplicationContext restarted = applicationContext(true)) {
      JdbcTemplate jdbc = restarted.getBean(JdbcTemplate.class);
      jdbc.update(insertFileRecordSql, TENANT, fileName, "s3://test/" + fileName, bizDate);
      jdbc.update(
          updateWorkflowNodeRunSql2,
          workflowRunId);

      ExecutorService executor = Executors.newFixedThreadPool(2);
      CountDownLatch start = new CountDownLatch(1);
      try {
        var sensorScan = executor.submit(() -> {
          start.await();
          RlsTenantContextHolder.runWithTenant(
              TENANT, () -> restarted.getBean(SensorPollScheduler.class).scan());
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
      RlsTenantContextHolder.runWithTenant(TENANT, () -> {
        WorkflowNodeRunEntity waitRun =
            nodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(workflowRunId, "WAIT_FILE");
        WorkflowNodeRunEntity endRun =
            nodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(workflowRunId, "END");
        assertThat(waitRun.getNodeStatus()).isEqualTo("SUCCESS");
        assertThat(waitRun.getOutput()).contains(fileName);
        assertThat(endRun.getNodeStatus()).isEqualTo("SUCCESS");
      });
      assertThat(jdbc.queryForObject(
              selectRunStatusSql,
              String.class,
              TENANT,
              workflowRunId))
          .isEqualTo("SUCCESS");
      assertThat(jdbc.queryForObject(
              selectRelatedJobInstanceIdSql,
              Long.class,
              TENANT,
              workflowRunId))
          .isNull();
      assertThat(jdbc.queryForObject(
              selectCountSql2,
              Long.class,
              TENANT,
              workflowRunId))
          .isEqualTo(1L);
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
      jdbc.update(insertJobDefinitionSql, TENANT, childJobCode, childJobCode, childWorkerGroup);
      jdbc.update(updateWorkflowNodeSql, childJobCode, TENANT, workflowDefinitionId);
      jdbc.update(insertWorkflowNodeSql, TENANT, workflowDefinitionId);
      jdbc.update(updateWorkflowEdgeSql, TENANT, workflowDefinitionId);
      jdbc.update(insertWorkflowEdgeSql, TENANT, workflowDefinitionId);
      jdbc.update(updateWorkflowNodeSql2, """
          {"sensor_type":"FILE_ARRIVAL",
           "sensor_spec":{"pattern":"terminated-wait-r11-*.csv","maxAgeSeconds":3600,
             "bindWorkflowBizDate":true,"fileGroupCode":"CONTEXT_RESTART",
             "requireCompleteGroup":true},
           "timeout_seconds":3600,"poll_interval_seconds":1,"on_timeout":"FAIL"}
          """, TENANT, workflowDefinitionId);

      LaunchRequest launchRequest = LaunchRequest.builder()
          .tenantId(TENANT)
          .workflowDefinitionId(workflowDefinitionId)
          .bizDate(bizDate)
          .triggerType(TriggerType.API)
          .requestId(requestId)
          .traceId(requestId)
          .params(Map.of())
          .build();
      RlsTenantContextHolder.runWithTenant(
          TENANT,
          () -> application
              .getBean(WorkflowRunLaunchService.class)
              .launch(launchRequest));
      Long workflowRunId = jdbc.queryForObject(
          selectIdSql,
          Long.class,
          TENANT,
          TENANT,
          requestId);
      insertCutoffBatchDay(jdbc, bizDate);
      application.getBean(BatchDaySettleScheduler.class).settle();
      assertThat(batchDayStatus(jdbc, bizDate)).isEqualTo("IN_FLIGHT");

      RlsTenantContextHolder.runWithTenant(
          TENANT,
          () -> application
              .getBean(WorkflowRunManagementApplicationService.class)
              .terminate(TENANT, workflowRunId));
      jdbc.update(insertFileRecordSql, TENANT, fileName, "s3://test/" + fileName, bizDate);
      jdbc.update(updateWorkflowNodeRunSql, workflowRunId);

      RlsTenantContextHolder.runWithTenant(
          TENANT, () -> application.getBean(SensorPollScheduler.class).scan());

      assertThat(jdbc.queryForObject(
              selectRunStatusSql,
              String.class,
              TENANT,
              workflowRunId))
          .isEqualTo("TERMINATED");
      assertThat(jdbc.queryForObject(
              selectCountSql,
              Integer.class,
              TENANT,
              workflowRunId))
          .isZero();
      assertThat(jdbc.queryForObject(
              selectNodeStatusSql,
              String.class,
              workflowRunId))
          .isEqualTo("SKIPPED");
      assertThat(jdbc.queryForObject(
              selectSkipReasonCodeSql,
              String.class,
              workflowRunId))
          .isEqualTo("PARENT_WORKFLOW_TERMINAL");
      assertThat(jdbc.queryForObject(
              selectSensorNextProbeAtSql,
              Timestamp.class,
              workflowRunId))
          .isNull();
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
          .tenantId(TENANT)
          .workflowDefinitionId(workflowDefinitionId)
          .bizDate(bizDate)
          .triggerType(TriggerType.API)
          .requestId(requestId)
          .traceId(traceId)
          .params(Map.of())
          .build();
      RlsTenantContextHolder.runWithTenant(
          TENANT, () -> first.getBean(WorkflowRunLaunchService.class).launch(launchRequest));
      workflowRunId = jdbc.queryForObject(
          selectIdSql,
          Long.class,
          TENANT,
          TENANT,
          requestId);
      insertCutoffBatchDay(jdbc, bizDate);
      jdbc.update(
          updateWorkflowNodeRunSql,
          workflowRunId);
      assertThat(jdbc.queryForObject(
              selectCountSql,
              Integer.class,
              workflowRunId))
          .isEqualTo(1);
      assertThat(jdbc.queryForObject(
              selectCountSql2,
              Integer.class,
              TENANT,
              jobCode))
          .isZero();
    }

    try (ConfigurableApplicationContext restarted = applicationContext(true)) {
      JdbcTemplate jdbc = restarted.getBean(JdbcTemplate.class);
      RlsTenantContextHolder.runWithTenant(
          TENANT, () -> restarted.getBean(SensorPollScheduler.class).scan());

      WorkflowNodeRunMapper nodeRunMapper = restarted.getBean(WorkflowNodeRunMapper.class);
      WorkflowNodeRunEntity timedOut = RlsTenantContextHolder.runWithTenant(
          TENANT,
          () -> nodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(workflowRunId, "WAIT_FILE"));
      assertThat(timedOut.getNodeStatus()).isEqualTo("FAILED");
      assertThat(timedOut.getErrorCode()).isEqualTo("SENSOR_FAIL");
      assertThat(jdbc.queryForObject(
              selectRunStatusSql,
              String.class,
              TENANT,
              workflowRunId))
          .isEqualTo("FAILED");

      RlsTenantContextHolder.runWithTenant(
          TENANT,
          () -> restarted
              .getBean(WorkflowRunManagementApplicationService.class)
              .skipNode(TENANT, workflowRunId, "WAIT_FILE", "operator-r08", "approved timeout"));
      WorkflowNodeRunEntity skipped = RlsTenantContextHolder.runWithTenant(
          TENANT,
          () -> nodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(workflowRunId, "WAIT_FILE"));
      assertThat(skipped.getNodeStatus()).isEqualTo("SKIPPED");
      assertThat(skipped.getSkipReasonCode()).isEqualTo("OPERATOR_SKIPPED");
      assertThat(skipped.getSkipReason()).isEqualTo("approved timeout");
      assertThat(jdbc.queryForObject(
              selectRunStatusSql,
              String.class,
              TENANT,
              workflowRunId))
          .isEqualTo("FAILED");

      restarted.getBean(BatchDaySettleScheduler.class).settle();
      assertThat(batchDayStatus(jdbc, bizDate)).isEqualTo("FAILED");
      assertThat(jdbc.queryForObject(
              selectCountSql3,
              Long.class,
              TENANT,
              workflowRunId))
          .isEqualTo(1L);
      assertThat(jdbc.queryForObject(
              selectCountSql2,
              Integer.class,
              TENANT,
              jobCode))
          .isZero();
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
          jdbc, TENANT, "BUNDLE_IMPORT", "IMPORT");
      String workflowCode = "WF-" + seed.jobCode();
      requestId = bundleRequestId(TENANT, seed.workflowDefinitionId(), groupCode, bizDate);
      insertBundleArrivalFile(
          first, jdbc, groupCode, fileName, workflowCode, templateCode, bizDate);
      jdbc.execute(createFunctionSql);
      jdbc.execute(createTriggerSql);
      try {
        first.getBean(FileGovernanceScheduler.class).manageFileArrivalGroups();
        assertThat(jdbc.queryForObject(
                selectRequestStatusSql,
                String.class,
                TENANT,
                requestId))
            .isEqualTo("ACCEPTED");
        assertThat(jdbc.queryForObject(
                selectCountSql,
                Integer.class,
                TENANT,
                TENANT,
                requestId))
            .isZero();
        assertThat(jdbc.queryForObject(
                selectCoalesceSql,
                String.class,
                TENANT,
                fileName))
            .isEqualTo("WAITING_ARRIVAL");
      } finally {
        jdbc.execute(dropTriggerSql);
        jdbc.execute(dropFunctionSql);
      }
    }

    try (ConfigurableApplicationContext restarted = applicationContext(true)) {
      JdbcTemplate jdbc = restarted.getBean(JdbcTemplate.class);
      restarted.getBean(FileGovernanceScheduler.class).manageFileArrivalGroups();

      assertThat(jdbc.queryForObject(
              selectCountSql2,
              Integer.class,
              TENANT,
              requestId))
          .isEqualTo(1);
      assertThat(jdbc.queryForObject(
              selectCountSql3,
              Integer.class,
              TENANT,
              TENANT,
              requestId))
          .isEqualTo(1);
      assertThat(jdbc.queryForObject(
              selectCountSql4,
              Integer.class,
              TENANT,
              seed.jobCode()))
          .isEqualTo(1);
      assertThat(jdbc.queryForObject(
              selectMetadataJsonSql,
              String.class,
              TENANT,
              fileName))
          .isEqualTo("TRIGGERED");
      assertThat(jdbc.queryForObject(
              selectOccurrenceStatusSql,
              String.class,
              TENANT,
              seed.workflowDefinitionId(),
              groupCode,
              bizDate))
          .isEqualTo("SATISFIED");
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
          jdbc, TENANT, "BUNDLE_IMPORT", "IMPORT");
      String workflowCode = "WF-" + seed.jobCode();
      String sensorParams = """
          {"sensor_type":"FILE_ARRIVAL",
           "sensor_spec":{"pattern":"bundle-sensor-race-*.csv","maxAgeSeconds":3600,
             "bindWorkflowBizDate":true,"fileGroupCode":"%s","requireCompleteGroup":true},
           "timeout_seconds":3600,"poll_interval_seconds":1,"on_timeout":"FAIL"}
          """.formatted(groupCode);
      jdbc.update(
          updateWorkflowNodeSql,
          TENANT,
          seed.workflowDefinitionId());
      jdbc.update(
          updateWorkflowNodeSql2,
          TENANT,
          seed.workflowDefinitionId());
      jdbc.update(insertWorkflowNodeSql, TENANT, seed.workflowDefinitionId(), sensorParams);
      jdbc.update(
          deleteWorkflowEdgeSql,
          TENANT,
          seed.workflowDefinitionId());
      jdbc.update(
          insertWorkflowEdgeSql,
          TENANT,
          seed.workflowDefinitionId(),
          TENANT,
          seed.workflowDefinitionId(),
          TENANT,
          seed.workflowDefinitionId());
      insertBundleArrivalFile(
          application, jdbc, groupCode, fileName, workflowCode, templateCode, bizDate);
      final String createFunctionSql = """
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
            .untilAsserted(() -> assertThat(jdbc.queryForObject(
                    selectCountSql,
                    Integer.class,
                    blockerPid))
                .isGreaterThanOrEqualTo(1));

        RlsTenantContextHolder.runWithTenant(
            TENANT, () -> application.getBean(SensorPollScheduler.class).scan());
        Long workflowRunId = jdbc.queryForObject(
            selectIdSql,
            Long.class,
            TENANT,
            seed.workflowDefinitionId(),
            bizDate);
        assertThat(jdbc.queryForObject(
                selectNodeStatusSql,
                String.class,
                workflowRunId))
            .isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject(
                selectCountSql2,
                Integer.class,
                workflowRunId))
            .isZero();
        assertThat(jdbc.queryForObject(
                selectCountSql3,
                Integer.class,
                TENANT,
                seed.jobCode()))
            .isZero();

        try (var statement = fence.prepareStatement(selectPgAdvisoryUnlockSql)) {
          statement.setLong(1, advisoryKey);
          statement.executeQuery().close();
        }
        scanner.get(30, TimeUnit.SECONDS);

        assertThat(jdbc.queryForObject(
                selectMetadataJsonSql,
                String.class,
                TENANT,
                fileName))
            .isEqualTo("TRIGGERED");
        assertThat(application
                .getBean(S3Client.class)
                .headObject(HeadObjectRequest.builder()
                    .bucket(s3Bucket())
                    .key("incoming/" + groupCode + "/" + fileName)
                    .build())
                .contentLength())
            .isPositive();
        jdbc.update(
            updateWorkflowNodeRunSql,
            workflowRunId);
        RlsTenantContextHolder.runWithTenant(
            TENANT, () -> application.getBean(SensorPollScheduler.class).scan());
        RlsTenantContextHolder.runWithTenant(
            TENANT, () -> application.getBean(SensorPollScheduler.class).scan());

        assertThat(jdbc.queryForObject(
                selectNodeStatusSql,
                String.class,
                workflowRunId))
            .isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject(
                selectCountSql2,
                Integer.class,
                workflowRunId))
            .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                selectCountSql3,
                Integer.class,
                TENANT,
                seed.jobCode()))
            .isEqualTo(1);
        assertThat(jdbc.queryForObject(selectCountSql4, Integer.class, TENANT, TENANT, TENANT, workflowRunId))
            .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                selectCountSql5,
                Integer.class,
                TENANT,
                seed.workflowDefinitionId(),
                groupCode,
                bizDate))
            .isEqualTo(1);
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
        """;

    jdbc.update(
        insertBatchDayInstanceSql,
        TENANT,
        bizDate);
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
        .untilAsserted(() -> assertThat(jdbc.queryForObject(
                selectCountSql,
                Integer.class,
                blockerPid))
            .isGreaterThanOrEqualTo(waiterCount));
  }

  private String batchDayStatus(JdbcTemplate jdbc, LocalDate bizDate) {
    final String selectDayStatusSql = """
        select day_status from batch.batch_day_instance where tenant_id = ? and calendar_code = 'JOB_CALENDAR'\s\
        and biz_date = ?\
        """;

    return jdbc.queryForObject(
        selectDayStatusSql,
        String.class,
        TENANT,
        bizDate);
  }

  private String workflowRunStatus(JdbcTemplate jdbc, Long workflowRunId) {
    final String selectRunStatusSql = """
        select run_status from batch.workflow_run where tenant_id = ? and id = ?
    """;

    return jdbc.queryForObject(
        selectRunStatusSql,
        String.class,
        TENANT,
        workflowRunId);
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

    jdbc.update(insertBusinessCalendarSql, TENANT);
    Long definitionId = jdbc.queryForObject(insertWorkflowDefinitionSql, Long.class, TENANT, jobCode, jobCode);
    jdbc.update(insertWorkflowNodeSql, TENANT, definitionId);
    String nodeParams = """
        {"sensor_type":"FILE_ARRIVAL",
         "sensor_spec":{"pattern":"context-restart-*.csv","maxAgeSeconds":3600,
           "bindWorkflowBizDate":true,"fileGroupCode":"CONTEXT_RESTART","requireCompleteGroup":true},
         "timeout_seconds":3600,"poll_interval_seconds":1,"on_timeout":"FAIL"}
        """;
    jdbc.update(insertWorkflowNodeSql2, TENANT, definitionId, nodeParams);
    jdbc.update(insertWorkflowNodeSql3, TENANT, definitionId);
    jdbc.update(insertWorkflowEdgeSql, TENANT, definitionId, TENANT, definitionId);
    jdbc.update(insertTriggerRequestSql, TENANT, requestId, definitionId, bizDate, dedupKey, traceId);
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
        TENANT,
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
