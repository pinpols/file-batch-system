package com.example.batch.worker.imports.jdbc;

import com.example.batch.common.jdbc.JdbcMappedSqlValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.util.StringUtils;

/**
 * Parsed from {@code file_template_config.query_param_schema.jdbcMappedImport}
 * or top-level {@code jdbc_mapped_import} (JSON object).
 */
public record JdbcMappedImportSpec(
        String schema,
        String table,
        String tenantColumn,
        List<ColumnMapping> columnMappings,
        List<String> conflictColumns,
        Map<String, String> systemBindings
) {
    public record ColumnMapping(String from, String to) {
    }

    @SuppressWarnings("unchecked")
    public static JdbcMappedImportSpec parse(Map<String, Object> templateConfig, ObjectMapper objectMapper) {
        Map<String, Object> root = extractJdbcMappedImport(templateConfig, objectMapper);
        if (root.isEmpty()) {
            throw new IllegalArgumentException("jdbc_mapped_import spec missing (use query_param_schema.jdbcMappedImport or jdbc_mapped_import)");
        }
        String schema = String.valueOf(root.getOrDefault("schema", "biz")).trim();
        String table = required(root, "table");
        String tenantColumn = required(root, "tenantColumn");
        List<ColumnMapping> mappings = parseMappings(root.get("columnMappings"));
        if (mappings.isEmpty()) {
            throw new IllegalArgumentException("jdbc_mapped_import.columnMappings is required");
        }
        List<String> conflicts = parseStringList(root.get("conflictColumns"));
        Map<String, String> system = parseSystemBindings(root.get("systemBindings"));
        return new JdbcMappedImportSpec(schema, table, tenantColumn, mappings, conflicts, system);
    }

    private static String required(Map<String, Object> root, String key) {
        Object v = root.get(key);
        if (v == null || !StringUtils.hasText(String.valueOf(v))) {
            throw new IllegalArgumentException("jdbc_mapped_import." + key + " is required");
        }
        return String.valueOf(v).trim();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractJdbcMappedImport(Map<String, Object> templateConfig, ObjectMapper objectMapper) {
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
        if (raw instanceof String text && StringUtils.hasText(text)) {
            try {
                return objectMapper.readValue(text, Map.class);
            } catch (Exception ignored) {
                return Map.of();
            }
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
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

    @SuppressWarnings("unchecked")
    private static List<String> parseStringList(Object raw) {
        if (raw instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object o : list) {
                if (o != null && StringUtils.hasText(String.valueOf(o))) {
                    out.add(String.valueOf(o).trim());
                }
            }
            return out;
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> parseSystemBindings(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        m.forEach((k, v) -> {
            if (k != null && v != null) {
                out.put(String.valueOf(k).trim(), String.valueOf(v).trim());
            }
        });
        return out;
    }

    public void validateIdentifiers(Collection<String> allowedSchemas) {
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
                throw new IllegalArgumentException("conflictColumns must appear in tenant/mappings: " + c);
            }
        }
        for (String dbCol : systemBindings.keySet()) {
            JdbcMappedSqlValidator.requireIdentifier(dbCol, "systemBinding column");
            allCols.add(dbCol);
        }
    }
}
