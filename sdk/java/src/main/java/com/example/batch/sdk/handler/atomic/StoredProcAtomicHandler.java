package com.example.batch.sdk.handler.atomic;

import com.example.batch.sdk.handler.SdkAbstractAtomicHandler;
import com.example.batch.sdk.task.SdkTaskContext;
import java.nio.charset.StandardCharsets;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;

/**
 * 开箱即用的存储过程原子 handler — 通过 JDBC {@link CallableStatement} 调用 PG 存储过程,对齐平台 {@code
 * StoredProcExecutor} 的三道安全闸(见 {@link StoredProcAtomicConfig})。
 *
 * <p>SDK 侧只依赖 {@code java.sql.*}(禁第三方 JDBC pool / Spring / batch-common),连接由调用方传入的 {@link
 * DataSource} 提供。
 *
 * <p>parameters 协议:
 *
 * <ul>
 *   <li>{@code procedureName}(required, String):schema-qualified 过程名,如 {@code "batch.refresh"};必须匹配
 *       {@link #PROC_NAME}。
 *   <li>{@code inParams}(optional, List):IN 参数,按位置顺序。
 *   <li>{@code outParams}(optional, List&lt;String&gt;):OUT 参数 SQL 类型名,如 {@code ["INTEGER",
 *       "VARCHAR"]}。
 * </ul>
 *
 * <p>output 协议:{@code {"outValues": Map<"p1",...>, "procedureName": name}}。
 */
@Slf4j
public class StoredProcAtomicHandler extends SdkAbstractAtomicHandler<Map<String, Object>> {

  static final String PARAM_PROC = "procedureName";
  static final String PARAM_IN = "inParams";
  static final String PARAM_OUT = "outParams";

  /** 过程名规则:schema.proc 或 proc;identifier 字符集 + 一个可选 dot。 */
  private static final Pattern PROC_NAME =
      Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?$");

  private final StoredProcAtomicConfig config;
  private final DataSource dataSource;

  public StoredProcAtomicHandler(StoredProcAtomicConfig config, DataSource dataSource) {
    if (config == null) {
      throw new IllegalArgumentException("config must not be null");
    }
    if (dataSource == null) {
      throw new IllegalArgumentException("dataSource must not be null");
    }
    this.config = config;
    this.dataSource = dataSource;
  }

  @Override
  public String taskType() {
    return config.taskType();
  }

  @Override
  protected Map<String, Object> asOutput(Map<String, Object> r) {
    return r;
  }

  @Override
  protected Map<String, Object> doInvoke(SdkTaskContext ctx) throws Exception {
    Map<String, Object> params = ctx.parameters();

    String procName = parseProcedureName(params);
    List<Object> inParams = parseInParams(params.get(PARAM_IN));
    List<String> outTypes = parseOutTypes(params.get(PARAM_OUT));

    // 闸 2 — allowedSchemas(连库前先拦)
    requireAllowedSchema(procName);

    int totalParams = inParams.size() + outTypes.size();
    try (Connection conn = dataSource.getConnection()) {
      // 闸 1 — OS 能力角色闸
      if (config.forbidOsCapableRole()) {
        requireNonOsCapableRole(conn);
      }
      // 闸 3 — SECURITY DEFINER 闸
      if (!config.allowSecurityDefiner()) {
        requireNotSecurityDefiner(conn, procName);
      }
      // 闸 4 — EXECUTE 权限校验(opt-in,对齐平台 verifyExecutePrivilege)
      if (config.verifyExecutePrivilege()) {
        requireExecutePrivilege(conn, procName);
      }
      return runCall(conn, procName, inParams, outTypes, totalParams);
    }
  }

