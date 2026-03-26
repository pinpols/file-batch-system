package com.example.batch.worker.exports.plugin;

import com.example.batch.common.plugin.ExportDataContext;
import com.example.batch.common.plugin.ExportDataPlugin;
import com.example.batch.worker.exports.config.SqlTemplateExportSecurityProperties;
import com.example.batch.worker.exports.sql.SqlTemplateExportSpec;
import com.example.batch.worker.exports.sql.SqlTemplateExportSqlValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Export plugin that runs template-provided SELECT SQL (stored in {@code default_query_sql}).
 *
 * <p>Pagination is implemented by wrapping the configured SQL as a CTE and applying keyset paging
 * on a configured cursor column.
 *
 * <p>SQL governance: the base SQL is validated at load time using {@link SqlTemplateExportSqlValidator}
 * (JSqlParser AST, schema whitelist, SELECT * prohibition, required params). When
 * {@code explainCheckEnabled=true}, a {@code EXPLAIN (FORMAT JSON)} is also executed before the
 * first page to guard against accidentally large full-table scans.
 */
@Component
public class SqlTemplateExportDataPlugin implements ExportDataPlugin {

    private static final Logger log = LoggerFactory.getLogger(SqlTemplateExportDataPlugin.class);

    public static final String PLUGIN_ID = "sql_template_export";

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final SqlTemplateExportSecurityProperties security;
    private final SqlTemplateExportSqlValidator sqlValidator;

    public SqlTemplateExportDataPlugin(@Qualifier("exportBusinessDataSource") DataSource businessDataSource,
                                       ObjectMapper objectMapper,
                                       SqlTemplateExportSecurityProperties security) {
        JdbcTemplate template = new JdbcTemplate(businessDataSource);
        template.setQueryTimeout(Math.max(1, security == null ? 30 : security.getQueryTimeoutSeconds()));
        this.jdbc = new NamedParameterJdbcTemplate(template);
        this.objectMapper = objectMapper;
        this.security = security;
        this.sqlValidator = new SqlTemplateExportSqlValidator(security);
    }

    @Override
    public String id() {
        return PLUGIN_ID;
    }

    @Override
    public Map<String, Object> loadBatch(ExportDataContext context) {
        if (context == null || !StringUtils.hasText(context.tenantId()) || !StringUtils.hasText(context.batchNo())) {
            return Map.of();
        }
        // Minimal header row: downstream pipeline requires a non-empty batch map and a batchId (can be synthetic).
        Map<String, Object> batch = new LinkedHashMap<>();
        batch.put("id", 0L);
        batch.put("batch_no", context.batchNo());
        batch.put("tenant_id", context.tenantId());
        batch.put("template_code", context.templateCode());
        return batch;
    }

    @Override
    public DetailPage loadDetailPage(ExportDataContext context, Long batchId, int pageSize, Object cursor) {
        if (context == null || !StringUtils.hasText(context.tenantId()) || !StringUtils.hasText(context.batchNo())) {
            return DetailPage.empty();
        }
        SqlTemplateExportSpec spec = SqlTemplateExportSpec.parse(context.templateConfig(), objectMapper);
        String baseSql = sqlValidator.validate(spec.detailSql());

        int limit = Math.max(1, pageSize);
        if (security != null && security.getMaxPageSize() > 0) {
            limit = Math.min(limit, security.getMaxPageSize());
        }

        Map<String, Object> baseParams = new LinkedHashMap<>();
        baseParams.put("tenantId", context.tenantId());
        baseParams.put("batchNo", context.batchNo());

        // EXPLAIN pre-check on the first page (cursor == null)
        if (cursor == null && security != null && security.isExplainCheckEnabled()) {
            runExplainCheck(baseSql, baseParams, context);
        }

        String cursorIdent = "\"" + spec.cursorColumn() + "\"";
        String sql = """
                WITH base AS (
                %s
                )
                SELECT *
                FROM base
                WHERE (:__cursor IS NULL OR base.%s > :__cursor)
                ORDER BY base.%s ASC
                LIMIT :__limit
                """.formatted(baseSql, cursorIdent, cursorIdent);

        Map<String, Object> params = new LinkedHashMap<>(baseParams);
        params.put("__cursor", cursor);
        params.put("__limit", limit);

        List<Map<String, Object>> rows = jdbc.queryForList(sql, params);
        if (rows.isEmpty()) {
            return DetailPage.empty();
        }

        Object nextCursor = null;
        for (Map<String, Object> row : rows) {
            nextCursor = row.get(spec.cursorColumn());
        }
        return new DetailPage(rows, nextCursor);
    }

    private void runExplainCheck(String baseSql, Map<String, Object> baseParams, ExportDataContext context) {
        String explainSql = "EXPLAIN (FORMAT JSON, ANALYZE FALSE) " + baseSql;
        try {
            List<Map<String, Object>> explainResult = jdbc.queryForList(explainSql, baseParams);
            if (explainResult.isEmpty()) {
                return;
            }
            String jsonText = String.valueOf(explainResult.getFirst().values().iterator().next());
            JsonNode plan = objectMapper.readTree(jsonText);
            JsonNode node = plan.path(0).path("Plan");

            double planCost = node.path("Total Cost").asDouble(-1);
            double estimatedRows = node.path("Plan Rows").asDouble(-1);

            if (security.getMaxEstimatedRows() > 0 && estimatedRows > security.getMaxEstimatedRows()) {
                throw new IllegalStateException(
                        "sql_template_export EXPLAIN estimated rows " + (long) estimatedRows
                        + " exceeds limit " + security.getMaxEstimatedRows()
                        + " for template " + context.templateCode());
            }
            if (security.getMaxPlanCost() > 0 && planCost > security.getMaxPlanCost()) {
                throw new IllegalStateException(
                        "sql_template_export EXPLAIN plan cost " + planCost
                        + " exceeds limit " + security.getMaxPlanCost()
                        + " for template " + context.templateCode());
            }
            log.debug("sql_template_export EXPLAIN check passed: rows={}, cost={}, template={}",
                    (long) estimatedRows, planCost, context.templateCode());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.warn("sql_template_export EXPLAIN check failed unexpectedly (non-fatal), template={}: {}",
                    context.templateCode(), e.getMessage());
        }
    }
}
