package io.github.pinpols.batch.console.domain.notification.service;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.domain.notification.mapper.ConsoleWebhookSubscriptionMapper;
import io.github.pinpols.batch.console.domain.notification.mapper.SubscriptionRuleMapper;
import io.github.pinpols.batch.console.domain.notification.mapper.WorkflowTerminalNotificationMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Kafka acknowledgment follows this transaction; external IO belongs to the delivery relay. */
@Service
@RequiredArgsConstructor
public class WorkflowTerminalNotificationService {
  private static final Set<String> TERMINAL = Set.of(
      "SUCCESS", "FAILED", "TERMINATED", "SUCCESS_DRY_RUN", "FAILED_DRY_RUN");
  private final WorkflowTerminalNotificationMapper deliveries;
  private final ConsoleWebhookSubscriptionMapper subscriptions;
  private final SubscriptionRuleMapper rules;

  @Transactional(transactionManager = "transactionManager")
  @SuppressWarnings("unchecked")
  public void accept(String eventKey, String payload) {
    Map<String, Object> data = JsonUtils.fromJson(payload, Map.class);
    if (EmptyChecks.isNull(data) || !"v2".equals(data.get("schemaVersion"))
        || !(data.get("tenantId") instanceof String tenant) || tenant.isBlank()
        || !(data.get("workflowRunId") instanceof Number id) || id.longValue() <= 0
        || !(data.get("runStatus") instanceof String status) || !TERMINAL.contains(status)
        || !((tenant + ":workflow:" + id.longValue() + ":terminal").equals(eventKey))) {
      throw new IllegalArgumentException("invalid Workflow terminal identity or schema");
    }
    String eventType = switch (status) {
      case "SUCCESS" -> "WORKFLOW_SUCCESS";
      case "FAILED" -> "WORKFLOW_FAILED";
      default -> null;
    };
    persistAndExpand(tenant, eventKey, payload, data, eventType, "workflow-runs");
  }

  @Transactional(transactionManager = "transactionManager")
  @SuppressWarnings("unchecked")
  public void acceptJob(String eventKey, String payload) {
    Map<String, Object> data = JsonUtils.fromJson(payload, Map.class);
    if (EmptyChecks.isNull(data) || !"v1".equals(data.get("schemaVersion"))
        || !"JOB_INSTANCE".equals(data.get("entityType"))
        || !(data.get("tenantId") instanceof String tenant) || tenant.isBlank()
        || !(data.get("jobInstanceId") instanceof Number id) || id.longValue() <= 0
        || !(data.get("terminalVersion") instanceof Number version) || version.longValue() < 0
        || !(data.get("instanceStatus") instanceof String status) || !JobInstanceStatus.terminalCodes().contains(status)
        || !(data.get("dryRun") instanceof Boolean dryRun)
        || !(data.get("jobCode") instanceof String code) || code.isBlank()
        || !(tenant + ":job:" + id.longValue() + ":terminal:" + status).equals(eventKey)) {
      throw new IllegalArgumentException("invalid Job terminal identity or schema");
    }
    String eventType = dryRun ? null : switch (status) {
      case "SUCCESS" -> "JOB_SUCCESS";
      case "FAILED", "PARTIAL_FAILED" -> "JOB_FAILED";
      default -> null;
    };
    persistAndExpand(tenant, eventKey, payload, data, eventType, "job-instances");
  }

  @SuppressWarnings("unchecked")
  private void persistAndExpand(String tenant, String eventKey, String payload,
      Map<String, Object> data, String eventType, String stream) {
    Instant finished;
    try {
      LocalDate.parse(String.valueOf(data.get("bizDate")));
      finished = Instant.parse(String.valueOf(data.get("finishedAt")));
    } catch (java.time.DateTimeException invalid) {
      throw new IllegalArgumentException("invalid terminal notification dates", invalid);
    }
    if (deliveries.insertReceipt(tenant, eventKey, payload) == 0) {
      Map<String, Object> original = JsonUtils.fromJson(deliveries.receiptPayload(tenant, eventKey), Map.class);
      if (!data.equals(original)) {
        throw new IllegalArgumentException("conflicting snapshot for terminal notification identity");
      }
      return;
    }
    if (EmptyChecks.isNull(eventType)) return;
    String notification = JsonUtils.toJson(new WebhookEventPayload(
        tenant, eventType, stream, eventKey, finished, data));
    for (var subscription : subscriptions.findEnabledByTenant(tenant)) {
      if (SubscriptionRuleDispatchPolicy.eventTypeMatches(subscription.getEventTypes(), eventType)) {
        deliveries.insertWebhook(tenant, subscription.getId(), eventKey, eventType, notification);
      }
    }
    for (var rule : rules.selectEnabledByEventType(tenant, eventType)) {
      if (SubscriptionRuleDispatchPolicy.matchesRule(rule, eventType, data)) {
        deliveries.insertRule(tenant, ((Number) rule.get("id")).longValue(),
            String.valueOf(rule.get("channel_code")), eventKey, eventType, notification);
      }
    }
  }
}
