package io.github.pinpols.batch.worker.imports.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.exception.WorkerConfigException;
import io.github.pinpols.batch.common.jdbc.DataSourceConnectionLease;
import io.github.pinpols.batch.common.jdbc.JdbcMappedSqlValidator;
import io.github.pinpols.batch.common.plugin.IdempotencyCapability;
import io.github.pinpols.batch.common.plugin.ImportLoadContext;
import io.github.pinpols.batch.common.plugin.ImportLoadPlugin;
import io.github.pinpols.batch.common.plugin.WorkerPluginIds;
import io.github.pinpols.batch.common.rls.RlsTenantSessionSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.worker.imports.config.JdbcMappedImportSecurityProperties;
import io.github.pinpols.batch.worker.imports.jdbc.ImportLoadStrategy;
import io.github.pinpols.batch.worker.imports.jdbc.JdbcMappedImportSpec;
import io.github.pinpols.batch.worker.imports.stage.format.ParseSupport;
import java.io.StringReader;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyManager;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
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
    DataSourceTransactionManager transactionManager =
        new DataSourceTransactionManager(importBusinessDataSource);
    this.txTemplate = new TransactionTemplate(transactionManager);
  }

  @Override
  public String id() {
    return WorkerPluginIds.IMPORT_LOAD_JDBC_MAPPED;
  }

  /**
   * ADR-038 R3-3:本 plugin 通过模板 INSERT/UPSERT(典型业务表带 {@code UNIQUE(tenant_id, ...)} 约束 + {@code ON
   * CONFLICT DO NOTHING/UPDATE}),续跑重做 chunk 时 DB 层幂等约束防重复写。
   */
  @Override
  public IdempotencyCapability idempotencyCapability() {
    return IdempotencyCapability.IDEMPOTENT_BY_UNIQUE_CONSTRAINT;
  }

  @Override
  @SuppressWarnings("java:S2077")
  public int loadChunk(ImportLoadContext context, List<Map<String, Object>> records)
      throws Exception {
    if (records == null || records.isEmpty()) {
      return 0;
    }
    JdbcMappedImportSpec spec = JdbcMappedImportSpec.parse(context.templateConfig(), objectMapper);
    // BATCH_UPSERT 必须声明业务唯一键；该重放安全约束不提供配置关闭路径。
    spec.validateIdentifiers(securityProperties.getAllowedSchemas());
    // 地区(per-run):触发未传 region 时用模板 defaultRegion 回退;allowedRegions 非空时做字典校验。
    // 用新局部变量(不重赋参数)——下方匿名内部类引用要求 effectively final。
    final ImportLoadContext loadContext = applyRegion(context, spec);

    List<String> insertCols = orderedInsertColumns(spec);
    if (spec.loadStrategy() == ImportLoadStrategy.PARTITION_REPLACE_COPY) {
      return copyChunk(loadContext, spec, insertCols, records);
    }
    if (spec.loadStrategy() == ImportLoadStrategy.PARTITION_STAGE_SWAP_COPY) {
      String stagingTable = stagingTableName(loadContext, spec);
      return copyChunk(loadContext, spec, insertCols, records, stagingTable);
    }
    String sql = buildSql(spec, insertCols);
    if (log.isInfoEnabled()) {
      log.info(
          "jdbc-mapped-import LOAD idempotency=ON: tenantId={}, template={}, schema={},"
              + " table={}, conflictColumns={}",
          context.tenantId(),
          context.templateCode(),
          spec.schema(),
          spec.table(),
          spec.conflictColumns());
    }
    List<Map<String, Object>> orderedRecords =
        orderedRecordsForConflictColumns(loadContext, spec, insertCols, records);
    int n = orderedRecords.size();
    // Phase A RLS:显式 tx 包 SET LOCAL + batchUpdate 共享同一 connection,触发 biz.* policy 过滤
    txTemplate.execute(status -> {
      RlsTenantSessionSupport.applyIfPresent(businessDataSource);
      jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
        @Override
        public void setValues(PreparedStatement ps, int i) throws SQLException {
          Object[] args = buildArgs(insertCols, spec, orderedRecords.get(i), loadContext);
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

  static List<Map<String, Object>> orderedRecordsForConflictColumns(
      ImportLoadContext context,
      JdbcMappedImportSpec spec,
      List<String> insertCols,
      List<Map<String, Object>> records) {
    if (EmptyChecks.isEmpty(spec.conflictColumns()) || records.size() < 2) {
      return records;
    }
    List<Integer> conflictIndexes =
        spec.conflictColumns().stream().map(insertCols::indexOf).toList();
    if (conflictIndexes.stream().anyMatch(index -> index < 0)) {
      return records;
    }
    List<Map<String, Object>> ordered = new ArrayList<>(records);
    Comparator<Map<String, Object>> conflictColumnOrder = Comparator.comparing(
        row -> conflictSortKey(context, spec, insertCols, conflictIndexes, row),
        Comparator.nullsFirst(String::compareTo));
    ordered.sort(conflictColumnOrder);
    return ordered;
  }

  private static String conflictSortKey(
      ImportLoadContext context,
      JdbcMappedImportSpec spec,
      List<String> insertCols,
      List<Integer> conflictIndexes,
      Map<String, Object> row) {
    Object[] args = buildArgs(insertCols, spec, row, context);
    StringBuilder key = new StringBuilder();
    for (Integer index : conflictIndexes) {
      key.append('\u0000').append(String.valueOf(args[index]));
    }
    return key.toString();
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

  @SuppressWarnings("java:S2077")
  public void preparePartitionReplace(ImportLoadContext context) {
    JdbcMappedImportSpec spec = JdbcMappedImportSpec.parse(context.templateConfig(), objectMapper);
    spec.validateIdentifiers(securityProperties.getAllowedSchemas());
    ImportLoadContext loadContext = applyRegion(context, spec);
    String sql = buildDeleteSql(spec);
    Object[] args = buildPartitionArgs(spec, loadContext);
    txTemplate.execute(status -> {
      RlsTenantSessionSupport.applyIfPresent(businessDataSource);
      int deleted = spec.loadStrategy() == ImportLoadStrategy.PARTITION_STAGE_SWAP_COPY
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

  @SuppressWarnings("java:S2077")
  public void finishPartitionStageSwap(ImportLoadContext context) {
    JdbcMappedImportSpec spec = JdbcMappedImportSpec.parse(context.templateConfig(), objectMapper);
    spec.validateIdentifiers(securityProperties.getAllowedSchemas());
    ImportLoadContext loadContext = applyRegion(context, spec);
    String parent = tableName(spec);
    String partition = qualifiedTableName(spec.schema(), spec.stageSwap().partitionTable());
    String staging = stagingTableName(loadContext, spec);
    txTemplate.execute(status -> {
      RlsTenantSessionSupport.applyIfPresent(businessDataSource);
      jdbcTemplate.execute("ALTER TABLE " + parent + " DETACH PARTITION " + partition);
      jdbcTemplate.execute("DROP TABLE " + partition);
      String partitionTableName = spec.stageSwap().partitionTable();
      String quotedPartitionTableName = JdbcMappedSqlValidator.quotePg(partitionTableName);
      String renamePartitionSql =
          "ALTER TABLE " + staging + " RENAME TO " + quotedPartitionTableName;
      jdbcTemplate.execute(renamePartitionSql);
      jdbcTemplate.execute("ALTER TABLE "
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

  private static Object[] buildArgs(
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

  private static Object valueForColumn(
      String col, JdbcMappedImportSpec spec, Map<String, Object> row, ImportLoadContext context) {
    if (col.equals(spec.tenantColumn())) {
      return context.tenantId();
    }
    for (JdbcMappedImportSpec.ColumnMapping m : spec.columnMappings()) {
      if (m.to().equals(col)) {
        Object sourceValue = resolveRowValue(row, m.from());
        return coerceMappedValue(sourceValue, m);
      }
    }
    String pattern = spec.systemBindings().get(col);
    if (pattern != null) {
      return resolveSystemBinding(pattern, context);
    }
    throw new IllegalStateException("no binding for column: " + col);
  }

  private static Object coerceMappedValue(
      Object value, JdbcMappedImportSpec.ColumnMapping mapping) {
    if (EmptyChecks.isNull(value) || EmptyChecks.isNull(mapping.type())) {
      return value;
    }
    String normalizedType = mapping.type().trim();
    String type = normalizedType.toUpperCase(Locale.ROOT);
    boolean valueNeedsNoJdbcTypeConversion =
        EmptyChecks.isEmpty(type) || value instanceof Number || value instanceof Boolean;
    if (valueNeedsNoJdbcTypeConversion) {
      return value;
    }
    String rawText = String.valueOf(value);
    String text = rawText.trim();
    if (EmptyChecks.isEmpty(text)) {
      return null;
    }
    try {
      return switch (type) {
        case "DECIMAL", "NUMBER", "NUMERIC", "BIGDECIMAL", "INTEGER", "INT", "LONG", "BIGINT" ->
          new BigDecimal(text);
        case "DATE", "LOCAL_DATE" -> parseLocalDate(text, mapping.format());
        case "TIMESTAMP", "DATETIME", "LOCAL_DATE_TIME" -> LocalDateTime.parse(text);
        case "OFFSET_DATE_TIME", "TIMESTAMPTZ" -> OffsetDateTime.parse(text);
        case "BOOLEAN", "BOOL" -> Boolean.valueOf(text);
        default -> value;
      };
    } catch (RuntimeException ex) {
      throw new WorkerConfigException("jdbc-mapped-import value type conversion failed: column="
          + mapping.to()
          + ", sourceField="
          + mapping.from()
          + ", type="
          + mapping.type()
          + ", value="
          + text);
    }
  }

  private static LocalDate parseLocalDate(String text, String format) {
    if (EmptyChecks.isBlank(format) || "yyyy-MM-dd".equals(format)) {
      return LocalDate.parse(text);
    }
    DateTimeFormatter formatter = DateTimeFormatter.ofPattern(format);
    return LocalDate.parse(text, formatter);
  }

  /**
   * 取 {@code from} 对应的行值:先精确命中(快路径);未命中再按 {@link ParseSupport#matchKey} 大小写/下划线容错匹配 （如文件表头 {@code
   * Phone}/{@code PHONE} 命中 {@code from=phone}),仍无则 null。
   */
  private static Object resolveRowValue(Map<String, Object> row, String from) {
    if (row.containsKey(from)) {
      return row.get(from);
    }
    String mk = ParseSupport.matchKey(from);
    for (Map.Entry<String, Object> e : row.entrySet()) {
      if (ParseSupport.matchKey(e.getKey()).equals(mk)) {
        return e.getValue();
      }
    }
    return null;
  }

  private Object valueForPartitionColumn(
      String col, JdbcMappedImportSpec spec, ImportLoadContext context) {
    if (col.equals(spec.tenantColumn())) {
      return context.tenantId();
    }
    String pattern = spec.systemBindings().get(col);
    if (pattern != null) {
      return resolveSystemBinding(pattern, context);
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
   * 地区解析:context.region(触发 metadata)优先,缺省用模板 defaultRegion 回退;allowedRegions 非空时做字典校验, 非法地区直接
   * WorkerConfigException 拒绝。返回 region 已规整(含默认)的 context 供 ${region} binding 用。
   */
  static ImportLoadContext applyRegion(ImportLoadContext context, JdbcMappedImportSpec spec) {
    String region = context.region() != null && !context.region().isBlank()
        ? context.region()
        : spec.defaultRegion();
    List<String> allowed = spec.allowedRegions();
    boolean regionViolatesAllowlist =
        EmptyChecks.isNotNull(allowed) && !EmptyChecks.isEmpty(allowed) && (EmptyChecks.isNull(region) || !allowed.contains(region));
    if (regionViolatesAllowlist) {
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
    if (EmptyChecks.isNull(pattern)) {
      return null;
    }
    String resolvedPattern = pattern;
    String tenantId = nullSafe(context.tenantId());
    resolvedPattern = resolvedPattern.replace("${tenantId}", tenantId);
    String traceId = nullSafe(context.traceId());
    resolvedPattern = resolvedPattern.replace("${traceId}", traceId);
    String workerId = nullSafe(context.workerId());
    resolvedPattern = resolvedPattern.replace("${workerId}", workerId);
    String sourceFileName = nullSafe(context.sourceFileName());
    resolvedPattern = resolvedPattern.replace("${sourceFileName}", sourceFileName);
    String batchNo = nullSafe(context.batchNo());
    resolvedPattern = resolvedPattern.replace("${batchNo}", batchNo);
    String bizDate = nullSafe(context.bizDate());
    resolvedPattern = resolvedPattern.replace("${bizDate}", bizDate);
    String bizType = nullSafe(context.bizType());
    resolvedPattern = resolvedPattern.replace("${bizType}", bizType);
    String region = nullSafe(context.region());
    resolvedPattern = resolvedPattern.replace("${region}", region);
    String jobCode = nullSafe(context.jobCode());
    resolvedPattern = resolvedPattern.replace("${jobCode}", jobCode);
    String templateCode = nullSafe(context.templateCode());
    return resolvedPattern.replace("${templateCode}", templateCode);
  }

  private static Object resolveSystemBinding(String pattern, ImportLoadContext context) {
    String value = resolveBinding(pattern, context);
    if ("${bizDate}".equals(pattern) && EmptyChecks.isNotBlank(value)) {
      return LocalDate.parse(value);
    }
    return value;
  }

  private static String nullSafe(String v) {
    return v == null ? "" : v;
  }

  private String buildSql(JdbcMappedImportSpec spec, List<String> insertCols) {
    String fq = JdbcMappedSqlValidator.quotePg(spec.schema())
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
    String targetTable = tableName(spec);
    return copyChunk(context, spec, insertCols, records, targetTable);
  }

  @SuppressWarnings("java:S2077")
  private int copyChunk(
      ImportLoadContext context,
      JdbcMappedImportSpec spec,
      List<String> insertCols,
      List<Map<String, Object>> records,
      String destinationTable) {
    String copySql = buildCopySql(destinationTable, insertCols);
    String csv = buildCopyCsv(insertCols, spec, records, context);
    int n = records.size();
    txTemplate.execute(status -> {
      RlsTenantSessionSupport.applyIfPresent(businessDataSource);
      try (DataSourceConnectionLease lease =
          DataSourceConnectionLease.acquire(businessDataSource)) {
        Connection conn = lease.connection();
        if (isRowLevelSecurityEnabled(conn, destinationTable)) {
          UUID tempTableUuid = UUID.randomUUID();
          String tempTableUuidText = tempTableUuid.toString();
          String tempTableSuffix = tempTableUuidText.replace("-", "");
          String tempTable = "batch_import_copy_" + tempTableSuffix;
          String quotedTempTable = JdbcMappedSqlValidator.quotePg(tempTable);
          try (Statement statement = conn.createStatement()) {
            statement.execute("CREATE TEMP TABLE " + quotedTempTable + " ON COMMIT DROP AS SELECT "
                + quotedColumns(insertCols) + " FROM " + destinationTable + " WITH NO DATA");
          }
          log.info(
              "jdbc-mapped-import COPY staged before RLS-protected insert:"
                  + " tenantId={}, table={}, rows={}",
              context.tenantId(),
              destinationTable,
              n);
          CopyManager copyManager = conn.unwrap(PGConnection.class).getCopyAPI();
          String tempDestinationTable = "pg_temp." + quotedTempTable;
          String tempCopySql = buildCopySql(tempDestinationTable, insertCols);
          long copied = copyManager.copyIn(tempCopySql, new StringReader(csv));
          if (copied != n) {
            throw new IllegalStateException(
                "PostgreSQL temporary COPY row count mismatch: expected=" + n + ", copied="
                    + copied);
          }
          String insertSql = "INSERT INTO " + destinationTable + " ("
              + quotedColumns(insertCols) + ") SELECT " + quotedColumns(insertCols)
              + " FROM pg_temp." + quotedTempTable;
          try (Statement statement = conn.createStatement()) {
            int inserted = statement.executeUpdate(insertSql);
            if (inserted != n) {
              throw new IllegalStateException("RLS-protected insert row count mismatch: expected="
                  + n + ", inserted=" + inserted);
            }
          }
          return null;
        }
        CopyManager copyManager = conn.unwrap(PGConnection.class).getCopyAPI();
        long copied = copyManager.copyIn(copySql, new StringReader(csv));
        if (copied != n) {
          throw new IllegalStateException(
              "PostgreSQL COPY row count mismatch: expected=" + n + ", copied=" + copied);
        }
        return null;
      } catch (Exception ex) {
        throw new IllegalStateException("Partition load failed: " + ex.getMessage(), ex);
      }
    });
    return n;
  }

  private boolean isRowLevelSecurityEnabled(Connection connection, String destinationTable)
      throws SQLException {
    String[] parts = destinationTable.split("\\.", 2);
    if (parts.length != 2) {
      throw new IllegalArgumentException("qualified destination table required");
    }
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT c.relrowsecurity FROM pg_class c"
            + " JOIN pg_namespace n ON n.oid = c.relnamespace"
            + " WHERE n.nspname = ? AND c.relname = ?")) {
      statement.setString(1, parts[0].replace("\"", ""));
      statement.setString(2, parts[1].replace("\"", ""));
      try (ResultSet result = statement.executeQuery()) {
        return result.next() && result.getBoolean(1);
      }
    }
  }

  private String buildDeleteSql(JdbcMappedImportSpec spec) {
    StringBuilder where = new StringBuilder();
    for (String c : spec.replacePartitionColumns()) {
      where.append(JdbcMappedSqlValidator.quotePg(c)).append("=? AND ");
    }
    where.setLength(where.length() - " AND ".length());
    return "DELETE FROM " + tableName(spec) + " WHERE " + where;
  }

  @SuppressWarnings("java:S2077")
  private void prepareStageSwapTable(ImportLoadContext context, JdbcMappedImportSpec spec) {
    String staging = stagingTableName(context, spec);
    jdbcTemplate.execute("DROP TABLE IF EXISTS " + staging);
    jdbcTemplate.execute(
        "CREATE TABLE " + staging + " (LIKE " + tableName(spec) + " INCLUDING ALL)");
  }

  private String buildCopySql(String destinationTable, List<String> insertCols) {
    return "COPY " + destinationTable + " (" + quotedColumns(insertCols)
        + ") FROM STDIN WITH (FORMAT csv, NULL '\\N')";
  }

  private String quotedColumns(List<String> columns) {
    StringBuilder colPart = new StringBuilder();
    for (String c : columns) {
      colPart.append(JdbcMappedSqlValidator.quotePg(c)).append(',');
    }
    colPart.setLength(colPart.length() - 1);
    return colPart.toString();
  }

  private String tableName(JdbcMappedImportSpec spec) {
    return qualifiedTableName(spec.schema(), spec.table());
  }

  private String stagingTableName(ImportLoadContext context, JdbcMappedImportSpec spec) {
    String tenantId = context.tenantId();
    String traceId = context.traceId();
    String batchNo = context.batchNo();
    String bizDate = context.bizDate();
    String templateCode = context.templateCode();
    int stagingIdentityHash =
        Objects.hash(tenantId, traceId, batchNo, bizDate, templateCode);
    String suffix = Integer.toHexString(stagingIdentityHash);
    String schema = spec.schema();
    String partitionTable = spec.stageSwap().partitionTable();
    String stagingTable = partitionTable + "__stage_" + suffix;
    return qualifiedTableName(schema, stagingTable);
  }

  private String qualifiedTableName(String schema, String table) {
    String quotedSchema = JdbcMappedSqlValidator.quotePg(schema);
    String quotedTable = JdbcMappedSqlValidator.quotePg(table);
    return quotedSchema + "." + quotedTable;
  }

  private String buildCopyCsv(
      List<String> insertCols,
      JdbcMappedImportSpec spec,
      List<Map<String, Object>> records,
      ImportLoadContext context) {
    int estimatedCsvCapacity = Math.max(1024, records.size() * insertCols.size() * 16);
    StringBuilder sb = new StringBuilder(estimatedCsvCapacity);
    for (Map<String, Object> row : records) {
      for (int i = 0; i < insertCols.size(); i++) {
        if (i > 0) {
          sb.append(',');
        }
        String columnName = insertCols.get(i);
        Object columnValue = valueForColumn(columnName, spec, row, context);
        appendCsvValue(sb, columnValue);
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
    boolean quote = text.equals("\\N")
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
