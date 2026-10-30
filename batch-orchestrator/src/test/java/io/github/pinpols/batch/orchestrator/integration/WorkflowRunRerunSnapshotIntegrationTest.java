package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.service.workflow.RerunJobDefinitionResolver;
import io.github.pinpols.batch.orchestrator.domain.entity.JobDefinitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowRunMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("RERUN 历史 Job 定义快照：PostgreSQL 精确查询与租户隔离")
class WorkflowRunRerunSnapshotIntegrationTest extends AbstractIntegrationTest {

  private final WorkflowRunMapper workflowRunMapper;

  private final JdbcTemplate jdbcTemplate;

  @Autowired
  WorkflowRunRerunSnapshotIntegrationTest(
      WorkflowRunMapper workflowRunMapper, JdbcTemplate jdbcTemplate) {
    this.workflowRunMapper = workflowRunMapper;
    this.jdbcTemplate = jdbcTemplate;
  }

  @Test
  @DisplayName("同一 Job 多版本快照按版本精确命中最新记录且不能跨租户读取")
  void shouldSelectExactVersionSnapshotWithinTenant() {
    final String insertJobDefinitionSql = """
          insert into batch.job_definition (
              tenant_id, job_code, job_name, job_type, schedule_type, timezone, trigger_mode,
              priority, shard_strategy, retry_policy, version, enabled)
          values (?, ?, 'rerun test job', 'GENERAL', 'MANUAL', 'UTC', 'MANUAL', 5, 'NONE', 'NONE', 2, true)
          returning id
          """;
    final String insertBusinessCalendarSql = """
        insert into batch.business_calendar (tenant_id,calendar_code,calendar_name,timezone,enabled)\s\
        values (?,'RERUN_CAL','RERUN snapshot calendar','UTC',true)
    """;
    final String insertWorkflowDefinitionSql = """
          insert into batch.workflow_definition (
              tenant_id, workflow_code, workflow_name, workflow_type, version, enabled,
              single_job_definition_id,calendar_code)
          values (?, ?, 'rerun test workflow', 'DAG', 1, true, ?,'RERUN_CAL')
          returning id
          """;
    final String deleteWorkflowNodeRunSql = """
        delete from batch.workflow_node_run where workflow_run_id in (select id from batch.workflow_run\s\
        where tenant_id = ? and workflow_definition_id = ?)\
        """;
    final String deleteJobInstanceSql = """
      delete from batch.job_instance where tenant_id=? and id=?
  """;
    final String deleteTriggerRequestSql = """
      delete from batch.trigger_request where tenant_id=? and parent_workflow_run_id is not null
  """;
    final String deleteWorkflowRunSql = """
      delete from batch.workflow_run where tenant_id=? and workflow_definition_id=?
  """;
    final String deleteTriggerRequestSql2 = """
      delete from batch.trigger_request where tenant_id=?
  """;
    final String deleteWorkflowDefinitionSql = """
        delete from batch.workflow_definition where tenant_id = ? and id = ?
    """;
    final String deleteJobDefinitionSql = """
        delete from batch.job_definition where tenant_id = ? and id = ?
    """;
    final String deleteBatchDayInstanceSql = """
        delete from batch.batch_day_instance where tenant_id=?
    """;
    final String deleteBusinessCalendarSql = """
        delete from batch.business_calendar where tenant_id=? and calendar_code='RERUN_CAL'
    """;

    String tenant = "rerun_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    String jobCode = "RERUN_JOB";
    Long jobDefinitionId =
        jdbcTemplate.queryForObject(insertJobDefinitionSql, Long.class, tenant, jobCode);
    jdbcTemplate.update(insertBusinessCalendarSql, tenant);
    Long workflowDefinitionId = jdbcTemplate.queryForObject(
        insertWorkflowDefinitionSql, Long.class, tenant, "WF_" + jobCode, jobDefinitionId);

    Long sourceJobId = null;
    try {
      insertRun(
          tenant, workflowDefinitionId, jobDefinitionId, jobCode, 1, "v1-first", jobDefinitionId);
      insertRun(tenant, workflowDefinitionId, jobDefinitionId, jobCode, 2, "v2", jobDefinitionId);
      Long latestVersionOneRun =
          insertRun(tenant, workflowDefinitionId, jobDefinitionId, jobCode, 1, "v1-latest", null);
      sourceJobId = insertSnapshotSource(
          tenant, workflowDefinitionId, jobDefinitionId, jobCode, latestVersionOneRun);

      var selected =
          workflowRunMapper.selectLatestJobDefinitionSnapshot(tenant, jobDefinitionId, jobCode, 1);

      assertThat(selected).isNotNull();
      assertThat(selected.getId()).isEqualTo(latestVersionOneRun);
      assertThat(workflowRunMapper.selectLatestJobDefinitionSnapshot(
              "other_" + tenant, jobDefinitionId, jobCode, 1))
          .isNull();
      assertThat(workflowRunMapper.selectLatestJobDefinitionSnapshot(
              tenant, jobDefinitionId, jobCode, 3))
          .isNull();

      JobInstanceMapper jobInstances = mock(JobInstanceMapper.class);
      JobInstanceEntity source = new JobInstanceEntity();
      source.setId(sourceJobId);
      source.setTenantId(tenant);
      source.setJobCode(jobCode);
      source.setJobDefinitionId(jobDefinitionId);
      source.setJobDefinitionVersion(1);
      when(jobInstances.selectById(tenant, sourceJobId)).thenReturn(source);
      RerunJobDefinitionResolver resolver =
          new RerunJobDefinitionResolver(jobInstances, workflowRunMapper);
      JobDefinitionEntity latestDefinition = JobDefinitionEntity.builder()
          .id(jobDefinitionId)
          .tenantId(tenant)
          .jobCode(jobCode)
          .version(2)
          .jobType("GENERAL")
          .enabled(true)
          .build();

      assertThat(resolver
              .resolve(rerunRequest(tenant, sourceJobId, Map.of()), jobCode, latestDefinition)
              .version())
          .isEqualTo(1);
      assertThat(resolver
              .resolve(
                  rerunRequest(
                      tenant,
                      sourceJobId,
                      Map.of(
                          "_rerunConfigVersionPolicy",
                          "USE_SPECIFIED_VERSION",
                          "_rerunConfigVersion",
                          1)),
                  jobCode,
                  latestDefinition)
              .version())
          .isEqualTo(1);
      assertThat(resolver.resolve(
              rerunRequest(
                  tenant, sourceJobId, Map.of("_rerunConfigVersionPolicy", "USE_LATEST_CONFIG")),
              jobCode,
              latestDefinition))
          .isSameAs(latestDefinition);
    } finally {
      jdbcTemplate.update(deleteWorkflowNodeRunSql, tenant, workflowDefinitionId);
      jdbcTemplate.update(deleteJobInstanceSql, tenant, sourceJobId);
      jdbcTemplate.update(deleteTriggerRequestSql, tenant);
      jdbcTemplate.update(deleteWorkflowRunSql, tenant, workflowDefinitionId);
      jdbcTemplate.update(deleteTriggerRequestSql2, tenant);
      jdbcTemplate.update(deleteWorkflowDefinitionSql, tenant, workflowDefinitionId);
      jdbcTemplate.update(deleteJobDefinitionSql, tenant, jobDefinitionId);
      jdbcTemplate.update(deleteBatchDayInstanceSql, tenant);
      jdbcTemplate.update(deleteBusinessCalendarSql, tenant);
    }
  }

