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
   * 允许通过 {@code parameters.dataSourceBean} 覆盖使用的 dataSource bean 白名单。
   *
   * <p>默认空 = 不允许任何覆盖:{@code parameters.dataSourceBean} 只能等于 {@link #dataSourceBeanName} (或缺省),否则抛
   * {@code StoredProcValidationException}。任务级随意指定 bean 名等于让调用方任选连接 / 凭据,
   * 是一条提权路径,所以默认锁死,需显式列入此白名单才放行。
   */
  private Set<String> allowedDataSourceBeans = Set.of();

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

  /**
   * 非 schema-qualified 过程名(无点)在 pin search_path 时使用的默认 schema。默认 "batch"。
   *
   * <p>CALL 前会 {@code SET LOCAL search_path = pg_catalog, <schema>},以固定解析目标、防止 调用方通过 session
   * search_path 把过程解析到攻击者可控的 schema(search_path 注入 / shadowing)。 过程名已 schema-qualified 时用其
   * schema,否则用本值。
   */
  private String defaultSchema = "batch";

  /**
   * REFCURSOR 单个结果集最大返回行数。默认 10000。
   *
   * <p>readRefCursor 读到此上限即停止(并设置 JDBC fetch size 避免一次性把整个游标拉进堆),输出标记 {@code
   * truncated=true}。旧实现无行数上限,大游标会 OOM,本属性是内存保护。
   */
  private int maxRefCursorRows = 10000;

  /**
   * 是否在 CALL 前用 {@code has_function_privilege(current_user, 'schema.proc', 'EXECUTE')} 做 DB
   * 原生授权校验。默认 false(不依赖真库,单测无需 docker)。
   *
   * <p>开启后多一次 PreparedStatement 往返:current_user 对目标过程无 EXECUTE 权限则在 CALL 前拒绝,
   * 把授权下沉到数据库(纵深防御,白名单之外再加一层)。
   */
  private boolean verifyExecutePrivilege = false;

  /**
   * 是否允许调用 SECURITY DEFINER 过程。默认 true(保持现有行为,不做 DB 检查)。
   *
   * <p>SECURITY DEFINER 过程以其 <b>owner</b> 身份执行而非调用者(对应 PG {@code pg_proc.prosecdef = true}),
   * 这是经典提权面:低权限 worker 角色可借此跑 owner 权限的逻辑(若 owner 是 superuser → 借机碰 OS)。 默认 <b>false</b>(堵死):CALL
   * 前查 {@code pg_proc.prosecdef},是 definer 过程则拒。仅在确知 definer 过程安全时显式置 true。
   */
  private boolean allowSecurityDefiner = false;

  /**
   * 是否拒绝以"有 OS 能力的 DB 角色"执行(默认 <b>true</b>,fail-closed)。
   *
   * <p>存过 body 在 DB 内执行、CALL 端无法审查其内容,故代码层堵死 OS 的唯一可靠手段是:拒绝 superuser 或 {@code
   * pg_execute_server_program} / {@code pg_read_server_files} / {@code pg_write_server_files} 成员角色。
   * 这些权限是 {@code COPY ... PROGRAM} / 不可信 PL / 服务端文件访问的前置;无之则过程物理上碰不到 OS。 执行前查 {@code current_user}
   * 能力,命中即拒。生产应以最小权限非 superuser 角色连接;测试(testcontainers superuser)需显式置 false。
   */
  private boolean forbidOsCapableRole = true;

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
