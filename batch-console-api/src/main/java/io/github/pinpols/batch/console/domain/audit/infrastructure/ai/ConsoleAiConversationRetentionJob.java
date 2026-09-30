package io.github.pinpols.batch.console.domain.audit.infrastructure.ai;

import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.console.domain.audit.mapper.ConsoleAiConversationMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    prefix = "batch.console.ai.persistence",
    name = "enabled",
    havingValue = "true")
public class ConsoleAiConversationRetentionJob {

  private final ConsoleAiConversationMapper mapper;
  private final ConsoleAiConversationService conversationService;
  private final ScheduledExecutorService executor =
      Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "console-ai-retention");
        thread.setDaemon(true);
        return thread;
      });

  @PostConstruct
  void start() {
    executor.scheduleWithFixedDelay(this::cleanExpired, 1, 1, TimeUnit.HOURS);
  }

  @PreDestroy
  void stop() {
    executor.shutdownNow();
  }

  private void cleanExpired() {
    try {
      for (String tenantId : mapper.selectActiveTenantIds()) {
        conversationService.deleteExpiredForTenant(tenantId);
      }
    } catch (RuntimeException exception) {
      SwallowedExceptionLogger.info(
          ConsoleAiConversationRetentionJob.class,
          "catch:ai-conversation-retention-failed",
          exception);
    }
  }
}
