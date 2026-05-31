package com.example.batch.worker.spi.storedproc;

import com.example.batch.common.spi.task.BatchTaskExecutor;
import com.example.batch.common.spi.task.ResourceKind;
import com.example.batch.common.spi.task.TaskCapability;
import com.example.batch.common.spi.task.TaskContext;
import com.example.batch.common.spi.task.TaskResult;
import com.example.batch.worker.spi.runtime.SpiConnectionManager;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/**
 * Stored Procedure SPI 实现 — 通过 {@link CallableStatement} 调用存储过程,带白名单 / 超时 / OUT 参数截断 / REFCURSOR
 * 支持。
 *
 * <p>启用方式:{@code batch.worker.executors.stored-proc.enabled=true}(默认 false)。
 *
 * <p>parameters 协议:
 *
 * <ul>
 *   <li>{@code procedureName} (required, String):schema-qualified 过程名,如 {@code
 *       "batch.refresh_metrics"}
 *   <li>{@code inParams} (optional, List&lt;Object&gt;):IN 参数,按位置顺序
 *   <li>{@code outParams} (optional, List&lt;String&gt;):OUT 参数 SQL 类型名,如 {@code ["INTEGER",
 *       "VARCHAR"]}
 *   <li>{@code statementTimeoutSeconds} (optional, Long):覆盖默认超时,只能缩短
 *   <li>{@code dataSourceBean} (optional, String):覆盖配置的 dataSource bean 名;若与配置的 {@code
 *       dataSourceBeanName} 不同,必须在 {@code allowedDataSourceBeans} 白名单内,否则拒绝
 *   <li>{@code autoCommit} (optional, Boolean):覆盖默认事务模式
 * </ul>
 *
 * <p>output 协议:
 *
 * <ul>
 *   <li>{@code outValues} (Map&lt;String,Object&gt;):OUT 参数值,key = "p1", "p2", ...(按位置)
 *   <li>{@code resultSets} (List&lt;List&lt;Map&gt;&gt;):REFCURSOR 返回的结果集(每个 OUT REFCURSOR 一份)
 *   <li>{@code durationMillis} (Long)
 * </ul>
 *
 * <p>SQL Type 字符串到 java.sql.Types 的映射见 {@link #toSqlType}。
 */
@Slf4j
@Component
@ConditionalOnProperty(
    prefix = "batch.worker.executors.stored-proc",
    name = "enabled",
    havingValue = "true")
public class StoredProcTaskExecutor implements BatchTaskExecutor {

  static final String PARAM_PROC = "procedureName";
  static final String PARAM_IN = "inParams";
  static final String PARAM_OUT = "outParams";
  static final String PARAM_TIMEOUT = "statementTimeoutSeconds";
  static final String PARAM_DS_BEAN = "dataSourceBean";
  static final String PARAM_AUTO_COMMIT = "autoCommit";

  // 过程名字符规则:schema.proc 或 proc;允许字母数字下划线 + 一个可选 dot
  private static final Pattern PROC_NAME =
      Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?$");

  private final StoredProcExecutorProperties props;
  private final BeanFactory beanFactory;
  private final DataSource defaultDataSource;

  public StoredProcTaskExecutor(
      StoredProcExecutorProperties props, BeanFactory beanFactory, DataSource defaultDataSource) {
    this.props = props;
    this.beanFactory = beanFactory;
    this.defaultDataSource = defaultDataSource;
  }

  @Override
  public String taskType() {
    return props.getTaskType();
  }

  @Override
  public TaskCapability capability() {
    return new TaskCapability(
        java.util.Set.of(ResourceKind.DB),
        false, // 存过通常有副作用
        true,
        props.getDefaultStatementTimeout());
  }

  @Override
  public TaskResult execute(TaskContext ctx) {
    try {
      Invocation inv = parseInvocation(ctx);
      return runCall(ctx, inv);
    } catch (StoredProcValidationException ex) {
      return TaskResult.fail(ex.getMessage());
    } catch (RuntimeException ex) {
      log.error(
          "stored proc executor unexpected error: tenantId={}, jobCode={}",
          ctx.tenantId(),
          ctx.jobCode(),
          ex);
      return TaskResult.fail(ex);
    }
  }

