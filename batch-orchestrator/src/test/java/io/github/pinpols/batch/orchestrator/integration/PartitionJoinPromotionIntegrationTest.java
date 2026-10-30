package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import io.github.pinpols.batch.common.enums.PartitionStatus;
import io.github.pinpols.batch.common.enums.TaskStatus;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.enums.WorkflowRunStatus;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.service.governance.RetryGovernanceService;
import io.github.pinpols.batch.orchestrator.application.service.task.InstanceManagementApplicationService;
import io.github.pinpols.batch.orchestrator.application.service.task.JobInstanceTerminalChildStateReconciler;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskExecutionService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunLaunchService;
import io.github.pinpols.batch.orchestrator.domain.command.TaskOutcomeCommand;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobPartitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.domain.query.JobPartitionQuery;
import io.github.pinpols.batch.orchestrator.domain.query.JobTaskQuery;
import io.github.pinpols.batch.orchestrator.infrastructure.scheduler.WaitingPartitionDispatchScheduler;
import io.github.pinpols.batch.orchestrator.infrastructure.scheduler.WorkerRegistryCache;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture.WorkflowLaunchSeed;
import io.github.pinpols.batch.orchestrator.integration.support.WorkerRegistryCacheTestSupport;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobTaskMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestConstructor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 多分片 fan-out / partition-join「晋级闸门」的最高风险正确性属性(真 PG)。
 *
 * <p>区别于 {@code TaskOutcomeSummaryBuilderTest}(手搓 entity 的纯单测),本测起真 orchestrator + Testcontainers
 * PG,走 ADR-046 束作业 {@code DYNAMIC} 展开出 <b>一个 job_instance + K 个异构 partition/task</b> 的真实路径,再用生产
 * REPORT 入口 {@link TaskExecutionService#applyTaskOutcome} 逐分片回报,直接压 {@code
 * DefaultTaskOutcomeService.advancePartitionAndInstance} 里对 FOR-UPDATE 锁定分区做 join + 状态机晋级的那段。此前该
 * join 只被手搓 entity 的单测覆盖;真状态机只有单分片 happy-path IT。
 *
 * <p>被证明的属性:
 *
 * <ol>
 *   <li>某分片 FAILED 时 instance 不得晋级为 SUCCESS(2 成功 + 1 失败 → PARTIAL_FAILED)。
 *   <li>还有分片在 RUNNING(未回报)时 instance 不得进任何 SUCCESS 终态(停在 RUNNING)。
 *   <li>同一分片 SUCCESS 被重放(duplicate report)时聚合计数只算一次(幂等,不重复计)。
 * </ol>
 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@DisplayName("分片汇聚晋级闸门,校验真实数据库上实例终态判定,计数幂等与失败分片重试重算")
class PartitionJoinPromotionIntegrationTest extends AbstractIntegrationTest {

  private static final String TENANT = "t1";
  private static final LocalDate BIZ_DATE = LocalDate.of(2026, Month.JANUARY, 15);

  private final WorkflowRunLaunchService workflowRunLaunchService;
  private final InstanceManagementApplicationService instanceManagementApplicationService;
  private final TaskExecutionService taskExecutionService;
  private final RetryGovernanceService retryGovernanceService;
  private final JobInstanceTerminalChildStateReconciler terminalChildStateReconciler;
  private final JobInstanceMapper jobInstanceMapper;
  private final JobPartitionMapper jobPartitionMapper;
  private final JobTaskMapper jobTaskMapper;
  private final JdbcTemplate jdbcTemplate;
  private final WorkerRegistryCache workerRegistryCache;
  private final WaitingPartitionDispatchScheduler waitingPartitionDispatchScheduler;
  private final TransactionTemplate transactionTemplate;

  PartitionJoinPromotionIntegrationTest(
      WorkflowRunLaunchService workflowRunLaunchService,
      InstanceManagementApplicationService instanceManagementApplicationService,
      TaskExecutionService taskExecutionService,
      RetryGovernanceService retryGovernanceService,
      JobInstanceTerminalChildStateReconciler terminalChildStateReconciler,
      JobInstanceMapper jobInstanceMapper,
      JobPartitionMapper jobPartitionMapper,
      JobTaskMapper jobTaskMapper,
      JdbcTemplate jdbcTemplate,
      WorkerRegistryCache workerRegistryCache,
      WaitingPartitionDispatchScheduler waitingPartitionDispatchScheduler,
      PlatformTransactionManager transactionManager) {
    this.workflowRunLaunchService = workflowRunLaunchService;
    this.instanceManagementApplicationService = instanceManagementApplicationService;
    this.taskExecutionService = taskExecutionService;
    this.retryGovernanceService = retryGovernanceService;
    this.terminalChildStateReconciler = terminalChildStateReconciler;
    this.jobInstanceMapper = jobInstanceMapper;
    this.jobPartitionMapper = jobPartitionMapper;
    this.jobTaskMapper = jobTaskMapper;
    this.jdbcTemplate = jdbcTemplate;
    this.workerRegistryCache = workerRegistryCache;
    this.waitingPartitionDispatchScheduler = waitingPartitionDispatchScheduler;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
  }

  @BeforeEach
  void refreshWorkers() {
    WorkerRegistryCacheTestSupport.evictTenantWorkerSelectors(workerRegistryCache, TENANT);
    LaunchIntegrationFixture.refreshAssignableWorkersForTenant(jdbcTemplate, TENANT);
  }

