package io.github.pinpols.batch.console.domain.audit.infrastructure.ai;

import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.config.ConsoleAiProperties;
import io.github.pinpols.batch.console.domain.audit.mapper.ConsoleAiAuditLogMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 按显式开关清理 AI 审计，默认关闭，避免未完成合规评审时误删证据。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ConsoleAiAuditRetentionJob {

  private final ConsoleAiProperties properties;
  private final ConsoleAiAuditLogMapper mapper;
  private ScheduledExecutorService executor;

  @PostConstruct
  void start() {
    if (!properties.isAuditRetentionEnabled() || properties.getAuditRetentionDays() <= 0) {
      return;
    }
    executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
      Thread thread = new Thread(runnable, "console-ai-audit-retention");
      thread.setDaemon(true);
      return thread;
    });
    executor.scheduleWithFixedDelay(this::purge, 1, 24, TimeUnit.HOURS);
  }

  @PreDestroy
  void stop() {
    if (EmptyChecks.isNotNull(executor)) {
      executor.shutdownNow();
    }
  }

  private void purge() {
    try {
      OffsetDateTime cutoff = OffsetDateTime.ofInstant(
              BatchDateTimeSupport.utcNow(), ZoneOffset.UTC)
          .minus(Duration.ofDays(properties.getAuditRetentionDays()));
      int deleted = mapper.deleteBefore(cutoff);
      log.info("AI audit retention completed: cutoff={}, deleted={}", cutoff, deleted);
    } catch (RuntimeException exception) {
      log.warn("AI audit retention failed", exception);
    }
  }
}
