package com.example.batch.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.testing.AbstractIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.Test;
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
                    Instant.now(), "factory-auto-create", Duration.ofSeconds(30), Duration.ZERO))
            .orElseThrow();
    simpleLock.unlock();
  }
}
