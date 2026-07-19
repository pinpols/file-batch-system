package io.github.pinpols.batch.orchestrator.application.service.dryrun;

import io.github.pinpols.batch.common.utils.Texts;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.Select;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

/** L3 SQL 计划探测，负责单语句白名单和只读 EXPLAIN 防护。 */
final class DryRunSqlProbe {

  private static final String SCOPE_EXECUTION = "execution";
  private static final Set<String> SQL_PARAM_KEYS =
      Set.of("sql", "querySql", "sourceQuery", "validationSql", "selectSql");
  private static final Pattern EXPLAIN_PREFIX =
      Pattern.compile("^\\s*EXPLAIN\\b", Pattern.CASE_INSENSITIVE);
  private static final Pattern SELECT_OR_WITH_PREFIX =
      Pattern.compile("^\\s*(SELECT|WITH)\\b", Pattern.CASE_INSENSITIVE);

  private final ObjectProvider<JdbcTemplate> jdbcTemplateProvider;

  DryRunSqlProbe(ObjectProvider<JdbcTemplate> jdbcTemplateProvider) {
    this.jdbcTemplateProvider = jdbcTemplateProvider;
  }

  int probe(Map<String, Object> params, List<DryRunFinding> findings) {
    JdbcTemplate jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
    if (jdbcTemplate == null) {
      return 0;
    }
    int probed = 0;
    for (String key : SQL_PARAM_KEYS) {
      Object raw = params.get(key);
      if (!(raw instanceof String sql) || !Texts.hasText(sql)) {
        continue;
      }
      probed++;
      probeSingleSql(key, sql.trim(), jdbcTemplate, findings);
    }
    return probed;
  }

  private void probeSingleSql(
      String key, String sql, JdbcTemplate jdbcTemplate, List<DryRunFinding> findings) {
    if (EXPLAIN_PREFIX.matcher(sql).find()) {
      findings.add(
          DryRunFinding.error(
              "EXEC_SQL_EXPLAIN_REJECTED",
              SCOPE_EXECUTION,
              "payload starts with EXPLAIN — refusing to nest a second EXPLAIN; submit raw SELECT",
              key));
      return;
    }
    if (!SELECT_OR_WITH_PREFIX.matcher(sql).find()) {
      findings.add(
          DryRunFinding.error(
              "EXEC_SQL_NON_SELECT_REJECTED",
              SCOPE_EXECUTION,
              "dry-run SQL probe only accepts SELECT / WITH statements; refusing to EXPLAIN a"
                  + " DML/DDL payload",
              key));
      return;
    }
    SingleSelectCheck check = classifySingleSelect(sql);
    if (check == SingleSelectCheck.UNPARSEABLE) {
      findings.add(
          DryRunFinding.error(
              "EXEC_SQL_UNPARSEABLE_REJECTED",
              SCOPE_EXECUTION,
              "dry-run SQL probe could not be parsed to verify single-statement safety; refusing"
                  + " to EXPLAIN via simple-query (may be valid PG-specific syntax jsqlparser"
                  + " cannot parse — submit a parseable single SELECT/WITH)",
              key));
      return;
    }
    if (check == SingleSelectCheck.MULTI_OR_NON_SELECT) {
      findings.add(
          DryRunFinding.error(
              "EXEC_SQL_MULTISTATEMENT_REJECTED",
              SCOPE_EXECUTION,
              "dry-run SQL probe must be exactly one SELECT/WITH statement; refusing to EXPLAIN a"
                  + " multi-statement / stacked-query payload",
              key));
      return;
    }
    try {
      jdbcTemplate.execute("EXPLAIN (ANALYZE FALSE, COSTS FALSE) " + sql);
      findings.add(
          DryRunFinding.pass("EXEC_SQL_EXPLAIN_OK", SCOPE_EXECUTION, "EXPLAIN passed for " + key));
    } catch (RuntimeException ex) {
      findings.add(
          DryRunFinding.error(
              "EXEC_SQL_EXPLAIN_FAILED",
              SCOPE_EXECUTION,
              "EXPLAIN failed for " + key + ": " + ex.getMessage(),
              key));
    }
  }

  private static SingleSelectCheck classifySingleSelect(String sql) {
    Statements statements;
    try {
      statements = CCJSqlParserUtil.parseStatements(sql);
    } catch (Exception ex) {
      return SingleSelectCheck.UNPARSEABLE;
    }
    List<Statement> list = statements.getStatements();
    if (list != null && list.size() == 1 && list.get(0) instanceof Select) {
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
