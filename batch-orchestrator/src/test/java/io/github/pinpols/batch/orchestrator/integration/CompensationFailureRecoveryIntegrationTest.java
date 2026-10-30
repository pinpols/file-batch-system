package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.github.pinpols.batch.common.enums.CompensationCommandStatus;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.archive.ArchiveIntegrityAlertService;
import io.github.pinpols.batch.orchestrator.application.archive.SuccessInstanceArchiveService;
import io.github.pinpols.batch.orchestrator.application.service.governance.CompensationService;
import io.github.pinpols.batch.orchestrator.application.service.governance.RetryGovernanceService;
import io.github.pinpols.batch.orchestrator.config.SuccessInstanceArchiveProperties;
import io.github.pinpols.batch.orchestrator.domain.command.CompensationSubmitCommand;
import io.github.pinpols.batch.orchestrator.domain.param.UpdateCompensationStatusParam;
import io.github.pinpols.batch.orchestrator.infrastructure.scheduler.StaleCompensationCommandReconciler;
import io.github.pinpols.batch.orchestrator.infrastructure.scheduler.StaleCompensationReconcilerProperties;
import io.github.pinpols.batch.orchestrator.mapper.CompensationCommandMapper;
import io.github.pinpols.batch.orchestrator.mapper.SuccessInstanceArchiveMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "batch.compensation.stale-running-reconciler.timeout-seconds=60",
      "batch.compensation.stale-running-reconciler.batch-size=10",
      "batch.compensation.stale-running-reconciler.initial-delay-millis=600000",
      "batch.compensation.stale-running-reconciler.fixed-delay-millis=600000"
    })
@DisplayName("补偿命令失败恢复集成:验证真实数据库与真实服务下补偿中途失败时命令置失败并留下错误信息,同一目标再次提交可成功且记录累计两条")
class CompensationFailureRecoveryIntegrationTest extends AbstractIntegrationTest {

  private static final String TENANT = "it-comp";
  private static final Long PARTITION_ID = 880001L;

  @Autowired
  private CompensationService compensationService;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @MockitoBean
  private RetryGovernanceService retryGovernanceService;

  @Autowired
  private StaleCompensationCommandReconciler staleCompensationCommandReconciler;

  @Autowired
  private StaleCompensationReconcilerProperties staleProperties;

  @Autowired
  private CompensationCommandMapper compensationCommandMapper;

  @Autowired
  private PlatformTransactionManager transactionManager;

  @Autowired
  private SuccessInstanceArchiveMapper archiveMapper;

