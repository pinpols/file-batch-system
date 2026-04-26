package com.example.batch.orchestrator.controller;

import com.example.batch.orchestrator.application.service.TaskControllerApplicationService;
import com.example.batch.orchestrator.controller.request.TaskExecutionReportDto;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 任务执行生命周期内部控制器，基础路径 {@code /internal/tasks}。 提供 Worker 与 Orchestrator 之间的任务交互端点： {@code POST
 * /{taskId}/claim} 认领任务、{@code POST /{taskId}/report} 上报执行结果、 {@code POST /{taskId}/renew}
 * 续期心跳租约。仅限 Worker 节点通过内部网络调用。
 */
@RestController
@RequestMapping("/internal/tasks")
@RequiredArgsConstructor
public class TaskController {

  private final TaskControllerApplicationService taskControllerApplicationService;

  @PostMapping("/{taskId}/claim")
  public void claim(@PathVariable Long taskId, @RequestBody TaskClaimRequest request) {
    taskControllerApplicationService.claim(taskId, request);
  }

  @PostMapping("/{taskId}/report")
  public void report(@PathVariable Long taskId, @RequestBody TaskExecutionReportDto request) {
    taskControllerApplicationService.report(taskId, request);
  }

  @PostMapping("/{taskId}/renew")
  public void renew(@PathVariable Long taskId, @RequestBody TaskClaimRequest request) {
    taskControllerApplicationService.renew(taskId, request);
  }

  public record TaskClaimRequest(String tenantId, String workerId) {}
}