  @Test
  @DisplayName("从失败 Workflow 的 FAILURE 分支来源重放时固定该来源的 Job 版本")
  void shouldPinRerunToFailureBranchSourceVersion() {
    final String insertJobDefinitionSql = """
          insert into batch.job_definition (
              tenant_id, job_code, job_name, job_type, schedule_type, timezone, trigger_mode,
              priority, shard_strategy, retry_policy, version, enabled)
          values (?, ?, 'failure cleanup job', 'GENERAL', 'MANUAL', 'UTC', 'MANUAL', 5, 'NONE', 'NONE', 2, true)
          returning id
          """;
    final String insertBusinessCalendarSql = """
          insert into batch.business_calendar (tenant_id,calendar_code,calendar_name,timezone,enabled)
          values (?,'RERUN_FAILURE_CAL','rerun failure calendar','UTC',true)
          """;
    final String insertWorkflowDefinitionSql = """
          insert into batch.workflow_definition (
              tenant_id, workflow_code, workflow_name, workflow_type, version, enabled,
              single_job_definition_id,calendar_code)
          values (?, ?, 'failure replay workflow', 'DAG', 2, true, ?,'RERUN_FAILURE_CAL')
          returning id
          """;
    final String selectExecutionSnapshotSql = """
            select execution_snapshot->'edges' @>
                '[{"edgeType":"FAILURE","fromNodeCode":"PRIMARY_JOB","toNodeCode":"FAILURE_CLEANUP"}]'::jsonb
            from batch.workflow_run where tenant_id=? and id=?
            """;
    final String selectNodeCodeSql = """
            select node_code from batch.workflow_node_run where workflow_run_id=? and job_instance_id=?
            """;
    final String deleteWorkflowNodeRunSql = """
        delete from batch.workflow_node_run where workflow_run_id in (select id from batch.workflow_run\s\
        where tenant_id=? and workflow_definition_id=?)\
        """;
    final String deleteJobInstanceSql = """
      delete from batch.job_instance where tenant_id=? and id=?
  """;
    final String deleteTriggerRequestSql = """
      delete from batch.trigger_request where tenant_id=? and parent_workflow_run_id is not null
  """;
    final String deleteWorkflowRunSql = """
      delete from batch.workflow_run where tenant_id=? and workflow_definition_id=?
  """;
    final String deleteTriggerRequestSql2 = """
      delete from batch.trigger_request where tenant_id=?
  """;
    final String deleteWorkflowDefinitionSql = """
        delete from batch.workflow_definition where tenant_id=? and id=?
    """;
    final String deleteJobDefinitionSql = """
        delete from batch.job_definition where tenant_id=? and id=?
    """;
    final String deleteBatchDayInstanceSql = """
        delete from batch.batch_day_instance where tenant_id=?
    """;
    final String deleteBusinessCalendarSql = """
        delete from batch.business_calendar where tenant_id=? and calendar_code='RERUN_FAILURE_CAL'
    """;

    String tenant =
        "rerun_failure_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    String jobCode = "FAILURE_CLEANUP_JOB";
    Long jobDefinitionId =
        jdbcTemplate.queryForObject(insertJobDefinitionSql, Long.class, tenant, jobCode);
    jdbcTemplate.update(insertBusinessCalendarSql, tenant);
    Long workflowDefinitionId = jdbcTemplate.queryForObject(
        insertWorkflowDefinitionSql, Long.class, tenant, "WF_" + jobCode, jobDefinitionId);

    Long sourceRunId = null;
    Long sourceJobId = null;
    try {
      sourceRunId = insertFailureBranchRun(tenant, workflowDefinitionId, jobDefinitionId, jobCode);
      sourceJobId = insertFailureBranchJobInstance(
          tenant, workflowDefinitionId, jobDefinitionId, jobCode, sourceRunId);
      // occurrence 账本作为调度记录：每条旧 PLANNED 触发分别提升，
      insertRun(tenant, workflowDefinitionId, jobDefinitionId, jobCode, 2, "latest-v2", null);

      var owner = workflowRunMapper.selectByNodeJobInstanceId(tenant, sourceJobId);
      assertThat(owner).isNotNull();
      assertThat(owner.getId()).isEqualTo(sourceRunId);
      assertThat(owner.getRunStatus()).isEqualTo("FAILED");
      Boolean selectedExecutionSnapshot = jdbcTemplate.queryForObject(
          selectExecutionSnapshotSql, Boolean.class, tenant, sourceRunId);
      assertThat(selectedExecutionSnapshot).isTrue();
      String selectedNodeCode =
          jdbcTemplate.queryForObject(selectNodeCodeSql, String.class, sourceRunId, sourceJobId);
      assertThat(selectedNodeCode).isEqualTo("FAILURE_CLEANUP");

      JobInstanceMapper jobInstances = mock(JobInstanceMapper.class);
      JobInstanceEntity source = new JobInstanceEntity();
      source.setId(sourceJobId);
      source.setTenantId(tenant);
      source.setJobCode(jobCode);
      source.setJobDefinitionId(jobDefinitionId);
      source.setJobDefinitionVersion(1);
      when(jobInstances.selectById(tenant, sourceJobId)).thenReturn(source);
      RerunJobDefinitionResolver resolver =
          new RerunJobDefinitionResolver(jobInstances, workflowRunMapper);
      JobDefinitionEntity latestDefinition = JobDefinitionEntity.builder()
          .id(jobDefinitionId)
          .tenantId(tenant)
          .jobCode(jobCode)
          .version(2)
          .jobType("GENERAL")
          .enabled(true)
          .build();

      assertThat(resolver
              .resolve(rerunRequest(tenant, sourceJobId, Map.of()), jobCode, latestDefinition)
              .version())
          .isEqualTo(1);
    } finally {
      jdbcTemplate.update(deleteWorkflowNodeRunSql, tenant, workflowDefinitionId);
      if (sourceJobId != null) {
        jdbcTemplate.update(deleteJobInstanceSql, tenant, sourceJobId);
      }
      jdbcTemplate.update(deleteTriggerRequestSql, tenant);
      jdbcTemplate.update(deleteWorkflowRunSql, tenant, workflowDefinitionId);
      jdbcTemplate.update(deleteTriggerRequestSql2, tenant);
      jdbcTemplate.update(deleteWorkflowDefinitionSql, tenant, workflowDefinitionId);
      jdbcTemplate.update(deleteJobDefinitionSql, tenant, jobDefinitionId);
      jdbcTemplate.update(deleteBatchDayInstanceSql, tenant);
      jdbcTemplate.update(deleteBusinessCalendarSql, tenant);
    }
  }

