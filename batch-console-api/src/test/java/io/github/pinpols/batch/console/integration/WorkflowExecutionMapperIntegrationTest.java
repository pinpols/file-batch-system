package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowExecutionMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Transactional
@DisplayName("运行冻结图查询的真实数据库映射与租户隔离")
class WorkflowExecutionMapperIntegrationTest extends AbstractIntegrationTest {
  private final JdbcTemplate jdbc;

  private final WorkflowExecutionMapper mapper;

  @Autowired
  WorkflowExecutionMapperIntegrationTest(JdbcTemplate jdbc, WorkflowExecutionMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  @Test
  @DisplayName("重试按序号取最新、等待节点保留，更新当前定义不改变运行冻结图")
  void shouldReadFrozenRunAndLatestAttempts_whenCurrentDefinitionChanges() {
    String tenant = "progress-" + UUID.randomUUID();
    mapper.setTenantContext(tenant);
    String insertJobDefinitionSql = """
        insert into batch.job_definition
          (tenant_id, job_code, job_name, job_type, schedule_type, timezone)
        values (?, 'PROGRESS_JOB', 'Progress job', 'GENERAL', 'MANUAL', 'Asia/Shanghai')
        returning id
        """;
    Long jobDefinitionId = jdbc.queryForObject(insertJobDefinitionSql, Long.class, tenant);
    String instanceNo = "progress-instance-" + UUID.randomUUID();
    String insertJobInstanceSql = """
        insert into batch.job_instance
          (tenant_id, job_definition_id, job_code, instance_no, biz_date,
           trigger_type, instance_status, priority, dedup_key)
        values (?, ?, 'PROGRESS_JOB', ?, current_date, 'MANUAL', 'RUNNING', 5, ?)
        returning id
        """;
    Long jobInstanceId = jdbc.queryForObject(
        insertJobInstanceSql,
        Long.class,
        tenant,
        jobDefinitionId,
        instanceNo,
        tenant + ":" + instanceNo);
    String insertDefinitionSql = """
        insert into batch.workflow_definition
          (tenant_id, workflow_code, workflow_name, workflow_type, version, enabled)
        values (?, 'PROGRESS_TEST', 'Before', 'DAG', 1, true) returning id
        """;
    Long definitionId = jdbc.queryForObject(insertDefinitionSql, Long.class, tenant);
    String frozen = """
        {"schemaVersion":2,"workflowDefinitionId":%d,"workflowVersion":1,
         "nodes":[{"nodeCode":"A","nodeType":"JOB"}],"edges":[]}
        """.formatted(definitionId);
    String insertRunSql = """
        insert into batch.workflow_run
          (tenant_id, workflow_definition_id, workflow_version_snapshot, run_status, biz_date, execution_snapshot, started_at)
        values (?, ?, 1, 'RUNNING', current_date, ?::jsonb, timestamptz '2026-10-26 02:00:00+00') returning id
        """;
    Long runId = jdbc.queryForObject(insertRunSql, Long.class, tenant, definitionId, frozen);
    String insertNodeSql = """
        insert into batch.workflow_node_run
          (workflow_run_id, node_code, node_type, run_seq, node_status, job_instance_id)
        values (?, 'A', 'JOB', ?, ?, ?) returning id
        """;
    Long latestId =
        jdbc.queryForObject(insertNodeSql, Long.class, runId, 2, "READY", jobInstanceId);
    // 后插入旧重试，确保不能用最大ID代表最新尝试。
    jdbc.queryForObject(insertNodeSql, Long.class, runId, 1, "FAILED", null);
    String mutateDefinitionSql =
        "update batch.workflow_definition set workflow_name='After', version=2 where id=?";
    jdbc.update(mutateDefinitionSql, definitionId);

    var run = mapper.selectSnapshot(tenant, runId);
    assertThat(run.getTenantId()).isEqualTo(tenant);
    assertThat(run.getWorkflowVersionSnapshot()).isEqualTo(1);
    assertThat(run.getStartedAt()).isEqualTo(Instant.parse("2026-10-26T02:00:00Z"));
    assertThat(run.getFinishedAt()).isNull();
    assertThat(run.getExecutionSnapshot()).contains("\"workflowVersion\": 1");
    var nodes = mapper.selectLatestNodes(tenant, runId);
    assertThat(nodes).singleElement().satisfies(node -> {
      assertThat(node.getId()).isEqualTo(latestId);
      assertThat(node.getRunSeq()).isEqualTo(2);
      assertThat(node.getNodeStatus()).isEqualTo("READY");
      assertThat(node.getJobInstanceId()).isEqualTo(jobInstanceId);
      assertThat(node.getJobInstanceStatus()).isEqualTo("RUNNING");
      assertThat(node.getCreatedTasks()).isZero();
      assertThat(node.getFinishedTasks()).isZero();
      assertThat(node.getExpectedPartitions()).isZero();
    });
    String finishNodeSql = "update batch.workflow_node_run set node_status='SUCCESS' where id=?";
    String finishJobSql =
        "update batch.job_instance set instance_status='SUCCESS' where tenant_id=? and id=?";
    String finishRunSql = """
        update batch.workflow_run set run_status='SUCCESS', finished_at=timestamptz '2026-10-26 02:01:00+00'
        where tenant_id=? and id=?
        """;
    jdbc.update(finishNodeSql, latestId);
    jdbc.update(finishJobSql, tenant, jobInstanceId);
    int finishedRuns = jdbc.update(finishRunSql, tenant, runId);
    assertThat(finishedRuns).isEqualTo(1);
    // JDBC更新不刷新MyBatis会话缓存；与下一次服务读取一样重新设置租户查询上下文。
    mapper.setTenantContext(tenant);
    var completedRun = mapper.selectSnapshot(tenant, runId);
    assertThat(completedRun.getStartedAt()).isEqualTo(Instant.parse("2026-10-26T02:00:00Z"));
    assertThat(completedRun.getFinishedAt()).isEqualTo(Instant.parse("2026-10-26T02:01:00Z"));
    assertThat(mapper.selectSnapshot("other-tenant", runId)).isNull();
    assertThat(mapper.selectLatestNodes("other-tenant", runId)).isEmpty();
  }
}
