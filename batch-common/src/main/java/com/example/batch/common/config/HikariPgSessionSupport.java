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

  /** 未显式配置时的 keepalive 默认值(毫秒):主备切换后主动探活、剔除指向旧主的死连接。 */
  static final long DEFAULT_KEEPALIVE_MS = 30_000L;

  private static void apply(
      HikariConfig cfg,
      BatchPgSessionProperties props,
      PoolTimeouts timeouts,
      String pgApplicationName) {
    // HA:Patroni / Citus coordinator 主备切换后,池中指向旧主的连接会变死。Hikari 默认 keepaliveTime=0
    // (仅借出时校验)→ 切换瞬间借到死连接会失败一次。未显式配置(yml 已设则尊重)时给 30s keepalive,
    // 让空闲连接定期 isValid 探测、死连接提前剔除,平滑度过 failover。与 pg-session init 是否启用无关,故置于最前。
    if (cfg.getKeepaliveTime() == 0L) {
      cfg.setKeepaliveTime(DEFAULT_KEEPALIVE_MS);
    }
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
