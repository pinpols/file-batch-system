package com.example.batch.worker.imports.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.testing.AbstractIntegrationTest;
import com.example.batch.worker.imports.config.PlatformDataSourceConfiguration;
import com.example.batch.worker.imports.config.ShedLockConfiguration;
import java.time.Duration;
import java.time.Instant;
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
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** 集成冒烟测试：确保 ShedLock 配置在 Flyway 和测试初始化脚本之后正常工作。 */
@SpringBootTest(
    classes = {PlatformDataSourceConfiguration.class, ShedLockConfiguration.class},
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "batch.shedlock.auto-create=true")
class ShedLockConfigurationIntegrationTest extends AbstractIntegrationTest {

  @Autowired DataSource dataSource;

  @Autowired LockProvider lockProvider;

  @Test
  void shouldCreateShedLockTableAndConfigureJdbcTemplateLockProvider() {
    Integer tableCount =
        new JdbcTemplate(dataSource)
            .queryForObject(
                """
                select count(*)
                from information_schema.tables
                where table_schema = 'batch'
                  and table_name = 'shedlock'
                """,
                Integer.class);

    assertThat(tableCount).isEqualTo(1);
    assertThat(lockProvider).isInstanceOf(JdbcTemplateLockProvider.class);
    assertThat(dataSource).isNotNull();
  }

  @Test
  void shouldEnforceMutualExclusion() throws Exception {
    String lockName = "it-lock-mutual-exclusion";
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      AtomicInteger successCount = new AtomicInteger(0);
      CountDownLatch latch = new CountDownLatch(2);
      Runnable tryLock =
          () -> {
            try {
              Optional<SimpleLock> lock =
                  lockProvider.lock(
                      new LockConfiguration(
                          Instant.now(), lockName, Duration.ofSeconds(5), Duration.ZERO));
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
  void shouldAllowReacquireAfterExpiry() throws Exception {
    String lockName = "it-lock-reacquire-after-expiry";
    Optional<SimpleLock> first =
        lockProvider.lock(
            new LockConfiguration(Instant.now(), lockName, Duration.ofMillis(800), Duration.ZERO));
    assertThat(first).isPresent();

    Thread.sleep(1_200);

    Optional<SimpleLock> second =
        lockProvider.lock(
            new LockConfiguration(Instant.now(), lockName, Duration.ofSeconds(2), Duration.ZERO));
    assertThat(second).isPresent();
    second.ifPresent(SimpleLock::unlock);
  }
}
