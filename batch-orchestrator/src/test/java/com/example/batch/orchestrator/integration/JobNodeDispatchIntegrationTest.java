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
 * Integration tests for the JOB node type in workflow DAGs.
 *
 * <p>Scenario: a parent workflow has a single JOB node that references a child job.
 * When the parent is launched, the orchestrator should:
 * <ol>
 *   <li>Launch the child job instance automatically.</li>
 *   <li>Create a virtual partition + task (RUNNING) in the parent job to track the child.</li>
 *   <li>When the child job's task completes, signal back to the parent virtual task so the
 *       parent DAG advances and the parent job instance reaches SUCCESS.</li>
 * </ol>
 */
@SpringBootTest(
        classes = BatchOrchestratorApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class JobNodeDispatchIntegrationTest extends AbstractIntegrationTest {

    private static final String TENANT = "t-job-node";
    private static final LocalDate BIZ_DATE = LocalDate.of(2026, 3, 1);

    @Autowired
    private LaunchService launchService;

    @Autowired
    private TaskOutcomeService taskOutcomeService;

    @Autowired
    private JobInstanceMapper jobInstanceMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Seeds a child job (simple job with one TASK worker, empty workflow DAG). */
    private String seedChildJob(String workerGroup) {
        String suffix = Long.toUnsignedString(System.nanoTime());
        String childCode = "CHILD_" + suffix;

        jdbcTemplate.update("""
                insert into batch.job_definition (
                    tenant_id, job_code, job_name, job_type, biz_type, schedule_type, timezone,
                    priority, queue_code, worker_group, trigger_mode, dag_enabled, shard_strategy,
                    retry_policy, retry_max_count, timeout_seconds, enabled, version
                ) values (?, ?, ?, 'GENERAL', 'IT', 'MANUAL', 'UTC',
                    5, 'q-it', ?, 'API', false, 'NONE',
                    'NONE', 0, 0, true, 1)
                """,
                TENANT, childCode, "child job " + childCode, workerGroup);

        jdbcTemplate.update("""
                insert into batch.workflow_definition (
                    tenant_id, workflow_code, workflow_name, workflow_type, version, enabled
                ) values (?, ?, 'child wf', 'DAG', 1, true)
                """,
                TENANT, childCode);

        jdbcTemplate.update("""
                insert into batch.worker_registry (
                    tenant_id, worker_code, worker_group, capability_tags, status, heartbeat_at, current_load
                ) values (?, ?, ?, '{}'::jsonb, 'ONLINE', now(), 0)
                """,
                TENANT, "wk-child-" + suffix, workerGroup);

        return childCode;
    }

    /**
     * Seeds a parent job whose workflow DAG is: START → JOB_NODE_1 (JOB type) → END.
     * The JOB node's {@code related_job_code} points to {@code childJobCode}.
     *
     * @return the trigger request details needed to call {@link LaunchService#launch}.
     */
    private ParentSeed seedParentJob(String childJobCode) {
        String suffix = Long.toUnsignedString(System.nanoTime());
        String parentCode = "PARENT_" + suffix;
        String requestId = "req-parent-" + suffix;
        String dedupKey = "dedup-parent-" + suffix;

        jdbcTemplate.update("""
                insert into batch.job_definition (
                    tenant_id, job_code, job_name, job_type, biz_type, schedule_type, timezone,
                    priority, queue_code, worker_group, trigger_mode, dag_enabled, shard_strategy,
                    retry_policy, retry_max_count, timeout_seconds, enabled, version
                ) values (?, ?, ?, 'WORKFLOW', 'IT', 'MANUAL', 'UTC',
                    5, 'q-it', 'NONE', 'API', true, 'NONE',
                    'NONE', 0, 0, true, 1)
                """,
                TENANT, parentCode, "parent job " + parentCode);

        // workflow_definition for the parent
        Long parentWfDefId = jdbcTemplate.queryForObject("""
                insert into batch.workflow_definition (
                    tenant_id, workflow_code, workflow_name, workflow_type, version, enabled
                ) values (?, ?, 'parent wf', 'DAG', 1, true)
                returning id
                """,
                Long.class,
                TENANT, parentCode);

        // JOB node: node_type = 'JOB', related_job_code = childJobCode
        jdbcTemplate.update("""
                insert into batch.workflow_node (
                    workflow_definition_id, node_code, node_name, node_type,
                    related_job_code, node_order, retry_policy, retry_max_count,
                    timeout_seconds, enabled
                ) values (?, 'JOB_NODE_1', 'Child Job Node', 'JOB',
                    ?, 1, 'NONE', 0, 0, true)
                """,
                parentWfDefId, childJobCode);

        // Edges: START → JOB_NODE_1 (ALWAYS), JOB_NODE_1 → END (SUCCESS)
        jdbcTemplate.update("""
                insert into batch.workflow_edge (
                    workflow_definition_id, from_node_code, to_node_code, edge_type, enabled
                ) values (?, 'START', 'JOB_NODE_1', 'ALWAYS', true)
                """,
                parentWfDefId);

        jdbcTemplate.update("""
                insert into batch.workflow_edge (
                    workflow_definition_id, from_node_code, to_node_code, edge_type, enabled
                ) values (?, 'JOB_NODE_1', 'END', 'SUCCESS', true)
                """,
                parentWfDefId);

        // Trigger request for the parent launch
        jdbcTemplate.update("""
                insert into batch.trigger_request (
                    tenant_id, request_id, trigger_type, job_code, biz_date,
                    dedup_key, request_status, trace_id
                ) values (?, ?, 'API', ?, ?, ?, 'ACCEPTED', 'trace-parent')
                """,
                TENANT, requestId, parentCode, BIZ_DATE, dedupKey);

        return new ParentSeed(parentCode, requestId, dedupKey);
    }

    private record ParentSeed(String jobCode, String requestId, String dedupKey) {}

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    void parentJobWithJobNode_shouldLaunchChildInstanceAndCreateVirtualPartition() {
        String workerGroup = "WG_JN_" + System.nanoTime();
        String childCode = seedChildJob(workerGroup);
        ParentSeed parent = seedParentJob(childCode);

        LaunchResponse response = launchService.launch(new LaunchRequest(
                TENANT, parent.jobCode(), BIZ_DATE, TriggerType.API,
                parent.requestId(), "trace-job-node-1", Map.of()));

        assertThat(response.instanceNo()).isNotBlank();

        JobInstanceEntity parentInstance = jobInstanceMapper.selectByTenantAndDedupKey(TENANT, parent.dedupKey());
        assertThat(parentInstance).isNotNull();
        assertThat(parentInstance.getExpectedPartitionCount()).isEqualTo(1);

        // Child job_instance was created automatically
        Long childInstanceCount = jdbcTemplate.queryForObject(
                "select count(*) from batch.job_instance where tenant_id = ? and job_code = ?",
                Long.class, TENANT, childCode);
        assertThat(childInstanceCount).isEqualTo(1L);

        // Virtual partition created in parent job scope (status = RUNNING)
        Long virtualPartitionCount = jdbcTemplate.queryForObject("""
                select count(*) from batch.job_partition
                where tenant_id = ? and job_instance_id = ? and partition_status = 'RUNNING'
                """,
                Long.class, TENANT, parentInstance.getId());
        assertThat(virtualPartitionCount).isEqualTo(1L);

        // Virtual task created in parent job scope (status = RUNNING)
        Long virtualTaskCount = jdbcTemplate.queryForObject("""
                select count(*) from batch.job_task
                where tenant_id = ? and job_instance_id = ? and task_status = 'RUNNING'
                """,
                Long.class, TENANT, parentInstance.getId());
        assertThat(virtualTaskCount).isEqualTo(1L);

        // Parent workflow_node_run for JOB_NODE_1 is READY
        Long nodeRunCount = jdbcTemplate.queryForObject("""
                select count(*)
                from batch.workflow_node_run wnr
                join batch.workflow_run wr on wr.id = wnr.workflow_run_id
                where wr.related_job_instance_id = ?
                  and wnr.node_code = 'JOB_NODE_1'
                  and wnr.node_status = 'READY'
                """,
                Long.class, parentInstance.getId());
        assertThat(nodeRunCount).isEqualTo(1L);
    }

    @Test
    void childJobCompletion_shouldSignalParentAndCompleteParentJobAsSuccess() {
        String workerGroup = "WG_JN_COMP_" + System.nanoTime();
        String childCode = seedChildJob(workerGroup);
        ParentSeed parent = seedParentJob(childCode);

        launchService.launch(new LaunchRequest(
                TENANT, parent.jobCode(), BIZ_DATE, TriggerType.API,
                parent.requestId(), "trace-job-node-2", Map.of()));

        JobInstanceEntity parentInstance = jobInstanceMapper.selectByTenantAndDedupKey(TENANT, parent.dedupKey());
        assertThat(parentInstance).isNotNull();

        // Find child job instance
        Long childInstanceId = jdbcTemplate.queryForObject(
                "select id from batch.job_instance where tenant_id = ? and job_code = ?",
                Long.class, TENANT, childCode);
        assertThat(childInstanceId).isNotNull();

        // Find child task (belongs to the child job instance)
        Long childTaskId = jdbcTemplate.queryForObject("""
                select id from batch.job_task where tenant_id = ? and job_instance_id = ?
                """,
                Long.class, TENANT, childInstanceId);
        assertThat(childTaskId).isNotNull();

        // Simulate worker picking up child task: move to RUNNING
        jdbcTemplate.update(
                "update batch.job_task set task_status = 'RUNNING', started_at = ? where id = ?",
                Timestamp.from(Instant.now()), childTaskId);

        // Simulate child task completing successfully
        taskOutcomeService.applyTaskOutcome(new TaskOutcomeCommand(
                TENANT, childTaskId, true, "{}", null, null));

        // Child job instance should be SUCCESS
        String childStatus = jdbcTemplate.queryForObject(
                "select instance_status from batch.job_instance where id = ?",
                String.class, childInstanceId);
        assertThat(childStatus).isEqualTo("SUCCESS");

        // Parent job instance should also be SUCCESS (signalled via virtual task)
        String parentStatus = jdbcTemplate.queryForObject(
                "select instance_status from batch.job_instance where id = ?",
                String.class, parentInstance.getId());
        assertThat(parentStatus).isEqualTo("SUCCESS");

        // Parent workflow_node_run for JOB_NODE_1 should be SUCCESS
        String nodeRunStatus = jdbcTemplate.queryForObject("""
                select wnr.node_status
                from batch.workflow_node_run wnr
                join batch.workflow_run wr on wr.id = wnr.workflow_run_id
                where wr.related_job_instance_id = ?
                  and wnr.node_code = 'JOB_NODE_1'
                """,
                String.class, parentInstance.getId());
        assertThat(nodeRunStatus).isEqualTo("SUCCESS");
    }

    @Test
    void childJobFailure_shouldSignalParentAndCompleteParentJobAsFailed() {
        String workerGroup = "WG_JN_FAIL_" + System.nanoTime();
        String childCode = seedChildJob(workerGroup);
        ParentSeed parent = seedParentJob(childCode);

        launchService.launch(new LaunchRequest(
                TENANT, parent.jobCode(), BIZ_DATE, TriggerType.API,
                parent.requestId(), "trace-job-node-3", Map.of()));

        JobInstanceEntity parentInstance = jobInstanceMapper.selectByTenantAndDedupKey(TENANT, parent.dedupKey());

        Long childInstanceId = jdbcTemplate.queryForObject(
                "select id from batch.job_instance where tenant_id = ? and job_code = ?",
                Long.class, TENANT, childCode);

        Long childTaskId = jdbcTemplate.queryForObject(
                "select id from batch.job_task where tenant_id = ? and job_instance_id = ?",
                Long.class, TENANT, childInstanceId);

        jdbcTemplate.update(
                "update batch.job_task set task_status = 'RUNNING', started_at = ? where id = ?",
                Timestamp.from(Instant.now()), childTaskId);

        taskOutcomeService.applyTaskOutcome(new TaskOutcomeCommand(
                TENANT, childTaskId, false, "{}", "ERR_CHILD", "child task failed"));

        // Parent should be FAILED (child failed, no retry policy)
        String parentStatus = jdbcTemplate.queryForObject(
                "select instance_status from batch.job_instance where id = ?",
                String.class, parentInstance.getId());
        assertThat(parentStatus).isEqualTo("FAILED");

        // Parent workflow_node_run for JOB_NODE_1 should be FAILED
        String nodeRunStatus = jdbcTemplate.queryForObject("""
                select wnr.node_status
                from batch.workflow_node_run wnr
                join batch.workflow_run wr on wr.id = wnr.workflow_run_id
                where wr.related_job_instance_id = ?
                  and wnr.node_code = 'JOB_NODE_1'
                """,
                String.class, parentInstance.getId());
        assertThat(nodeRunStatus).isEqualTo("FAILED");
    }
}
