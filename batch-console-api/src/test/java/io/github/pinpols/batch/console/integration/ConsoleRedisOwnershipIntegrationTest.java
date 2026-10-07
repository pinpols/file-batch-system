package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.domain.rbac.infrastructure.ConsoleUserBatchProvisioningStore;
import io.github.pinpols.batch.console.support.web.RedisConsoleIdempotencyStore;
import io.github.pinpols.batch.testing.TestValkeyContainers;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 使用真实 Redis 兼容服务验证 Lua 的 owner 校验和预览 CAS，不启动整套应用。 */
@DisplayName("Redis 占位归属: 陈旧持有者不能改写,并发预览更新只有一个赢家")
class ConsoleRedisOwnershipIntegrationTest {
  @Test
  @DisplayName("占位归属校验: 过期持有者改写被拒,并发替换只有一个请求成功")
  void shouldRejectStaleOwnersAndLetOnlyOneConcurrentPreviewWin_whenReplacing() throws Exception {
    try (var redis = TestValkeyContainers.create()) {
      redis.start();
      var connectionFactory = new LettuceConnectionFactory(
          redis.getHost(), redis.getMappedPort(TestValkeyContainers.REDIS_PORT));
      connectionFactory.afterPropertiesSet();
      try {
        var template = new StringRedisTemplate(connectionFactory);
        var idempotency = new RedisConsoleIdempotencyStore(template);
        Duration ttl = Duration.ofMinutes(1);
        assertThat(idempotency.setIfAbsent("lease", "owner-a", ttl)).isTrue();
        assertThat(idempotency.compareAndSet("lease", "owner-a", "owner-a", ttl))
            .isTrue();
        template.delete("lease"); // 确定性模拟原占位过期，然后由新请求取得占位。
        assertThat(idempotency.setIfAbsent("lease", "owner-b", ttl)).isTrue();
        assertThat(idempotency.deleteIfValue("lease", "owner-a")).isFalse();
        assertThat(idempotency.compareAndSet("lease", "owner-a", "DONE", ttl)).isFalse();
        assertThat(idempotency.get("lease")).isEqualTo("owner-b");
        assertThat(idempotency.compareAndSet("lease", "owner-b", "DONE", ttl)).isTrue();

        var previews = new ConsoleUserBatchProvisioningStore(template);
        previews.savePreview("preview", "snapshot-1", ttl);
        var barrier = new CyclicBarrier(2);
        var pool = Executors.newFixedThreadPool(2);
        try {
          var first = pool.submit(() -> {
            barrier.await(3, TimeUnit.SECONDS);
            return previews.replacePreview("preview", "snapshot-1", "snapshot-a", ttl);
          });
          var second = pool.submit(() -> {
            barrier.await(3, TimeUnit.SECONDS);
            return previews.replacePreview("preview", "snapshot-1", "snapshot-b", ttl);
          });
          assertThat(List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)))
              .containsExactlyInAnyOrder(true, false);
          String winner = previews.loadPreview("preview");
          previews.deletePreview("preview", "snapshot-1");
          assertThat(previews.loadPreview("preview")).isEqualTo(winner);
          previews.deletePreview("preview", winner);
          assertThat(previews.loadPreview("preview")).isNull();
        } finally {
          pool.shutdownNow();
          assertThat(pool.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        }
      } finally {
        connectionFactory.destroy();
      }
    }
  }
}
