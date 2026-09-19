package io.github.pinpols.batch.orchestrator.infrastructure.redis;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.pinpols.batch.common.config.ConfigCacheInvalidationEvent;
import io.github.pinpols.batch.common.redis.BatchRedisKeys;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.ErrorHandler;

/** 订阅 Console 配置变更事件，及时清理 Orchestrator 进程内配置缓存。 */
@Slf4j
@Service
public class OrchestratorConfigInvalidationSubscriber implements MessageListener {

  static final String CHANNEL = "batch:config:invalidation";
  private static final int REVISION_TRACKER_MAX_SIZE = 100_000;

  private final StringRedisTemplate redisTemplate;
  private final OrchestratorConfigCacheService cacheService;
  private final AtomicBoolean running = new AtomicBoolean(false);
  private final AtomicLong appliedRevision = new AtomicLong(0);
  private final Cache<String, Long> keyRevisions = Caffeine.newBuilder()
      .expireAfterAccess(Duration.ofHours(12))
      .maximumSize(REVISION_TRACKER_MAX_SIZE)
      .build();

  private final Counter receivedCounter;
  private final Counter staleCounter;
  private final Counter failureCounter;
  private final Counter reconcileCounter;
  private final Timer eventLagTimer;

  private RedisMessageListenerContainer listenerContainer;

  @Autowired
  public OrchestratorConfigInvalidationSubscriber(
      StringRedisTemplate redisTemplate,
      OrchestratorConfigCacheService cacheService,
      ObjectProvider<MeterRegistry> meterRegistryProvider) {
    this(redisTemplate, cacheService, meterRegistryProvider.getIfAvailable());
  }

  OrchestratorConfigInvalidationSubscriber(
      StringRedisTemplate redisTemplate,
      OrchestratorConfigCacheService cacheService,
      MeterRegistry meterRegistry) {
    this.redisTemplate = redisTemplate;
    this.cacheService = cacheService;
    if (meterRegistry == null) {
      this.receivedCounter = null;
      this.staleCounter = null;
      this.failureCounter = null;
      this.reconcileCounter = null;
      this.eventLagTimer = null;
      return;
    }
    meterRegistry.gauge("batch.orchestrator.config.invalidation.applied_revision", appliedRevision);
    meterRegistry.gauge(
        "batch.orchestrator.config.invalidation.tracked_keys", keyRevisions, Cache::estimatedSize);
    this.receivedCounter = meterRegistry.counter(
        "batch.orchestrator.config.invalidation.event.total", "result", "applied");
    this.staleCounter = meterRegistry.counter(
        "batch.orchestrator.config.invalidation.event.total", "result", "stale");
    this.failureCounter = meterRegistry.counter(
        "batch.orchestrator.config.invalidation.event.total", "result", "failure");
    this.reconcileCounter =
        meterRegistry.counter("batch.orchestrator.config.invalidation.reconcile.total");
    this.eventLagTimer = Timer.builder("batch.orchestrator.config.invalidation.event_lag")
        .publishPercentileHistogram()
        .register(meterRegistry);
  }

  @PostConstruct
  void start() {
    if (!running.compareAndSet(false, true)) {
      return;
    }
    RedisConnectionFactory connectionFactory = redisTemplate.getConnectionFactory();
    if (connectionFactory == null) {
      throw new IllegalStateException(
          "redis connection factory is required for config invalidation subscriber");
    }
    listenerContainer = new RedisMessageListenerContainer();
    listenerContainer.setConnectionFactory(connectionFactory);
    listenerContainer.setErrorHandler(logErrorHandler());
    listenerContainer.addMessageListener(this, new ChannelTopic(CHANNEL));
    listenerContainer.afterPropertiesSet();
    listenerContainer.start();
    log.info("orchestrator config invalidation subscriber started: channel={}", CHANNEL);
  }

