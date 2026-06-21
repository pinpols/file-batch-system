package com.example.batch.worker.atomic.sql;

import com.example.batch.common.exception.BizException;
import com.example.batch.common.security.SensitiveDataValidator;
import com.example.batch.common.spi.task.BatchTaskExecutor;
import com.example.batch.common.spi.task.ResourceKind;
import com.example.batch.common.spi.task.TaskCapability;
import com.example.batch.common.spi.task.TaskContext;
import com.example.batch.common.spi.task.TaskResult;
import com.example.batch.worker.atomic.runtime.AtomicConnectionManager;
import com.example.batch.worker.atomic.runtime.AtomicErrorCode;
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
import java.util.Set;
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
        Set.of(ResourceKind.DB),
        // SELECT 幂等,UPDATE/INSERT/DELETE/DDL 不幂等,无法静态判断 → 保守标 false
        false,
        true,
        props.getDefaultStatementTimeout());
  }

  @Override
  public TaskResult execute(TaskContext ctx) {
    try {
      SensitiveDataValidator.rejectIfContainsSensitiveKeys(
          ctx.parameters(), "atomic.sql.parameters");
      SqlInvocation inv = parseInvocation(ctx);
      if (ctx.isDryRun()) {
        return buildDryRunResult(ctx, inv);
      }
      return runStatements(ctx, inv);
    } catch (SqlValidationException ex) {
      // SqlValidationException 同时承担「输入非法」和「OS-capable role 闸拒绝」语义,后者走 SECURITY_REJECTED
      boolean security = ex.getMessage() != null && ex.getMessage().contains("OS-capable");
      return AtomicErrorCode.fail(
          security ? AtomicErrorCode.SECURITY_REJECTED : AtomicErrorCode.CONFIG_INVALID,
          ex.getMessage());
    } catch (BizException ex) {
      log.warn(
          "sql executor rejected by SensitiveDataValidator: tenantId={}, jobCode={}, key={}",
          ctx.tenantId(),
          ctx.jobCode(),
          ex.getMessageArgs() == null || ex.getMessageArgs().length < 2
              ? "?"
              : ex.getMessageArgs()[1]);
      return AtomicErrorCode.fail(
          AtomicErrorCode.SECURITY_REJECTED, "SENSITIVE_DATA_IN_PARAMETERS: " + ex.getMessage());
    } catch (RuntimeException ex) {
      log.error(
          "sql executor unexpected error: tenantId={}, jobCode={}",
          ctx.tenantId(),
          ctx.jobCode(),
          ex);
      return AtomicErrorCode.fail(
          AtomicErrorCode.EXECUTION_FAILED,
          ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(),
          ex);
    }
  }

  /**
   * ADR-026 §dry-run:演练模式不发 SQL / 不开事务 / 不写库,返回解析后的"会执行什么"。 仅暴露 SQL 文本 + dataSource bean 名(Spring
   * 标识,非凭据)+ 事务/超时参数;不进任何 row-level data。
   */
  private TaskResult buildDryRunResult(TaskContext ctx, SqlInvocation inv) {
    String dsBean = resolveDataSourceBeanName(ctx.parameters());
    Map<String, Object> planned = new LinkedHashMap<>();
    planned.put("dryRun", true);
    planned.put("plannedAction", "sql");
    planned.put("statementCount", inv.statements.size());
    planned.put("statements", inv.statements);
    planned.put("dataSourceBean", dsBean == null ? "<default>" : dsBean);
    planned.put("autoCommit", inv.autoCommit);
    planned.put("readOnly", inv.allSelect);
    planned.put("statementTimeoutSeconds", inv.timeoutSec);
    log.info(
        "sql executor dry-run skipped real execution: tenantId={}, jobCode={}, statements={}",
        ctx.tenantId(),
        ctx.jobCode(),
        inv.statements.size());
    return TaskResult.ok(
        "dry-run: parsed " + inv.statements.size() + " statement(s), not executed", planned);
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

    // 三道闸模型:语句类型/DDL 不再由 app 白名单管控,放行范围 = 所连最小权限 DB 角色被授予的权限;
    // OS 拒绝由 requireNonOsCapableRole(连接级角色闸)回退。

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

  /**
   * 用 `;` 切语句,跳过引号内 / 注释内 / PG dollar-quote 体内的 `;`。dollar-quote(<code>$$...$$</code> / <code>
   * $tag$...$tag$</code>)体内含 `;` 不再被误切(PG 函数体、含分号的字面量常用),修正原"不处理 dollar-quote" 导致的
   * maxStatementsPerJob / 只读判定偏差。
   */
  static List<String> splitStatements(String sql) {
    SplitScanner sc = new SplitScanner();
    char prev = 0;
    int i = 0;
    int n = sql.length();
    while (i < n) {
      char c = sql.charAt(i);
      // 顶层(非引号/注释内)的 dollar-quote 开标签:整段(含开/闭标签)一次性消费,体内 ; 引号 注释全字面
      if (sc.atTopLevel() && c == '$') {
        String openTag = matchDollarTag(sql, i);
        if (openTag != null) {
          i = consumeDollarQuote(sql, i, openTag, sc.buf);
          prev = '$';
          continue;
        }
      }
      sc.feed(c, prev);
      prev = c;
      i++;
    }
    return sc.finish();
  }

  /** {@link #splitStatements} 的逐字符状态机,把引号/注释/分号状态与 buffer 封装,避免单方法 NCSS 过高。 */
  private static final class SplitScanner {
    private final List<String> out = new ArrayList<>();
    private final StringBuilder buf = new StringBuilder();
    private boolean inSingleQuote;
    private boolean inDoubleQuote;
    private boolean inLineComment;
    private boolean inBlockComment;

    boolean atTopLevel() {
      return !inSingleQuote && !inDoubleQuote && !inLineComment && !inBlockComment;
    }

    void feed(char c, char prev) {
      if (inLineComment) {
        if (c == '\n') inLineComment = false;
      } else if (inBlockComment) {
        if (prev == '*' && c == '/') inBlockComment = false;
      } else if (inSingleQuote) {
        if (c == '\'' && prev != '\\') inSingleQuote = false;
      } else if (inDoubleQuote) {
        if (c == '"' && prev != '\\') inDoubleQuote = false;
      } else if (feedTopLevel(c, prev)) {
        return; // 分号已切分,不入 buffer
      }
      buf.append(c);
    }

    /** 顶层字符:开注释/开引号置位;遇 `;` 切分并返回 true(调用方不再 append)。 */
    private boolean feedTopLevel(char c, char prev) {
      if (c == '-' && prev == '-') {
        inLineComment = true;
      } else if (c == '*' && prev == '/') {
        inBlockComment = true;
      } else if (c == '\'') {
        inSingleQuote = true;
      } else if (c == '"') {
        inDoubleQuote = true;
      } else if (c == ';') {
        flush();
        return true;
      }
      return false;
    }

    private void flush() {
      String s = buf.toString().trim();
      if (!s.isEmpty()) out.add(s);
      buf.setLength(0);
    }

    List<String> finish() {
      flush();
      return out;
    }
  }

  /**
   * 在 {@code pos}(此处字符为 {@code $})尝试匹配 PG dollar-quote 开标签 {@code $tag$}: tag 为空或 {@code
   * [A-Za-z_][A-Za-z0-9_]*}。返回含两端 {@code $} 的完整标签串(如 {@code $$} / {@code $body$}); 不是合法
   * dollar-quote(如位置参数 {@code $1}、运算)返回 null。
   */
  /** 从 dollar-quote 开标签处把整段(含开/闭标签)写入 {@code buf},返回闭标签之后的索引; 未闭合(到串尾仍无闭标签)则全部写入并返回串尾。 */
  private static int consumeDollarQuote(String sql, int start, String tag, StringBuilder buf) {
    buf.append(tag);
    int i = start + tag.length();
    int n = sql.length();
    while (i < n) {
      if (sql.charAt(i) == '$' && sql.startsWith(tag, i)) {
        buf.append(tag);
        return i + tag.length();
      }
      buf.append(sql.charAt(i));
      i++;
    }
    return n;
  }

  private static String matchDollarTag(String sql, int pos) {
    int n = sql.length();
    for (int j = pos + 1; j < n; j++) {
      char c = sql.charAt(j);
      if (c == '$') {
        String tag = sql.substring(pos + 1, j);
        return (tag.isEmpty() || isValidDollarTag(tag)) ? sql.substring(pos, j + 1) : null;
      }
      if (!(Character.isLetterOrDigit(c) || c == '_')) {
        return null; // 标签里出现非法字符(空格/运算符)→ 普通 $,非 dollar-quote
      }
    }
    return null;
  }

  private static boolean isValidDollarTag(String tag) {
    if (Character.isDigit(tag.charAt(0))) {
      return false; // 标识符不能以数字开头($1 是位置参数,不是 dollar-quote)
    }
    for (int k = 0; k < tag.length(); k++) {
      char c = tag.charAt(k);
      if (!(Character.isLetterOrDigit(c) || c == '_')) {
        return false;
      }
    }
    return true;
  }

  /** 取首关键字粗分类语句类型。三道闸模型下<b>不再做类型白名单校验</b>,本方法仅用于判定"是否全是 SELECT"以走只读事务 + statement_timeout 优化。 */
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

  /**
   * 代码层堵 OS 的硬保证:拒绝以 OS 能力角色执行 —— superuser 或 {@code pg_execute_server_program} / {@code
   * pg_read_server_files} / {@code pg_write_server_files} 成员(COPY PROGRAM / 服务端文件 / 不可信 PL 的前置)。
   *
   * <p><b>方言支持</b>:本检查仅对 PostgreSQL 有效(查 {@code pg_roles} / {@code pg_has_role()})。其他方言上若仍开 {@code
   * forbidOsCapableRole=true},本方法 <b>fail-closed 拒绝执行</b>——既然运维显式要求"禁 OS 能力角色"、而本闸在该方言下无法核验,
   * 静默放行等于安全控制 no-op(原 WARN+放行的"假阴性"姿态比报错更危险)。要在非 PG 方言上跑,须显式 {@code
   * forbidOsCapableRole=false}(明确接受该方言下无 OS 角色核验)并自行用方言原生最小权限角色回退。
   *
   * <p>见 {@link SqlExecutorProperties#isForbidOsCapableRole()} javadoc 标注「PostgreSQL only」。
   */
  void requireNonOsCapableRole(Connection conn) {
    String productName;
    try {
      productName = conn.getMetaData().getDatabaseProductName();
    } catch (SQLException ex) {
      throw new SqlValidationException(
          "OS-capable role check failed reading DatabaseMetaData: " + ex.getMessage());
    }
    if (productName == null || !productName.toLowerCase(Locale.ROOT).contains("postgres")) {
      throw new SqlValidationException(
          "forbidOsCapableRole=true but OS-capable-role check is PostgreSQL-only and cannot be"
              + " verified on dialect '"
              + productName
              + "' — refusing to execute (fail-closed). Run on PostgreSQL, or explicitly set"
              + " batch.worker.executors.sql.forbid-os-capable-role=false and enforce a"
              + " dialect-native least-privilege role.");
    }
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
    AtomicConnectionManager.Options opts =
        AtomicConnectionManager.Options.defaults()
            .withAutoCommit(effectiveAutoCommit)
            .withReadOnly(inv.allSelect)
            // 角色闸用 executor 本地实现(error message + 异常类型保留 i18n / SqlValidationException 语义),
            // 不走 manager 的 SecurityException 实现。
            .withForbidOsCapableRole(false);

    try {
      AtomicConnectionManager.withConnection(
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
      // PG statement_timeout → SQLState "57014" (query_canceled);其它驱动语义不同,这里做最常见识别
      boolean isTimeout =
          "57014".equals(ex.getSQLState())
              || (ex.getMessage() != null
                  && ex.getMessage().toLowerCase(Locale.ROOT).contains("timeout"));
      return AtomicErrorCode.fail(
          isTimeout ? AtomicErrorCode.TIMEOUT : AtomicErrorCode.EXECUTION_FAILED,
          "sql failed: " + ex.getMessage(),
          ex);
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
