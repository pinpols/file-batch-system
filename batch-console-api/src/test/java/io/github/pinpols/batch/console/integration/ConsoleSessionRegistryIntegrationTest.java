package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.config.BatchClockConfig;
import io.github.pinpols.batch.console.config.ConsoleSecurityProperties;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleSessionRegistry;
import io.github.pinpols.batch.console.infrastructure.rbac.RedisConsoleSessionStore;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 集成测试：验证 ConsoleSessionRegistry 的会话版本管理使用真实 Redis 容器执行。 */
@SpringBootTest(
    classes = ConsoleSessionRegistryIntegrationTest.TestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "batch.console.security.single-session-enabled=true",
      "batch.console.security.session-state-ttl=30d",
      "batch.startup-self-check.enabled=false"
    })
@DisplayName("控制台会话版本注册表: 版本自增,存储键过期时间与当前会话判定")
class ConsoleSessionRegistryIntegrationTest extends AbstractIntegrationTest {

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @EnableConfigurationProperties(ConsoleSecurityProperties.class)
  @Import({BatchClockConfig.class, ConsoleSessionRegistry.class, RedisConsoleSessionStore.class})
  static class TestApplication {}

  @Autowired
  private ConsoleSessionRegistry sessionRegistry;

  @Autowired
  private StringRedisTemplate redisTemplate;

  @Test
  @DisplayName("连续推进会话版本: 返回值从 1 开始单调递增")
  void shouldIncrementMonotonically_whenAdvancingSessionVersion() {
    String username = "user-" + System.nanoTime();
    String tenantId = "t-sess-" + System.nanoTime();

    long v1 = sessionRegistry.nextSessionVersion(username, tenantId);
    long v2 = sessionRegistry.nextSessionVersion(username, tenantId);
    long v3 = sessionRegistry.nextSessionVersion(username, tenantId);

    assertThat(v1).isEqualTo(1L);
    assertThat(v2).isEqualTo(2L);
    assertThat(v3).isEqualTo(3L);
  }

  @Test
  @DisplayName("推进会话版本: 存储键被设置正数过期时间")
  void shouldSetTtlOnStoredKey_whenAdvancingSessionVersion() {
    String username = "user-ttl-" + System.nanoTime();
    String tenantId = "t-ttl-" + System.nanoTime();

    sessionRegistry.nextSessionVersion(username, tenantId);

    String key =
        "batch:console:auth:session:" + tenantId.toLowerCase() + ":" + username.toLowerCase();
    assertThat(redisTemplate.getExpire(key)).isPositive();
  }

  @Test
  @DisplayName("会话判定: 最新版本有效,旧版本判定失效")
  void shouldAcceptCurrentVersionAndRejectStale_whenCheckingSession() {
    String username = "user-cur-" + System.nanoTime();
    String tenantId = "t-cur-" + System.nanoTime();

    long v1 = sessionRegistry.nextSessionVersion(username, tenantId);
    long v2 = sessionRegistry.nextSessionVersion(username, tenantId);

    assertThat(sessionRegistry.isCurrentSession(username, tenantId, v2)).isTrue();
    assertThat(sessionRegistry.isCurrentSession(username, tenantId, v1)).isFalse();
  }

  @Test
  @DisplayName("会话不存在: 任意版本号都判定为失效")
  void shouldRejectAnyVersion_whenNoSessionExists() {
    String username = "user-new-" + System.nanoTime();
    String tenantId = "t-new-" + System.nanoTime();

    assertThat(sessionRegistry.isCurrentSession(username, tenantId, 1L)).isFalse();
    assertThat(sessionRegistry.isCurrentSession(username, tenantId, 0L)).isFalse();
  }
}
