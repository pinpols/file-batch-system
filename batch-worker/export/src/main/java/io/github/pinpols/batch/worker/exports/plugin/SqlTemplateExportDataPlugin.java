package io.github.pinpols.batch.worker.exports.plugin;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

import io.github.pinpols.batch.common.exception.WorkerConfigException;
import io.github.pinpols.batch.common.jdbc.JdbcMappedSqlValidator;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.plugin.ExportDataContext;
import io.github.pinpols.batch.common.plugin.ExportDataPlugin;
import io.github.pinpols.batch.common.plugin.IdempotencyCapability;
import io.github.pinpols.batch.common.plugin.WorkerPluginIds;
import io.github.pinpols.batch.common.rls.RlsTenantSessionSupport;
import io.github.pinpols.batch.common.utils.EmptyValues;
import io.github.pinpols.batch.worker.exports.config.ExportWorkerConfiguration;
import io.github.pinpols.batch.worker.exports.config.SqlTemplateExportSecurityProperties;
import io.github.pinpols.batch.worker.exports.sql.SqlTemplateExportSpec;
import io.github.pinpols.batch.worker.exports.sql.SqlTemplateExportSqlValidator;

/**
 * 执行模板配置的 SELECT SQL（存储于 {@code default_query_sql}）的导出插件。
 *
 * <p>分页通过将配置 SQL 包装为 CTE 并按配置的游标列进行 keyset 分页实现。
 *
 * <p>SQL 治理：基础 SQL 在加载时经由 {@link SqlTemplateExportSqlValidator} 验证（JSqlParser AST、schema 白名单、禁止
 * SELECT *、必填参数）。 当 {@code explainCheckEnabled=true} 时，首页前还会执行 {@code EXPLAIN (FORMAT JSON)}
 * 防止全表扫描。
 */
@Slf4j
@Component
public class SqlTemplateExportDataPlugin implements ExportDataPlugin {

    @Override
    public IdempotencyCapability registrationCallbackIdempotencyCapability() {
        return IdempotencyCapability.IDEMPOTENT_BY_PLUGIN_LOGIC;
    }

    public static final String PLUGIN_ID = WorkerPluginIds.EXPORT_DATA_SQL_TEMPLATE;

    private final NamedParameterJdbcTemplate jdbc;
    private final DataSource businessDataSource;
    private final ObjectMapper objectMapper;
    private final SqlTemplateExportSecurityProperties security;
    private final SqlTemplateExportSqlValidator sqlValidator;
    private final ExportWorkerConfiguration workerConfiguration;

    /** Phase A RLS:read 路径 tx 包 SET LOCAL,触发 biz.* USING 过滤(防跨租户读)。 */
    private final TransactionTemplate txTemplate;

    private final ExportKeysetRangePlanner keysetRangePlanner = new ExportKeysetRangePlanner();

    public SqlTemplateExportDataPlugin(
            @Qualifier("exportBusinessDataSource") DataSource businessDataSource,
            ObjectMapper objectMapper,
            SqlTemplateExportSecurityProperties security,
            ExportWorkerConfiguration workerConfiguration) {
        this.businessDataSource = businessDataSource;
        JdbcTemplate template = new JdbcTemplate(businessDataSource);
        int effectiveQueryTimeoutSeconds = Math.max(1, security.getQueryTimeoutSeconds());
        template.setQueryTimeout(effectiveQueryTimeoutSeconds);
        this.jdbc = new NamedParameterJdbcTemplate(template);
        this.objectMapper = objectMapper;
        this.security = security;
        this.workerConfiguration = workerConfiguration;
        this.sqlValidator = new SqlTemplateExportSqlValidator(security);
        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(businessDataSource);
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.txTemplate.setReadOnly(true);
    }

    @Override
    public String id() {
        return PLUGIN_ID;
    }