  // ─── parsing + validation ────────────────────────────────────────────────────

  private Invocation parseInvocation(TaskContext ctx) {
    Map<String, Object> params = ctx.parameters();

    Object pnObj = params.get(PARAM_PROC);
    if (!(pnObj instanceof String) || ((String) pnObj).isBlank()) {
      throw new StoredProcValidationException("parameters.procedureName required");
    }
    String procName = ((String) pnObj).trim();
    if (!PROC_NAME.matcher(procName).matches()) {
      throw new StoredProcValidationException(
          "procedureName must match ^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?$, got: "
              + procName);
    }
    requireAllowed(procName);

    List<Object> inParams = parseInParams(params.get(PARAM_IN));
    List<String> outTypes = parseOutTypes(params.get(PARAM_OUT));

    // timeout(只能缩短)
    int timeoutSec = (int) props.getDefaultStatementTimeout().toSeconds();
    Object t = params.get(PARAM_TIMEOUT);
    if (t instanceof Number) {
      long requested = ((Number) t).longValue();
      if (requested <= 0) {
        throw new StoredProcValidationException("statementTimeoutSeconds must be positive");
      }
      if (requested < timeoutSec) {
        timeoutSec = (int) requested;
      }
    }

    // dataSource — 覆盖 bean 名必须在白名单内
    String requestedDsBean = stringParam(params, PARAM_DS_BEAN, null);
    String dsBeanName = resolveDataSourceBean(requestedDsBean);
    DataSource ds =
        dsBeanName == null ? defaultDataSource : beanFactory.getBean(dsBeanName, DataSource.class);

    boolean autoCommit = props.isDefaultAutoCommit();
    Object ac = params.get(PARAM_AUTO_COMMIT);
    if (ac instanceof Boolean) {
      autoCommit = (Boolean) ac;
    }

    return new Invocation(procName, inParams, outTypes, ds, timeoutSec, autoCommit);
  }

  /**
   * 放行校验:procedureWhitelist(精确)与 allowedSchemas(schema 级)是 OR 关系,命中任一即放行。 两者都空 = 允许全部(仅 dev /
   * 信任环境)。schema 级用于"把可信 schema 整个放行,新增过程零配置"。
   */
  private void requireAllowed(String procName) {
    boolean hasExactList = !props.getProcedureWhitelist().isEmpty();
    boolean hasSchemaList = !props.getAllowedSchemas().isEmpty();
    if (!hasExactList && !hasSchemaList) {
      return; // 两者都空 = 允许全部(仅 dev)
    }
    // 精确匹配大小写无关化:PG identifier 默认折叠小写(unquoted),白名单也按小写比较,
    // 避免 "BATCH.Proc" 这类大小写变体绕过/误拒(P-1)。
    boolean exactOk = lowercaseSet(props.getProcedureWhitelist()).contains(toLowerKey(procName));
    String schema = schemaOf(procName);
    boolean schemaOk = schema != null && props.getAllowedSchemas().contains(schema);
    if (!exactOk && !schemaOk) {
      throw new StoredProcValidationException(
          "procedureName not allowed: "
              + procName
              + ", allowedProcedures="
              + props.getProcedureWhitelist()
              + ", allowedSchemas="
              + props.getAllowedSchemas());
    }
  }

  /** 取 schema-qualified 过程名的 schema 部分(点号前);非 schema-qualified(无点)返回 null。 */
  private static String schemaOf(String procName) {
    int dot = procName.indexOf('.');
    return dot > 0 ? procName.substring(0, dot) : null;
  }

  /** identifier 折叠为小写比较 key(PG unquoted identifier 折叠规则)。 */
  private static String toLowerKey(String s) {
    return s.toLowerCase(Locale.ROOT);
  }

  /** 把白名单整体小写化用于大小写无关精确匹配。 */
  private static java.util.Set<String> lowercaseSet(java.util.Set<String> in) {
    java.util.Set<String> out = new java.util.HashSet<>(in.size());
    for (String s : in) {
      out.add(toLowerKey(s));
    }
    return out;
  }

