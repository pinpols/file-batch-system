package com.example.batch.console.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.common.kafka.BatchTopics;
import com.example.batch.console.service.ConsoleResponseFactory;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 事件总线目录：暴露系统可订阅事件类型与 Kafka Topic 映射。 */
@RestController
@RequestMapping("/api/console/event-catalog")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class ConsoleEventCatalogController {

  private final ConsoleResponseFactory responseFactory;

  @GetMapping("/event-types")
  public CommonResponse<List<Map<String, String>>> eventTypes() {
    List<Map<String, String>> types =
        List.of(
            eventType("JOB_SUCCESS", "作业成功完成"),
            eventType("JOB_FAILED", "作业执行失败"),
            eventType("JOB_TIMEOUT", "作业执行超时"),
            eventType("JOB_SLA_BREACH", "作业 SLA 违约"),
            eventType("WORKFLOW_SUCCESS", "工作流成功完成"),
            eventType("WORKFLOW_FAILED", "工作流执行失败"),
            eventType("FILE_ARRIVED", "文件到达"),
            eventType("FILE_PROCESSED", "文件处理完成"),
            eventType("FILE_ERROR", "文件处理异常"),
            eventType("WORKER_OFFLINE", "Worker 离线"),
            eventType("WORKER_DRAIN", "Worker 进入排空状态"),
            eventType("ALERT_TRIGGERED", "告警触发"),
            eventType("APPROVAL_PENDING", "审批工单待处理"),
            eventType("APPROVAL_COMPLETED", "审批工单已完成"));
    return responseFactory.success(types);
  }

  @GetMapping("/topics")
  public CommonResponse<List<Map<String, String>>> topics() {
    List<Map<String, String>> topicList =
        List.of(
            topic(BatchTopics.TASK_DISPATCH_IMPORT, "导入任务分发"),
            topic(BatchTopics.TASK_DISPATCH_EXPORT, "导出任务分发"),
            topic(BatchTopics.TASK_DISPATCH_DISPATCH, "调度任务分发"),
            topic(BatchTopics.TASK_RESULT, "任务执行结果"),
            topic(BatchTopics.TASK_RETRY, "任务重试"),
            topic(BatchTopics.TASK_DEAD_LETTER, "死信队列"),
            topic(BatchTopics.OUTBOX_EVENT, "Outbox 事件"),
            topic(BatchTopics.WORKER_HEARTBEAT, "Worker 心跳"));
    return responseFactory.success(topicList);
  }

  private static Map<String, String> eventType(String code, String description) {
    return Map.of("code", code, "description", description);
  }

  private static Map<String, String> topic(String name, String description) {
    return Map.of("name", name, "description", description);
  }
}