  /** 按 defaultAutoCommit 决定事务边界,执行 CALL,读 OUT(按 maxOutBytesPerParam 截断)。 */
  private Map<String, Object> runCall(
      Connection conn, String procName, List<Object> inParams, List<String> outTypes, int total)
      throws Exception {
    boolean autoCommit = config.defaultAutoCommit();
    boolean originalAutoCommit = conn.getAutoCommit();
    try {
      conn.setAutoCommit(autoCommit);
      try (CallableStatement cs = conn.prepareCall(buildCall(procName, total))) {
        cs.setQueryTimeout(config.statementTimeoutSeconds());
        for (int i = 0; i < inParams.size(); i++) {
          cs.setObject(i + 1, inParams.get(i));
        }
        for (int i = 0; i < outTypes.size(); i++) {
          cs.registerOutParameter(inParams.size() + i + 1, toSqlType(outTypes.get(i)));
        }
        cs.execute();

        Map<String, Object> outValues = new LinkedHashMap<>();
        for (int i = 0; i < outTypes.size(); i++) {
          outValues.put("p" + (i + 1), truncateOut(cs.getObject(inParams.size() + i + 1)));
        }
        if (!autoCommit) conn.commit();

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("outValues", outValues);
        output.put("procedureName", procName);
        log.info(
            "stored proc {} called (in={}, out={})", procName, inParams.size(), outTypes.size());
        return output;
      }
    } catch (Exception ex) {
      if (!autoCommit) {
        try {
          conn.rollback();
        } catch (Exception rbEx) {
          log.warn("rollback failed: {}", rbEx.getMessage());
        }
      }
      throw ex;
    } finally {
      try {
        conn.setAutoCommit(originalAutoCommit);
      } catch (Exception restoreEx) {
        log.warn("restore autoCommit failed: {}", restoreEx.getMessage());
      }
    }
  }

  /** OUT 值字符串化后超 maxOutBytesPerParam(UTF-8 字节)则截断 + 标记。 */
  private Object truncateOut(Object value) {
    if (!(value instanceof String s)) {
      return value;
    }
    byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
    if (bytes.length <= config.maxOutBytesPerParam()) {
      return s;
    }
    // 截断到字节上限(可能切断多字节字符,用 String 解码兜底)
    String truncated = new String(bytes, 0, config.maxOutBytesPerParam(), StandardCharsets.UTF_8);
    return truncated + "...[truncated " + bytes.length + " bytes]";
  }