  @Override
  public void onMessage(Message message, byte[] pattern) {
    if (message == null || message.getBody() == null || message.getBody().length == 0) {
      return;
    }
    ConfigCacheInvalidationEvent event;
    try {
      String payload = new String(message.getBody(), StandardCharsets.UTF_8);
      event = JsonUtils.fromJsonStrict(payload, ConfigCacheInvalidationEvent.class);
      apply(event);
    } catch (RuntimeException exception) {
      increment(failureCounter);
      log.warn("config invalidation event apply failed: reason={}", exception.getMessage());
      log.debug("config invalidation event apply failure", exception);
    }
  }

  @Scheduled(
      fixedDelayString = "${batch.orchestrator.config-cache.reconcile-interval-millis:10000}")
  public void reconcileRevisionGap() {
    String rawRevision;
    try {
      rawRevision =
          redisTemplate.opsForValue().get(BatchRedisKeys.configInvalidationGlobalRevision());
    } catch (RuntimeException exception) {
      log.debug(
          "config invalidation revision reconcile skipped: reason={}", exception.getMessage());
      return;
    }
    if (rawRevision == null || rawRevision.isBlank()) {
      return;
    }
    long publishedRevision;
    try {
      publishedRevision = Long.parseLong(rawRevision);
    } catch (NumberFormatException exception) {
      log.warn("config invalidation global revision is invalid: value={}", rawRevision);
      return;
    }
    long current = appliedRevision.get();
    if (publishedRevision <= current) {
      return;
    }
    cacheService.evictAllLocal();
    appliedRevision.accumulateAndGet(publishedRevision, Math::max);
    increment(reconcileCounter);
    log.info(
        "config invalidation revision gap reconciled: previousRevision={}, publishedRevision={}",
        current,
        publishedRevision);
  }

  @EventListener(ContextClosedEvent.class)
  void stopOnContextClosed(ContextClosedEvent event) {
    stopContainer("context-closed");
  }

  @PreDestroy
  void shutdown() {
    stopContainer("pre-destroy");
  }

  private void apply(ConfigCacheInvalidationEvent event) {
    if (event == null || event.revision() <= 0 || event.keyRevision() <= 0) {
      increment(failureCounter);
      return;
    }
    recordLag(event.changedAt());
    String key = eventKey(event);
    Long previous = keyRevisions.getIfPresent(key);
    if (previous != null && event.keyRevision() <= previous) {
      increment(staleCounter);
      return;
    }
    keyRevisions.put(key, event.keyRevision());
    cacheService.evictLocal(event.tenantId(), event.type(), event.code());
    appliedRevision.accumulateAndGet(event.revision(), Math::max);
    increment(receivedCounter);
  }

  private String eventKey(ConfigCacheInvalidationEvent event) {
    return "%s:%s:%s".formatted(event.tenantId(), event.type(), event.code());
  }

  private void recordLag(Instant changedAt) {
    if (eventLagTimer == null || changedAt == null) {
      return;
    }
    long lagMillis = Math.max(0, Duration.between(changedAt, Instant.now()).toMillis());
    eventLagTimer.record(lagMillis, TimeUnit.MILLISECONDS);
  }

  private void stopContainer(String source) {
    if (!running.compareAndSet(true, false)) {
      return;
    }
    if (listenerContainer == null) {
      return;
    }
    log.info("orchestrator config invalidation subscriber stopping: source={}", source);
    try {
      listenerContainer.stop();
    } catch (RuntimeException exception) {
      log.debug(
          "orchestrator config invalidation subscriber stop skipped: reason={}",
          exception.getMessage(),
          exception);
    }
    try {
      listenerContainer.destroy();
    } catch (Exception exception) {
      log.debug(
          "orchestrator config invalidation subscriber destroy skipped: reason={}",
          exception.getMessage(),
          exception);
    }
  }

  private ErrorHandler logErrorHandler() {
    return throwable -> {
      increment(failureCounter);
      log.warn(
          "orchestrator config invalidation subscriber error: reason={}",
          throwable.getMessage(),
          throwable);
    };
  }

  private void increment(Counter counter) {
    if (counter != null) {
      counter.increment();
    }
  }
}
