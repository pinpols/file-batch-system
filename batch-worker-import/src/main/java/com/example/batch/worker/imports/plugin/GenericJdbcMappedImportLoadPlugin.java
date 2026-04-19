package com.example.batch.worker.imports.plugin;

import com.example.batch.common.jdbc.JdbcMappedSqlValidator;
import com.example.batch.common.plugin.ImportLoadContext;
import com.example.batch.common.plugin.ImportLoadPlugin;
import com.example.batch.common.plugin.WorkerPluginIds;
import com.example.batch.worker.imports.config.JdbcMappedImportSecurityProperties;
import com.example.batch.worker.imports.jdbc.JdbcMappedImportSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 通用 LOAD：通过模板驱动的列映射（白名单）向单张业务表执行 INSERT / UPSERT。 每次 {@link ImportLoadPlugin#loadChunk} 调用使用 JDBC
 * {@code batchUpdate} 处理整个批次（非逐行请求）。 通过 {@code query_param_schema.jdbcMappedImport} 或 {@code
 * jdbc_mapped_import} 配置。
 */
@Slf4j
@Component
public class GenericJdbcMappedImportLoadPlugin implements ImportLoadPlugin {

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;
  private final JdbcMappedImportSecurityProperties securityProperties;

  public GenericJdbcMappedImportLoadPlugin(
      @Qualifier("importBusinessDataSource") DataSource importBusinessDataSource,
      ObjectMapper objectMapper,
      JdbcMappedImportSecurityProperties securityProperties) {
    this.jdbcTemplate = new JdbcTemplate(importBusinessDataSource);
    this.objectMapper = objectMapper;
    this.securityProperties = securityProperties;
  }

  @Override
  public String id() {
    return WorkerPluginIds.IMPORT_LOAD_JDBC_MAPPED;
  }

  @Override
  public int loadChunk(ImportLoadContext context, List<Map<String, Object>> records)
      throws Exception {
    if (records == null || records.isEmpty()) {
      return 0;
    }
    JdbcMappedImportSpec spec = JdbcMappedImportSpec.parse(context.templateConfig(), objectMapper);
    // A-3.5：严格幂等模式下，模板必须声明 conflictColumns；否则立即拒绝加载
    spec.validateIdentifiers(
        securityProperties.getAllowedSchemas(), securityProperties.isStrictIdempotency());

    List<String> insertCols = orderedInsertColumns(spec);
    String sql = buildSql(spec, insertCols);
    // C-2.7 b: 显式日志幂等模式——运维可按关键字 "idempotency=OFF" 扫出需要补
    // conflict_columns 的模板（重跑安全性靠模板声明业务主键 + batch_no 唯一约束）。
    if (spec.conflictColumns() == null || spec.conflictColumns().isEmpty()) {
      log.warn(
          "jdbc-mapped-import LOAD running without ON CONFLICT clause: tenantId={},"
              + " template={}, schema={}, table={}, idempotency=OFF (重跑会重复落行)"
              + " — add conflict_columns to template to enable UNIQUE-based dedupe",
          context.tenantId(),
          context.templateCode(),
          spec.schema(),
          spec.table());
    } else if (log.isInfoEnabled()) {
      log.info(
          "jdbc-mapped-import LOAD idempotency=ON: tenantId={}, template={}, schema={},"
              + " table={}, conflictColumns={}",
          context.tenantId(),
          context.templateCode(),
          spec.schema(),
          spec.table(),
          spec.conflictColumns());
    }
    int n = records.size();
    jdbcTemplate.batchUpdate(
        sql,
        new BatchPreparedStatementSetter() {
          @Override
          public void setValues(PreparedStatement ps, int i) throws SQLException {
            Object[] args = buildArgs(insertCols, spec, records.get(i), context);
            for (int j = 0; j < args.length; j++) {
              ps.setObject(j + 1, args[j]);
            }
          }

          @Override
          public int getBatchSize() {
            return n;
          }
        });
    return n;
  }

  private List<String> orderedInsertColumns(JdbcMappedImportSpec spec) {
    List<String> cols = new ArrayList<>();
    cols.add(spec.tenantColumn());
    for (JdbcMappedImportSpec.ColumnMapping m : spec.columnMappings()) {
      if (!cols.contains(m.to())) {
        cols.add(m.to());
      }
    }
    for (String k : spec.systemBindings().keySet()) {
      if (!cols.contains(k)) {
        cols.add(k);
      }
    }
    return cols;
  }

  private Object[] buildArgs(
      List<String> insertCols,
      JdbcMappedImportSpec spec,
      Map<String, Object> row,
      ImportLoadContext context) {
    Object[] args = new Object[insertCols.size()];
    for (int i = 0; i < insertCols.size(); i++) {
      args[i] = valueForColumn(insertCols.get(i), spec, row, context);
    }
    return args;
  }

  private Object valueForColumn(
      String col, JdbcMappedImportSpec spec, Map<String, Object> row, ImportLoadContext context) {
    if (col.equals(spec.tenantColumn())) {
      return context.tenantId();
    }
    for (JdbcMappedImportSpec.ColumnMapping m : spec.columnMappings()) {
      if (m.to().equals(col)) {
        return row.get(m.from());
      }
    }
    String pattern = spec.systemBindings().get(col);
    if (pattern != null) {
      return resolveBinding(pattern, context);
    }
    throw new IllegalStateException("no binding for column: " + col);
  }

  private String resolveBinding(String pattern, ImportLoadContext context) {
    if (pattern == null) {
      return null;
    }
    return pattern
        .replace("${tenantId}", nullSafe(context.tenantId()))
        .replace("${traceId}", nullSafe(context.traceId()))
        .replace("${workerId}", nullSafe(context.workerId()))
        .replace("${sourceFileName}", nullSafe(context.sourceFileName()))
        .replace("${batchNo}", nullSafe(context.batchNo()))
        .replace("${jobCode}", nullSafe(context.jobCode()))
        .replace("${templateCode}", nullSafe(context.templateCode()));
  }

  private String nullSafe(String v) {
    return v == null ? "" : v;
  }

  private String buildSql(JdbcMappedImportSpec spec, List<String> insertCols) {
    String fq =
        JdbcMappedSqlValidator.quotePg(spec.schema())
            + "."
            + JdbcMappedSqlValidator.quotePg(spec.table());
    StringBuilder colPart = new StringBuilder();
    StringBuilder ph = new StringBuilder();
    for (String c : insertCols) {
      String q = JdbcMappedSqlValidator.quotePg(c);
      colPart.append(q).append(',');
      ph.append("?,");
    }
    colPart.setLength(colPart.length() - 1);
    ph.setLength(ph.length() - 1);
    String insert = "INSERT INTO " + fq + " (" + colPart + ") VALUES (" + ph + ")";
    List<String> conflicts = spec.conflictColumns();
    if (conflicts == null || conflicts.isEmpty()) {
      return insert;
    }
    StringBuilder conflictPart = new StringBuilder();
    for (String c : conflicts) {
      conflictPart.append(JdbcMappedSqlValidator.quotePg(c)).append(',');
    }
    conflictPart.setLength(conflictPart.length() - 1);
    StringBuilder update = new StringBuilder();
    for (String c : insertCols) {
      if (conflicts.contains(c)) {
        continue;
      }
      String q = JdbcMappedSqlValidator.quotePg(c);
      update.append(q).append("=EXCLUDED.").append(q).append(',');
    }
    if (update.isEmpty()) {
      return insert + " ON CONFLICT (" + conflictPart + ") DO NOTHING";
    }
    update.setLength(update.length() - 1);
    return insert + " ON CONFLICT (" + conflictPart + ") DO UPDATE SET " + update;
  }
}
