package com.example.batch.console.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.exception.BizException;
import com.example.batch.console.domain.entity.WebhookSubscriptionEntity;
import com.example.batch.console.repository.ConsoleWebhookDeliveryLogRepository;
import com.example.batch.console.repository.ConsoleWebhookSubscriptionRepository;
import com.example.batch.console.support.CallbackUrlValidator;
import com.example.batch.console.support.ConsoleTenantGuard;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConsoleWebhookServiceTest {

  private ConsoleWebhookSubscriptionRepository subscriptionRepository;
  private ConsoleWebhookDeliveryLogRepository deliveryLogRepository;
  private ConsoleTenantGuard tenantGuard;
  private CallbackUrlValidator callbackUrlValidator;
  private ConsoleWebhookService service;

  @BeforeEach
  void setUp() {
    subscriptionRepository = mock(ConsoleWebhookSubscriptionRepository.class);
    deliveryLogRepository = mock(ConsoleWebhookDeliveryLogRepository.class);
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
        .hasMessageContaining("not found");
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

    WebhookSubscriptionEntity result =
        service.createSubscription(
            "t1", "hook-new", "https://example.com/hook", "JOB_SUCCESS", "secret", true, "admin");

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

    assertThatThrownBy(
            () ->
                service.createSubscription(
                    "t1",
                    "hook-dup",
                    "https://example.com/hook",
                    "JOB_SUCCESS",
                    "secret",
                    true,
                    "admin"))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("already exists");
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

    WebhookSubscriptionEntity result =
        service.updateSubscription(
            "t1", 1L, "https://new-url.com", "JOB_FAILED", "new-secret", false, "admin");

    assertThat(result).isNotNull();
    verify(subscriptionRepository)
        .update("t1", 1L, "https://new-url.com", "JOB_FAILED", "new-secret", false, "admin");
  }

  @Test
  void shouldThrowNotFoundWhenUpdatingMissing() {
    when(subscriptionRepository.findByTenantAndId("t1", 99L)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.updateSubscription(
                    "t1", 99L, "https://example.com", "JOB_SUCCESS", "s", true, "admin"))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("not found");
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

    service.createSubscription(
        "t1",
        "hook-norm",
        "https://example.com/hook",
        "job-success, JOB_FAILED",
        "secret",
        true,
        "admin");

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
