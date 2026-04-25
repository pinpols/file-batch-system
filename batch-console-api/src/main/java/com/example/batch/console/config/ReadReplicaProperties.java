package com.example.batch.console.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * P2-4: Console-api 读写分离配置（{@code batch.console.read-replica}）。
 *
 * <p>启用后：
 * <ul>
 *   <li>{@code @Transactional(readOnly = true)} 标注的查询路由到从库 HikariPool
 *   <li>读写事务（默认 readOnly=false）走主库 HikariPool
 *   <li>{@link RouteToPrimary @RouteToPrimary} 注解强制走主库（read-after-write 场景）
 *   <li>从库连接连续失败 ≥ {@code failureThreshold} 次后 fail-open 降级到主库，进入 {@code quarantineSeconds} 隔离期
 * </ul>
 *
 * <p>未启用（默认）时不创建从库连接池，行为同历史（Spring Boot 主 DataSource 自动配置）。
 */
@Data
@ConfigurationProperties(prefix = "batch.console.read-replica")
public class ReadReplicaProperties {

  private boolean enabled = false;

  /** fail-open 触发阈值：连续从库连接失败次数。默认 3，达阈值后进入 quarantine。 */
  private int failureThreshold = 3;

  /** quarantine 持续秒数：进入隔离期内所有读查询走主库；期满后下次请求重试从库。默认 30s。 */
  private int quarantineSeconds = 30;

  private Pool primary = new Pool();

  private Pool replica = new Pool();

  /**
   * 主从连接池共用的配置 schema —— DRY 替代原先 Primary/Replica 双份重复定义（CLAUDE.md §分支消除规则）。
   */
  @Data
  public static class Pool {
    private String url;
    private String username;
    private String password;
    private String driverClassName = "org.postgresql.Driver";
    private int maximumPoolSize = 16;
    private int minimumIdle = 2;
    /** 获取连接超时（毫秒）。海量场景压低到 5s 避免 worker 堆积。 */
    private long connectionTimeoutMillis = 5_000L;
    /** Hikari validation timeout（毫秒）。 */
    private long validationTimeoutMillis = 3_000L;
    /** 连接最大空闲时间（毫秒）。 */
    private long idleTimeoutMillis = 600_000L;
    /** 连接最大寿命（毫秒）。需小于 PG/中间网络的 idle timeout。 */
    private long maxLifetimeMillis = 1_800_000L;
    /**
     * 连接泄漏检测阈值（毫秒）。0=禁用；建议 60_000 ~ 120_000，能在日志里抓到 Connection 没归还的代码栈。
     */
    private long leakDetectionThresholdMillis = 0L;
  }
}