  private Long insertFailureBranchRun(
      String tenant, Long workflowDefinitionId, Long jobDefinitionId, String jobCode) {
    final String insertWorkflowRunSql = """
    insert into batch.workflow_run (
    tenant_id, workflow_definition_id, single_job_definition_id, biz_date,
    run_status, workflow_version_snapshot, execution_snapshot)
    values (?, ?, ?, ?, 'RUNNING', 1, ?::jsonb)
    returning id
    """;

    Long primaryJobDefinitionId = jobDefinitionId + 1_000_000_000L;
    Map<String, Object> primaryDefinition = Map.of(
        "id",
        primaryJobDefinitionId,
        "tenantId",
        tenant,
        "jobCode",
        "PRIMARY_JOB",
        "jobType",
        "GENERAL",
        "version",
        1,
        "defaultParams",
        Map.of("profile", "primary-v1"));
    Map<String, Object> cleanupDefinition = Map.of(
        "id",
        jobDefinitionId,
        "tenantId",
        tenant,
        "jobCode",
        jobCode,
        "jobType",
        "GENERAL",
        "version",
        1,
        "defaultParams",
        Map.of("profile", "failure-cleanup-v1"));
    String snapshot = JsonUtils.toJson(Map.of(
        "schemaVersion", 2,
        "workflowDefinitionId", workflowDefinitionId,
        "workflowVersion", 1,
        "nodes",
            List.of(
                Map.of(
                    "tenantId",
                    tenant,
                    "workflowDefinitionId",
                    workflowDefinitionId,
                    "nodeCode",
                    "PRIMARY_JOB",
                    "nodeType",
                    "JOB",
                    "relatedJobCode",
                    "PRIMARY_JOB"),
                Map.of(
                    "tenantId",
                    tenant,
                    "workflowDefinitionId",
                    workflowDefinitionId,
                    "nodeCode",
                    "FAILURE_CLEANUP",
                    "nodeType",
                    "JOB",
                    "relatedJobCode",
                    jobCode)),
        "edges",
            List.of(Map.of(
                "tenantId",
                tenant,
                "workflowDefinitionId",
                workflowDefinitionId,
                "fromNodeCode",
                "PRIMARY_JOB",
                "toNodeCode",
                "FAILURE_CLEANUP",
                "edgeType",
                "FAILURE",
                "enabled",
                true)),
        "initialNodes", List.of(),
        "jobDefinitions",
            Map.of(
                "PRIMARY_JOB",
                    Map.of(
                        "jobDefinitionId",
                        primaryJobDefinitionId,
                        "jobCode",
                        "PRIMARY_JOB",
                        "jobType",
                        "GENERAL",
                        "jobDefinitionVersion",
                        1,
                        "executionDefinition",
                        primaryDefinition),
                "FAILURE_CLEANUP",
                    Map.of(
                        "jobDefinitionId", jobDefinitionId,
                        "jobCode", jobCode,
                        "jobType", "GENERAL",
                        "jobDefinitionVersion", 1,
                        "executionDefinition", cleanupDefinition))));
    return jdbcTemplate.queryForObject(
        insertWorkflowRunSql,
        Long.class,
        tenant,
        workflowDefinitionId,
        jobDefinitionId,
        LocalDate.of(2026, 10, 1),
        snapshot);
  }

