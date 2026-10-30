package io.github.pinpols.batch.orchestrator.infrastructure.dryrun;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.application.service.dryrun.DryRunFinding;
import io.github.pinpols.batch.orchestrator.application.service.dryrun.DryRunSqlProbe;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.Select;
import org.springframework.stereotype.Component;

/** L3 SQL 静态检查；数据库计划探测停用以避免规划阶段的副作用。 */
@Component
public final class JdbcDryRunSqlProbe implements DryRunSqlProbe {

  private static final String SCOPE_EXECUTION = "execution";
  private static final Set<String> SQL_PARAM_KEYS =
      Set.of("sql", "querySql", "sourceQuery", "validationSql", "selectSql");
  private static final Pattern EXPLAIN_PREFIX =
      Pattern.compile("^\\s*EXPLAIN\\b", Pattern.CASE_INSENSITIVE);
  private static final Pattern SELECT_OR_WITH_PREFIX =
      Pattern.compile("^\\s*(SELECT|WITH)\\b", Pattern.CASE_INSENSITIVE);

  @Override
  public int probe(Map<String, Object> params, List<DryRunFinding> findings) {
    int probed = 0;
    for (String key : SQL_PARAM_KEYS) {
      Object raw = params.get(key);
      if (!(raw instanceof String sql) || !Texts.hasText(sql)) {
        continue;
      }
      probed++;
      probeSingleSql(key, sql.trim(), findings);
    }
    return probed;
  }

  private void probeSingleSql(String key, String sql, List<DryRunFinding> findings) {
    if (EXPLAIN_PREFIX.matcher(sql).find()) {
      findings.add(DryRunFinding.error(
          "EXEC_SQL_EXPLAIN_REJECTED",
          SCOPE_EXECUTION,
          "EXPLAIN input is not accepted; submit a raw SELECT for static validation",
          key));
      return;
    }
    if (!SELECT_OR_WITH_PREFIX.matcher(sql).find()) {
      findings.add(DryRunFinding.error(
          "EXEC_SQL_NON_SELECT_REJECTED",
          SCOPE_EXECUTION,
          "dry-run SQL static validation only accepts SELECT / WITH statements; DML/DDL rejected",
          key));
      return;
    }
    SingleSelectCheck check = classifySingleSelect(sql);
    if (check == SingleSelectCheck.UNPARSEABLE) {
      findings.add(DryRunFinding.error(
          "EXEC_SQL_UNPARSEABLE_REJECTED",
          SCOPE_EXECUTION,
          "dry-run SQL could not be parsed for static validation; submit a parseable single"
              + " SELECT/WITH (some valid PostgreSQL syntax may not be supported)",
          key));
      return;
    }
    if (check == SingleSelectCheck.MULTI_OR_NON_SELECT) {
      findings.add(DryRunFinding.error(
          "EXEC_SQL_MULTISTATEMENT_REJECTED",
          SCOPE_EXECUTION,
          "dry-run SQL static validation requires exactly one SELECT/WITH statement;"
              + " multi-statement or non-SELECT input rejected",
          key));
      return;
    }
    // PostgreSQL 可在 EXPLAIN 规划阶段执行函数，不能保证无副作用。
    findings.add(DryRunFinding.error(
        "EXEC_SQL_PLAN_UNAVAILABLE",
        SCOPE_EXECUTION,
        "SQL syntax checked; database execution plan unavailable because EXPLAIN is disabled"
            + " to prevent planning-time side effects",
        key));
  }

  private static SingleSelectCheck classifySingleSelect(String sql) {
    Statements statements;
    try {
      statements = CCJSqlParserUtil.parseStatements(sql);
    } catch (Exception ex) {
      return SingleSelectCheck.UNPARSEABLE;
    }
    List<Statement> list = statements;
    boolean containsExactlyOneSelect =
        EmptyChecks.isNotNull(list) && list.size() == 1 && list.get(0) instanceof Select;
    if (containsExactlyOneSelect) {
      return SingleSelectCheck.SINGLE_SELECT;
    }
    return SingleSelectCheck.MULTI_OR_NON_SELECT;
  }

  private enum SingleSelectCheck {
    SINGLE_SELECT,
    MULTI_OR_NON_SELECT,
    UNPARSEABLE
  }
}
