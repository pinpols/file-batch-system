package com.example.batch.orchestrator.application.service.task;

import com.example.batch.common.dto.EffectiveTaskConfig;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.enums.TaskStatus;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.observability.BatchMetricsNames;
import com.example.batch.common.utils.Guard;
import com.example.batch.common.utils.Texts;
import com.example.batch.orchestrator.application.service.task.TaskAssignmentService.TaskHeartbeatResult;
import com.example.batch.orchestrator.config.BundleBatchClaimProperties;
import com.example.batch.orchestrator.controller.TaskController.TaskClaimRequest;
import com.example.batch.orchestrator.controller.request.TaskCancelRequest;
import com.example.batch.orchestrator.controller.request.TaskClaimBatchRequest;
import com.example.batch.orchestrator.controller.request.TaskClaimBatchResponse;
import com.example.batch.orchestrator.controller.request.TaskClaimItemPayload;
import com.example.batch.orchestrator.controller.request.TaskClaimResultPayload;
import com.example.batch.orchestrator.controller.request.TaskExecutionReportDto;
import com.example.batch.orchestrator.controller.request.TaskHeartbeatRequest;
import com.example.batch.orchestrator.controller.request.TaskHeartbeatResponse;
import com.example.batch.orchestrator.controller.request.TaskLeaseRenewBatchRequest;
import com.example.batch.orchestrator.controller.request.TaskLeaseRenewBatchResponse;
import com.example.batch.orchestrator.controller.request.TaskLeaseRenewItemPayload;
import com.example.batch.orchestrator.controller.request.TaskLeaseRenewResultPayload;
import com.example.batch.orchestrator.controller.request.TaskReportBatchRequest;
import com.example.batch.orchestrator.controller.request.TaskReportBatchResponse;
import com.example.batch.orchestrator.controller.request.TaskReportResultPayload;
import com.example.batch.orchestrator.domain.command.TaskOutcomeCommand;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

