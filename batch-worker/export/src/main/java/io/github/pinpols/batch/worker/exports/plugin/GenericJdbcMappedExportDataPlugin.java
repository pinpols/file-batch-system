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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.pinpols.batch.common.enums.JobType;
import io.github.pinpols.batch.common.enums.SystemPipelineCatalog;
import io.github.pinpols.batch.common.exception.WorkerConfigException;
import io.github.pinpols.batch.common.jdbc.JdbcMappedSqlValidator;
import io.github.pinpols.batch.common.plugin.ExportDataContext;
import io.github.pinpols.batch.common.plugin.ExportDataPlugin;
import io.github.pinpols.batch.common.plugin.IdempotencyCapability;
import io.github.pinpols.batch.common.plugin.ReplayCapabilityDeclaration;
import io.github.pinpols.batch.common.plugin.WorkerPluginIds;
import io.github.pinpols.batch.common.rls.RlsTenantSessionSupport;
import io.github.pinpols.batch.common.utils.EmptyValues;
import io.github.pinpols.batch.worker.exports.config.JdbcMappedExportSecurityProperties;
import io.github.pinpols.batch.worker.exports.jdbc.JdbcMappedExportSpec;

/** 通用 JDBC 映射导出插件：从模板配置的白名单表/列中查询批次头部及明细分页数据。 */
@Component
public class GenericJdbcMappedExportDataPlugin implements ExportDataPlugin {

    @Override
    public IdempotencyCapability registrationCallbackIdempotencyCapability() {
        return IdempotencyCapability.IDEMPOTENT_BY_PLUGIN_LOGIC;
    }

    private final JdbcTemplate jdbcTemplate;
    private final DataSource businessDataSource;
    private final ObjectMapper objectMapper;
    private final JdbcMappedExportSecurityProperties securityProperties;

    /** Phase A RLS:export query 需 tx 包 SET LOCAL,触发 biz.* SELECT 时的 USING 过滤(防跨租户读)。 */
    private final TransactionTemplate txTemplate;

    private final ExportKeysetRangePlanner keysetRangePlanner = new ExportKeysetRangePlanner();

    public GenericJdbcMappedExportDataPlugin(
            @Qualifier("exportBusinessDataSource") DataSource businessDataSource,
            ObjectMapper objectMapper,
            JdbcMappedExportSecurityProperties securityProperties) {
        this.businessDataSource = businessDataSource;
        this.jdbcTemplate = new JdbcTemplate(businessDataSource);
        this.objectMapper = objectMapper;
        this.securityProperties = securityProperties;
        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(businessDataSource);
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.txTemplate.setReadOnly(true);
    }

    @Override
    public String id() {
        return WorkerPluginIds.EXPORT_DATA_JDBC_MAPPED;
    }

    @Override
    public ReplayCapabilityDeclaration replayCapabilityDeclaration() {
        int semanticVersion = SystemPipelineCatalog.forJobType(JobType.EXPORT)
                .orElseThrow(() -> new IllegalStateException("EXPORT system pipeline is missing"))
                .semanticVersion();
        return new ReplayCapabilityDeclaration(
                id(),
                Integer.toString(semanticVersion),
                List.of("BUSINESS_DATABASE_READ"),
                IdempotencyCapability.IDEMPOTENT_BY_PLUGIN_LOGIC,
                "tenantId:batchNo:templateCode:partitionNo:partitionCount",
                "read-only-jdbc-query-v1",
                ReplayCapabilityDeclaration.CURRENT_CONTRACT_VERSION);
    }

    @Override
    @SuppressWarnings("java:S2077")
    public Map<String, Object> loadBatch(ExportDataContext context) {
        JdbcMappedExportSpec spec = JdbcMappedExportSpec.parse(context.templateConfig(), objectMapper);
        spec.validateIdentifiers(securityProperties.getAllowedSchemas());
        String fq =
                JdbcMappedSqlValidator.quotePg(spec.schema()) + "." + JdbcMappedSqlValidator.quotePg(spec.batchTable());
        StringBuilder cols = new StringBuilder();
        for (String c : spec.batchSelectColumns()) {
            cols.append(JdbcMappedSqlValidator.quotePg(c)).append(',');
        }
        cols.setLength(cols.length() - 1);
        String tenant = JdbcMappedSqlValidator.quotePg(spec.batchTenantColumn());
        String bno = JdbcMappedSqlValidator.quotePg(spec.batchNoColumn());
        String sql = "SELECT " + cols + " FROM " + fq + " WHERE " + tenant + " = ? AND " + bno + " = ? LIMIT 1";
        List<Map<String, Object>> rows = txTemplate.execute(status -> {
            RlsTenantSessionSupport.applyIfPresent(businessDataSource);
            return jdbcTemplate.queryForList(sql, context.tenantId(), context.batchNo());
        });
        if (EmptyValues.isEmpty(rows)) {
            return Map.of();
        }
        Map<String, Object> firstRow = rows.get(0);
        if (firstRow == null) {
            return Map.of();
        }
        return new LinkedHashMap<>(firstRow);
    }

