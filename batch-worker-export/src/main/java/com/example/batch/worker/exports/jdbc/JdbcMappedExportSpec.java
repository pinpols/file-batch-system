package com.example.batch.worker.exports.jdbc;

import com.example.batch.common.jdbc.JdbcMappedSqlValidator;
import com.example.batch.common.logging.SwallowedExceptionLogger;
import com.example.batch.common.utils.Texts;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 从模板配置的 {@code query_param_schema.jdbcMappedExport} 或 {@code jdbc_mapped_export} 解析而来的导出规格。 */
public record JdbcMappedExportSpec(
    String schema,
    String batchTable,
    String batchTenantColumn,
    String batchNoColumn,
    List<String> batchSelectColumns,
    String detailTable,
    String detailFkColumn,
    String detailOrderByColumn,
    List<String> detailSelectColumns) {

  /**
   * 从模板配置中解析 JdbcMappedExportSpec。
   *
   * @param templateConfig 模板配置 Map
   * @param objectMapper JSON 解析工具
   * @return 解析结果
   * @throws IllegalArgumentException 配置缺失或校验失败时抛出
   */
  @SuppressWarnings("unchecked")
  public static JdbcMappedExportSpec parse(
      Map<String, Object> templateConfig, ObjectMapper objectMapper) {
    Map<String, Object> root = extract(templateConfig, objectMapper);
    if (root.isEmpty()) {
      throw new IllegalArgumentException("jdbc_mapped_export spec missing");
    }
    String schema = String.valueOf(root.getOrDefault("schema", "biz")).trim();
    String batchTable = required(root, "batchTable");
    String batchTenantColumn = required(root, "batchTenantColumn");
    String batchNoColumn = required(root, "batchNoColumn");
    List<String> batchCols = parseStringList(root.get("batchSelectColumns"));
    String detailTable = required(root, "detailTable");
    String detailFk = required(root, "detailFkColumn");
    String orderCol = required(root, "detailOrderByColumn");
    List<String> detailCols = parseStringList(root.get("detailSelectColumns"));
    if (batchCols.isEmpty() || detailCols.isEmpty()) {
      throw new IllegalArgumentException("batchSelectColumns and detailSelectColumns are required");
    }
    return new JdbcMappedExportSpec(
        schema,
        batchTable,
        batchTenantColumn,
        batchNoColumn,
        batchCols,
        detailTable,
        detailFk,
        orderCol,
        detailCols);
  }

  /**
   * 校验所有表名、列名标识符以及 schema 白名单。
   *
   * @param allowedSchemas 允许的 schema 列表
   * @throws IllegalArgumentException 任一标识符不合法时抛出
   */
  public void validateIdentifiers(List<String> allowedSchemas) {
    JdbcMappedSqlValidator.requireInAllowlist(schema, allowedSchemas);
    JdbcMappedSqlValidator.requireIdentifier(batchTable, "batchTable");
    JdbcMappedSqlValidator.requireIdentifier(batchTenantColumn, "batchTenantColumn");
    JdbcMappedSqlValidator.requireIdentifier(batchNoColumn, "batchNoColumn");
    for (String c : batchSelectColumns) {
      JdbcMappedSqlValidator.requireIdentifier(c, "batchSelectColumns");
    }
    JdbcMappedSqlValidator.requireIdentifier(detailTable, "detailTable");
    JdbcMappedSqlValidator.requireIdentifier(detailFkColumn, "detailFkColumn");
    JdbcMappedSqlValidator.requireIdentifier(detailOrderByColumn, "detailOrderByColumn");
    for (String c : detailSelectColumns) {
      JdbcMappedSqlValidator.requireIdentifier(c, "detailSelectColumns");
    }
    if (!batchSelectColumns.contains("id")) {
      throw new IllegalArgumentException("batchSelectColumns must include id for detail FK join");
    }
  }

  private static String required(Map<String, Object> root, String key) {
    Object v = root.get(key);
    if (v == null || !Texts.hasText(String.valueOf(v))) {
      throw new IllegalArgumentException("jdbc_mapped_export." + key + " is required");
    }
    return String.valueOf(v).trim();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> extract(
      Map<String, Object> templateConfig, ObjectMapper objectMapper) {
    if (templateConfig == null) {
      return Map.of();
    }
    Object direct = templateConfig.get("jdbc_mapped_export");
    if (direct instanceof Map<?, ?> m) {
      return (Map<String, Object>) m;
    }
    Object qps = templateConfig.get("query_param_schema");
    Map<String, Object> qpsMap = toMap(qps, objectMapper);
    Object nested = qpsMap.get("jdbcMappedExport");
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
    if (raw instanceof String text && Texts.hasText(text)) {
      try {
        return objectMapper.readValue(text, Map.class);
      } catch (Exception ignored) {
        SwallowedExceptionLogger.warn(JdbcMappedExportSpec.class, "catch:Exception", ignored);

        return Map.of();
      }
    }
    return Map.of();
  }

  private static List<String> parseStringList(Object raw) {
    if (!(raw instanceof List<?> list)) {
      return List.of();
    }
    List<String> out = new ArrayList<>();
    for (Object o : list) {
      if (o != null && Texts.hasText(String.valueOf(o))) {
        out.add(String.valueOf(o).trim());
      }
    }
    return out;
  }
}
