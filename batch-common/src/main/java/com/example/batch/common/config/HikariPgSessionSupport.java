package com.example.batch.common.config;

import com.example.batch.common.config.BatchPgSessionProperties.PoolTimeouts;
import com.example.batch.common.utils.Texts;
import com.zaxxer.hikari.HikariConfig;

/**
 * 将 {@link BatchPgSessionProperties} 应用到 Hikari 配置：PG {@code ApplicationName} 与 {@code
 * connectionInitSql}。
 */
public final class HikariPgSessionSupport {

  private static final int PG_APPLICATION_NAME_MAX_LEN = 63;

  private HikariPgSessionSupport() {}

  public static void applyPlatform(
      HikariConfig cfg, BatchPgSessionProperties props, String pgApplicationName) {
    apply(cfg, props, props.getPlatform(), pgApplicationName);
  }

  public static void applyBusiness(
      HikariConfig cfg, BatchPgSessionProperties props, String pgApplicationName) {
    apply(cfg, props, props.getBusiness(), pgApplicationName);
  }

  private static void apply(
      HikariConfig cfg,
      BatchPgSessionProperties props,
      PoolTimeouts timeouts,
      String pgApplicationName) {
    if (!props.isEnabled()) {
      return;
    }
    if (Texts.hasText(pgApplicationName)) {
      cfg.addDataSourceProperty(
          "ApplicationName", truncate(pgApplicationName, PG_APPLICATION_NAME_MAX_LEN));
    }
    String sessionSql = buildSessionInitSql(timeouts);
    if (!Texts.hasText(sessionSql)) {
      return;
    }
    if (props.isMergeConnectionInitSql() && Texts.hasText(cfg.getConnectionInitSql())) {
      cfg.setConnectionInitSql(sessionSql + " " + cfg.getConnectionInitSql().trim());
    } else {
      cfg.setConnectionInitSql(sessionSql);
    }
  }

  static String buildSessionInitSql(PoolTimeouts timeouts) {
    if (timeouts == null) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    if (timeouts.getStatementTimeout() != null) {
      sb.append("SET statement_timeout TO ")
          .append(timeouts.getStatementTimeout().toMillis())
          .append("; ");
    }
    if (timeouts.getIdleInTransactionTimeout() != null) {
      sb.append("SET idle_in_transaction_session_timeout TO ")
          .append(timeouts.getIdleInTransactionTimeout().toMillis())
          .append("; ");
    }
    if (timeouts.getWorkMem() != null && timeouts.getWorkMem().toBytes() > 0L) {
      sb.append("SET work_mem TO '")
          .append(toPgMemory(timeouts.getWorkMem().toBytes()))
          .append("'; ");
    }
    if (timeouts.getMaintenanceWorkMem() != null
        && timeouts.getMaintenanceWorkMem().toBytes() > 0L) {
      sb.append("SET maintenance_work_mem TO '")
          .append(toPgMemory(timeouts.getMaintenanceWorkMem().toBytes()))
          .append("'; ");
    }
    return sb.toString().trim();
  }

  private static String toPgMemory(long bytes) {
    long kib = Math.max(1L, bytes / 1024L);
    return kib + "kB";
  }

  static String truncate(String value, int maxLen) {
    if (value.length() <= maxLen) {
      return value;
    }
    return value.substring(0, maxLen);
  }
}
