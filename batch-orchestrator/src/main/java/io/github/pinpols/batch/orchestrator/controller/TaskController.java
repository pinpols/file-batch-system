package io.github.pinpols.batch.orchestrator.controller;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.pinpols.batch.common.dto.EffectiveTaskConfig;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.orchestrator.application.ratelimit.RateLimitAction;
import io.github.pinpols.batch.orchestrator.application.ratelimit.TenantActionRateLimiter;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskControlPayloads.TaskCancelCommand;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskControlPayloads.TaskClaimBatchCommand;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskControlPayloads.TaskClaimBatchResult;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskControlPayloads.TaskClaimCommand;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskControlPayloads.TaskClaimItemCommand;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskControlPayloads.TaskExecutionReportCommand;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskControlPayloads.TaskHeartbeatCommand;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskControlPayloads.TaskHeartbeatResult;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskControlPayloads.TaskLeaseRenewBatchCommand;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskControlPayloads.TaskLeaseRenewBatchResult;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskControlPayloads.TaskLeaseRenewItemCommand;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskControlPayloads.TaskNonIdempotentStartCommand;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskControlPayloads.TaskReportBatchCommand;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskControlPayloads.TaskReportBatchResult;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskControllerApplicationService;
import io.github.pinpols.batch.orchestrator.controller.request.TaskCancelRequest;
import io.github.pinpols.batch.orchestrator.controller.request.TaskClaimBatchRequest;
import io.github.pinpols.batch.orchestrator.controller.request.TaskClaimBatchResponse;
import io.github.pinpols.batch.orchestrator.controller.request.TaskClaimItemPayload;
import io.github.pinpols.batch.orchestrator.controller.request.TaskClaimResultPayload;
import io.github.pinpols.batch.orchestrator.controller.request.TaskExecutionReportDto;
import io.github.pinpols.batch.orchestrator.controller.request.TaskHeartbeatRequest;
import io.github.pinpols.batch.orchestrator.controller.request.TaskHeartbeatResponse;
import io.github.pinpols.batch.orchestrator.controller.request.TaskLeaseRenewBatchRequest;
import io.github.pinpols.batch.orchestrator.controller.request.TaskLeaseRenewBatchResponse;
import io.github.pinpols.batch.orchestrator.controller.request.TaskLeaseRenewItemPayload;
import io.github.pinpols.batch.orchestrator.controller.request.TaskLeaseRenewResultPayload;
import io.github.pinpols.batch.orchestrator.controller.request.TaskNonIdempotentStartRequest;
import io.github.pinpols.batch.orchestrator.controller.request.TaskReportBatchRequest;
import io.github.pinpols.batch.orchestrator.controller.request.TaskReportBatchResponse;
import io.github.pinpols.batch.orchestrator.controller.request.TaskReportResultPayload;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 任务执行生命周期内部控制器，基础路径 {@code /internal/tasks}。 提供 Worker 与 Orchestrator 之间的任务交互端点： {@code POST
 * /{taskId}/claim} 认领任务、{@code POST /{taskId}/report} 上报执行结果、 {@code POST /{taskId}/renew} 心跳(续租 +
 * 进度上报 + 取消感知,ORCH-P4-1)、{@code POST /{taskId}/cancel} 请求取消(ORCH-P4-1)、 {@code POST
 * /leases/renew-batch} 批量续租（ADR-016）。仅限 Worker 节点 / 运维内部网络调用。
 *
 * <p>{@code claim} 端点 P1-2.1 起返回 {@link EffectiveTaskConfig} body(认领成功时);旧版 worker 不解析 body
 * 仍可正常工作(HTTP 200 即认为成功),协议向前兼容。
 */
@RestController
@RequestMapping("/internal/tasks")
@RequiredArgsConstructor
public class TaskController {

  private final TaskControllerApplicationService taskControllerApplicationService;
  private final TenantActionRateLimiter tenantActionRateLimiter;

  @PostMapping("/{taskId}/claim")
  public EffectiveTaskConfig claim(
      @PathVariable Long taskId,
      @RequestBody TaskClaimRequest request,
      HttpServletRequest httpRequest) {
    TaskClaimRequest normalized = normalize(request, httpRequest);
    rateLimit(
        EmptyChecks.isNull(normalized) ? null : normalized.tenantId(), RateLimitAction.TASK_CLAIM);
    TaskClaimCommand command = new TaskClaimCommand(
        normalized.tenantId(), normalized.workerId(), normalized.partitionInvocationId());
    return taskControllerApplicationService.claim(taskId, command);
  }

