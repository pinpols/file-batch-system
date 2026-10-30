package io.github.pinpols.batch.console.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.pinpols.batch.console.domain.notification.entity.WebhookSubscriptionEntity;
import io.github.pinpols.batch.console.domain.notification.mapper.ConsoleWebhookSubscriptionMapper;
import io.github.pinpols.batch.console.domain.notification.mapper.SubscriptionRuleMapper;
import io.github.pinpols.batch.console.domain.notification.mapper.WorkflowTerminalNotificationMapper;
import io.github.pinpols.batch.console.domain.notification.view.EnabledSubscriptionRuleRow;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("Workflow 终态订阅展开:实体口径,首次目标冻结与干运行隔离")
@ExtendWith(MockitoExtension.class)
class WorkflowTerminalNotificationServiceTest {
  @Mock
  private WorkflowTerminalNotificationMapper mapper;

  @Mock
  private ConsoleWebhookSubscriptionMapper subscriptions;

  @Mock
  private SubscriptionRuleMapper rules;

  private WorkflowTerminalNotificationService service;
  private static final String KEY = "t1:workflow:10:terminal";

  @Test
  @DisplayName("Workflow 失败仅展开对应订阅,不触发 Job 成功订阅")
  void shouldExpandFormalFailureWithoutConfusingJobSuccess() {
    when(mapper.insertReceipt(eq("t1"), eq(KEY), anyString())).thenReturn(1);
    var workflow = new WebhookSubscriptionEntity();
    workflow.setId(1L);
    workflow.setEventTypes("WORKFLOW_FAILED");
    var job = new WebhookSubscriptionEntity();
    job.setId(2L);
    job.setEventTypes("JOB_SUCCESS");
    when(subscriptions.findEnabledByTenant("t1")).thenReturn(List.of(workflow, job));
    when(rules.selectEnabledByEventType("t1", "WORKFLOW_FAILED"))
        .thenReturn(List.of(new EnabledSubscriptionRuleRow(
            7L, "t1", null, "channel", "WORKFLOW_FAILED", null, null, null, null)));
    service.accept(KEY, payload("FAILED"));
    verify(mapper)
        .insertWebhook(ArgumentMatchers.argThat(parameters -> parameters != null
            && Objects.equals(parameters.tenantId(), "t1")
            && Objects.equals(parameters.recipientId(), 1L)
            && Objects.equals(parameters.eventKey(), KEY)
            && Objects.equals(parameters.eventType(), "WORKFLOW_FAILED")
            && parameters.payload() != null));
    verify(mapper, never())
        .insertWebhook(ArgumentMatchers.argThat(parameters -> parameters != null
            && Objects.equals(parameters.tenantId(), "t1")
            && Objects.equals(parameters.recipientId(), 2L)
            && parameters.eventKey() != null
            && parameters.eventType() != null
            && parameters.payload() != null));
    verify(mapper)
        .insertRule(ArgumentMatchers.argThat(parameters -> parameters != null
            && Objects.equals(parameters.tenantId(), "t1")
            && Objects.equals(parameters.recipientId(), 7L)
            && Objects.equals(parameters.channelCode(), "channel")
            && Objects.equals(parameters.eventKey(), KEY)
            && Objects.equals(parameters.eventType(), "WORKFLOW_FAILED")
            && parameters.payload() != null));
  }

  @Test
  @DisplayName("相同事件再次到达不重新展开当前订阅")
  void shouldKeepFirstRecipientSetOnRedelivery() {
    when(mapper.receiptPayload("t1", KEY)).thenReturn(payload("FAILED"));
    service.accept(KEY, payload("FAILED"));
    verifyNoInteractions(subscriptions, rules);
    verify(mapper, never())
        .insertWebhook(ArgumentMatchers.argThat(parameters -> parameters != null
            && parameters.tenantId() != null
            && parameters.eventKey() != null
            && parameters.eventType() != null
            && parameters.payload() != null));
  }

  @Test
  @DisplayName("同一终态身份的冲突载荷被拒绝,不覆盖首次事实")
  void shouldRejectConflictingTerminalStatus() {
    when(mapper.receiptPayload("t1", KEY)).thenReturn(payload("FAILED"));
    assertThatThrownBy(() -> service.accept(KEY, payload("SUCCESS")))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(subscriptions, rules);
  }

  @ParameterizedTest
  @ValueSource(strings = {"SUCCESS_DRY_RUN", "FAILED_DRY_RUN", "TERMINATED"})
  @DisplayName("干运行或取消仅留原始回执,不冒充正式成功失败通知")
  void shouldPreserveDistinctTerminalFactsWithoutFormalNotification(String status) {
    when(mapper.insertReceipt(eq("t1"), eq(KEY), anyString())).thenReturn(1);
    service.accept(KEY, payload(status));
    verifyNoInteractions(subscriptions, rules);
  }

  @Test
  @DisplayName("未知版本与错误业务日期在写入回执前被拒绝")
  void shouldRejectInvalidContractBeforePersistence() {
    assertThatThrownBy(() -> service.accept(KEY, payload("SUCCESS").replace("v2", "v999")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> service.accept(KEY, payload("SUCCESS").replace("2026-10-19", "bad-date")))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(mapper, subscriptions, rules);
  }

  private static String payload(String status) {
    return """
        {"schemaVersion":"v2","tenantId":"t1","workflowRunId":10,"runStatus":"%s",
         "bizDate":"2026-10-19","finishedAt":"2026-10-20T01:00:00Z"}
        """.formatted(status);
  }

  @BeforeEach
  void initializeSubject() {
    service = new WorkflowTerminalNotificationService(
        mapper, subscriptions, rules, mock(JobPushIntakeService.class));
  }
}
