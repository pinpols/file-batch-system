package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import io.github.pinpols.batch.common.enums.PartitionStatus;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskExecutionService;
import io.github.pinpols.batch.orchestrator.domain.command.TaskOutcomeCommand;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobPartitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.domain.query.JobPartitionQuery;
import io.github.pinpols.batch.orchestrator.domain.query.JobTaskQuery;
import io.github.pinpols.batch.orchestrator.infrastructure.scheduler.WorkerRegistryCache;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture.LaunchSeed;
import io.github.pinpols.batch.orchestrator.integration.support.WorkerRegistryCacheTestSupport;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobTaskMapper;
import io.github.pinpols.batch.orchestrator.service.LaunchService;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

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
class PartitionJoinPromotionIntegrationTest extends AbstractIntegrationTest {

  private static final String TENANT = "t1";
  private static final LocalDate BIZ_DATE = LocalDate.of(2026, 1, 15);

  @Autowired private LaunchService launchService;
  @Autowired private TaskExecutionService taskExecutionService;
  @Autowired private JobInstanceMapper jobInstanceMapper;
  @Autowired private JobPartitionMapper jobPartitionMapper;
  @Autowired private JobTaskMapper jobTaskMapper;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private WorkerRegistryCache workerRegistryCache;

  @BeforeEach
  void refreshWorkers() {
    WorkerRegistryCacheTestSupport.evictTenantWorkerSelectors(workerRegistryCache, TENANT);
    LaunchIntegrationFixture.refreshAssignableWorkersForTenant(jdbcTemplate, TENANT);
  }

  @Test
  @DisplayName("某分片 FAILED → instance 落 PARTIAL_FAILED 而非 SUCCESS(3 分区:2 成功 + 1 失败)")
  void instanceDoesNotPromoteToSuccess_whenOneShardFailed() {
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
    assertThat(failedPartitionCount(fannedOut.instanceId())).isEqualTo(0);

    // assert:重放不产生重复的结果版本 —— 每个 job_instance 至多 1 行 result_version(writer 幂等守护)。
    Long resultVersionRows =
        jdbcTemplate.queryForObject(
            "select count(*) from batch.result_version"
                + " where tenant_id = ? and job_instance_id = ?",
            Long.class,
            TENANT,
            fannedOut.instanceId());
    assertThat(resultVersionRows).isEqualTo(1L);
  }

  // ---- helpers（复用 LaunchIntegrationFixture + WorkerClaim/TaskBatch 的 claim-report 范式）----

  /**
   * 用 {@link LaunchIntegrationFixture#prepareBundleLaunchWithWorker} seed 一个 {@code BUNDLE_IMPORT}
   * 束作业 + 单 ONLINE worker,再 {@link LaunchService#launch} 展开成 {@code count} 个 partition/task(单
   * instance)。 束的 IMPORT 绑定 profile = 源文件 + 模板,每项一 partition(异构、各自绑定)。
   */
  private FannedOutInstance launchBundle(int count) {
    LaunchSeed seed =
        LaunchIntegrationFixture.prepareBundleLaunchWithWorker(
            jdbcTemplate, TENANT, "BUNDLE_IMPORT", "IMPORT");

    List<Map<String, Object>> bundleFiles = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      bundleFiles.add(Map.of("sourceFileId", 3000 + i, "templateCode", "TPL_" + i));
    }

    LaunchRequest request =
        LaunchRequest.builder()
            .tenantId(TENANT)
            .jobCode(seed.jobCode())
            .bizDate(BIZ_DATE)
            .triggerType(TriggerType.EVENT)
            .requestId(seed.requestId())
            .traceId("trace-join-" + seed.requestId())
            .params(Map.of("bundleFiles", bundleFiles))
            .build();
    launchService.launch(request);

    JobInstanceEntity instance =
        jobInstanceMapper.selectByTenantAndDedupKey(TENANT, seed.dedupKey());
    assertThat(instance).as("bundle launch must create the instance").isNotNull();

    List<JobPartitionEntity> partitions =
        jobPartitionMapper.selectByQuery(
            new JobPartitionQuery(TENANT, instance.getId(), null, null));
    assertThat(partitions).as("bundle must fan out into K partitions").hasSize(count);

    return new FannedOutInstance(seed, instance.getId());
  }

  /** 每个 partition 对应一个 task,用束的单 worker 逐个 CLAIM(task→RUNNING, partition→RUNNING + invocation)。 */
  private List<Shard> claimAllShards(FannedOutInstance fannedOut) {
    LaunchIntegrationFixture.refreshAssignableWorkersForTenant(jdbcTemplate, TENANT);
    List<JobTaskEntity> tasks =
        jobTaskMapper.selectByQuery(
            new JobTaskQuery(TENANT, fannedOut.instanceId(), null, null, null));
    List<Shard> shards = new ArrayList<>();
    for (JobTaskEntity task : tasks) {
      JobTaskEntity claimed =
          taskExecutionService.assignWorker(TENANT, task.getId(), fannedOut.seed().workerCode());
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
    TaskOutcomeCommand command =
        TaskOutcomeCommand.builder()
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

  private int countColumn(String column, Long instanceId) {
    Integer value =
        jdbcTemplate.queryForObject(
            "select " + column + " from batch.job_instance where tenant_id = ? and id = ?",
            Integer.class,
            TENANT,
            instanceId);
    return value == null ? 0 : value;
  }

  private record FannedOutInstance(LaunchSeed seed, Long instanceId) {}

  private record Shard(Long taskId, Long partitionId) {}
}
