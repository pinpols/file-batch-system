package com.example.batch.worker.exports.sql;

import com.example.batch.worker.exports.config.SqlTemplateExportSecurityProperties;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectBody;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.SubSelect;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.util.StringUtils;

/**
 * Validates template-provided SELECT SQL using JSqlParser AST analysis.
 *
 * <p>Replaces the previous string-based keyword scanning, which was bypassable via
 * SQL comments, mixed case, or whitespace tricks.
 *
 * <p>Checks performed:
 * <ol>
 *   <li>SQL is parseable and is a SELECT/WITH statement (not DML/DDL)</li>
 *   <li>{@code SELECT *} / {@code SELECT table.*} is forbidden when {@code forbidSelectStar=true}</li>
 *   <li>All table schema references must be in {@code allowedSchemas} (if non-empty)</li>
 *   <li>Required named parameters ({@code :tenantId}, {@code :batchNo} by default) are present</li>
 * </ol>
 */
public class SqlTemplateExportSqlValidator {

    private final SqlTemplateExportSecurityProperties security;

    public SqlTemplateExportSqlValidator(SqlTemplateExportSecurityProperties security) {
        this.security = security;
    }

    /**
     * Validates and returns the normalized (trimmed) SQL.
     *
     * @throws IllegalArgumentException if any check fails
     */
    public String validate(String raw) {
        String sql = raw == null ? "" : raw.trim();
        if (!StringUtils.hasText(sql)) {
            throw new IllegalArgumentException("default_query_sql is blank");
        }

        Statement statement;
        try {
            statement = CCJSqlParserUtil.parse(sql);
        } catch (Exception e) {
            throw new IllegalArgumentException("sql_template_export: SQL parse error — " + e.getMessage(), e);
        }

        if (!(statement instanceof Select)) {
            throw new IllegalArgumentException(
                    "sql_template_export only allows SELECT/WITH queries, got: "
                    + statement.getClass().getSimpleName());
        }

        Select select = (Select) statement;

        if (security != null && security.isForbidSelectStar()) {
            checkNoSelectStar(select);
        }

        if (security != null && security.getAllowedSchemas() != null && !security.getAllowedSchemas().isEmpty()) {
            checkAllowedSchemas(statement, security.getAllowedSchemas());
        }

        checkRequiredParams(sql);

        return sql;
    }

    /**
     * Walks all PlainSelect bodies in the statement and rejects any SELECT * or SELECT table.*.
     */
    private void checkNoSelectStar(Select select) {
        Deque<SelectBody> queue = new ArrayDeque<>();
        // Collect the main body and all WITH-item bodies
        if (select.getSelectBody() != null) {
            queue.add(select.getSelectBody());
        }
        if (select.getWithItemsList() != null) {
            for (WithItem wi : select.getWithItemsList()) {
                if (wi.getSubSelect() != null && wi.getSubSelect().getSelectBody() != null) {
                    queue.add(wi.getSubSelect().getSelectBody());
                }
            }
        }

        while (!queue.isEmpty()) {
            SelectBody body = queue.poll();
            if (body instanceof PlainSelect ps) {
                if (ps.getSelectItems() != null) {
                    for (SelectItem item : ps.getSelectItems()) {
                        if (item instanceof AllColumns || item instanceof AllTableColumns) {
                            throw new IllegalArgumentException(
                                    "sql_template_export forbids SELECT * / SELECT table.*;"
                                    + " enumerate columns explicitly");
                        }
                    }
                }
                // Enqueue subqueries in FROM
                if (ps.getFromItem() instanceof SubSelect sub && sub.getSelectBody() != null) {
                    queue.add(sub.getSelectBody());
                }
                // Enqueue subqueries in JOINs
                if (ps.getJoins() != null) {
                    ps.getJoins().stream()
                            .filter(j -> j.getRightItem() instanceof SubSelect)
                            .map(j -> ((SubSelect) j.getRightItem()).getSelectBody())
                            .filter(b -> b != null)
                            .forEach(queue::add);
                }
            } else if (body instanceof SetOperationList sol) {
                if (sol.getSelects() != null) {
                    queue.addAll(sol.getSelects());
                }
            }
        }
    }

    /**
     * Uses TablesNamesFinder to collect all table names (possibly "schema.table") and
     * verifies each referenced schema is in the allow-list.
     */
    private void checkAllowedSchemas(Statement statement, List<String> allowedSchemas) {
        List<String> tableNames = new TablesNamesFinder().getTableList(statement);
        for (String name : tableNames) {
            int dot = name.indexOf('.');
            if (dot > 0) {
                String schema = name.substring(0, dot).toLowerCase();
                if (!allowedSchemas.contains(schema)) {
                    throw new IllegalArgumentException(
                            "sql_template_export references disallowed schema '" + schema
                            + "' — allowed: " + allowedSchemas);
                }
            }
        }
    }

    private void checkRequiredParams(String sql) {
        List<String> required = security == null ? List.of("tenantId", "batchNo") : security.getRequiredParams();
        for (String param : required) {
            if (!StringUtils.hasText(param)) {
                continue;
            }
            if (!sql.contains(":" + param)) {
                throw new IllegalArgumentException(
                        "sql_template_export must reference named parameter :" + param);
            }
        }
    }
}
