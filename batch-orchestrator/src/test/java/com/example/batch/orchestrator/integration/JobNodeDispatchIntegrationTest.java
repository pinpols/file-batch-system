package com.example.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.dto.LaunchResponse;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.orchestrator.BatchOrchestratorApplication;
import com.example.batch.orchestrator.application.service.TaskOutcomeService;
import com.example.batch.orchestrator.domain.command.TaskOutcomeCommand;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.service.LaunchService;
import com.example.batch.testing.AbstractIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 集成测试：工作流 DAG 中的 JOB 节点类型。
 *
 * <p>场景：父工作流包含一个引用子任务的 JOB 节点。 当父任务启动时，编排器应：
 *
 * <ol>
 *   <li>自动启动子 job instance。
 *   <li>在父任务中创建虚拟分区 + 任务（RUNNING）以跟踪子任务。
 *   <li>当子任务的 task 完成时，回信号给父虚拟任务， 使父 DAG 推进且父 job instance 达到 SUCCESS。
 * </ol>
 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
class JobNodeDispatchIntegrationTest extends AbstractIntegrationTest {

  private static final String TENANT = "t-job-node";
  private static final LocalDate BIZ_DATE = LocalDate.of(2026, 3, 1);

  @Autowired private LaunchService launchService;

  @Autowired private TaskOutcomeService taskOutcomeService;

  @Autowired private JobInstanceMapper jobInstanceMapper;

  @Autowired private JdbcTemplate jdbcTemplate;

  // ── helpers ──────────────────────────────────────────────────────────────

  /** 初始化子任务（单 TASK Worker 的简单任务，空工作流 DAG）。 */
  private String seedChildJob(String workerGroup) {
    String suffix = Long.toUnsignedString(System.nanoTime());
    String childCode = "CHILD_" + suffix;

    jdbcTemplate.update(
        """
        insert into batch.job_definition (
            tenant_id, job_code, job_name, job_type, biz_type, schedule_type, timezone,
            priority, queue_code, worker_group, trigger_mode, dag_enabled, shard_strategy,
            retry_policy, retry_max_count, timeout_seconds, enabled, version
        ) values (?, ?, ?, 'GENERAL', 'IT', 'MANUAL', 'UTC',
            5, 'q-it', ?, 'API', false, 'NONE',
            'NONE', 0, 0, true, 1)
        """,
        TENANT,
        childCode,
        "child job " + childCode,
        workerGroup);

    jdbcTemplate.update(
        """
        insert into batch.workflow_definition (
            tenant_id, workflow_code, workflow_name, workflow_type, version, enabled
        ) values (?, ?, 'child wf', 'DAG', 1, true)
        """,
        TENANT,
        childCode);

    jdbcTemplate.update(
        """
        insert into batch.worker_registry (
            tenant_id, worker_code, worker_group, capability_tags, status, heartbeat_at, current_load
        ) values (?, ?, ?, '{}'::jsonb, 'ONLINE', now(), 0)
        """,
        TENANT,
        "wk-child-" + suffix,
        workerGroup);

    return childCode;
  }

  /**
   * 初始化父任务，其工作流 DAG 为：START → JOB_NODE_1（JOB 类型）→ END。 JOB 节点的 {@code related_job_code} 指向 {@code
   * childJobCode}。
   *
   * @return 调用 {@link LaunchService#launch} 所需的触发请求详情。
   */
  private ParentSeed seedParentJob(String childJobCode) {
    String suffix = Long.toUnsignedString(System.nanoTime());
    String parentCode = "PARENT_" + suffix;
    String requestId = "req-parent-" + suffix;
    String dedupKey = "dedup-parent-" + suffix;

    jdbcTemplate.update(
        """
        insert into batch.job_definition (
            tenant_id, job_code, job_name, job_type, biz_type, schedule_type, timezone,
            priority, queue_code, worker_group, trigger_mode, dag_enabled, shard_strategy,
            retry_policy, retry_max_count, timeout_seconds, enabled, version
        ) values (?, ?, ?, 'WORKFLOW', 'IT', 'MANUAL', 'UTC',
            5, 'q-it', 'NONE', 'API', true, 'NONE',
            'NONE', 0, 0, true, 1)
        """,
        TENANT,
        parentCode,
        "parent job " + parentCode);

    // workflow_definition for the parent
    Long parentWfDefId =
        jdbcTemplate.queryForObject(
            """
            insert into batch.workflow_definition (
                tenant_id, workflow_code, workflow_name, workflow_type, version, enabled
            ) values (?, ?, 'parent wf', 'DAG', 1, true)
            returning id
            """,
            Long.class,
            TENANT,
            parentCode);

    // JOB node: node_type = 'JOB', related_job_code = childJobCode
    jdbcTemplate.update(
        """
        insert into batch.workflow_node (
            workflow_definition_id, node_code, node_name, node_type,
            related_job_code, node_order, retry_policy, retry_max_count,
            timeout_seconds, enabled
        ) values (?, 'JOB_NODE_1', 'Child Job Node', 'JOB',
            ?, 1, 'NONE', 0, 0, true)
        """,
        parentWfDefId,
        childJobCode);

    // 边：START → JOB_NODE_1（ALWAYS），JOB_NODE_1 → END（SUCCESS）
    jdbcTemplate.update(
        """
        insert into batch.workflow_edge (
            workflow_definition_id, from_node_code, to_node_code, edge_type, enabled
        ) values (?, 'START', 'JOB_NODE_1', 'ALWAYS', true)
        """,
        parentWfDefId);

    jdbcTemplate.update(
        """
        insert into batch.workflow_edge (
            workflow_definition_id, from_node_code, to_node_code, edge_type, enabled
        ) values (?, 'JOB_NODE_1', 'END', 'SUCCESS', true)
        """,
        parentWfDefId);

    // 父任务启动所需的触发请求
    jdbcTemplate.update(
        """
        insert into batch.trigger_request (
            tenant_id, request_id, trigger_type, job_code, biz_date,
            dedup_key, request_status, trace_id
        ) values (?, ?, 'API', ?, ?, ?, 'ACCEPTED', 'trace-parent')
        """,
        TENANT,
        requestId,
        parentCode,
        BIZ_DATE,
        dedupKey);

    return new ParentSeed(parentCode, requestId, dedupKey);
  }