  /** 闸 4 — current_user 对目标过程无 EXECUTE 权限则拒(has_function_privilege)。 */
  private void requireExecutePrivilege(Connection conn, String procName) throws Exception {
    String sql = "select has_function_privilege(current_user, ?, 'EXECUTE')";
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      // has_function_privilege 需要 regprocedure 形式(proc 名 + 参数签名);简化:用 proc 名 + "()" 兜底,
      // 真业务过程有参数时建议直接给 schema-qualified 名,PG 会按名解析。无参或重载场景由 DB 报错兜底。
      ps.setString(1, procName);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next() && !rs.getBoolean(1)) {
          throw new SecurityException(
              "current_user lacks EXECUTE privilege on procedure: " + procName);
        }
      }
    }
  }

  // ─── parsing ──────────────────────────────────────────────────────────────

  private static String parseProcedureName(Map<String, Object> params) {
    Object raw = params.get(PARAM_PROC);
    if (!(raw instanceof String s) || s.isBlank()) {
      throw new IllegalArgumentException("parameters.procedureName required");
    }
    String procName = s.trim();
    if (!PROC_NAME.matcher(procName).matches()) {
      throw new IllegalArgumentException(
          "procedureName must match ^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?$, got: "
              + procName);
    }
    return procName;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> parseInParams(Object raw) {
    if (raw == null) {
      return List.of();
    }
    if (raw instanceof List<?> list) {
      return List.copyOf((List<Object>) list);
    }
    throw new IllegalArgumentException("parameters.inParams must be a list");
  }

  private static List<String> parseOutTypes(Object raw) {
    if (raw == null) {
      return List.of();
    }
    if (!(raw instanceof List<?> list)) {
      throw new IllegalArgumentException("parameters.outParams must be a list of SQL type names");
    }
    List<String> out = new ArrayList<>();
    for (Object o : list) {
      if (o == null) {
        throw new IllegalArgumentException("parameters.outParams contains null");
      }
      out.add(String.valueOf(o).trim().toUpperCase(Locale.ROOT));
    }
    return out;
  }

  // ─── 三道闸 ─────────────────────────────────────────────────────────────────

  /** 闸 2:allowedSchemas 非空时,procedureName 必须 schema-qualified 且 schema 命中白名单。 */
  private void requireAllowedSchema(String procName) {
    if (config.allowedSchemas().isEmpty()) {
      return; // 空 = dev 全放行(授权由最小权限 DB 角色保证)
    }
    String schema = schemaOf(procName);
    if (schema == null || !config.allowedSchemas().contains(schema)) {
      throw new SecurityException(
          "procedureName schema not allowed: "
              + procName
              + ", allowedSchemas="
              + config.allowedSchemas());
    }
  }

  /**
   * 闸 1:拒绝 OS 能力 DB 角色(superuser / pg_execute_server_program / pg_read_server_files /
   * pg_write_server_files),命中即抛 {@link SecurityException}。
   */
  private void requireNonOsCapableRole(Connection conn) throws Exception {
    String sql =
        "select rolsuper"
            + " or pg_has_role(current_user, 'pg_execute_server_program', 'USAGE')"
            + " or pg_has_role(current_user, 'pg_read_server_files', 'USAGE')"
            + " or pg_has_role(current_user, 'pg_write_server_files', 'USAGE')"
            + " from pg_roles where rolname = current_user";
    try (PreparedStatement ps = conn.prepareStatement(sql);
        ResultSet rs = ps.executeQuery()) {
      if (rs.next() && rs.getBoolean(1)) {
        throw new SecurityException(
            "refusing stored-proc on OS-capable DB role (superuser /"
                + " pg_execute_server_program / pg_read_server_files / pg_write_server_files);"
                + " connect as a least-privilege role, or disable forbidOsCapableRole only in"
                + " trusted test envs");
      }
    }
  }

  /**
   * 闸 3:拒绝 SECURITY DEFINER 过程({@code pg_proc.prosecdef=true})。它以 owner 身份运行,若 owner 是 OS 能力角色,可绕过闸
   * 1 提权碰 OS。
   */
  private void requireNotSecurityDefiner(Connection conn, String procName) throws Exception {
    String schema = schemaOf(procName);
    String name = nameOf(procName);
    String sql =
        schema == null
            ? "select prosecdef from pg_proc where proname = ?"
            : "select p.prosecdef from pg_catalog.pg_proc p"
                + " join pg_catalog.pg_namespace n on n.oid = p.pronamespace"
                + " where p.proname = ? and n.nspname = ?";
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, name);
      if (schema != null) {
        ps.setString(2, schema);
      }
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next() && rs.getBoolean(1)) {
          throw new SecurityException(
              "refusing SECURITY DEFINER procedure (privilege escalation risk): " + procName);
        }
      }
    }
  }

  // ─── helpers ──────────────────────────────────────────────────────────────

  /** 取 schema-qualified 过程名的 schema(点号前);非 qualified 返回 null。 */
  private static String schemaOf(String procName) {
    int dot = procName.indexOf('.');
    return dot > 0 ? procName.substring(0, dot) : null;
  }

  /** 取过程名(点号后部分);非 qualified 返回原值。 */
  private static String nameOf(String procName) {
    int dot = procName.indexOf('.');
    return dot > 0 ? procName.substring(dot + 1) : procName;
  }

  static String buildCall(String name, int total) {
    return "{call " + name + "(" + "?,".repeat(total).replaceAll(",$", "") + ")}";
  }

  static int toSqlType(String typeName) {
    return switch (typeName) {
      case "INTEGER" -> Types.INTEGER;
      case "BIGINT" -> Types.BIGINT;
      case "VARCHAR" -> Types.VARCHAR;
      case "BOOLEAN" -> Types.BOOLEAN;
      case "NUMERIC" -> Types.NUMERIC;
      case "DATE" -> Types.DATE;
      case "TIMESTAMP" -> Types.TIMESTAMP;
      default -> throw new IllegalArgumentException("unsupported SQL type: " + typeName);
    };
  }
}
