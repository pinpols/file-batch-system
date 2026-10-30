package io.github.pinpols.batch.console.domain.notification.service;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.domain.notification.entity.WebhookSubscriptionEntity;
import io.github.pinpols.batch.console.domain.notification.mapper.ConsoleWebhookSubscriptionMapper;
import io.github.pinpols.batch.console.domain.notification.mapper.SubscriptionRuleMapper;
import io.github.pinpols.batch.console.domain.notification.mapper.WorkflowTerminalNotificationMapper;
import io.github.pinpols.batch.console.domain.notification.param.WorkflowTerminalNotificationInsertRuleParam;
import io.github.pinpols.batch.console.domain.notification.param.WorkflowTerminalNotificationInsertWebhookParam;
import io.github.pinpols.batch.console.domain.notification.view.EnabledSubscriptionRuleRow;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Kafka 确认发生在当前事务提交之后；外部 IO 由投递中继负责。 */
@Service
@RequiredArgsConstructor
public class WorkflowTerminalNotificationService {
  private static final String INVALID_JOB_TERMINAL_IDENTITY =
      "invalid Job terminal identity or schema";
  private static final TypeReference<Map<String, Object>> EVENT_DATA_TYPE =
      new TypeReference<>() {};
  private static final Set<String> TERMINAL =
      Set.of("SUCCESS", "FAILED", "TERMINATED", "SUCCESS_DRY_RUN", "FAILED_DRY_RUN");
  private final WorkflowTerminalNotificationMapper deliveries;
  private final ConsoleWebhookSubscriptionMapper subscriptions;
  private final SubscriptionRuleMapper rules;
  private final JobPushIntakeService push;

  @Transactional(transactionManager = "transactionManager")
  public void accept(String eventKey, String payload) {
    Map<String, Object> data = JsonUtils.fromJson(payload, EVENT_DATA_TYPE);
    if (EmptyChecks.isNull(data) || !"v2".equals(data.get("schemaVersion"))) {
      throw new IllegalArgumentException("invalid Workflow terminal identity or schema");
    }
    if (!(data.get("tenantId") instanceof String tenant) || EmptyChecks.isBlank(tenant)) {
      throw new IllegalArgumentException("invalid Workflow terminal identity or schema");
    }
    if (!(data.get("workflowRunId") instanceof Number id) || id.longValue() <= 0) {
      throw new IllegalArgumentException("invalid Workflow terminal identity or schema");
    }
    if (!(data.get("runStatus") instanceof String status) || !TERMINAL.contains(status)) {
      throw new IllegalArgumentException("invalid Workflow terminal identity or schema");
    }
    if (!(tenant + ":workflow:" + id.longValue() + ":terminal").equals(eventKey)) {
      throw new IllegalArgumentException("invalid Workflow terminal identity or schema");
    }
    String eventType =
        switch (status) {
          case "SUCCESS" -> "WORKFLOW_SUCCESS";
          case "FAILED" -> "WORKFLOW_FAILED";
          default -> null;
        };
    persistAndExpand(tenant, eventKey, payload, data, eventType, "workflow-runs");
  }

