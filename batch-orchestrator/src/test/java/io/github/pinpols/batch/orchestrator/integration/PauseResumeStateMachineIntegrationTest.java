package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.service.task.InstanceManagementApplicationService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunManagementApplicationService;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture.WorkflowChildTriggerSeed;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.LocalDate;
import java.time.Month;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("作业实例与工作流运行的暂停恢复状态机,验证真实数据库条件更新下的状态流转与终态不可复活")
class PauseResumeStateMachineIntegrationTest extends AbstractIntegrationTest {

  private static final String TENANT = "pause-it";
  private static final LocalDate BIZ_DATE = LocalDate.of(2026, Month.JANUARY, 15);

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private InstanceManagementApplicationService instanceService;

  @Autowired
  private WorkflowRunManagementApplicationService workflowRunService;

  @Test
  @DisplayName("运行中的作业实例可暂停再恢复,而终态实例恢复被拒绝且状态保持不变")
  void shouldPauseResumeAndRejectTerminalRevive_whenJobInstanceRunning() {
    Long runningId = insertJobInstance("RUNNING");

    assertThat(instanceService.pause(TENANT, runningId).status()).isEqualTo("PAUSED");
    assertThat(statusOfJobInstance(runningId)).isEqualTo("PAUSED");

    assertThat(instanceService.resume(TENANT, runningId).status()).isEqualTo("RUNNING");
    assertThat(statusOfJobInstance(runningId)).isEqualTo("RUNNING");

    Long terminalId = insertJobInstance("SUCCESS");
    assertThatThrownBy(() -> instanceService.resume(TENANT, terminalId))
        .isInstanceOf(BizException.class);
    assertThat(statusOfJobInstance(terminalId)).isEqualTo("SUCCESS");
  }

  @Test
  @DisplayName("运行中的工作流运行可暂停再恢复,而终态运行恢复被拒绝且状态保持不变")
  void shouldPauseResumeAndRejectTerminalRevive_whenWorkflowRunRunning() {
    Long runningId = insertWorkflowRun("RUNNING");

    assertThat(workflowRunService.pause(TENANT, runningId).status()).isEqualTo("PAUSED");
    assertThat(statusOfWorkflowRun(runningId)).isEqualTo("PAUSED");

    assertThat(workflowRunService.resume(TENANT, runningId).status()).isEqualTo("RUNNING");
    assertThat(statusOfWorkflowRun(runningId)).isEqualTo("RUNNING");

    Long terminalId = insertWorkflowRun("SUCCESS");
    assertThatThrownBy(() -> workflowRunService.resume(TENANT, terminalId))
        .isInstanceOf(BizException.class);
    assertThat(statusOfWorkflowRun(terminalId)).isEqualTo("SUCCESS");
  }

  private Long insertJobInstance(String status) {
    String suffix = Long.toUnsignedString(System.nanoTime());
    String jobCode = "JOB-" + suffix;
    String insertJobDefinitionSql = """
            insert into batch.job_definition (
              tenant_id, job_code, job_name, job_type, schedule_type, timezone,
              enabled, created_by, updated_by
            ) values (?, ?, ?, 'GENERAL', 'MANUAL', 'UTC', true, 'it', 'it')
            returning id
            """;
    Long jobDefId = jdbcTemplate.queryForObject(
        insertJobDefinitionSql, Long.class, TENANT, jobCode, "job " + suffix);
    WorkflowChildTriggerSeed childRequest =
        LaunchIntegrationFixture.prepareWorkflowChildTriggerRequestForDefinition(
            jdbcTemplate, TENANT, jobDefId, jobCode, suffix, BIZ_DATE, TriggerType.API);
    String insertJobInstanceSql = """
        insert into batch.job_instance (
          tenant_id, job_definition_id, trigger_request_id, job_code, instance_no, biz_date, trigger_type,
          instance_status, queue_code, worker_group, priority, dedup_key, run_attempt, version,
          expected_partition_count, success_partition_count, failed_partition_count,
          params_snapshot
        ) values (?, ?, ?, ?, ?, ?, 'EVENT', ?, 'q', 'wg', 5, ?, 1, 0, 0, 0, 0, '{}'::jsonb)
        returning id
        """;
    return jdbcTemplate.queryForObject(
        insertJobInstanceSql,
        Long.class,
        TENANT,
        jobDefId,
        childRequest.triggerRequestId(),
        jobCode,
        "INST-" + suffix,
        BIZ_DATE,
        status,
        "DEDUP-" + suffix);
  }

  private Long insertWorkflowRun(String status) {
    String suffix = Long.toUnsignedString(System.nanoTime());
    String insertWorkflowDefinitionSql = """
            insert into batch.workflow_definition (
              tenant_id, workflow_code, workflow_name, workflow_type, version, enabled
            ) values (?, ?, ?, 'DAG', 1, true)
            returning id
            """;
    Long workflowDefId = jdbcTemplate.queryForObject(
        insertWorkflowDefinitionSql, Long.class, TENANT, "WF-" + suffix, "workflow " + suffix);
    String insertWorkflowRunSql = """
        insert into batch.workflow_run (
          tenant_id, workflow_definition_id, biz_date, run_status, current_node_code, trace_id
        ) values (?, ?, ?, ?, 'N1', ?)
        returning id
        """;
    return jdbcTemplate.queryForObject(
        insertWorkflowRunSql, Long.class, TENANT, workflowDefId, BIZ_DATE, status, "trace-" + suffix);
  }

  private String statusOfJobInstance(Long id) {
    String selectJobInstanceStatusSql =
        """
        select instance_status from batch.job_instance where tenant_id = ? and id = ?
    """;
    return jdbcTemplate.queryForObject(
        selectJobInstanceStatusSql,
        String.class,
        TENANT,
        id);
  }

  private String statusOfWorkflowRun(Long id) {
    String selectWorkflowRunStatusSql =
        """
        select run_status from batch.workflow_run where tenant_id = ? and id = ?
    """;
    return jdbcTemplate.queryForObject(
        selectWorkflowRunStatusSql,
        String.class,
        TENANT,
        id);
  }
}
