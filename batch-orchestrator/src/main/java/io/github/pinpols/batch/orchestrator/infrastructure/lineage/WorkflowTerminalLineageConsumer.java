package io.github.pinpols.batch.orchestrator.infrastructure.lineage;

import io.github.pinpols.batch.common.kafka.BatchTopics;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.application.engine.WorkflowTerminalOutboxService;
import io.github.pinpols.batch.orchestrator.config.OpenLineageProperties;
import io.github.pinpols.batch.orchestrator.config.OrchestratorKafkaConsumerConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/** 将事务 Outbox 的 workflow 终态事件可靠转换为 OpenLineage RunEvent。 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "batch.openlineage.enabled", havingValue = "true")
public class WorkflowTerminalLineageConsumer {

  private final OpenLineageEmitter emitter;
  private final OpenLineageProperties properties;

  @KafkaListener(
      topics = BatchTopics.WORKFLOW_TERMINAL_V1,
      groupId = "${batch.openlineage.consumer-group-id:batch-openlineage-emitter}",
      containerFactory = OrchestratorKafkaConsumerConfiguration.TRIGGER_LISTENER_FACTORY)
  public void consume(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
    TerminalSnapshot snapshot;
    try {
      snapshot = parse(record.value());
    } catch (IllegalArgumentException ex) {
      log.error(
          "OpenLineage terminal event is invalid; skipping poison message: partition={} offset={} cause={}",
          record.partition(),
          record.offset(),
          ex.getMessage());
      acknowledgment.acknowledge();
      return;
    }

    try {
      emitter.emitWorkflowTerminalReliably(
          snapshot.run(), snapshot.terminalStatus(), snapshot.finishedAt());
      acknowledgment.acknowledge();
    } catch (RuntimeException ex) {
      long backoffMs = Math.max(1L, properties.getRetryBackoffMs());
      log.warn(
          "OpenLineage delivery failed; retaining Kafka offset for retry: workflowRunId={} backoffMs={} cause={}",
          snapshot.run().getId(),
          backoffMs,
          ex.getMessage());
      acknowledgment.nack(Duration.ofMillis(backoffMs));
    }
  }

  @SuppressWarnings("unchecked")
  static TerminalSnapshot parse(String payload) {
    Map<String, Object> data = JsonUtils.fromJson(payload, Map.class);
    if (EmptyChecks.isNull(data)) {
      throw new IllegalArgumentException("payload is null");
    }
    String tenantId = text(data.get("tenantId"));
    Long runId = number(data.get("workflowRunId"));
    String status = text(data.get("runStatus"));
    if (!Texts.hasText(tenantId)
        || EmptyChecks.isNull(runId)
        || !WorkflowTerminalOutboxService.isTerminal(status)) {
      throw new IllegalArgumentException("tenantId, workflowRunId or terminal status is invalid");
    }
    WorkflowRunEntity run = new WorkflowRunEntity();
    run.setId(runId);
    run.setTenantId(tenantId);
    run.setWorkflowDefinitionId(number(data.get("workflowDefinitionId")));
    run.setRelatedJobInstanceId(number(data.get("relatedJobInstanceId")));
    run.setRunStatus(status);
    run.setBizDate(localDate(data.get("bizDate")));
    run.setStartedAt(instant(data.get("startedAt")));
    run.setTraceId(text(data.get("traceId")));
    return new TerminalSnapshot(run, status, instant(data.get("finishedAt")));
  }

  private static String text(Object value) {
    return EmptyChecks.isNull(value) ? null : String.valueOf(value);
  }

  private static Long number(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value instanceof String text && EmptyChecks.isNotBlank(text)) {
      try {
        return Long.valueOf(text);
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    return null;
  }

  private static Instant instant(Object value) {
    String text = text(value);
    return Texts.hasText(text) ? Instant.parse(text) : null;
  }

  private static LocalDate localDate(Object value) {
    String text = text(value);
    return Texts.hasText(text) ? LocalDate.parse(text) : null;
  }

  record TerminalSnapshot(WorkflowRunEntity run, String terminalStatus, Instant finishedAt) {}
}
