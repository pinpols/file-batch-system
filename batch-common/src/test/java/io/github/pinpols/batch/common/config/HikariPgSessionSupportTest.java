package io.github.pinpols.batch.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

@DisplayName("连接池会话初始化:会话超时语句拼装、与既有初始化语句的合并顺序,以及应用名截断规则")
class HikariPgSessionSupportTest {

  @Test
  @DisplayName("会话超时按毫秒拼装、内存参数按千字节换算,四条设置语句依次以分号拼接")
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
  @DisplayName("已有初始化语句时把会话设置语句前置并保留原语句,同时把应用名写入连接属性")
  void shouldPrependSessionSql_whenExistingInitSqlConfigured() {
    BatchPgSessionProperties props = new BatchPgSessionProperties();
    props.setMergeConnectionInitSql(true);
    BatchPgSessionProperties.PoolTimeouts t = props.getPlatform();
    t.setStatementTimeout(Duration.ZERO);

    HikariConfig cfg = new HikariConfig();
    cfg.setConnectionInitSql("SELECT 1");
    HikariPgSessionSupport.applyPlatform(cfg, props, "svc-platform");

    assertThat(cfg.getConnectionInitSql())
        .startsWith("SET statement_timeout TO 0; SET idle_in_transaction_session_timeout TO "
            + props.getPlatform().getIdleInTransactionTimeout().toMillis());
    assertThat(cfg.getConnectionInitSql()).endsWith("SELECT 1");
    assertThat(cfg.getDataSourceProperties()
            .getProperty(HikariPgSessionSupport.PG_APPLICATION_NAME_KEY))
        .isEqualTo("svc-platform");
  }

  @Test
  @DisplayName("应用名超过六十三字符时截断到六十三位,以匹配数据库标识长度上限")
  void shouldTruncateApplicationName_whenExceedingSixtyThreeChars() {
    assertThat(HikariPgSessionSupport.truncate("a".repeat(80), 63)).hasSize(63);
  }
}