/**
 * Worker 任务控制应用服务，处理 Worker 侧发起的任务认领（Claim）、结果上报（Report）和租约续期（Renew）请求。
 *
 * <p>认领时校验任务是否确实已被当前 Worker 认领（状态为 {@code RUNNING} 且 Worker 编码匹配），
 * 防止在并发场景下错误地响应成功。上报时对请求字段进行兼容处理——同时支持新版 {@code errorCode/errorMessage} 和旧版 {@code code/message}
 * 字段，缺失时降级为 {@code "UNKNOWN"}， 保证向后兼容。续期失败时抛出业务异常，要求 Worker 重新认领或放弃任务。
 *
 * <p>该类作为 HTTP Controller 与 {@link TaskExecutionService} 之间的薄适配层， 不包含任务状态机核心逻辑，复杂业务逻辑下沉至 {@code
 * TaskExecutionService}。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TaskControllerApplicationService {

  private final TaskExecutionService taskExecutionService;
  private final ObjectMapper objectMapper;
  private final BundleBatchClaimProperties batchClaimProperties;
  private final MeterRegistry meterRegistry;

  /**
   * 自引用(AOP self-invocation 豁免①):reportBatch 逐项调用 {@link #report} 需经代理才能触发其
   * {@code @Retryable}(死锁退避)与 {@code applyTaskOutcome} 的 {@code @Transactional}(逐项独立事务)。 直接
   * this.report() 会绕过代理。
   */
  @Lazy @Autowired private TaskControllerApplicationService self;

  @Timed(
      value = "batch.task.claim.duration",
      description = "Worker task claim latency on orchestrator",
      histogram = true)
  public EffectiveTaskConfig claim(Long taskId, TaskClaimRequest request) {
    JobTaskEntity task =
        Guard.requireFound(
            taskExecutionService.assignWorker(request.tenantId(), taskId, request.workerId()),
            "task not found");
    if (!isClaimedBy(task, request.workerId())) {
      throw BizException.of(ResultCode.CONFLICT, "error.task.already_claimed");
    }
    // P1-2.1:认领成功后返回 effective config 快照,worker 优先用本对象的字段。
    return taskExecutionService.loadEffectiveConfig(request.tenantId(), taskId);
  }

  /**
   * ADR-046 P2 切片 2.1:批量认领 —— 一次 HTTP 往返认领 K 个**独立** partition 对应的 task, 把控制面往返从 O(N) 降到 O(N/K)。
   *
   * <p>语义与单条 {@link #claim} 完全一致(逐 task 各自 {@code assignWorker} CAS),只是**逐项返回结果而非抛异常**: 没领到的
   * task(被并发对手领走 / READY 窗口已过 / 不存在)记 {@code claimed=false},worker 只处理领到的子集。 **不是束级状态机 / 不共享幂等 ——
   * 每个 partition 仍是独立单元**(见 ADR-046 §1)。
   *
   * <p>批大小受 {@link BundleBatchClaimProperties#effectiveBatchSize()} 守卫,超限直接拒绝(保护 orchestrator)。
   */
  public TaskClaimBatchResponse claimBatch(TaskClaimBatchRequest request) {
    List<TaskClaimItemPayload> items =
        request == null || request.items() == null ? List.of() : request.items();
    int cap = batchClaimProperties.effectiveBatchSize();
    if (items.size() > cap) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          "error.common.invalid_argument_detail",
          "claim batch size " + items.size() + " exceeds max " + cap);
    }
    List<TaskClaimResultPayload> results = new ArrayList<>(items.size());
    int claimed = 0;
    for (TaskClaimItemPayload item : items) {
      JobTaskEntity task =
          taskExecutionService.assignWorker(item.tenantId(), item.taskId(), item.workerId());
      if (task != null && isClaimedBy(task, item.workerId())) {
        EffectiveTaskConfig config =
            taskExecutionService.loadEffectiveConfig(item.tenantId(), item.taskId());
        results.add(new TaskClaimResultPayload(item.taskId(), true, config));
        claimed++;
      } else {
        results.add(new TaskClaimResultPayload(item.taskId(), false, null));
      }
    }
    // 2.4 观测:批大小分布(看 K 实际多大=churn 省多少)+ 逐项 outcome 计数
    if (!items.isEmpty()) {
      meterRegistry.summary(BatchMetricsNames.BATCH_CLAIM_SIZE).record(items.size());
      meterRegistry
          .counter(
              BatchMetricsNames.BATCH_CLAIM_ITEMS_TOTAL, BatchMetricsNames.TAG_OUTCOME, "claimed")
          .increment(claimed);
      meterRegistry
          .counter(
              BatchMetricsNames.BATCH_CLAIM_ITEMS_TOTAL, BatchMetricsNames.TAG_OUTCOME, "skipped")
          .increment(items.size() - claimed);
    }
    return new TaskClaimBatchResponse(results);
  }

  @Retryable(
      retryFor = {CannotAcquireLockException.class, TransientDataAccessException.class},
      maxAttempts = 5,
      // Citus 上同 instance 的并发 report 会撞分布式死锁(FOR UPDATE 锁多分区行,加锁顺序非确定)。
      // 原 3 次/50ms→100ms 窗口太窄,等长退避还会让两个 report 同步重投再撞。改 5 次 + random jitter
      // (delay~maxDelay 间随机)打散并发重试,吸收瞬时死锁,避免落到 SYSTEM 死信。
      backoff = @Backoff(delay = 50, maxDelay = 1000, multiplier = 2.0, random = true))
  public void report(Long taskId, TaskExecutionReportDto request) {
    String errorCode =
        resolveFailureField(request.getErrorCode(), request.getCode(), request.isSuccess());
    String errorMessage =
        resolveFailureField(request.getErrorMessage(), request.getMessage(), request.isSuccess());
    TaskOutcomeCommand command =
        TaskOutcomeCommand.builder()
            .tenantId(request.getTenantId())
            .taskId(taskId)
            .workerId(request.getWorkerId())
            .success(request.isSuccess())
            .resultSummary(request.getResultSummary())
            .errorCode(errorCode)
            .errorMessage(errorMessage)
            .errorKey(request.getErrorKey())
            .errorArgs(request.getErrorArgs())
            .highWaterMarkOut(request.getHighWaterMarkOut())
            .outputs(request.getOutputs())
            .partitionInvocationId(request.getPartitionInvocationId())
            .failureClass(request.isSuccess() ? null : request.getFailureClass())
            .verifierFailures(request.isSuccess() ? request.getVerifierFailures() : null)
            .build();
    taskExecutionService.applyTaskOutcome(command);
  }

  /**
   * ADR-046 P2 切片 2.2:批量上报 —— 一次 HTTP 往返上报 K 个**独立** partition 的结果, 把控制面往返从 O(N) 降到 O(N/K)。
   *
   * <p>**逐项独立事务 + 逐项结果**:每项经 {@code self.report}(各自 {@code @Transactional} + {@code @Retryable}
   * 死锁退避)推进,**某项失败(版本 CAS 冲突 / 校验 / 其它)只标记该项,不影响其余项也不回滚整批** —— 这正是 ADR-046 要求的「批内部分失败 =
   * 失败项独立、不退整束」。worker 据逐项结果只重报 ok=false 的项。
   *
   * <p>设计取舍:不在单事务里做 savepoint 批量推进 —— {@code applyTaskOutcome} 自身 {@code @Transactional} + 父汇总
   * self-invocation + 死锁 {@code @Retryable},逐项独立事务隔离最强、复用已验证路径,风险最低; 真正的 churn 收益在「一次 HTTP 报 K
   * 个」。outbox 单 INSERT 批写是更高风险的次要微优,后置(见计划 §2.2)。
   *
   * <p>批大小受 {@link BundleBatchClaimProperties#effectiveBatchSize()} 守卫,超限直接拒绝。
   */
  public TaskReportBatchResponse reportBatch(TaskReportBatchRequest request) {
    List<TaskExecutionReportDto> items =
        request == null || request.items() == null ? List.of() : request.items();
    int cap = batchClaimProperties.effectiveBatchSize();
    if (items.size() > cap) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          "error.common.invalid_argument_detail",
          "report batch size " + items.size() + " exceeds max " + cap);
    }
    List<TaskReportResultPayload> results = new ArrayList<>(items.size());
    int ok = 0;
    for (TaskExecutionReportDto item : items) {
      Long taskId = item.getTaskId();
      try {
        self.report(taskId, item);
        results.add(new TaskReportResultPayload(taskId, true, null));
        ok++;
      } catch (RuntimeException e) {
        // 逐项独立:该项失败不影响其余项;worker 只重报 ok=false 的项
        log.warn("batch report item failed: taskId={} err={}", taskId, e.toString());
        results.add(new TaskReportResultPayload(taskId, false, e.getMessage()));
      }
    }
    // 2.4 观测:批大小分布 + 逐项 outcome(批内部分失败可观测)
    if (!items.isEmpty()) {
      meterRegistry.summary(BatchMetricsNames.BATCH_REPORT_SIZE).record(items.size());
      meterRegistry
          .counter(BatchMetricsNames.BATCH_REPORT_ITEMS_TOTAL, BatchMetricsNames.TAG_OUTCOME, "ok")
          .increment(ok);
      meterRegistry
          .counter(
              BatchMetricsNames.BATCH_REPORT_ITEMS_TOTAL, BatchMetricsNames.TAG_OUTCOME, "failed")
          .increment(items.size() - ok);
    }
    return new TaskReportBatchResponse(results);
  }

  /**
   * ORCH-P4-1：心跳 = 续租 + 进度上报 + 取消感知。续租失败 → 409;成功则(可选)落 details 并回带 {@code cancelRequested}, SDK
   * 据此主动停长循环(不等 lease 超时)。
   */
  public TaskHeartbeatResponse renew(Long taskId, TaskHeartbeatRequest request) {
    String detailsJson = serializeDetails(taskId, request.details());
    TaskHeartbeatResult result =
        taskExecutionService.recordHeartbeat(
            request.tenantId(),
            taskId,
            request.workerId(),
            request.partitionInvocationId(),
            detailsJson);
    if (!result.leaseRenewed()) {
      throw BizException.of(ResultCode.CONFLICT, "error.task.lease_renew_rejected");
    }
    return new TaskHeartbeatResponse(result.cancelRequested());
  }

  /** ORCH-P4-1：运维 / 平台请求取消 RUNNING task;非 RUNNING / 不存在则静默成功(幂等,无需让运维区分)。 */
  public void cancel(Long taskId, TaskCancelRequest request) {
    boolean marked = taskExecutionService.requestCancel(request.tenantId(), taskId);
    log.info(
        "task cancel requested: tenant={} taskId={} reason={} marked={}",
        request.tenantId(),
        taskId,
        request.reason(),
        marked);
  }

  /** details 非空时序列化为 JSON 文本(存 job_task.heartbeat_details);序列化失败降级为跳过 details,不阻塞续租。 */
  private String serializeDetails(Long taskId, Object details) {
    if (details == null) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(details);
    } catch (JsonProcessingException e) {
      log.warn(
          "heartbeat details serialize failed, skipped: taskId={} err={}", taskId, e.toString());
      return null;
    }
  }

  /**
   * ADR-016:批量续租 —— 一次 HTTP 往返处理多个 task;逐项返回结果而不抛异常。
   *
   * <p>ORCH-P4-1:批量续租同时返回 {@code cancelRequested};worker-core 据此中断长时间运行的 task, 而不必等待租约超时。
   */
  public TaskLeaseRenewBatchResponse renewBatch(TaskLeaseRenewBatchRequest request) {
    List<TaskLeaseRenewItemPayload> items =
        request == null || request.items() == null ? List.of() : request.items();
    List<TaskLeaseRenewResultPayload> results = new ArrayList<>(items.size());
    for (TaskLeaseRenewItemPayload item : items) {
      TaskAssignmentService.TaskHeartbeatResult result =
          taskExecutionService.recordHeartbeat(
              item.tenantId(), item.taskId(), item.workerId(), item.partitionInvocationId(), null);
      results.add(
          new TaskLeaseRenewResultPayload(
              item.taskId(), result.leaseRenewed(), result.cancelRequested()));
    }
    return new TaskLeaseRenewBatchResponse(results);
  }

  private boolean isClaimedBy(JobTaskEntity task, String workerId) {
    return task != null
        && TaskStatus.RUNNING.code().equals(task.getTaskStatus())
        && workerId != null
        && workerId.equals(task.getAssignedWorkerCode());
  }

  private String resolveFailureField(String primary, String fallback, boolean success) {
    if (success) {
      return null;
    }
    if (Texts.hasText(primary)) {
      return primary;
    }
    if (Texts.hasText(fallback)) {
      return fallback;
    }
    return "UNKNOWN";
  }
}