  /**
   * 解析最终使用的 dataSource bean 名。{@code requested} 为 null = 用配置默认(返回 {@link
   * StoredProcExecutorProperties#getDataSourceBeanName()})。若 {@code requested} 与配置默认不同, 则必须在 {@code
   * allowedDataSourceBeans} 白名单内,否则抛 {@link StoredProcValidationException}。 提取为静态可测 helper。
   */
  static String resolveDataSourceBean(
      String requested, String configured, java.util.Set<String> allowed) {
    if (requested == null) {
      return configured;
    }
    if (requested.equals(configured)) {
      return requested;
    }
    if (allowed != null && allowed.contains(requested)) {
      return requested;
    }
    throw new StoredProcValidationException(
        "dataSourceBean not allowed: " + requested + ", allowedDataSourceBeans=" + allowed);
  }

  private String resolveDataSourceBean(String requested) {
    return resolveDataSourceBean(
        requested, props.getDataSourceBeanName(), props.getAllowedDataSourceBeans());
  }

  @SuppressWarnings("unchecked")
  private static List<Object> parseInParams(Object raw) {
    if (raw == null) {
      return List.of();
    }
    if (raw instanceof List<?>) {
      return new ArrayList<>((List<Object>) raw);
    }
    throw new StoredProcValidationException("parameters.inParams must be a list");
  }

  @SuppressWarnings("unchecked")
  private List<String> parseOutTypes(Object raw) {
    if (raw == null) {
      return List.of();
    }
    if (!(raw instanceof List<?>)) {
      throw new StoredProcValidationException(
          "parameters.outParams must be a list of SQL type names");
    }
    List<String> out = new ArrayList<>();
    for (Object o : (List<?>) raw) {
      if (o == null) {
        throw new StoredProcValidationException("outParams contains null");
      }
      String type = String.valueOf(o).trim().toUpperCase(Locale.ROOT);
      if (!props.getAllowedOutSqlTypes().contains(type)) {
        throw new StoredProcValidationException(
            "OUT type " + type + " not in allowedOutSqlTypes=" + props.getAllowedOutSqlTypes());
      }
      out.add(type);
    }
    return out;
  }

  private static String stringParam(Map<String, Object> p, String key, String fallback) {
    Object v = p.get(key);
    return v instanceof String && !((String) v).isBlank() ? ((String) v).trim() : fallback;
  }

  static int toSqlType(String typeName) {
    return switch (typeName) {
      case "BIGINT" -> Types.BIGINT;
      case "INTEGER" -> Types.INTEGER;
      case "SMALLINT" -> Types.SMALLINT;
      case "TINYINT" -> Types.TINYINT;
      case "DECIMAL" -> Types.DECIMAL;
      case "NUMERIC" -> Types.NUMERIC;
      case "DOUBLE" -> Types.DOUBLE;
      case "FLOAT" -> Types.FLOAT;
      case "REAL" -> Types.REAL;
      case "VARCHAR" -> Types.VARCHAR;
      case "CHAR" -> Types.CHAR;
      case "NVARCHAR" -> Types.NVARCHAR;
      case "NCHAR" -> Types.NCHAR;
      case "BOOLEAN" -> Types.BOOLEAN;
      case "BIT" -> Types.BIT;
      case "DATE" -> Types.DATE;
      case "TIME" -> Types.TIME;
      case "TIMESTAMP" -> Types.TIMESTAMP;
      case "TIMESTAMP_WITH_TIMEZONE" -> Types.TIMESTAMP_WITH_TIMEZONE;
      case "REF_CURSOR" -> Types.REF_CURSOR;
      case "OTHER" -> Types.OTHER;
      default -> throw new StoredProcValidationException("unsupported SQL type: " + typeName);
    };
  }

  // ─── execution ──────────────────────────────────────────────────────────────

