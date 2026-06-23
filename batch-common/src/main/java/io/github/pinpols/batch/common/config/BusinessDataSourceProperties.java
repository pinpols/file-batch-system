package io.github.pinpols.batch.common.config;

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

  /**
   * 连接最长存活（ms）。主备切换（Patroni/HAProxy/VIP）后池中旧连接已断但 Hikari 不知道，到点主动 重建，让残留死连接在一个生命周期内被淘汰。应 &lt; 后端
   * idle 上限 + &lt; PG wait_timeout。默认 29min 留余量避免与常见 30min 基础设施上限打平。0=用 Hikari 默认 30min。
   */
  private long maxLifetimeMs = 1740000L;

  // keepalive 不在此处配:由 HikariPgSessionSupport 对所有 PG 池统一默认回退 30s(见 PR #454),
  // 业务池经 applyBusiness() 已覆盖,此处再设会形成双源默认、易 drift。

  /** 借出前连接校验超时（ms）。校验慢/长期停滞时快速判失败重取，避免切换瞬间长时间阻塞在坏连接上。 */
  private long validationTimeoutMs = 3000L;
}
