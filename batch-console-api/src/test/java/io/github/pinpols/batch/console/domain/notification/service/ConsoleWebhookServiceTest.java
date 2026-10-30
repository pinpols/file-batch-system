package io.github.pinpols.batch.console.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.domain.notification.entity.WebhookSubscriptionEntity;
import io.github.pinpols.batch.console.domain.notification.mapper.ConsoleWebhookDeliveryLogMapper;
import io.github.pinpols.batch.console.domain.notification.mapper.ConsoleWebhookSubscriptionMapper;
import io.github.pinpols.batch.console.domain.notification.param.ConsoleWebhookSubscriptionInsertParam;
import io.github.pinpols.batch.console.domain.notification.param.ConsoleWebhookSubscriptionUpdateParam;
import io.github.pinpols.batch.console.domain.notification.service.ConsoleWebhookService.CreateSubscriptionCommand;
import io.github.pinpols.batch.console.domain.notification.service.ConsoleWebhookService.UpdateSubscriptionCommand;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.support.CallbackUrlValidator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;

@DisplayName("订阅管理服务: 增删改查, 名称冲突与事件类型规范化")
class ConsoleWebhookServiceTest {

  @DisplayName("创建 Webhook 前拒绝仅用于告警的事件类型")
  @ParameterizedTest
  @ValueSource(strings = {"JOB_SLA_BREACH", " job_sla_violation ", "JOB_SLA_VIOLATION_ESCALATED"})
  void shouldRejectAlertOnlyTypesBeforeCreatingWebhook(String eventType) {
    CreateSubscriptionCommand command = CreateSubscriptionCommand.builder()
        .tenantId("t1")
        .name("sla")
        .callbackUrl("https://example.com/hook")
        .eventTypes("JOB_FAILED," + eventType)
        .enabled(true)
        .build();
    assertThatThrownBy(() -> service.createSubscription(command))
        .isInstanceOfSatisfying(
            BizException.class,
            ex -> assertThat(ex.getMessageKey()).isEqualTo("error.notification.sla_alert_channel"));
    verify(subscriptionRepository, never())
        .insert(ArgumentMatchers.any(ConsoleWebhookSubscriptionInsertParam.class));
  }

  @DisplayName("更新 Webhook 前拒绝仅用于告警的事件类型")
  @ParameterizedTest
  @ValueSource(strings = {"JOB_SLA_BREACH", " job_sla_violation ", "JOB_SLA_VIOLATION_ESCALATED"})
  void shouldRejectAlertOnlyTypesBeforeUpdatingWebhook(String eventType) {
    when(subscriptionRepository.findByTenantAndId("t1", 1L))
        .thenReturn(Optional.of(new WebhookSubscriptionEntity()));
    UpdateSubscriptionCommand command = UpdateSubscriptionCommand.builder()
        .tenantId("t1")
        .id(1L)
        .callbackUrl("https://example.com/hook")
        .eventTypes(eventType)
        .enabled(true)
        .build();
    assertThatThrownBy(() -> service.updateSubscription(command))
        .isInstanceOfSatisfying(
            BizException.class,
            ex -> assertThat(ex.getMessageKey()).isEqualTo("error.notification.sla_alert_channel"));
    verify(subscriptionRepository, never())
        .update(ArgumentMatchers.any(ConsoleWebhookSubscriptionUpdateParam.class));
  }

  private ConsoleWebhookSubscriptionMapper subscriptionRepository;
  private ConsoleWebhookDeliveryLogMapper deliveryLogRepository;
  private ConsoleTenantGuard tenantGuard;
  private CallbackUrlValidator callbackUrlValidator;
  private ConsoleWebhookService service;

  @BeforeEach
  void setUp() {
    subscriptionRepository = mock(ConsoleWebhookSubscriptionMapper.class);
    deliveryLogRepository = mock(ConsoleWebhookDeliveryLogMapper.class);
    tenantGuard = mock(ConsoleTenantGuard.class);
    callbackUrlValidator = mock(CallbackUrlValidator.class);
    service = new ConsoleWebhookService(
        subscriptionRepository, deliveryLogRepository, tenantGuard, callbackUrlValidator);
    when(tenantGuard.resolveTenant("t1")).thenReturn("t1");
  }

