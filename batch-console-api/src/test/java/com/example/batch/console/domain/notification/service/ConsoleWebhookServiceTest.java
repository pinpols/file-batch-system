package com.example.batch.console.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.exception.BizException;
import com.example.batch.console.domain.notification.entity.WebhookSubscriptionEntity;
import com.example.batch.console.domain.notification.mapper.ConsoleWebhookDeliveryLogMapper;
import com.example.batch.console.domain.notification.mapper.ConsoleWebhookSubscriptionMapper;
import com.example.batch.console.domain.notification.service.ConsoleWebhookService.CreateSubscriptionCommand;
import com.example.batch.console.domain.notification.service.ConsoleWebhookService.UpdateSubscriptionCommand;
import com.example.batch.console.domain.rbac.support.ConsoleTenantGuard;
import com.example.batch.console.support.CallbackUrlValidator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConsoleWebhookServiceTest {

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
    service =
        new ConsoleWebhookService(
            subscriptionRepository, deliveryLogRepository, tenantGuard, callbackUrlValidator);
    when(tenantGuard.resolveTenant("t1")).thenReturn("t1");
  }

  @Test
  void shouldListSubscriptions() {
    WebhookSubscriptionEntity entity = new WebhookSubscriptionEntity();
    entity.setName("hook-1");
    when(subscriptionRepository.findAllByTenant("t1")).thenReturn(List.of(entity));

    List<WebhookSubscriptionEntity> result = service.listSubscriptions("t1");

    assertThat(result).hasSize(1);
    assertThat(result.get(0).getName()).isEqualTo("hook-1");
  }

  @Test
  void shouldGetSubscription() {
    WebhookSubscriptionEntity entity = new WebhookSubscriptionEntity();
    entity.setId(1L);
    entity.setName("hook-1");
    when(subscriptionRepository.findByTenantAndId("t1", 1L)).thenReturn(Optional.of(entity));

    WebhookSubscriptionEntity result = service.getSubscription("t1", 1L);

    assertThat(result.getName()).isEqualTo("hook-1");
  }

  @Test
  void shouldThrowNotFoundWhenSubscriptionMissing() {
    when(subscriptionRepository.findByTenantAndId("t1", 99L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.getSubscription("t1", 99L))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("not_found");
  }

  @Test
  void shouldCreateSubscription() {
    when(subscriptionRepository.findByTenantAndName("t1", "hook-new")).thenReturn(Optional.empty());
    WebhookSubscriptionEntity created = new WebhookSubscriptionEntity();
    created.setId(1L);
    created.setName("hook-new");
    when(subscriptionRepository.findByTenantAndName("t1", "hook-new"))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(created));

    CreateSubscriptionCommand createCmd =
        CreateSubscriptionCommand.builder()
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
        .insert(
            "t1", "hook-new", "https://example.com/hook", "JOB_SUCCESS", "secret", true, "admin");
  }

  @Test
  void shouldThrowConflictWhenNameExists() {
    WebhookSubscriptionEntity existing = new WebhookSubscriptionEntity();
    existing.setName("hook-dup");
    when(subscriptionRepository.findByTenantAndName("t1", "hook-dup"))
        .thenReturn(Optional.of(existing));

    CreateSubscriptionCommand dupCmd =
        CreateSubscriptionCommand.builder()
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

    UpdateSubscriptionCommand updateCmd =
        UpdateSubscriptionCommand.builder()
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
        .update("t1", 1L, "https://new-url.com", "JOB_FAILED", "new-secret", false, "admin");
  }

  @Test
  void shouldThrowNotFoundWhenUpdatingMissing() {
    when(subscriptionRepository.findByTenantAndId("t1", 99L)).thenReturn(Optional.empty());

    UpdateSubscriptionCommand missingCmd =
        UpdateSubscriptionCommand.builder()
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
  void shouldDeleteSubscription() {
    service.deleteSubscription("t1", 1L);

    verify(subscriptionRepository).deleteByTenantAndId("t1", 1L);
  }

  @Test
  void shouldNormalizeEventTypes() {
    when(subscriptionRepository.findByTenantAndName("t1", "hook-norm"))
        .thenReturn(Optional.empty());
    WebhookSubscriptionEntity created = new WebhookSubscriptionEntity();
    created.setId(1L);
    created.setName("hook-norm");
    when(subscriptionRepository.findByTenantAndName("t1", "hook-norm"))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(created));

    CreateSubscriptionCommand normCmd =
        CreateSubscriptionCommand.builder()
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
        .insert(
            eq("t1"),
            eq("hook-norm"),
            eq("https://example.com/hook"),
            eq("JOB-SUCCESS,JOB_FAILED"),
            eq("secret"),
            eq(true),
            eq("admin"));
  }
}