  private record ParentSeed(String jobCode, String requestId, String dedupKey) {}

  // ── tests ─────────────────────────────────────────────────────────────────

  @Test
  void parentJobWithJobNode_shouldLaunchChildInstanceAndCreateVirtualPartition() {
    String workerGroup = "WG_JN_" + System.nanoTime();
    String childCode = seedChildJob(workerGroup);
    ParentSeed parent = seedParentJob(childCode);

    LaunchRequest launchRequest =
        LaunchRequest.builder()
            .tenantId(TENANT)
            .jobCode(parent.jobCode())
            .bizDate(BIZ_DATE)
            .triggerType(TriggerType.API)
            .requestId(parent.requestId())
            .traceId("trace-job-node-1")
            .params(Map.of())
            .build();
    LaunchResponse response = launchService.launch(launchRequest);

    assertThat(response.instanceNo()).isNotBlank();

    JobInstanceEntity parentInstance =
        jobInstanceMapper.selectByTenantAndDedupKey(TENANT, parent.dedupKey());
    assertThat(parentInstance).isNotNull();
    assertThat(parentInstance.getExpectedPartitionCount()).isEqualTo(1);

    // 子 job_instance 已自动创建
    Long childInstanceCount =
        jdbcTemplate.queryForObject(
            "select count(*) from batch.job_instance where tenant_id = ? and job_code = ?",
            Long.class,
            TENANT,
            childCode);
    assertThat(childInstanceCount).isEqualTo(1L);

    // 在父任务范围内创建了虚拟分区（状态 = RUNNING）
    Long virtualPartitionCount =
        jdbcTemplate.queryForObject(
            """
            select count(*) from batch.job_partition
            where tenant_id = ? and job_instance_id = ? and partition_status = 'RUNNING'
            """,
            Long.class,
            TENANT,
            parentInstance.getId());
    assertThat(virtualPartitionCount).isEqualTo(1L);

    // 在父任务范围内创建了虚拟任务（状态 = RUNNING）
    Long virtualTaskCount =
        jdbcTemplate.queryForObject(
            """
            select count(*) from batch.job_task
            where tenant_id = ? and job_instance_id = ? and task_status = 'RUNNING'
            """,
            Long.class,
            TENANT,
            parentInstance.getId());
    assertThat(virtualTaskCount).isEqualTo(1L);

    // 父 workflow_node_run 中 JOB_NODE_1 为 READY 状态
    Long nodeRunCount =
        jdbcTemplate.queryForObject(
            """
            select count(*)
            from batch.workflow_node_run wnr
            join batch.workflow_run wr on wr.id = wnr.workflow_run_id
            where wr.related_job_instance_id = ?
              and wnr.node_code = 'JOB_NODE_1'
              and wnr.node_status = 'READY'
            """,
            Long.class,
            parentInstance.getId());
    assertThat(nodeRunCount).isEqualTo(1L);
  }

