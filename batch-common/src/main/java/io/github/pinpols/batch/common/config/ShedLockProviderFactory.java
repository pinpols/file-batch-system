package io.github.pinpols.batch.common.config;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.provider.redis.spring.RedisLockProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/** 创建 ShedLock 提供者(JDBC / Redis)的共享工具类。 */
public final class ShedLockProviderFactory {

  private static final String SHEDLOCK_TABLE = "batch.shedlock";
  private static final String CREATE_BATCH_SCHEMA_SQL = "CREATE SCHEMA IF NOT EXISTS batch";
  private static final String CREATE_SHEDLOCK_TABLE_SQL =
      """
      CREATE TABLE IF NOT EXISTS batch.shedlock (
          name        VARCHAR(64)  NOT NULL PRIMARY KEY,
          lock_until  TIMESTAMPTZ  NOT NULL,
          locked_at   TIMESTAMPTZ  NOT NULL,
          locked_by   VARCHAR(255) NOT NULL
      )
      """;

  private ShedLockProviderFactory() {}

  public static LockProvider jdbcTemplateLockProvider(DataSource dataSource) {
    return jdbcTemplateLockProvider(dataSource, false);
  }

  public static LockProvider jdbcTemplateLockProvider(
      DataSource dataSource, boolean autoCreateTable) {
    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
    if (autoCreateTable) {
      ensureShedLockTable(jdbcTemplate);
    }
    return new JdbcTemplateLockProvider(
        JdbcTemplateLockProvider.Configuration.builder()
            .withJdbcTemplate(jdbcTemplate)
            .withTableName(SHEDLOCK_TABLE)
            .usingDbTime()
            .build());
  }

  private static void ensureShedLockTable(JdbcTemplate jdbcTemplate) {
    try {
      jdbcTemplate.execute(CREATE_BATCH_SCHEMA_SQL);
      jdbcTemplate.execute(CREATE_SHEDLOCK_TABLE_SQL);
    } catch (DataAccessException ex) {
      throw new IllegalStateException(
          "Failed to auto-create batch.shedlock for ShedLock startup", ex);
    }
  }

  /**
   * Redis LockProvider:基于 Redis SETNX + TTL,适合多节点 HA 高并发场景。
   *
   * <p>对比 JDBC:
   *
   * <ul>
   *   <li>性能:SETNX 单 key 写比 PG row UPDATE 快 1 个量级(几 ms 内完成)
   *   <li>时钟:Redis 服务端 TTL,不依赖应用机器时钟(比 JDBC 的 usingDbTime 更稳)
   *   <li>HA:Redis 不可达时 ShedLock 抛异常,任务跳过本次(不会同时跑多份)
   *   <li>持久化:不需要(锁 TTL 短,Redis 重启丢锁等同于"锁过期")
   * </ul>
   *
   * <p>key 命名:{@code <prefix>:<env>:<lockName>}(防多服务 / 多环境共享 Redis 时撞 key)。
   *
   * @param connectionFactory Spring Data Redis 连接工厂
   * @param environment 环境名(prod / staging / dev),作为 key 第二层 prefix
   */
  public static LockProvider redisLockProvider(
      RedisConnectionFactory connectionFactory, String environment) {
    String keyPrefix =
        "shedlock:" + (environment == null || environment.isBlank() ? "default" : environment);
    return new RedisLockProvider(connectionFactory, keyPrefix);
  }
}
