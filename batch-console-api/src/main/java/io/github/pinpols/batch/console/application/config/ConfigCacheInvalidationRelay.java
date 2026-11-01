package io.github.pinpols.batch.console.application.config;

import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.console.mapper.ConfigCacheInvalidationOutboxMapper;
import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 重放已提交的失效意图；Redis 调用不持有数据库事务。 */
@Slf4j
@Service
public class ConfigCacheInvalidationRelay {
  private static final int MAX_PER_POLL = 100;
  private final ConfigCacheInvalidationOutboxMapper outbox;
  private final ConsoleConfigCacheInvalidationService invalidation;
  private final TransactionTemplate transactions;
  private final AtomicBoolean stopping = new AtomicBoolean();
  private ScheduledExecutorService scheduler;

  public ConfigCacheInvalidationRelay(
      ConfigCacheInvalidationOutboxMapper outbox,
      ConsoleConfigCacheInvalidationService invalidation,
      @Qualifier("transactionManager") PlatformTransactionManager transactionManager) {
    this.outbox = outbox;
    this.invalidation = invalidation;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  @EventListener(ApplicationReadyEvent.class)
  public synchronized void start() {
    if (scheduler != null || stopping.get()) {
      return;
    }
    scheduler = Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().name("config-cache-invalidation-relay").daemon(true).factory());
    scheduler.scheduleWithFixedDelay(this::pollSafely, 1, 5, TimeUnit.SECONDS);
  }

  private void pollSafely() {
    try {
      poll();
    } catch (RuntimeException failure) {
      log.warn("配置缓存失效队列轮询失败: {}", SwallowedExceptionLogger.summary(failure));
      log.debug("配置缓存失效队列轮询失败详情", failure);
    }
  }

  public void poll() {
    for (int i = 0; i < MAX_PER_POLL && !stopping.get(); i++) {
      String token = UUID.randomUUID().toString();
      ConfigCacheInvalidationIntent intent = transactions.execute(status -> {
        ConfigCacheInvalidationIntent next = outbox.lockNext();
        if (next != null) {
          outbox.claim(next.id(), token);
        }
        return next;
      });
      if (intent == null) {
        return;
      }
      try {
        invalidation.deliver(intent);
        transactions.executeWithoutResult(status -> outbox.acknowledge(intent.id(), token));
      } catch (RuntimeException failure) {
        transactions.executeWithoutResult(status -> outbox.retry(intent.id(), token));
        log.warn("配置缓存失效投递失败: id={}, reason={}",
            intent.id(), SwallowedExceptionLogger.summary(failure));
        log.debug("配置缓存失效投递失败详情: id={}", intent.id(), failure);
      }
    }
  }

  @EventListener(ContextClosedEvent.class)
  public void stopOnContextClosed() {
    stop();
  }

  @PreDestroy
  public synchronized void stop() {
    stopping.set(true);
    if (scheduler != null) {
      scheduler.shutdown();
      try {
        if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
          log.warn("配置缓存失效投递线程未在停机等待时间内退出，未确认记录将由租约重试");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
