package io.github.pinpols.batch.orchestrator.controller;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.pinpols.batch.common.dto.EffectiveTaskConfig;
import io.github.pinpols.batch.orchestrator.application.ratelimit.RateLimitAction;
import io.github.pinpols.batch.orchestrator.application.ratelimit.TenantActionRateLimiter;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskControllerApplicationService;
import io.github.pinpols.batch.orchestrator.controller.request.TaskCancelRequest;
import io.github.pinpols.batch.orchestrator.controller.request.TaskClaimBatchRequest;
import io.github.pinpols.batch.orchestrator.controller.request.TaskClaimBatchResponse;
import io.github.pinpols.batch.orchestrator.controller.request.TaskClaimItemPayload;
import io.github.pinpols.batch.orchestrator.controller.request.TaskExecutionReportDto;
import io.github.pinpols.batch.orchestrator.controller.request.TaskHeartbeatRequest;
import io.github.pinpols.batch.orchestrator.controller.request.TaskHeartbeatResponse;
import io.github.pinpols.batch.orchestrator.controller.request.TaskLeaseRenewBatchRequest;
import io.github.pinpols.batch.orchestrator.controller.request.TaskLeaseRenewBatchResponse;
import io.github.pinpols.batch.orchestrator.controller.request.TaskLeaseRenewItemPayload;
import io.github.pinpols.batch.orchestrator.controller.request.TaskReportBatchRequest;
import io.github.pinpols.batch.orchestrator.controller.request.TaskReportBatchResponse;
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
    rateLimit(normalized == null ? null : normalized.tenantId(), RateLimitAction.TASK_CLAIM);
    return taskControllerApplicationService.claim(taskId, normalized);
  }

  @PostMapping("/{taskId}/report")
  public void report(
      @PathVariable Long taskId,
      @Valid @RequestBody TaskExecutionReportDto request,
      HttpServletRequest httpRequest) {
    request.setTenantId(
        InternalRequestTenantGuard.resolveTenant(httpRequest, request.getTenantId()));
    rateLimit(request.getTenantId(), RateLimitAction.TASK_REPORT);
    taskControllerApplicationService.report(taskId, request);
  }

  @PostMapping("/{taskId}/renew")
  public TaskHeartbeatResponse renew(
      @PathVariable Long taskId,
      @RequestBody TaskHeartbeatRequest request,
      HttpServletRequest httpRequest) {
    return taskControllerApplicationService.renew(taskId, normalize(request, httpRequest));
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
    String tenantId =
        InternalRequestTenantGuard.resolveTenant(
            httpRequest, request == null ? null : request.tenantId());
    taskControllerApplicationService.cancel(
        taskId, new TaskCancelRequest(tenantId, request == null ? null : request.reason()));
  }

  @PostMapping("/leases/renew-batch")
  public TaskLeaseRenewBatchResponse renewBatch(
      @RequestBody TaskLeaseRenewBatchRequest request, HttpServletRequest httpRequest) {
    return taskControllerApplicationService.renewBatch(normalize(request, httpRequest));
  }

  /**
   * ADR-046 P2 切片 2.1:批量认领 —— 一次 HTTP 往返认领 K 个独立 partition 对应的 task(O(N)→O(N/K))。 逐项返回结果(claimed +
   * config),没领到的项不抛异常;worker 只处理 claimed=true 的子集。
   */
  @PostMapping("/claim-batch")
  public TaskClaimBatchResponse claimBatch(
      @RequestBody TaskClaimBatchRequest request, HttpServletRequest httpRequest) {
    // 批量端点按绑定 api_key 的租户限流(一次 HTTP 调用计 1 次);items 大小另由 body-size / 批量上限约束。
    rateLimit(
        InternalRequestTenantGuard.resolveTenant(httpRequest, null), RateLimitAction.TASK_CLAIM);
    return taskControllerApplicationService.claimBatch(normalize(request, httpRequest));
  }

  /**
   * ADR-046 P2 切片 2.2:批量上报 —— 一次 HTTP 往返上报 K 个独立 partition 的结果(O(N)→O(N/K))。 逐项独立事务推进,逐项返回 {@code
   * {taskId, ok, error}};某项失败只标记该项,不影响其余项。
   */
  @PostMapping("/report-batch")
  public TaskReportBatchResponse reportBatch(
      @RequestBody TaskReportBatchRequest request, HttpServletRequest httpRequest) {
    rateLimit(
        InternalRequestTenantGuard.resolveTenant(httpRequest, null), RateLimitAction.TASK_REPORT);
    return taskControllerApplicationService.reportBatch(normalize(request, httpRequest));
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
    String tenantId =
        InternalRequestTenantGuard.resolveTenant(
            httpRequest, request == null ? null : request.tenantId());
    return new TaskClaimRequest(
        tenantId,
        request == null ? null : request.workerId(),
        request == null ? null : request.partitionInvocationId());
  }

  private static TaskClaimBatchRequest normalize(
      TaskClaimBatchRequest request, HttpServletRequest httpRequest) {
    if (request == null || request.items() == null) {
      return request;
    }
    List<TaskClaimItemPayload> items = new ArrayList<>(request.items().size());
    for (TaskClaimItemPayload item : request.items()) {
      String tenantId =
          InternalRequestTenantGuard.resolveTenant(
              httpRequest, item == null ? null : item.tenantId());
      items.add(
          new TaskClaimItemPayload(
              tenantId,
              item == null ? null : item.taskId(),
              item == null ? null : item.workerId(),
              item == null ? null : item.partitionInvocationId()));
    }
    return new TaskClaimBatchRequest(items);
  }

  private static TaskReportBatchRequest normalize(
      TaskReportBatchRequest request, HttpServletRequest httpRequest) {
    if (request == null || request.items() == null) {
      return request;
    }
    for (TaskExecutionReportDto item : request.items()) {
      if (item != null) {
        item.setTenantId(InternalRequestTenantGuard.resolveTenant(httpRequest, item.getTenantId()));
      }
    }
    return request;
  }

  private static TaskHeartbeatRequest normalize(
      TaskHeartbeatRequest request, HttpServletRequest httpRequest) {
    String tenantId =
        InternalRequestTenantGuard.resolveTenant(
            httpRequest, request == null ? null : request.tenantId());
    return new TaskHeartbeatRequest(
        tenantId,
        request == null ? null : request.workerId(),
        request == null ? null : request.partitionInvocationId(),
        request == null ? null : request.details());
  }

  private static TaskLeaseRenewBatchRequest normalize(
      TaskLeaseRenewBatchRequest request, HttpServletRequest httpRequest) {
    if (request == null || request.items() == null) {
      return request;
    }
    List<TaskLeaseRenewItemPayload> items = new ArrayList<>(request.items().size());
    for (TaskLeaseRenewItemPayload item : request.items()) {
      String tenantId =
          InternalRequestTenantGuard.resolveTenant(
              httpRequest, item == null ? null : item.tenantId());
      items.add(
          new TaskLeaseRenewItemPayload(
              tenantId,
              item == null ? null : item.taskId(),
              item == null ? null : item.workerId(),
              item == null ? null : item.partitionInvocationId()));
    }
    return new TaskLeaseRenewBatchRequest(items);
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record TaskClaimRequest(String tenantId, String workerId, String partitionInvocationId) {}
}