  @Test
  @DisplayName("投递历史查询在授权租户下绑定冷热记录的读取上下文")
  void shouldBindTenantForHistory_whenDeliveryLogsAreRequested() {
    when(deliveryLogRepository.findRecentByTenant("t1", 20)).thenReturn(List.of());
    var rows = service.deliveryLogs("t1", null, 20);
    assertThat(rows).isEmpty();
    verify(deliveryLogRepository).bindTenant("t1");
    verify(deliveryLogRepository).findRecentByTenant("t1", 20);
  }

  @Test
  @DisplayName("按租户列出全部订阅, 条目与名称原样透出")
  void shouldListSubscriptions() {
    WebhookSubscriptionEntity entity = new WebhookSubscriptionEntity();
    entity.setName("hook-1");
    when(subscriptionRepository.findAllByTenant("t1")).thenReturn(List.of(entity));

    List<WebhookSubscriptionEntity> result = service.listSubscriptions("t1");

    assertThat(result).hasSize(1);
    assertThat(result.get(0).getName()).isEqualTo("hook-1");
  }

  @Test
  @DisplayName("按租户与主键查询订阅, 命中时返回该行")
  void shouldGetSubscription() {
    WebhookSubscriptionEntity entity = new WebhookSubscriptionEntity();
    entity.setId(1L);
    entity.setName("hook-1");
    when(subscriptionRepository.findByTenantAndId("t1", 1L)).thenReturn(Optional.of(entity));

    WebhookSubscriptionEntity result = service.getSubscription("t1", 1L);

    assertThat(result.getName()).isEqualTo("hook-1");
  }

  @Test
  @DisplayName("订阅不存在时抛出业务异常, 提示未找到")
  void shouldThrowNotFoundWhenSubscriptionMissing() {
    when(subscriptionRepository.findByTenantAndId("t1", 99L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.getSubscription("t1", 99L))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("not_found");
  }

  @Test
  @DisplayName("名称未被占用时创建订阅, 入库字段与请求逐项一致")
  void shouldCreateSubscription() {
    when(subscriptionRepository.findByTenantAndName("t1", "hook-new")).thenReturn(Optional.empty());
    WebhookSubscriptionEntity created = new WebhookSubscriptionEntity();
    created.setId(1L);
    created.setName("hook-new");
    when(subscriptionRepository.findByTenantAndName("t1", "hook-new"))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(created));

    CreateSubscriptionCommand createCmd = CreateSubscriptionCommand.builder()
        .tenantId("t1")
        .name("hook-new")
        .callbackUrl("https://example.com/hook")
        .eventTypes("JOB_SUCCESS")
        .secret("secret")
        .enabled(true)
        .operator("admin")
        .build();
    WebhookSubscriptionEntity result = service.createSubscription(createCmd);

    assertThat(result.getName()).isEqualTo("hook-new");
    verify(subscriptionRepository)
        .insert(ConsoleWebhookSubscriptionInsertParam.builder()
            .tenantId("t1")
            .name("hook-new")
            .callbackUrl("https://example.com/hook")
            .eventTypes("JOB_SUCCESS")
            .secret("secret")
            .enabled(true)
            .operator("admin")
            .build());
  }

  @Test
  @DisplayName("同名订阅已存在时抛出业务异常, 提示重复")
  void shouldThrowConflictWhenNameExists() {
    WebhookSubscriptionEntity existing = new WebhookSubscriptionEntity();
    existing.setName("hook-dup");
    when(subscriptionRepository.findByTenantAndName("t1", "hook-dup"))
        .thenReturn(Optional.of(existing));

    CreateSubscriptionCommand dupCmd = CreateSubscriptionCommand.builder()
        .tenantId("t1")
        .name("hook-dup")
        .callbackUrl("https://example.com/hook")
        .eventTypes("JOB_SUCCESS")
        .secret("secret")
        .enabled(true)
        .operator("admin")
        .build();
    assertThatThrownBy(() -> service.createSubscription(dupCmd))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("subscription_exists");
  }

