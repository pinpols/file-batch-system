package com.example.batch.orchestrator.infrastructure.scheduler;

import com.example.batch.common.enums.JobInstanceStatus;
import com.example.batch.common.enums.PartitionStatus;
import com.example.batch.common.enums.TaskStatus;
import com.example.batch.common.enums.WorkflowRunStatus;
import com.example.batch.common.logging.BatchMdc;
import com.example.batch.common.logging.StructuredLogField;
import com.example.batch.common.logging.SwallowedExceptionLogger;
import com.example.batch.common.persistence.entity.WorkflowRunEntity;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.orchestrator.application.engine.OutboxEventKeyGenerator;
import com.example.batch.orchestrator.application.engine.TaskDispatchOutboxService;
import com.example.batch.orchestrator.application.ratelimit.RateLimitAction;
import com.example.batch.orchestrator.application.ratelimit.TenantActionRateLimiter;
import com.example.batch.orchestrator.application.scheduler.ResourceScheduler;
import com.example.batch.orchestrator.application.service.task.OrchestratorJobMappers;
import com.example.batch.orchestrator.application.service.task.PartitionLifecycleService;
import com.example.batch.orchestrator.application.service.workflow.OrchestratorWorkflowMappers;
import com.example.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;
import com.example.batch.orchestrator.domain.entity.JobDefinitionEntity;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.JobPartitionEntity;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.domain.param.MarkInstanceRunningParam;
import com.example.batch.orchestrator.domain.scheduling.ResourceSchedulingDecision;
import com.example.batch.orchestrator.domain.scheduling.ResourceSchedulingRequest;
import com.example.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import com.example.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * WAITING 分片重派定时器：每 {@code waiting-dispatch-interval-millis}（默认 10s）扫一批 WAITING 状态分片，
 * 满足资源/窗口/worker 条件的升级为 READY 并写 outbox 派发事件。
 *
 * <p>关键机制：
 *
 * <ul>
 *   <li><b>ShedLock</b> {@code waiting_partition_dispatch}：保证多实例部署下同一时刻只有一台执行， 避免重复消费 WAITING 分片。
 *   <li><b>公平排序</b>：按 {@code (fairnessScore desc, priority desc, partitionId asc)} 排序后派发，
 *       防止单租户/单优先级独占队列；fairnessScore 由 {@code ResourceScheduler} 结合租户权重/队列权重计算。
 *   <li><b>租户限流</b>：每次 release 走 {@code DISPATCH_RELEASE} 令牌桶，超额直接跳过（下轮再试）。
 *   <li><b>优雅下线</b>：{@code gracefulShutdown.isDraining()} 为 true 时整批跳过，不新派任何分片， 让现有任务收尾后进程退出。
 *   <li><b>连锁状态推进</b>：release 成功后把 job_instance（WAITING→RUNNING）与 workflow_run（CREATED→RUNNING）
 *       同步推进，避免"分片已跑但实例仍在 WAITING"的 UI 口径不一致。
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WaitingPartitionDispatchScheduler {

  private final ResourceScheduler resourceScheduler;
  private final BatchOrchestratorGovernanceProperties governance;
  private final OrchestratorJobMappers jobMappers;
  private final OrchestratorWorkflowMappers workflowMappers;
  private final OrchestratorConfigCacheService configCacheService;
  private final TaskDispatchOutboxService taskDispatchOutboxService;
  private final PartitionLifecycleService partitionLifecycleService;
  private final TenantActionRateLimiter tenantActionRateLimiter;
  private final OrchestratorGracefulShutdown gracefulShutdown;
  private final ObjectProvider<WaitingPartitionDispatchScheduler> selfProvider;

  /** WAITING partition 会在这里重新进入资源判断，只有满足窗口/并发/worker 条件才会真正出队。 */
  @Scheduled(
      fixedDelayString = "${batch.resource-scheduler.waiting-dispatch-interval-millis:10000}")
  @SchedulerLock(
      name = "waiting_partition_dispatch",
      lockAtMostFor = "PT1M",
      lockAtLeastFor = "PT5S")
  public void dispatchWaitingPartitions() {
    if (gracefulShutdown.isDraining()) {
      return;
    }
    List<JobPartitionEntity> waitingPartitions =
        jobMappers.jobPartitionMapper.selectWaitingPartitionsGlobal(
            governance.resourceScheduler().getWaitingDispatchBatchSize(),
            PartitionStatus.WAITING.code());
    if (waitingPartitions.isEmpty()) {
      log.debug("no WAITING partitions this tick");
      return;
    }
    // 每 10 秒一次 tick，evaluate 数本身没业务进展信息（仍 WAITING 表明一直被限流挡）；
    // 改 DEBUG 防 3 小时积 1000+ 噪音。真有 partition 被释放时由 line 269 的"released" 日志体现。
    log.debug("waiting dispatch tick: {} WAITING partitions to evaluate", waitingPartitions.size());
    List<WaitingDispatchCandidate> candidates = new ArrayList<>();
    for (JobPartitionEntity partition : waitingPartitions) {
      WaitingDispatchCandidate candidate;
      try {
        candidate = buildCandidate(partition);
      } catch (RuntimeException exception) {
        // 单个候选的资源评估失败（常见于 quota_runtime_state 的 @Version CAS 冲突）不应中断整批 tick，
        // 否则会出现"第一条 partition 撞 OLFE → 整轮 scheduled 任务被 ErrorHandler 吞掉 → 后面几千条
        // WAITING 永远轮不到"的级联阻塞。跳过本条，下 tick 重新评估。
        log.warn(
            "buildCandidate failed, skipping this tick's partition: tenantId={}, partitionId={},"
                + " error={}",
            partition == null ? null : partition.getTenantId(),
            partition == null ? null : partition.getId(),
            exception.getMessage());
        continue;
      }
      if (candidate != null) {
        candidates.add(candidate);
      }
    }
    Comparator<WaitingDispatchCandidate> comparator =
        Comparator.comparingLong(WaitingDispatchCandidate::fairnessScore)
            .reversed()
            .thenComparing(Comparator.comparingInt(WaitingDispatchCandidate::priority).reversed())
            .thenComparingLong(WaitingDispatchCandidate::partitionId);
    // 逐个 partition 走独立 REQUIRES_NEW 事务：release + outbox + instance/workflow 状态推进作为原子单元。
    // 一个 partition 出错（例如乐观锁冲突）只回滚它自己，其他候选继续；self-proxy 触发 @Transactional AOP。
    List<WaitingDispatchCandidate> sorted = candidates.stream().sorted(comparator).toList();
    for (WaitingDispatchCandidate candidate : sorted) {
      try {
        selfProvider
            .getObject()
            .dispatchWaitingPartition(
                candidate.partition(),
                candidate.task(),
                candidate.jobInstance(),
                candidate.decision());
      } catch (RuntimeException exception) {
        log.warn(
            "dispatch waiting partition failed, will retry next tick: tenantId={},"
                + " partitionId={}, error={}",
            candidate.partition().getTenantId(),
            candidate.partition().getId(),
            exception.getMessage());
      }
    }
  }

  private WaitingDispatchCandidate buildCandidate(JobPartitionEntity partition) {
    if (partition == null) {
      return null;
    }
    JobTaskEntity task =
        jobMappers.jobTaskMapper.selectByPartitionAndSeq(
            partition.getTenantId(), partition.getId(), 1);
    if (task == null || !TaskStatus.CREATED.code().equals(task.getTaskStatus())) {
      log.debug(
          "skip partitionId={}: task missing or not CREATED (status={})",
          partition.getId(),
          task == null ? "null" : task.getTaskStatus());
      return null;
    }
    JobInstanceEntity jobInstance =
        jobMappers.jobInstanceMapper.selectById(
            partition.getTenantId(), partition.getJobInstanceId());
    if (jobInstance == null) {
      log.debug("skip partitionId={}: job_instance missing", partition.getId());
      return null;
    }
    JobDefinitionEntity jobDefinition =
        configCacheService.findEnabledJobDefinition(
            jobInstance.getTenantId(), jobInstance.getJobCode());
    ResourceSchedulingDecision decision =
        resourceScheduler.schedule(buildRequest(jobInstance, partition, task, jobDefinition));
    if (!decision.isDispatchable()) {
      log.debug(
          "skip partitionId={}: not dispatchable, reason={}, route={}",
          partition.getId(),
          decision.getReasonCode(),
          decision.getRoute() == null ? "null" : decision.getRoute().getAvailable());
      return null;
    }
    return new WaitingDispatchCandidate(partition, task, jobInstance, decision);
  }

  /**
   * MDC 包装层：给 {@link #executeDispatch} 套上 tenantId / traceId / jobInstanceId 上下文， 事务边界由
   * executeDispatch 自己管。纯粹是 ThreadLocal MDC 操作，不做 DB 调用，因此本方法 <b>不挂
   * {@code @Transactional}</b>，避免在一个只做 MDC + 代理委派的方法上空开事务。
   */
  public void dispatchWaitingPartition(
      JobPartitionEntity partition,
      JobTaskEntity task,
      JobInstanceEntity jobInstance,
      ResourceSchedulingDecision decision) {
    if (partition == null
        || task == null
        || jobInstance == null
        || decision == null
        || !decision.isDispatchable()) {
      return;
    }
    BatchMdc.withTenantAndTrace(
        jobInstance.getTenantId(),
        jobInstance.getTraceId(),
        () -> {
          BatchMdc.put(
              StructuredLogField.JOB_INSTANCE_ID,
              jobInstance.getId() == null ? null : String.valueOf(jobInstance.getId()));
          try {
            selfProvider.getObject().executeDispatch(partition, task, jobInstance, decision);
          } finally {
            BatchMdc.remove(StructuredLogField.JOB_INSTANCE_ID);
          }
        });
  }

  /**
   * 单条 WAITING 分片的派发事务：release partition/task → 写 outbox → 推进 job_instance / workflow_run
   * 全部在同一事务内提交，满足 CLAUDE.md §架构硬约束 "outbox_event 必须与任务状态写入处于同一事务"。
   *
   * <p>必须是 public 且通过 {@code selfProvider} self-proxy 调用才能让 Spring AOP 织入 {@code @Transactional}；
   * 调用方负责在 MDC 上下文中调用，事务本身不关心日志 MDC。
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void executeDispatch(
      JobPartitionEntity partition,
      JobTaskEntity task,
      JobInstanceEntity jobInstance,
      ResourceSchedulingDecision decision) {
    boolean allowed =
        tenantActionRateLimiter.tryConsume(
            jobInstance.getTenantId(), RateLimitAction.DISPATCH_RELEASE);
    if (!allowed) {
      return;
    }
    if (!partitionLifecycleService.releaseForDispatch(
        partition, task, PartitionStatus.WAITING.code(), TaskStatus.CREATED.code())) {
      return;
    }
    taskDispatchOutboxService.writeDispatchEvent(
        jobInstance,
        task,
        partition,
        jobInstance.getTraceId(),
        OutboxEventKeyGenerator.forDispatch(task.getTenantId(), task.getId()));
    if (JobInstanceStatus.WAITING.code().equals(jobInstance.getInstanceStatus())) {
      int updated =
          jobMappers.jobInstanceMapper.markRunning(
              MarkInstanceRunningParam.builder()
                  .tenantId(jobInstance.getTenantId())
                  .id(jobInstance.getId())
                  .instanceStatus(JobInstanceStatus.RUNNING.code())
                  .expectedPartitionCount(jobInstance.getExpectedPartitionCount())
                  .startedAt(Instant.now())
                  .expectedVersion(jobInstance.getVersion())
                  .build());
      if (updated > 0) {
        jobInstance.setVersion(
            (jobInstance.getVersion() == null ? 0L : jobInstance.getVersion()) + 1);
      }
    }
    WorkflowRunEntity workflowRun =
        workflowMappers.workflowRunMapper.selectByRelatedJobInstanceId(
            jobInstance.getTenantId(), jobInstance.getId());
    if (workflowRun != null
        && WorkflowRunStatus.CREATED.code().equals(workflowRun.getRunStatus())) {
      workflowMappers.workflowRunMapper.markRunning(
          workflowRun.getTenantId(),
          workflowRun.getId(),
          WorkflowRunStatus.RUNNING.code(),
          workflowRun.getCurrentNodeCode(),
          Instant.now());
    }
    log.info(
        "waiting partition released: tenantId={}, partitionId={},"
            + " taskId={}, fairnessScore={}, tenantWeight={},"
            + " queueWeight={}",
        partition.getTenantId(),
        partition.getId(),
        task.getId(),
        decision.getFairnessScore(),
        decision.getTenantWeight(),
        decision.getQueueWeight());
  }

  private ResourceSchedulingRequest buildRequest(
      JobInstanceEntity jobInstance,
      JobPartitionEntity partition,
      JobTaskEntity task,
      JobDefinitionEntity jobDefinition) {
    ResourceSchedulingRequest request = new ResourceSchedulingRequest();
    request.setTenantId(jobInstance.getTenantId());
    request.setJobCode(jobInstance.getJobCode());
    // workflow TASK 节点的 partition 由 SchedulePlanBuilder 按 sub-job 的 job_definition 填
    // input_snapshot.queueCode（如 DISPATCH 节点 → dispatch_queue），与 jobInstance.queue_code
    // （workflow 自己的 workflow_queue）不一致。优先用 partition input_snapshot 里写的 queueCode，
    // 否则 selector 按 workflow_queue 的 resource_tag=workflow 去找 DISPATCH worker，永远不会匹配到
    // capability_tags=[delivery] 的分发 worker → NO_AVAILABLE_WORKER 死循环。
    String partitionQueueCode = extractInputField(partition, "queueCode");
    request.setQueueCode(
        partitionQueueCode != null && !partitionQueueCode.isBlank()
            ? partitionQueueCode
            : jobInstance.getQueueCode());
    request.setWorkerGroup(
        partition.getWorkerGroup() == null
            ? jobInstance.getWorkerGroup()
            : partition.getWorkerGroup());
    request.setWorkerType(task.getTaskType());
    request.setPriority(jobInstance.getPriority());
    request.setRequestedPartitionCount(1);
    // windowCode 同理：优先读 partition（sub-job 的 window），否则回退到 workflow 的 job_definition
    String partitionWindowCode = extractInputField(partition, "windowCode");
    request.setWindowCode(
        partitionWindowCode != null && !partitionWindowCode.isBlank()
            ? partitionWindowCode
            : (jobDefinition == null ? null : jobDefinition.windowCode()));
    return request;
  }

  /** 从 partition.input_snapshot（JSON 字符串）里抽一个顶层字符串字段。畸形 JSON 返回 null。 */
  private static String extractInputField(JobPartitionEntity partition, String field) {
    if (partition == null || partition.getInputSnapshot() == null) {
      return null;
    }
    try {
      Object parsed = JsonUtils.fromJson(partition.getInputSnapshot(), Object.class);
      if (parsed instanceof Map<?, ?> map) {
        Object v = map.get(field);
        return v == null ? null : String.valueOf(v);
      }
    } catch (IllegalArgumentException ignored) {
      SwallowedExceptionLogger.info(
          WaitingPartitionDispatchScheduler.class, "catch:IllegalArgumentException", ignored);

      // 畸形 snapshot 不阻断调度
    }
    return null;
  }

  private record WaitingDispatchCandidate(
      JobPartitionEntity partition,
      JobTaskEntity task,
      JobInstanceEntity jobInstance,
      ResourceSchedulingDecision decision) {

    private long fairnessScore() {
      return decision == null || decision.getFairnessScore() == null
          ? 0L
          : decision.getFairnessScore();
    }

    private int priority() {
      return jobInstance == null || jobInstance.getPriority() == null
          ? 5
          : jobInstance.getPriority();
    }

    private long partitionId() {
      return partition == null || partition.getId() == null ? Long.MAX_VALUE : partition.getId();
    }
  }
}
