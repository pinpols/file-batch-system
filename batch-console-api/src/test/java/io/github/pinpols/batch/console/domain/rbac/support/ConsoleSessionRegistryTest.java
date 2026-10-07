package io.github.pinpols.batch.console.domain.rbac.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.console.config.ConsoleSecurityProperties;
import io.github.pinpols.batch.console.infrastructure.rbac.RedisConsoleSessionStore;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@SuppressWarnings("unchecked")
@DisplayName("控制台会话注册表:会话版本自增与过期, 当前版本比对, 失效后旧令牌不可用")
class ConsoleSessionRegistryTest {

  private StringRedisTemplate redisTemplate;
  private ValueOperations<String, String> valueOperations;
  private ConsoleSessionRegistry registry;

  @BeforeEach
  void setUp() {
    redisTemplate = mock(StringRedisTemplate.class);
    valueOperations = mock(ValueOperations.class);
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    ConsoleSecurityProperties properties = new ConsoleSecurityProperties();
    properties.setSingleSessionEnabled(true);
    properties.setSessionStateTtl(Duration.ofDays(30));
    // R-4.7：meterRegistry 在单测里用空 provider；无 registry 时指标逻辑会跳过
    ObjectProvider<MeterRegistry> meterRegistryProvider = mock(ObjectProvider.class);
    when(meterRegistryProvider.getIfAvailable()).thenReturn(null);
    registry = new ConsoleSessionRegistry(
        new RedisConsoleSessionStore(redisTemplate), properties, meterRegistryProvider);
  }

  @Test
  @DisplayName("版本自增:返回自增后的版本并按配置设置过期时间")
  void shouldIncrementAndPersistSessionVersion() {
    when(valueOperations.increment("batch:console:auth:session:tenant-a:alice")).thenReturn(3L);

    long version = registry.nextSessionVersion("alice", "tenant-a");

    assertThat(version).isEqualTo(3L);
    verify(redisTemplate).expire("batch:console:auth:session:tenant-a:alice", Duration.ofDays(30));
  }

  @Test
  @DisplayName("版本比对:与当前版本一致为真, 不一致为假")
  void shouldMatchCurrentSessionVersion() {
    when(valueOperations.get("batch:console:auth:session:tenant-a:alice")).thenReturn("5");

    assertThat(registry.isCurrentSession("alice", "tenant-a", 5L)).isTrue();
    assertThat(registry.isCurrentSession("alice", "tenant-a", 4L)).isFalse();
  }

  @Test
  @DisplayName("会话失效:删除存储键使既有令牌失效")
  void invalidateSession_deletesRedisKey_makingExistingJwtInvalid() {
    registry.invalidateSession("alice", "tenant-a");

    verify(redisTemplate).delete("batch:console:auth:session:tenant-a:alice");
  }

  @Test
  @DisplayName("失效查询:删除后任何版本都不再是当前会话")
  void invalidateSession_afterDeletion_isCurrentSessionReturnsFalse() {
    when(valueOperations.get("batch:console:auth:session:tenant-a:alice")).thenReturn(null);

    assertThat(registry.isCurrentSession("alice", "tenant-a", 1L)).isFalse();
  }
}
