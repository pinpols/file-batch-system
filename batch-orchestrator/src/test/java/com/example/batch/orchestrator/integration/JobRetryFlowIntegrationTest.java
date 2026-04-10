package com.example.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.dto.LaunchResponse;
import com.example.batch.common.enums.RetryScheduleStatus;
import com.example.batch.common.enums.TaskStatus;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.orchestrator.BatchOrchestratorApplication;
import com.example.batch.orchestrator.application.service.RetryGovernanceService;
import com.example.batch.orchestrator.application.service.TaskExecutionService;
import com.example.batch.orchestrator.domain.command.TaskOutcomeCommand;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.JobPartitionEntity;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.domain.entity.RetryScheduleEntity;
import com.example.batch.orchestrator.domain.query.JobPartitionQuery;
import com.example.batch.orchestrator.domain.query.JobTaskQuery;
import com.example.batch.orchestrator.domain.query.RetryScheduleQuery;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.mapper.JobPartitionMapper;
import com.example.batch.orchestrator.mapper.JobTaskMapper;
import com.example.batch.orchestrator.mapper.RetryScheduleMapper;
import com.example.batch.orchestrator.service.LaunchService;
import com.example.batch.testing.AbstractIntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 集成测试：任务重试流程。
 *
 * <p>初始化一个 {@code retry_policy=FIXED}、{@code retry_max_count=1} 的任务定义。
 * 验证路径：
 * <ol>
 *   <li>启动 → 创建任务。</li>
 *   <li>认领 → 任务变为 RUNNING。</li>
 *   <li>上报失败 → {@link RetryGovernanceService#scheduleRetryIfNecessary} 写入
 *       状态为 {@code WAITING} 的 {@code retry_schedule} 行。</li>
 *   <li>{@link RetryGovernanceService#dispatchDueRetries} 处理到期重试并重新入队
 *       分区（写入 event_type = worker type 的新 outbox_event）。</li>
 * </ol>
 */
@SpringBootTest(
        classes = BatchOrchestratorApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class JobRetryFlowIntegrationTest extends AbstractIntegrationTest {

    private static final String TENANT = "t1";
    private static final LocalDate BIZ_DATE = LocalDate.of(2026, 1, 15);

    @Autowired
    private LaunchService launchService;

    @Autowired
    private TaskExecutionService taskExecutionService;

    @Autowired
    private RetryGovernanceService retryGovernanceService;

    @Autowired
    private JobInstanceMapper jobInstanceMapper;

    @Autowired
    private JobPartitionMapper jobPartitionMapper;

    @Autowired
    private JobTaskMapper jobTaskMapper;

    @Autowired
    private RetryScheduleMapper retryScheduleMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void taskFailure_withRetryPolicy_schedulesRetryAndDispatchDueRetries_requeuesPartition() {
        String suffix = Long.toUnsignedString(System.nanoTime());
        String jobCode = "IT_RETRY_" + suffix;
        String requestId = "req-retry-" + suffix;
        String dedupKey = "dedup-retry-" + suffix;
        String workerCode = "wk-retry-" + suffix;

        // 初始化一个 FIXED 重试策略、最多重试 1 次的任务定义
        jdbcTemplate.update(
                """
                insert into batch.job_definition (
                    tenant_id, job_code, job_name, job_type, biz_type, schedule_type, timezone,
                    priority, queue_code, worker_group, trigger_mode, dag_enabled, shard_strategy,
                    retry_policy, retry_max_count, timeout_seconds, enabled, version
                ) values (?, ?, ?, 'IMPORT', 'IT', 'MANUAL', 'UTC',
                    5, 'q-retry-it', ?, 'API', false, 'NONE',
                    'FIXED', 1, 0, true, 1)
                """,
                TENANT, jobCode, "retry IT " + jobCode, workerCode);

        jdbcTemplate.update(
                """
                insert into batch.workflow_definition (
                    tenant_id, workflow_code, workflow_name, workflow_type, version, enabled
                ) values (?, ?, 'retry wf', 'DAG', 1, true)
                """,
                TENANT, jobCode);

        jdbcTemplate.update(
                """
                insert into batch.trigger_request (
                    tenant_id, request_id, trigger_type, job_code, biz_date, dedup_key, request_status, trace_id
                ) values (?, ?, 'API', ?, date '2026-01-15', ?, 'ACCEPTED', 'trace-retry')
                """,
                TENANT, requestId, jobCode, dedupKey);

        jdbcTemplate.update(
                """
                insert into batch.worker_registry (
                    tenant_id, worker_code, worker_group, capability_tags, status, heartbeat_at, current_load
                ) values (?, ?, ?, '{}'::jsonb, 'ONLINE', now(), 0)
                """,
                TENANT, workerCode, workerCode);

        // 1) Launch
        LaunchResponse response = launchService.launch(new LaunchRequest(
                TENANT, jobCode, BIZ_DATE, TriggerType.API,
                requestId, "trace-retry-" + suffix, Map.of()));
        assertThat(response.instanceNo()).isNotBlank();

        JobInstanceEntity jobInstance = jobInstanceMapper.selectByTenantAndDedupKey(TENANT, dedupKey);
        assertThat(jobInstance).isNotNull();

        List<JobTaskEntity> tasks = jobTaskMapper.selectByQuery(
                new JobTaskQuery(TENANT, jobInstance.getId(), null, null, null));
        assertThat(tasks).isNotEmpty();
        JobTaskEntity task = tasks.get(0);

        List<JobPartitionEntity> partitions = jobPartitionMapper.selectByQuery(
                new JobPartitionQuery(TENANT, jobInstance.getId(), null, null));
        assertThat(partitions).isNotEmpty();
        JobPartitionEntity partition = partitions.get(0);

        // 2) Claim
        taskExecutionService.assignWorker(TENANT, task.getId(), workerCode);

        // 3) Report failure → retry should be scheduled
        taskExecutionService.applyTaskOutcome(new TaskOutcomeCommand(
                TENANT, task.getId(), false, null, "SIMULATED_ERROR", "retry flow test"));

        // 验证已为该分区创建了 WAITING 状态的 retry_schedule 行
        List<RetryScheduleEntity> retries =
                retryScheduleMapper.selectByQuery(new RetryScheduleQuery(
                        TENANT, RetryScheduleStatus.WAITING.code(), Instant.now().plusSeconds(3600), 100));
        boolean retryFound = retries.stream()
                .anyMatch(r -> partition.getId().equals(r.getRelatedId())
                        && "JOB_PARTITION".equals(r.getRelatedType()));
        assertThat(retryFound).as("a WAITING retry_schedule row should exist for the failed partition").isTrue();

        // 4) Make retry due immediately, then dispatch
        jdbcTemplate.update(
                "update batch.retry_schedule set next_retry_at = now() - interval '1 second' where related_id = ? and related_type = 'JOB_PARTITION' and retry_status = 'WAITING'",
                partition.getId());

        long outboxBefore = countOutboxForTenant(TENANT);
        retryGovernanceService.dispatchDueRetries();
        long outboxAfter = countOutboxForTenant(TENANT);

        // A new outbox event should have been written for the re-queued partition
        assertThat(outboxAfter).isGreaterThan(outboxBefore);
    }

    private long countOutboxForTenant(String tenantId) {
        Long n = jdbcTemplate.queryForObject(
                "select count(*) from batch.outbox_event where tenant_id = ?",
                Long.class, tenantId);
        return n == null ? 0L : n;
    }
}
