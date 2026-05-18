package com.example.batch.worker.exports.sql;

import com.example.batch.common.exception.WorkerConfigException;
import com.example.batch.common.jdbc.JdbcMappedSqlValidator;
import com.example.batch.common.logging.SwallowedExceptionLogger;
import com.example.batch.common.utils.Texts;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 从模板配置解析而来的 SQL 模板导出规格。
 *
 * <p>主查询 SQL 来自 {@code default_query_sql}，附加配置来自 {@code query_param_schema.sqlTemplateExport}（或
 * {@code sql_template_export}）。
 */
public record SqlTemplateExportSpec(String detailSql, String cursorColumn) {

  /**
   * 从模板配置中解析 SqlTemplateExportSpec。
   *
   * @param templateConfig 模板配置 Map
   * @param objectMapper JSON 解析工具
   * @return 解析结果
   * @throws IllegalArgumentException 配置缺失或 SQL 为空时抛出
   */
  public static SqlTemplateExportSpec parse(
      Map<String, Object> templateConfig, ObjectMapper objectMapper) {
    if (templateConfig == null || templateConfig.isEmpty()) {
      throw new WorkerConfigException("template config missing");
    }
    String detailSql =
        textValue(
            firstNonNull(
                templateConfig.get("default_query_sql"),
                templateConfig.get("defaultQuerySql"),
                templateConfig.get("detail_query_sql"),
                templateConfig.get("detailQuerySql")));
    if (!Texts.hasText(detailSql)) {
      throw new WorkerConfigException("default_query_sql is required for sql_template_export");
    }

    Map<String, Object> schemaMap = toMap(templateConfig.get("query_param_schema"), objectMapper);
    Map<String, Object> spec =
        toMap(
            firstNonNull(
                schemaMap.get("sqlTemplateExport"),
                schemaMap.get("sql_template_export"),
                templateConfig.get("sql_template_export"),
                templateConfig.get("sqlTemplateExport")),
            objectMapper);

    String cursorColumn =
        textValue(firstNonNull(spec.get("cursorColumn"), spec.get("cursor_column")));
    if (!Texts.hasText(cursorColumn)) {
      cursorColumn = "id";
    }
    cursorColumn = JdbcMappedSqlValidator.requireIdentifier(cursorColumn, "cursorColumn");

    // 早校验：包装层 SQL 会附加 `ORDER BY base."<cursorColumn>" ASC`，base 是用户 SQL 的 CTE。
    // 如果用户 SELECT 里没出这列，运行期才会炸 PostgreSQL `bad SQL grammar`，debug 成本极高。
    // 这里用词界正则做廉价启发式检测：拦住「忘 SELECT id」的主流坏配置，同时允许显式换 cursorColumn。
    String trimmedSql = detailSql.trim();
    if (!mentionsIdentifier(trimmedSql, cursorColumn)) {
      throw new IllegalArgumentException(
          "sql_template_export default_query_sql must reference cursor column `"
              + cursorColumn
              + "` (used by ORDER BY / keyset pagination). Either add `"
              + cursorColumn
              + "` to the SELECT list, or set sqlTemplateExport.cursorColumn to a column actually"
              + " selected by the query.");
    }

    return new SqlTemplateExportSpec(trimmedSql, cursorColumn);
  }

  /** 词界扫描：识别 `\b<name>\b` 是否出现在 SQL 中（不区分大小写）。 */
  private static boolean mentionsIdentifier(String sql, String name) {
    if (sql == null || name == null) {
      return false;
    }
    return Pattern.compile("\\b" + Pattern.quote(name) + "\\b", Pattern.CASE_INSENSITIVE)
        .matcher(sql)
        .find();
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
    return Texts.hasText(text) ? text.trim() : null;
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
        SwallowedExceptionLogger.warn(SqlTemplateExportSpec.class, "catch:Exception", ignored);

        return Map.of();
      }
    }
    return Map.of();
  }
}