    @Override
    @SuppressWarnings("java:S2077")
    public DetailPage loadDetailPage(ExportDataContext context, Long batchId, int pageSize, Object cursor) {
        if (batchId == null) {
            return DetailPage.empty();
        }
        JdbcMappedExportSpec spec = JdbcMappedExportSpec.parse(context.templateConfig(), objectMapper);
        spec.validateIdentifiers(securityProperties.getAllowedSchemas());
        boolean composite = spec.tiebreakerColumnIsConfigured();
        List<Object> cursorValues = splitCursor(cursor);
        requireCursorShape(spec, cursorValues, composite);
        String fq = JdbcMappedSqlValidator.quotePg(spec.schema())
                + "."
                + JdbcMappedSqlValidator.quotePg(spec.detailTable());
        String ob = JdbcMappedSqlValidator.quotePg(spec.detailOrderByColumn());
        String tb = composite ? JdbcMappedSqlValidator.quotePg(spec.detailTiebreakerColumn()) : null;
        boolean orderColumnSelected = spec.detailSelectColumns().contains(spec.detailOrderByColumn());
        boolean tiebreakerColumnSelected =
                composite && spec.detailSelectColumns().contains(spec.detailTiebreakerColumn());
        StringBuilder cols = new StringBuilder();
        for (String c : spec.detailSelectColumns()) {
            cols.append(JdbcMappedSqlValidator.quotePg(c)).append(',');
        }
        if (!orderColumnSelected) {
            cols.append(ob).append(" as ").append(ORDER_CURSOR_ALIAS).append(',');
        }
        if (composite && !tiebreakerColumnSelected) {
            cols.append(tb).append(" as ").append(TIEBREAKER_CURSOR_ALIAS).append(',');
        }
        cols.setLength(cols.length() - 1);
        String fk = JdbcMappedSqlValidator.quotePg(spec.detailFkColumn());
        if (cursor == null) {
            // 首页预检: 排序键含 NULL 的行会被 keyset/哈希分片谓词静默过滤,必须先明确失败。
            // 检查不受当前 partition 谓词限制,覆盖哈希会过滤掉的行。
            String nullProbeSql = buildNullSortValueProbeSql(fq, fk, ob, tb);
            List<Map<String, Object>> nullSortValues = txTemplate.execute(status -> {
                RlsTenantSessionSupport.applyIfPresent(businessDataSource);
                return jdbcTemplate.queryForList(nullProbeSql, batchId);
            });
            if (EmptyValues.isNotEmpty(nullSortValues)) {
                throw new WorkerConfigException("jdbc_mapped_export sort column is NULL for batchId="
                        + batchId
                        + " (orderByColumn="
                        + ob
                        + (tb == null ? "" : ", tiebreakerColumn=" + tb)
                        + "); keyset pagination would silently drop those rows.");
            }
        }
        ExportKeysetRange keysetRange = keysetRangePlanner.resolve(context, () -> minMax(spec, batchId));
        DetailSql detailSql = new DetailSql(cols.toString(), fq, fk, ob, tb);
        // 多取一行: 最后一页仍是满页时用来证明下一页起点不会跳过同排序值的行。
        int limit = Math.max(1, pageSize);
        int fetchLimit = limit == Integer.MAX_VALUE ? limit : limit + 1;
        PagedQuery pq = buildDetailQuery(detailSql, batchId, cursorValues, fetchLimit, keysetRange);
        final String finalSql = pq.sql();
        final Object[] sqlArgs = pq.args();
        List<Map<String, Object>> rows = txTemplate.execute(status -> {
            RlsTenantSessionSupport.applyIfPresent(businessDataSource);
            return jdbcTemplate.queryForList(finalSql, sqlArgs);
        });
        if (EmptyValues.isEmpty(rows)) {
            return DetailPage.empty();
        }
        boolean hasOverflowRow = rows.size() > limit;
        List<Object> lastTuple = cursorTuple(
                rows.get(hasOverflowRow ? limit - 1 : rows.size() - 1),
                spec,
                orderColumnSelected,
                tiebreakerColumnSelected);
        if (hasOverflowRow) {
            List<Object> overflowTuple =
                    cursorTuple(rows.get(limit), spec, orderColumnSelected, tiebreakerColumnSelected);
            requirePageBoundaryIsUnique(spec, lastTuple, overflowTuple);
            rows = new ArrayList<>(rows.subList(0, limit));
        }
        for (Map<String, Object> row : rows) {
            row.remove(ORDER_CURSOR_ALIAS);
            row.remove(TIEBREAKER_CURSOR_ALIAS);
        }
        Object nextCursor = composite ? lastTuple : lastTuple.getFirst();
        return new DetailPage(rows, nextCursor);
    }

