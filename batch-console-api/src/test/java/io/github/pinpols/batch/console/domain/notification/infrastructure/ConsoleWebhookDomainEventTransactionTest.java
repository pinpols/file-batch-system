package io.github.pinpols.batch.console.domain.notification.infrastructure;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import io.github.pinpols.batch.console.config.ConsoleAsyncConfiguration;
import io.github.pinpols.batch.console.domain.notification.service.SubscriptionRuleWebhookDispatcher;
import io.github.pinpols.batch.console.domain.notification.service.WebhookDispatcher;
import io.github.pinpols.batch.console.shared.event.ConsoleRealtimeDomainEvent;
import java.time.Instant;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class ConsoleWebhookDomainEventTransactionTest {

  @Test
  void shouldDispatchOnlyAfterCommitAndDiscardRolledBackEvent() {
    try (var context = new AnnotationConfigApplicationContext(TestConfiguration.class)) {
      WebhookDispatcher webhook = context.getBean(WebhookDispatcher.class);
      SubscriptionRuleWebhookDispatcher subscription =
          context.getBean(SubscriptionRuleWebhookDispatcher.class);
      TransactionTemplate transaction =
          new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
      ConsoleRealtimeDomainEvent event = event();

      transaction.executeWithoutResult(status -> {
        context.publishEvent(event);
        verifyNoInteractions(webhook, subscription);
      });

      verify(webhook)
          .dispatchAsync(
              "tenant-1", "JOB_COMPLETED", "job-instance", "cursor-1", null, event.emittedAt());
      verify(subscription)
          .dispatch(
              "tenant-1", "JOB_COMPLETED", "job-instance", "cursor-1", null, event.emittedAt());

      reset(webhook, subscription);
      transaction.executeWithoutResult(status -> {
        context.publishEvent(event);
        status.setRollbackOnly();
      });
      verifyNoInteractions(webhook, subscription);
    }
  }

  private static ConsoleRealtimeDomainEvent event() {
    return ConsoleRealtimeDomainEvent.builder().tenantId("tenant-1").stream("job-instance")
        .eventType("JOB_COMPLETED")
        .cursor("cursor-1")
        .emittedAt(Instant.parse("2026-09-20T00:00:00Z"))
        .build();
  }

  @Configuration(proxyBeanMethods = false)
  @EnableAsync
  @EnableTransactionManagement
  static class TestConfiguration {

    @Bean(name = ConsoleAsyncConfiguration.PUSH_TASK_EXECUTOR)
    Executor pushTaskExecutor() {
      return new SyncTaskExecutor();
    }

    @Bean
    PlatformTransactionManager transactionManager() {
      return new TestTransactionManager();
    }

    @Bean
    WebhookDispatcher webhookDispatcher() {
      return mock(WebhookDispatcher.class);
    }

    @Bean
    SubscriptionRuleWebhookDispatcher subscriptionRuleWebhookDispatcher() {
      return mock(SubscriptionRuleWebhookDispatcher.class);
    }

    @Bean
    ConsoleWebhookDomainEventListener consoleWebhookDomainEventListener(
        WebhookDispatcher webhookDispatcher,
        SubscriptionRuleWebhookDispatcher subscriptionRuleWebhookDispatcher) {
      return new ConsoleWebhookDomainEventListener(
          webhookDispatcher, subscriptionRuleWebhookDispatcher);
    }
  }

  @SuppressWarnings("serial")
  private static final class TestTransactionManager extends AbstractPlatformTransactionManager {

    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {}

    @Override
    protected void doCommit(DefaultTransactionStatus status) {}

    @Override
    protected void doRollback(DefaultTransactionStatus status) {}
  }
}
