package io.github.pinpols.batch.console.domain.notification.service;

import io.github.pinpols.batch.console.domain.notification.param.WorkflowTerminalNotificationClaimParam;
import io.github.pinpols.batch.console.domain.notification.param.WorkflowTerminalNotificationFinishParam;


import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.pinpols.batch.console.config.WebhookRelayProperties;
import io.github.pinpols.batch.console.domain.notification.view.TerminalNotificationDelivery;
import io.github.pinpols.batch.console.domain.notification.entity.WebhookSubscriptionEntity;
import io.github.pinpols.batch.console.domain.notification.mapper.ConsoleWebhookSubscriptionMapper;
import io.github.pinpols.batch.console.domain.notification.mapper.NotificationChannelMapper;
import io.github.pinpols.batch.console.domain.notification.mapper.SubscriptionRuleMapper;
import io.github.pinpols.batch.console.domain.notification.mapper.WorkflowTerminalNotificationMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

@DisplayName("可靠终态投递停机边界:停止领取后续行并保留恢复意图")
class TerminalNotificationDeliveryServiceTest {
  private final WorkflowTerminalNotificationMapper mapper =
      mock(WorkflowTerminalNotificationMapper.class);
  private final ConsoleWebhookSubscriptionMapper subscriptions =
      mock(ConsoleWebhookSubscriptionMapper.class);
  private final WebhookDispatcher dispatcher = mock(WebhookDispatcher.class);
  private final SubscriptionRuleMapper rules = mock(SubscriptionRuleMapper.class);
  private final NotificationChannelMapper channels = mock(NotificationChannelMapper.class);
  private final NotificationSenderRegistry senders = mock(NotificationSenderRegistry.class);
  private final TerminalNotificationDeliveryService service =
      new TerminalNotificationDeliveryService(
          mapper,
          subscriptions,
          rules,
          channels,
          dispatcher,
          senders,
          new WebhookRelayProperties(),
          mock(PlatformTransactionManager.class));

  @Test
  @DisplayName("已进入停机状态时不扫描或发送")
  void shouldStopBeforeLoadingCandidates() {
    service.poll(() -> true);
    verifyNoInteractions(mapper, subscriptions, dispatcher);
  }

  @Test
  @DisplayName("一行发送期间进入停机时完成当前行,不再认领下一行")
  void shouldLeaveRemainingRowsUnclaimedAfterStop() {
    var first = row(1L);
    var second = row(2L);
    AtomicBoolean stopping = new AtomicBoolean();
    when(mapper.pending(any(Instant.class), eq(50))).thenReturn(List.of(first, second));
    when(mapper.claim(org.mockito.ArgumentMatchers.argThat(parameters -> parameters != null && java.util.Objects.equals(parameters.row(), first) && parameters.token() != null && java.util.Objects.equals(parameters.maxAttempts(), 8)))).thenReturn(1);
    var subscription = new WebhookSubscriptionEntity();
    subscription.setEnabled(true);
    when(subscriptions.findByTenantAndId("t1", 10L)).thenReturn(Optional.of(subscription));
    when(dispatcher.attemptDelivery(eq(subscription), any(), anyString())).thenAnswer(call -> {
      stopping.set(true);
      return WebhookDeliveryResult.ok();
    });
    when(mapper.finish(org.mockito.ArgumentMatchers.argThat(parameters -> parameters != null && java.util.Objects.equals(parameters.row(), first) && parameters.token() != null && java.util.Objects.equals(parameters.status(), "SUCCESS") && parameters.error() == null && parameters.nextRetry() == null)))
        .thenReturn(1);
    service.poll(stopping::get);
    verify(mapper).finish(org.mockito.ArgumentMatchers.argThat(parameters -> parameters != null && java.util.Objects.equals(parameters.row(), first) && parameters.token() != null && java.util.Objects.equals(parameters.status(), "SUCCESS") && parameters.error() == null && parameters.nextRetry() == null));
    verify(mapper, never()).claim(org.mockito.ArgumentMatchers.argThat(parameters -> parameters != null && java.util.Objects.equals(parameters.row(), second) && parameters.token() != null));
    verify(dispatcher, times(1)).attemptDelivery(any(), any(), anyString());
  }

  @Test
  @DisplayName("非幂等渠道认领后中断,恢复只落结果不明审计且不重复发送")
  void shouldNotResendProviderAfterInterruptedClaim() {
    var row = providerRow(1);
    prepareProvider(row);
    service.poll();
    verifyNoInteractions(senders, dispatcher);
    verify(mapper)
        .finish(org.mockito.ArgumentMatchers.argThat(parameters -> parameters != null && java.util.Objects.equals(parameters.row(), row) && parameters.token() != null && java.util.Objects.equals(parameters.status(), "EXHAUSTED") && (parameters.error() != null && parameters.error().contains("outcome unknown")) && parameters.nextRetry() == null));
  }

  @Test
  @DisplayName("非幂等渠道首次发送成功会持久化成功,无需重复调度")
  void shouldSendFirstProviderAttemptAndPersistSuccess() {
    var row = providerRow(0);
    prepareProvider(row);
    NotificationSender sender = mock(NotificationSender.class);
    when(senders.resolve("EMAIL")).thenReturn(sender);
    when(sender.send(any())).thenReturn(WebhookDeliveryResult.ok());
    service.poll();
    verify(sender, times(1)).send(any());
    verify(mapper).finish(org.mockito.ArgumentMatchers.argThat(parameters -> parameters != null && java.util.Objects.equals(parameters.row(), row) && parameters.token() != null && java.util.Objects.equals(parameters.status(), "SUCCESS") && parameters.error() == null && parameters.nextRetry() == null));
  }

  private TerminalNotificationDelivery providerRow(int attempt) {
    return new TerminalNotificationDelivery(
        "RULE", 3L, "t1", 10L, "mail", "t1:workflow:10:terminal", row(3L).payloadJson(), attempt);
  }

  private void prepareProvider(TerminalNotificationDelivery row) {
    when(mapper.pending(any(Instant.class), eq(50))).thenReturn(List.of(row));
    when(mapper.claim(org.mockito.ArgumentMatchers.argThat(parameters -> parameters != null && java.util.Objects.equals(parameters.row(), row) && parameters.token() != null && java.util.Objects.equals(parameters.maxAttempts(), 8)))).thenReturn(1);
    when(rules.selectById("t1", 10L)).thenReturn(Map.of("enabled", true, "channel_code", "mail"));
    when(channels.selectByCode("t1", "mail"))
        .thenReturn(Map.of("enabled", true, "channel_type", "EMAIL", "config_json", "{}"));
  }

  private TerminalNotificationDelivery row(Long id) {
    return new TerminalNotificationDelivery(
        "WEBHOOK", id, "t1", 10L, null, "t1:workflow:10:terminal", """
        {"tenantId":"t1","eventType":"WORKFLOW_FAILED","stream":"workflow-runs",
         "cursor":"t1:workflow:10:terminal","emittedAt":"2026-10-20T01:00:00Z","data":{}}
        """, 0);
  }
}
