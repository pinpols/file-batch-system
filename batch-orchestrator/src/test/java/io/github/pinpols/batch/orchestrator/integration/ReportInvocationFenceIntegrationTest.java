package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.PartitionStatus;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.TaskStatus;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskAssignmentService;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskExecutionService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunLaunchService;
import io.github.pinpols.batch.orchestrator.domain.command.TaskOutcomeCommand;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobPartitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.domain.query.JobPartitionQuery;
import io.github.pinpols.batch.orchestrator.domain.query.JobTaskQuery;
import io.github.pinpols.batch.orchestrator.infrastructure.lease.PartitionReclaimUnit;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture.WorkflowLaunchSeed;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobTaskMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.LocalDate;
import java.time.Month;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * R3-P1-10 一致性回归(对抗审查 #6):report 纳入 partition invocation fence。
 *
 * <p>场景:task 被 worker#1 以 invocation I1 认领(CLAIM)→ 租约被回收(reclaim)后同一 task/partition 被重新以 invocation
 * I2 派发(double-executor:task 仍处 RUNNING,partition.current_invocation_id 从 I1 换成 I2)。此时 worker#1
 * 的**迟到 report** 携带旧 I1(或根本不带 invocationId)。
 *
 * <p>此前 report 是唯一没强制 fence 的租约操作("带 invocationId 才校验,缺就跳过"),迟到 report 会绕过守卫、经 {@code finishTask}
 * CAS(RUNNING + 报告时重读的当前 version)命中已被 I2 重领的行,用陈旧结果把任务终结,丢掉 I2 的真实结果。
 *
 * <p>本测试断言收紧后的行为:
 *
 * <ol>
 *   <li>携带旧 I1 的迟到 report → 拒绝(CONFLICT / error.task.invocation_mismatch),且不写任何状态(task 仍 RUNNING,
 *       partition 仍 RUNNING 且 invocation 仍为 I2)。
 *   <li>缺失 invocationId 的迟到 report(镜像未回填的陈旧 worker)→ 同样被拒。
 *   <li>I2 的真实 report → 正常推进(task→SUCCESS、partition→SUCCESS),I2 的结果不丢。
 * </ol>
 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("任务结果上报的调用围栏,验证认领重派后旧调用的迟到上报被拒绝且新调用的真实结果不丢失")
class ReportInvocationFenceIntegrationTest extends AbstractIntegrationTest {

  private static final String TENANT = "t1";
  private static final LocalDate BIZ_DATE = LocalDate.of(2026, Month.JANUARY, 15);

  @Autowired
  private WorkflowRunLaunchService workflowRunLaunchService;

  @Autowired
  private TaskExecutionService taskExecutionService;

  @Autowired
  private JobInstanceMapper jobInstanceMapper;

  @Autowired
  private JobPartitionMapper jobPartitionMapper;

  @Autowired
  private JobTaskMapper jobTaskMapper;

  private final PartitionReclaimUnit partitionReclaimUnit;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  ReportInvocationFenceIntegrationTest(PartitionReclaimUnit partitionReclaimUnit) {
    this.partitionReclaimUnit = partitionReclaimUnit;
  }

  @BeforeEach
  void refreshWorkersForClaim() {
    LaunchIntegrationFixture.refreshAssignableWorkersForTenant(jdbcTemplate, TENANT);
  }

  @Test
  @DisplayName("reclaim 重派后旧 invocation(及缺失)的迟到 report 被 fence 拒,I2 真实结果不丢")
  void staleReportAfterReclaim_isRejectedByInvocationFence_andI2ResultWins() {
    WorkflowLaunchSeed seed = LaunchIntegrationFixture.prepareWorkflowLaunchWithWorker(
        jdbcTemplate, TENANT, "IMPORT", "IMPORT", TriggerType.API);
    isolateSeedWorker(seed.workerCode());

    LaunchRequest launchRequest = LaunchRequest.builder()
        .tenantId(TENANT)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.API)
        .requestId(seed.requestId())
        .traceId("trace-fence-" + seed.requestId())
        .params(Map.of())
        .build();
    var response = workflowRunLaunchService.launch(launchRequest);

    Long jobInstanceId = LaunchIntegrationFixture.selectDispatchedJobInstanceId(
        jdbcTemplate, TENANT, response.instanceNo());
    JobInstanceEntity jobInstance = jobInstanceMapper.selectById(TENANT, jobInstanceId);
    assertThat(jobInstance).isNotNull();

    JobTaskEntity task = jobTaskMapper
        .selectByQuery(new JobTaskQuery(TENANT, jobInstance.getId(), null, null, null))
        .get(0);
    JobPartitionEntity partition = jobPartitionMapper
        .selectByQuery(new JobPartitionQuery(TENANT, jobInstance.getId(), null, null))
        .get(0);

    // 1) worker#1 认领 → partition.current_invocation_id = I1
    JobTaskEntity claimed = assignWorkerWithRetry(task.getId(), seed.workerCode());
    assertThat(claimed).isNotNull();
    assertThat(claimed.getTaskStatus()).isEqualTo(TaskStatus.RUNNING.code());
    JobPartitionEntity claimedPartition = jobPartitionMapper.selectById(TENANT, partition.getId());
    String invocationI1 = claimedPartition.getCurrentInvocationId();
    assertThat(invocationI1).isNotBlank();

    // 2) 真 PG reclaim:将租约置为过期后运行生产 reclaim 单元,复位 task/partition/step 并写入重派 outbox。
    String expirePartitionLeaseSql = """
        update batch.job_partition
        set lease_expire_at = current_timestamp - interval '1 minute'
        where tenant_id = ? and id = ?
        """;
    jdbcTemplate.update(expirePartitionLeaseSql, TENANT, partition.getId());
    JobPartitionEntity expired = jobPartitionMapper.selectById(TENANT, partition.getId());
    partitionReclaimUnit.reclaim(expired);
    assertThat(jobTaskMapper.selectById(TENANT, task.getId()).getTaskStatus())
        .isEqualTo(TaskStatus.READY.code());
    assertThat(jobPartitionMapper.selectById(TENANT, partition.getId()).getPartitionStatus())
        .isEqualTo(PartitionStatus.READY.code());
    String reclaimedOutboxSelectedWorkerSql = """
        select payload_json ->> 'selectedWorkerId' from batch.outbox_event
        where tenant_id = ? and event_key = ?
        """;
    String reclaimedOutboxEventKey =
        TENANT + ":reclaim:" + partition.getId() + ":v" + expired.getVersion();
    String reclaimedOutboxSelectedWorker = jdbcTemplate.queryForObject(
        reclaimedOutboxSelectedWorkerSql, String.class, TENANT, reclaimedOutboxEventKey);
    assertThat(reclaimedOutboxSelectedWorker)
        .as("reclaim dispatch must not keep the previous attempt's worker instance target")
        .isNull();

    // 第二次认领产生新 invocation I2;旧 I1 的批量续租必须被真实数据库围栏拒绝。
    JobTaskEntity reclaimed = assignWorkerWithRetry(task.getId(), seed.workerCode());
    assertThat(reclaimed).isNotNull();
    assertThat(reclaimed.getTaskStatus()).isEqualTo(TaskStatus.RUNNING.code());
    String invocationI2 =
        jobPartitionMapper.selectById(TENANT, partition.getId()).getCurrentInvocationId();
    assertThat(invocationI2).isNotBlank().isNotEqualTo(invocationI1);
    List<TaskAssignmentService.TaskHeartbeatResult> oldRenewal =
        taskExecutionService.renewLeaseBatch(List.of(new TaskAssignmentService.LeaseRenewCommand(
            TENANT, task.getId(), seed.workerCode(), invocationI1)));
    assertThat(oldRenewal).hasSize(1);
    assertThat(oldRenewal.get(0).leaseRenewed()).isFalse();

    // 3) worker#1 的迟到 report 带旧 I1 → 必须被 fence 拒,且不写任何状态。
    TaskOutcomeCommand staleWithOldInvocation = TaskOutcomeCommand.builder()
        .tenantId(TENANT)
        .taskId(task.getId())
        .success(true)
        .resultSummary("{\"stale\":\"I1 result must be dropped\"}")
        .partitionInvocationId(invocationI1)
        .build();
    assertThatThrownBy(() -> taskExecutionService.applyTaskOutcome(staleWithOldInvocation))
        .isInstanceOfSatisfying(BizException.class, ex -> {
          assertThat(ex.getCode()).isEqualTo(ResultCode.CONFLICT);
          assertThat(ex.getMessageKey()).isEqualTo("error.task.invocation_mismatch");
        });

    // 4) 迟到 report 缺失 invocationId(镜像未回填的陈旧 worker)→ 同样被拒。
    TaskOutcomeCommand staleMissingInvocation = TaskOutcomeCommand.builder()
        .tenantId(TENANT)
        .taskId(task.getId())
        .success(true)
        .resultSummary("{\"stale\":\"missing invocation must be dropped\"}")
        .build();
    assertThatThrownBy(() -> taskExecutionService.applyTaskOutcome(staleMissingInvocation))
        .isInstanceOfSatisfying(BizException.class, ex -> {
          assertThat(ex.getCode()).isEqualTo(ResultCode.CONFLICT);
          assertThat(ex.getMessageKey()).isEqualTo("error.task.invocation_mismatch");
        });

    // 两次被拒后:task 仍 RUNNING、partition 仍 RUNNING 且 invocation 仍是 I2(旧结果没污染状态)。
    assertThat(jobTaskMapper.selectById(TENANT, task.getId()).getTaskStatus())
        .isEqualTo(TaskStatus.RUNNING.code());
    JobPartitionEntity afterStale = jobPartitionMapper.selectById(TENANT, partition.getId());
    assertThat(afterStale.getPartitionStatus()).isEqualTo(PartitionStatus.RUNNING.code());
    assertThat(afterStale.getCurrentInvocationId()).isEqualTo(invocationI2);

    // 5) I2 的真实 report → 正常推进,I2 结果落地(task→SUCCESS、partition→SUCCESS)。
    TaskOutcomeCommand i2Report = TaskOutcomeCommand.builder()
        .tenantId(TENANT)
        .taskId(task.getId())
        .success(true)
        .resultSummary("{\"records\":42,\"invocation\":\"I2 real result\"}")
        .partitionInvocationId(invocationI2)
        .build();
    taskExecutionService.applyTaskOutcome(i2Report);

    assertThat(jobTaskMapper.selectById(TENANT, task.getId()).getTaskStatus())
        .isEqualTo(TaskStatus.SUCCESS.code());
    assertThat(jobPartitionMapper.selectById(TENANT, partition.getId()).getPartitionStatus())
        .isEqualTo(PartitionStatus.SUCCESS.code());

    // 6) I2 已成功后，旧 I1 再以冲突失败结果乱序到达：终态回报幂等忽略，不能覆盖 I2。
    TaskOutcomeCommand lateConflictingI1Report = TaskOutcomeCommand.builder()
        .tenantId(TENANT)
        .taskId(task.getId())
        .success(false)
        .errorCode("STALE_I1_FAILURE")
        .errorMessage("late I1 failure must not replace the committed I2 success")
        .resultSummary("{\"invocation\":\"I1 stale failure\"}")
        .partitionInvocationId(invocationI1)
        .build();
    assertThat(taskExecutionService.applyTaskOutcome(lateConflictingI1Report).getTaskStatus())
        .isEqualTo(TaskStatus.SUCCESS.code());
    String taskResultInvocationSql = """
        select result_summary ->> 'invocation' from batch.job_task
        where tenant_id = ? and id = ?
        """;
    String persistedResultInvocation =
        jdbcTemplate.queryForObject(taskResultInvocationSql, String.class, TENANT, task.getId());
    assertThat(persistedResultInvocation).isEqualTo("I2 real result");
    String taskErrorCodeSql = """
        select error_code from batch.job_task where tenant_id = ? and id = ?
    """;
    String persistedErrorCode =
        jdbcTemplate.queryForObject(taskErrorCodeSql, String.class, TENANT, task.getId());
    assertThat(persistedErrorCode).isNull();
    assertThat(jobPartitionMapper.selectById(TENANT, partition.getId()).getPartitionStatus())
        .isEqualTo(PartitionStatus.SUCCESS.code());
  }

  @Test
  @DisplayName("回收租约时载荷异常必须回滚任务分区步骤并保留过期租约")
  void shouldRollbackLeaseReclaimWhenTaskPayloadIsInvalid() {
    final String clearPayloadSql =
        "update batch.job_task set task_payload = null where tenant_id = ? and id = ?";
    final String expireLeaseSql =
        "update batch.job_partition set lease_expire_at = current_timestamp - interval '1 minute' where tenant_id = ? and id = ?";
    final String selectStepSql =
        "select step_status, version from batch.job_step_instance where tenant_id = ? and job_task_id = ?";
    final String selectEventCountSql =
        "select count(*) from batch.outbox_event where tenant_id = ? and event_key = ?";
    WorkflowLaunchSeed seed = LaunchIntegrationFixture.prepareWorkflowLaunchWithWorker(
        jdbcTemplate, TENANT, "IMPORT", "IMPORT", TriggerType.API);
    isolateSeedWorker(seed.workerCode());
    var response = workflowRunLaunchService.launch(LaunchRequest.builder()
        .tenantId(TENANT)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.API)
        .requestId(seed.requestId())
        .traceId("invalid-reclaim-" + seed.requestId())
        .params(Map.of())
        .build());
    Long instanceId = LaunchIntegrationFixture.selectDispatchedJobInstanceId(
        jdbcTemplate, TENANT, response.instanceNo());
    JobTaskEntity task = jobTaskMapper
        .selectByQuery(JobTaskQuery.ofJobInstance(TENANT, instanceId))
        .getFirst();
    JobTaskEntity claimed = assignWorkerWithRetry(task.getId(), seed.workerCode());
    assertThat(claimed).isNotNull();
    assertThat(claimed.getTaskStatus()).isEqualTo(TaskStatus.RUNNING.code());
    jdbcTemplate.update(clearPayloadSql, TENANT, task.getId());
    jdbcTemplate.update(expireLeaseSql, TENANT, task.getJobPartitionId());
    JobTaskEntity beforeTask = jobTaskMapper.selectById(TENANT, task.getId());
    JobPartitionEntity beforePartition =
        jobPartitionMapper.selectById(TENANT, task.getJobPartitionId());
    Map<String, Object> beforeStep = jdbcTemplate.queryForMap(selectStepSql, TENANT, task.getId());
    String reclaimKey =
        TENANT + ":reclaim:" + beforePartition.getId() + ":v" + beforePartition.getVersion();

    assertThatThrownBy(() -> partitionReclaimUnit.reclaim(beforePartition))
        .isInstanceOf(BizException.class)
        .satisfies(error -> assertThat(((BizException) error).getMessageKey())
            .isEqualTo("error.task.retry_payload_unsafe"));

    JobTaskEntity afterTask = jobTaskMapper.selectById(TENANT, task.getId());
    JobPartitionEntity afterPartition =
        jobPartitionMapper.selectById(TENANT, task.getJobPartitionId());
    assertThat(afterTask.getTaskStatus())
        .isEqualTo(beforeTask.getTaskStatus())
        .isEqualTo("RUNNING");
    assertThat(afterTask.getVersion()).isEqualTo(beforeTask.getVersion());
    assertThat(afterTask.getTaskPayload()).isNull();
    assertThat(afterPartition.getPartitionStatus())
        .isEqualTo(beforePartition.getPartitionStatus())
        .isEqualTo("RUNNING");
    assertThat(afterPartition.getVersion()).isEqualTo(beforePartition.getVersion());
    assertThat(afterPartition.getCurrentInvocationId())
        .isEqualTo(beforePartition.getCurrentInvocationId());
    assertThat(afterPartition.getLeaseExpireAt()).isEqualTo(beforePartition.getLeaseExpireAt());
    assertThat(jdbcTemplate.queryForMap(selectStepSql, TENANT, task.getId())).isEqualTo(beforeStep);
    assertThat(jdbcTemplate.queryForObject(selectEventCountSql, Long.class, TENANT, reclaimKey))
        .isZero();
  }

  /**
   * 全量测试共享真实数据库时，历史用例残留的 ONLINE worker 可能被资源调度优先选中，
   * 导致本用例用 seed.workerCode() claim 时因 assignedWorkerCode 不匹配而永久 READY。
   * 本用例只依赖自己的 seed worker，因此在 launch 前把同租户其他 worker 置为 OFFLINE 隔离。
   */
  private void isolateSeedWorker(String workerCode) {
    jdbcTemplate.update(
        "update batch.worker_registry set status = 'OFFLINE' where tenant_id = ? and worker_code <> ?",
        TENANT,
        workerCode);
  }

  /** 复刻 lifecycle 测试的 CAS 抖动重试:延长重试 + 每拍刷新 worker 可见性,直至 task RUNNING。 */
  private JobTaskEntity assignWorkerWithRetry(Long taskId, String workerCode) {
    long deadline = System.currentTimeMillis() + 20_000L;
    JobTaskEntity claimed = null;
    while (System.currentTimeMillis() < deadline) {
      LaunchIntegrationFixture.refreshAssignableWorkersForTenant(jdbcTemplate, TENANT);
      claimed = taskExecutionService.assignWorker(TENANT, taskId, workerCode);
      if (claimed != null && TaskStatus.RUNNING.code().equals(claimed.getTaskStatus())) {
        return claimed;
      }
      try {
        Thread.sleep(50L);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    JobTaskEntity latest = jobTaskMapper.selectById(TENANT, taskId);
    throw new AssertionError("task did not reach RUNNING within 20s: taskId="
        + taskId
        + ", workerCode="
        + workerCode
        + ", lastClaimedStatus="
        + (claimed == null ? null : claimed.getTaskStatus())
        + ", persistedStatus="
        + (latest == null ? null : latest.getTaskStatus()));
  }
}
