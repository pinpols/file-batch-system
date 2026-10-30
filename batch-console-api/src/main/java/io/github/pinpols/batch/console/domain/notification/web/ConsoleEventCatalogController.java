package io.github.pinpols.batch.console.domain.notification.web;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.common.kafka.BatchKafkaTopicNamespace;
import io.github.pinpols.batch.common.kafka.BatchTopics;
import io.github.pinpols.batch.console.domain.notification.application.contract.response.ConsoleEventTopicResponse;
import io.github.pinpols.batch.console.domain.notification.application.contract.response.ConsoleEventTypeResponse;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 事件总线目录:暴露系统可订阅事件类型与 Kafka Topic 映射。 */
@RestController
@RequestMapping("/api/console/event-catalog")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class ConsoleEventCatalogController {

  private final ConsoleResponseFactory responseFactory;
  private final MessageSource messageSource;
  private final BatchKafkaTopicNamespace topicNamespace;

  @GetMapping("/event-types")
  public CommonResponse<List<ConsoleEventTypeResponse>> eventTypes() {
    Locale locale = LocaleContextHolder.getLocale();
    // 仅列出已有同名生产者的业务事件；SSE 刷新提示和审批 Push 不能冒充订阅事件。
    List<ConsoleEventTypeResponse> types = List.of(
        eventType("JOB_SUCCESS", locale),
        eventType("JOB_FAILED", locale),
        eventType("WORKFLOW_SUCCESS", locale),
        eventType("WORKFLOW_FAILED", locale),
        eventType("ALERT_ESCALATED", locale));

    return responseFactory.success(types);
  }

  @GetMapping("/topics")
  public CommonResponse<List<ConsoleEventTopicResponse>> topics() {
    Locale locale = LocaleContextHolder.getLocale();
    // P2-3(2026-05-16):补 TASK_DISPATCH_PROCESS / TRIGGER_LAUNCH_V1 / VERIFIER_FAILURE_V1。
    // 这 3 个 topic 在 BatchTopics.java 都是 active 常量,运维 UI 需要看到完整 topic 列表
    // (订阅告警/排障时不能漏);测试可加 catalog-vs-BatchTopics 同步守护。
    List<ConsoleEventTopicResponse> topicList = List.of(
        topic(BatchTopics.TASK_DISPATCH_IMPORT, "TASK_DISPATCH_IMPORT", locale),
        topic(BatchTopics.TASK_DISPATCH_EXPORT, "TASK_DISPATCH_EXPORT", locale),
        topic(BatchTopics.TASK_DISPATCH_PROCESS, "TASK_DISPATCH_PROCESS", locale),
        topic(BatchTopics.TASK_DISPATCH_DISPATCH, "TASK_DISPATCH_DISPATCH", locale),
        topic(BatchTopics.TASK_DISPATCH_ATOMIC, "TASK_DISPATCH_ATOMIC", locale),
        topic(BatchTopics.TASK_RESULT, "TASK_RESULT", locale),
        topic(BatchTopics.TASK_RETRY, "TASK_RETRY", locale),
        topic(BatchTopics.TASK_DEAD_LETTER, "TASK_DEAD_LETTER", locale),
        topic(BatchTopics.OUTBOX_EVENT, "OUTBOX_EVENT", locale),
        topic(BatchTopics.WORKER_HEARTBEAT, "WORKER_HEARTBEAT", locale),
        topic(BatchTopics.TRIGGER_LAUNCH_V1, "TRIGGER_LAUNCH_V1", locale),
        topic(BatchTopics.JOB_TERMINAL_V1, "JOB_TERMINAL_V1", locale),
        topic(BatchTopics.WORKFLOW_TERMINAL_V1, "WORKFLOW_TERMINAL_V1", locale),
        topic(BatchTopics.VERIFIER_FAILURE_V1, "VERIFIER_FAILURE_V1", locale));
    return responseFactory.success(topicList);
  }

  private ConsoleEventTypeResponse eventType(String code, Locale locale) {
    String description = messageSource.getMessage("event.type." + code, null, code, locale);
    return new ConsoleEventTypeResponse(code, description);
  }

  private ConsoleEventTopicResponse topic(String name, String constantName, Locale locale) {
    String description =
        messageSource.getMessage("event.topic." + constantName, null, name, locale);
    String namespacedTopic = topicNamespace.topic(name);
    return new ConsoleEventTopicResponse(namespacedTopic, description);
  }
}
