package com.example.batch.orchestrator.controller;

import com.example.batch.common.dto.EffectiveTaskConfig;
import com.example.batch.orchestrator.application.service.task.TaskControllerApplicationService;
import com.example.batch.orchestrator.controller.request.TaskCancelRequest;
import com.example.batch.orchestrator.controller.request.TaskExecutionReportDto;
import com.example.batch.orchestrator.controller.request.TaskHeartbeatRequest;
import com.example.batch.orchestrator.controller.request.TaskHeartbeatResponse;
import com.example.batch.orchestrator.controller.request.TaskLeaseRenewBatchRequest;
import com.example.batch.orchestrator.controller.request.TaskLeaseRenewBatchResponse;
import com.example.batch.orchestrator.controller.request.TaskLeaseRenewItemPayload;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

  @PostMapping("/{taskId}/claim")
  public EffectiveTaskConfig claim(
      @PathVariable Long taskId,
      @RequestBody TaskClaimRequest request,
      HttpServletRequest httpRequest) {
    return taskControllerApplicationService.claim(taskId, normalize(request, httpRequest));
  }

  @PostMapping("/{taskId}/report")
  public void report(
      @PathVariable Long taskId,
      @Valid @RequestBody TaskExecutionReportDto request,
      HttpServletRequest httpRequest) {
    request.setTenantId(
        InternalRequestTenantGuard.resolveTenant(httpRequest, request.getTenantId()));
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
