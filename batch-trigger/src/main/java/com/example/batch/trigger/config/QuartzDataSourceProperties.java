package com.example.batch.trigger.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Quartz JobStore 独占 DataSource 配置（{@code batch.trigger.quartz-datasource}）。
 *
 * <p>开启 {@code enabled=true} 后，Quartz 走独占连接池而非默认主库 DataSource。海量调度场景下：
 * <ul>
 *   <li>Quartz 持续抢锁 / poll trigger 会和业务表争 WAL/锁，分流后互不影响
 *   <li>Quartz 库容量增长可控（misfire / scheduler_state 表）独立观察
 *   <li>主库 read replica 启用时，Quartz 仍走自己的 PG（Quartz 只支持单点写）
 * </ul>
 *
 * <p>未启用（默认）时，{@code QuartzAutoConfiguration} 共用 Spring Boot 自动配置的主 DataSource，
 * 行为与历史一致。启用时本配置必填 {@code url} / {@code username} / {@code password}。
 */
@Data
@ConfigurationProperties(prefix = "batch.trigger.quartz-datasource")
public class QuartzDataSourceProperties {

  private boolean enabled = false;
  private String url;
  private String username;
  private String password;
  private String driverClassName = "org.postgresql.Driver";
  private int maximumPoolSize = 8;
  private int minimumIdle = 2;
  private long connectionTimeoutMillis = 30_000L;
  private long idleTimeoutMillis = 600_000L;
  private long maxLifetimeMillis = 1_800_000L;
}
