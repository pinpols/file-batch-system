package io.github.pinpols.batch.orchestrator.application.engine;

import static io.github.pinpols.batch.orchestrator.application.engine.CountContinuityFixtures.accountedInput;
import static io.github.pinpols.batch.orchestrator.application.engine.CountContinuityFixtures.output;
import static io.github.pinpols.batch.orchestrator.application.engine.CountContinuityFixtures.policy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("连续性核对的真实 PostgreSQL 事务、重复回报和重试身份")
@ExtendWith(OutputCaptureExtension.class)
class CountContinuityOutboxIntegrationTest extends AbstractIntegrationTest {
  @Autowired
  private CountContinuityOutboxService service;

  @Autowired
  private JdbcTemplate jdbc;

  @Autowired
  private PlatformTransactionManager transactionManager;

  @Test
  @DisplayName("同次执行重复核对只写一次，新执行独立记录，定义变更不改冻结核对规则")
  void shouldDeduplicateSameExecutionAndRetainRetry_whenDefinitionChanges(CapturedOutput captured) {
    final String updateWorkflowDefinitionSql = """
        update batch.workflow_definition set version = 2, enabled = false where id = ?
    """;
    final String selectNodeStatusSql = """
        select node_status from batch.workflow_node_run where id = ?
    """;
    final String selectStatementSql = """
              select (payload_json->>'nodeRunId')::bigint from batch.outbox_event
              where tenant_id = ? and aggregate_type = 'WORKFLOW_RUN' and aggregate_id = ? order by id
              """;

    withScenario(
        policy(0, ""),
        output("output", 1000, "version-1", "RECORDS"),
        output("input", 950, "version-1", "RECORDS"),
        scenario -> {
          jdbc.update(updateWorkflowDefinitionSql, scenario.definitionId());
          inTransaction(scenario, () -> {
            check(scenario);
            check(scenario);
          });

          assertThat(events(scenario)).hasSize(1);
          assertThat(statuses(scenario)).containsExactly("MISMATCH");
          assertThat(captured.getAll())
              .contains("count continuity mismatch (alert-only): workflowRunId=" + scenario.runId()
                  + ",");
          String selectedNodeStatus =
              jdbc.queryForObject(selectNodeStatusSql, String.class, scenario.currentId());
          assertThat(selectedNodeStatus).isEqualTo("SUCCESS");

          Long retryId = insertNode(scenario.runId(), "PROCESS", 2, currentOutput(scenario));
          inTransaction(scenario, () -> check(scenario));

          assertThat(events(scenario)).hasSize(2).doesNotHaveDuplicates();
          List<Long> recordedExecutions = jdbc.queryForList(
              selectStatementSql, Long.class, scenario.tenant(), scenario.runId());
          assertThat(recordedExecutions).containsExactly(scenario.currentId(), retryId);
        });
  }

  @Test
  @DisplayName("控制边的一行到六行不产生连续性告警，也不把范围不同称为匹配")
  void shouldProduceNoCheck_whenOnlyControlDependencyExists() {
    withScenario(
        Map.of(),
        output("output", 1, "file-1", "RECORDS"),
        output("input", 6, "tenant-table", "RECORDS"),
        scenario -> {
          inTransaction(scenario, () -> check(scenario));

          assertThat(events(scenario)).isEmpty();
        });
  }

  @Test
  @DisplayName("显式核对但数据范围不一致，落未验证审计，不发真实差异告警")
  void shouldPersistUnverifiedEvidence_whenDeclaredScopesDiffer() {
    final String selectEventTypeSql = """
              select event_type from batch.outbox_event where tenant_id = ? and aggregate_id = ?
              """;
    final String selectPayloadJsonSql = """
              select payload_json->>'reason' from batch.outbox_event where tenant_id = ? and aggregate_id = ?
              """;

    withScenario(
        policy(0, ""),
        output("output", 1, "file-1", "RECORDS"),
        output("input", 6, "tenant-table", "RECORDS"),
        scenario -> {
          inTransaction(scenario, () -> check(scenario));

          assertThat(statuses(scenario)).containsExactly("UNVERIFIED");
          String selectedEventType = jdbc.queryForObject(
              selectEventTypeSql, String.class, scenario.tenant(), scenario.runId());
          assertThat(selectedEventType).isEqualTo("workflow.node.count.continuity.checked.v1");
          String selectedPayloadJson = jdbc.queryForObject(
              selectPayloadJsonSql, String.class, scenario.tenant(), scenario.runId());
          assertThat(selectedPayloadJson).isEqualTo("SCOPE_MISMATCH");
        });
  }