  private TaskResult runCall(TaskContext ctx, Invocation inv) {
    long start = System.currentTimeMillis();
    int totalParams = inv.inParams.size() + inv.outTypes.size();
    SpiConnectionManager.Options opts =
        SpiConnectionManager.Options.defaults()
            .withAutoCommit(inv.autoCommit)
            // 角色闸用 executor 本地实现(StoredProcValidationException 保留 i18n 语义)
            .withForbidOsCapableRole(false);

    try {
      return SpiConnectionManager.withConnection(
          inv.dataSource,
          opts,
          conn -> {
            String call = buildCallSql(conn, inv.procName, totalParams);
            // 同一事务内 pin search_path,固定过程解析(防 search_path shadowing/注入)
            pinSearchPath(conn, inv.procName);
            // 堵死 OS:① 角色 ② SECURITY DEFINER(借 owner 提权绕过①)
            if (props.isForbidOsCapableRole()) {
              requireNonOsCapableRole(conn);
            }
            if (!props.isAllowSecurityDefiner()) {
              requireNotSecurityDefiner(conn, inv.procName);
            }
            if (props.isVerifyExecutePrivilege()) {
              requireExecutePrivilege(conn, inv.procName);
            }
            return execCallableStatement(conn, call, inv, start);
          });
    } catch (SQLException ex) {
      return TaskResult.fail("stored proc call failed: " + ex.getMessage(), ex);
    }
  }

  private TaskResult execCallableStatement(Connection conn, String call, Invocation inv, long start)
      throws SQLException {
    try (CallableStatement cs = conn.prepareCall(call)) {
      cs.setQueryTimeout(inv.timeoutSec);
      for (int i = 0; i < inv.inParams.size(); i++) {
        cs.setObject(i + 1, inv.inParams.get(i));
      }
      for (int i = 0; i < inv.outTypes.size(); i++) {
        cs.registerOutParameter(inv.inParams.size() + i + 1, toSqlType(inv.outTypes.get(i)));
      }
      cs.execute();

      Map<String, Object> outValues = new LinkedHashMap<>();
      List<List<Map<String, Object>>> resultSets = new ArrayList<>();
      boolean outputTruncated = false;
      for (int i = 0; i < inv.outTypes.size(); i++) {
        int pos = inv.inParams.size() + i + 1;
        String key = "p" + (i + 1);
        String type = inv.outTypes.get(i);
        if ("REF_CURSOR".equals(type)) {
          Object cursor = cs.getObject(pos);
          if (cursor instanceof ResultSet) {
            RefCursorResult rc = readRefCursor((ResultSet) cursor);
            resultSets.add(rc.rows());
            if (rc.truncated()) {
              outValues.put(key, "<REF_CURSOR truncated>");
              outputTruncated = true;
            } else {
              outValues.put(key, "<REF_CURSOR>");
            }
          } else {
            outValues.put(key, "<REF_CURSOR>");
          }
        } else {
          outValues.put(key, truncateIfNeeded(cs.getObject(pos)));
        }
      }

      Map<String, Object> output = new HashMap<>();
      output.put("outValues", outValues);
      output.put("resultSets", resultSets);
      output.put("truncated", outputTruncated);
      output.put("durationMillis", System.currentTimeMillis() - start);
      return TaskResult.ok(
          "called "
              + inv.procName
              + " (in="
              + inv.inParams.size()
              + ", out="
              + inv.outTypes.size()
              + ")",
          output);
    }
  }

  /**
   * 构造调用语句。真 PROCEDURE 用原生 {@code CALL proc(...)};FUNCTION 用 JDBC {@code {call ...}} 转义(PG 驱动转
   * SELECT)。原因:PG 默认 {@code escapeSyntaxCallMode=select} 把 {@code {call}} 当函数解析,调真过程会报 "is a
   * procedure, use CALL"。经 {@code pg_proc.prokind} 判定;非 PG / 查不到 / 异常时回退 {@code {call}}(保留旧函数语义)。
   */
  private static String buildCallSql(Connection conn, String procName, int totalParams) {
    String placeholders = "?,".repeat(totalParams);
    if (!placeholders.isEmpty()) {
      placeholders = placeholders.substring(0, placeholders.length() - 1);
    }
    if (isProcedure(conn, procName)) {
      return "CALL " + procName + "(" + placeholders + ")";
    }
    return "{call " + procName + "(" + placeholders + ")}";
  }

