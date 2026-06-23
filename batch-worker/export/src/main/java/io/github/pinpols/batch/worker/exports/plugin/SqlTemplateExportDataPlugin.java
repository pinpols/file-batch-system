package io.github.pinpols.batch.worker.exports.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.plugin.ExportDataContext;
import io.github.pinpols.batch.common.plugin.ExportDataPlugin;
import io.github.pinpols.batch.common.rls.RlsTenantSessionSupport;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.worker.exports.config.ExportWorkerConfiguration;
import io.github.pinpols.batch.worker.exports.config.SqlTemplateExportSecurityProperties;
import io.github.pinpols.batch.worker.exports.sql.SqlTemplateExportSpec;
import io.github.pinpols.batch.worker.exports.sql.SqlTemplateExportSqlValidator;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

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

  public static final String PLUGIN_ID = "sql_template_export";

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
    template.setQueryTimeout(
        Math.max(1, security == null ? 30 : security.getQueryTimeoutSeconds()));
    this.jdbc = new NamedParameterJdbcTemplate(template);
    this.objectMapper = objectMapper;
    this.security = security;
    this.workerConfiguration = workerConfiguration;
    this.sqlValidator = new SqlTemplateExportSqlValidator(security);
    this.txTemplate = new TransactionTemplate(new DataSourceTransactionManager(businessDataSource));
    this.txTemplate.setReadOnly(true);
  }

  @Override
  public String id() {
    return PLUGIN_ID;
  }

  @Override
  public Map<String, Object> loadBatch(ExportDataContext context) {
    if (context == null
        || !Texts.hasText(context.tenantId())
        || !Texts.hasText(context.batchNo())) {
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
  public DetailPage loadDetailPage(
      ExportDataContext context, Long batchId, int pageSize, Object cursor) {
    if (context == null
        || !Texts.hasText(context.tenantId())
        || !Texts.hasText(context.batchNo())) {
      return DetailPage.empty();
    }
    SqlTemplateExportSpec spec =
        SqlTemplateExportSpec.parse(context.templateConfig(), objectMapper);
    String baseSql = sqlValidator.validate(spec.detailSql());

    int limit = Math.max(1, pageSize);
    if (security != null && security.getMaxPageSize() > 0) {
      limit = Math.min(limit, security.getMaxPageSize());
    }

    Map<String, Object> baseParams = new LinkedHashMap<>();
    baseParams.put("tenantId", context.tenantId());
    baseParams.put("batchNo", context.batchNo());
    // 地区过滤(per-run):PrepareStep 已解析并注入 exportSnapshot,模板 SQL 可声明 `WHERE region = :region`。
    // 始终绑定(即使 null):模板引用 :region 时缺参会让 NamedParameterJdbcTemplate 抛 No value for parameter。
    // region 由 allowedRegions 字典在 PREPARE 阶段把关,这里仅透传不再校验。
    baseParams.put("region", regionFromSnapshot(context));

    // 首页（cursor == null）时执行 EXPLAIN 预检
    if (cursor == null && security != null && security.isExplainCheckEnabled()) {
      runExplainCheck(baseSql, baseParams, context);
    }

    ExportKeysetRange keysetRange =
        keysetRangePlanner.resolve(context, () -> minMax(baseSql, spec.cursorColumn(), baseParams));
    String sql = buildPagedSql(baseSql, spec.cursorColumn(), cursor != null, keysetRange);

    Map<String, Object> params = new LinkedHashMap<>(baseParams);
    if (cursor != null) {
      params.put("__cursor", cursor);
    }
    params.put("__limit", limit);
    if (keysetRange.active()) {
      params.put("__loN", keysetRange.loN());
      params.put("__hiN", keysetRange.hiN());
    }

    final String finalSql = sql;
    final Map<String, Object> finalParams = params;
    final NamedParameterJdbcTemplate pageJdbc = namedJdbc(resolveFetchSize(context));
    List<Map<String, Object>> rows =
        txTemplate.execute(
            status -> {
              RlsTenantSessionSupport.applyIfPresent(businessDataSource);
              return pageJdbc.queryForList(finalSql, finalParams);
            });
    if (rows == null || rows.isEmpty()) {
      return DetailPage.empty();
    }

    Object nextCursor = null;
    for (Map<String, Object> row : rows) {
      nextCursor = row.get(spec.cursorColumn());
    }
    return new DetailPage(rows, nextCursor);
  }

  /** 从 exportSnapshot 取 PrepareStep 已解析的 region(可能为 null:模板未配 region 时)。 */
  private static Object regionFromSnapshot(ExportDataContext context) {
    Map<String, Object> snap = context.exportSnapshot();
    return snap == null ? null : snap.get("region");
  }

  private NamedParameterJdbcTemplate namedJdbc(int fetchSize) {
    JdbcTemplate template = new JdbcTemplate(businessDataSource);
    template.setQueryTimeout(
        Math.max(1, security == null ? 30 : security.getQueryTimeoutSeconds()));
    template.setFetchSize(fetchSize);
    return new NamedParameterJdbcTemplate(template);
  }

  private int resolveFetchSize(ExportDataContext context) {
    int fallback = workerConfiguration == null ? 1000 : workerConfiguration.fetchSize();
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
    if (value != null && Texts.hasText(String.valueOf(value))) {
      try {
        int candidate = Integer.parseInt(String.valueOf(value).trim());
        return candidate > 0 ? candidate : null;
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    return null;
  }

  private void runExplainCheck(
      String baseSql, Map<String, Object> baseParams, ExportDataContext context) {
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
            "sql_template_export EXPLAIN estimated rows "
                + (long) estimatedRows
                + " exceeds limit "
                + security.getMaxEstimatedRows()
                + " for template "
                + context.templateCode());
      }
      if (security.getMaxPlanCost() > 0 && planCost > security.getMaxPlanCost()) {
        throw new IllegalStateException(
            "sql_template_export EXPLAIN plan cost "
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
          e.getMessage());
    }
  }

  /**
   * 将基础 SQL 包装为 keyset 分页查询（CTE + 可选分片谓词 + WHERE cursor > :__cursor + LIMIT :__limit）。
   *
   * @param baseSql 原始 SELECT SQL
   * @param cursorColumn 游标列名
   * @param hasCursor 是否携带游标（非首页时为 true）
   * @param partitionCount 分片总数（为 1 时不注入分片谓词）
   * @param partitionNo 当前分片编号（1-based）
   * @return 分页 SQL 字符串
   */
  static String buildPagedSql(
      String baseSql, String cursorColumn, boolean hasCursor, ExportKeysetRange range) {
    // R2-P2-4 二层防御：之前手动 `"` 拼接 cursorColumn 依赖上游 requireIdentifier 校验。
    // 改为统一走 quotePg（同样调 requireIdentifier 但出于此函数自管），即使未来调用绕过 spec.parse 也安全。
    String cursorIdent =
        io.github.pinpols.batch.common.jdbc.JdbcMappedSqlValidator.quotePg(cursorColumn);
    StringBuilder where = new StringBuilder();
    if (range != null && range.active()) {
      where.append("WHERE base.%s >= :__loN%n".formatted(cursorIdent));
      where.append(
          "AND base.%s %s :__hiN%n".formatted(cursorIdent, range.includeUpper() ? "<=" : "<"));
    } else if (range != null && range.partitionCount() > 1) {
      where.append(
          "WHERE ((hashtext(base.%s::text) %% %d) + %d) %% %d = %d%n"
              .formatted(
                  cursorIdent,
                  range.partitionCount(),
                  range.partitionCount(),
                  range.partitionCount(),
                  range.partitionNo() - 1));
    }
    if (hasCursor) {
      where
          .append(where.isEmpty() ? "WHERE " : "AND ")
          .append("base.%s > :__cursor%n".formatted(cursorIdent));
    }
    return """
    WITH base AS (
    %s
    )
    SELECT *
    FROM base
    %sORDER BY base.%s ASC
    LIMIT :__limit
    """
        .formatted(baseSql, where, cursorIdent);
  }

  /** 兼容旧签名:转调新重载（inactive → 退回 hashtext 分片谓词）。 */
  static String buildPagedSql(
      String baseSql, String cursorColumn, boolean hasCursor, int partitionCount, int partitionNo) {
    return buildPagedSql(
        baseSql,
        cursorColumn,
        hasCursor,
        ExportKeysetRange.inactiveFor(partitionCount, partitionNo));
  }

  /** 算游标列 [min,max];非数值列 → 元素 null(planner 据此退 hashtext)。复用只读 RLS tx。 */
  private BigDecimal[] minMax(String baseSql, String cursorColumn, Map<String, Object> baseParams) {
    String cur = io.github.pinpols.batch.common.jdbc.JdbcMappedSqlValidator.quotePg(cursorColumn);
    String mmSql =
        "SELECT min(%s) AS lo, max(%s) AS hi FROM (%s) base".formatted(cur, cur, baseSql);
    Map<String, Object> row =
        txTemplate.execute(
            status -> {
              RlsTenantSessionSupport.applyIfPresent(businessDataSource);
              return jdbc.queryForMap(mmSql, baseParams);
            });
    return new BigDecimal[] {toBig(row.get("lo")), toBig(row.get("hi"))};
  }

  private static BigDecimal toBig(Object v) {
    if (v instanceof BigDecimal b) {
      return b;
    }
    if (v instanceof Number n) {
      return new BigDecimal(n.toString());
    }
    return null; // 非数值游标列 → 退 hashtext
  }
}
