package com.example.batch.console.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.common.kafka.BatchTopics;
import com.example.batch.console.service.ConsoleResponseFactory;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

  @GetMapping("/event-types")
  public CommonResponse<List<Map<String, String>>> eventTypes() {
    Locale locale = LocaleContextHolder.getLocale();
    List<Map<String, String>> types =
        List.of(
            eventType("JOB_SUCCESS", locale),
            eventType("JOB_FAILED", locale),
            eventType("JOB_TIMEOUT", locale),
            eventType("JOB_SLA_BREACH", locale),
            eventType("WORKFLOW_SUCCESS", locale),
            eventType("WORKFLOW_FAILED", locale),
            eventType("FILE_ARRIVED", locale),
            eventType("FILE_PROCESSED", locale),
            eventType("FILE_ERROR", locale),
            eventType("WORKER_OFFLINE", locale),
            eventType("WORKER_DRAIN", locale),
            eventType("ALERT_TRIGGERED", locale),
            eventType("APPROVAL_PENDING", locale),
            eventType("APPROVAL_COMPLETED", locale));
    return responseFactory.success(types);
  }

  @GetMapping("/topics")
  public CommonResponse<List<Map<String, String>>> topics() {
    Locale locale = LocaleContextHolder.getLocale();
    List<Map<String, String>> topicList =
        List.of(
            topic(BatchTopics.TASK_DISPATCH_IMPORT, "TASK_DISPATCH_IMPORT", locale),
            topic(BatchTopics.TASK_DISPATCH_EXPORT, "TASK_DISPATCH_EXPORT", locale),
            topic(BatchTopics.TASK_DISPATCH_DISPATCH, "TASK_DISPATCH_DISPATCH", locale),
            topic(BatchTopics.TASK_RESULT, "TASK_RESULT", locale),
            topic(BatchTopics.TASK_RETRY, "TASK_RETRY", locale),
            topic(BatchTopics.TASK_DEAD_LETTER, "TASK_DEAD_LETTER", locale),
            topic(BatchTopics.OUTBOX_EVENT, "OUTBOX_EVENT", locale),
            topic(BatchTopics.WORKER_HEARTBEAT, "WORKER_HEARTBEAT", locale));
    return responseFactory.success(topicList);
  }

  private Map<String, String> eventType(String code, Locale locale) {
    String description = messageSource.getMessage("event.type." + code, null, code, locale);
    return Map.of("code", code, "description", description);
  }

  private Map<String, String> topic(String name, String constantName, Locale locale) {
    String description =
        messageSource.getMessage("event.topic." + constantName, null, name, locale);
    return Map.of("name", name, "description", description);
  }
}
