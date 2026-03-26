package com.example.batch.worker.exports.sql;

import com.example.batch.common.jdbc.JdbcMappedSqlValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.util.StringUtils;

/**
 * SQL template export spec parsed from template config.
 *
 * <p>Primary SQL comes from {@code default_query_sql}. Additional settings come from
 * {@code query_param_schema.sqlTemplateExport} (or {@code sql_template_export}).
 */
public record SqlTemplateExportSpec(
        String detailSql,
        String cursorColumn
) {

    public static SqlTemplateExportSpec parse(Map<String, Object> templateConfig, ObjectMapper objectMapper) {
        if (templateConfig == null || templateConfig.isEmpty()) {
            throw new IllegalArgumentException("template config missing");
        }
        String detailSql = textValue(firstNonNull(
                templateConfig.get("default_query_sql"),
                templateConfig.get("defaultQuerySql"),
                templateConfig.get("detail_query_sql"),
                templateConfig.get("detailQuerySql")
        ));
        if (!StringUtils.hasText(detailSql)) {
            throw new IllegalArgumentException("default_query_sql is required for sql_template_export");
        }

        Map<String, Object> schemaMap = toMap(templateConfig.get("query_param_schema"), objectMapper);
        Map<String, Object> spec = toMap(firstNonNull(
                schemaMap.get("sqlTemplateExport"),
                schemaMap.get("sql_template_export"),
                templateConfig.get("sql_template_export"),
                templateConfig.get("sqlTemplateExport")
        ), objectMapper);

        String cursorColumn = textValue(firstNonNull(spec.get("cursorColumn"), spec.get("cursor_column")));
        if (!StringUtils.hasText(cursorColumn)) {
            cursorColumn = "id";
        }
        cursorColumn = JdbcMappedSqlValidator.requireIdentifier(cursorColumn, "cursorColumn");

        return new SqlTemplateExportSpec(detailSql.trim(), cursorColumn);
    }

    private static Object firstNonNull(Object... values) {
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String textValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return StringUtils.hasText(text) ? text.trim() : null;
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
}

