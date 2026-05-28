package com.example.batch.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.testing.AbstractIntegrationTest;
import java.time.Duration;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ShedLockProviderFactoryTest extends AbstractIntegrationTest {

  private static final String DB_USERNAME = "batch_user";
  private static final String DB_PASSWORD = "batch_pass_123";

  @Test
  void shouldAutoCreateShedLockTableWhenEnabled() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(platformJdbcUrl());
    dataSource.setUsername(DB_USERNAME);
    dataSource.setPassword(DB_PASSWORD);

    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
    jdbcTemplate.execute("drop table if exists batch.shedlock");

    LockProvider lockProvider = ShedLockProviderFactory.jdbcTemplateLockProvider(dataSource, true);

    Integer tableCount =
        jdbcTemplate.queryForObject(
            """
            select count(*)
              from information_schema.tables
             where table_schema = 'batch'
               and table_name = 'shedlock'
            """,
            Integer.class);
    assertThat(tableCount).isEqualTo(1);

    SimpleLock simpleLock =
        lockProvider
            .lock(
                new LockConfiguration(
                    BatchDateTimeSupport.utcNow(),
                    "factory-auto-create",
                    Duration.ofSeconds(30),
                    Duration.ZERO))
            .orElseThrow();
    simpleLock.unlock();
  }

  /**
   * Redis LockProvider 同款锁语义验证:抢到锁 → 重复抢同名应失败 → 释放后能再抢。 验 ShedLock switch 到 Redis
   * 后业务侧 @SchedulerLock 行为不变。
   */
  @Test
  void redisLockProviderShouldEnforceMutualExclusion() {
    LettuceConnectionFactory connectionFactory =
        new LettuceConnectionFactory(new RedisStandaloneConfiguration(redisHost(), redisPort()));
    connectionFactory.afterPropertiesSet();
    try {
      LockProvider provider = ShedLockProviderFactory.redisLockProvider(connectionFactory, "test");

      LockConfiguration cfg =
          new LockConfiguration(
              BatchDateTimeSupport.utcNow(),
              "factory-redis-mutex-" + System.nanoTime(),
              Duration.ofSeconds(30),
              Duration.ZERO);

      SimpleLock first = provider.lock(cfg).orElseThrow();
      try {
        // 第二次抢同名锁必须失败(SETNX 互斥)
        assertThat(provider.lock(cfg)).isEmpty();
      } finally {
        first.unlock();
      }
      // 释放后能再抢到
      SimpleLock again = provider.lock(cfg).orElseThrow();
      again.unlock();
    } finally {
      connectionFactory.destroy();
    }
  }
}
