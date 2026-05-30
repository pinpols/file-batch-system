package com.example.batch.worker.core.spi.storedproc;

import com.example.batch.common.spi.task.BatchTaskExecutor;
import com.example.batch.common.spi.task.ResourceKind;
import com.example.batch.common.spi.task.TaskCapability;
import com.example.batch.common.spi.task.TaskContext;
import com.example.batch.common.spi.task.TaskResult;
import java.sql.CallableStatement;
import java.sql.Connection;
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
 *   <li>{@code dataSourceBean} (optional, String):覆盖配置的 dataSource bean 名
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

    // dataSource
    String dsBeanName = stringParam(params, PARAM_DS_BEAN, props.getDataSourceBeanName());
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
    boolean exactOk = props.getProcedureWhitelist().contains(procName);
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

    // 构造 CALL SQL:{call proc(?, ?, ...)} placeholder count = in + out
    int totalParams = inv.inParams.size() + inv.outTypes.size();
    StringBuilder call = new StringBuilder("{call ").append(inv.procName).append("(");
    for (int i = 0; i < totalParams; i++) {
      if (i > 0) call.append(",");
      call.append("?");
    }
    call.append(")}");

    try (Connection conn = inv.dataSource.getConnection()) {
      boolean originalAutoCommit = conn.getAutoCommit();
      try {
        conn.setAutoCommit(inv.autoCommit);

        try (CallableStatement cs = conn.prepareCall(call.toString())) {
          cs.setQueryTimeout(inv.timeoutSec);

          // IN params (positions 1..N)
          for (int i = 0; i < inv.inParams.size(); i++) {
            cs.setObject(i + 1, inv.inParams.get(i));
          }
          // OUT params (positions N+1..N+M)
          for (int i = 0; i < inv.outTypes.size(); i++) {
            cs.registerOutParameter(inv.inParams.size() + i + 1, toSqlType(inv.outTypes.get(i)));
          }

          cs.execute();

          // 读 OUT 值
          Map<String, Object> outValues = new LinkedHashMap<>();
          List<List<Map<String, Object>>> resultSets = new ArrayList<>();
          for (int i = 0; i < inv.outTypes.size(); i++) {
            int pos = inv.inParams.size() + i + 1;
            String key = "p" + (i + 1);
            String type = inv.outTypes.get(i);
            if ("REF_CURSOR".equals(type)) {
              Object cursor = cs.getObject(pos);
              if (cursor instanceof ResultSet) {
                resultSets.add(readRefCursor((ResultSet) cursor));
              }
              outValues.put(key, "<REF_CURSOR>");
            } else {
              Object v = cs.getObject(pos);
              outValues.put(key, truncateIfNeeded(v));
            }
          }

          if (!inv.autoCommit) {
            conn.commit();
          }

          Map<String, Object> output = new HashMap<>();
          output.put("outValues", outValues);
          output.put("resultSets", resultSets);
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
      } catch (SQLException | RuntimeException ex) {
        if (!inv.autoCommit) {
          try {
            conn.rollback();
          } catch (SQLException rollbackEx) {
            log.warn("rollback failed: {}", rollbackEx.getMessage());
          }
        }
        throw ex;
      } finally {
        try {
          conn.setAutoCommit(originalAutoCommit);
        } catch (SQLException restoreEx) {
          log.warn("restore autoCommit failed: {}", restoreEx.getMessage());
        }
      }
    } catch (SQLException ex) {
      return TaskResult.fail("stored proc call failed: " + ex.getMessage(), ex);
    }
  }

  private List<Map<String, Object>> readRefCursor(ResultSet rs) throws SQLException {
    List<Map<String, Object>> rows = new ArrayList<>();
    try (ResultSet r = rs) {
      ResultSetMetaData md = r.getMetaData();
      int cols = md.getColumnCount();
      while (r.next()) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 1; i <= cols; i++) {
          row.put(md.getColumnLabel(i), r.getObject(i));
        }
        rows.add(row);
      }
    }
    return rows;
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
