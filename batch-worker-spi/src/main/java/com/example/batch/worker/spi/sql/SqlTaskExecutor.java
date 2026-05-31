package com.example.batch.worker.spi.sql;

import com.example.batch.common.spi.task.BatchTaskExecutor;
import com.example.batch.common.spi.task.ResourceKind;
import com.example.batch.common.spi.task.TaskCapability;
import com.example.batch.common.spi.task.TaskContext;
import com.example.batch.common.spi.task.TaskResult;
import com.example.batch.worker.spi.runtime.SpiConnectionManager;
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

    // dataSource(param 覆盖需命中 allowedDataSourceBeans)
    String dsBeanName = resolveDataSourceBeanName(params);
    DataSource ds =
        dsBeanName == null ? defaultDataSource : beanFactory.getBean(dsBeanName, DataSource.class);

    boolean allSelect = statements.stream().allMatch(s -> "SELECT".equals(detectStatementType(s)));

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

    return new SqlInvocation(statements, ds, timeoutSec, autoCommit, allSelect);
  }

  private static String stringParam(Map<String, Object> p, String key, String fallback) {
    Object v = p.get(key);
    return v instanceof String && !((String) v).isBlank() ? ((String) v).trim() : fallback;
  }

  /**
   * 解析最终要用的 dataSource bean 名,并对 param {@code dataSourceBean} 覆盖做白名单校验。
   *
   * <p>规则:param 缺省时回落到 {@link SqlExecutorProperties#getDataSourceBeanName()}(可能 null = 默认库)。 当
   * param 显式给出且与配置的默认 bean 名不同时,必须命中 {@link SqlExecutorProperties#getAllowedDataSourceBeans()},否则抛
   * {@link SqlValidationException}。配置的默认 bean 永远允许。
   *
   * @return 校验后的 bean 名,或 null 表示用注入的默认 dataSource
   */
  String resolveDataSourceBeanName(Map<String, Object> params) {
    String configured = props.getDataSourceBeanName();
    Object v = params.get(PARAM_DS_BEAN);
    String paramBean = v instanceof String && !((String) v).isBlank() ? ((String) v).trim() : null;

    if (paramBean == null || paramBean.equals(configured)) {
      return configured;
    }
    if (!props.getAllowedDataSourceBeans().contains(paramBean)) {
      throw new SqlValidationException(
          "dataSourceBean '"
              + paramBean
              + "' not in allowedDataSourceBeans="
              + props.getAllowedDataSourceBeans());
    }
    return paramBean;
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

  /**
   * 取首关键字粗分类语句类型。
   *
   * <p>PG 的 {@code DO $$...$$} 匿名代码块可执行任意过程化逻辑(含 DML/DDL),归为 {@code "DDL"} 走 DDL 白名单, 避免落到 {@code
   * "OTHER"} 绕过校验。
   *
   * <p><b>安全提示</b>:把 {@code "OTHER"} 放进 {@link SqlExecutorProperties#getAllowedStatementTypes()}
   * 约等于放开任意 SQL(无法识别的语句一律通过),生产慎用。
   */
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
          "VACUUM",
          "DO" ->
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

  /**
   * 代码层堵 OS 的硬保证:拒绝以 OS 能力角色执行 —— superuser 或 {@code pg_execute_server_program} / {@code
   * pg_read_server_files} / {@code pg_write_server_files} 成员(COPY PROGRAM / 服务端文件 / 不可信 PL 的前置)。
   */
  private void requireNonOsCapableRole(Connection conn) {
    String sql =
        "select rolsuper"
            + " or pg_has_role(current_user, 'pg_execute_server_program', 'USAGE')"
            + " or pg_has_role(current_user, 'pg_read_server_files', 'USAGE')"
            + " or pg_has_role(current_user, 'pg_write_server_files', 'USAGE')"
            + " from pg_roles where rolname = current_user";
    try (Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      if (rs.next() && rs.getBoolean(1)) {
        throw new SqlValidationException(
            "refusing SQL on OS-capable DB role (superuser / pg_execute_server_program /"
                + " pg_read_server_files / pg_write_server_files); connect as a least-privilege"
                + " role");
      }
    } catch (SQLException ex) {
      throw new SqlValidationException("OS-capable role check failed: " + ex.getMessage());
    }
  }

  // ─── execution ──────────────────────────────────────────────────────────────

  private TaskResult runStatements(TaskContext ctx, SqlInvocation inv) {
    long start = System.currentTimeMillis();
    ExecResult execResult = new ExecResult();

    // 全 SELECT 时强制显式事务(autoCommit off),使 READ ONLY + SET LOCAL statement_timeout 在整段内生效。
    // 写任务保持原有 autoCommit 语义。
    boolean effectiveAutoCommit = inv.allSelect ? false : inv.autoCommit;
    SpiConnectionManager.Options opts =
        SpiConnectionManager.Options.defaults()
            .withAutoCommit(effectiveAutoCommit)
            .withReadOnly(inv.allSelect)
            // 角色闸用 executor 本地实现(error message + 异常类型保留 i18n / SqlValidationException 语义),
            // 不走 manager 的 SecurityException 实现。
            .withForbidOsCapableRole(false);

    try {
      SpiConnectionManager.withConnection(
          inv.dataSource,
          opts,
          conn -> {
            if (props.isForbidOsCapableRole()) {
              requireNonOsCapableRole(conn);
            }
            if (inv.allSelect) {
              try (Statement guard = conn.createStatement()) {
                guard.execute("SET LOCAL statement_timeout = " + (inv.timeoutSec * 1000L));
              } catch (SQLException setEx) {
                log.warn("SET LOCAL statement_timeout failed: {}", setEx.getMessage());
              }
            }
            for (String sql : inv.statements) {
              try (Statement stmt = conn.createStatement()) {
                stmt.setQueryTimeout(inv.timeoutSec);
                boolean hasResultSet = stmt.execute(sql);
                if (hasResultSet) {
                  try (ResultSet rs = stmt.getResultSet()) {
                    FetchResult fr = fetchResults(rs);
                    execResult.lastResultSet = fr.rows;
                    execResult.lastResultRows = fr.actualCount;
                    execResult.truncated = execResult.truncated || fr.truncated;
                  }
                } else {
                  int affected = stmt.getUpdateCount();
                  if (affected > 0) {
                    execResult.totalAffected += affected;
                  }
                }
              }
            }
            return null;
          });
    } catch (SQLException ex) {
      return TaskResult.fail("sql failed: " + ex.getMessage(), ex);
    }

    Map<String, Object> output = new HashMap<>();
    output.put("statementCount", inv.statements.size());
    output.put("totalAffectedRows", execResult.totalAffected);
    output.put("lastResultRows", execResult.lastResultRows);
    output.put("resultTruncated", execResult.truncated);
    output.put("durationMillis", System.currentTimeMillis() - start);
    if (props.isIncludeResultSet()) {
      output.put("lastResultSet", execResult.lastResultSet);
    }
    return TaskResult.ok(
        "executed " + inv.statements.size() + " statement(s), affected=" + execResult.totalAffected,
        output);
  }

  /** 单次执行结果聚合 — 给 lambda 内闭包写。 */
  private static final class ExecResult {
    long totalAffected;
    List<Map<String, Object>> lastResultSet = List.of();
    int lastResultRows;
    boolean truncated;
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
      List<String> statements,
      DataSource dataSource,
      int timeoutSec,
      boolean autoCommit,
      boolean allSelect) {}

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
