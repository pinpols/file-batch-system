package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.github.pinpols.batch.common.enums.CompensationCommandStatus;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.service.governance.CompensationService;
import io.github.pinpols.batch.orchestrator.application.service.governance.RetryGovernanceService;
import io.github.pinpols.batch.orchestrator.domain.command.CompensationSubmitCommand;
import io.github.pinpols.batch.orchestrator.infrastructure.scheduler.StaleCompensationCommandReconciler;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

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
        insertFileRecordSql, Long.class, tenantB, "foreign-comp-file-" + suffix, "/compensation/foreign/" + suffix);

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
      jdbcTemplate.update(
          deleteFileRecordSql, tenantB, foreignFileId);
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
      staleCompensationCommandReconciler.reconcile();

      assertThat(commandStatus(tenantA, staleA)).isEqualTo(CompensationCommandStatus.FAILED.code());
      assertThat(commandStatus(tenantB, staleB)).isEqualTo(CompensationCommandStatus.FAILED.code());
      assertThat(commandStatus(tenantA, fresh)).isEqualTo(CompensationCommandStatus.RUNNING.code());
      assertThat(commandStatus(tenantB, terminal))
          .isEqualTo(CompensationCommandStatus.SUCCESS.code());
      assertThat(errorCode(tenantA, staleA)).isEqualTo("STALE_RUNNING_TIMEOUT");
      assertThat(errorCode(tenantB, staleB)).isEqualTo("STALE_RUNNING_TIMEOUT");
    } finally {
      jdbcTemplate.update(
          deleteCompensationCommandSql, tenantA, tenantB);
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

    return jdbcTemplate.queryForObject(
        selectCommandStatusSql,
        String.class,
        tenantId,
        commandNo);
  }

  private String errorCode(String tenantId, String commandNo) {
    final String selectErrorCodeSql = """
        select error_code from batch.compensation_command where tenant_id = ? and command_no = ?
    """;

    return jdbcTemplate.queryForObject(
        selectErrorCodeSql,
        String.class,
        tenantId,
        commandNo);
  }
}