  private Long insertFailureBranchJobInstance(
      String tenant, Long workflowDefinitionId, Long jobDefinitionId, String jobCode, Long runId) {
    final String insertTriggerRequestSql = """
    insert into batch.trigger_request (
    tenant_id,request_id,trigger_type,workflow_definition_id,biz_date,dedup_key,request_status)
    values (?,?,'API',?,'2026-10-01',?,'LAUNCHED') returning id
    """;
    final String updateWorkflowRunSql = """
            update batch.workflow_run set trigger_request_id=? where tenant_id=? and id=?
        """;
    final String insertWorkflowNodeRunSql = """
    insert into batch.workflow_node_run (
    workflow_run_id,node_code,node_type,run_seq,node_status,retry_count,duration_ms)
    values (?,'PRIMARY_JOB','JOB',1,'FAILED',0,0)
    """;
    final String insertWorkflowNodeRunSql2 = """
        insert into batch.workflow_node_run (
            workflow_run_id,node_code,node_type,run_seq,node_status,retry_count,duration_ms)
        values (?,'FAILURE_CLEANUP','JOB',2,'SUCCESS',0,0) returning id
        """;
    final String insertTriggerRequestSql2 = """
        insert into batch.trigger_request (
            tenant_id,request_id,trigger_type,job_code,biz_date,dedup_key,request_status,
            parent_workflow_run_id,parent_node_code)
        values (?,?,'CATCH_UP',?,'2026-10-01',?,'LAUNCHED',?,'FAILURE_CLEANUP') returning id
        """;
    final String insertJobInstanceSql = """
    insert into batch.job_instance (
    tenant_id,job_definition_id,trigger_request_id,job_code,instance_no,biz_date,
    trigger_type,instance_status,dedup_key,job_definition_version)
    values (?,?,?,?,?,'2026-10-01','CATCH_UP','SUCCESS',?,1) returning id
    """;
    final String updateWorkflowNodeRunSql = """
            update batch.workflow_node_run set job_instance_id=? where id=?
        """;
    final String updateWorkflowRunSql2 = """
      update batch.workflow_run set run_status='FAILED' where tenant_id=? and id=?
  """;

    Long rootRequest = jdbcTemplate.queryForObject(
        insertTriggerRequestSql,
        Long.class,
        tenant,
        "failure-root-" + tenant,
        workflowDefinitionId,
        "failure-root-" + tenant);
    jdbcTemplate.update(updateWorkflowRunSql, rootRequest, tenant, runId);
    jdbcTemplate.update(insertWorkflowNodeRunSql, runId);
    Long nodeRunId = jdbcTemplate.queryForObject(insertWorkflowNodeRunSql2, Long.class, runId);
    Long childRequest = jdbcTemplate.queryForObject(
        insertTriggerRequestSql2,
        Long.class,
        tenant,
        "failure-child-" + tenant,
        jobCode,
        "failure-child-" + tenant,
        runId);
    Long jobInstanceId = jdbcTemplate.queryForObject(
        insertJobInstanceSql,
        Long.class,
        tenant,
        jobDefinitionId,
        childRequest,
        jobCode,
        "failure-cleanup-" + tenant,
        "failure-cleanup-" + tenant);
    jdbcTemplate.update(updateWorkflowNodeRunSql, jobInstanceId, nodeRunId);
    jdbcTemplate.update(updateWorkflowRunSql2, tenant, runId);
    return jobInstanceId;
  }

