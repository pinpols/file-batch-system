package com.example.batch.worker.exports.jdbc;

import com.example.batch.common.jdbc.JdbcMappedSqlValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.util.StringUtils;

/**
 * Parsed from {@code query_param_schema.jdbcMappedExport} or {@code jdbc_mapped_export}.
 */
public record JdbcMappedExportSpec(
        String schema,
        String batchTable,
        String batchTenantColumn,
        String batchNoColumn,
        List<String> batchSelectColumns,
        String detailTable,
        String detailFkColumn,
        String detailOrderByColumn,
        List<String> detailSelectColumns
) {

    @SuppressWarnings("unchecked")
    public static JdbcMappedExportSpec parse(Map<String, Object> templateConfig, ObjectMapper objectMapper) {
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
                detailCols
        );
    }

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
        if (v == null || !StringUtils.hasText(String.valueOf(v))) {
            throw new IllegalArgumentException("jdbc_mapped_export." + key + " is required");
        }
        return String.valueOf(v).trim();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extract(Map<String, Object> templateConfig, ObjectMapper objectMapper) {
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
        if (raw instanceof String text && StringUtils.hasText(text)) {
            try {
                return objectMapper.readValue(text, Map.class);
            } catch (Exception ignored) {
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
            if (o != null && StringUtils.hasText(String.valueOf(o))) {
                out.add(String.valueOf(o).trim());
            }
        }
        return out;
    }
}
