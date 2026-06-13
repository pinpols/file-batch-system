package com.example.batch.orchestrator.application.service.task;

import com.example.batch.common.dto.EffectiveTaskConfig;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.enums.TaskStatus;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.Guard;
import com.example.batch.common.utils.Texts;
import com.example.batch.orchestrator.application.service.task.TaskAssignmentService.TaskHeartbeatResult;
import com.example.batch.orchestrator.controller.TaskController.TaskClaimRequest;
import com.example.batch.orchestrator.controller.request.TaskCancelRequest;
import com.example.batch.orchestrator.controller.request.TaskExecutionReportDto;
import com.example.batch.orchestrator.controller.request.TaskHeartbeatRequest;
import com.example.batch.orchestrator.controller.request.TaskHeartbeatResponse;
import com.example.batch.orchestrator.controller.request.TaskLeaseRenewBatchRequest;
import com.example.batch.orchestrator.controller.request.TaskLeaseRenewBatchResponse;
import com.example.batch.orchestrator.controller.request.TaskLeaseRenewItemPayload;
import com.example.batch.orchestrator.controller.request.TaskLeaseRenewResultPayload;
import com.example.batch.orchestrator.domain.command.TaskOutcomeCommand;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.annotation.Timed;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
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

  @Retryable(
      retryFor = {
        DeadlockLoserDataAccessException.class,
        CannotAcquireLockException.class,
        TransientDataAccessException.class
      },
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
   * ADR-016: batch renew — one HTTP roundtrip for many tasks; per-item outcome without throwing.
   *
   * <p>ORCH-P4-1: batch renew also returns {@code cancelRequested}; worker-core uses it to
   * interrupt long-running tasks instead of waiting for lease timeout.
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
