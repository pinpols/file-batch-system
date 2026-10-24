package io.github.pinpols.batch.console.domain.notification.infrastructure;

import io.github.pinpols.batch.common.kafka.BatchTopics;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.util.Map;
import io.github.pinpols.batch.console.domain.notification.service.WorkflowTerminalNotificationService;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class WorkflowTerminalNotificationConsumer {
  private final WorkflowTerminalNotificationService notifications;

  @KafkaListener(id = "consoleWorkflowTerminalNotifications", topics = BatchTopics.WORKFLOW_TERMINAL_V1,
      groupId = "batch-console-workflow-terminal-notifications", containerFactory = "terminalNotificationKafkaFactory")
  public void consume(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
    try {
      Map<?, ?> data = JsonUtils.fromJson(record.value(), Map.class);
      if (EmptyChecks.isNull(data) || !(data.get("tenantId") instanceof String tenant) || tenant.isBlank()) {
        throw new IllegalArgumentException("missing terminal notification tenant");
      }
      RlsTenantContextHolder.runWithTenant(tenant, () -> {
        notifications.accept(record.key(), record.value());
        return null;
      });
    } catch (IllegalArgumentException invalid) {
      log.error("Invalid Workflow terminal notification; no external action: partition={} offset={} cause={}",
          record.partition(), record.offset(), SwallowedExceptionLogger.summary(invalid));
      acknowledgment.acknowledge();
      return;
    } catch (RuntimeException unavailable) {
      log.warn("Workflow terminal notification was not persisted; retaining Kafka offset: partition={} offset={} cause={}",
          record.partition(), record.offset(), SwallowedExceptionLogger.summary(unavailable));
      acknowledgment.nack(Duration.ofSeconds(1));
      return;
    }
    acknowledgment.acknowledge();
  }
}