  @Test
  @DisplayName("补偿处理期间作业先归档，失败状态和无热作业关联的审计仍持久化")
  void shouldPersistFailureAuditWhenArchiveWinsDuringStepCompensation() throws Exception {
    final String insertJobDefinitionSql1 = """
        insert into batch.job_definition
          (tenant_id, job_code, job_name, job_type, schedule_type, timezone, retry_policy)
        values (?, 'ARCHIVE_JOB', 'Archive job', 'GENERAL', 'MANUAL', 'Asia/Shanghai', 'NONE')
        returning id
        """;
    final String insertJobInstanceSql2 = """
        insert into batch.job_instance
          (tenant_id, job_definition_id, job_code, instance_no, biz_date, trigger_type,
           instance_status, priority, dedup_key, expected_partition_count,
           success_partition_count, failed_partition_count, trace_id, finished_at, dry_run)
        values (?, ?, 'ARCHIVE_JOB', ?, current_date - 3, 'MANUAL', 'SUCCESS', 5, ?,
                1, 1, 0, ?, now() - interval '3 days', false) returning id
        """;
    final String insertJobPartitionSql3 = """
        insert into batch.job_partition
          (tenant_id, job_instance_id, job_instance_biz_date, partition_no, partition_status,
           business_key, idempotency_key, finished_at)
        values (?, ?, current_date - 3, 1, 'SUCCESS', ?, ?, now() - interval '3 days') returning id
        """;
    final String insertJobTaskSql4 = """
        insert into batch.job_task
          (tenant_id, job_instance_id, job_instance_biz_date, job_partition_id, task_type,
           task_seq, task_status, finished_at)
        values (?, ?, current_date - 3, ?, 'EXECUTION', 1, 'SUCCESS', now() - interval '3 days')
        returning id
        """;
    final String insertJobStepInstanceSql5 = """
        insert into batch.job_step_instance
          (tenant_id, job_instance_id, job_partition_id, job_task_id, step_code, step_type,
           step_status, finished_at)
        values (?, ?, ?, ?, 'STEP_1', 'TASK', 'SUCCESS', now() - interval '3 days') returning id
        """;
    final String selectJobInstanceArchiveSql6 =
        "select count(*) from archive.job_instance_archive where id = ?";
    final String selectCompensationCommandSql7 =
        "select command_status, related_job_instance_id from batch.compensation_command where tenant_id = ?";
    final String selectJobExecutionLogSql8 =
        "select count(*) from batch.job_execution_log where tenant_id = ? and log_type = 'COMPENSATION' and job_instance_id is null";
    String tenant = "comp-archive-" + UUID.randomUUID().toString().replace("-", "");
    Long definitionId = jdbcTemplate.queryForObject(insertJobDefinitionSql1, Long.class, tenant);
    Long instanceId = jdbcTemplate.queryForObject(
        insertJobInstanceSql2,
        Long.class,
        tenant,
        definitionId,
        "instance-" + tenant,
        "dedup-" + tenant,
        tenant);
    Long partitionId = jdbcTemplate.queryForObject(
        insertJobPartitionSql3,
        Long.class,
        tenant,
        instanceId,
        "business-" + tenant,
        "idempotency-" + tenant);
    Long taskId =
        jdbcTemplate.queryForObject(insertJobTaskSql4, Long.class, tenant, instanceId, partitionId);
    Long stepId = jdbcTemplate.queryForObject(
        insertJobStepInstanceSql5, Long.class, tenant, instanceId, partitionId, taskId);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    doAnswer(invocation -> {
          entered.countDown();
          if (!release.await(15, TimeUnit.SECONDS)) {
            throw new IllegalStateException("archive was not released");
          }
          throw new IllegalStateException("retry failed after archive");
        })
        .when(retryGovernanceService)
        .retryTask(eq(tenant), eq(taskId), ArgumentMatchers.anyString());
    SuccessInstanceArchiveProperties properties = new SuccessInstanceArchiveProperties();
    properties.setEnabled(true);
    properties.setRetentionDays(1);
    properties.setBatchSize(10);
    SuccessInstanceArchiveService archive = new SuccessInstanceArchiveService(
        archiveMapper, properties, mock(ArchiveIntegrityAlertService.class));
    var command = CompensationSubmitCommand.builder()
        .tenantId(tenant)
        .compensationType("STEP")
        .targetId(stepId)
        .reason("archive race")
        .operatorId("it")
        .build();
    try (var executor = Executors.newSingleThreadExecutor()) {
      Future<Throwable> outcome = executor.submit(() -> {
        try {
          compensationService.submit(command);
          return null;
        } catch (Throwable failure) {
          return failure;
        }
      });
      try {
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        new TransactionTemplate(transactionManager)
            .executeWithoutResult(status -> archive.archiveOnce());
        assertThat(jdbcTemplate.queryForObject(
                selectJobInstanceArchiveSql6, Integer.class, instanceId))
            .isEqualTo(1);
      } finally {
        release.countDown();
      }
      assertThat(outcome.get(10, TimeUnit.SECONDS))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("retry failed after archive");
      Map<String, Object> commandRow =
          jdbcTemplate.queryForMap(selectCompensationCommandSql7, tenant);
      assertThat(commandRow)
          .containsEntry("command_status", "FAILED")
          .containsEntry("related_job_instance_id", null);
      assertThat(jdbcTemplate.queryForObject(selectJobExecutionLogSql8, Integer.class, tenant))
          .isEqualTo(1);
    } finally {
      reset(retryGovernanceService);
    }
  }