  @PostMapping("/{taskId}/non-idempotent-started")
  public void markNonIdempotentExecutionStarted(
      @PathVariable Long taskId,
      @RequestBody TaskNonIdempotentStartRequest request,
      HttpServletRequest httpRequest) {
    String requestedTenantId = EmptyChecks.isNull(request) ? null : request.tenantId();
    String tenantId = InternalRequestTenantGuard.resolveTenant(httpRequest, requestedTenantId);
    rateLimit(tenantId, RateLimitAction.TASK_CLAIM);
    String workerId = EmptyChecks.isNull(request) ? null : request.workerId();
    String partitionInvocationId =
        EmptyChecks.isNull(request) ? null : request.partitionInvocationId();
    TaskNonIdempotentStartCommand command =
        new TaskNonIdempotentStartCommand(tenantId, workerId, partitionInvocationId);
    taskControllerApplicationService.markNonIdempotentExecutionStarted(taskId, command);
  }

  @PostMapping("/{taskId}/report")
  public void report(
      @PathVariable Long taskId,
      @Valid @RequestBody TaskExecutionReportDto request,
      HttpServletRequest httpRequest) {
    String requestedTenantId = request.getTenantId();
    String tenantId = InternalRequestTenantGuard.resolveTenant(httpRequest, requestedTenantId);
    request.setTenantId(tenantId);
    rateLimit(request.getTenantId(), RateLimitAction.TASK_REPORT);
    TaskExecutionReportCommand command = toCommand(request);
    taskControllerApplicationService.report(taskId, command);
  }

  @PostMapping("/{taskId}/renew")
  public TaskHeartbeatResponse renew(
      @PathVariable Long taskId,
      @RequestBody TaskHeartbeatRequest request,
      HttpServletRequest httpRequest) {
    TaskHeartbeatRequest normalized = normalize(request, httpRequest);
    TaskHeartbeatCommand command = toCommand(normalized);
    TaskHeartbeatResult result = taskControllerApplicationService.renew(taskId, command);
    return new TaskHeartbeatResponse(result.cancelRequested());
  }

  /**
   * ORCH-P4-1：运维 / 平台请求取消 RUNNING task。置 cancel_requested=true,SDK 下次 renew 收到 {@code
   * cancelRequested=true} 后主动停(不等 lease 超时)。幂等:重复 / 对非 RUNNING task 调用均返回 200。
   */
  @PostMapping("/{taskId}/cancel")
  public void cancel(
      @PathVariable Long taskId,
      @RequestBody TaskCancelRequest request,
      HttpServletRequest httpRequest) {
    String tenantId = InternalRequestTenantGuard.resolveTenant(
        httpRequest, EmptyChecks.isNull(request) ? null : request.tenantId());
    taskControllerApplicationService.cancel(
        taskId,
        new TaskCancelCommand(tenantId, EmptyChecks.isNull(request) ? null : request.reason()));
  }

  @PostMapping("/leases/renew-batch")
  public TaskLeaseRenewBatchResponse renewBatch(
      @RequestBody TaskLeaseRenewBatchRequest request, HttpServletRequest httpRequest) {
    TaskLeaseRenewBatchRequest normalized = normalize(request, httpRequest);
    TaskLeaseRenewBatchCommand command = toCommand(normalized);
    TaskLeaseRenewBatchResult result = taskControllerApplicationService.renewBatch(command);
    return toResponse(result);
  }

  /**
   * ADR-046 P2 切片 2.1:批量认领 —— 一次 HTTP 往返认领 K 个独立 partition 对应的 task(O(N)→O(N/K))。 逐项返回结果(claimed +
   * config),没领到的项不抛异常;worker 只处理 claimed=true 的子集。
   */
  @PostMapping("/claim-batch")
  public TaskClaimBatchResponse claimBatch(
      @RequestBody TaskClaimBatchRequest request, HttpServletRequest httpRequest) {
    // 批量端点按绑定 api_key 的租户限流(一次 HTTP 调用计 1 次);items 大小另由 body-size / 批量上限约束。
    String tenantId = InternalRequestTenantGuard.resolveTenant(httpRequest, null);
    rateLimit(tenantId, RateLimitAction.TASK_CLAIM);
    TaskClaimBatchRequest normalized = normalize(request, httpRequest);
    TaskClaimBatchCommand command = toCommand(normalized);
    TaskClaimBatchResult result = taskControllerApplicationService.claimBatch(command);
    return toResponse(result);
  }