    private static final String ORDER_CURSOR_ALIAS = "__cursor_value__";
    private static final String TIEBREAKER_CURSOR_ALIAS = "__cursor_tiebreaker__";

    /** 游标拆为排序键元组: 单列位点是 1 个值,复合位点是 (排序列, 唯一后缀)。 */
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
    private static void requireCursorShape(JdbcMappedExportSpec spec, List<Object> cursorValues, boolean composite) {
        if (EmptyValues.isEmpty(cursorValues) || cursorValues.size() == (composite ? 2 : 1)) {
            return;
        }
        throw new WorkerConfigException(
                "jdbc_mapped_export cursor does not match the configured sort key of detailTable="
                        + spec.detailTable()
                        + ": expected "
                        + (composite ? "(detailOrderByColumn, detailTiebreakerColumn)" : "detailOrderByColumn")
                        + " but the stored GENERATE position has "
                        + cursorValues.size()
                        + " value(s). Start a new run instead of resuming this one.");
    }

    /** 单列排序在页边界遇到同值时严格大于谓词会漏行: 明确失败,而不是产出静默缺行的文件。 */
    private static void requirePageBoundaryIsUnique(
            JdbcMappedExportSpec spec, List<Object> lastTuple, List<Object> overflowTuple) {
        if (overflowTuple.equals(lastTuple)) {
            throw new WorkerConfigException("jdbc_mapped_export would skip rows sharing the page-boundary sort value "
                    + lastTuple
                    + " on detailTable="
                    + spec.detailTable()
                    + ", column="
                    + spec.detailOrderByColumn()
                    + ". Configure jdbc_mapped_export.detailTiebreakerColumn with a non-null unique"
                    + " column (for example the primary key) so pagination can order by"
                    + " (detailOrderByColumn, detailTiebreakerColumn).");
        }
    }

    private static List<Object> cursorTuple(
            Map<String, Object> row,
            JdbcMappedExportSpec spec,
            boolean orderColumnSelected,
            boolean tiebreakerColumnSelected) {
        Object primary = orderColumnSelected ? row.get(spec.detailOrderByColumn()) : row.remove(ORDER_CURSOR_ALIAS);
        requireNonNullSortValue(primary, spec.detailOrderByColumn(), spec);
        if (!spec.tiebreakerColumnIsConfigured()) {
            return Collections.singletonList(primary);
        }
        Object tiebreaker =
                tiebreakerColumnSelected ? row.get(spec.detailTiebreakerColumn()) : row.remove(TIEBREAKER_CURSOR_ALIAS);
        requireNonNullSortValue(tiebreaker, spec.detailTiebreakerColumn(), spec);
        return List.of(primary, tiebreaker);
    }

    private static void requireNonNullSortValue(Object value, String column, JdbcMappedExportSpec spec) {
        if (value == null) {
            throw new WorkerConfigException("jdbc_mapped_export sort column `"
                    + column
                    + "` is NULL on detailTable="
                    + spec.detailTable()
                    + "; keyset pagination cannot order NULL values. Make the column NOT NULL or"
                    + " filter those rows out of the batch before export.");
        }
    }

    /** 首页 NULL 预检 SQL: 标识符均已经 quotePg 校验,谓词不受当前分片限制。 */
    private static String buildNullSortValueProbeSql(String fq, String fk, String ob, String tb) {
        StringBuilder sql = new StringBuilder("SELECT 1 FROM ")
                .append(fq)
                .append(" WHERE ")
                .append(fk)
                .append(" = ? AND (")
                .append(ob)
                .append(" IS NULL");
        if (tb != null) {
            sql.append(" OR ").append(tb).append(" IS NULL");
        }
        sql.append(") LIMIT 1");
        return sql.toString();
    }

    @Override
    public List<DelimitedColumn> describeDelimitedColumns(ExportDataContext context, Map<String, Object> batch) {
        JdbcMappedExportSpec spec = JdbcMappedExportSpec.parse(context.templateConfig(), objectMapper);
        spec.validateIdentifiers(securityProperties.getAllowedSchemas());
        return spec.detailSelectColumns().stream()
                .map(col -> new DelimitedColumn(col, "detail." + col))
                .toList();
    }