  /** 查 {@code pg_proc.prokind} 判定目标是否为 PROCEDURE('p')。非 PG / mock / 查不到时返回 false(按函数处理)。 */
  private static boolean isProcedure(Connection conn, String procName) {
    String schema = null;
    String name = procName;
    int dot = procName.indexOf('.');
    if (dot > 0) {
      schema = procName.substring(0, dot);
      name = procName.substring(dot + 1);
    }
    String sql =
        "select p.prokind from pg_catalog.pg_proc p"
            + " join pg_catalog.pg_namespace n on n.oid = p.pronamespace"
            + " where p.proname = ?"
            + (schema == null ? "" : " and n.nspname = ?")
            + " limit 1";
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, name);
      if (schema != null) {
        ps.setString(2, schema);
      }
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() && "p".equals(rs.getString(1));
      }
    } catch (SQLException | RuntimeException ex) {
      return false;
    }
  }

  /**
   * 读 REFCURSOR,最多读 {@link StoredProcExecutorProperties#getMaxRefCursorRows()} 行。达到上限即停止, 不再继续
   * {@code next()},标记 truncated;并设置 fetch size 避免驱动一次性把整个游标拉进堆(P-2)。
   */
  private RefCursorResult readRefCursor(ResultSet rs) throws SQLException {
    int cap = props.getMaxRefCursorRows();
    List<Map<String, Object>> rows = new ArrayList<>();
    boolean truncated = false;
    try (ResultSet r = rs) {
      try {
        // 提示驱动分批取,避免无界堆读。容量从上限和一个合理批量中取较小值。
        r.setFetchSize(Math.min(cap > 0 ? cap : 1000, 1000));
      } catch (SQLException ignore) {
        // 部分驱动/REFCURSOR 不支持 setFetchSize,忽略(仍由下面的行数上限兜底)。
      }
      ResultSetMetaData md = r.getMetaData();
      int cols = md.getColumnCount();
      while (r.next()) {
        if (rows.size() >= cap) {
          truncated = true;
          break;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 1; i <= cols; i++) {
          row.put(md.getColumnLabel(i), r.getObject(i));
        }
        rows.add(row);
      }
    }
    if (truncated) {
      log.warn("REF_CURSOR truncated at maxRefCursorRows={}", cap);
    }
    return new RefCursorResult(rows, truncated);
  }

  /**
   * 在当前事务内 pin search_path:{@code SET LOCAL search_path = pg_catalog, <schema>}。schema = 过程名
   * schema(若 qualified)否则 {@link StoredProcExecutorProperties#getDefaultSchema()}。 防止调用方借 session
   * search_path 把过程解析到攻击者可控 schema。仅在事务中生效(SET LOCAL),autoCommit=true 时无事务, 退化为对单条语句生效。非 PG /
   * 不支持时静默忽略(由白名单 + 限定名兜底)。
   */
  private void pinSearchPath(Connection conn, String procName) {
    String schema = schemaOf(procName);
    if (schema == null) {
      schema = props.getDefaultSchema();
    }
    // schema 已经过 PROC_NAME 正则校验(identifier 字符集),拼接安全;仍走静态 SQL。
    String sql = "SET LOCAL search_path = pg_catalog, " + schema;
    try (java.sql.Statement st = conn.createStatement()) {
      st.execute(sql);
    } catch (SQLException | RuntimeException ex) {
      log.debug("pin search_path skipped: {}", ex.getMessage());
    }
  }

  /**
   * DB 原生授权:current_user 必须对目标过程有 EXECUTE 权限,否则抛 {@link StoredProcValidationException}。 仅在 {@code
   * verifyExecutePrivilege=true} 时调用。
   */
  private void requireExecutePrivilege(Connection conn, String procName) {
    // 按 OID 判权:has_function_privilege 的 text 形态需带参数签名(如 'sch.f()'),无签名会报
    // "function does not exist";改用 pg_proc 解析 OID 再判,免签名、对重载也稳。
    int dot = procName.indexOf('.');
    String schema = dot > 0 ? procName.substring(0, dot) : props.getDefaultSchema();
    String name = dot > 0 ? procName.substring(dot + 1) : procName;
    String sql =
        "select has_function_privilege(current_user, p.oid, 'EXECUTE')"
            + " from pg_catalog.pg_proc p"
            + " join pg_catalog.pg_namespace n on n.oid = p.pronamespace"
            + " where p.proname = ? and n.nspname = ? limit 1";
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, name);
      ps.setString(2, schema);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          throw new StoredProcValidationException(
              "procedure not found for EXECUTE check: " + procName);
        }
        if (!rs.getBoolean(1)) {
          throw new StoredProcValidationException(
              "current_user lacks EXECUTE privilege on " + procName);
        }
      }
    } catch (SQLException ex) {
      throw new StoredProcValidationException(
          "EXECUTE privilege check failed for " + procName + ": " + ex.getMessage());
    }
  }

  /**
   * 代码层堵死 OS:拒绝以 OS 能力角色执行。存过 body 不可审查,唯一可靠的代码层防线是不给执行角色 OS 权限—— superuser 或 {@code
   * pg_execute_server_program} / {@code pg_read_server_files} / {@code pg_write_server_files}
   * 成员(这些是 COPY PROGRAM / 不可信 PL / 服务端文件访问的前置),命中即拒。
   */
  private void requireNonOsCapableRole(Connection conn) {
    String sql =
        "select rolsuper"
            + " or pg_has_role(current_user, 'pg_execute_server_program', 'USAGE')"
            + " or pg_has_role(current_user, 'pg_read_server_files', 'USAGE')"
            + " or pg_has_role(current_user, 'pg_write_server_files', 'USAGE')"
            + " from pg_roles where rolname = current_user";
    try (PreparedStatement ps = conn.prepareStatement(sql);
        ResultSet rs = ps.executeQuery()) {
      if (rs.next() && rs.getBoolean(1)) {
        throw new StoredProcValidationException(
            "refusing stored-proc on OS-capable DB role (superuser / pg_execute_server_program /"
                + " pg_read_server_files / pg_write_server_files); connect as a least-privilege"
                + " role, or disable forbidOsCapableRole only in trusted test envs");
      }
    } catch (SQLException ex) {
      throw new StoredProcValidationException("OS-capable role check failed: " + ex.getMessage());
    }
  }

  /**
   * 代码层堵死 OS(补充):拒绝 SECURITY DEFINER 过程({@code pg_proc.prosecdef=true})。它以 owner 身份运行, 若 owner 是
   * superuser/OS 能力角色,可绕过 {@link #requireNonOsCapableRole} 提权碰 OS。
   */
  private void requireNotSecurityDefiner(Connection conn, String procName) {
    int dot = procName.indexOf('.');
    String schema = dot > 0 ? procName.substring(0, dot) : props.getDefaultSchema();
    String name = dot > 0 ? procName.substring(dot + 1) : procName;
    String sql =
        "select p.prosecdef from pg_catalog.pg_proc p"
            + " join pg_catalog.pg_namespace n on n.oid = p.pronamespace"
            + " where p.proname = ? and n.nspname = ? limit 1";
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, name);
      ps.setString(2, schema);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next() && rs.getBoolean(1)) {
          throw new StoredProcValidationException(
              "refusing SECURITY DEFINER procedure (privilege escalation risk): " + procName);
        }
      }
    } catch (SQLException ex) {
      throw new StoredProcValidationException(
          "SECURITY DEFINER check failed for " + procName + ": " + ex.getMessage());
    }
  }

  private Object truncateIfNeeded(Object value) {
    if (value instanceof String s && s.length() > props.getMaxOutBytesPerParam()) {
      log.warn("OUT value truncated at {} bytes", props.getMaxOutBytesPerParam());
      return s.substring(0, props.getMaxOutBytesPerParam()) + "...<truncated>";
    }
    return value;
  }

  // ─── helper records / exceptions ────────────────────────────────────────────

  private record Invocation(
      String procName,
      List<Object> inParams,
      List<String> outTypes,
      DataSource dataSource,
      int timeoutSec,
      boolean autoCommit) {}

  /** readRefCursor 结果:已读行 + 是否因 maxRefCursorRows 截断。 */
  private record RefCursorResult(List<Map<String, Object>> rows, boolean truncated) {}

  static final class StoredProcValidationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    StoredProcValidationException(String message) {
      super(message);
    }
  }

  @Configuration
  @EnableConfigurationProperties(StoredProcExecutorProperties.class)
  static class PropertiesConfig {}
}