  /**
   * ADR-046 P2 切片 2.2:批量上报 —— 一次 HTTP 往返上报 K 个独立 partition 的结果(O(N)→O(N/K))。 逐项独立事务推进,逐项返回 {@code
   * {taskId, ok, error}};某项失败只标记该项,不影响其余项。
   */
  @PostMapping("/report-batch")
  public TaskReportBatchResponse reportBatch(
      @RequestBody TaskReportBatchRequest request, HttpServletRequest httpRequest) {
    String tenantId = InternalRequestTenantGuard.resolveTenant(httpRequest, null);
    rateLimit(tenantId, RateLimitAction.TASK_REPORT);
    TaskReportBatchRequest normalized = normalize(request, httpRequest);
    TaskReportBatchCommand command = toCommand(normalized);
    TaskReportBatchResult result = taskControllerApplicationService.reportBatch(command);
    return toResponse(result);
  }

  /** 按绑定 api_key 的租户对热路径动作限流;超额即 429。tenantId 为空(无法归属)时由限流器放行。 */
  private void rateLimit(String tenantId, RateLimitAction action) {
    if (!tenantActionRateLimiter.tryConsume(tenantId, action)) {
      throw new ResponseStatusException(
          HttpStatus.TOO_MANY_REQUESTS, "rate limit exceeded: " + action);
    }
  }

  private static TaskClaimRequest normalize(
      TaskClaimRequest request, HttpServletRequest httpRequest) {
    String tenantId = InternalRequestTenantGuard.resolveTenant(
        httpRequest, EmptyChecks.isNull(request) ? null : request.tenantId());
    return new TaskClaimRequest(
        tenantId,
        EmptyChecks.isNull(request) ? null : request.workerId(),
        EmptyChecks.isNull(request) ? null : request.partitionInvocationId());
  }

  private static TaskClaimBatchRequest normalize(
      TaskClaimBatchRequest request, HttpServletRequest httpRequest) {
    if (EmptyChecks.isNull(request)) {
      return request;
    }
    List<TaskClaimItemPayload> requestItems = request.items();
    if (EmptyChecks.isNull(requestItems)) {
      return request;
    }
    List<TaskClaimItemPayload> items = new ArrayList<>(requestItems.size());
    for (TaskClaimItemPayload item : requestItems) {
      String requestedTenantId = EmptyChecks.isNull(item) ? null : item.tenantId();
      String tenantId = InternalRequestTenantGuard.resolveTenant(httpRequest, requestedTenantId);
      Long taskId = null;
      String workerId = null;
      String partitionInvocationId = null;
      if (EmptyChecks.isNotNull(item)) {
        taskId = item.taskId();
        workerId = item.workerId();
        partitionInvocationId = item.partitionInvocationId();
      }
      items.add(new TaskClaimItemPayload(tenantId, taskId, workerId, partitionInvocationId));
    }
    return new TaskClaimBatchRequest(items);
  }

  private static TaskReportBatchRequest normalize(
      TaskReportBatchRequest request, HttpServletRequest httpRequest) {
    if (EmptyChecks.isNull(request)) {
      return request;
    }
    List<TaskExecutionReportDto> requestItems = request.items();
    if (EmptyChecks.isNull(requestItems)) {
      return request;
    }
    for (TaskExecutionReportDto item : requestItems) {
      if (EmptyChecks.isNotNull(item)) {
        String requestedTenantId = item.getTenantId();
        String resolvedTenantId =
            InternalRequestTenantGuard.resolveTenant(httpRequest, requestedTenantId);
        item.setTenantId(resolvedTenantId);
      }
    }
    return request;
  }

  private static TaskHeartbeatRequest normalize(
      TaskHeartbeatRequest request, HttpServletRequest httpRequest) {
    String requestedTenantId = EmptyChecks.isNull(request) ? null : request.tenantId();
    String tenantId = InternalRequestTenantGuard.resolveTenant(httpRequest, requestedTenantId);
    return new TaskHeartbeatRequest(
        tenantId,
        EmptyChecks.isNull(request) ? null : request.workerId(),
        EmptyChecks.isNull(request) ? null : request.partitionInvocationId(),
        EmptyChecks.isNull(request) ? null : request.details());
  }

  private static TaskLeaseRenewBatchRequest normalize(
      TaskLeaseRenewBatchRequest request, HttpServletRequest httpRequest) {
    if (EmptyChecks.isNull(request)) {
      return request;
    }
    List<TaskLeaseRenewItemPayload> requestItems = request.items();
    if (EmptyChecks.isNull(requestItems)) {
      return request;
    }
    List<TaskLeaseRenewItemPayload> items = new ArrayList<>(requestItems.size());
    for (TaskLeaseRenewItemPayload item : requestItems) {
      String tenantId = InternalRequestTenantGuard.resolveTenant(
          httpRequest, EmptyChecks.isNull(item) ? null : item.tenantId());
      Long taskId = null;
      String workerId = null;
      String partitionInvocationId = null;
      if (EmptyChecks.isNotNull(item)) {
        taskId = item.taskId();
        workerId = item.workerId();
        partitionInvocationId = item.partitionInvocationId();
      }
      items.add(new TaskLeaseRenewItemPayload(tenantId, taskId, workerId, partitionInvocationId));
    }
    return new TaskLeaseRenewBatchRequest(items);
  }