    /** 构造明细分页 SQL + 顺序参数。partitionCount>1 时叠加 hashtext 分片谓词。 */
    @SuppressWarnings("java:S6218")
    static record PagedQuery(String sql, Object[] args) {}

    /** 明细 SQL 片段打包(避免 buildDetailQuery 超 6 参,PMD ExcessiveParameterList)。 */
    static record DetailSql(String cols, String fq, String fk, String ob, String tb) {
        DetailSql(String cols, String fq, String fk, String ob) {
            this(cols, fq, fk, ob, null);
        }
    }

    static PagedQuery buildDetailQuery(
            DetailSql q, Long batchId, List<Object> cursorValues, int limit, ExportKeysetRange range) {
        String cols = q.cols();
        String fq = q.fq();
        String fk = q.fk();
        String ob = q.ob();
        String tb = q.tb();
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(cols)
                .append(" FROM ")
                .append(fq)
                .append(" WHERE ")
                .append(fk)
                .append(" = ?");
        List<Object> args = new ArrayList<>();
        args.add(batchId);
        if (range != null && range.active()) {
            sql.append(" AND ")
                    .append(ob)
                    .append(" >= ?")
                    .append(" AND ")
                    .append(ob)
                    .append(range.includeUpper() ? " <= ?" : " < ?");
            args.add(range.loN());
            args.add(range.hiN());
        } else if (range != null && range.partitionCount() > 1) {
            sql.append(" AND ((hashtext(").append(ob).append("::text) % ?) + ?) % ? = ?");
            args.add(range.partitionCount());
            args.add(range.partitionCount());
            args.add(range.partitionCount());
            args.add(range.partitionNo() - 1);
        }
        appendCursorPredicate(sql, args, ob, tb, cursorValues);
        sql.append(" ORDER BY ").append(ob).append(" ASC");
        if (tb != null) {
            sql.append(", ").append(tb).append(" ASC");
        }
        sql.append(" LIMIT ?");
        args.add(limit);
        String querySql = sql.toString();
        Object[] queryArgs = args.toArray();
        return new PagedQuery(querySql, queryArgs);
    }

    /**
     * 复合游标用 {@code (ob > ?) OR (ob = ? AND tb > ?)}: 单列严格大于会跳过与末行同排序值的剩余行。单列游标保持原谓词。
     */
    private static void appendCursorPredicate(
            StringBuilder sql, List<Object> args, String ob, String tb, List<Object> cursorValues) {
        if (EmptyValues.isEmpty(cursorValues)) {
            return;
        }
        Object primary = cursorValues.getFirst();
        if (tb == null) {
            sql.append(" AND ").append(ob).append(" > ?");
            args.add(primary);
            return;
        }
        Object tiebreaker = cursorValues.get(1);
        sql.append(" AND (")
                .append(ob)
                .append(" > ? OR (")
                .append(ob)
                .append(" = ? AND ")
                .append(tb)
                .append(" > ?))");
        args.add(primary);
        args.add(primary);
        args.add(tiebreaker);
    }

    // 兼容旧签名: 单列位点。
    static PagedQuery buildDetailQuery(
            DetailSql q, Long batchId, Object cursor, int pageSize, ExportKeysetRange range) {
        List<Object> cursorValues = cursor == null ? List.of() : List.of(cursor);
        return buildDetailQuery(q, batchId, cursorValues, pageSize, range);
    }

    // 兼容旧签名:转调新重载。
    static PagedQuery buildDetailQuery(
            DetailSql q, Long batchId, Object cursor, int pageSize, int partitionCount, int partitionNo) {
        return buildDetailQuery(
                q, batchId, cursor, pageSize, ExportKeysetRange.inactiveFor(partitionCount, partitionNo));
    }

    /** 物理表游标列 [min,max];非数值列 → 元素 null(planner 退 hashtext)。复用只读 RLS tx。 */
    @SuppressWarnings("java:S2077")
    private BigDecimal[] minMax(JdbcMappedExportSpec spec, Long batchId) {
        String fq = JdbcMappedSqlValidator.quotePg(spec.schema())
                + "."
                + JdbcMappedSqlValidator.quotePg(spec.detailTable());
        String ob = JdbcMappedSqlValidator.quotePg(spec.detailOrderByColumn());
        String fk = JdbcMappedSqlValidator.quotePg(spec.detailFkColumn());
        String sql = "SELECT min(" + ob + ") lo, max(" + ob + ") hi FROM " + fq + " WHERE " + fk + " = ?";
        Map<String, Object> row = txTemplate.execute(status -> {
            RlsTenantSessionSupport.applyIfPresent(businessDataSource);
            return jdbcTemplate.queryForMap(sql, batchId);
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
        return null;
    }
}