  @Test
  @DisplayName("订阅存在时更新回调地址与事件类型, 返回更新后结果")
  void shouldUpdateSubscription() {
    WebhookSubscriptionEntity existing = new WebhookSubscriptionEntity();
    existing.setId(1L);
    existing.setName("hook-1");
    when(subscriptionRepository.findByTenantAndId("t1", 1L)).thenReturn(Optional.of(existing));

    WebhookSubscriptionEntity updated = new WebhookSubscriptionEntity();
    updated.setId(1L);
    updated.setName("hook-1");
    updated.setCallbackUrl("https://new-url.com");
    when(subscriptionRepository.findByTenantAndId("t1", 1L))
        .thenReturn(Optional.of(existing))
        .thenReturn(Optional.of(updated));

    UpdateSubscriptionCommand updateCmd = UpdateSubscriptionCommand.builder()
        .tenantId("t1")
        .id(1L)
        .callbackUrl("https://new-url.com")
        .eventTypes("JOB_FAILED")
        .secret("new-secret")
        .enabled(false)
        .operator("admin")
        .build();
    WebhookSubscriptionEntity result = service.updateSubscription(updateCmd);

    assertThat(result).isNotNull();
    verify(subscriptionRepository)
        .update(ConsoleWebhookSubscriptionUpdateParam.builder()
            .tenantId("t1")
            .id(1L)
            .callbackUrl("https://new-url.com")
            .eventTypes("JOB_FAILED")
            .secret("new-secret")
            .enabled(false)
            .operator("admin")
            .build());
  }

  @Test
  @DisplayName("更新不存在的订阅时抛出业务异常, 提示未找到")
  void shouldThrowNotFoundWhenUpdatingMissing() {
    when(subscriptionRepository.findByTenantAndId("t1", 99L)).thenReturn(Optional.empty());

    UpdateSubscriptionCommand missingCmd = UpdateSubscriptionCommand.builder()
        .tenantId("t1")
        .id(99L)
        .callbackUrl("https://example.com")
        .eventTypes("JOB_SUCCESS")
        .secret("s")
        .enabled(true)
        .operator("admin")
        .build();
    assertThatThrownBy(() -> service.updateSubscription(missingCmd))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("not_found");
  }

  @Test
  @DisplayName("按租户与主键删除订阅, 删除条件与入参一致")
  void shouldDeleteSubscription() {
    service.deleteSubscription("t1", 1L);

    verify(subscriptionRepository).deleteByTenantAndId("t1", 1L);
  }

  @Test
  @DisplayName("事件类型统一转为大写, 并以半角逗号连接后入库")
  void shouldNormalizeEventTypes() {
    when(subscriptionRepository.findByTenantAndName("t1", "hook-norm"))
        .thenReturn(Optional.empty());
    WebhookSubscriptionEntity created = new WebhookSubscriptionEntity();
    created.setId(1L);
    created.setName("hook-norm");
    when(subscriptionRepository.findByTenantAndName("t1", "hook-norm"))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(created));

    CreateSubscriptionCommand normCmd = CreateSubscriptionCommand.builder()
        .tenantId("t1")
        .name("hook-norm")
        .callbackUrl("https://example.com/hook")
        .eventTypes("job-success, JOB_FAILED")
        .secret("secret")
        .enabled(true)
        .operator("admin")
        .build();
    service.createSubscription(normCmd);

    verify(subscriptionRepository)
        .insert(ConsoleWebhookSubscriptionInsertParam.builder()
            .tenantId("t1")
            .name("hook-norm")
            .callbackUrl("https://example.com/hook")
            .eventTypes("JOB-SUCCESS,JOB_FAILED")
            .secret("secret")
            .enabled(true)
            .operator("admin")
            .build());
  }
}