    @Override
    public Map<String, Object> loadBatch(ExportDataContext context) {
        if (context == null || !EmptyValues.hasText(context.tenantId()) || !EmptyValues.hasText(context.batchNo())) {
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
    @SuppressWarnings("java:S2077")
    public DetailPage loadDetailPage(ExportDataContext context, Long batchId, int pageSize, Object cursor) {
        if (context == null || !EmptyValues.hasText(context.tenantId()) || !EmptyValues.hasText(context.batchNo())) {
            return DetailPage.empty();
        }
        SqlTemplateExportSpec spec = SqlTemplateExportSpec.parse(context.templateConfig(), objectMapper);
        String baseSql = sqlValidator.validate(spec.detailSql());
        boolean composite = spec.tiebreakerColumnIsConfigured();
        List<Object> cursorValues = splitCursor(cursor);
        requireCursorShape(spec, cursorValues, composite);

        int limit = Math.max(1, pageSize);
        if (security.getMaxPageSize() > 0) {
            limit = Math.min(limit, security.getMaxPageSize());
        }

        Map<String, Object> baseParams = new LinkedHashMap<>();
        baseParams.put("tenantId", context.tenantId());
        baseParams.put("batchNo", context.batchNo());
        // 地区过滤(per-run):PrepareStep 已解析并注入 exportSnapshot,模板 SQL 可声明 `WHERE region = :region`。
        // 始终绑定(即使 null):模板引用 :region 时缺参会让 NamedParameterJdbcTemplate 抛 No value for parameter。
        // region 由 allowedRegions 字典在 PREPARE 阶段把关,这里仅透传不再校验。
        baseParams.put("region", regionFromSnapshot(context));

        boolean firstPage = EmptyValues.isEmpty(cursorValues);
        // 首页（cursor == null）时执行 EXPLAIN 预检
        boolean shouldRunExplainCheck = firstPage && security.isExplainCheckEnabled();
        if (shouldRunExplainCheck) {
            runExplainCheck(baseSql, baseParams, context);
        }
        if (firstPage) {
            // 排序键含 NULL 的行会被 keyset/哈希分片谓词静默过滤;检查不受当前 partition 谓词限制。
            String nullProbeSql = buildNullSortValueProbeSql(baseSql, spec);
            List<Map<String, Object>> nullSortValues = txTemplate.execute(status -> {
                RlsTenantSessionSupport.applyIfPresent(businessDataSource);
                return jdbc.queryForList(nullProbeSql, baseParams);
            });
            if (!EmptyValues.isEmpty(nullSortValues)) {
                throw new WorkerConfigException(
                        "sql_template_export sort column is NULL for the template query result (cursorColumn="
                                + spec.cursorColumn()
                                + (spec.tiebreakerColumnIsConfigured()
                                        ? ", cursorTiebreakerColumn=" + spec.cursorTiebreakerColumn()
                                        : "")
                                + "); keyset pagination would silently drop those rows.");
            }
        }

        ExportKeysetRange keysetRange =
                keysetRangePlanner.resolve(context, () -> minMax(baseSql, spec.cursorColumn(), baseParams));
        String sql =
                buildPagedSql(baseSql, spec.cursorColumn(), spec.cursorTiebreakerColumn(), !firstPage, keysetRange);

        Map<String, Object> params = new LinkedHashMap<>(baseParams);
        bindCursor(params, spec, cursorValues);
        // 多取一行: 最后一页仍是满页时用来证明下一页起点不会跳过同排序值的行。
        int fetchLimit = limit == Integer.MAX_VALUE ? limit : limit + 1;
        params.put("__limit", fetchLimit);
        if (keysetRange.active()) {
            params.put("__loN", keysetRange.loN());
            params.put("__hiN", keysetRange.hiN());
        }

        final String finalSql = sql;
        final Map<String, Object> finalParams = params;
        int fetchSize = resolveFetchSize(context);
        final NamedParameterJdbcTemplate pageJdbc = namedJdbc(fetchSize);
        List<Map<String, Object>> rows = txTemplate.execute(status -> {
            RlsTenantSessionSupport.applyIfPresent(businessDataSource);
            return pageJdbc.queryForList(finalSql, finalParams);
        });
        if (EmptyValues.isEmpty(rows)) {
            return DetailPage.empty();
        }
        boolean hasOverflowRow = rows.size() > limit;
        List<Object> lastTuple = cursorTuple(rows.get(hasOverflowRow ? limit - 1 : rows.size() - 1), spec);
        if (hasOverflowRow) {
            List<Object> overflowTuple = cursorTuple(rows.get(limit), spec);
            requirePageBoundaryIsUnique(spec, lastTuple, overflowTuple);
            rows = new ArrayList<>(rows.subList(0, limit));
        }
        Object nextCursor = composite ? lastTuple : lastTuple.getFirst();
        return new DetailPage(rows, nextCursor);
    }

    private static List<Object> splitCursor(Object cursor) {
        if (cursor == null) {
            return List.of();
        }
        if (cursor instanceof List<?> values) {
            return List.copyOf(values);
        }
        return List.of(cursor);
    }

    /** 位点形状必须与当前冻结配置一致; 排序语义变化后旧的单列位点不能继续使用。 */
    private static void requireCursorShape(SqlTemplateExportSpec spec, List<Object> cursorValues, boolean composite) {
        if (EmptyValues.isEmpty(cursorValues) || cursorValues.size() == (composite ? 2 : 1)) {
            return;
        }
        throw new WorkerConfigException("sql_template_export cursor does not match the configured sort key: expected "
                + (composite ? "(cursorColumn, cursorTiebreakerColumn)" : "cursorColumn")
                + " but the stored GENERATE position has "
                + cursorValues.size()
                + " value(s). Start a new run instead of resuming this one.");
    }

    private static void bindCursor(Map<String, Object> params, SqlTemplateExportSpec spec, List<Object> cursorValues) {
        if (EmptyValues.isEmpty(cursorValues)) {
            return;
        }
        if (!spec.tiebreakerColumnIsConfigured()) {
            params.put("__cursor", cursorValues.getFirst());
            return;
        }
        params.put("__cursor0", cursorValues.get(0));
        params.put("__cursor1", cursorValues.get(1));
    }

    private static List<Object> cursorTuple(Map<String, Object> row, SqlTemplateExportSpec spec) {
        Object primary = row.get(spec.cursorColumn());
        requireNonNullSortValue(primary, spec.cursorColumn());
        if (!spec.tiebreakerColumnIsConfigured()) {
            return Collections.singletonList(primary);
        }
        Object tiebreaker = row.get(spec.cursorTiebreakerColumn());
        requireNonNullSortValue(tiebreaker, spec.cursorTiebreakerColumn());
        return List.of(primary, tiebreaker);
    }

    private static void requireNonNullSortValue(Object value, String column) {
        if (value == null) {
            throw new WorkerConfigException("sql_template_export sort column `"
                    + column
                    + "` is NULL in the query result; keyset pagination cannot order NULL values."
                    + " Make the column NOT NULL or filter those rows out of the template query.");
        }
    }

    /** 单列排序在页边界遇到同值时严格大于谓词会漏行: 明确失败,而不是产出静默缺行的文件。 */
    private static void requirePageBoundaryIsUnique(
            SqlTemplateExportSpec spec, List<Object> lastTuple, List<Object> overflowTuple) {
        if (overflowTuple.equals(lastTuple)) {
            throw new WorkerConfigException("sql_template_export would skip rows sharing the page-boundary sort value "
                    + lastTuple
                    + " on cursorColumn="
                    + spec.cursorColumn()
                    + ". Configure sqlTemplateExport.cursorTiebreakerColumn with a non-null unique"
                    + " column (for example the result-set primary key) so pagination can order by"
                    + " (cursorColumn, cursorTiebreakerColumn).");
        }
    }

    /** 首页 NULL 预检 SQL: 列名均经 quotePg 校验,谓词不受当前分片限制。 */
    private static String buildNullSortValueProbeSql(String baseSql, SqlTemplateExportSpec spec) {
        StringBuilder nullCondition = new StringBuilder("base.")
                .append(JdbcMappedSqlValidator.quotePg(spec.cursorColumn()))
                .append(" IS NULL");
        if (spec.tiebreakerColumnIsConfigured()) {
            nullCondition
                    .append(" OR base.")
                    .append(JdbcMappedSqlValidator.quotePg(spec.cursorTiebreakerColumn()))
                    .append(" IS NULL");
        }
        return "SELECT 1 FROM (%s) base WHERE %s LIMIT 1".formatted(baseSql, nullCondition);
    }

    /** 从 exportSnapshot 取 PrepareStep 已解析的 region(可能为 null:模板未配 region 时)。 */
    private static Object regionFromSnapshot(ExportDataContext context) {
        Map<String, Object> snap = context.exportSnapshot();
        return snap == null ? null : snap.get("region");
    }

    private NamedParameterJdbcTemplate namedJdbc(int fetchSize) {
        JdbcTemplate template = new JdbcTemplate(businessDataSource);
        int effectiveQueryTimeout = Math.max(1, security.getQueryTimeoutSeconds());
        template.setQueryTimeout(effectiveQueryTimeout);
        template.setFetchSize(fetchSize);
        return new NamedParameterJdbcTemplate(template);
    }

    private int resolveFetchSize(ExportDataContext context) {
        int fallback = workerConfiguration.fetchSize();
        Map<String, Object> tc = context == null ? Map.of() : context.templateConfig();
        Object raw = tc == null ? null : firstNonNull(tc.get("fetch_size"), tc.get("fetchSize"));
        Integer configured = positiveInt(raw);
        return configured == null ? Math.max(1, fallback) : configured;
    }

    private static Object firstNonNull(Object... values) {
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Integer positiveInt(Object value) {
        if (value instanceof Number n) {
            int candidate = n.intValue();
            return candidate > 0 ? candidate : null;
        }
        boolean textualValueIsPresent = value != null && EmptyValues.hasText(String.valueOf(value));
        if (textualValueIsPresent) {
            try {
                String rawText = String.valueOf(value);
                String text = rawText.trim();
                int candidate = Integer.parseInt(text);
                return candidate > 0 ? candidate : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    @SuppressWarnings("java:S2077")
    private void runExplainCheck(String baseSql, Map<String, Object> baseParams, ExportDataContext context) {
        String explainSql = "EXPLAIN (FORMAT JSON, ANALYZE FALSE) " + baseSql;
        try {
            List<Map<String, Object>> explainResult = jdbc.queryForList(explainSql, baseParams);
            if (explainResult.isEmpty()) {
                return;
            }
            Map<String, Object> firstExplainRow = explainResult.getFirst();
            Object planValue = firstExplainRow.values().iterator().next();
            String jsonText = String.valueOf(planValue);
            JsonNode plan = objectMapper.readTree(jsonText);
            JsonNode explainEntry = plan.path(0);
            JsonNode node = explainEntry.path("Plan");

            double planCost = node.path("Total Cost").asDouble(-1);
            double estimatedRows = node.path("Plan Rows").asDouble(-1);

            if (security.getMaxEstimatedRows() > 0 && estimatedRows > security.getMaxEstimatedRows()) {
                throw new IllegalStateException("sql_template_export EXPLAIN estimated rows "
                        + (long) estimatedRows
                        + " exceeds limit "
                        + security.getMaxEstimatedRows()
                        + " for template "
                        + context.templateCode());
            }
            if (security.getMaxPlanCost() > 0 && planCost > security.getMaxPlanCost()) {
                throw new IllegalStateException("sql_template_export EXPLAIN plan cost "
                        + planCost
                        + " exceeds limit "
                        + security.getMaxPlanCost()
                        + " for template "
                        + context.templateCode());
            }
            log.debug(
                    "sql_template_export EXPLAIN check passed: rows={}, cost={}, template={}",
                    (long) estimatedRows,
                    planCost,
                    context.templateCode());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.warn(
                    "sql_template_export EXPLAIN check failed unexpectedly (non-fatal), template={}: {}",
                    context.templateCode(),
                    SwallowedExceptionLogger.summary(e));
        }
    }

    /**
     * 将基础 SQL 包装为 keyset 分页查询（CTE + 可选分片谓词 + 游标谓词 + LIMIT :__limit）。
     *
     * <p>单列游标为 {@code base.col > :__cursor};配置唯一排序后缀时改为
     * {@code base.col > :__cursor0 OR (base.col = :__cursor0 AND base.tie > :__cursor1)} 并按
     * {@code (col, tie)} 排序,避免同排序值跨页时丢失剩余行。
     *
     * @param baseSql 原始 SELECT SQL
     * @param cursorColumn 游标列名
     * @param tiebreakerColumn 唯一排序后缀列名,未配置为 null
     * @param hasCursor 是否携带游标（非首页时为 true）
     * @param range 分片/区间谓词配置
     * @return 分页 SQL 字符串
     */
    static String buildPagedSql(
            String baseSql, String cursorColumn, String tiebreakerColumn, boolean hasCursor, ExportKeysetRange range) {
        // R2-P2-4 二层防御：之前手动 `"` 拼接 cursorColumn 依赖上游 requireIdentifier 校验。
        // 改为统一走 quotePg（同样调 requireIdentifier 但出于此函数自管），即使未来调用绕过 spec.parse 也安全。
        String cursorIdent = JdbcMappedSqlValidator.quotePg(cursorColumn);
        String tiebreakerIdent = tiebreakerColumn == null ? null : JdbcMappedSqlValidator.quotePg(tiebreakerColumn);
        StringBuilder where = new StringBuilder();
        if (range != null && range.active()) {
            where.append("WHERE base.%s >= :__loN%n".formatted(cursorIdent));
            String upperBoundOperator = range.includeUpper() ? "<=" : "<";
            where.append("AND base.%s %s :__hiN%n".formatted(cursorIdent, upperBoundOperator));
        } else if (range != null && range.partitionCount() > 1) {
            String partitionPredicate = "WHERE ((hashtext(base.%s::text) %% %d) + %d) %% %d = %d%n"
                    .formatted(
                            cursorIdent,
                            range.partitionCount(),
                            range.partitionCount(),
                            range.partitionCount(),
                            range.partitionNo() - 1);
            where.append(partitionPredicate);
        }
        if (hasCursor) {
            String cursorPredicate = tiebreakerIdent == null
                    ? "base.%s > :__cursor%n".formatted(cursorIdent)
                    : "(base.%s > :__cursor0 OR (base.%s = :__cursor0 AND base.%s > :__cursor1))%n"
                            .formatted(cursorIdent, cursorIdent, tiebreakerIdent);
            where.append(EmptyValues.isEmpty(where) ? "WHERE " : "AND ").append(cursorPredicate);
        }
        String orderBy = tiebreakerIdent == null
                ? "base.%s ASC".formatted(cursorIdent)
                : "base.%s ASC, base.%s ASC".formatted(cursorIdent, tiebreakerIdent);
        return """
    WITH base AS (
    %s
    )
    SELECT *
    FROM base
    %sORDER BY %s
    LIMIT :__limit
    """.formatted(baseSql, where, orderBy);
    }

    /** 兼容旧签名: 单列游标。 */
    static String buildPagedSql(String baseSql, String cursorColumn, boolean hasCursor, ExportKeysetRange range) {
        return buildPagedSql(baseSql, cursorColumn, null, hasCursor, range);
    }

    /** 兼容旧签名: 单列游标 + 分片参数（inactive → 退回 hashtext 分片谓词）。 */
    static String buildPagedSql(
            String baseSql, String cursorColumn, boolean hasCursor, int partitionCount, int partitionNo) {
        return buildPagedSql(
                baseSql, cursorColumn, hasCursor, ExportKeysetRange.inactiveFor(partitionCount, partitionNo));
    }

    /** 算游标列 [min,max];非数值列 → 元素 null(planner 据此退 hashtext)。复用只读 RLS tx。 */
    @SuppressWarnings("java:S2077")
    private BigDecimal[] minMax(String baseSql, String cursorColumn, Map<String, Object> baseParams) {
        String cur = JdbcMappedSqlValidator.quotePg(cursorColumn);
        String mmSql = "SELECT min(%s) AS lo, max(%s) AS hi FROM (%s) base".formatted(cur, cur, baseSql);
        Map<String, Object> row = txTemplate.execute(status -> {
            RlsTenantSessionSupport.applyIfPresent(businessDataSource);
            return jdbc.queryForMap(mmSql, baseParams);
        });
        Object rawLowerBound = row.get("lo");
        BigDecimal lowerBound = toBig(rawLowerBound);
        Object rawUpperBound = row.get("hi");
        BigDecimal upperBound = toBig(rawUpperBound);
        return new BigDecimal[] {lowerBound, upperBound};
    }

    private static BigDecimal toBig(Object v) {
        if (v instanceof BigDecimal b) {
            return b;
        }
        if (v instanceof Number n) {
            String numericText = n.toString();
            return new BigDecimal(numericText);
        }
        return null; // 非数值游标列 → 退 hashtext
    }
}
