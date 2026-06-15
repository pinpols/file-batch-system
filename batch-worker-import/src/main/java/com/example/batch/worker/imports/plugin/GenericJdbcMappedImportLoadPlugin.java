package com.example.batch.worker.imports.plugin;

import com.example.batch.common.exception.WorkerConfigException;
import com.example.batch.common.jdbc.JdbcMappedSqlValidator;
import com.example.batch.common.plugin.IdempotencyCapability;
import com.example.batch.common.plugin.ImportLoadContext;
import com.example.batch.common.plugin.ImportLoadPlugin;
import com.example.batch.common.plugin.WorkerPluginIds;
import com.example.batch.common.rls.RlsTenantSessionSupport;
import com.example.batch.worker.imports.config.JdbcMappedImportSecurityProperties;
import com.example.batch.worker.imports.jdbc.ImportLoadStrategy;
import com.example.batch.worker.imports.jdbc.JdbcMappedImportSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyManager;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 通用 LOAD：通过模板驱动的列映射（白名单）向单张业务表执行 INSERT / UPSERT。 每次 {@link ImportLoadPlugin#loadChunk} 调用使用 JDBC
 * {@code batchUpdate} 处理整个批次（非逐行请求）。 通过 {@code query_param_schema.jdbcMappedImport} 或 {@code
 * jdbc_mapped_import} 配置。
 */
@Slf4j
@Component
public class GenericJdbcMappedImportLoadPlugin implements ImportLoadPlugin {

  private final JdbcTemplate jdbcTemplate;
  private final DataSource businessDataSource;
  private final ObjectMapper objectMapper;
  private final JdbcMappedImportSecurityProperties securityProperties;

  /** Phase A RLS:loadChunk 的 batchUpdate 需显式 tx 包 SET LOCAL,policy 才生效。 */
  private final TransactionTemplate txTemplate;

  public GenericJdbcMappedImportLoadPlugin(
      @Qualifier("importBusinessDataSource") DataSource importBusinessDataSource,
      ObjectMapper objectMapper,
      JdbcMappedImportSecurityProperties securityProperties) {
    this.businessDataSource = importBusinessDataSource;
    this.jdbcTemplate = new JdbcTemplate(importBusinessDataSource);
    this.objectMapper = objectMapper;
    this.securityProperties = securityProperties;
    this.txTemplate =
        new TransactionTemplate(new DataSourceTransactionManager(importBusinessDataSource));
  }

  @Override
  public String id() {
    return WorkerPluginIds.IMPORT_LOAD_JDBC_MAPPED;
  }

  /**
   * ADR-038 R3-3:本 plugin 通过模板 INSERT/UPSERT(典型业务表带 {@code UNIQUE(tenant_id, ...)} 约束 + {@code ON
   * CONFLICT DO NOTHING/UPDATE}),续跑重做 chunk 时 DB 层兜底重复写。
   */
  @Override
  public IdempotencyCapability idempotencyCapability() {
    return IdempotencyCapability.IDEMPOTENT_BY_UNIQUE_CONSTRAINT;
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
    // 地区(per-run):触发未传 region 时用模板 defaultRegion 兜底;allowedRegions 非空时做字典校验。
    // 用新局部变量(不重赋参数)——下方匿名内部类引用要求 effectively final。
    final ImportLoadContext loadContext = applyRegion(context, spec);

    List<String> insertCols = orderedInsertColumns(spec);
    if (spec.loadStrategy() == ImportLoadStrategy.PARTITION_REPLACE_COPY) {
      return copyChunk(loadContext, spec, insertCols, records);
    }
    if (spec.loadStrategy() == ImportLoadStrategy.PARTITION_STAGE_SWAP_COPY) {
      return copyChunk(loadContext, spec, insertCols, records, stagingTableName(loadContext, spec));
    }
    String sql = buildSql(spec, insertCols);
    // C-2.7 b / R2-P1-4: 模板未声明 conflict_columns → 纯 INSERT，partition reclaim 重试时已 COMMIT 的 chunk
    // 会再次落入 → 业务表重复行。开发期允许（INFO），但 prod 必须开 strictIdempotency=true（启动期 parse 即拒）。
    // 此处保留 ERROR 级日志 + 指明影响范围，运维按关键字 "idempotency=OFF" 监控并强制治理。
    if (spec.conflictColumns() == null || spec.conflictColumns().isEmpty()) {
      log.error(
          "jdbc-mapped-import LOAD running without ON CONFLICT clause: tenantId={},"
              + " template={}, schema={}, table={}, idempotency=OFF — RETRY WILL DUPLICATE ROWS."
              + " Add conflict_columns to template or set"
              + " batch.worker.import.jdbc-mapped.strict-idempotency=true to enforce.",
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
    // Phase A RLS:显式 tx 包 SET LOCAL + batchUpdate 共享同一 connection,触发 biz.* policy 过滤
    txTemplate.execute(
        status -> {
          RlsTenantSessionSupport.applyIfPresent(businessDataSource);
          jdbcTemplate.batchUpdate(
              sql,
              new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int i) throws SQLException {
                  Object[] args = buildArgs(insertCols, spec, records.get(i), loadContext);
                  for (int j = 0; j < args.length; j++) {
                    ps.setObject(j + 1, args[j]);
                  }
                }

                @Override
                public int getBatchSize() {
                  return n;
                }
              });
          return null;
        });
    return n;
  }

  public boolean isPartitionReplaceCopy(ImportLoadContext context) {
    JdbcMappedImportSpec spec = JdbcMappedImportSpec.parse(context.templateConfig(), objectMapper);
    return spec.loadStrategy() == ImportLoadStrategy.PARTITION_REPLACE_COPY
        || spec.loadStrategy() == ImportLoadStrategy.PARTITION_STAGE_SWAP_COPY;
  }

  public boolean isPartitionStageSwapCopy(ImportLoadContext context) {
    JdbcMappedImportSpec spec = JdbcMappedImportSpec.parse(context.templateConfig(), objectMapper);
    return spec.loadStrategy() == ImportLoadStrategy.PARTITION_STAGE_SWAP_COPY;
  }

  public void preparePartitionReplace(ImportLoadContext context) {
    JdbcMappedImportSpec spec = JdbcMappedImportSpec.parse(context.templateConfig(), objectMapper);
    spec.validateIdentifiers(
        securityProperties.getAllowedSchemas(), securityProperties.isStrictIdempotency());
    ImportLoadContext loadContext = applyRegion(context, spec);
    String sql = buildDeleteSql(spec);
    Object[] args = buildPartitionArgs(spec, loadContext);
    txTemplate.execute(
        status -> {
          RlsTenantSessionSupport.applyIfPresent(businessDataSource);
          int deleted =
              spec.loadStrategy() == ImportLoadStrategy.PARTITION_STAGE_SWAP_COPY
                  ? 0
                  : jdbcTemplate.update(sql, args);
          if (spec.loadStrategy() == ImportLoadStrategy.PARTITION_STAGE_SWAP_COPY) {
            prepareStageSwapTable(loadContext, spec);
          }
          if (log.isInfoEnabled()) {
            log.info(
                "jdbc-mapped-import partition replace prepared: tenantId={}, template={},"
                    + " schema={}, table={}, replacePartitionColumns={}, deletedRows={}",
                loadContext.tenantId(),
                loadContext.templateCode(),
                spec.schema(),
                spec.table(),
                spec.replacePartitionColumns(),
                deleted);
          }
          return null;
        });
  }

  public void finishPartitionStageSwap(ImportLoadContext context) {
    JdbcMappedImportSpec spec = JdbcMappedImportSpec.parse(context.templateConfig(), objectMapper);
    spec.validateIdentifiers(
        securityProperties.getAllowedSchemas(), securityProperties.isStrictIdempotency());
    ImportLoadContext loadContext = applyRegion(context, spec);
    String parent = tableName(spec);
    String partition = qualifiedTableName(spec.schema(), spec.stageSwap().partitionTable());
    String staging = stagingTableName(loadContext, spec);
    txTemplate.execute(
        status -> {
          RlsTenantSessionSupport.applyIfPresent(businessDataSource);
          jdbcTemplate.execute("ALTER TABLE " + parent + " DETACH PARTITION " + partition);
          jdbcTemplate.execute("DROP TABLE " + partition);
          jdbcTemplate.execute(
              "ALTER TABLE "
                  + staging
                  + " RENAME TO "
                  + JdbcMappedSqlValidator.quotePg(spec.stageSwap().partitionTable()));
          jdbcTemplate.execute(
              "ALTER TABLE "
                  + parent
                  + " ATTACH PARTITION "
                  + partition
                  + " "
                  + spec.stageSwap().attachClause());
          return null;
        });
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

  private Object valueForPartitionColumn(
      String col, JdbcMappedImportSpec spec, ImportLoadContext context) {
    if (col.equals(spec.tenantColumn())) {
      return context.tenantId();
    }
    String pattern = spec.systemBindings().get(col);
    if (pattern != null) {
      return resolveBinding(pattern, context);
    }
    throw new IllegalStateException("no partition binding for column: " + col);
  }

  private Object[] buildPartitionArgs(JdbcMappedImportSpec spec, ImportLoadContext context) {
    List<String> partitionCols = spec.replacePartitionColumns();
    Object[] args = new Object[partitionCols.size()];
    for (int i = 0; i < partitionCols.size(); i++) {
      args[i] = valueForPartitionColumn(partitionCols.get(i), spec, context);
    }
    return args;
  }

  /**
   * 地区解析:context.region(触发 metadata)优先,缺省用模板 defaultRegion 兜底;allowedRegions 非空时做字典校验, 非法地区直接
   * WorkerConfigException 拒绝。返回 region 已规整(含默认)的 context 供 ${region} binding 用。
   */
  static ImportLoadContext applyRegion(ImportLoadContext context, JdbcMappedImportSpec spec) {
    String region =
        context.region() != null && !context.region().isBlank()
            ? context.region()
            : spec.defaultRegion();
    List<String> allowed = spec.allowedRegions();
    if (allowed != null && !allowed.isEmpty() && (region == null || !allowed.contains(region))) {
      throw new WorkerConfigException(
          "import region not in allowedRegions: region=" + region + ", allowed=" + allowed);
    }
    if (Objects.equals(region, context.region())) {
      return context;
    }
    return new ImportLoadContext(
        context.tenantId(),
        context.jobCode(),
        context.traceId(),
        context.workerId(),
        context.sourceFileName(),
        context.batchNo(),
        context.bizDate(),
        context.bizType(),
        region,
        context.templateCode(),
        context.templateConfig());
  }

  static String resolveBinding(String pattern, ImportLoadContext context) {
    if (pattern == null) {
      return null;
    }
    return pattern
        .replace("${tenantId}", nullSafe(context.tenantId()))
        .replace("${traceId}", nullSafe(context.traceId()))
        .replace("${workerId}", nullSafe(context.workerId()))
        .replace("${sourceFileName}", nullSafe(context.sourceFileName()))
        .replace("${batchNo}", nullSafe(context.batchNo()))
        .replace("${bizDate}", nullSafe(context.bizDate()))
        .replace("${bizType}", nullSafe(context.bizType()))
        .replace("${region}", nullSafe(context.region()))
        .replace("${jobCode}", nullSafe(context.jobCode()))
        .replace("${templateCode}", nullSafe(context.templateCode()));
  }

  private static String nullSafe(String v) {
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

  private int copyChunk(
      ImportLoadContext context,
      JdbcMappedImportSpec spec,
      List<String> insertCols,
      List<Map<String, Object>> records) {
    return copyChunk(context, spec, insertCols, records, tableName(spec));
  }

  private int copyChunk(
      ImportLoadContext context,
      JdbcMappedImportSpec spec,
      List<String> insertCols,
      List<Map<String, Object>> records,
      String destinationTable) {
    String copySql = buildCopySql(destinationTable, insertCols);
    String csv = buildCopyCsv(insertCols, spec, records, context);
    int n = records.size();
    txTemplate.execute(
        status -> {
          RlsTenantSessionSupport.applyIfPresent(businessDataSource);
          Connection conn = DataSourceUtils.getConnection(businessDataSource);
          try {
            CopyManager copyManager = conn.unwrap(PGConnection.class).getCopyAPI();
            long copied = copyManager.copyIn(copySql, new StringReader(csv));
            if (copied != n) {
              throw new IllegalStateException(
                  "PostgreSQL COPY row count mismatch: expected=" + n + ", copied=" + copied);
            }
            return null;
          } catch (Exception ex) {
            throw new IllegalStateException("PostgreSQL COPY failed: " + ex.getMessage(), ex);
          } finally {
            DataSourceUtils.releaseConnection(conn, businessDataSource);
          }
        });
    return n;
  }

  private String buildDeleteSql(JdbcMappedImportSpec spec) {
    StringBuilder where = new StringBuilder();
    for (String c : spec.replacePartitionColumns()) {
      where.append(JdbcMappedSqlValidator.quotePg(c)).append("=? AND ");
    }
    where.setLength(where.length() - " AND ".length());
    return "DELETE FROM " + tableName(spec) + " WHERE " + where;
  }

  private void prepareStageSwapTable(ImportLoadContext context, JdbcMappedImportSpec spec) {
    String staging = stagingTableName(context, spec);
    jdbcTemplate.execute("DROP TABLE IF EXISTS " + staging);
    jdbcTemplate.execute(
        "CREATE TABLE " + staging + " (LIKE " + tableName(spec) + " INCLUDING ALL)");
  }

  private String buildCopySql(String destinationTable, List<String> insertCols) {
    StringBuilder colPart = new StringBuilder();
    for (String c : insertCols) {
      colPart.append(JdbcMappedSqlValidator.quotePg(c)).append(',');
    }
    colPart.setLength(colPart.length() - 1);
    return "COPY "
        + destinationTable
        + " ("
        + colPart
        + ") FROM STDIN WITH (FORMAT csv, NULL '\\N')";
  }

  private String tableName(JdbcMappedImportSpec spec) {
    return qualifiedTableName(spec.schema(), spec.table());
  }

  private String stagingTableName(ImportLoadContext context, JdbcMappedImportSpec spec) {
    String suffix =
        Integer.toHexString(
            Objects.hash(
                context.tenantId(),
                context.traceId(),
                context.batchNo(),
                context.bizDate(),
                context.templateCode()));
    return qualifiedTableName(
        spec.schema(), spec.stageSwap().partitionTable() + "__stage_" + suffix);
  }

  private String qualifiedTableName(String schema, String table) {
    return JdbcMappedSqlValidator.quotePg(schema) + "." + JdbcMappedSqlValidator.quotePg(table);
  }

  private String buildCopyCsv(
      List<String> insertCols,
      JdbcMappedImportSpec spec,
      List<Map<String, Object>> records,
      ImportLoadContext context) {
    StringBuilder sb = new StringBuilder(Math.max(1024, records.size() * insertCols.size() * 16));
    for (Map<String, Object> row : records) {
      for (int i = 0; i < insertCols.size(); i++) {
        if (i > 0) {
          sb.append(',');
        }
        appendCsvValue(sb, valueForColumn(insertCols.get(i), spec, row, context));
      }
      sb.append('\n');
    }
    return sb.toString();
  }

  private static void appendCsvValue(StringBuilder sb, Object value) {
    if (value == null) {
      sb.append("\\N");
      return;
    }
    String text = String.valueOf(value);
    boolean quote =
        text.equals("\\N")
            || text.indexOf(',') >= 0
            || text.indexOf('"') >= 0
            || text.indexOf('\n') >= 0
            || text.indexOf('\r') >= 0;
    if (!quote) {
      sb.append(text);
      return;
    }
    sb.append('"');
    for (int i = 0; i < text.length(); i++) {
      char ch = text.charAt(i);
      if (ch == '"') {
        sb.append("\"\"");
      } else {
        sb.append(ch);
      }
    }
    sb.append('"');
  }
}