  /** 按有界分页处理，并保留原始触发时刻和已冻结的业务日期。 */
  private Long insertSnapshotSource(
      String tenant, Long workflow, Long definition, String jobCode, Long run) {
    final String insertTriggerRequestSql = """
    insert into batch.trigger_request\s\
    (tenant_id,request_id,trigger_type,workflow_definition_id,biz_date,dedup_key,request_status)
    values (?,?,'API',?,'2026-10-01',?,'LAUNCHED') returning id
    """;
    final String updateWorkflowRunSql = """
            update batch.workflow_run set trigger_request_id=?,run_status='RUNNING' where tenant_id=? and id=?
        """;
    final String insertWorkflowNodeRunSql = """
    insert into batch.workflow_node_run\s\
    (workflow_run_id,node_code,node_type,run_seq,node_status,retry_count,duration_ms)
    values (?,'JOB','JOB',1,'RUNNING',0,0) returning id
    """;
    final String insertTriggerRequestSql2 = """
        insert into batch.trigger_request\s\
        (tenant_id,request_id,trigger_type,job_code,biz_date,dedup_key,request_status,parent_workflow_run_id,parent_node_code)
        values (?,?,'EVENT',?,'2026-10-01',?,'LAUNCHED',?,'JOB') returning id
        """;
    final String insertJobInstanceSql = """
    insert into batch.job_instance (tenant_id,job_definition_id,trigger_request_id,job_code,instance_no,biz_date,
    trigger_type,instance_status,dedup_key,job_definition_version)
    values (?,?,?,?,?,'2026-10-01','EVENT','SUCCESS',?,1) returning id
    """;
    final String updateWorkflowNodeRunSql = """
            update batch.workflow_node_run set job_instance_id=?,node_status='SUCCESS' where id=?
        """;
    final String updateWorkflowRunSql2 = """
      update batch.workflow_run set run_status='SUCCESS' where tenant_id=? and id=?
  """;

    Long rootRequest = jdbcTemplate.queryForObject(
        insertTriggerRequestSql,
        Long.class,
        tenant,
        "snapshot-root-" + tenant,
        workflow,
        "snapshot-root-" + tenant);
    jdbcTemplate.update(updateWorkflowRunSql, rootRequest, tenant, run);
    Long node = jdbcTemplate.queryForObject(insertWorkflowNodeRunSql, Long.class, run);
    Long childRequest = jdbcTemplate.queryForObject(
        insertTriggerRequestSql2,
        Long.class,
        tenant,
        "snapshot-child-" + tenant,
        jobCode,
        "snapshot-child-" + tenant,
        run);
    Long job = jdbcTemplate.queryForObject(
        insertJobInstanceSql,
        Long.class,
        tenant,
        definition,
        childRequest,
        jobCode,
        "snapshot-source-" + tenant,
        "snapshot-source-" + tenant);
    jdbcTemplate.update(updateWorkflowNodeRunSql, job, node);
    jdbcTemplate.update(updateWorkflowRunSql2, tenant, run);
    return job;
  }

