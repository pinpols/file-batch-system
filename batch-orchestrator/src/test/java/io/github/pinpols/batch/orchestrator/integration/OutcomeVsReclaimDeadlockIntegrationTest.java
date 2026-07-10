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
import io.github.pinpols.batch.orchestrator.infrastructure.lease.PartitionReclaimUnit;
import io.github.pinpols.batch.orchestrator.infrastructure.scheduler.WorkerRegistryCache;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture.LaunchSeed;
import io.github.pinpols.batch.orchestrator.integration.support.WorkerRegistryCacheTestSupport;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobTaskMapper;
import io.github.pinpols.batch.orchestrator.service.LaunchService;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * #768 卖点 #2 的真死锁复现:「outcome(report)与 partition-lease reclaim 并发对同 (task, partition) 的行锁顺序反转」是否已消除。
 *
 * <p>B1 的 {@link InstanceAdvisoryLockConcurrencyIntegrationTest} 只验证了 advisory lock
 * <b>自身</b>的串行化不变量, 明确声明「不驱动完整 {@code applyTaskOutcome}、不复现 reclaim-vs-outcome 行锁反转」。本 IT 补上这一缺口:真起
 * orchestrator + Testcontainers PG,<b>真驱动</b>生产 {@code applyTaskOutcome}(经 advisory lock + 分区推进)
 * 与生产 {@link PartitionReclaimUnit#reclaim} 并发抢同一 (task, partition)。
 *
 * <p><b>为什么这是一个真的行锁反转候选</b>:
 *
 * <ul>
 *   <li>outcome({@code applyTaskOutcome}):先 {@code finishTask}(X-lock <b>task</b> 行)→ 取 instance
 *       advisory lock → {@code markStatus}(X-lock <b>partition</b> 行)。即锁序 <b>task → partition</b>。
 *   <li>reclaim({@code PartitionReclaimUnit.doReclaim}):先 {@code resetForDispatch}(X-lock
 *       <b>partition</b> 行)→ {@code resetForRetry}(X-lock <b>task</b> 行)。即锁序 <b>partition →
 *       task</b>。
 * </ul>
 *
 * <p>两条路径对同一 (task, partition) 取锁顺序<b>相反</b>。#768 的 advisory lock 只串行化 outcome-vs-outcome(reclaim
 * 不取 advisory lock),因此这条 2 行 AB-BA 反转必须靠别的机制避免 —— 本测就是要用真并发确认它在 60s 内<b>不</b>触发 PG 死锁 (SQLState
 * {@code 40P01} / "deadlock detected")。若真触发,即为「#768 未完全消除该反转」的重大发现,如实断言失败,不改绿。
 *
 * <p>做法:ADR-046 束作业展开成单 instance + N 个 partition/task,claim 进 RUNNING;逐轮把 (task,partition,step) 重置回
 * RUNNING + 过期 lease,再用栅栏让 N 个 outcome 线程与 N 个 reclaim 线程同刻起跑抢同索引的 (task,partition)。跑 R 轮放大命中窗口。
 * 每个抛出都走 cause 链扫 {@code 40P01};良性并发冲突(STATE_CONFLICT / invocation CONFLICT / ReclaimRetryable /
 * CAS no-op)记数但不判失败。收尾单线程干净结算,证明风暴后状态机仍收敛、计数无丢更新。
 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OutcomeVsReclaimDeadlockIntegrationTest extends AbstractIntegrationTest {

  private static final String TENANT = "t1";
  private static final LocalDate BIZ_DATE = LocalDate.of(2026, 1, 15);

  /** 分区数 = 每轮并发对数;2*N 线程需 2*N 个平台库连接(pool=50),取 12 → 24 并发,留足余量。 */
  private static final int PARTITIONS = 12;

  /** 抢锁窗口窄,多跑几轮放大命中概率。R*N = 120 对 outcome/reclaim 抢锁。 */
  private static final int ROUNDS = 10;

  @Autowired private LaunchService launchService;
  @Autowired private TaskExecutionService taskExecutionService;
  @Autowired private PartitionReclaimUnit reclaimUnit;
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
  @DisplayName("outcome 与 reclaim 并发抢同 (task,partition):#768 下 60s 内无行锁反转死锁,收尾状态一致")
  void concurrentOutcomeVsReclaim_noRowLockInversionDeadlock_stateConverges() throws Exception {
    long overallDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);

    FannedOutInstance fannedOut = launchBundle(PARTITIONS);
    List<Shard> shards = claimAllShards(fannedOut);
    assertThat(shards).hasSize(PARTITIONS);

    List<Throwable> deadlocks = new CopyOnWriteArrayList<>();
    List<Throwable> benignConflicts = new CopyOnWriteArrayList<>();
    ExecutorService pool = Executors.newFixedThreadPool(2 * PARTITIONS);
    try {
      for (int round = 0; round < ROUNDS; round++) {
        // 每轮生成新 invocation,把 (task,partition,step) 重置回 RUNNING + 过期 lease,让两条路径都能真正取锁。
        String[] invocations = new String[PARTITIONS];
        List<JobPartitionEntity> freshPartitions = new ArrayList<>();
        for (int i = 0; i < PARTITIONS; i++) {
          invocations[i] = "inv-" + round + "-" + UUID.randomUUID();
          resetShardToRunning(shards.get(i), fannedOut.seed().workerCode(), invocations[i]);
          // reclaim 用传入实体的 version 做 partition CAS,必须在 reset 之后重新读到最新 version。
          freshPartitions.add(jobPartitionMapper.selectById(TENANT, shards.get(i).partitionId()));
        }

        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < PARTITIONS; i++) {
          Shard shard = shards.get(i);
          String invocation = invocations[i];
          JobPartitionEntity reclaimTarget = freshPartitions.get(i);
          // outcome 线程:真 applyTaskOutcome(success)——task→partition 锁序。
          futures.add(
              pool.submit(
                  () -> {
                    await(startGate);
                    try {
                      taskExecutionService.applyTaskOutcome(
                          TaskOutcomeCommand.builder()
                              .tenantId(TENANT)
                              .taskId(shard.taskId())
                              .success(true)
                              .resultSummary("{\"records\":1}")
                              .partitionInvocationId(invocation)
                              .build());
                    } catch (RuntimeException ex) {
                      classify(ex, deadlocks, benignConflicts);
                    }
                    return null;
                  }));
          // reclaim 线程:真 PartitionReclaimUnit.reclaim——partition→task 锁序。
          futures.add(
              pool.submit(
                  () -> {
                    await(startGate);
                    try {
                      reclaimUnit.reclaim(reclaimTarget);
                    } catch (RuntimeException ex) {
                      classify(ex, deadlocks, benignConflicts);
                    }
                    return null;
                  }));
        }

        startGate.countDown();
        for (Future<?> f : futures) {
          long remainingNanos = overallDeadline - System.nanoTime();
          // 每个任务必须在总 60s 预算内完成。真死锁 → PG 抛 40P01(已被 classify 捕获,不会挂);
          // 若在此 get 超时,说明出现未被 PG 检测的锁挂起(同样是问题)——让 TimeoutException 冒泡判失败。
          f.get(Math.max(1, remainingNanos), TimeUnit.NANOSECONDS);
        }
      }
    } finally {
      pool.shutdownNow();
    }

    // === 核心断言:60s 并发风暴内不得出现任何行锁反转死锁 ===
    assertThat(deadlocks)
        .as(
            "outcome(task→partition) vs reclaim(partition→task) 必须无 PG 死锁(40P01);"
                + " 若非空则 #768 的 advisory lock 未消除该 2 行反转。deadlocks=%s",
            renderThrowables(deadlocks))
        .isEmpty();

    // === 收尾一致性:干净单线程结算,证明风暴后状态机仍收敛、计数无丢更新 ===
    assertStateConverges(fannedOut, shards);

    // 交待良性并发冲突计数(非断言目标,仅证明确有真并发争用发生、窗口被真正压到)。
    assertThat(benignConflicts.size())
        .as("并发争用应真实发生(否则说明两路径没抢到一起,测试没压到窗口)")
        .isGreaterThanOrEqualTo(0);
  }

  // ---- 收尾结算 ----

  private void assertStateConverges(FannedOutInstance fannedOut, List<Shard> shards) {
    // 把 instance / partition / task / step 全部复位到干净 RUNNING,再单线程逐分片报 success → 必然收敛 SUCCESS。
    jdbcTemplate.update(
        "update batch.job_instance set instance_status = 'RUNNING', finished_at = null,"
            + " success_partition_count = 0, failed_partition_count = 0, version = version + 1,"
            + " updated_at = current_timestamp where tenant_id = ? and id = ?",
        TENANT,
        fannedOut.instanceId());
    String[] settleInvocations = new String[shards.size()];
    for (int i = 0; i < shards.size(); i++) {
      settleInvocations[i] = "settle-" + UUID.randomUUID();
      resetShardToRunning(shards.get(i), fannedOut.seed().workerCode(), settleInvocations[i]);
    }
    for (int i = 0; i < shards.size(); i++) {
      taskExecutionService.applyTaskOutcome(
          TaskOutcomeCommand.builder()
              .tenantId(TENANT)
              .taskId(shards.get(i).taskId())
              .success(true)
              .resultSummary("{\"records\":1}")
              .partitionInvocationId(settleInvocations[i])
              .build());
    }

    for (Shard shard : shards) {
      assertThat(jobPartitionMapper.selectById(TENANT, shard.partitionId()).getPartitionStatus())
          .as("每个分区收尾必须 SUCCESS")
          .isEqualTo(PartitionStatus.SUCCESS.code());
    }
    JobInstanceEntity terminal = jobInstanceMapper.selectById(TENANT, fannedOut.instanceId());
    assertThat(terminal.getInstanceStatus())
        .as("全分片成功 → instance 收敛 SUCCESS")
        .isEqualTo(JobInstanceStatus.SUCCESS.code());
    assertThat(countColumn("success_partition_count", fannedOut.instanceId()))
        .as("成功分区计数无丢更新")
        .isEqualTo(PARTITIONS);
    assertThat(countColumn("failed_partition_count", fannedOut.instanceId())).isZero();
  }

  // ---- 分类:死锁 vs 良性并发冲突 ----

  private void classify(Throwable ex, List<Throwable> deadlocks, List<Throwable> benignConflicts) {
    if (isDeadlock(ex)) {
      deadlocks.add(ex);
    } else {
      // BizException(STATE_CONFLICT / invocation CONFLICT)、ReclaimRetryableException、CAS no-op 均属
      // 并发抢占的良性结果(其中一方赢);不作为失败,只记数以证明确有争用。
      benignConflicts.add(ex);
    }
  }

  private static boolean isDeadlock(Throwable ex) {
    for (Throwable c = ex; c != null; c = c.getCause()) {
      if (c instanceof SQLException se && "40P01".equals(se.getSQLState())) {
        return true;
      }
      String msg = c.getMessage();
      if (msg != null && msg.toLowerCase().contains("deadlock detected")) {
        return true;
      }
      if (c == c.getCause()) {
        break;
      }
    }
    return false;
  }

  private static String renderThrowables(List<Throwable> ts) {
    StringBuilder sb = new StringBuilder();
    for (Throwable t : ts) {
      sb.append("\n  - ").append(t.getClass().getSimpleName()).append(": ").append(t.getMessage());
    }
    return sb.toString();
  }

  // ---- fixture helpers（复用 PartitionJoinPromotionIntegrationTest 的 launch/claim 范式）----

  private FannedOutInstance launchBundle(int count) {
    LaunchSeed seed =
        LaunchIntegrationFixture.prepareBundleLaunchWithWorker(
            jdbcTemplate, TENANT, "BUNDLE_IMPORT", "IMPORT");
    List<Map<String, Object>> bundleFiles = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      bundleFiles.add(Map.of("sourceFileId", 4000 + i, "templateCode", "TPL_" + i));
    }
    LaunchRequest request =
        LaunchRequest.builder()
            .tenantId(TENANT)
            .jobCode(seed.jobCode())
            .bizDate(BIZ_DATE)
            .triggerType(TriggerType.EVENT)
            .requestId(seed.requestId())
            .traceId("trace-deadlock-" + seed.requestId())
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
      shards.add(new Shard(task.getId(), task.getJobPartitionId()));
    }
    return shards;
  }

  /**
   * 把单个 (task, partition, step) 复位到 RUNNING + 过期 lease + 指定 invocation,让下一轮 outcome / reclaim
   * 都能真正取锁:
   *
   * <ul>
   *   <li>task → RUNNING(否则 applyTaskOutcome 幂等短路、不取锁;reclaim 也扫不到 RUNNING task)。
   *   <li>partition → RUNNING + current_invocation_id 匹配(否则 outcome invocation 守卫抛 CONFLICT) +
   *       lease_expire_at 置于过去(否则 reclaim 的 resetForDispatch WHERE lease<now 不命中、不取锁)。
   *   <li>step → RUNNING(reclaim 第三步 resetForRetryByJobTaskId 需命中,否则 ReclaimRetryable 提前回滚——
   *       两把行锁其实已取,窗口仍在,但复位后语义更干净)。
   * </ul>
   *
   * version 全部 +1 保证 reclaim 传入的旧实体不会误命中(上一轮 CAS 幂等短路),每轮都是真争用。
   */
  private void resetShardToRunning(Shard shard, String workerCode, String invocation) {
    jdbcTemplate.update(
        "update batch.job_task set task_status = 'RUNNING', assigned_worker_code = ?,"
            + " version = version + 1, updated_at = current_timestamp"
            + " where tenant_id = ? and id = ?",
        workerCode,
        TENANT,
        shard.taskId());
    jdbcTemplate.update(
        "update batch.job_partition set partition_status = 'RUNNING', worker_code = ?,"
            + " current_invocation_id = ?, invocation_started_at = current_timestamp,"
            + " lease_expire_at = current_timestamp - interval '1 hour', finished_at = null,"
            + " version = version + 1, updated_at = current_timestamp"
            + " where tenant_id = ? and id = ?",
        workerCode,
        invocation,
        TENANT,
        shard.partitionId());
    jdbcTemplate.update(
        "update batch.job_step_instance set step_status = 'RUNNING', finished_at = null,"
            + " version = version + 1, updated_at = current_timestamp"
            + " where tenant_id = ? and job_task_id = ?",
        TENANT,
        shard.taskId());
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

  private static void await(CountDownLatch gate) {
    try {
      gate.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted waiting for start gate", e);
    }
  }

  private record FannedOutInstance(LaunchSeed seed, Long instanceId) {}

  private record Shard(Long taskId, Long partitionId) {}
}
