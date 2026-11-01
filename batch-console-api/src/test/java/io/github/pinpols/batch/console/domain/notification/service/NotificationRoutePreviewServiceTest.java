package io.github.pinpols.batch.console.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.console.domain.notification.entity.WebhookSubscriptionEntity;
import io.github.pinpols.batch.console.domain.notification.mapper.ConsoleWebhookSubscriptionMapper;
import io.github.pinpols.batch.console.domain.notification.mapper.SubscriptionRuleMapper;
import io.github.pinpols.batch.console.domain.notification.view.EnabledSubscriptionRuleRow;
import io.github.pinpols.batch.console.shared.query.TenantIdResolver;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("通知路由预览")
class NotificationRoutePreviewServiceTest {
  @Mock
  TenantIdResolver guard;

  @Mock
  SubscriptionRuleMapper rules;

  @Mock
  ConsoleWebhookSubscriptionMapper subscriptions;

  @Test
  @DisplayName("使用真实匹配策略且不泄露目的地或修改投递状态")
  void shouldMatchActualPoliciesWithoutExposingDestinations_whenPreviewingJobEvent()
      throws Exception {
    when(guard.resolveTenant("child")).thenReturn("child");
    when(rules.selectEnabledByEventType("child", "JOB_FAILED"))
        .thenReturn(List.of(
            new EnabledSubscriptionRuleRow(
                1L,
                "child",
                "matched",
                "mail",
                "JOB_FAILED",
                null,
                "JOB_A",
                "EMAIL",
                "secret-channel-configuration"),
            new EnabledSubscriptionRuleRow(
                2L,
                "child",
                "wrong severity",
                "mail",
                "JOB_FAILED",
                "LOW",
                "JOB_A",
                "EMAIL",
                "secret-channel-configuration"),
            new EnabledSubscriptionRuleRow(
                3L,
                "child",
                "wrong event",
                "mail",
                "JOB_FAILED_OTHER",
                null,
                null,
                "EMAIL",
                "secret-channel-configuration")));
    WebhookSubscriptionEntity wildcard = subscription(4L, "all", "*", "secret-webhook");
    WebhookSubscriptionEntity exact = subscription(5L, "other", "JOB_SUCCESS", "secret-webhook");
    when(subscriptions.findEnabledByTenant("child")).thenReturn(List.of(wildcard, exact));

    var result = new NotificationRoutePreviewService(guard, rules, subscriptions)
        .preview("child", "job_failed", "high", "job_a");

    assertThat(result.rules())
        .extracting(NotificationRoutePreviewService.Rule::id)
        .containsExactly(1L);
    assertThat(result.webhooks())
        .extracting(NotificationRoutePreviewService.Webhook::id)
        .containsExactly(4L);
    assertThat(new ObjectMapper().writeValueAsString(result))
        .doesNotContain("secret-channel", "secret-webhook");
    verify(rules).selectEnabledByEventType("child", "JOB_FAILED");
    verify(subscriptions).findEnabledByTenant("child");
    verifyNoMoreInteractions(rules, subscriptions);
  }

  @Test
  @DisplayName("升级告警以真实级别匹配，兼容大小写")
  void shouldMatchAlertSeverity_whenInputUsesMixedCase() {
    when(guard.resolveTenant("tenant")).thenReturn("tenant");
    when(rules.selectEnabledByEventType("tenant", "ALERT_ESCALATED"))
        .thenReturn(List.of(new EnabledSubscriptionRuleRow(
            1L, "tenant", "filtered", "mail", "*", "WARN", null, "EMAIL", null)));
    when(subscriptions.findEnabledByTenant("tenant")).thenReturn(List.of());
    var result = new NotificationRoutePreviewService(guard, rules, subscriptions)
        .preview("tenant", "ALERT_ESCALATED", " warn ", null);
    assertThat(result.rules())
        .extracting(NotificationRoutePreviewService.Rule::id)
        .containsExactly(1L);
  }

  @Test
  @DisplayName("四种真实升级告警级别均可预览")
  void shouldAcceptEveryRealAlertSeverity_whenPreviewingEscalation() {
    when(guard.resolveTenant("tenant")).thenReturn("tenant");
    var service = new NotificationRoutePreviewService(guard, rules, subscriptions);
    for (String severity : List.of("INFO", "WARN", "ERROR", "CRITICAL")) {
      assertThat(service.preview("tenant", "ALERT_ESCALATED", severity, null).rules())
          .isEmpty();
    }
  }

  @Test
  @DisplayName("升级告警禁止缺失或不存在的级别")
  void shouldRejectAlertSeverity_whenMissingOrImpossible() {
    when(guard.resolveTenant("tenant")).thenReturn("tenant");
    var service = new NotificationRoutePreviewService(guard, rules, subscriptions);
    assertThatThrownBy(() -> service.preview("tenant", "ALERT_ESCALATED", null, null))
        .isInstanceOf(io.github.pinpols.batch.common.exception.BizException.class);
    assertThatThrownBy(() -> service.preview("tenant", "ALERT_ESCALATED", " ", null))
        .isInstanceOf(io.github.pinpols.batch.common.exception.BizException.class);
    assertThatThrownBy(() -> service.preview("tenant", "ALERT_ESCALATED", "WARNING", null))
        .isInstanceOf(io.github.pinpols.batch.common.exception.BizException.class);
    verifyNoInteractions(rules, subscriptions);
  }

  @Test
  @DisplayName("作业终态事件需要作业编码")
  void shouldRequireJobCode_whenPreviewingJobTerminalEvent() {
    when(guard.resolveTenant("tenant")).thenReturn("tenant");
    var service = new NotificationRoutePreviewService(guard, rules, subscriptions);
    assertThatThrownBy(() -> service.preview("tenant", "JOB_FAILED", null, null))
        .isInstanceOf(io.github.pinpols.batch.common.exception.BizException.class);
    verifyNoInteractions(rules, subscriptions);
  }

  @Test
  @DisplayName("越权租户在读取配置前被拒绝")
  void shouldDenyTenant_whenNotAuthorizedBeforeReadingConfiguration() {
    when(guard.resolveTenant("other")).thenThrow(new IllegalArgumentException("forbidden"));
    var service = new NotificationRoutePreviewService(guard, rules, subscriptions);
    assertThatThrownBy(() -> service.preview("other", "JOB_FAILED", null, null))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(rules, subscriptions);
  }

  private WebhookSubscriptionEntity subscription(
      Long id, String name, String events, String secret) {
    WebhookSubscriptionEntity value = new WebhookSubscriptionEntity();
    value.setId(id);
    value.setName(name);
    value.setEventTypes(events);
    value.setSecret(secret);
    value.setCallbackUrl("https://secret.example");
    return value;
  }
}
