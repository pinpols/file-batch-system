package com.example.batch.worker.spi.sql;

import java.time.Duration;
import java.util.Set;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@link SqlTaskExecutor} 配置 — 默认全关,业务方按需开 + 配 dataSource bean 名 + 配 DDL 白名单。
 *
 * <p>设计依据:{@code docs/design/task-spi-design.md}。
 *
 * <p>安全防护链:
 *
 * <ol>
 *   <li>{@link #enabled}:总开关,默认 false → bean 不注册
 *   <li>{@link #dataSourceBeanName}:用哪个 dataSource 跑(默认走主库;生产建议建专用低权限 DB role + 独立 datasource bean)
 *   <li>{@link #allowedStatementTypes}:语句类型白名单(SELECT / INSERT / UPDATE / DELETE / DDL / CALL / 其它)
 *   <li>{@link #ddlWhitelist}:DDL 关键词白名单(allowedStatementTypes 含 "DDL" 时生效)
 *   <li>{@link #defaultStatementTimeout}:单 statement 超时(setQueryTimeout)
 *   <li>{@link #maxStatementsPerJob}:单任务最多语句数,防一次塞 1000 条
 *   <li>{@link #defaultAutoCommit}:默认事务模式(false = 显式 commit/rollback)
 *   <li>{@link #maxResultRows}:SELECT 结果行数上限,超出截断 + WARN
 * </ol>
 */
@Data
@ConfigurationProperties(prefix = "batch.worker.executors.sql")
public class SqlExecutorProperties {

  /** 总开关。默认 false。 */
  private boolean enabled = false;

  /** 给 sql 任务挂的 task type。固定 "sql"。 */
  private String taskType = "sql";

  /** dataSource bean 名。null = 用主 datasource;生产推荐显式指定专用低权限 datasource。 */
  private String dataSourceBeanName = null;

  /** 允许的语句类型集合。默认只允许 SELECT(读)。生产按需放开。 */
  private Set<String> allowedStatementTypes = Set.of("SELECT");

  /** DDL 关键词白名单(allowedStatementTypes 含 DDL 时生效)。空 = 拒绝所有 DDL。 */
  private Set<String> ddlWhitelist = Set.of();

  /** 单 statement 超时。 */
  private Duration defaultStatementTimeout = Duration.ofSeconds(30);

  /** 单任务最多语句数。 */
  private int maxStatementsPerJob = 50;

  /** 默认事务模式。false = autoCommit OFF,需要显式 commit/rollback;true = 每条自动 commit。 */
  private boolean defaultAutoCommit = false;

  /** SELECT 结果行数上限。超出截断 + WARN。 */
  private int maxResultRows = 10_000;

  /** 是否回写 SELECT result(false → 不进 TaskResult.output,只返 rowCount)。 */
  private boolean includeResultSet = true;
}
