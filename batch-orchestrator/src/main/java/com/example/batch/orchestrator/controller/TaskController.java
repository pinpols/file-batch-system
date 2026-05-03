package com.example.batch.orchestrator.controller;

import com.example.batch.common.dto.EffectiveTaskConfig;
import com.example.batch.orchestrator.application.service.task.TaskControllerApplicationService;
import com.example.batch.orchestrator.controller.request.TaskExecutionReportDto;
import com.example.batch.orchestrator.controller.request.TaskLeaseRenewBatchRequest;
import com.example.batch.orchestrator.controller.request.TaskLeaseRenewBatchResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 任务执行生命周期内部控制器，基础路径 {@code /internal/tasks}。 提供 Worker 与 Orchestrator 之间的任务交互端点： {@code POST
 * /{taskId}/claim} 认领任务、{@code POST /{taskId}/report} 上报执行结果、 {@code POST /{taskId}/renew}
 * 续期心跳租约、{@code POST /leases/renew-batch} 批量续租（ADR-016）。仅限 Worker 节点通过内部网络调用。
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
      @PathVariable Long taskId, @RequestBody TaskClaimRequest request) {
    return taskControllerApplicationService.claim(taskId, request);
  }

  @PostMapping("/{taskId}/report")
  public void report(@PathVariable Long taskId, @RequestBody TaskExecutionReportDto request) {
    taskControllerApplicationService.report(taskId, request);
  }

  @PostMapping("/{taskId}/renew")
  public void renew(@PathVariable Long taskId, @RequestBody TaskClaimRequest request) {
    taskControllerApplicationService.renew(taskId, request);
  }

  @PostMapping("/leases/renew-batch")
  public TaskLeaseRenewBatchResponse renewBatch(@RequestBody TaskLeaseRenewBatchRequest request) {
    return taskControllerApplicationService.renewBatch(request);
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record TaskClaimRequest(String tenantId, String workerId, String partitionInvocationId) {}
}
