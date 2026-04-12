package com.example.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.console.config.ConsoleSecurityProperties;
import com.example.batch.console.support.ConsoleSessionRegistry;
import com.example.batch.testing.AbstractIntegrationTest;
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
      "batch.console.security.session-state-ttl=30d"
    })
class ConsoleSessionRegistryIntegrationTest extends AbstractIntegrationTest {

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @EnableConfigurationProperties(ConsoleSecurityProperties.class)
  @Import(ConsoleSessionRegistry.class)
  static class TestApplication {}

  @Autowired private ConsoleSessionRegistry sessionRegistry;

  @Autowired private StringRedisTemplate redisTemplate;

  @Test
  void nextSessionVersionIncrementsMonotonically() {
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
  void nextSessionVersionSetsTtlOnRedisKey() {
    String username = "user-ttl-" + System.nanoTime();
    String tenantId = "t-ttl-" + System.nanoTime();

    sessionRegistry.nextSessionVersion(username, tenantId);

    String key =
        "batch:console:auth:session:" + tenantId.toLowerCase() + ":" + username.toLowerCase();
    assertThat(redisTemplate.getExpire(key)).isPositive();
  }

  @Test
  void isCurrentSessionReturnsTrueForCurrentVersionAndFalseForStale() {
    String username = "user-cur-" + System.nanoTime();
    String tenantId = "t-cur-" + System.nanoTime();

    long v1 = sessionRegistry.nextSessionVersion(username, tenantId);
    long v2 = sessionRegistry.nextSessionVersion(username, tenantId);

    assertThat(sessionRegistry.isCurrentSession(username, tenantId, v2)).isTrue();
    assertThat(sessionRegistry.isCurrentSession(username, tenantId, v1)).isFalse();
  }

  @Test
  void isCurrentSessionReturnsFalseWhenNoSessionExists() {
    String username = "user-new-" + System.nanoTime();
    String tenantId = "t-new-" + System.nanoTime();

    assertThat(sessionRegistry.isCurrentSession(username, tenantId, 1L)).isFalse();
    assertThat(sessionRegistry.isCurrentSession(username, tenantId, 0L)).isFalse();
  }
}
