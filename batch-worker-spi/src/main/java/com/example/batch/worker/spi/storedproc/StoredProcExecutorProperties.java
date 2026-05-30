package com.example.batch.worker.spi.storedproc;

import java.time.Duration;
import java.util.Set;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@link StoredProcTaskExecutor} 配置 — 默认全关。
 *
 * <p>设计依据:{@code docs/design/task-spi-design.md}。
 *
 * <p>安全防护链:
 *
 * <ol>
 *   <li>{@link #enabled}:总开关,默认 false
 *   <li>{@link #dataSourceBeanName}:用哪个 dataSource(生产推荐独立低权限 datasource)
 *   <li>{@link #procedureWhitelist}:存储过程名白名单(schema-qualified),空 = 允许全部(不推荐)
 *   <li>{@link #defaultStatementTimeout}:CALL 超时
 *   <li>{@link #maxOutBytesPerParam}:单个 OUT 参数返回值的最大字节数(LOB / 大字符串截断)
 *   <li>{@link #defaultAutoCommit}:事务模式
 * </ol>
 */
@Data
@ConfigurationProperties(prefix = "batch.worker.executors.stored-proc")
public class StoredProcExecutorProperties {

  /** 总开关。默认 false。 */
  private boolean enabled = false;

  /** 给 stored proc 任务挂的 task type。固定 "stored_proc"。 */
  private String taskType = "stored_proc";

  /** dataSource bean 名。null = 主 datasource。 */
  private String dataSourceBeanName = null;

  /**
   * 过程名精确白名单(schema-qualified,如 "batch.refresh_metrics")。
   *
   * <p>与 {@link #allowedSchemas} 是 OR 关系:命中任一即放行。两者都空 = 允许全部(仅 dev / 信任环境)。 精确列举死板(每加一个
   * 过程都要改),日常优先用 {@link #allowedSchemas} 按 schema 放行。
   */
  private Set<String> procedureWhitelist = Set.of();

  /**
   * Schema 级允许清单(如 "batch" / "app")。命中 = 该 schema 下的所有过程都放行,新增过程零配置。
   *
   * <p>这是推荐的日常用法:把可信 schema(如自家 batch 业务库)整个放行,既不用逐个列过程,又挡住 {@code pg_catalog} / {@code public} 等逃逸
   * schema。与 {@link #procedureWhitelist} OR;两者都空 = 允许全部(仅 dev)。
   *
   * <p>注意:schema 级放行 = 信任该 schema 下"现在和将来的所有过程",请确保该 schema 的 DDL 写权限受控 (能建过程的人 = 能让 worker
   * 执行任意逻辑)。
   */
  private Set<String> allowedSchemas = Set.of();

  /** CALL 超时(setQueryTimeout)。 */
  private Duration defaultStatementTimeout = Duration.ofMinutes(1);

  /** 单个 OUT 参数最大返回字节数(超出截断 + WARN)。LOB / 大字符串保护。 */
  private int maxOutBytesPerParam = 64 * 1024;

  /** 事务模式。false = 显式 commit/rollback;true = autoCommit。 */
  private boolean defaultAutoCommit = false;

  /** 允许的 SQL Type 名集合(给 outParams 白名单)。常用全开;不允许 OTHER / STRUCT / ARRAY 等复合。 */
  private Set<String> allowedOutSqlTypes =
      Set.of(
          "BIGINT",
          "INTEGER",
          "SMALLINT",
          "TINYINT",
          "DECIMAL",
          "NUMERIC",
          "DOUBLE",
          "FLOAT",
          "REAL",
          "VARCHAR",
          "CHAR",
          "NVARCHAR",
          "NCHAR",
          "BOOLEAN",
          "BIT",
          "DATE",
          "TIME",
          "TIMESTAMP",
          "TIMESTAMP_WITH_TIMEZONE",
          "REF_CURSOR", // PG REFCURSOR
          "OTHER" // PG JSON / JSONB / UUID 等走 OTHER
          );
}