  @Test
  @DisplayName("节点产出、核对事件和去重键一起回滚，原身份可再次成功提交")
  void shouldRollbackOutputEventAndDedupKey_whenReportTransactionAborts(CapturedOutput captured) {
    final String updateWorkflowNodeRunSql = """
        update batch.workflow_node_run set node_status = 'RUNNING', output = null where id = ?
    """;
    final String selectNodeStatusSql = """
        select node_status from batch.workflow_node_run where id = ?
    """;
    final String selectCountSql = """
        select count(*) from batch.outbox_event_dedup_key where tenant_id = ?
    """;

    withScenario(
        policy(0, ""),
        output("output", 1000, "version-1", "RECORDS"),
        output("input", 950, "version-1", "RECORDS"),
        scenario -> {
          String reported = currentOutput(scenario);
          jdbc.update(updateWorkflowNodeRunSql, scenario.currentId());
          assertThatThrownBy(() -> inTransaction(scenario, () -> {
                finishNode(scenario, reported);
                check(scenario);
                assertThat(captured.getAll())
                    .doesNotContain("count continuity mismatch (alert-only): workflowRunId="
                        + scenario.runId() + ",");
                throw new IllegalStateException("abort report transaction");
              }))
              .isInstanceOf(IllegalStateException.class)
              .hasMessage("abort report transaction");

          assertThat(events(scenario)).isEmpty();
          String selectedNodeStatus =
              jdbc.queryForObject(selectNodeStatusSql, String.class, scenario.currentId());
          assertThat(selectedNodeStatus).isEqualTo("RUNNING");
          assertThat(currentOutput(scenario)).isNull();
          Long selectedOutboxEventDedupKeyCount =
              jdbc.queryForObject(selectCountSql, Long.class, scenario.tenant());
          assertThat(selectedOutboxEventDedupKeyCount).isZero();

          inTransaction(scenario, () -> {
            finishNode(scenario, reported);
            check(scenario);
          });
          assertThat(statuses(scenario)).containsExactly("MISMATCH");
        });
  }

