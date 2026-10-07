package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.config.MeteredLockProvider;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 集成冒烟：确认 ShedLock 在 Flyway 与测试基础设施之后仍可正常装配。
 *
 * <p>2026-05-28 起 orchestrator 删除自家 RedisShedLockProvider,统一走 batch-common {@code
 * BatchShedLockAutoConfiguration} 的官方 {@code RedisLockProvider}(默认 redis)。
 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("分布式锁配置:锁表迁移结果,锁提供者装配类型,以及互斥获取与到期重入行为")
class ShedLockConfigurationIntegrationTest extends AbstractIntegrationTest {

  @Autowired
  JdbcTemplate jdbcTemplate;

  @Autowired
  LockProvider lockProvider;

  @Autowired
  DataSource dataSource;

  @Test
  @DisplayName("数据库迁移后锁表存在,且装配的锁提供者为 Redis 实现并注入数据源")
  void shouldCreateShedLockTableFromFlywayAndConfigureRedisLockProvider() {
    Integer tableCount = jdbcTemplate.queryForObject("""
            select count(*)
            from information_schema.tables
            where table_schema = 'batch'
              and table_name = 'shedlock'
            """, Integer.class);

    assertThat(tableCount).isEqualTo(1);
    assertThat(lockProvider)
        .isInstanceOfSatisfying(
            MeteredLockProvider.class,
            provider -> assertThat(provider.providerType()).isEqualTo("redis"));
    assertThat(dataSource).isNotNull();
  }

  @Test
  @DisplayName("同一锁名称被两个线程同时争抢时只有一个获取成功")
  void shouldEnforceMutualExclusion() throws Exception {
    String lockName = "it-lock-mutual-exclusion-" + System.nanoTime();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      AtomicInteger successCount = new AtomicInteger(0);
      CountDownLatch latch = new CountDownLatch(2);
      Runnable tryLock = () -> {
        try {
          Optional<SimpleLock> lock = lockProvider.lock(new LockConfiguration(
              BatchDateTimeSupport.utcNow(), lockName, Duration.ofSeconds(5), Duration.ZERO));
          if (lock.isPresent()) {
            successCount.incrementAndGet();
          }
        } finally {
          latch.countDown();
        }
      };
      pool.submit(tryLock);
      pool.submit(tryLock);
      assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
      assertThat(successCount.get()).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName("锁未主动释放时最大持有时长到期后可再次获取")
  void shouldAllowReacquireAfterExpiry() throws Exception {
    String lockName = "it-lock-reacquire-after-expiry";
    Optional<SimpleLock> first = lockProvider.lock(new LockConfiguration(
        BatchDateTimeSupport.utcNow(), lockName, Duration.ofMillis(800), Duration.ZERO));
    assertThat(first).isPresent();

    // 不主动解锁；依赖 lockAtMostFor 到期自动释放。
    Thread.sleep(1_200);

    Optional<SimpleLock> second = lockProvider.lock(new LockConfiguration(
        BatchDateTimeSupport.utcNow(), lockName, Duration.ofSeconds(2), Duration.ZERO));
    assertThat(second).isPresent();
    second.ifPresent(SimpleLock::unlock);
  }
}
