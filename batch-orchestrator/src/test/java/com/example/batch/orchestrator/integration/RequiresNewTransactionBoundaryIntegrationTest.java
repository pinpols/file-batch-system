package com.example.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.dto.LaunchResponse;
import com.example.batch.common.enums.DeadLetterReplayStatus;
import com.example.batch.common.enums.TaskStatus;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.orchestrator.BatchOrchestratorApplication;
import com.example.batch.orchestrator.application.service.governance.RetryGovernanceService;
import com.example.batch.orchestrator.application.service.task.TaskExecutionService;
import com.example.batch.orchestrator.domain.command.TaskOutcomeCommand;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.JobPartitionEntity;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.domain.query.JobPartitionQuery;
import com.example.batch.orchestrator.domain.query.JobTaskQuery;
import com.example.batch.orchestrator.mapper.DeadLetterTaskMapper;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.mapper.JobPartitionMapper;
import com.example.batch.orchestrator.mapper.JobTaskMapper;
import com.example.batch.orchestrator.service.LaunchService;
import com.example.batch.testing.AbstractIntegrationTest;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * REQUIRES_NEW 事务边界集成测试。
 *
 * <p>验证使用 {@code Propagation.REQUIRES_NEW} 的方法其内部事务独立于外部事务：
 *
 * <ul>
 *   <li>内部事务提交后，即使外部事务回滚，内部写入仍然持久化。
 *   <li>内部事务抛异常回滚时，不影响外部事务已提交的数据。
 * </ul>
 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RequiresNewTransactionBoundaryIntegrationTest extends AbstractIntegrationTest {

  private static final String TENANT = "t1";
  private static final LocalDate BIZ_DATE = LocalDate.of(2026, 1, 20);

  @Autowired private LaunchService launchService;
  @Autowired private TaskExecutionService taskExecutionService;
  @Autowired private RetryGovernanceService retryGovernanceService;
  @Autowired private JobInstanceMapper jobInstanceMapper;
  @Autowired private JobPartitionMapper jobPartitionMapper;
  @Autowired private JobTaskMapper jobTaskMapper;
  @Autowired private DeadLetterTaskMapper deadLetterTaskMapper;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PlatformTransactionManager transactionManager;

  // ── retryTask: REQUIRES_NEW 内部提交独立于外部回滚 ────────────────────────

  @Test
  void retryTask_innerCommitSurvivesOuterRollback() {
    LaunchedJob job = launchAndFail("RETRY_TASK_BOUNDARY");

    long outboxBefore = countOutbox();

    // 在外部事务中调用 retryTask（REQUIRES_NEW），然后回滚外部事务
    TransactionTemplate outer = new TransactionTemplate(transactionManager);
    outer.execute(
        status -> {
          retryGovernanceService.retryTask(TENANT, job.taskId, "boundary-test-retry");
          // 外部事务人工回滚
          status.setRollbackOnly();
          return null;
        });

    // retryTask 的 REQUIRES_NEW 内部事务应已独立提交：
    // partition 被重置为 READY，outbox 多了一条派发事件
    long outboxAfter = countOutbox();
    assertThat(outboxAfter)
        .as("retryTask REQUIRES_NEW should commit outbox event despite outer rollback")
        .isGreaterThan(outboxBefore);

    JobTaskEntity taskAfter = jobTaskMapper.selectById(TENANT, job.taskId);
    assertThat(taskAfter.getStatus())
        .as("task should be reset to READY by retryTask")
        .isEqualTo(TaskStatus.READY.code());
  }

  // ── reclaimTask: REQUIRES_NEW 内部提交独立于外部回滚 ──────────────────────

  @Test
  void reclaimTask_innerCommitSurvivesOuterRollback() {
    LaunchedJob job = launchAndClaim("RECLAIM_BOUNDARY");
    // D4 修复：resetForDispatch 现在守护 lease_expire_at < now 防止 reclaim 抢答活 worker。
    // 测试场景模拟"lease 真实过期后被 scheduler 回收"——手动把 lease 过期掉。
    jdbcTemplate.update(
        "update batch.job_partition set lease_expire_at = now() - interval '1 minute' where"
            + " tenant_id = ? and id = (select job_partition_id from batch.job_task where id = ?)",
        TENANT,
        job.taskId);

    long outboxBefore = countOutbox();

    TransactionTemplate outer = new TransactionTemplate(transactionManager);
    outer.execute(
        status -> {
          retryGovernanceService.reclaimTask(TENANT, job.taskId, "boundary-test-reclaim");
          status.setRollbackOnly();
          return null;
        });

    long outboxAfter = countOutbox();
    assertThat(outboxAfter)
        .as("reclaimTask REQUIRES_NEW should commit outbox event despite outer rollback")
        .isGreaterThan(outboxBefore);

    JobTaskEntity taskAfter = jobTaskMapper.selectById(TENANT, job.taskId);
    assertThat(taskAfter.getStatus())
        .as("task should be reset to READY by reclaimTask")
        .isEqualTo(TaskStatus.READY.code());
  }

  // ── replayDeadLetter: REQUIRES_NEW 内部提交独立于外部回滚 ────────────────

  @Test
  void replayDeadLetter_innerCommitSurvivesOuterRollback() {
    LaunchedJob job = launchAndExhaustRetries("REPLAY_DL_BOUNDARY");

    // 确认已产生死信
    Long deadLetterId = findDeadLetterForPartition(job.partitionId);
    assertThat(deadLetterId).as("dead letter should exist").isNotNull();

    long outboxBefore = countOutbox();

    TransactionTemplate outer = new TransactionTemplate(transactionManager);
    outer.execute(
        status -> {
          retryGovernanceService.replayDeadLetter(TENANT, deadLetterId);
          status.setRollbackOnly();
          return null;
        });

    long outboxAfter = countOutbox();
    assertThat(outboxAfter)
        .as("replayDeadLetter REQUIRES_NEW should commit despite outer rollback")
        .isGreaterThan(outboxBefore);

    // 死信状态应为 SUCCESS（内部事务已提交）
    String replayStatus =
        jdbcTemplate.queryForObject(
            "select replay_status from batch.dead_letter_task where id = ?",
            String.class,
            deadLetterId);
    assertThat(replayStatus).isEqualTo(DeadLetterReplayStatus.SUCCESS.code());
  }

  // ── retryTask: 非终态任务拒绝重试 ────────────────────────────────────────

  @Test
  void retryTask_rejectsNonTerminalStatus() {
    LaunchedJob job = launchAndClaim("RETRY_GUARD");

    // task 是 RUNNING，retryTask 应拒绝（只允许 FAILED/CANCELLED/TERMINATED）
    assertThatThrownBy(
            () -> retryGovernanceService.retryTask(TENANT, job.taskId, "boundary-reject"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("terminal state");
  }

  // ── reclaimTask: 非法状态拒绝 ────────────────────────────────────────────

  @Test
  void reclaimTask_rejectsCreatedStatus() {
    LaunchedJob job = launchJob("RECLAIM_GUARD");

    // task 是 CREATED/READY，reclaimTask 应拒绝（只允许 RUNNING 或终态）
    assertThatThrownBy(
            () -> retryGovernanceService.reclaimTask(TENANT, job.taskId, "boundary-reject"))
        .isInstanceOf(IllegalStateException.class);
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private record LaunchedJob(Long instanceId, Long partitionId, Long taskId, String workerCode) {}

  /** 启动任务并让其进入 FAILED 状态（走完 launch → claim → report failure）。 */
  private LaunchedJob launchAndFail(String prefix) {
    LaunchedJob job = launchAndClaim(prefix);
    TaskOutcomeCommand failureOutcome =
        TaskOutcomeCommand.builder()
            .tenantId(TENANT)
            .taskId(job.taskId)
            .errorCode("SIMULATED_ERROR")
            .errorMessage("boundary test")
            .build();
    taskExecutionService.applyTaskOutcome(failureOutcome);
    return job;
  }

  /** 启动任务并让其进入 RUNNING 状态（走完 launch → claim）。 */
  private LaunchedJob launchAndClaim(String prefix) {
    LaunchedJob job = launchJob(prefix);
    taskExecutionService.assignWorker(TENANT, job.taskId, job.workerCode);
    return job;
  }

  /** 启动任务（READY 状态），不 claim。 */
  private LaunchedJob launchJob(String prefix) {
    String suffix = Long.toUnsignedString(System.nanoTime());
    String jobCode = "IT_" + prefix + "_" + suffix;
    String requestId = "req-" + prefix.toLowerCase() + "-" + suffix;
    String dedupKey = "dedup-" + prefix.toLowerCase() + "-" + suffix;
    String workerCode = "WK-" + prefix + "-" + suffix;

    jdbcTemplate.update(
        """
        insert into batch.job_definition (
            tenant_id, job_code, job_name, job_type, biz_type, schedule_type, timezone,
            priority, queue_code, worker_group, trigger_mode, dag_enabled, shard_strategy,
            retry_policy, retry_max_count, timeout_seconds, enabled, version
        ) values (?, ?, ?, 'IMPORT', 'IT', 'MANUAL', 'UTC',
            5, 'q-it', ?, 'API', false, 'NONE',
            'FIXED', 1, 0, true, 1)
        """,
        TENANT,
        jobCode,
        "IT " + jobCode,
        workerCode);

    jdbcTemplate.update(
        """
        insert into batch.workflow_definition (
            tenant_id, workflow_code, workflow_name, workflow_type, version, enabled
        ) values (?, ?, 'IT wf', 'DAG', 1, true)
        """,
        TENANT,
        jobCode);

    jdbcTemplate.update(
        """
        insert into batch.trigger_request (
            tenant_id, request_id, trigger_type, job_code, biz_date, dedup_key,
            request_status, trace_id
        ) values (?, ?, 'API', ?, date '2026-01-20', ?, 'ACCEPTED', 'trace-it')
        """,
        TENANT,
        requestId,
        jobCode,
        dedupKey);

    jdbcTemplate.update(
        """
        insert into batch.worker_registry (
            tenant_id, worker_code, worker_group, capability_tags, status,
            heartbeat_at, current_load
        ) values (?, ?, ?, '[]'::jsonb, 'ONLINE', now(), 0)
        """,
        TENANT,
        workerCode,
        workerCode);

    LaunchRequest launchRequest =
        LaunchRequest.builder()
            .tenantId(TENANT)
            .jobCode(jobCode)
            .bizDate(BIZ_DATE)
            .triggerType(TriggerType.API)
            .requestId(requestId)
            .traceId("trace-" + suffix)
            .params(Map.of())
            .build();
    LaunchResponse response = launchService.launch(launchRequest);

    JobInstanceEntity instance = jobInstanceMapper.selectByTenantAndDedupKey(TENANT, dedupKey);
    List<JobPartitionEntity> partitions =
        jobPartitionMapper.selectByQuery(
            new JobPartitionQuery(TENANT, instance.getId(), null, null));
    List<JobTaskEntity> tasks =
        jobTaskMapper.selectByQuery(new JobTaskQuery(TENANT, instance.getId(), null, null, null));

    return new LaunchedJob(
        instance.getId(),
        partitions.isEmpty() ? null : partitions.get(0).getId(),
        tasks.get(0).getId(),
        workerCode);
  }

  /** 启动任务，失败，耗尽重试（retry_max_count=1），产生死信。 */
  private LaunchedJob launchAndExhaustRetries(String prefix) {
    String suffix = Long.toUnsignedString(System.nanoTime());
    String jobCode = "IT_" + prefix + "_" + suffix;
    String requestId = "req-" + prefix.toLowerCase() + "-" + suffix;
    String dedupKey = "dedup-" + prefix.toLowerCase() + "-" + suffix;
    String workerCode = "WK-" + prefix + "-" + suffix;

    // retry_policy=NONE, retry_max_count=0 → 立即进死信
    jdbcTemplate.update(
        """
        insert into batch.job_definition (
            tenant_id, job_code, job_name, job_type, biz_type, schedule_type, timezone,
            priority, queue_code, worker_group, trigger_mode, dag_enabled, shard_strategy,
            retry_policy, retry_max_count, timeout_seconds, enabled, version
        ) values (?, ?, ?, 'IMPORT', 'IT', 'MANUAL', 'UTC',
            5, 'q-it', ?, 'API', false, 'NONE',
            'NONE', 0, 0, true, 1)
        """,
        TENANT,
        jobCode,
        "IT " + jobCode,
        workerCode);

    jdbcTemplate.update(
        """
        insert into batch.workflow_definition (
            tenant_id, workflow_code, workflow_name, workflow_type, version, enabled
        ) values (?, ?, 'IT wf', 'DAG', 1, true)
        """,
        TENANT,
        jobCode);

    jdbcTemplate.update(
        """
        insert into batch.trigger_request (
            tenant_id, request_id, trigger_type, job_code, biz_date, dedup_key,
            request_status, trace_id
        ) values (?, ?, 'API', ?, date '2026-01-20', ?, 'ACCEPTED', 'trace-it')
        """,
        TENANT,
        requestId,
        jobCode,
        dedupKey);

    jdbcTemplate.update(
        """
        insert into batch.worker_registry (
            tenant_id, worker_code, worker_group, capability_tags, status,
            heartbeat_at, current_load
        ) values (?, ?, ?, '[]'::jsonb, 'ONLINE', now(), 0)
        """,
        TENANT,
        workerCode,
        workerCode);

    LaunchRequest exhaustRequest =
        LaunchRequest.builder()
            .tenantId(TENANT)
            .jobCode(jobCode)
            .bizDate(BIZ_DATE)
            .triggerType(TriggerType.API)
            .requestId(requestId)
            .traceId("trace-" + suffix)
            .params(Map.of())
            .build();
    launchService.launch(exhaustRequest);

    JobInstanceEntity instance = jobInstanceMapper.selectByTenantAndDedupKey(TENANT, dedupKey);
    List<JobPartitionEntity> partitions =
        jobPartitionMapper.selectByQuery(
            new JobPartitionQuery(TENANT, instance.getId(), null, null));
    List<JobTaskEntity> tasks =
        jobTaskMapper.selectByQuery(new JobTaskQuery(TENANT, instance.getId(), null, null, null));

    Long taskId = tasks.get(0).getId();
    Long partitionId = partitions.isEmpty() ? null : partitions.get(0).getId();

    // claim + fail → 进死信（因为 NONE / 0）
    taskExecutionService.assignWorker(TENANT, taskId, workerCode);
    TaskOutcomeCommand exhaustOutcome =
        TaskOutcomeCommand.builder()
            .tenantId(TENANT)
            .taskId(taskId)
            .errorCode("EXHAUST_ERROR")
            .errorMessage("exhaust retries")
            .build();
    taskExecutionService.applyTaskOutcome(exhaustOutcome);

    return new LaunchedJob(instance.getId(), partitionId, taskId, workerCode);
  }

  private Long findDeadLetterForPartition(Long partitionId) {
    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            "select id from batch.dead_letter_task where tenant_id = ? and source_id = ?"
                + " and source_type = 'JOB_PARTITION' order by id desc limit 1",
            TENANT,
            partitionId);
    return rows.isEmpty() ? null : ((Number) rows.get(0).get("id")).longValue();
  }

  private long countOutbox() {
    Long n =
        jdbcTemplate.queryForObject(
            "select count(*) from batch.outbox_event where tenant_id = ?", Long.class, TENANT);
    return n == null ? 0L : n;
  }
}