  @Transactional(transactionManager = "transactionManager")
  public void acceptJob(String eventKey, String payload) {
    Map<String, Object> data = JsonUtils.fromJson(payload, EVENT_DATA_TYPE);
    boolean notificationPayloadIsNotJobInstanceV1 = EmptyChecks.isNull(data)
        || !"v1".equals(data.get("schemaVersion"))
        || !"JOB_INSTANCE".equals(data.get("entityType"));
    if (notificationPayloadIsNotJobInstanceV1) {
      throw new IllegalArgumentException(INVALID_JOB_TERMINAL_IDENTITY);
    }
    if (!(data.get("tenantId") instanceof String tenant) || EmptyChecks.isBlank(tenant)) {
      throw new IllegalArgumentException(INVALID_JOB_TERMINAL_IDENTITY);
    }
    if (!(data.get("jobInstanceId") instanceof Number id) || id.longValue() <= 0) {
      throw new IllegalArgumentException(INVALID_JOB_TERMINAL_IDENTITY);
    }
    if (!(data.get("terminalVersion") instanceof Number version) || version.longValue() < 0) {
      throw new IllegalArgumentException(INVALID_JOB_TERMINAL_IDENTITY);
    }
    if (!(data.get("instanceStatus") instanceof String status)
        || !JobInstanceStatus.terminalCodes().contains(status)) {
      throw new IllegalArgumentException(INVALID_JOB_TERMINAL_IDENTITY);
    }
    if (!(data.get("dryRun") instanceof Boolean dryRun)) {
      throw new IllegalArgumentException(INVALID_JOB_TERMINAL_IDENTITY);
    }
    if (!(data.get("jobCode") instanceof String code) || EmptyChecks.isBlank(code)) {
      throw new IllegalArgumentException(INVALID_JOB_TERMINAL_IDENTITY);
    }
    if (!(tenant + ":job:" + id.longValue() + ":terminal:" + status).equals(eventKey)) {
      throw new IllegalArgumentException(INVALID_JOB_TERMINAL_IDENTITY);
    }
    String eventType = dryRun
        ? null
        : switch (status) {
          case "SUCCESS" -> "JOB_SUCCESS";
          case "FAILED", "PARTIAL_FAILED" -> "JOB_FAILED";
          default -> null;
        };
    persistAndExpand(tenant, eventKey, payload, data, eventType, "job-instances");
  }

  private void persistAndExpand(
      String tenant,
      String eventKey,
      String payload,
      Map<String, Object> data,
      String eventType,
      String stream) {
    Instant finished;
    try {
      String businessDateValue = String.valueOf(data.get("bizDate"));
      LocalDate.parse(businessDateValue);
      String finishedAtValue = String.valueOf(data.get("finishedAt"));
      finished = Instant.parse(finishedAtValue);
    } catch (DateTimeException invalid) {
      throw new IllegalArgumentException("invalid terminal notification dates", invalid);
    }
    deliveries.bindTenant(tenant);
    deliveries.lockReceipt(tenant, eventKey);
    if (deliveries.insertReceipt(tenant, eventKey, payload) == 0) {
      Map<String, Object> original =
          JsonUtils.fromJson(deliveries.receiptPayload(tenant, eventKey), EVENT_DATA_TYPE);
      if (!data.equals(original)) {
        throw new IllegalArgumentException(
            "conflicting snapshot for terminal notification identity");
      }
      return;
    }
    if ("job-instances".equals(stream)) push.accept(tenant, eventKey, data);
    if (EmptyChecks.isNull(eventType)) return;
    String notification = JsonUtils.toJson(
        new WebhookEventPayload(tenant, eventType, stream, eventKey, finished, data));
    for (WebhookSubscriptionEntity subscription : subscriptions.findEnabledByTenant(tenant)) {
      if (SubscriptionRuleDispatchPolicy.eventTypeMatches(
          subscription.getEventTypes(), eventType)) {
        WorkflowTerminalNotificationInsertWebhookParam
            workflowTerminalNotificationInsertWebhookParam =
                WorkflowTerminalNotificationInsertWebhookParam.builder()
                    .tenantId(tenant)
                    .recipientId(subscription.getId())
                    .eventKey(eventKey)
                    .eventType(eventType)
                    .payload(notification)
                    .build();
        deliveries.insertWebhook(workflowTerminalNotificationInsertWebhookParam);
      }
    }
    for (EnabledSubscriptionRuleRow rule : rules.selectEnabledByEventType(tenant, eventType)) {
      if (SubscriptionRuleDispatchPolicy.matchesRule(rule, eventType, data)) {
        WorkflowTerminalNotificationInsertRuleParam workflowTerminalNotificationInsertRuleParam =
            WorkflowTerminalNotificationInsertRuleParam.builder()
                .tenantId(tenant)
                .recipientId(rule.id())
                .channelCode(rule.channelCode())
                .eventKey(eventKey)
                .eventType(eventType)
                .payload(notification)
                .build();
        deliveries.insertRule(workflowTerminalNotificationInsertRuleParam);
      }
    }
  }
}
