package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.domain.notification.entity.WebhookSubscriptionEntity;
import io.github.pinpols.batch.console.domain.notification.service.SubscriptionRuleWebhookDispatcher;
import io.github.pinpols.batch.console.domain.notification.service.WebhookDeliveryResult;
import io.github.pinpols.batch.console.domain.notification.service.WebhookDispatcher;
import io.github.pinpols.batch.console.domain.notification.service.WebhookEventPayload;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(classes = BatchConsoleApiApplication.class)
@DisplayName("实时订阅规则：真实数据库候选与模拟网络投递")
class SubscriptionRuleRealtimeSelectionIntegrationTest extends AbstractIntegrationTest {
  @Autowired
  private JdbcTemplate jdbc;

  @Autowired
  private SubscriptionRuleWebhookDispatcher dispatcher;

  @MockitoBean
  private WebhookDispatcher webhookDispatcher;

  @Test
  @DisplayName("Unicode 大小写扩展后的自定义事件从数据库规则匹配到投递")
  void shouldDispatchUnicodeCustomEventFromPersistedRule() {
    String tenant = "realtime-unicode-" + Long.toUnsignedString(System.nanoTime());
    String insertChannelSql = """
        insert into batch.notification_channel
          (tenant_id, channel_code, channel_name, channel_type, config_json, enabled)
        values (?, 'custom', 'custom', 'WEBHOOK', '{"url":"https://custom.example.com"}'::jsonb, true)
        """;
    String insertRuleSql = """
        insert into batch.subscription_rule (tenant_id, rule_name, channel_code, event_types, enabled)
        values (?, 'custom-rule', 'custom', 'straße', true)
        """;
    jdbc.update(insertChannelSql, tenant);
    jdbc.update(insertRuleSql, tenant);
    when(webhookDispatcher.attemptDelivery(any(), any(), any()))
        .thenReturn(WebhookDeliveryResult.ok());
    RlsTenantContextHolder.runWithTenant(
        tenant,
        () -> dispatcher.dispatch(tenant, "straße", "stream", "cursor", Map.of(), Instant.now()));
    ArgumentCaptor<WebhookSubscriptionEntity> subscription =
        ArgumentCaptor.forClass(WebhookSubscriptionEntity.class);
    ArgumentCaptor<WebhookEventPayload> payload =
        ArgumentCaptor.forClass(WebhookEventPayload.class);
    verify(webhookDispatcher, timeout(5000))
        .attemptDelivery(subscription.capture(), payload.capture(), any());
    assertThat(subscription.getValue().getCallbackUrl()).isEqualTo("https://custom.example.com");
    assertThat(payload.getValue().eventType()).isEqualTo("STRASSE");
    assertThat(payload.getValue().tenantId()).isEqualTo(tenant);
  }
}
