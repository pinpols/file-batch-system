package com.example.batch.worker.spi.sql;

import com.example.batch.common.spi.task.BatchTaskExecutor;
import com.example.batch.common.spi.task.ResourceKind;
import com.example.batch.common.spi.task.TaskCapability;
import com.example.batch.common.spi.task.TaskContext;
import com.example.batch.common.spi.task.TaskResult;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/**
 * SQL task SPI 实现 — 在受控 dataSource 上跑 DML/DDL/SELECT 语句,带 statement timeout / DDL 白名单 / 结果截断 /
 * 显式事务。
 *
 * <p>启用方式:{@code batch.worker.executors.sql.enabled=true}(默认 false → bean 不注册)。
 *
 * <p>parameters 协议:
 *
 * <ul>
 *   <li>{@code sql} (required, String):SQL 文本,可含多语句用 `;` 分隔(必须以 `;` 结尾)
 *   <li>{@code dataSourceBean} (optional, String):覆盖配置的 dataSource bean 名
 *   <li>{@code statementTimeoutSeconds} (optional, Long):覆盖默认 statement 超时,只能缩短
 *   <li>{@code autoCommit} (optional, Boolean):覆盖默认事务模式
 * </ul>
 *
 * <p>output 协议(在 {@link TaskResult#output()} 里):
 *
 * <ul>
 *   <li>{@code statementCount} (Integer):执行了多少条语句
 *   <li>{@code totalAffectedRows} (Long):所有 UPDATE/INSERT/DELETE 累计 affected
 *   <li>{@code lastResultSet} (List&lt;Map&gt;):最后一条 SELECT 的结果(截断到 maxResultRows)
 *   <li>{@code lastResultRows} (Integer):最后一条 SELECT 的实际行数
 *   <li>{@code resultTruncated} (Boolean):是否被截断
 *   <li>{@code durationMillis} (Long)
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(
    prefix = "batch.worker.executors.sql",
    name = "enabled",
    havingValue = "true")
public class SqlTaskExecutor implements BatchTaskExecutor {

  static final String PARAM_SQL = "sql";
  static final String PARAM_DS_BEAN = "dataSourceBean";
  static final String PARAM_STMT_TIMEOUT = "statementTimeoutSeconds";
  static final String PARAM_AUTO_COMMIT = "autoCommit";

  // 简化的语句类型识别 — 取首关键字
  private static final Pattern FIRST_KEYWORD =
      Pattern.compile("^\\s*(--[^\\n]*\\n|/\\*.*?\\*/|\\s)*(\\w+)", Pattern.DOTALL);

  private final SqlExecutorProperties props;
  private final BeanFactory beanFactory;
  private final DataSource defaultDataSource;

  public SqlTaskExecutor(
      SqlExecutorProperties props, BeanFactory beanFactory, DataSource defaultDataSource) {
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
        // SELECT 幂等,UPDATE/INSERT/DELETE/DDL 不幂等,无法静态判断 → 保守标 false
        false,
        true,
        props.getDefaultStatementTimeout());
  }

  @Override
  public TaskResult execute(TaskContext ctx) {
    try {
      SqlInvocation inv = parseInvocation(ctx);
      return runStatements(ctx, inv);
    } catch (SqlValidationException ex) {
      return TaskResult.fail(ex.getMessage());
    } catch (RuntimeException ex) {
      log.error(
          "sql executor unexpected error: tenantId={}, jobCode={}",
          ctx.tenantId(),
          ctx.jobCode(),
          ex);
      return TaskResult.fail(ex);
    }
  }

  // ─── parsing + validation ────────────────────────────────────────────────────

  private SqlInvocation parseInvocation(TaskContext ctx) {
    Map<String, Object> params = ctx.parameters();

    Object sqlObj = params.get(PARAM_SQL);
    if (!(sqlObj instanceof String) || ((String) sqlObj).isBlank()) {
      throw new SqlValidationException("parameters.sql required (non-blank string)");
    }
    String sql = ((String) sqlObj).trim();

    List<String> statements = splitStatements(sql);
    if (statements.isEmpty()) {
      throw new SqlValidationException("no valid SQL statement found");
    }
    if (statements.size() > props.getMaxStatementsPerJob()) {
      throw new SqlValidationException(
          "too many statements: "
              + statements.size()
              + " > maxStatementsPerJob="
              + props.getMaxStatementsPerJob());
    }

    // 类型 + DDL 白名单校验
    for (String stmt : statements) {
      String type = detectStatementType(stmt);
      if (!props.getAllowedStatementTypes().contains(type)) {
        throw new SqlValidationException(
            "statement type "
                + type
                + " not in allowedStatementTypes="
                + props.getAllowedStatementTypes());
      }
      if ("DDL".equals(type)) {
        validateDdlWhitelist(stmt);
      }
    }

    // dataSource
    String dsBeanName = stringParam(params, PARAM_DS_BEAN, props.getDataSourceBeanName());
    DataSource ds =
        dsBeanName == null ? defaultDataSource : beanFactory.getBean(dsBeanName, DataSource.class);

    // timeout(只能缩短)
    int timeoutSec = (int) props.getDefaultStatementTimeout().toSeconds();
    Object t = params.get(PARAM_STMT_TIMEOUT);
    if (t instanceof Number) {
      long requested = ((Number) t).longValue();
      if (requested <= 0) {
        throw new SqlValidationException("statementTimeoutSeconds must be positive");
      }
      if (requested < timeoutSec) {
        timeoutSec = (int) requested;
      }
    }

    boolean autoCommit = props.isDefaultAutoCommit();
    Object ac = params.get(PARAM_AUTO_COMMIT);
    if (ac instanceof Boolean) {
      autoCommit = (Boolean) ac;
    }

    return new SqlInvocation(statements, ds, timeoutSec, autoCommit);
  }

  private static String stringParam(Map<String, Object> p, String key, String fallback) {
    Object v = p.get(key);
    return v instanceof String && !((String) v).isBlank() ? ((String) v).trim() : fallback;
  }

  /** 用 `;` 切语句,跳过引号内 / 注释内 `;`(简化版,不处理嵌套或 dollar-quote)。 */
  static List<String> splitStatements(String sql) {
    List<String> out = new ArrayList<>();
    StringBuilder buf = new StringBuilder();
    boolean inSingleQuote = false;
    boolean inDoubleQuote = false;
    boolean inLineComment = false;
    boolean inBlockComment = false;
    char prev = 0;
    for (int i = 0; i < sql.length(); i++) {
      char c = sql.charAt(i);
      if (inLineComment) {
        if (c == '\n') inLineComment = false;
        buf.append(c);
      } else if (inBlockComment) {
        if (prev == '*' && c == '/') inBlockComment = false;
        buf.append(c);
      } else if (inSingleQuote) {
        if (c == '\'' && prev != '\\') inSingleQuote = false;
        buf.append(c);
      } else if (inDoubleQuote) {
        if (c == '"' && prev != '\\') inDoubleQuote = false;
        buf.append(c);
      } else {
        if (c == '-' && prev == '-') {
          inLineComment = true;
          buf.append(c);
        } else if (c == '*' && prev == '/') {
          inBlockComment = true;
          buf.append(c);
        } else if (c == '\'') {
          inSingleQuote = true;
          buf.append(c);
        } else if (c == '"') {
          inDoubleQuote = true;
          buf.append(c);
        } else if (c == ';') {
          String s = buf.toString().trim();
          if (!s.isEmpty()) out.add(s);
          buf.setLength(0);
        } else {
          buf.append(c);
        }
      }
      prev = c;
    }
    String tail = buf.toString().trim();
    if (!tail.isEmpty()) out.add(tail);
    return out;
  }

  static String detectStatementType(String stmt) {
    Matcher m = FIRST_KEYWORD.matcher(stmt);
    if (!m.find()) return "UNKNOWN";
    String kw = m.group(2).toUpperCase(Locale.ROOT);
    return switch (kw) {
      case "SELECT", "WITH", "EXPLAIN", "SHOW" -> "SELECT";
      case "INSERT" -> "INSERT";
      case "UPDATE" -> "UPDATE";
      case "DELETE" -> "DELETE";
      case "MERGE", "UPSERT" -> "UPSERT";
      case "CALL", "EXEC", "EXECUTE" -> "CALL";
      case "CREATE",
          "ALTER",
          "DROP",
          "TRUNCATE",
          "RENAME",
          "COMMENT",
          "GRANT",
          "REVOKE",
          "ANALYZE",
          "VACUUM" ->
          "DDL";
      case "BEGIN", "COMMIT", "ROLLBACK", "SAVEPOINT", "SET" -> "TX";
      default -> "OTHER";
    };
  }

  private void validateDdlWhitelist(String stmt) {
    if (props.getDdlWhitelist().isEmpty()) {
      throw new SqlValidationException("DDL not allowed (ddlWhitelist empty)");
    }
    String upper = stmt.toUpperCase(Locale.ROOT);
    for (String allowed : props.getDdlWhitelist()) {
      if (upper.contains(allowed.toUpperCase(Locale.ROOT))) {
        return;
      }
    }
    throw new SqlValidationException("DDL not in ddlWhitelist: " + summarize(stmt));
  }

  private static String summarize(String s) {
    return s.length() <= 80 ? s : s.substring(0, 80) + "...";
  }

  // ─── execution ──────────────────────────────────────────────────────────────

  private TaskResult runStatements(TaskContext ctx, SqlInvocation inv) {
    long start = System.currentTimeMillis();
    long totalAffected = 0;
    List<Map<String, Object>> lastResultSet = List.of();
    int lastResultRows = 0;
    boolean truncated = false;

    try (Connection conn = inv.dataSource.getConnection()) {
      boolean originalAutoCommit = conn.getAutoCommit();
      try {
        conn.setAutoCommit(inv.autoCommit);

        for (String sql : inv.statements) {
          try (Statement stmt = conn.createStatement()) {
            stmt.setQueryTimeout(inv.timeoutSec);
            boolean hasResultSet = stmt.execute(sql);
            if (hasResultSet) {
              try (ResultSet rs = stmt.getResultSet()) {
                FetchResult fr = fetchResults(rs);
                lastResultSet = fr.rows;
                lastResultRows = fr.actualCount;
                truncated = truncated || fr.truncated;
              }
            } else {
              int affected = stmt.getUpdateCount();
              if (affected > 0) {
                totalAffected += affected;
              }
            }
          }
        }

        if (!inv.autoCommit) {
          conn.commit();
        }
      } catch (SQLException | RuntimeException ex) {
        if (!inv.autoCommit) {
          try {
            conn.rollback();
          } catch (SQLException rollbackEx) {
            log.warn("sql rollback failed: {}", rollbackEx.getMessage());
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
      return TaskResult.fail("sql failed: " + ex.getMessage(), ex);
    }

    Map<String, Object> output = new HashMap<>();
    output.put("statementCount", inv.statements.size());
    output.put("totalAffectedRows", totalAffected);
    output.put("lastResultRows", lastResultRows);
    output.put("resultTruncated", truncated);
    output.put("durationMillis", System.currentTimeMillis() - start);
    if (props.isIncludeResultSet()) {
      output.put("lastResultSet", lastResultSet);
    }
    return TaskResult.ok(
        "executed " + inv.statements.size() + " statement(s), affected=" + totalAffected, output);
  }

  private FetchResult fetchResults(ResultSet rs) throws SQLException {
    ResultSetMetaData md = rs.getMetaData();
    int colCount = md.getColumnCount();
    List<Map<String, Object>> rows = new ArrayList<>();
    int actual = 0;
    boolean trunc = false;
    while (rs.next()) {
      actual++;
      if (rows.size() < props.getMaxResultRows()) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 1; i <= colCount; i++) {
          row.put(md.getColumnLabel(i), rs.getObject(i));
        }
        rows.add(row);
      } else if (!trunc) {
        log.warn("sql resultSet truncated at maxResultRows={}", props.getMaxResultRows());
        trunc = true;
        // 继续 next() 数行数但不存(给业务知真实大小)
      }
    }
    return new FetchResult(rows, actual, trunc);
  }

  // ─── helper records / exceptions ────────────────────────────────────────────

  private record SqlInvocation(
      List<String> statements, DataSource dataSource, int timeoutSec, boolean autoCommit) {}

  private record FetchResult(List<Map<String, Object>> rows, int actualCount, boolean truncated) {}

  static final class SqlValidationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    SqlValidationException(String message) {
      super(message);
    }
  }

  @Configuration
  @EnableConfigurationProperties(SqlExecutorProperties.class)
  static class PropertiesConfig {}
}
