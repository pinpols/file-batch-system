package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.sql.SQLException;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** V310 tenant reference and request retention behavior for batch day waiting launches. */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
@DisplayName("批量日等待启动项对原触发请求的租户引用与删除保护")
class BatchDayWaitingLaunchReferenceIntegrationTest extends AbstractIntegrationTest {

  private final JdbcTemplate jdbcTemplate;

  @Autowired
  BatchDayWaitingLaunchReferenceIntegrationTest(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Test
  @DisplayName("拒绝将等待项关联到另一租户的同名触发请求")
  void shouldRejectWaitingLaunchWhoseTenantDiffersFromRequest() {
    insertRequest("parent-tenant", "same-request-id");

    assertThatThrownBy(() -> insertWaiting("child-tenant", "same-request-id"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasRootCauseInstanceOf(SQLException.class);
  }

  @Test
  @DisplayName("等待项仍存在时阻止清理原触发请求")
  void shouldRetainTriggerRequestWhileWaitingLaunchExists() {
    insertRequest("retention-tenant", "retained-request-id");
    insertWaiting("retention-tenant", "retained-request-id");

    assertThatThrownBy(() -> jdbcTemplate.update(
            "delete from batch.trigger_request where tenant_id=? and request_id=?",
            "retention-tenant",
            "retained-request-id"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasRootCauseInstanceOf(SQLException.class);
  }

  @Test
  @DisplayName("等待项仍存在时阻止修改原触发请求的租户键")
  void shouldProtectTriggerRequestTenantKeyWhileWaitingLaunchExists() {
    insertRequest("update-tenant", "immutable-request-id");
    insertWaiting("update-tenant", "immutable-request-id");

    assertThatThrownBy(() -> jdbcTemplate.update(
            "update batch.trigger_request set tenant_id=? where tenant_id=? and request_id=?",
            "changed-tenant",
            "update-tenant",
            "immutable-request-id"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasRootCauseInstanceOf(SQLException.class);
  }

  private void insertRequest(String tenantId, String requestId) {
    Long definitionId = jdbcTemplate.queryForObject(
        """
        insert into batch.workflow_definition (
            tenant_id, workflow_code, workflow_name, workflow_type, version, enabled, calendar_code
        ) values (?, ?, 'FK workflow', 'DAG', 1, false, 'CAL_FK') returning id
        """,
        Long.class,
        tenantId,
        "FK_" + tenantId.replace('-', '_'));
    Long workflowRunId = jdbcTemplate.queryForObject(
        """
        insert into batch.workflow_run (
            tenant_id, workflow_definition_id, biz_date, run_status
        ) values (?, ?, ?, 'RUNNING') returning id
        """,
        Long.class,
        tenantId,
        definitionId,
        LocalDate.of(2026, 10, 18));
    jdbcTemplate.update(
        """
        insert into batch.trigger_request (
            tenant_id, request_id, trigger_type, job_code, biz_date, dedup_key,
            request_status, parent_workflow_run_id, parent_node_code
        ) values (?, ?, 'EVENT', 'WAITING_JOB', ?, ?, 'WAITING', ?, 'WAITING_JOB')
        """,
        tenantId,
        requestId,
        LocalDate.of(2026, 10, 18),
        "dedup-" + tenantId + "-" + requestId,
        workflowRunId);
  }

  private void insertWaiting(String tenantId, String requestId) {
    jdbcTemplate.update(
        """
        insert into batch.batch_day_waiting_launch (
            tenant_id, calendar_code, job_code, biz_date, request_id, trigger_type,
            wait_reason, launch_payload, wait_status
        ) values (?, 'CAL', 'WAITING_JOB', ?, ?, 'SCHEDULED',
                 'PREVIOUS_BATCH_DAY_NOT_CLOSED', '{}'::jsonb, 'WAITING')
        """,
        tenantId,
        LocalDate.of(2026, 10, 18),
        requestId);
  }
}
