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
 * 执行模板配置的 SELECT SQL（存储于 {@code default_query_sql}）的导出插件。
 *
 * <p>分页通过将配置 SQL 包装为 CTE 并按配置的游标列进行 keyset 分页实现。
 *
 * <p>SQL 治理：基础 SQL 在加载时经由 {@link SqlTemplateExportSqlValidator}
 * 验证（JSqlParser AST、schema 白名单、禁止 SELECT *、必填参数）。
 * 当 {@code explainCheckEnabled=true} 时，首页前还会执行 {@code EXPLAIN (FORMAT JSON)} 防止全表扫描。
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
        // 最小头部行：下游 pipeline 要求非空 batch map 及 batchId（可以是合成值）。
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

        // 首页（cursor == null）时执行 EXPLAIN 预检
        if (cursor == null && security != null && security.isExplainCheckEnabled()) {
            runExplainCheck(baseSql, baseParams, context);
        }

        String sql = buildPagedSql(baseSql, spec.cursorColumn(), cursor != null);

        Map<String, Object> params = new LinkedHashMap<>(baseParams);
        if (cursor != null) {
            params.put("__cursor", cursor);
        }
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

    static String buildPagedSql(String baseSql, String cursorColumn, boolean hasCursor) {
        String cursorIdent = "\"" + cursorColumn + "\"";
        String whereClause = hasCursor ? "WHERE base.%s > :__cursor%n".formatted(cursorIdent) : "";
        return """
                WITH base AS (
                %s
                )
                SELECT *
                FROM base
                %sORDER BY base.%s ASC
                LIMIT :__limit
                """.formatted(baseSql, whereClause, cursorIdent);
    }
}
