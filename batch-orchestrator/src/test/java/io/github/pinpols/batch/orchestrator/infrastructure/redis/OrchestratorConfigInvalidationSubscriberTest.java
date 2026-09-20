package io.github.pinpols.batch.orchestrator.infrastructure.redis;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.ConfigCacheInvalidationEvent;
import io.github.pinpols.batch.common.redis.BatchRedisKeys;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class OrchestratorConfigInvalidationSubscriberTest {

  @Mock
  private StringRedisTemplate redisTemplate;

  @Mock
  private OrchestratorConfigCacheService cacheService;

  @Mock
  private ValueOperations<String, String> valueOperations;

  private OrchestratorConfigInvalidationSubscriber subscriber;

  @BeforeEach
  void setUp() {
    subscriber = new OrchestratorConfigInvalidationSubscriber(
        redisTemplate, cacheService, (MeterRegistry) null);
  }

  @Test
  void appliesConfigInvalidationEventToLocalCache() {
    subscriber.onMessage(message(event(1, 1)), null);

    verify(cacheService).evictLocal("t1", "job-definition", "JOB1");
  }

  @Test
  void rejectsStaleKeyRevision() {
    subscriber.onMessage(message(event(2, 2)), null);
    subscriber.onMessage(message(event(3, 1)), null);

    verify(cacheService).evictLocal("t1", "job-definition", "JOB1");
  }

  @Test
  void wildcardEventClearsTypeLocalCache() {
    ConfigCacheInvalidationEvent event =
        new ConfigCacheInvalidationEvent("t1", "job-definition", "*", 4, 1, Instant.now());

    subscriber.onMessage(message(event), null);

    verify(cacheService).evictLocal("t1", "job-definition", "*");
  }

  @Test
  void eventRevisionGapClearsAllLocalCachesBeforeApplyingEvent() {
    subscriber.onMessage(message(event(1, 1)), null);
    subscriber.onMessage(message(event(3, 2)), null);

    verify(cacheService).evictAllLocal();
    verify(cacheService, times(2)).evictLocal("t1", "job-definition", "JOB1");
  }

  @Test
  void reconcileRevisionGapClearsAllLocalCaches() {
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.get(BatchRedisKeys.configInvalidationGlobalRevision())).thenReturn("10");

    subscriber.reconcileRevisionGap();

    verify(cacheService).evictAllLocal();
  }

  @Test
  void reconcileDoesNothingWhenRevisionAlreadyApplied() {
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.get(BatchRedisKeys.configInvalidationGlobalRevision())).thenReturn("10");

    subscriber.reconcileRevisionGap();
    subscriber.reconcileRevisionGap();

    verify(cacheService).evictAllLocal();
  }

  private static ConfigCacheInvalidationEvent event(long revision, long keyRevision) {
    return new ConfigCacheInvalidationEvent(
        "t1", "job-definition", "JOB1", revision, keyRevision, Instant.now());
  }

  private static Message message(ConfigCacheInvalidationEvent event) {
    Message message = mock(Message.class);
    when(message.getBody()).thenReturn(JsonUtils.toJson(event).getBytes(StandardCharsets.UTF_8));
    return message;
  }
}
