package com.example.batch.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Worker 业务库 HikariCP 连接池配置（{@code batch.datasource.business}）。
 *
 * <p>区别于 platform 库（任务实例 / outbox / 审计等运行态表），business 库存放业务侧 的源表 / 落地表（IMPORT 的目标表、EXPORT 的源表）。两库走双
 * DataSource 路由， 详见 ADR-007。Worker 进程同时持有两个 pool，<b>business pool 默认比 platform 略大</b>， 因为单个 task
 * 通常多次读写业务表。
 */
@Data
@ConfigurationProperties(prefix = "batch.datasource.business")
public class BusinessDataSourceProperties {

  /** JDBC URL（PostgreSQL: {@code jdbc:postgresql://host:5432/dbname?currentSchema=...}）。 */
  private String url;

  /** 业务库登录名。 */
  private String username;

  /** 业务库登录密码。生产必须通过 secret manager 注入，禁止明文写入仓库。 */
  private String password;

  /** 业务库 schema 名（PG 多 schema 隔离 platform / business 时使用）。 */
  private String schema;

  /** Hikari 池上限。Worker 单实例并发 task 数 × 单 task 平均连接占用 + 5 余量。 */
  private int maximumPoolSize = 20;

  /** 最小空闲连接，避免冷启动每次现建。 */
  private int minimumIdle = 5;

  /** 取连接超时（ms）。超时未拿到 → fail-fast，避免请求线程全部阻塞在拿连接上。 */
  private long connectionTimeoutMs = 5000L;

  /** 连接泄漏检测阈值（ms）。超过未还的连接打 WARN 堆栈，定位忘记 close 的代码。 */
  private long leakDetectionThresholdMs = 30000L;
}
