package com.example.batch.worker.exports.plugin;

import com.example.batch.common.jdbc.JdbcMappedSqlValidator;
import com.example.batch.common.plugin.ExportDataContext;
import com.example.batch.common.plugin.ExportDataPlugin;
import com.example.batch.common.plugin.WorkerPluginIds;
import com.example.batch.common.rls.RlsTenantSessionSupport;
import com.example.batch.worker.exports.config.JdbcMappedExportSecurityProperties;
import com.example.batch.worker.exports.jdbc.JdbcMappedExportSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 通用 JDBC 映射导出插件：从模板配置的白名单表/列中查询批次头部及明细分页数据。 */
@Component
public class GenericJdbcMappedExportDataPlugin implements ExportDataPlugin {

  private final JdbcTemplate jdbcTemplate;
  private final DataSource businessDataSource;
  private final ObjectMapper objectMapper;
  private final JdbcMappedExportSecurityProperties securityProperties;

  /** Phase A RLS:export query 需 tx 包 SET LOCAL,触发 biz.* SELECT 时的 USING 过滤(防跨租户读)。 */
  private final org.springframework.transaction.support.TransactionTemplate txTemplate;

  public GenericJdbcMappedExportDataPlugin(
      @Qualifier("exportBusinessDataSource") DataSource businessDataSource,
      ObjectMapper objectMapper,
      JdbcMappedExportSecurityProperties securityProperties) {
    this.businessDataSource = businessDataSource;
    this.jdbcTemplate = new JdbcTemplate(businessDataSource);
    this.objectMapper = objectMapper;
    this.securityProperties = securityProperties;
    this.txTemplate =
        new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                businessDataSource));
    this.txTemplate.setReadOnly(true);
  }

  @Override
  public String id() {
    return WorkerPluginIds.EXPORT_DATA_JDBC_MAPPED;
  }

  @Override
  public Map<String, Object> loadBatch(ExportDataContext context) {
    JdbcMappedExportSpec spec = JdbcMappedExportSpec.parse(context.templateConfig(), objectMapper);
    spec.validateIdentifiers(securityProperties.getAllowedSchemas());
    String fq =
        JdbcMappedSqlValidator.quotePg(spec.schema())
            + "."
            + JdbcMappedSqlValidator.quotePg(spec.batchTable());
    StringBuilder cols = new StringBuilder();
    for (String c : spec.batchSelectColumns()) {
      cols.append(JdbcMappedSqlValidator.quotePg(c)).append(',');
    }
    cols.setLength(cols.length() - 1);
    String tenant = JdbcMappedSqlValidator.quotePg(spec.batchTenantColumn());
    String bno = JdbcMappedSqlValidator.quotePg(spec.batchNoColumn());
    String sql =
        "SELECT " + cols + " FROM " + fq + " WHERE " + tenant + " = ? AND " + bno + " = ? LIMIT 1";
    List<Map<String, Object>> rows =
        txTemplate.execute(
            status -> {
              RlsTenantSessionSupport.applyIfPresent(businessDataSource);
              return jdbcTemplate.queryForList(sql, context.tenantId(), context.batchNo());
            });
    if (rows == null || rows.isEmpty()) {
      return Map.of();
    }
    return new LinkedHashMap<>(rows.get(0));
  }

  @Override
  public DetailPage loadDetailPage(
      ExportDataContext context, Long batchId, int pageSize, Object cursor) {
    if (batchId == null) {
      return DetailPage.empty();
    }
    JdbcMappedExportSpec spec = JdbcMappedExportSpec.parse(context.templateConfig(), objectMapper);
    spec.validateIdentifiers(securityProperties.getAllowedSchemas());
    String fq =
        JdbcMappedSqlValidator.quotePg(spec.schema())
            + "."
            + JdbcMappedSqlValidator.quotePg(spec.detailTable());
    StringBuilder cols = new StringBuilder();
    boolean orderColumnSelected = false;
    for (String c : spec.detailSelectColumns()) {
      cols.append(JdbcMappedSqlValidator.quotePg(c)).append(',');
      if (c.equals(spec.detailOrderByColumn())) {
        orderColumnSelected = true;
      }
    }
    if (!orderColumnSelected) {
      cols.append(JdbcMappedSqlValidator.quotePg(spec.detailOrderByColumn()))
          .append(" as __cursor_value__,");
    }
    cols.setLength(cols.length() - 1);
    String fk = JdbcMappedSqlValidator.quotePg(spec.detailFkColumn());
    String ob = JdbcMappedSqlValidator.quotePg(spec.detailOrderByColumn());
    StringBuilder sql =
        new StringBuilder("SELECT ")
            .append(cols)
            .append(" FROM ")
            .append(fq)
            .append(" WHERE ")
            .append(fk)
            .append(" = ?");
    String finalSql;
    Object[] sqlArgs;
    if (cursor != null) {
      sql.append(" AND ").append(ob).append(" > ?");
      sql.append(" ORDER BY ").append(ob).append(" ASC LIMIT ?");
      finalSql = sql.toString();
      sqlArgs = new Object[] {batchId, cursor, pageSize};
    } else {
      sql.append(" ORDER BY ").append(ob).append(" ASC LIMIT ?");
      finalSql = sql.toString();
      sqlArgs = new Object[] {batchId, pageSize};
    }
    List<Map<String, Object>> rows =
        txTemplate.execute(
            status -> {
              RlsTenantSessionSupport.applyIfPresent(businessDataSource);
              return jdbcTemplate.queryForList(finalSql, sqlArgs);
            });
    if (rows == null || rows.isEmpty()) {
      return DetailPage.empty();
    }
    Object nextCursor = null;
    for (Map<String, Object> row : rows) {
      nextCursor =
          orderColumnSelected
              ? row.get(spec.detailOrderByColumn())
              : row.remove("__cursor_value__");
    }
    return new DetailPage(rows, nextCursor);
  }

  @Override
  public List<DelimitedColumn> describeDelimitedColumns(
      ExportDataContext context, Map<String, Object> batch) {
    JdbcMappedExportSpec spec = JdbcMappedExportSpec.parse(context.templateConfig(), objectMapper);
    spec.validateIdentifiers(securityProperties.getAllowedSchemas());
    return spec.detailSelectColumns().stream()
        .map(col -> new DelimitedColumn(col, "detail." + col))
        .toList();
  }
}
