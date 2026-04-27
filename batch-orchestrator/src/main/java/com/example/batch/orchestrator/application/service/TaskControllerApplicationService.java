package com.example.batch.orchestrator.application.service;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.enums.TaskStatus;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.Guard;
import com.example.batch.common.utils.Texts;
import com.example.batch.orchestrator.controller.TaskController.TaskClaimRequest;
import com.example.batch.orchestrator.controller.request.TaskExecutionReportDto;
import com.example.batch.orchestrator.domain.command.TaskOutcomeCommand;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import lombok.RequiredArgsConstructor;
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
public class TaskControllerApplicationService {

  private final TaskExecutionService taskExecutionService;

  public void claim(Long taskId, TaskClaimRequest request) {
    JobTaskEntity task =
        Guard.requireFound(
            taskExecutionService.assignWorker(request.tenantId(), taskId, request.workerId()),
            "task not found");
    if (!isClaimedBy(task, request.workerId())) {
      throw new BizException(ResultCode.CONFLICT, "task already claimed");
    }
  }

  public void report(Long taskId, TaskExecutionReportDto request) {
    String errorCode =
        resolveFailureField(request.getErrorCode(), request.getCode(), request.isSuccess());
    String errorMessage =
        resolveFailureField(request.getErrorMessage(), request.getMessage(), request.isSuccess());
    taskExecutionService.applyTaskOutcome(
        new TaskOutcomeCommand(
            request.getTenantId(),
            taskId,
            request.getWorkerId(),
            request.isSuccess(),
            request.getResultSummary(),
            errorCode,
            errorMessage,
            request.getHighWaterMarkOut()));
  }

  public void renew(Long taskId, TaskClaimRequest request) {
    boolean renewed =
        taskExecutionService.renewTaskLease(request.tenantId(), taskId, request.workerId());
    if (!renewed) {
      throw new BizException(ResultCode.CONFLICT, "task lease renew rejected");
    }
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
