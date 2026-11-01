package io.github.pinpols.batch.console.domain.notification.application;

import io.github.pinpols.batch.common.kafka.BatchKafkaTopicNamespace;
import io.github.pinpols.batch.common.kafka.BatchTopics;
import io.github.pinpols.batch.console.domain.notification.application.contract.response.ConsoleEventTopicResponse;
import io.github.pinpols.batch.console.domain.notification.application.contract.response.ConsoleEventTypeResponse;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;

/** 可订阅业务事件及其运行环境主题目录。 */
@Service
@RequiredArgsConstructor
public class ConsoleEventCatalogService {
  private final MessageSource messageSource;
  private final BatchKafkaTopicNamespace topicNamespace;

  public List<ConsoleEventTypeResponse> eventTypes(Locale locale) {
    return List.of(
        eventType("JOB_SUCCESS", locale),
        eventType("JOB_FAILED", locale),
        eventType("WORKFLOW_SUCCESS", locale),
        eventType("WORKFLOW_FAILED", locale),
        eventType("ALERT_ESCALATED", locale));
  }

  public List<ConsoleEventTopicResponse> topics(Locale locale) {
    return List.of(
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
  }

  private ConsoleEventTypeResponse eventType(String code, Locale locale) {
    String description = messageSource.getMessage("event.type." + code, null, code, locale);
    return new ConsoleEventTypeResponse(code, description);
  }

  private ConsoleEventTopicResponse topic(String name, String constantName, Locale locale) {
    String description = messageSource.getMessage("event.topic." + constantName, null, name, locale);
    return new ConsoleEventTopicResponse(topicNamespace.topic(name), description);
  }
}
