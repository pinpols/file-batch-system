package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.service.task.InstanceManagementApplicationService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunManagementApplicationService;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
class PauseResumeStateMachineIntegrationTest extends AbstractIntegrationTest {

  private static final String TENANT = "pause-it";
  private static final LocalDate BIZ_DATE = LocalDate.of(2026, 1, 15);

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private InstanceManagementApplicationService instanceService;
  @Autowired private WorkflowRunManagementApplicationService workflowRunService;

  @Test
  void jobInstancePauseResumeUsesRealDbCasAndDoesNotReviveTerminalState() {
    Long runningId = insertJobInstance("RUNNING");

    assertThat(instanceService.pause(TENANT, runningId)).containsEntry("status", "PAUSED");
    assertThat(statusOfJobInstance(runningId)).isEqualTo("PAUSED");

    assertThat(instanceService.resume(TENANT, runningId)).containsEntry("status", "RUNNING");
    assertThat(statusOfJobInstance(runningId)).isEqualTo("RUNNING");

    Long terminalId = insertJobInstance("SUCCESS");
    assertThatThrownBy(() -> instanceService.resume(TENANT, terminalId))
        .isInstanceOf(BizException.class);
    assertThat(statusOfJobInstance(terminalId)).isEqualTo("SUCCESS");
  }

  @Test
  void workflowRunPauseResumeUsesRealDbCasAndDoesNotReviveTerminalState() {
    Long runningId = insertWorkflowRun("RUNNING");

    assertThat(workflowRunService.pause(TENANT, runningId)).containsEntry("status", "PAUSED");
    assertThat(statusOfWorkflowRun(runningId)).isEqualTo("PAUSED");

    assertThat(workflowRunService.resume(TENANT, runningId)).containsEntry("status", "RUNNING");
    assertThat(statusOfWorkflowRun(runningId)).isEqualTo("RUNNING");

    Long terminalId = insertWorkflowRun("SUCCESS");
    assertThatThrownBy(() -> workflowRunService.resume(TENANT, terminalId))
        .isInstanceOf(BizException.class);
    assertThat(statusOfWorkflowRun(terminalId)).isEqualTo("SUCCESS");
  }

  private Long insertJobInstance(String status) {
    String suffix = Long.toUnsignedString(System.nanoTime());
    String jobCode = "JOB-" + suffix;
    Long jobDefId =
        jdbcTemplate.queryForObject(
            """
            insert into batch.job_definition (
              tenant_id, job_code, job_name, job_type, schedule_type, timezone,
              enabled, created_by, updated_by
            ) values (?, ?, ?, 'GENERAL', 'MANUAL', 'UTC', true, 'it', 'it')
            returning id
            """,
            Long.class,
            TENANT,
            jobCode,
            "job " + suffix);
    Long triggerRequestId =
        jdbcTemplate.queryForObject(
            """
            insert into batch.trigger_request (
              tenant_id, request_id, trigger_type, job_code, dedup_key, request_status
            ) values (?, ?, 'API', ?, ?, 'LAUNCHED')
            returning id
            """,
            Long.class,
            TENANT,
            "REQ-" + suffix,
            jobCode,
            "TR-DEDUP-" + suffix);
    return jdbcTemplate.queryForObject(
        """
        insert into batch.job_instance (
          tenant_id, job_definition_id, trigger_request_id, job_code, instance_no, biz_date, trigger_type,
          instance_status, queue_code, worker_group, priority, dedup_key, run_attempt, version,
          expected_partition_count, success_partition_count, failed_partition_count,
          params_snapshot
        ) values (?, ?, ?, ?, ?, ?, 'API', ?, 'q', 'wg', 5, ?, 1, 0, 0, 0, 0, '{}'::jsonb)
        returning id
        """,
        Long.class,
        TENANT,
        jobDefId,
        triggerRequestId,
        jobCode,
        "INST-" + suffix,
        BIZ_DATE,
        status,
        "DEDUP-" + suffix);
  }

  private Long insertWorkflowRun(String status) {
    String suffix = Long.toUnsignedString(System.nanoTime());
    Long workflowDefId =
        jdbcTemplate.queryForObject(
            """
            insert into batch.workflow_definition (
              tenant_id, workflow_code, workflow_name, workflow_type, version, enabled
            ) values (?, ?, ?, 'DAG', 1, true)
            returning id
            """,
            Long.class,
            TENANT,
            "WF-" + suffix,
            "workflow " + suffix);
    return jdbcTemplate.queryForObject(
        """
        insert into batch.workflow_run (
          tenant_id, workflow_definition_id, biz_date, run_status, current_node_code, trace_id
        ) values (?, ?, ?, ?, 'N1', ?)
        returning id
        """,
        Long.class,
        TENANT,
        workflowDefId,
        BIZ_DATE,
        status,
        "trace-" + suffix);
  }

  private String statusOfJobInstance(Long id) {
    return jdbcTemplate.queryForObject(
        "select instance_status from batch.job_instance where tenant_id = ? and id = ?",
        String.class,
        TENANT,
        id);
  }

  private String statusOfWorkflowRun(Long id) {
    return jdbcTemplate.queryForObject(
        "select run_status from batch.workflow_run where tenant_id = ? and id = ?",
        String.class,
        TENANT,
        id);
  }
}
