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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
@DisplayName("配置缓存失效订阅事件消费与修订号缺口对账的验证")
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
  @DisplayName("收到配置失效事件后,本地缓存中对应条目被立即清除")
  void shouldEvictLocalCache_whenInvalidationEventReceived() {
    subscriber.onMessage(message(event(1, 1)), null);

    verify(cacheService).evictLocal("t1", "job-definition", "JOB1");
  }

  @Test
  @DisplayName("同一配置键收到修订号更小的事件时忽略,已应用的修订号不被回退")
  void shouldIgnoreEvent_whenKeyRevisionOlderThanApplied() {
    subscriber.onMessage(message(event(2, 2)), null);
    subscriber.onMessage(message(event(3, 1)), null);

    verify(cacheService).evictLocal("t1", "job-definition", "JOB1");
  }

  @Test
  @DisplayName("事件配置键为通配符时,清除该类型下全部本地缓存条目")
  void shouldEvictWholeTypeCache_whenWildcardEventReceived() {
    ConfigCacheInvalidationEvent event =
        new ConfigCacheInvalidationEvent("t1", "job-definition", "*", 4, 1, Instant.now());

    subscriber.onMessage(message(event), null);

    verify(cacheService).evictLocal("t1", "job-definition", "*");
  }

  @Test
  @DisplayName("事件修订号出现缺口时先清空全部本地缓存,再应用新事件")
  void shouldClearAllLocalCaches_whenEventRevisionGapDetected() {
    subscriber.onMessage(message(event(1, 1)), null);
    subscriber.onMessage(message(event(3, 2)), null);

    verify(cacheService).evictAllLocal();
    verify(cacheService, times(2)).evictLocal("t1", "job-definition", "JOB1");
  }

  @Test
  @DisplayName("对账发现全局修订号落后时清空全部本地缓存")
  void shouldClearAllLocalCaches_whenReconcileFindsRevisionGap() {
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.get(BatchRedisKeys.configInvalidationGlobalRevision())).thenReturn("10");

    subscriber.reconcileRevisionGap();

    verify(cacheService).evictAllLocal();
  }

  @Test
  @DisplayName("全局修订号已应用时重复对账不再触发本地缓存清空")
  void shouldNotClearLocalCaches_whenRevisionAlreadyApplied() {
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
