package io.github.pinpols.batch.console.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.pinpols.batch.console.domain.notification.entity.WebhookSubscriptionEntity;
import io.github.pinpols.batch.console.domain.notification.mapper.ConsoleWebhookSubscriptionMapper;
import io.github.pinpols.batch.console.domain.notification.mapper.SubscriptionRuleMapper;
import io.github.pinpols.batch.console.domain.notification.mapper.WorkflowTerminalNotificationMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Job 终态接纳:状态与 Workflow 隔离、重投和干运行")
class JobTerminalNotificationServiceTest {
  private final WorkflowTerminalNotificationMapper mapper = mock(WorkflowTerminalNotificationMapper.class);
  private final ConsoleWebhookSubscriptionMapper subscriptions = mock(ConsoleWebhookSubscriptionMapper.class);
  private final SubscriptionRuleMapper rules = mock(SubscriptionRuleMapper.class);
  private final WorkflowTerminalNotificationService service = new WorkflowTerminalNotificationService(mapper, subscriptions, rules);

  @ParameterizedTest
  @ValueSource(strings={"SUCCESS","FAILED","PARTIAL_FAILED"})
  @DisplayName("仅展开对应 Job 订阅,同 ID 的 Workflow 不能混入")
  void shouldKeepFormalJobOutcomeSeparateFromWorkflow(String status) {
    String key = key(status);
    when(mapper.insertReceipt(eq("ta"),eq(key),anyString())).thenReturn(1);
    var job = new WebhookSubscriptionEntity(); job.setId(1L); job.setEventTypes("JOB_SUCCESS,JOB_FAILED");
    var workflow = new WebhookSubscriptionEntity(); workflow.setId(2L); workflow.setEventTypes("WORKFLOW_SUCCESS,WORKFLOW_FAILED");
    when(subscriptions.findEnabledByTenant("ta")).thenReturn(List.of(job,workflow));
    service.acceptJob(key,payload(status,false));
    verify(mapper).insertWebhook(eq("ta"),eq(1L),eq(key),eq(status.equals("SUCCESS")?"JOB_SUCCESS":"JOB_FAILED"),anyString());
    verify(mapper,never()).insertWebhook(eq("ta"),eq(2L),anyString(),anyString(),anyString());
  }

  @ParameterizedTest
  @ValueSource(strings={"SUCCESS","FAILED","SUCCESS_DRY_RUN","FAILED_DRY_RUN","CANCELLED","TERMINATED"})
  @DisplayName("干运行或取消/终止不冒充正式 Job 成功失败")
  void shouldRetainNonFormalFactsWithoutNotification(String status) {
    when(mapper.insertReceipt(eq("ta"),eq(key(status)),anyString())).thenReturn(1);
    service.acceptJob(key(status),payload(status,!status.equals("CANCELLED")&&!status.equals("TERMINATED")));
    verifyNoInteractions(subscriptions,rules);
  }

  @Test
  @DisplayName("重复 Job 事实不展开新订阅,坏 schema/混用 Workflow 身份拒绝")
  void shouldRejectWrongEntityAndSchemaBeforePersistence() {
    assertThatThrownBy(() -> service.acceptJob(key("SUCCESS"),payload("SUCCESS",false).replace("v1","v999")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.acceptJob("ta:workflow:10:terminal",payload("SUCCESS",false)))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(mapper);
    when(mapper.receiptPayload("ta",key("SUCCESS"))).thenReturn(payload("SUCCESS",false));
    service.acceptJob(key("SUCCESS"),payload("SUCCESS",false));
    verifyNoInteractions(subscriptions,rules);
  }

  private String key(String status) { return "ta:job:10:terminal:"+status; }
  private String payload(String status, boolean dryRun) {
    return """
        {"schemaVersion":"v1","entityType":"JOB_INSTANCE","tenantId":"ta","jobInstanceId":10,
         "jobCode":"IMPORT","terminalVersion":5,"instanceStatus":"%s","dryRun":%s,
         "bizDate":"2026-10-19","finishedAt":"2026-10-20T01:00:00Z"}
        """.formatted(status,dryRun);
  }
}