  @Test
  @DisplayName("动态 fan-out 总分区数超过租户上限时整组等待且完整物化")
  void dynamicFanOut_shouldDeferAllPartitionsWhenRequestedCountExceedsTenantCap() {
    final String insertTenantQuotaPolicySql = """
          insert into batch.tenant_quota_policy (
              tenant_id, policy_code, max_running_jobs_per_tenant, max_partitions_per_tenant,
              max_qps_per_tenant, fair_share_weight, enabled, description, burst_limit,
              partition_burst_limit, quota_reset_policy, group_shared_max_running_jobs,
              exceeded_strategy
          ) values (?, ?, 1, 2, 0, 1, true, 'R21 dynamic fan-out partition cap',
                    0, 0, 'NONE', 0, 'QUEUE_DEFER')
          """;
    final String selectCountSql = """
                select count(*) from batch.job_partition
                where tenant_id = ? and job_instance_id = ?
                  and partition_status in ('READY', 'RUNNING', 'RETRYING')
                """;
    final String selectCountSql2 = """
              select count(*) from batch.job_partition
              where tenant_id = ? and job_instance_id = ? and partition_status = 'WAITING'
              """;

    String tenantId = "t-r21-fanout-" + Long.toUnsignedString(System.nanoTime());
    String policyCode = "fanout-partition-cap";
    jdbcTemplate.update(insertTenantQuotaPolicySql, tenantId, policyCode);

    FannedOutInstance fannedOut = launchBundle(tenantId, 3);

    List<JobPartitionEntity> partitions = jobPartitionMapper.selectByQuery(
        new JobPartitionQuery(tenantId, fannedOut.instanceId(), null, null));
    assertThat(partitions)
        .as("all requested bundle partitions remain represented when quota defers admission")
        .hasSize(3)
        .extracting(JobPartitionEntity::getPartitionStatus)
        .containsOnly(PartitionStatus.WAITING.code());
    assertThat(jobInstanceMapper
            .selectById(tenantId, fannedOut.instanceId())
            .getExpectedPartitionCount())
        .isEqualTo(3);

    List<JobTaskEntity> tasks = jobTaskMapper.selectByQuery(
        new JobTaskQuery(tenantId, fannedOut.instanceId(), null, null, null));
    assertThat(tasks)
        .as("quota deferral preserves one CREATED task for each fan-out partition")
        .hasSize(3)
        .extracting(JobTaskEntity::getTaskStatus)
        .containsOnly(TaskStatus.CREATED.code());

    Awaitility.await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
      // 测试手工触发前，Spring 定时 tick 可能已持有 ShedLock；
      // 有界重试生产入口，直到某次 tick 持有锁并观察到目标行。
      waitingPartitionDispatchScheduler.dispatchWaitingPartitions();
      Integer selectedJobPartitionCount = jdbcTemplate.queryForObject(
          selectCountSql, Integer.class, tenantId, fannedOut.instanceId());
      assertThat(selectedJobPartitionCount)
          .as("re-evaluation admits no more active fan-out partitions than the tenant cap")
          .isEqualTo(2);
      Integer selectedJobPartitionCountRead2 = jdbcTemplate.queryForObject(
          selectCountSql2, Integer.class, tenantId, fannedOut.instanceId());
      assertThat(selectedJobPartitionCountRead2).isEqualTo(1);
    });

    JobPartitionEntity releasedPartition =
        jobPartitionMapper
            .selectByQuery(new JobPartitionQuery(tenantId, fannedOut.instanceId(), null, null))
            .stream()
            .filter(
                partition -> PartitionStatus.READY.code().equals(partition.getPartitionStatus()))
            .findFirst()
            .orElseThrow();
    JobTaskEntity releasedTask =
        jobTaskMapper.selectByPartitionAndSeq(tenantId, releasedPartition.getId(), 1);
    JobTaskEntity claimedTask = taskExecutionService.assignWorker(
        tenantId, releasedTask.getId(), fannedOut.seed().workerCode());
    assertThat(claimedTask).isNotNull();
    JobPartitionEntity claimedPartition =
        jobPartitionMapper.selectById(tenantId, releasedPartition.getId());
    TaskOutcomeCommand releasedPartitionOutcome = TaskOutcomeCommand.builder()
        .tenantId(tenantId)
        .taskId(claimedTask.getId())
        .success(true)
        .resultSummary("{\"records\":10}")
        .partitionInvocationId(claimedPartition.getCurrentInvocationId())
        .build();
    taskExecutionService.applyTaskOutcome(releasedPartitionOutcome);

    Awaitility.await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
      waitingPartitionDispatchScheduler.dispatchWaitingPartitions();
      Integer selectedJobPartitionCountRead3 = jdbcTemplate.queryForObject(
          selectCountSql, Integer.class, tenantId, fannedOut.instanceId());
      assertThat(selectedJobPartitionCountRead3)
          .as("the final WAITING partition is released after capacity becomes available")
          .isEqualTo(2);
      Integer selectedJobPartitionCountRead4 = jdbcTemplate.queryForObject(
          selectCountSql2, Integer.class, tenantId, fannedOut.instanceId());
      assertThat(selectedJobPartitionCountRead4).isZero();
    });
  }

  @Test
  @DisplayName("最后失败报告的产物不能写入节点或部分失败结果版本")
  void shouldNotPromoteFailedReportOutputsToSuccessfulOutputs() {
    String nodeOutputSql = """
        select output::text from batch.workflow_node_run where job_instance_id = ?
        """;
    String versionPayloadSql = """
        select payload_json::text from batch.result_version where tenant_id = ? and job_instance_id = ?
        """;
    FannedOutInstance fannedOut = launchBundle(2);
    List<Shard> shards = claimAllShards(fannedOut);
    reportOutcome(shards.get(0), true);
    Shard failedShard = shards.get(1);
    JobPartitionEntity partition = jobPartitionMapper.selectById(TENANT, failedShard.partitionId());
    taskExecutionService.applyTaskOutcome(TaskOutcomeCommand.builder()
        .tenantId(TENANT)
        .taskId(failedShard.taskId())
        .success(false)
        .errorCode("E_SHARD_FAIL")
        .outputs(Map.of("failedOnly", 999))
        .partitionInvocationId(partition.getCurrentInvocationId())
        .build());

    assertThat(jobInstanceMapper.selectById(TENANT, fannedOut.instanceId()).getInstanceStatus())
        .isEqualTo(JobInstanceStatus.PARTIAL_FAILED.code());
    assertThat(jdbcTemplate.queryForObject(nodeOutputSql, String.class, fannedOut.instanceId()))
        .isNull();
    assertThat(jdbcTemplate.queryForObject(
            versionPayloadSql, String.class, TENANT, fannedOut.instanceId()))
        .isEqualTo("{}");
  }

  @Test
  @DisplayName("某分片 FAILED → instance 落 PARTIAL_FAILED 而非 SUCCESS(3 分区:2 成功 + 1 失败)")
  void instanceDoesNotPromoteToSuccess_whenOneShardFailed() {
    final String selectCountSql = """
        select count(*) from batch.result_version where tenant_id = ? and job_instance_id = ?\s\
        and status = 'EFFECTIVE'\
        """;

    // arrange:一个 BUNDLE_IMPORT 束作业展开成 3 个异构 partition/task(单 instance,真 launch)
    FannedOutInstance fannedOut = launchBundle(3);
    List<Shard> shards = claimAllShards(fannedOut);

    // act:前 2 个分片 SUCCESS,第 3 个分片 FAILED —— 驱动到全分片终结
    reportOutcome(shards.get(0), true);
    reportOutcome(shards.get(1), true);
    reportOutcome(shards.get(2), false);

    // assert:分区终态 = 2 SUCCESS + 1 FAILED(各分片独立落定)
    assertThat(partitionStatus(shards.get(0))).isEqualTo(PartitionStatus.SUCCESS.code());
    assertThat(partitionStatus(shards.get(1))).isEqualTo(PartitionStatus.SUCCESS.code());
    assertThat(partitionStatus(shards.get(2))).isEqualTo(PartitionStatus.FAILED.code());

    // assert(核心属性):instance 绝不因 2/3 成功而晋级 SUCCESS —— 有失败分片必须落 PARTIAL_FAILED,
    // 且其公共生命周期投影是 FAILED(不是 SUCCESS)。
    JobInstanceEntity terminal = jobInstanceMapper.selectById(TENANT, fannedOut.instanceId());
    assertThat(terminal.getInstanceStatus()).isNotEqualTo(JobInstanceStatus.SUCCESS.code());
    assertThat(terminal.getInstanceStatus()).isEqualTo(JobInstanceStatus.PARTIAL_FAILED.code());
    assertThat(JobInstanceStatus.fromCodeOrNull(terminal.getInstanceStatus()).lifecycle())
        .isEqualTo(JobInstanceStatus.FAILED.lifecycle());

    // assert:分区计数如实反映 join 结果(2 成功 / 1 失败),不把失败分片吞成成功。
    assertThat(successPartitionCount(fannedOut.instanceId())).isEqualTo(2);
    assertThat(failedPartitionCount(fannedOut.instanceId())).isEqualTo(1);

    // assert(结算级契约):PARTIAL_FAILED 不得产出 EFFECTIVE result_version —— 否则下游 readiness
    // 会把不完整结果当完整消费(roadmap §2.2「PARTIAL_FAILED 不得被静默消费」)。
    Integer effectiveVersions =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT, fannedOut.instanceId());
    assertThat(effectiveVersions).isZero();
  }

  @Test
  @DisplayName("仍有分片在 RUNNING(未回报)→ instance 停在 RUNNING,不进任何 SUCCESS 终态")
  void instanceStaysNonTerminal_whileShardStillRunning() {
    // arrange:2 个 partition/task,两个都 claim 进 RUNNING
    FannedOutInstance fannedOut = launchBundle(2);
    List<Shard> shards = claimAllShards(fannedOut);

    // act:只回报第 1 个分片 SUCCESS,第 2 个分片留在 RUNNING(不回报)
    reportOutcome(shards.get(0), true);

    // assert:第 1 分片 SUCCESS、第 2 分片仍 RUNNING
    assertThat(partitionStatus(shards.get(0))).isEqualTo(PartitionStatus.SUCCESS.code());
    assertThat(partitionStatus(shards.get(1))).isEqualTo(PartitionStatus.RUNNING.code());

    // assert(核心属性):还有分片未终结时 instance 绝不进 SUCCESS —— 停在 RUNNING(非终态),
    // 只有全分片终结后 join 才允许晋级。
    JobInstanceEntity instance = jobInstanceMapper.selectById(TENANT, fannedOut.instanceId());
    assertThat(instance.getInstanceStatus()).isNotEqualTo(JobInstanceStatus.SUCCESS.code());
    assertThat(instance.getInstanceStatus()).isEqualTo(JobInstanceStatus.RUNNING.code());
    assertThat(instance.getFinishedAt()).isNull();
  }

  @Test
  @DisplayName("同一分片 SUCCESS 被重放 → 聚合计数只算一次(幂等,不重复计;result_version 仍只 1 行)")
  void duplicateShardReport_doesNotDoubleCount() {
    final String selectCountSql = """
        select count(*) from batch.result_version where tenant_id = ? and job_instance_id = ?\
        """;

    // arrange:2 个 partition/task,两个都 claim 进 RUNNING
    FannedOutInstance fannedOut = launchBundle(2);
    List<Shard> shards = claimAllShards(fannedOut);

    // act:分片 A SUCCESS 上报「两次」(模拟 Kafka 重投 / worker 重放,同一 partition 同一 invocation),分片 B SUCCESS 一次
    reportOutcome(shards.get(0), true);
    reportOutcome(shards.get(0), true); // duplicate / replay —— 应被幂等吞掉,不二次推进
    reportOutcome(shards.get(1), true);

    // assert:两个分片各自 SUCCESS
    assertThat(partitionStatus(shards.get(0))).isEqualTo(PartitionStatus.SUCCESS.code());
    assertThat(partitionStatus(shards.get(1))).isEqualTo(PartitionStatus.SUCCESS.code());

    // assert(核心属性):成功分片计数 = 2(而非因重放变成 3);instance 正常 SUCCESS。
    JobInstanceEntity instance = jobInstanceMapper.selectById(TENANT, fannedOut.instanceId());
    assertThat(instance.getInstanceStatus()).isEqualTo(JobInstanceStatus.SUCCESS.code());
    assertThat(successPartitionCount(fannedOut.instanceId())).isEqualTo(2);
    assertThat(failedPartitionCount(fannedOut.instanceId())).isZero();

    // assert:重放不产生重复的结果版本 —— 每个 job_instance 至多 1 行 result_version(writer 幂等守护)。
    Long resultVersionRows =
        jdbcTemplate.queryForObject(selectCountSql, Long.class, TENANT, fannedOut.instanceId());
    assertThat(resultVersionRows).isEqualTo(1L);
  }

  @Test
  @DisplayName("终态报告把分区状态、计数和输出摘要原子落库")
  void terminalReport_persistsPartitionSummaryWithStatusTransition() {
    FannedOutInstance fannedOut = launchBundle(1);
    Shard shard = claimAllShards(fannedOut).get(0);

    reportOutcome(shard, true);

    JobPartitionEntity partition = jobPartitionMapper.selectById(TENANT, shard.partitionId());
    assertThat(partition.getPartitionStatus()).isEqualTo(PartitionStatus.SUCCESS.code());
    Map<?, ?> outputSummary = JsonUtils.fromJson(partition.getOutputSummary(), Map.class);
    assertThat(((Number) outputSummary.get("taskId")).longValue()).isEqualTo(shard.taskId());
    assertThat(outputSummary.get("success")).isEqualTo(true);
    assertThat(successPartitionCount(fannedOut.instanceId())).isEqualTo(1);
  }

  @Test
  @DisplayName("终态父实例的分区和任务同时活跃时只计一次")
  void terminalInstanceWithActivePartitionAndTask_isCountedOnce() {
    final String updateJobInstanceSql = """
        update batch.job_instance set instance_status = 'SUCCESS' where tenant_id = ? and id = ?\
        """;
    final String updateJobPartitionSql = """
        update batch.job_partition set partition_status = 'SUCCESS' where tenant_id = ? and job_instance_id = ?\
        """;
    final String updateJobTaskSql = """
        update batch.job_task set task_status = 'SUCCESS' where tenant_id = ? and job_instance_id = ?\
        """;

    long before = jobInstanceMapper.countTerminalInstancesWithActiveChildren();
    FannedOutInstance fannedOut = launchBundle(1);
    claimAllShards(fannedOut);

    jdbcTemplate.update(updateJobInstanceSql, TENANT, fannedOut.instanceId());

    assertThat(jobInstanceMapper.countTerminalInstancesWithActiveChildren()).isEqualTo(before + 1);

    jdbcTemplate.update(updateJobPartitionSql, TENANT, fannedOut.instanceId());
    jdbcTemplate.update(updateJobTaskSql, TENANT, fannedOut.instanceId());
    assertThat(jobInstanceMapper.countTerminalInstancesWithActiveChildren()).isEqualTo(before);
  }

  @Test
  @DisplayName("分片重试后迟到的旧报告不能覆盖新产物")
  void shouldRejectStaleReportAndKeepOnlySuccessfulOutputsAfterRetry() {
    String versionPayloadSql = """
        select payload_json::text from batch.result_version where tenant_id = ? and job_instance_id = ?
        """;
    FannedOutInstance fannedOut = launchBundle(3);
    List<Shard> shards = claimAllShards(fannedOut);
    Shard retryShard = shards.get(0);
    JobPartitionEntity firstAttempt =
        jobPartitionMapper.selectById(TENANT, retryShard.partitionId());
    String oldInvocation = firstAttempt.getCurrentInvocationId();
    TaskOutcomeCommand oldReport = TaskOutcomeCommand.builder()
        .tenantId(TENANT)
        .taskId(retryShard.taskId())
        .success(false)
        .errorCode("E_SHARD_FAIL")
        .outputs(Map.of("oldAttempt", "failed"))
        .partitionInvocationId(oldInvocation)
        .build();
    taskExecutionService.applyTaskOutcome(oldReport);
    retryGovernanceService.retryPartition(
        TENANT, retryShard.partitionId(), TENANT + ":it-output-retry:" + retryShard.partitionId());
    taskExecutionService.assignWorker(
        TENANT, retryShard.taskId(), fannedOut.seed().workerCode());
    String newInvocation =
        jobPartitionMapper.selectById(TENANT, retryShard.partitionId()).getCurrentInvocationId();
    assertThat(newInvocation).isNotBlank().isNotEqualTo(oldInvocation);
    assertThatThrownBy(() -> taskExecutionService.applyTaskOutcome(oldReport))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("error.task.invocation_mismatch");
    taskExecutionService.applyTaskOutcome(TaskOutcomeCommand.builder()
        .tenantId(TENANT)
        .taskId(retryShard.taskId())
        .success(true)
        .outputs(Map.of("newAttempt", "success"))
        .partitionInvocationId(newInvocation)
        .build());
    reportOutcome(shards.get(1), true);
    reportOutcome(shards.get(2), true);

    assertThat(jobInstanceMapper.selectById(TENANT, fannedOut.instanceId()).getInstanceStatus())
        .isEqualTo(JobInstanceStatus.SUCCESS.code());
    String payload = jdbcTemplate.queryForObject(
        versionPayloadSql, String.class, TENANT, fannedOut.instanceId());
    assertThat(payload).contains("newAttempt", "success").doesNotContain("oldAttempt");
  }

  @Test
  @DisplayName("父实例仍运行时失败分片重开回退失败计数,全部分片成功后实例才结束")
  void retryFailedShard_rebalancesIncrementalCounters() {
    FannedOutInstance fannedOut = launchBundle(3);
    List<Shard> shards = claimAllShards(fannedOut);
    Shard successfulShard = shards.get(0);
    Shard failedShard = shards.get(1);

    reportOutcome(successfulShard, true);
    reportOutcome(failedShard, false);

    assertThat(successPartitionCount(fannedOut.instanceId())).isEqualTo(1);
    assertThat(failedPartitionCount(fannedOut.instanceId())).isEqualTo(1);
    assertThat(jobInstanceMapper.selectById(TENANT, fannedOut.instanceId()).getInstanceStatus())
        .isEqualTo(JobInstanceStatus.RUNNING.code());

    retryGovernanceService.retryPartition(
        TENANT,
        failedShard.partitionId(),
        TENANT + ":it-partition-retry:" + failedShard.partitionId());

    assertThat(partitionStatus(failedShard)).isEqualTo(PartitionStatus.READY.code());
    assertThat(successPartitionCount(fannedOut.instanceId())).isEqualTo(1);
    assertThat(failedPartitionCount(fannedOut.instanceId())).isZero();

    taskExecutionService.assignWorker(
        TENANT, failedShard.taskId(), fannedOut.seed().workerCode());
    reportOutcome(failedShard, true);
    assertThat(jobInstanceMapper.selectById(TENANT, fannedOut.instanceId()).getInstanceStatus())
        .isEqualTo(JobInstanceStatus.RUNNING.code());
    reportOutcome(shards.get(2), true);

    JobInstanceEntity recovered = jobInstanceMapper.selectById(TENANT, fannedOut.instanceId());
    assertThat(recovered.getInstanceStatus()).isEqualTo(JobInstanceStatus.SUCCESS.code());
    assertThat(successPartitionCount(fannedOut.instanceId())).isEqualTo(3);
    assertThat(failedPartitionCount(fannedOut.instanceId())).isZero();
  }

  @Test
  @DisplayName("父实例已部分失败终结时拒绝原地重试,失败分片与计数保持不变")
  void shouldRejectRetry_whenParentExecutionIsTerminal() {
    FannedOutInstance fannedOut = launchBundle(2);
    List<Shard> shards = claimAllShards(fannedOut);
    reportOutcome(shards.get(0), true);
    Shard failedShard = shards.get(1);
    reportOutcome(failedShard, false);
    JobPartitionEntity before = jobPartitionMapper.selectById(TENANT, failedShard.partitionId());

    assertThatThrownBy(() -> retryGovernanceService.retryPartition(
            TENANT,
            failedShard.partitionId(),
            TENANT + ":closed-retry:" + failedShard.partitionId()))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("error.partition.retry_execution_closed");

    JobPartitionEntity after = jobPartitionMapper.selectById(TENANT, failedShard.partitionId());
    assertThat(after.getPartitionStatus()).isEqualTo(PartitionStatus.FAILED.code());
    assertThat(after.getVersion()).isEqualTo(before.getVersion());
    assertThat(jobTaskMapper.selectById(TENANT, failedShard.taskId()).getTaskStatus())
        .isEqualTo(TaskStatus.FAILED.code());
    assertThat(jobInstanceMapper.selectById(TENANT, fannedOut.instanceId()).getInstanceStatus())
        .isEqualTo(JobInstanceStatus.PARTIAL_FAILED.code());
    assertThat(successPartitionCount(fannedOut.instanceId())).isOne();
    assertThat(failedPartitionCount(fannedOut.instanceId())).isOne();
  }

  @Test
  @DisplayName("终态子分区修复同步校正实例计数")
  void terminalChildReconcile_repairsInstanceCounters() {
    final String updateJobInstanceSql = """
        update batch.job_instance set success_partition_count = 7, failed_partition_count = 9\s\
        where tenant_id = ? and id = ?\
        """;

    FannedOutInstance fannedOut = launchBundle(2);

    jdbcTemplate.update(updateJobInstanceSql, TENANT, fannedOut.instanceId());

    terminalChildStateReconciler.reconcile(
        TENANT, fannedOut.instanceId(), JobInstanceStatus.FAILED.code());

    List<JobPartitionEntity> partitions = jobPartitionMapper.selectByQuery(
        new JobPartitionQuery(TENANT, fannedOut.instanceId(), null, null));
    assertThat(partitions)
        .extracting(JobPartitionEntity::getPartitionStatus)
        .containsOnly(PartitionStatus.FAILED.code());
    assertThat(successPartitionCount(fannedOut.instanceId())).isZero();
    assertThat(failedPartitionCount(fannedOut.instanceId())).isEqualTo(2);
  }

  @Test
  @DisplayName("取消一个分区同步终结 task,剩余分区成功后实例与 Workflow 按部分失败收口")
  void cancelledPartition_contributesToParentCompletionAndReconcilesTask() {
    final String selectCountSql = """
        select count(*) from batch.job_partition where tenant_id = ? and job_instance_id = ?\s\
        and partition_status in ('CREATED', 'WAITING', 'READY', 'RUNNING', 'RETRYING')\
        """;
    final String selectWorkflowRunIdSql = """
        select workflow_run_id from batch.workflow_node_run where job_instance_id = ? order by id desc limit 1\
        """;
    final String selectRunStatusSql = """
        select run_status from batch.workflow_run where tenant_id = ? and id = ?
    """;

    FannedOutInstance fannedOut = launchBundle(2);
    List<JobTaskEntity> tasks = jobTaskMapper.selectByQuery(
        new JobTaskQuery(TENANT, fannedOut.instanceId(), null, null, null));
    assertThat(tasks).hasSize(2);

    JobTaskEntity cancelledTask = tasks.get(0);
    JobPartitionEntity cancelledPartition =
        jobPartitionMapper.selectById(TENANT, cancelledTask.getJobPartitionId());
    assertThat(cancelledPartition.getPartitionStatus())
        .isIn(
            PartitionStatus.CREATED.code(),
            PartitionStatus.WAITING.code(),
            PartitionStatus.READY.code());

    instanceManagementApplicationService.cancelPartition(TENANT, cancelledPartition.getId());

    JobTaskEntity cancelledTaskAfter = jobTaskMapper.selectById(TENANT, cancelledTask.getId());
    assertThat(
            jobPartitionMapper.selectById(TENANT, cancelledPartition.getId()).getPartitionStatus())
        .isEqualTo(PartitionStatus.CANCELLED.code());
    assertThat(cancelledTaskAfter.getTaskStatus()).isEqualTo(TaskStatus.CANCELLED.code());
    assertThat(cancelledTaskAfter.getFinishedAt()).isNotNull();
    assertThat(cancelledPartitionCount(fannedOut.instanceId())).isEqualTo(1);
    assertThat(jobInstanceMapper.selectById(TENANT, fannedOut.instanceId()).getInstanceStatus())
        .isEqualTo(JobInstanceStatus.RUNNING.code());

    JobTaskEntity remainingTask = tasks.get(1);
    JobTaskEntity claimed = taskExecutionService.assignWorker(
        TENANT, remainingTask.getId(), fannedOut.seed().workerCode());
    assertThat(claimed).isNotNull();
    reportOutcome(new Shard(remainingTask.getId(), remainingTask.getJobPartitionId()), true);

    JobInstanceEntity terminal = jobInstanceMapper.selectById(TENANT, fannedOut.instanceId());
    assertThat(terminal.getInstanceStatus()).isEqualTo(JobInstanceStatus.PARTIAL_FAILED.code());
    assertThat(terminal.getFinishedAt()).isNotNull();
    assertThat(successPartitionCount(fannedOut.instanceId())).isEqualTo(1);
    assertThat(failedPartitionCount(fannedOut.instanceId())).isZero();
    assertThat(cancelledPartitionCount(fannedOut.instanceId())).isEqualTo(1);
    Integer selectedJobPartitionCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT, fannedOut.instanceId());
    assertThat(selectedJobPartitionCount).isZero();

    Long workflowRunId =
        jdbcTemplate.queryForObject(selectWorkflowRunIdSql, Long.class, fannedOut.instanceId());
    String selectedRunStatus =
        jdbcTemplate.queryForObject(selectRunStatusSql, String.class, TENANT, workflowRunId);
    assertThat(selectedRunStatus).isEqualTo(WorkflowRunStatus.FAILED.code());
  }

  @Test
  @DisplayName("claim 已先锁住 task 时取消必须让 claim 获胜且不留下半状态")
  void cancellationRace_claimWinsWithoutPartialState() throws Exception {
    final String setLocalSql = """
        set local lock_timeout = '5s'
    """;

    final String selectIdSql = """
        select id from batch.job_task where tenant_id = ? and id = ? for update
    """;
    final String selectPgBackendPidSql = """
        select pg_backend_pid()
    """;

    FannedOutInstance fannedOut = launchBundle(1);
    JobTaskEntity task = jobTaskMapper
        .selectByQuery(JobTaskQuery.ofJobInstance(TENANT, fannedOut.instanceId()))
        .getFirst();
    JobPartitionEntity partition = jobPartitionMapper.selectById(TENANT, task.getJobPartitionId());
    assertThat(task.getTaskStatus()).isEqualTo(TaskStatus.READY.code());
    assertThat(partition.getPartitionStatus()).isEqualTo(PartitionStatus.READY.code());

    CountDownLatch taskLocked = new CountDownLatch(1);
    CountDownLatch releaseClaim = new CountDownLatch(1);
    CompletableFuture<Integer> cancellationPid = new CompletableFuture<>();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<JobTaskEntity> claim = pool.submit(() -> transactionTemplate.execute(status -> {
        jdbcTemplate.execute(setLocalSql);
        jdbcTemplate.queryForObject(selectIdSql, Long.class, TENANT, task.getId());
        taskLocked.countDown();
        awaitBarrier(releaseClaim);
        return taskExecutionService.assignWorker(
            TENANT, task.getId(), fannedOut.seed().workerCode());
      }));
      assertThat(taskLocked.await(10, TimeUnit.SECONDS)).isTrue();

      Future<?> cancellation = pool.submit(() -> transactionTemplate.execute(status -> {
        jdbcTemplate.execute(setLocalSql);
        cancellationPid.complete(jdbcTemplate.queryForObject(selectPgBackendPidSql, Integer.class));
        return instanceManagementApplicationService.cancelPartition(TENANT, partition.getId());
      }));
      awaitDatabaseLockWait(cancellationPid.get(5, TimeUnit.SECONDS));
      releaseClaim.countDown();

      JobTaskEntity claimed = claim.get(10, TimeUnit.SECONDS);
      assertThat(claimed.getTaskStatus()).isEqualTo(TaskStatus.RUNNING.code());
      assertThatThrownBy(() -> cancellation.get(10, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(BizException.class);

      assertThat(jobTaskMapper.selectById(TENANT, task.getId()).getTaskStatus())
          .isEqualTo(TaskStatus.RUNNING.code());
      assertThat(jobPartitionMapper.selectById(TENANT, partition.getId()).getPartitionStatus())
          .isEqualTo(PartitionStatus.RUNNING.code());
      assertThat(cancelledPartitionCount(fannedOut.instanceId())).isZero();
      assertThat(jobInstanceMapper.selectById(TENANT, fannedOut.instanceId()).getInstanceStatus())
          .isEqualTo(JobInstanceStatus.RUNNING.code());
    } finally {
      releaseClaim.countDown();
      pool.shutdownNow();
      assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  @DisplayName("取消先提交时并发 claim 被拒绝,取消状态与分区及父级一致")
  void cancellationRace_cancellationWinsWithoutPartialState() throws Exception {
    final String setLocalSql = """
        set local lock_timeout = '5s'
    """;

    final String selectPgBackendPidSql = """
        select pg_backend_pid()
    """;

    FannedOutInstance fannedOut = launchBundle(1);
    JobTaskEntity task = jobTaskMapper
        .selectByQuery(JobTaskQuery.ofJobInstance(TENANT, fannedOut.instanceId()))
        .getFirst();
    JobPartitionEntity partition = jobPartitionMapper.selectById(TENANT, task.getJobPartitionId());
    CountDownLatch cancellationApplied = new CountDownLatch(1);
    CountDownLatch releaseCancellation = new CountDownLatch(1);
    CompletableFuture<Integer> claimPid = new CompletableFuture<>();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<?> cancellation = pool.submit(() -> transactionTemplate.execute(status -> {
        jdbcTemplate.execute(setLocalSql);
        var result =
            instanceManagementApplicationService.cancelPartition(TENANT, partition.getId());
        cancellationApplied.countDown();
        awaitBarrier(releaseCancellation);
        return result;
      }));
      assertThat(cancellationApplied.await(10, TimeUnit.SECONDS)).isTrue();

      Future<JobTaskEntity> claim = pool.submit(() -> transactionTemplate.execute(status -> {
        jdbcTemplate.execute(setLocalSql);
        claimPid.complete(jdbcTemplate.queryForObject(selectPgBackendPidSql, Integer.class));
        return taskExecutionService.assignWorker(
            TENANT, task.getId(), fannedOut.seed().workerCode());
      }));
      awaitDatabaseLockWait(claimPid.get(5, TimeUnit.SECONDS));
      releaseCancellation.countDown();

      cancellation.get(10, TimeUnit.SECONDS);
      JobTaskEntity claimResult = claim.get(10, TimeUnit.SECONDS);
      assertThat(claimResult).isNull();
      assertThat(jobTaskMapper.selectById(TENANT, task.getId()).getTaskStatus())
          .isEqualTo(TaskStatus.CANCELLED.code());
      assertThat(jobPartitionMapper.selectById(TENANT, partition.getId()).getPartitionStatus())
          .isEqualTo(PartitionStatus.CANCELLED.code());
      assertThat(cancelledPartitionCount(fannedOut.instanceId())).isEqualTo(1);
      assertThat(jobInstanceMapper.selectById(TENANT, fannedOut.instanceId()).getInstanceStatus())
          .isEqualTo(JobInstanceStatus.FAILED.code());
    } finally {
      releaseCancellation.countDown();
      pool.shutdownNow();
      assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  // ---- helpers（复用 LaunchIntegrationFixture + WorkerClaim/TaskBatch 的 claim-report 范式）----

  /**
   * 用 {@link LaunchIntegrationFixture#prepareBundleLaunchWithWorker} seed 一个 {@code BUNDLE_IMPORT}
   * 束作业 + 单 ONLINE worker,再通过 Workflow 根运行展开成 {@code count} 个 partition/task(单
   * instance)。 束的 IMPORT 绑定 profile = 源文件 + 模板,每项一 partition(异构、各自绑定)。
   */
  private FannedOutInstance launchBundle(int count) {
    return launchBundle(TENANT, count);
  }

  private FannedOutInstance launchBundle(String tenantId, int count) {
    final String insertFileRecordSql = """
            insert into batch.file_record (
                tenant_id, file_category, file_name, file_format_type, storage_type,
                storage_path, source_type, file_status
            ) values (?, 'INPUT', ?, 'DELIMITED', 'LOCAL', ?, 'UPLOAD', 'RECEIVED')
            returning id
            """;

    WorkflowLaunchSeed seed = LaunchIntegrationFixture.prepareBundleLaunchWithWorker(
        jdbcTemplate, tenantId, "BUNDLE_IMPORT", "IMPORT");

    List<Map<String, Object>> bundleFiles = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      Long sourceFileId = jdbcTemplate.queryForObject(
          insertFileRecordSql,
          Long.class,
          tenantId,
          seed.jobCode() + "-" + i,
          "/test/partition-join/" + seed.requestId() + "/" + i);
      bundleFiles.add(Map.of("sourceFileId", sourceFileId, "templateCode", "TPL_" + i));
    }

    LaunchRequest request = LaunchRequest.builder()
        .tenantId(tenantId)
        .workflowDefinitionId(seed.workflowDefinitionId())
        .bizDate(BIZ_DATE)
        .triggerType(TriggerType.EVENT)
        .requestId(seed.requestId())
        .traceId("trace-join-" + seed.requestId())
        .params(Map.of("bundleFiles", bundleFiles))
        .build();
    var response = workflowRunLaunchService.launch(request);

    Long jobInstanceId = LaunchIntegrationFixture.selectDispatchedJobInstanceId(
        jdbcTemplate, tenantId, response.instanceNo());
    JobInstanceEntity instance = jobInstanceMapper.selectById(tenantId, jobInstanceId);
    assertThat(instance).as("bundle launch must create the instance").isNotNull();

    List<JobPartitionEntity> partitions = jobPartitionMapper.selectByQuery(
        new JobPartitionQuery(tenantId, instance.getId(), null, null));
    assertThat(partitions).as("bundle must fan out into K partitions").hasSize(count);

    return new FannedOutInstance(seed, instance.getId());
  }

  /** 每个 partition 对应一个 task,用束的单 worker 逐个 CLAIM(task→RUNNING, partition→RUNNING + invocation)。 */
  private List<Shard> claimAllShards(FannedOutInstance fannedOut) {
    LaunchIntegrationFixture.refreshAssignableWorkersForTenant(jdbcTemplate, TENANT);
    List<JobTaskEntity> tasks = jobTaskMapper.selectByQuery(
        new JobTaskQuery(TENANT, fannedOut.instanceId(), null, null, null));
    List<Shard> shards = new ArrayList<>();
    for (JobTaskEntity task : tasks) {
      JobTaskEntity claimed = taskExecutionService.assignWorker(
          TENANT, task.getId(), fannedOut.seed().workerCode());
      assertThat(claimed).isNotNull();
      JobPartitionEntity partition =
          jobPartitionMapper.selectById(TENANT, task.getJobPartitionId());
      assertThat(partition.getPartitionStatus()).isEqualTo(PartitionStatus.RUNNING.code());
      assertThat(partition.getCurrentInvocationId()).isNotBlank();
      shards.add(new Shard(task.getId(), task.getJobPartitionId()));
    }
    return shards;
  }

  /** 生产 REPORT 入口:worker 上报 outcome → orchestrator 状态机推进(真 {@code applyTaskOutcome})。 */
  private void reportOutcome(Shard shard, boolean success) {
    JobPartitionEntity partition = jobPartitionMapper.selectById(TENANT, shard.partitionId());
    TaskOutcomeCommand command = TaskOutcomeCommand.builder()
        .tenantId(TENANT)
        .taskId(shard.taskId())
        .success(success)
        .resultSummary(success ? "{\"records\":10}" : null)
        .errorCode(success ? null : "E_SHARD_FAIL")
        .errorMessage(success ? null : "intentional shard failure for join gate")
        .partitionInvocationId(partition.getCurrentInvocationId())
        .build();
    taskExecutionService.applyTaskOutcome(command);
  }

  private String partitionStatus(Shard shard) {
    return jobPartitionMapper.selectById(TENANT, shard.partitionId()).getPartitionStatus();
  }

  private int successPartitionCount(Long instanceId) {
    return countColumn("success_partition_count", instanceId);
  }

  private int failedPartitionCount(Long instanceId) {
    return countColumn("failed_partition_count", instanceId);
  }

  private int cancelledPartitionCount(Long instanceId) {
    return countColumn("cancelled_partition_count", instanceId);
  }

  private int countColumn(String column, Long instanceId) {
    final String selectStatementSql = """
      select %s from batch.job_instance where tenant_id = ? and id = ?\
      """.formatted(column);
    Integer value =
        jdbcTemplate.queryForObject(selectStatementSql, Integer.class, TENANT, instanceId);
    return value == null ? 0 : value;
  }

  private void awaitDatabaseLockWait(int pid) {
    final String selectExistsSql = """
        select exists(select 1 from pg_stat_activity where pid = ? and wait_event_type = 'Lock')
    """;

    Awaitility.await()
        .alias("状态竞争必须在 PostgreSQL 中实际等待行锁")
        .atMost(3, TimeUnit.SECONDS)
        .until(() ->
            Boolean.TRUE.equals(jdbcTemplate.queryForObject(selectExistsSql, Boolean.class, pid)));
  }

  private static void awaitBarrier(CountDownLatch barrier) {
    try {
      if (!barrier.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("concurrency test barrier timed out");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("concurrency test interrupted", interrupted);
    }
  }

  private record FannedOutInstance(WorkflowLaunchSeed seed, Long instanceId) {}

  private record Shard(Long taskId, Long partitionId) {}
}
