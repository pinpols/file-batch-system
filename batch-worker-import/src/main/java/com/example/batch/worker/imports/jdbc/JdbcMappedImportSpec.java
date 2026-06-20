package com.example.batch.worker.imports.jdbc;

import com.example.batch.common.exception.WorkerConfigException;
import com.example.batch.common.jdbc.JdbcMappedSqlValidator;
import com.example.batch.common.logging.SwallowedExceptionLogger;
import com.example.batch.common.utils.PostgresqlJsonbTexts;
import com.example.batch.common.utils.Texts;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 从 {@code file_template_config.query_param_schema.jdbcMappedImport} 或顶层 {@code
 * jdbc_mapped_import}（JSON 对象）解析而来。
 */
public record JdbcMappedImportSpec(
    String schema,
    String table,
    String tenantColumn,
    List<ColumnMapping> columnMappings,
    List<String> conflictColumns,
    Map<String, String> systemBindings,
    String defaultRegion,
    List<String> allowedRegions,
    ImportLoadStrategy loadStrategy,
    List<String> replacePartitionColumns,
    StageSwap stageSwap) {
  public record ColumnMapping(String from, String to) {}

  public record StageSwap(String partitionTable, String attachClause) {}

  public JdbcMappedImportSpec {
    columnMappings = columnMappings == null ? List.of() : List.copyOf(columnMappings);
    conflictColumns = conflictColumns == null ? List.of() : List.copyOf(conflictColumns);
    systemBindings = systemBindings == null ? Map.of() : new LinkedHashMap<>(systemBindings);
    allowedRegions = allowedRegions == null ? List.of() : List.copyOf(allowedRegions);
    loadStrategy = loadStrategy == null ? ImportLoadStrategy.BATCH_UPSERT : loadStrategy;
    replacePartitionColumns =
        replacePartitionColumns == null ? List.of() : List.copyOf(replacePartitionColumns);
  }

  public static JdbcMappedImportSpec parse(
      Map<String, Object> templateConfig, ObjectMapper objectMapper) {
    Map<String, Object> root = extractJdbcMappedImport(templateConfig, objectMapper);
    if (root.isEmpty()) {
      throw new WorkerConfigException(
          "jdbc_mapped_import spec missing (use query_param_schema.jdbcMappedImport or"
              + " jdbc_mapped_import)");
    }
    String schema = String.valueOf(root.getOrDefault("schema", "biz")).trim();
    String table = required(root, "table");
    String tenantColumn = required(root, "tenantColumn");
    // columnMappings 可缺省(不写 / 空 [] / null)——此时从模板 field_mappings 推断,
    // 显式项按 from 覆盖推断项、并追加新项("只配置明确差异")。
    List<ColumnMapping> explicit = parseMappings(root.get("columnMappings"));
    List<ColumnMapping> inferred = inferFromFieldMappings(templateConfig, objectMapper);
    List<ColumnMapping> mappings = mergeMappings(inferred, explicit);
    if (mappings.isEmpty()) {
      throw new WorkerConfigException(
          "jdbc_mapped_import.columnMappings is required and could not be inferred from"
              + " field_mappings (declare columnMappings or field_mappings[*].name/targetColumn)");
    }
    // B2:conflictColumns 非空但漏了 tenant 列时自动前置——多租隔离下幂等键几乎必含 tenant_id,
    // 漏填基本是跨租户冲突 bug。空列表(不走 UPSERT)不动。
    List<String> conflicts =
        ensureTenantInConflicts(parseStringList(root.get("conflictColumns")), tenantColumn);
    // B3:standardAuditBindings=true 一键展开标准审计绑定(用户显式 systemBindings 覆盖同名项),
    // 省掉逐条手写 ${...} 样板。是 opt-in 开关而非默认注入,目标表是否有这些列由配置方负责。
    Map<String, String> system = parseSystemBindings(root.get("systemBindings"));
    if (Boolean.TRUE.equals(toBoolean(root.get("standardAuditBindings")))) {
      system = withStandardAuditBindings(system);
    }
    // 地区维度(per-run):defaultRegion 触发未传 region 时兜底;allowedRegions 非空时作字典校验。
    Object dr = root.get("defaultRegion");
    String defaultRegion = dr == null ? null : String.valueOf(dr).trim();
    List<String> allowedRegions = parseStringList(root.get("allowedRegions"));
    ImportLoadStrategy loadStrategy = ImportLoadStrategy.parse(root.get("loadStrategy"));
    List<String> replacePartitionColumns = parseStringList(root.get("replacePartitionColumns"));
    if (replacePartitionColumns.isEmpty()) {
      replacePartitionColumns = parseStringList(root.get("partitionReplaceColumns"));
    }
    StageSwap stageSwap = parseStageSwap(root.get("stageSwap"));
    return new JdbcMappedImportSpec(
        schema,
        table,
        tenantColumn,
        mappings,
        conflicts,
        system,
        defaultRegion,
        allowedRegions,
        loadStrategy,
        replacePartitionColumns,
        stageSwap);
  }

  private static String required(Map<String, Object> root, String key) {
    Object v = root.get(key);
    if (v == null || !Texts.hasText(String.valueOf(v))) {
      throw new WorkerConfigException("jdbc_mapped_import." + key + " is required");
    }
    return String.valueOf(v).trim();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> extractJdbcMappedImport(
      Map<String, Object> templateConfig, ObjectMapper objectMapper) {
    if (templateConfig == null) {
      return Map.of();
    }
    Object direct = templateConfig.get("jdbc_mapped_import");
    if (direct instanceof Map<?, ?> m) {
      return (Map<String, Object>) m;
    }
    Object qps = templateConfig.get("query_param_schema");
    Map<String, Object> qpsMap = toMap(qps, objectMapper);
    Object nested = qpsMap.get("jdbcMappedImport");
    if (nested instanceof Map<?, ?> m) {
      return (Map<String, Object>) m;
    }
    return Map.of();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> toMap(Object raw, ObjectMapper objectMapper) {
    if (raw instanceof Map<?, ?> m) {
      Map<String, Object> out = new LinkedHashMap<>();
      m.forEach((k, v) -> out.put(String.valueOf(k), v));
      return out;
    }
    String jsonText = extractJsonText(raw);
    if (jsonText != null && Texts.hasText(jsonText)) {
      try {
        return objectMapper.readValue(jsonText, Map.class);
      } catch (Exception ignored) {
        SwallowedExceptionLogger.warn(JdbcMappedImportSpec.class, "catch:Exception", ignored);

        return Map.of();
      }
    }
    return Map.of();
  }

  /**
   * PG JDBC 对 {@code json}/{@code jsonb} 常映射为 {@code org.postgresql.util.PGobject}；driver 为 {@code
   * runtime} 依赖，用 {@link PostgresqlJsonbTexts} 反射取 JSON 文本后再解析。
   */
  private static String extractJsonText(Object raw) {
    if (raw instanceof String text) {
      return text;
    }
    return PostgresqlJsonbTexts.tryExtract(raw);
  }

  private static List<ColumnMapping> parseMappings(Object raw) {
    if (!(raw instanceof List<?> list)) {
      return List.of();
    }
    List<ColumnMapping> out = new ArrayList<>();
    for (Object item : list) {
      if (item instanceof Map<?, ?> m) {
        Object from = m.get("from");
        Object to = m.get("to");
        if (from != null && to != null) {
          out.add(new ColumnMapping(String.valueOf(from).trim(), String.valueOf(to).trim()));
        }
      }
    }
    return out;
  }

  /**
   * 从模板顶层 {@code field_mappings} 推断列映射:每个声明字段产出一条 {@code from(name) -> to}。{@code to} 优先取显式 {@code
   * targetColumn},缺省则对 {@code name} 做 {@link #normalizeColumn} 归一化(驼峰/下划线/大小写 写法差异默认兼容)。{@code
   * persist:false} 的字段只参与解析校验、不入库,故不进推断集。
   */
  private static List<ColumnMapping> inferFromFieldMappings(
      Map<String, Object> templateConfig, ObjectMapper objectMapper) {
    List<ColumnMapping> out = new ArrayList<>();
    for (Map<String, Object> fm : extractFieldMappings(templateConfig, objectMapper)) {
      Object nameRaw = fm.get("name");
      if (nameRaw == null || !Texts.hasText(String.valueOf(nameRaw))) {
        continue;
      }
      if (Boolean.FALSE.equals(toBoolean(fm.get("persist")))) {
        continue;
      }
      String name = String.valueOf(nameRaw).trim();
      Object target = fm.get("targetColumn");
      String to =
          target != null && Texts.hasText(String.valueOf(target))
              ? String.valueOf(target).trim()
              : normalizeColumn(name);
      if (Texts.hasText(to)) {
        out.add(new ColumnMapping(name, to));
      }
    }
    return out;
  }

  /**
   * 合并推断项与显式项:显式按 {@code from} 覆盖同名推断项,其余显式项追加。顺序 = 未被覆盖的推断项在前、显式项在后。 不在此塌缩重复(fan-out / 碰撞),交由
   * {@link #validateMappingCardinality} 显式拒绝。
   */
  private static List<ColumnMapping> mergeMappings(
      List<ColumnMapping> inferred, List<ColumnMapping> explicit) {
    if (explicit.isEmpty()) {
      return inferred;
    }
    Set<String> explicitFroms = new LinkedHashSet<>();
    for (ColumnMapping e : explicit) {
      explicitFroms.add(e.from());
    }
    List<ColumnMapping> out = new ArrayList<>();
    for (ColumnMapping inf : inferred) {
      if (!explicitFroms.contains(inf.from())) {
        out.add(inf);
      }
    }
    out.addAll(explicit);
    return out;
  }

  private static List<Map<String, Object>> extractFieldMappings(
      Map<String, Object> templateConfig, ObjectMapper objectMapper) {
    if (templateConfig == null) {
      return List.of();
    }
    Object raw = templateConfig.get("field_mappings");
    if (raw == null) {
      return List.of();
    }
    List<?> list;
    if (raw instanceof List<?> l) {
      list = l;
    } else {
      String text = extractJsonText(raw);
      if (text == null || !Texts.hasText(text)) {
        return List.of();
      }
      try {
        list = objectMapper.readValue(text, List.class);
      } catch (Exception ignored) {
        SwallowedExceptionLogger.warn(JdbcMappedImportSpec.class, "catch:Exception", ignored);
        return List.of();
      }
    }
    List<Map<String, Object>> out = new ArrayList<>();
    for (Object item : list) {
      if (item instanceof Map<?, ?> m) {
        Map<String, Object> entry = new LinkedHashMap<>();
        m.forEach((k, v) -> entry.put(String.valueOf(k), v));
        out.add(entry);
      }
    }
    return out;
  }

  private static Boolean toBoolean(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Boolean b) {
      return b;
    }
    String s = String.valueOf(value).trim();
    if ("false".equalsIgnoreCase(s)) {
      return false;
    }
    if ("true".equalsIgnoreCase(s)) {
      return true;
    }
    return null;
  }

  /**
   * 字段名 → snake_case 列名的确定性归一化:驼峰/连续大写边界切分 + 全小写 + 下划线/连字符/空格折叠为单下划线。 {@code customerNo}/{@code
   * CUSTOMER_NO}/{@code customer_no} 都归一为 {@code customer_no};{@code customerHTTPUrl} → {@code
   * customer_http_url}。仅消写法差异,不做相似度模糊匹配。
   */
  static String normalizeColumn(String name) {
    if (name == null) {
      return "";
    }
    String s = name.trim();
    StringBuilder sb = new StringBuilder(s.length() + 4);
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '_' || c == '-' || c == ' ') {
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '_') {
          sb.append('_');
        }
        continue;
      }
      if (Character.isUpperCase(c)) {
        boolean prevLowerOrDigit =
            i > 0 && (Character.isLowerCase(s.charAt(i - 1)) || Character.isDigit(s.charAt(i - 1)));
        boolean nextLower = i + 1 < s.length() && Character.isLowerCase(s.charAt(i + 1));
        if (sb.length() > 0
            && sb.charAt(sb.length() - 1) != '_'
            && (prevLowerOrDigit || nextLower)) {
          sb.append('_');
        }
      }
      sb.append(Character.toLowerCase(c));
    }
    return sb.toString();
  }

  private static List<String> parseStringList(Object raw) {
    if (raw instanceof List<?> list) {
      List<String> out = new ArrayList<>();
      for (Object o : list) {
        if (o != null && Texts.hasText(String.valueOf(o))) {
          out.add(String.valueOf(o).trim());
        }
      }
      return out;
    }
    return List.of();
  }

  private static Map<String, String> parseSystemBindings(Object raw) {
    if (!(raw instanceof Map<?, ?> m)) {
      return Map.of();
    }
    Map<String, String> out = new LinkedHashMap<>();
    m.forEach(
        (k, v) -> {
          if (k != null && v != null) {
            out.put(String.valueOf(k).trim(), String.valueOf(v).trim());
          }
        });
    return out;
  }

  /** B3 标准审计绑定:平台运行时变量 → 常见审计列。用户显式 systemBindings 同名项优先。 */
  private static final Map<String, String> STANDARD_AUDIT_BINDINGS =
      Map.of(
          "source_file_name", "${sourceFileName}",
          "source_batch_no", "${batchNo}",
          "source_trace_id", "${traceId}",
          "created_by", "${workerId}",
          "updated_by", "${workerId}");

  private static Map<String, String> withStandardAuditBindings(Map<String, String> explicit) {
    Map<String, String> out = new LinkedHashMap<>(STANDARD_AUDIT_BINDINGS);
    out.putAll(explicit); // 用户显式项覆盖标准默认
    return out;
  }

  private static List<String> ensureTenantInConflicts(List<String> conflicts, String tenantColumn) {
    if (conflicts.isEmpty() || conflicts.contains(tenantColumn)) {
      return conflicts;
    }
    List<String> out = new ArrayList<>(conflicts.size() + 1);
    out.add(tenantColumn);
    out.addAll(conflicts);
    return out;
  }

  private static StageSwap parseStageSwap(Object raw) {
    if (!(raw instanceof Map<?, ?> m)) {
      return null;
    }
    Object partitionTable = m.get("partitionTable");
    Object attachClause = m.get("attachClause");
    if (partitionTable == null && attachClause == null) {
      return null;
    }
    return new StageSwap(
        partitionTable == null ? null : String.valueOf(partitionTable).trim(),
        attachClause == null ? null : String.valueOf(attachClause).trim());
  }

  /**
   * A-3.5 严格幂等模式入口：strictIdempotency=true 时 conflictColumns 必须非空， 否则 {@code parse +
   * validateIdentifiers} 阶段直接拒绝加载模板。
   */
  public void validateIdentifiers(Collection<String> allowedSchemas, boolean strictIdempotency) {
    validateIdentifiers(allowedSchemas);
    if (strictIdempotency
        && loadStrategy == ImportLoadStrategy.BATCH_UPSERT
        && (conflictColumns == null || conflictColumns.isEmpty())) {
      throw new WorkerConfigException(
          "jdbc-mapped-import template missing conflictColumns under strictIdempotency: "
              + "schema="
              + schema
              + ", table="
              + table
              + " — declare unique business key columns to enable ON CONFLICT DO NOTHING");
    }
  }

  public void validateIdentifiers(Collection<String> allowedSchemas) {
    validateMappingCardinality();
    JdbcMappedSqlValidator.requireInAllowlist(schema, allowedSchemas);
    JdbcMappedSqlValidator.requireIdentifier(table, "table");
    JdbcMappedSqlValidator.requireIdentifier(tenantColumn, "tenantColumn");
    Set<String> allCols = new LinkedHashSet<>();
    allCols.add(tenantColumn);
    for (ColumnMapping m : columnMappings) {
      JdbcMappedSqlValidator.requireIdentifier(m.to(), "column");
      allCols.add(m.to());
    }
    for (String c : conflictColumns) {
      JdbcMappedSqlValidator.requireIdentifier(c, "conflictColumn");
      if (!allCols.contains(c)) {
        throw new WorkerConfigException("conflictColumns must appear in tenant/mappings: " + c);
      }
    }
    for (String dbCol : systemBindings.keySet()) {
      JdbcMappedSqlValidator.requireIdentifier(dbCol, "systemBinding column");
      allCols.add(dbCol);
    }
    if (loadStrategy == ImportLoadStrategy.PARTITION_REPLACE_COPY
        || loadStrategy == ImportLoadStrategy.PARTITION_STAGE_SWAP_COPY) {
      validatePartitionReplaceColumns(allCols);
    }
    if (loadStrategy == ImportLoadStrategy.PARTITION_STAGE_SWAP_COPY) {
      validateStageSwap();
    }
  }

  /**
   * 列映射基数强制 1:1。fan-out(一个 {@code from} 映射多个 {@code to})与碰撞(多个 {@code from} 落同一 {@code to})都
   * fail-fast——后者在历史实现里是静默 first-wins 丢列,这里改为显式拒绝并指明冲突双方。
   */
  private void validateMappingCardinality() {
    Map<String, String> byFrom = new LinkedHashMap<>();
    Map<String, String> byTo = new LinkedHashMap<>();
    for (ColumnMapping m : columnMappings) {
      String prevTo = byFrom.putIfAbsent(m.from(), m.to());
      if (prevTo != null) {
        throw new WorkerConfigException(
            "columnMappings: source field '"
                + m.from()
                + "' maps to multiple columns ('"
                + prevTo
                + "', '"
                + m.to()
                + "'); fan-out is not supported");
      }
      String prevFrom = byTo.putIfAbsent(m.to(), m.from());
      if (prevFrom != null) {
        throw new WorkerConfigException(
            "columnMappings: target column '"
                + m.to()
                + "' is mapped from multiple source fields ('"
                + prevFrom
                + "', '"
                + m.from()
                + "'); each column must have a single source");
      }
    }
  }

  private void validatePartitionReplaceColumns(Set<String> allCols) {
    if (replacePartitionColumns == null || replacePartitionColumns.isEmpty()) {
      throw new WorkerConfigException(
          "jdbc_mapped_import.replacePartitionColumns is required for PARTITION_REPLACE_COPY");
    }
    Set<String> contextResolvableCols = new LinkedHashSet<>();
    contextResolvableCols.add(tenantColumn);
    contextResolvableCols.addAll(systemBindings.keySet());
    for (String c : replacePartitionColumns) {
      JdbcMappedSqlValidator.requireIdentifier(c, "replacePartitionColumn");
      if (!allCols.contains(c)) {
        throw new WorkerConfigException(
            "replacePartitionColumns must appear in tenant/mappings/systemBindings: " + c);
      }
      if (!contextResolvableCols.contains(c)) {
        throw new WorkerConfigException(
            "replacePartitionColumns must be resolvable before reading rows: "
                + c
                + " (use tenantColumn or systemBindings)");
      }
    }
  }

  private void validateStageSwap() {
    if (stageSwap == null) {
      throw new WorkerConfigException(
          "jdbc_mapped_import.stageSwap is required for PARTITION_STAGE_SWAP_COPY");
    }
    JdbcMappedSqlValidator.requireIdentifier(
        stageSwap.partitionTable(), "stageSwap.partitionTable");
    String clause = stageSwap.attachClause();
    if (!Texts.hasText(clause)) {
      throw new WorkerConfigException(
          "jdbc_mapped_import.stageSwap.attachClause is required for PARTITION_STAGE_SWAP_COPY");
    }
    String upper = clause.trim().toUpperCase();
    if (!upper.startsWith("FOR VALUES ")) {
      throw new WorkerConfigException(
          "jdbc_mapped_import.stageSwap.attachClause must start with FOR VALUES");
    }
    // attachClause 是运营在 Console 写入的自由文本,最终拼进 ATTACH PARTITION DDL——
    // 用白名单字符集而非黑名单:仅允许分区边界字面量(FROM ('2026-06-09') TO (...) /
    // IN (...) / WITH (MODULUS n, REMAINDER m) / MINVALUE / MAXVALUE)所需字符。
    // 同时杜绝 ; 分隔、-- 行注释、/* */ 块注释、$$ dollar-quoting、双引号标识符等
    // 一切可携带附加 DDL 语法的载体('-' 保留给日期字面量,但 '--' 仍显式拒绝)。
    if (!clause.matches("[A-Za-z0-9_(),'.:+\\-\\s]+") || clause.contains("--")) {
      throw new WorkerConfigException(
          "jdbc_mapped_import.stageSwap.attachClause contains characters outside the partition"
              + " bound whitelist [A-Za-z0-9_(),'.:+- and whitespace]");
    }
  }
}
