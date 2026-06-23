package io.github.pinpols.batch.common.config;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * 全模块共用的 PostgreSQL 会话治理：JDBC {@code ApplicationName} + 连接级 {@code SET}（由 Hikari {@code
 * connectionInitSql} 注入）。
 *
 * <p>平台库池（调度态、短事务）默认更紧；业务库池（导出/导入大 SQL）默认更松。Flyway 与业务共用同一 DataSource 时，若单条迁移超过 {@code
 * platform.statement-timeout} 会失败——大迁移前请临时抬高该值或关闭 {@code enabled}。
 */
@Data
@ConfigurationProperties(prefix = "batch.datasource.pg-session")
public class BatchPgSessionProperties {

  /** 关闭后跳过 ApplicationName / connectionInitSql（联调排查用）。 */
  private boolean enabled = true;

  /**
   * 为 true 时：在已有 {@code spring.datasource.hikari.connection-init-sql} 之前<b>前置</b>会话级 {@code SET}。
   */
  private boolean mergeConnectionInitSql = true;

  /** 平台库池（spring.datasource / console primary&replica / worker platform pool）。 */
  private PoolTimeouts platform = platformDefaults();

  /**
   * 业务库池（worker {@code batch.datasource.business}）。注意与 {@link
   * io.github.pinpols.batch.common.config.BusinessDataSourceProperties} 的 JDBC URL 配置是不同节点。
   */
  private PoolTimeouts business = businessPoolDefaults();

  private static PoolTimeouts platformDefaults() {
    PoolTimeouts p = new PoolTimeouts();
    p.setStatementTimeout(Duration.ofMinutes(15));
    p.setIdleInTransactionTimeout(Duration.ofSeconds(60));
    return p;
  }

  private static PoolTimeouts businessPoolDefaults() {
    PoolTimeouts p = new PoolTimeouts();
    p.setStatementTimeout(Duration.ofMinutes(30));
    // 默认 10 分钟: PROCESS 5-stage WAP 中 COMMIT 步骤可能跑长事务 (compute → validate → commit), 5 分钟不够
    // 用; EXPORT 单 task 多在分钟内, 10 分钟仍足够防 "BEGIN; ... 出去吃饭" 场景
    p.setIdleInTransactionTimeout(Duration.ofMinutes(10));
    return p;
  }

  @Data
  public static class PoolTimeouts {
    /**
     * 单语句上限。PostgreSQL 语义为毫秒；{@link Duration#ZERO} 映射为 {@code SET ... TO 0}（即关闭服务端 statement 超时）。
     */
    private Duration statementTimeout = Duration.ofMinutes(15);

    /** 事务开启后空闲上限；{@link Duration#ZERO} 映射为关闭该超时。 */
    private Duration idleInTransactionTimeout = Duration.ofSeconds(60);

    /** PostgreSQL work_mem；null 表示不改会话默认。 */
    private DataSize workMem;

    /** PostgreSQL maintenance_work_mem；null 表示不改会话默认。 */
    private DataSize maintenanceWorkMem;
  }
}
