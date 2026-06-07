package com.example.batch.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class HikariPgSessionSupportTest {

  @Test
  void buildSessionInitSql_formatsMilliseconds() {
    BatchPgSessionProperties.PoolTimeouts t = new BatchPgSessionProperties.PoolTimeouts();
    t.setStatementTimeout(Duration.ofMinutes(1));
    t.setIdleInTransactionTimeout(Duration.ofSeconds(30));
    t.setWorkMem(DataSize.ofMegabytes(64));
    t.setMaintenanceWorkMem(DataSize.ofMegabytes(512));
    assertThat(HikariPgSessionSupport.buildSessionInitSql(t))
        .isEqualTo(
            "SET statement_timeout TO 60000; SET idle_in_transaction_session_timeout TO 30000;"
                + " SET work_mem TO '65536kB'; SET maintenance_work_mem TO '524288kB';");
  }

  @Test
  void mergePrependsSessionSqlBeforeExistingInitSql() {
    BatchPgSessionProperties props = new BatchPgSessionProperties();
    props.setMergeConnectionInitSql(true);
    BatchPgSessionProperties.PoolTimeouts t = props.getPlatform();
    t.setStatementTimeout(Duration.ZERO);

    HikariConfig cfg = new HikariConfig();
    cfg.setConnectionInitSql("SELECT 1");
    HikariPgSessionSupport.applyPlatform(cfg, props, "svc-platform");

    assertThat(cfg.getConnectionInitSql())
        .startsWith(
            "SET statement_timeout TO 0; SET idle_in_transaction_session_timeout TO "
                + props.getPlatform().getIdleInTransactionTimeout().toMillis());
    assertThat(cfg.getConnectionInitSql()).endsWith("SELECT 1");
    assertThat(cfg.getDataSourceProperties().getProperty("ApplicationName"))
        .isEqualTo("svc-platform");
  }

  @Test
  void truncateApplicationNameAtSixtyThreeChars() {
    assertThat(HikariPgSessionSupport.truncate("a".repeat(80), 63)).hasSize(63);
  }
}