  @Test
  @DisplayName("调用方已有事务时，handler 失败不能让失败记录事务等待自身持有的命令锁")
  void shouldPersistFailureWithoutSelfBlocking_whenCallerHasTransaction() throws Exception {
    String tenant = "comp-outer-" + UUID.randomUUID().toString().replace("-", "");
    String backendPidSql = "select pg_backend_pid()";
    String blockedSessionsSql =
        "select pid from pg_stat_activity where ? = any(pg_blocking_pids(pid))";
    String cancelSessionSql = "select pg_cancel_backend(?)";
    String terminateSessionSql = "select pg_terminate_backend(?)";
    String selectStatusSql =
        "select command_status from batch.compensation_command where tenant_id = ?";
    CountDownLatch entered = new CountDownLatch(1);
    AtomicInteger outerPid = new AtomicInteger();
    doAnswer(invocation -> {
          entered.countDown();
          throw new IllegalStateException("nested handler failed");
        })
        .when(retryGovernanceService)
        .retryPartition(eq(tenant), eq(PARTITION_ID), ArgumentMatchers.anyString());
    CompensationSubmitCommand command = CompensationSubmitCommand.builder()
        .tenantId(tenant)
        .compensationType("PARTITION")
        .targetId(PARTITION_ID)
        .reason("nested transaction failure")
        .operatorId("it")
        .build();
    try (var executor = Executors.newSingleThreadExecutor()) {
      Future<Throwable> outcome = executor.submit(() -> {
        try {
          new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            outerPid.set(jdbcTemplate.queryForObject(backendPidSql, Integer.class));
            compensationService.submit(command);
          });
          return null;
        } catch (Throwable exception) {
          return exception;
        }
      });
      assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
      Throwable error;
      try {
        error = outcome.get(3, TimeUnit.SECONDS);
      } catch (TimeoutException blocked) {
        List<Integer> blockedSessions =
            jdbcTemplate.queryForList(blockedSessionsSql, Integer.class, outerPid.get());
        if (blockedSessions.isEmpty()) {
          jdbcTemplate.queryForObject(terminateSessionSql, Boolean.class, outerPid.get());
        } else {
          for (Integer blockedPid : blockedSessions) {
            jdbcTemplate.queryForObject(cancelSessionSql, Boolean.class, blockedPid);
          }
        }
        outcome.get(10, TimeUnit.SECONDS);
        throw new AssertionError(
            "failure recording waited on caller transaction; blocked sessions=" + blockedSessions,
            blocked);
      }
      assertThat(error)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("nested handler failed");
      assertThat(jdbcTemplate.queryForObject(selectStatusSql, String.class, tenant))
          .isEqualTo(CompensationCommandStatus.FAILED.code());
    } finally {
      reset(retryGovernanceService);
    }
  }

  @Test
  @DisplayName("调用方事务提交时补偿成功，后续回滚时补偿命令不得遗留 RUNNING")
  void shouldCompleteCommand_whenCallerCommitsOrRollsBack() {
    String tenant = "comp-completion-" + UUID.randomUUID().toString().replace("-", "");
    String selectStatusSql = """
        select command_status from batch.compensation_command where tenant_id = ? and target_id = ?
        """;
    CompensationSubmitCommand committed = nestedCommand(tenant, PARTITION_ID);
    CompensationSubmitCommand rolledBack = nestedCommand(tenant, PARTITION_ID + 1);
    try {
      new TransactionTemplate(transactionManager)
          .executeWithoutResult(status -> compensationService.submit(committed));
      assertThat(jdbcTemplate.queryForObject(selectStatusSql, String.class, tenant, PARTITION_ID))
          .isEqualTo(CompensationCommandStatus.SUCCESS.code());

      assertThatThrownBy(
              () -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                compensationService.submit(rolledBack);
                throw new IllegalStateException("caller rolls back after successful handler");
              }))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("caller rolls back");
      assertThat(
              jdbcTemplate.queryForObject(selectStatusSql, String.class, tenant, PARTITION_ID + 1))
          .isEqualTo(CompensationCommandStatus.FAILED.code());
    } finally {
      reset(retryGovernanceService);
    }
  }

  private static CompensationSubmitCommand nestedCommand(String tenant, long targetId) {
    return CompensationSubmitCommand.builder()
        .tenantId(tenant)
        .compensationType("PARTITION")
        .targetId(targetId)
        .reason("nested transaction completion")
        .operatorId("it")
        .build();
  }

  @Test
  @DisplayName("对账先结束遗留命令时后续锁定与状态回写不得复活该命令")
  void shouldNotReviveCommand_whenReconcilerWinsBeforeExecution() {
    String tenant = "comp-stale-" + UUID.randomUUID().toString().replace("-", "");
    String commandNo = "stale-" + UUID.randomUUID();
    String selectIdSql = """
        select id from batch.compensation_command where tenant_id = ? and command_no = ?
        """;
    String deleteCommandSql = "delete from batch.compensation_command where tenant_id = ?";
    try {
      insertCommand(tenant, commandNo, CompensationCommandStatus.RUNNING.code(), 120);
      Long id = jdbcTemplate.queryForObject(selectIdSql, Long.class, tenant, commandNo);
      StaleCompensationCommandReconciler reconciler =
          AopTestUtils.getUltimateTargetObject(staleCompensationCommandReconciler);
      reconciler.reconcile();
      assertThat(commandStatus(tenant, commandNo))
          .isEqualTo(CompensationCommandStatus.FAILED.code());
      assertThat(compensationCommandMapper.lockRunningCommand(
              tenant, id, CompensationCommandStatus.RUNNING.code()))
          .isNull();
      var staleUpdate = UpdateCompensationStatusParam.builder()
          .tenantId(tenant)
          .id(id)
          .expectedStatus(CompensationCommandStatus.RUNNING.code())
          .commandStatus(CompensationCommandStatus.SUCCESS.code())
          .finishedAt(Instant.now())
          .build();
      assertThat(compensationCommandMapper.updateStatus(staleUpdate)).isZero();
      assertThat(errorCode(tenant, commandNo)).isEqualTo("STALE_RUNNING_TIMEOUT");
    } finally {
      jdbcTemplate.update(deleteCommandSql, tenant);
    }
  }

  @Test
  @DisplayName("仍在执行的补偿不能被超时对账解除同目标互斥")
  void shouldKeepActiveCompensationExclusive_whenReconcilerRuns() throws Exception {
    String tenant = "comp-race-" + UUID.randomUUID().toString().replace("-", "");
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger executions = new AtomicInteger();
    doAnswer(invocation -> {
          if (executions.incrementAndGet() == 1) {
            entered.countDown();
            if (!release.await(20, TimeUnit.SECONDS)) {
              throw new IllegalStateException("test handler release timed out");
            }
          }
          return null;
        })
        .when(retryGovernanceService)
        .retryPartition(eq(tenant), eq(PARTITION_ID), ArgumentMatchers.anyString());
    CompensationSubmitCommand command = CompensationSubmitCommand.builder()
        .tenantId(tenant)
        .compensationType("PARTITION")
        .targetId(PARTITION_ID)
        .reason("active compensation race")
        .operatorId("it")
        .traceId("trace-comp-race")
        .build();
    String selectStatusSql = """
        select command_status from batch.compensation_command where tenant_id = ? and target_id = ?
        """;
    long previousTimeout = staleProperties.getTimeoutSeconds();
    staleProperties.setTimeoutSeconds(1);
    try (var executor = Executors.newSingleThreadExecutor()) {
      Future<String> first = executor.submit(() -> compensationService.submit(command));
      try {
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(1500);
        StaleCompensationCommandReconciler reconciler =
            AopTestUtils.getUltimateTargetObject(staleCompensationCommandReconciler);
        reconciler.reconcile();
        assertThat(jdbcTemplate.queryForObject(selectStatusSql, String.class, tenant, PARTITION_ID))
            .isEqualTo(CompensationCommandStatus.RUNNING.code());
        assertThatThrownBy(() -> compensationService.submit(command))
            .isInstanceOf(BizException.class);
        assertThat(executions).hasValue(1);
      } finally {
        release.countDown();
      }
      assertThat(first.get(10, TimeUnit.SECONDS)).isNotBlank();
    } finally {
      staleProperties.setTimeoutSeconds(previousTimeout);
      reset(retryGovernanceService);
    }
  }

  @Test
  @DisplayName("补偿执行中途失败时命令置为失败并落库错误信息,失败后同一目标再次提交可成功恢复且累计两条命令记录")
  void shouldPersistFailedCommandAndRecover_whenSameTargetResubmitted() {
    final String selectCountSql = """
                  select count(*)::int
                  from batch.compensation_command
                  where tenant_id = ? and compensation_type = 'PARTITION' and target_id = ?
                  """;

    doThrow(new IllegalStateException("simulated mid-compensation failure"))
        .when(retryGovernanceService)
        .retryPartition(eq(TENANT), eq(PARTITION_ID), ArgumentMatchers.anyString());

    assertThatThrownBy(() -> compensationService.submit(command("first attempt")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("simulated mid-compensation failure");

    Map<String, Object> failed = latestCommand();
    assertThat(failed).containsEntry("command_status", CompensationCommandStatus.FAILED.code());
    assertThat(failed.get("error_message").toString())
        .contains("simulated mid-compensation failure");

    reset(retryGovernanceService);
    doNothing()
        .when(retryGovernanceService)
        .retryPartition(eq(TENANT), eq(PARTITION_ID), ArgumentMatchers.anyString());

    String recoveredCommandNo = compensationService.submit(command("recovery attempt"));

    assertThat(recoveredCommandNo).isNotBlank();
    Map<String, Object> recovered = latestCommand();
    assertThat(recovered).containsEntry("command_no", recoveredCommandNo);
    assertThat(recovered).containsEntry("command_status", CompensationCommandStatus.SUCCESS.code());
    Integer selectedCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT, PARTITION_ID);
    assertThat(selectedCount).isEqualTo(2);
  }

  @Test
  @DisplayName("同一已批准补偿重投返回原命令且不重复执行")
  void shouldReturnExistingCommand_whenApprovedCompensationIsResubmitted() {
    final String approvalId = "approval-idempotency-" + UUID.randomUUID();
    final String selectCountSql = """
        select (
            (select count(*) from batch.compensation_command where tenant_id = ? and approval_id = ?)
            + (select count(*) from archive.compensation_command_archive where tenant_id = ?\s\
            and approval_id = ?)
        )::int
        """;
    Map<String, Object> approvedParams = new LinkedHashMap<>();
    approvedParams.put("alpha", "one");
    approvedParams.put("beta", "two");
    CompensationSubmitCommand approved = CompensationSubmitCommand.builder()
        .tenantId(TENANT)
        .compensationType("PARTITION")
        .targetId(PARTITION_ID)
        .reason("approved recovery")
        .operatorId("it")
        .approvalId(approvalId)
        .launchParams(approvedParams)
        .traceId("trace-" + approvalId)
        .build();

    reset(retryGovernanceService);
    doNothing()
        .when(retryGovernanceService)
        .retryPartition(eq(TENANT), eq(PARTITION_ID), ArgumentMatchers.anyString());

    String firstCommandNo = compensationService.submit(approved);
    CompensationSubmitCommand retriedApproval = CompensationSubmitCommand.builder()
        .tenantId(TENANT)
        .compensationType("PARTITION")
        .targetId(PARTITION_ID)
        .reason("approved recovery")
        .operatorId("different-authenticated-operator")
        .approvalId(approvalId)
        .launchParams(new LinkedHashMap<>(Map.of("beta", "two", "alpha", "one")))
        .traceId("trace-retry")
        .build();
    String retriedCommandNo = compensationService.submit(retriedApproval);

    assertThat(retriedCommandNo).isEqualTo(firstCommandNo);
    Integer selectedCount = jdbcTemplate.queryForObject(
        selectCountSql, Integer.class, TENANT, approvalId, TENANT, approvalId);
    assertThat(selectedCount).isEqualTo(1);

    final String archiveCommandSql = """
        insert into archive.compensation_command_archive (
            id, tenant_id, command_no, compensation_type, target_id, source_workflow_run_id,
            job_code, biz_date, batch_no, related_job_instance_id, related_file_id, approval_id,
            operator_id, reason, strategy, command_status, trace_id, result_summary, error_code,
            error_message, created_at, finished_at, error_key, error_args, request_fingerprint
        )
        select id, tenant_id, command_no, compensation_type, target_id, source_workflow_run_id,
            job_code, biz_date, batch_no, related_job_instance_id, related_file_id, approval_id,
            operator_id, reason, strategy, command_status, trace_id, result_summary, error_code,
            error_message, created_at, finished_at, error_key, error_args, request_fingerprint
        from batch.compensation_command
        where tenant_id = ? and approval_id = ?
        """;
    jdbcTemplate.update(archiveCommandSql, TENANT, approvalId);
    final String deleteCompensationCommandSql = """
        delete from batch.compensation_command where tenant_id = ? and approval_id = ?
    """;
    jdbcTemplate.update(deleteCompensationCommandSql, TENANT, approvalId);
    assertThat(compensationService.submit(retriedApproval)).isEqualTo(firstCommandNo);

    CompensationSubmitCommand changedTarget = CompensationSubmitCommand.builder()
        .tenantId(TENANT)
        .compensationType("PARTITION")
        .targetId(PARTITION_ID + 1)
        .reason("approved recovery")
        .operatorId("it")
        .approvalId(approvalId)
        .traceId("trace-changed-target")
        .build();
    assertThatThrownBy(() -> compensationService.submit(changedTarget))
        .hasMessage("error.compensation.approval_request_mismatch");
    Integer selectedCountRead2 = jdbcTemplate.queryForObject(
        selectCountSql, Integer.class, TENANT, approvalId, TENANT, approvalId);
    assertThat(selectedCountRead2).isEqualTo(1);
    verify(retryGovernanceService, times(1))
        .retryPartition(eq(TENANT), eq(PARTITION_ID), ArgumentMatchers.anyString());
  }

  @Test
  @DisplayName("补偿命令引用其他租户文件时真实租户查询拒绝且不写命令")
  void shouldRejectForeignTenantRelatedFileBeforePersistingCommand() {
    final String insertFileRecordSql = """
          insert into batch.file_record (tenant_id, file_category, file_name, file_format_type,
              storage_type, storage_path, source_type, file_status)
          values (?, 'INPUT', ?, 'DELIMITED', 'LOCAL', ?, 'UPLOAD', 'RECEIVED')
          returning id
          """;
    final String selectCountSql = """
      select count(*)::int from batch.compensation_command where tenant_id = ?
  """;
    final String selectCountSql2 = """
      select count(*)::int from batch.file_record where tenant_id = ? and id = ?
  """;
    final String deleteJobExecutionLogSql = """
        delete from batch.job_execution_log where trace_id = ?
    """;
    final String deleteFileRecordSql = """
        delete from batch.file_record where tenant_id = ? and id = ?
    """;

    String suffix = UUID.randomUUID().toString().replace("-", "");
    String tenantA = "comp-file-a-" + suffix;
    String tenantB = "comp-file-b-" + suffix;
    String traceId = "trace-comp-file-" + suffix;
    Long foreignFileId = jdbcTemplate.queryForObject(
        insertFileRecordSql,
        Long.class,
        tenantB,
        "foreign-comp-file-" + suffix,
        "/compensation/foreign/" + suffix);

    try {
      CompensationSubmitCommand command = CompensationSubmitCommand.builder()
          .tenantId(tenantA)
          .compensationType("PARTITION")
          .targetId(PARTITION_ID)
          .relatedFileId(foreignFileId)
          .reason("cross-tenant reference must fail before insert")
          .traceId(traceId)
          .build();

      assertThatThrownBy(() -> compensationService.submit(command))
          .hasMessage("error.file.record_not_found");

      Integer selectedCount = jdbcTemplate.queryForObject(selectCountSql, Integer.class, tenantA);
      assertThat(selectedCount).isZero();
      Integer selectedCountRead2 =
          jdbcTemplate.queryForObject(selectCountSql2, Integer.class, tenantB, foreignFileId);
      assertThat(selectedCountRead2).isEqualTo(1);
    } finally {
      jdbcTemplate.update(deleteJobExecutionLogSql, traceId);
      jdbcTemplate.update(deleteFileRecordSql, tenantB, foreignFileId);
    }
  }

  @Test
  @DisplayName("全局超时扫描跨租户回收陈旧命令并保留新命令与终态")
  void shouldReconcileStaleRunningCommandsAcrossTenants() {
    final String deleteCompensationCommandSql = """
        delete from batch.compensation_command where tenant_id in (?, ?)
    """;

    String suffix = UUID.randomUUID().toString().replace("-", "");
    String tenantA = "comp-stale-a-" + suffix;
    String tenantB = "comp-stale-b-" + suffix;
    String staleA = suffix + "-stale-a";
    String staleB = suffix + "-stale-b";
    String fresh = suffix + "-fresh";
    String terminal = suffix + "-terminal";
    insertCommand(tenantA, staleA, CompensationCommandStatus.RUNNING.code(), 120);
    insertCommand(tenantB, staleB, CompensationCommandStatus.RUNNING.code(), 120);
    insertCommand(tenantA, fresh, CompensationCommandStatus.RUNNING.code(), 10);
    insertCommand(tenantB, terminal, CompensationCommandStatus.SUCCESS.code(), 120);

    try {
      // 本用例验证跨租户回收 SQL；绕过调度代理，避免其他测试留下的 ShedLock 使调用静默跳过。
      StaleCompensationCommandReconciler target =
          AopTestUtils.getUltimateTargetObject(staleCompensationCommandReconciler);
      target.reconcile();

      assertThat(commandStatus(tenantA, staleA)).isEqualTo(CompensationCommandStatus.FAILED.code());
      assertThat(commandStatus(tenantB, staleB)).isEqualTo(CompensationCommandStatus.FAILED.code());
      assertThat(commandStatus(tenantA, fresh)).isEqualTo(CompensationCommandStatus.RUNNING.code());
      assertThat(commandStatus(tenantB, terminal))
          .isEqualTo(CompensationCommandStatus.SUCCESS.code());
      assertThat(errorCode(tenantA, staleA)).isEqualTo("STALE_RUNNING_TIMEOUT");
      assertThat(errorCode(tenantB, staleB)).isEqualTo("STALE_RUNNING_TIMEOUT");
    } finally {
      jdbcTemplate.update(deleteCompensationCommandSql, tenantA, tenantB);
    }
  }

  private static CompensationSubmitCommand command(String reason) {
    return CompensationSubmitCommand.builder()
        .tenantId(TENANT)
        .compensationType("PARTITION")
        .targetId(PARTITION_ID)
        .reason(reason)
        .operatorId("it")
        .traceId("trace-comp-recovery")
        .build();
  }

  private Map<String, Object> latestCommand() {
    final String selectCommandNoSql = """
          select command_no, command_status, error_message
          from batch.compensation_command
          where tenant_id = ? and compensation_type = 'PARTITION' and target_id = ?
          order by id desc
          limit 1
          """;

    return jdbcTemplate.queryForMap(selectCommandNoSql, TENANT, PARTITION_ID);
  }

  private void insertCommand(String tenantId, String commandNo, String status, long ageSeconds) {
    final String insertCompensationCommandSql = """
        insert into batch.compensation_command (tenant_id, command_no, compensation_type, command_status,\s\
        created_at) values (?, ?, 'JOB', ?, ?)\
        """;

    jdbcTemplate.update(
        insertCompensationCommandSql,
        tenantId,
        commandNo,
        status,
        Timestamp.from(Instant.now().minusSeconds(ageSeconds)));
  }

  private String commandStatus(String tenantId, String commandNo) {
    final String selectCommandStatusSql = """
        select command_status from batch.compensation_command where tenant_id = ? and command_no = ?
    """;

    return jdbcTemplate.queryForObject(selectCommandStatusSql, String.class, tenantId, commandNo);
  }

  private String errorCode(String tenantId, String commandNo) {
    final String selectErrorCodeSql = """
        select error_code from batch.compensation_command where tenant_id = ? and command_no = ?
    """;

    return jdbcTemplate.queryForObject(selectErrorCodeSql, String.class, tenantId, commandNo);
  }
}