  @Test
  void childJobCompletion_shouldSignalParentAndCompleteParentJobAsSuccess() {
    String workerGroup = "WG_JN_COMP_" + System.nanoTime();
    String childCode = seedChildJob(workerGroup);
    ParentSeed parent = seedParentJob(childCode);

    LaunchRequest launchRequest2 =
        LaunchRequest.builder()
            .tenantId(TENANT)
            .jobCode(parent.jobCode())
            .bizDate(BIZ_DATE)
            .triggerType(TriggerType.API)
            .requestId(parent.requestId())
            .traceId("trace-job-node-2")
            .params(Map.of())
            .build();
    launchService.launch(launchRequest2);

    JobInstanceEntity parentInstance =
        jobInstanceMapper.selectByTenantAndDedupKey(TENANT, parent.dedupKey());
    assertThat(parentInstance).isNotNull();

    // 查找子 job instance
    Long childInstanceId =
        jdbcTemplate.queryForObject(
            "select id from batch.job_instance where tenant_id = ? and job_code = ?",
            Long.class,
            TENANT,
            childCode);
    assertThat(childInstanceId).isNotNull();

    // 查找子任务（属于子 job instance）
    Long childTaskId =
        jdbcTemplate.queryForObject(
            """
            select id from batch.job_task where tenant_id = ? and job_instance_id = ?
            """,
            Long.class,
            TENANT,
            childInstanceId);
    assertThat(childTaskId).isNotNull();

    // 模拟 Worker 拾取子任务：转为 RUNNING
    jdbcTemplate.update(
        "update batch.job_task set task_status = 'RUNNING', started_at = ? where id = ?",
        Timestamp.from(Instant.now()),
        childTaskId);

    // 模拟子任务成功完成
    TaskOutcomeCommand childSuccessOutcome =
        TaskOutcomeCommand.builder()
            .tenantId(TENANT)
            .taskId(childTaskId)
            .success(true)
            .resultSummary("{}")
            .build();
    taskOutcomeService.applyTaskOutcome(childSuccessOutcome);

    // 子 job instance 应为 SUCCESS
    String childStatus =
        jdbcTemplate.queryForObject(
            "select instance_status from batch.job_instance where id = ?",
            String.class,
            childInstanceId);
    assertThat(childStatus).isEqualTo("SUCCESS");

    // 父 job instance 也应为 SUCCESS（通过虚拟任务信号传递）
    String parentStatus =
        jdbcTemplate.queryForObject(
            "select instance_status from batch.job_instance where id = ?",
            String.class,
            parentInstance.getId());
    assertThat(parentStatus).isEqualTo("SUCCESS");

    // 父 workflow_node_run 中 JOB_NODE_1 应为 SUCCESS
    String nodeRunStatus =
        jdbcTemplate.queryForObject(
            """
            select wnr.node_status
            from batch.workflow_node_run wnr
            join batch.workflow_run wr on wr.id = wnr.workflow_run_id
            where wr.related_job_instance_id = ?
              and wnr.node_code = 'JOB_NODE_1'
            """,
            String.class,
            parentInstance.getId());
    assertThat(nodeRunStatus).isEqualTo("SUCCESS");
  }

  @Test
  void childJobFailure_shouldSignalParentAndCompleteParentJobAsFailed() {
    String workerGroup = "WG_JN_FAIL_" + System.nanoTime();
    String childCode = seedChildJob(workerGroup);
    ParentSeed parent = seedParentJob(childCode);

    LaunchRequest launchRequest3 =
        LaunchRequest.builder()
            .tenantId(TENANT)
            .jobCode(parent.jobCode())
            .bizDate(BIZ_DATE)
            .triggerType(TriggerType.API)
            .requestId(parent.requestId())
            .traceId("trace-job-node-3")
            .params(Map.of())
            .build();
    launchService.launch(launchRequest3);

    JobInstanceEntity parentInstance =
        jobInstanceMapper.selectByTenantAndDedupKey(TENANT, parent.dedupKey());

    Long childInstanceId =
        jdbcTemplate.queryForObject(
            "select id from batch.job_instance where tenant_id = ? and job_code = ?",
            Long.class,
            TENANT,
            childCode);

    Long childTaskId =
        jdbcTemplate.queryForObject(
            "select id from batch.job_task where tenant_id = ? and job_instance_id = ?",
            Long.class,
            TENANT,
            childInstanceId);

    jdbcTemplate.update(
        "update batch.job_task set task_status = 'RUNNING', started_at = ? where id = ?",
        Timestamp.from(Instant.now()),
        childTaskId);

    TaskOutcomeCommand childFailureOutcome =
        TaskOutcomeCommand.builder()
            .tenantId(TENANT)
            .taskId(childTaskId)
            .resultSummary("{}")
            .errorCode("ERR_CHILD")
            .errorMessage("child task failed")
            .build();
    taskOutcomeService.applyTaskOutcome(childFailureOutcome);

    // 父任务应为 FAILED（子任务失败，无重试策略）
    String parentStatus =
        jdbcTemplate.queryForObject(
            "select instance_status from batch.job_instance where id = ?",
            String.class,
            parentInstance.getId());
    assertThat(parentStatus).isEqualTo("FAILED");

    // 父 workflow_node_run 中 JOB_NODE_1 应为 FAILED
    String nodeRunStatus =
        jdbcTemplate.queryForObject(
            """
            select wnr.node_status
            from batch.workflow_node_run wnr
            join batch.workflow_run wr on wr.id = wnr.workflow_run_id
            where wr.related_job_instance_id = ?
              and wnr.node_code = 'JOB_NODE_1'
            """,
            String.class,
            parentInstance.getId());
    assertThat(nodeRunStatus).isEqualTo("FAILED");
  }
}
