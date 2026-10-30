package io.github.pinpols.batch.console.domain.notification.infrastructure;

import io.github.pinpols.batch.common.kafka.BatchKafkaTopicNamespace;
import io.github.pinpols.batch.common.kafka.BatchTopics;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.domain.notification.service.WorkflowTerminalNotificationService;
import java.time.Duration;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class JobTerminalNotificationConsumer {
  private final WorkflowTerminalNotificationService notifications;
  private final BatchKafkaTopicNamespace topicNamespace;

  @KafkaListener(
      id = "consoleJobTerminalNotifications",
      topics = "#{__listener.topicName()}",
      groupId = "#{__listener.consumerGroupId()}",
      containerFactory = "terminalNotificationKafkaFactory")
  public void consume(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
    try {
      Map<?, ?> data = JsonUtils.fromJson(record.value(), Map.class);
      String tenant = requireTenant(data);
      RlsTenantContextHolder.runWithTenant(tenant, () -> {
        notifications.acceptJob(record.key(), record.value());
        return null;
      });
    } catch (IllegalArgumentException invalid) {
      log.error(
          "Invalid Job terminal notification; no external action: partition={} offset={} cause={}",
          record.partition(),
          record.offset(),
          SwallowedExceptionLogger.summary(invalid));
      acknowledgment.acknowledge();
      return;
    } catch (RuntimeException unavailable) {
      log.warn(
          "Job terminal notification was not persisted; retaining Kafka offset: partition={} offset={} cause={}",
          record.partition(),
          record.offset(),
          SwallowedExceptionLogger.summary(unavailable));
      acknowledgment.nack(Duration.ofSeconds(1));
      return;
    }
    acknowledgment.acknowledge();
  }

  private static String requireTenant(Map<?, ?> data) {
    if (EmptyChecks.isNull(data)) {
      throw new IllegalArgumentException("missing terminal notification tenant");
    }
    Object tenantValue = data.get("tenantId");
    if (!(tenantValue instanceof String tenant) || EmptyChecks.isBlank(tenant)) {
      throw new IllegalArgumentException("missing terminal notification tenant");
    }
    return tenant;
  }

  public String topicName() {
    return topicNamespace.topic(BatchTopics.JOB_TERMINAL_V1);
  }

  public String consumerGroupId() {
    return topicNamespace.consumerGroup("batch-console-job-terminal-notifications");
  }
}