  private LaunchRequest rerunRequest(
      String tenant, Long sourceJobId, Map<String, Object> extraParams) {
    Map<String, Object> params = new LinkedHashMap<>(extraParams);
    params.put("operationType", "JOB_RERUN");
    params.put("parentInstanceId", sourceJobId);
    return LaunchRequest.builder()
        .tenantId(tenant)
        .requestId("rerun-request")
        .triggerType(TriggerType.CATCH_UP)
        .bizDate(LocalDate.of(2026, 10, 1))
        .params(params)
        .build();
  }

  private Long insertRun(
      String tenant,
      Long workflowDefinitionId,
      Long jobDefinitionId,
      String jobCode,
      int jobVersion,
      String profile,
      Long singleJobDefinitionId) {
    final String insertWorkflowRunSql = """
    insert into batch.workflow_run (
    tenant_id, workflow_definition_id, single_job_definition_id, biz_date,
    run_status, workflow_version_snapshot, execution_snapshot)
    values (?, ?, ?, ?, 'SUCCESS', 1, ?::jsonb)
    returning id
    """;

    String snapshot = JsonUtils.toJson(Map.of(
        "schemaVersion",
        2,
        "workflowDefinitionId",
        workflowDefinitionId,
        "workflowVersion",
        1,
        "nodes",
        List.of(Map.of(
            "tenantId", tenant,
            "workflowDefinitionId", workflowDefinitionId,
            "nodeCode", "JOB",
            "nodeType", "JOB",
            "relatedJobCode", jobCode)),
        "edges",
        List.of(),
        "initialNodes",
        List.of(),
        "jobDefinitions",
        Map.of(
            "JOB",
            Map.of(
                "jobDefinitionId",
                jobDefinitionId,
                "jobCode",
                jobCode,
                "jobType",
                "GENERAL",
                "jobDefinitionVersion",
                jobVersion,
                "executionDefinition",
                Map.of(
                    "id", jobDefinitionId,
                    "tenantId", tenant,
                    "jobCode", jobCode,
                    "jobType", "GENERAL",
                    "version", jobVersion,
                    "defaultParams", Map.of("profile", profile))))));
    return jdbcTemplate.queryForObject(
        insertWorkflowRunSql,
        Long.class,
        tenant,
        workflowDefinitionId,
        singleJobDefinitionId,
        LocalDate.of(2026, 10, 1),
        snapshot);
  }
}