  @Test
  @DisplayName("脱离回报事务的核对被事务代理拒绝")
  void shouldRequireExistingTransaction_whenInvokedDirectly() {
    assertThatThrownBy(() -> service.checkContinuity(1L, "PROCESS", "{}"))
        .isInstanceOf(IllegalTransactionStateException.class);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @DisplayName("非零差额只有报告排除计数并保留取证引用才可匹配")
  void shouldRequireReportedAccounting_whenNonzeroDeltaIsDeclared(boolean hasEvidence) {
    final String selectPayloadJsonSql = """
            select payload_json #>> '{currentCountContext,exclusionEvidenceRef}' from batch.outbox_event
            where tenant_id = ? and aggregate_id = ?
            """;

    Map<String, Object> current = hasEvidence
        ? accountedInput(950, 50, "rejection-artifact:version-1:50")
        : output("input", 950, "version-1", "RECORDS");
    withScenario(
        policy(50, "documented rejection"),
        output("output", 1000, "version-1", "RECORDS"),
        current,
        scenario -> {
          inTransaction(scenario, () -> check(scenario));

          assertThat(statuses(scenario)).containsExactly(hasEvidence ? "MATCHED" : "UNVERIFIED");
          if (hasEvidence) {
            String selectedPayloadJson = jdbc.queryForObject(
                selectPayloadJsonSql, String.class, scenario.tenant(), scenario.runId());
            assertThat(selectedPayloadJson).isEqualTo("rejection-artifact:version-1:50");
          }
        });
  }

  private void withScenario(
      Map<String, Object> params,
      Map<String, Object> upstream,
      Map<String, Object> current,
      Consumer<Scenario> exercise) {
    final String deleteOutboxEventSql = """
            delete from batch.outbox_event where tenant_id = ? and aggregate_type = 'WORKFLOW_RUN'\s\
            and aggregate_id = ?
        """;
    final String deleteOutboxEventDedupKeySql = """
            delete from batch.outbox_event_dedup_key where tenant_id = ?
        """;
    final String deleteWorkflowNodeRunSql = """
        delete from batch.workflow_node_run where workflow_run_id = ?
    """;
    final String deleteWorkflowRunSql = """
        delete from batch.workflow_run where id = ?
    """;
    final String deleteWorkflowDefinitionSql = """
        delete from batch.workflow_definition where id = ?
    """;

    String tenant = "count_" + UUID.randomUUID().toString().replace("-", "");
    Scenario scenario = new TransactionTemplate(transactionManager)
        .execute(status -> createScenario(tenant, params, upstream, current));
    Long runId = scenario.runId();
    Long definitionId = scenario.definitionId();
    try {
      exercise.accept(scenario);
    } finally {
      jdbc.update(deleteOutboxEventSql, tenant, runId);
      jdbc.update(deleteOutboxEventDedupKeySql, tenant);
      jdbc.update(deleteWorkflowNodeRunSql, runId);
      jdbc.update(deleteWorkflowRunSql, runId);
      jdbc.update(deleteWorkflowDefinitionSql, definitionId);
    }
  }

  private Scenario createScenario(
      String tenant,
      Map<String, Object> params,
      Map<String, Object> upstream,
      Map<String, Object> current) {
    final String insertWorkflowDefinitionSql = """
        insert into batch.workflow_definition (tenant_id, workflow_code, workflow_name, workflow_type, version,\s\
        enabled)
        values (?, 'COUNT_CHECK', 'continuity check fixture', 'DAG', 1, true) returning id
        """;
    final String insertWorkflowRunSql = """
        insert into batch.workflow_run (tenant_id, workflow_definition_id, biz_date, run_status,\s\
        workflow_version_snapshot, execution_snapshot)
        values (?, ?, ?, 'RUNNING', 1, ?::jsonb) returning id
        """;

    Long definitionId = jdbc.queryForObject(insertWorkflowDefinitionSql, Long.class, tenant);
    String snapshot = CountContinuityFixtures.snapshot(tenant, definitionId, params, true);
    Long runId = jdbc.queryForObject(
        insertWorkflowRunSql,
        Long.class,
        tenant,
        definitionId,
        LocalDate.of(2026, 10, 16),
        snapshot);
    Long upstreamId = insertNode(runId, "IMPORT", 1, JsonUtils.toJson(upstream));
    Long currentId = insertNode(runId, "PROCESS", 1, JsonUtils.toJson(current));
    return new Scenario(tenant, definitionId, runId, upstreamId, currentId);
  }

  private Long insertNode(Long runId, String code, int runSeq, String output) {
    final String insertWorkflowNodeRunSql = """
        insert into batch.workflow_node_run (workflow_run_id, node_code, node_type, run_seq, node_status,\s\
        retry_count, output)
        values (?, ?, 'JOB', ?, 'SUCCESS', 0, ?::jsonb) returning id
        """;

    return jdbc.queryForObject(insertWorkflowNodeRunSql, Long.class, runId, code, runSeq, output);
  }

  private void inTransaction(Scenario scenario, Runnable action) {
    RlsTenantContextHolder.runWithTenant(
        scenario.tenant(),
        () -> new TransactionTemplate(transactionManager)
            .executeWithoutResult(status -> action.run()));
  }

  private void check(Scenario scenario) {
    String output = currentOutput(scenario);
    service.checkContinuity(scenario.runId(), "PROCESS", output);
  }

  private String currentOutput(Scenario scenario) {
    final String selectOutputSql = """
        select output::text from batch.workflow_node_run where workflow_run_id = ? and node_code = 'PROCESS'
        order by run_seq desc, id desc limit 1
        """;

    return jdbc.queryForObject(selectOutputSql, String.class, scenario.runId());
  }

  private void finishNode(Scenario scenario, String output) {
    final String updateWorkflowNodeRunSql = """
        update batch.workflow_node_run set node_status = 'SUCCESS', output = ?::jsonb where id = ?
    """;

    jdbc.update(updateWorkflowNodeRunSql, output, scenario.currentId());
  }

  private List<String> events(Scenario scenario) {
    final String selectEventKeySql = """
        select event_key from batch.outbox_event where tenant_id = ? and aggregate_type = 'WORKFLOW_RUN'
        and aggregate_id = ? order by id
        """;

    return jdbc.queryForList(selectEventKeySql, String.class, scenario.tenant(), scenario.runId());
  }

  private List<String> statuses(Scenario scenario) {
    final String selectPayloadJsonSql = """
        select payload_json->>'status' from batch.outbox_event where tenant_id = ?\s\
        and aggregate_type = 'WORKFLOW_RUN'
        and aggregate_id = ? order by id
        """;

    return jdbc.queryForList(
        selectPayloadJsonSql, String.class, scenario.tenant(), scenario.runId());
  }

  private record Scenario(
      String tenant, Long definitionId, Long runId, Long upstreamId, Long currentId) {}
}
