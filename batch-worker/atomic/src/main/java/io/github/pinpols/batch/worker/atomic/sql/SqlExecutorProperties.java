package io.github.pinpols.batch.worker.atomic.sql;

import java.time.Duration;
import java.util.Set;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@link SqlTaskExecutor} 配置 — 默认开启,放行范围由所连 DB 角色决定(运维配置)。
 *
 * <p>设计依据:{@code docs/design/task-spi-design.md}。
 *
 * <p>三道闸安全模型(不再用高维护的 app 语句白名单):
 *
 * <ol>
 *   <li><b>schema/user 白名单</b>:{@link #dataSourceBeanName} / {@link #allowedDataSourceBeans} 锁连接 /
 *       凭据,最小权限 DB role 才是真边界
 *   <li><b>资源限制</b>:{@link #defaultStatementTimeout} / {@link #maxStatementsPerJob} / {@link
 *       #maxResultRows}
 *   <li><b>代码层 OS 拒绝</b>:{@link #forbidOsCapableRole} 拒 superuser / pg_execute_server_program 等 OS
 *       能力角色
 * </ol>
 *
 * <p>语句类型 / DDL 不再由 app 白名单管控 —— 放行范围 = 所连 DB 角色被授予的权限。{@link #enabled} 默认 true。
 */
@Data
@ConfigurationProperties(prefix = "batch.worker.executors.sql")
public class SqlExecutorProperties {

  /** 总开关。默认 <b>true</b>(随 atomic worker 默认提供;真边界是最小权限 DB 角色 + 三道闸)。 */
  private boolean enabled = true;

  /** 给 sql 任务挂的 task type。固定 "sql"。 */
  private String taskType = "sql";

  /** dataSource bean 名。null = 用主 datasource;生产推荐显式指定专用低权限 datasource。 */
  private String dataSourceBeanName = null;

  /**
   * 允许通过 param {@code dataSourceBean} 覆盖的 bean 名白名单。默认空 = 不允许任何覆盖。
   *
   * <p>配置的 {@link #dataSourceBeanName}(及 null 默认库)永远允许;param 指定的其它 bean 名必须命中此集合,否则拒绝。 防止业务方借 param
   * 切到任意高权限 datasource。
   */
  private Set<String> allowedDataSourceBeans = Set.of();

  /**
   * 是否拒绝以"有 OS 能力的 DB 角色"执行(默认 <b>true</b>,fail-closed)。
   *
   * <p><b>PostgreSQL only</b>:本闸通过查 {@code pg_roles} / {@code pg_has_role()} 实现,仅 PostgreSQL 方言生效。
   * 在 MySQL / Oracle / SQL Server / H2 等其他方言上执行时,{@link SqlTaskExecutor} 会打一条 WARN 后跳过(避免「查询失败抛异常 →
   * 误以为安全闸生效」的假阴性),仍允许 SQL 执行;请在该方言上自行配置最小权限 DB 用户作为真正的边界。
   *
   * <p>执行前查 {@code current_user}:superuser 或 {@code pg_execute_server_program} / {@code
   * pg_read_server_files} / {@code pg_write_server_files} 成员即拒。这些是 {@code COPY ... PROGRAM} / 服务端文件
   * / 不可信 PL 的前置;无之则 SQL 物理上碰不到 OS。这是堵 OS 的<b>硬保证</b>(黑名单可被混淆绕过,角色闸不会)。 生产应以最小权限非 superuser
   * 角色连接;测试(testcontainers superuser)需显式置 false。
   */
  private boolean forbidOsCapableRole = true;

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