  private static TaskClaimBatchCommand toCommand(TaskClaimBatchRequest request) {
    if (EmptyChecks.isNull(request)) {
      return new TaskClaimBatchCommand(null);
    }
    List<TaskClaimItemPayload> requestItems = request.items();
    if (EmptyChecks.isNull(requestItems)) {
      return new TaskClaimBatchCommand(null);
    }
    List<TaskClaimItemCommand> commands = requestItems.stream()
        .map(item -> {
          TaskClaimItemCommand command = new TaskClaimItemCommand(
              item.tenantId(), item.taskId(), item.workerId(), item.partitionInvocationId());
          return command;
        })
        .toList();
    return new TaskClaimBatchCommand(commands);
  }

  private static TaskClaimBatchResponse toResponse(TaskClaimBatchResult result) {
    List<TaskClaimResultPayload> results = result.results().stream()
        .map(item -> new TaskClaimResultPayload(item.taskId(), item.claimed(), item.config()))
        .toList();
    return new TaskClaimBatchResponse(results);
  }

  private static TaskExecutionReportCommand toCommand(TaskExecutionReportDto request) {
    if (EmptyChecks.isNull(request)) {
      return null;
    }
    return TaskExecutionReportCommand.builder()
        .taskId(request.getTaskId())
        .tenantId(request.getTenantId())
        .workerId(request.getWorkerId())
        .success(request.isSuccess())
        .code(request.getCode())
        .message(request.getMessage())
        .resultSummary(request.getResultSummary())
        .errorCode(request.getErrorCode())
        .errorMessage(request.getErrorMessage())
        .errorKey(request.getErrorKey())
        .errorArgs(request.getErrorArgs())
        .highWaterMarkOut(request.getHighWaterMarkOut())
        .outputs(request.getOutputs())
        .partitionInvocationId(request.getPartitionInvocationId())
        .failureClass(request.getFailureClass())
        .verifierFailures(request.getVerifierFailures())
        .build();
  }

  private static TaskReportBatchCommand toCommand(TaskReportBatchRequest request) {
    if (EmptyChecks.isNull(request)) {
      return new TaskReportBatchCommand(null);
    }
    List<TaskExecutionReportDto> requestItems = request.items();
    if (EmptyChecks.isNull(requestItems)) {
      return new TaskReportBatchCommand(null);
    }
    List<TaskExecutionReportCommand> commands =
        requestItems.stream().map(TaskController::toCommand).toList();
    return new TaskReportBatchCommand(commands);
  }

  private static TaskReportBatchResponse toResponse(TaskReportBatchResult result) {
    List<TaskReportResultPayload> results = result.results().stream()
        .map(item -> new TaskReportResultPayload(item.taskId(), item.ok(), item.error()))
        .toList();
    return new TaskReportBatchResponse(results);
  }

  private static TaskHeartbeatCommand toCommand(TaskHeartbeatRequest request) {
    return new TaskHeartbeatCommand(
        request.tenantId(), request.workerId(), request.partitionInvocationId(), request.details());
  }

  private static TaskLeaseRenewBatchCommand toCommand(TaskLeaseRenewBatchRequest request) {
    if (EmptyChecks.isNull(request)) {
      return new TaskLeaseRenewBatchCommand(null);
    }
    List<TaskLeaseRenewItemPayload> requestItems = request.items();
    if (EmptyChecks.isNull(requestItems)) {
      return new TaskLeaseRenewBatchCommand(null);
    }
    List<TaskLeaseRenewItemCommand> commands = requestItems.stream()
        .map(item -> {
          TaskLeaseRenewItemCommand command = new TaskLeaseRenewItemCommand(
              item.tenantId(), item.taskId(), item.workerId(), item.partitionInvocationId());
          return command;
        })
        .toList();
    return new TaskLeaseRenewBatchCommand(commands);
  }

  private static TaskLeaseRenewBatchResponse toResponse(TaskLeaseRenewBatchResult result) {
    List<TaskLeaseRenewResultPayload> results = result.results().stream()
        .map(item ->
            new TaskLeaseRenewResultPayload(item.taskId(), item.renewed(), item.cancelRequested()))
        .toList();
    return new TaskLeaseRenewBatchResponse(results);
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record TaskClaimRequest(String tenantId, String workerId, String partitionInvocationId) {}
}
