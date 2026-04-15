package com.example.batch.worker.exports.stage.format;

import com.example.batch.common.plugin.ExportDataContext;
import com.example.batch.common.plugin.ExportDataPlugin;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.exports.domain.ExportJobContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.util.StringUtils;

/**
 * 所有 {@link ExportFormatStrategy} 实现的公共基础设施。
 *
 * <p>包含列布局解析、分隔格式配置、表头写入、值格式化辅助方法， 以及被两个或以上具体策略共用的内部类型 （{@link ColumnLayout}、{@link
 * DelimitedFormatConfig}、{@link QuotePolicy}、{@link EscapePolicy}）。
 */
public abstract class AbstractExportFormat implements ExportFormatStrategy {

  // ── duplicate literal constants ─────────────────────────────────────────
  private static final String KEY_DETAIL_PREFIX = "detail.";

  protected static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  protected final ObjectMapper objectMapper;

  protected AbstractExportFormat(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  // ─── 列布局解析 ────────────────────────────────────────────

  /** 解析分隔格式的列布局，优先级：模板配置 &gt; 插件描述 &gt; 首页字段推断。 */
  protected List<ColumnLayout> resolveDelimitedColumns(
      ExportDataContext dataCtx,
      ExportDataPlugin dataPlugin,
      Map<String, Object> batch,
      List<Map<String, Object>> firstPage) {
    List<ColumnLayout> configured = templateDelimitedColumns(dataCtx.templateConfig());
    if (!configured.isEmpty()) {
      return configured;
    }
    List<ExportDataPlugin.DelimitedColumn> pluginColumns =
        dataPlugin.describeDelimitedColumns(dataCtx, batch);
    if (!pluginColumns.isEmpty()) {
      return pluginColumns.stream()
          .map(col -> new ColumnLayout(col.header(), col.source(), null, false, ' '))
          .toList();
    }
    if (firstPage == null || firstPage.isEmpty()) {
      return List.of();
    }
    List<ColumnLayout> inferred = new ArrayList<>();
    for (String key : firstPage.get(0).keySet()) {
      inferred.add(new ColumnLayout(key, KEY_DETAIL_PREFIX + key, null, false, ' '));
    }
    return inferred;
  }

  /** 解析 Excel 格式的列布局，逻辑与分隔格式相同。 */
  protected List<ColumnLayout> resolveExcelColumns(
      ExportDataContext dataCtx,
      ExportDataPlugin dataPlugin,
      Map<String, Object> batch,
      List<Map<String, Object>> firstPage) {
    return resolveDelimitedColumns(dataCtx, dataPlugin, batch, firstPage);
  }

  /** 解析固定宽度格式的列布局，优先级：模板配置 &gt; 插件描述 &gt; 首页字段推断（含默认宽度）。 */
  protected List<ColumnLayout> resolveFixedWidthColumns(
      ExportDataContext dataCtx,
      ExportDataPlugin dataPlugin,
      Map<String, Object> batch,
      List<Map<String, Object>> firstPage) {
    List<ColumnLayout> configured = templateFixedWidthColumns(dataCtx.templateConfig());
    if (!configured.isEmpty()) {
      return configured;
    }
    List<ExportDataPlugin.DelimitedColumn> pluginColumns =
        dataPlugin.describeFixedWidthColumns(dataCtx, batch);
    if (!pluginColumns.isEmpty()) {
      return pluginColumns.stream()
          .map(col -> new ColumnLayout(col.header(), col.source(), null, false, ' '))
          .toList();
    }
    if (firstPage == null || firstPage.isEmpty()) {
      return List.of();
    }
    List<ColumnLayout> inferred = new ArrayList<>();
    for (String key : firstPage.get(0).keySet()) {
      inferred.add(
          new ColumnLayout(key, KEY_DETAIL_PREFIX + key, Math.max(key.length(), 16), false, ' '));
    }
    return inferred;
  }

  // ─── 模板配置辅助方法 ─────────────────────────────────────────────

  protected List<ColumnLayout> templateDelimitedColumns(Map<String, Object> templateConfig) {
    if (templateConfig == null || templateConfig.isEmpty()) {
      return List.of();
    }
    Object direct =
        firstNonNull(templateConfig.get("csv_columns"), templateConfig.get("csvColumns"));
    List<ColumnLayout> parsed = parseDelimitedColumns(direct, false);
    if (!parsed.isEmpty()) {
      return parsed;
    }
    Object querySchema = templateConfig.get("query_param_schema");
    Map<String, Object> schemaMap = toMap(querySchema);
    return parseDelimitedColumns(
        firstNonNull(schemaMap.get("csvColumns"), schemaMap.get("delimitedColumns")), false);
  }

  protected List<ColumnLayout> templateFixedWidthColumns(Map<String, Object> templateConfig) {
    if (templateConfig == null || templateConfig.isEmpty()) {
      return List.of();
    }
    Object direct =
        firstNonNull(
            templateConfig.get("fixed_width_columns"), templateConfig.get("fixedWidthColumns"));
    List<ColumnLayout> parsed = parseDelimitedColumns(direct, true);
    if (!parsed.isEmpty()) {
      return parsed;
    }
    Object querySchema = templateConfig.get("query_param_schema");
    Map<String, Object> schemaMap = toMap(querySchema);
    return parseDelimitedColumns(
        firstNonNull(schemaMap.get("fixedWidthColumns"), schemaMap.get("fixed_width_columns")),
        true);
  }

  protected List<ColumnLayout> parseDelimitedColumns(Object raw, boolean fixedWidth) {
    if (!(raw instanceof Collection<?> list)) {
      return List.of();
    }
    List<ColumnLayout> columns = new ArrayList<>();
    for (Object item : list) {
      Map<String, Object> map = toMap(item);
      if (map.isEmpty()) {
        continue;
      }
      String source = textValue(firstNonNull(map.get("source"), map.get("path"), map.get("field")));
      if (!StringUtils.hasText(source)) {
        continue;
      }
      String normalizedSource = normalizeDelimitedSource(source);
      String header = textValue(firstNonNull(map.get("header"), map.get("name")));
      if (!StringUtils.hasText(header)) {
        header = defaultDelimitedHeader(normalizedSource);
      }
      Integer width =
          fixedWidth
              ? integerValue(firstNonNull(map.get("width"), map.get("size"), map.get("len")))
              : null;
      String align =
          fixedWidth ? textValue(firstNonNull(map.get("align"), map.get("alignment"))) : null;
      String padChar =
          fixedWidth ? textValue(firstNonNull(map.get("padChar"), map.get("pad_char"))) : null;
      columns.add(
          new ColumnLayout(
              header,
              normalizedSource,
              width,
              "RIGHT".equalsIgnoreCase(align),
              resolvePadChar(padChar)));
    }
    return columns;
  }

  protected String normalizeDelimitedSource(String source) {
    String value = source == null ? "" : source.trim();
    if (value.startsWith("batch.") || value.startsWith(KEY_DETAIL_PREFIX)) {
      return value;
    }
    return KEY_DETAIL_PREFIX + value;
  }

  protected String defaultDelimitedHeader(String source) {
    int idx = source.lastIndexOf('.');
    return idx >= 0 && idx + 1 < source.length() ? source.substring(idx + 1) : source;
  }

  // ─── 格式配置 ───────────────────────────────────────────────────────

  /** 从模板配置中解析分隔格式参数（分隔符、引号字符、引号策略、转义策略、表头行数）。 */
  protected DelimitedFormatConfig resolveDelimitedFormatConfig(Map<String, Object> templateConfig) {
    Map<String, Object> source = templateConfig == null ? Map.of() : templateConfig;
    Object schema = source.get("query_param_schema");
    Map<String, Object> schemaMap = toMap(schema);
    Object delimiterRaw =
        firstNonNull(
            source.get("delimiter"), source.get("quote_delimiter"), schemaMap.get("delimiter"));
    String delimiter = delimiterRaw == null ? null : String.valueOf(delimiterRaw);
    if (delimiter == null || delimiter.isEmpty()) {
      delimiter = ",";
    }
    Object quoteCharRaw =
        firstNonNull(source.get("quote_char"), source.get("quoteChar"), schemaMap.get("quoteChar"));
    String quoteChar = quoteCharRaw == null ? null : String.valueOf(quoteCharRaw);
    if (quoteChar == null || quoteChar.isEmpty()) {
      quoteChar = "\"";
    }
    QuotePolicy quotePolicy =
        QuotePolicy.from(
            firstNonNull(
                source.get("quote_policy"),
                source.get("quotePolicy"),
                schemaMap.get("quotePolicy")));
    EscapePolicy escapePolicy =
        EscapePolicy.from(
            firstNonNull(
                source.get("escape_policy"),
                source.get("escapePolicy"),
                schemaMap.get("escapePolicy")));
    int headerRows =
        resolveIntValue(
            firstNonNull(
                source.get("header_rows"), source.get("headerRows"), schemaMap.get("headerRows")),
            1);
    return new DelimitedFormatConfig(delimiter, quoteChar, quotePolicy, escapePolicy, headerRows);
  }

  protected String resolveSheetName(Map<String, Object> templateConfig) {
    if (templateConfig == null || templateConfig.isEmpty()) {
      return "Sheet1";
    }
    Object v = firstNonNull(templateConfig.get("sheet_name"), templateConfig.get("sheetName"));
    String text = textValue(v);
    return StringUtils.hasText(text) ? sanitizeSheetName(text) : "Sheet1";
  }

  protected String sanitizeSheetName(String value) {
    String cleaned = value.replaceAll("[\\\\/?*\\[\\]:]", "_");
    return cleaned.length() > 31 ? cleaned.substring(0, 31) : cleaned;
  }

  // ─── 值解析 ────────────────────────────────────────────────────

  protected Object resolveDelimitedValue(
      Map<String, Object> batch, Map<String, Object> detail, String source) {
    if (!StringUtils.hasText(source)) {
      return null;
    }
    if (source.startsWith("batch.")) {
      return batch.get(source.substring("batch.".length()));
    }
    if (source.startsWith(KEY_DETAIL_PREFIX)) {
      return detail.get(source.substring(KEY_DETAIL_PREFIX.length()));
    }
    Object detailValue = detail.get(source);
    return detailValue != null ? detailValue : batch.get(source);
  }

  protected int resolveTemplateInt(ExportJobContext context, String key, int fallback) {
    Object templateConfigObject =
        context == null ? null : context.getAttributes().get(PipelineRuntimeKeys.TEMPLATE_CONFIG);
    if (templateConfigObject instanceof Map<?, ?> templateConfig) {
      Object value = templateConfig.get(key);
      if (value instanceof Number number) {
        return Math.max(1, number.intValue());
      }
      if (value != null && StringUtils.hasText(String.valueOf(value))) {
        return Math.max(1, Integer.parseInt(String.valueOf(value)));
      }
    }
    return fallback;
  }

  // ─── 分隔格式化 ────────────────────────────────────────────────────

  protected String csv(Object value, DelimitedFormatConfig formatConfig) {
    String text = textValue(value);
    if (!StringUtils.hasText(text)) {
      return "";
    }
    boolean needsQuote =
        switch (formatConfig.quotePolicy()) {
          case ALL -> true;
          case NONE -> false;
          case REQUIRED ->
              text.contains(formatConfig.delimiter())
                  || text.contains("\n")
                  || text.contains("\r")
                  || text.contains(formatConfig.quoteChar())
                  || text.startsWith(" ")
                  || text.endsWith(" ");
        };
    String escaped = escapeDelimited(text, formatConfig);
    if (!needsQuote) {
      return escaped;
    }
    return formatConfig.quoteChar() + escaped + formatConfig.quoteChar();
  }

  protected String escapeDelimited(String value, DelimitedFormatConfig formatConfig) {
    return switch (formatConfig.escapePolicy()) {
      case BACKSLASH ->
          value
              .replace("\\", "\\\\")
              .replace(formatConfig.quoteChar(), "\\" + formatConfig.quoteChar())
              .replace("\r", "\\r")
              .replace("\n", "\\n");
      case NONE -> value;
      case DOUBLE_QUOTE ->
          value.replace(
              formatConfig.quoteChar(), formatConfig.quoteChar() + formatConfig.quoteChar());
    };
  }

  // ─── 固定宽度格式化 ──────────────────────────────────────────────

  protected String fixedWidthLine(
      List<ColumnLayout> columns, int recordLength, Function<ColumnLayout, String> valueMapper) {
    StringBuilder builder = new StringBuilder();
    for (ColumnLayout column : columns) {
      builder.append(fixedWidth(valueMapper.apply(column), column));
    }
    if (recordLength > 0) {
      return padRight(builder.toString(), recordLength);
    }
    return builder.toString();
  }

  protected String fixedWidth(Object value, ColumnLayout column) {
    String text = textValue(value);
    int width =
        column.width() == null || column.width() <= 0
            ? Math.max(column.header().length(), 16)
            : column.width();
    if (text == null) {
      text = "";
    }
    if (text.length() > width) {
      return text.substring(0, width);
    }
    char pad = column.padChar();
    String padding = String.valueOf(pad).repeat(width - text.length());
    if (column.rightAlign()) {
      return padding + text;
    }
    return text + padding;
  }

  protected String padRight(String text, int length) {
    String value = text == null ? "" : text;
    if (value.length() >= length) {
      return value.substring(0, length);
    }
    return value + " ".repeat(length - value.length());
  }

  // ─── 通用工具方法 ────────────────────────────────────────────────────

  protected Object firstNonNull(Object... values) {
    for (Object value : values) {
      if (value != null) {
        return value;
      }
    }
    return null;
  }

  protected String textValue(Object value) {
    if (value == null) {
      return null;
    }
    String text = String.valueOf(value);
    return StringUtils.hasText(text) ? text.trim() : null;
  }

  protected Map<String, Object> toMap(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> converted = new LinkedHashMap<>();
      map.forEach((k, v) -> converted.put(String.valueOf(k), v));
      return converted;
    }
    if (value instanceof String text && StringUtils.hasText(text)) {
      try {
        return objectMapper.readValue(text, MAP_TYPE);
      } catch (Exception ignored) {
        return Map.of();
      }
    }
    return Map.of();
  }

  protected int resolveIntValue(Object value, int fallback) {
    Integer resolved = integerValue(value);
    return resolved == null ? fallback : Math.max(1, resolved);
  }

  protected Integer integerValue(Object value) {
    if (value instanceof Number number) {
      return number.intValue();
    }
    if (value == null) {
      return null;
    }
    String text = String.valueOf(value).trim();
    if (!StringUtils.hasText(text)) {
      return null;
    }
    try {
      return Integer.parseInt(text);
    } catch (Exception ignored) {
      return null;
    }
  }

  protected char resolvePadChar(String padChar) {
    if (!StringUtils.hasText(padChar)) {
      return ' ';
    }
    return padChar.charAt(0);
  }

  // ─── 内部类型 ─────────────────────────────────────────────────────────

  /** 列布局描述，包含表头、数据源路径、宽度及对齐信息。 */
  public record ColumnLayout(
      String header, String source, Integer width, boolean rightAlign, char padChar) {}

  /** 分隔格式参数，包含分隔符、引号字符、引号策略、转义策略和表头行数。 */
  public record DelimitedFormatConfig(
      String delimiter,
      String quoteChar,
      QuotePolicy quotePolicy,
      EscapePolicy escapePolicy,
      int headerRows) {}

  /** 引号策略：NONE 不加引号，REQUIRED 仅必要时加，ALL 全部加引号。 */
  public enum QuotePolicy {
    NONE,
    REQUIRED,
    ALL;

    public static QuotePolicy from(Object value) {
      if (value == null) {
        return REQUIRED;
      }
      try {
        return QuotePolicy.valueOf(String.valueOf(value).trim().toUpperCase());
      } catch (Exception ignored) {
        return REQUIRED;
      }
    }
  }

  /** 转义策略：DOUBLE_QUOTE 双写引号，BACKSLASH 反斜杠转义，NONE 不转义。 */
  public enum EscapePolicy {
    DOUBLE_QUOTE,
    BACKSLASH,
    NONE;

    public static EscapePolicy from(Object value) {
      if (value == null) {
        return DOUBLE_QUOTE;
      }
      try {
        return EscapePolicy.valueOf(String.valueOf(value).trim().toUpperCase());
      } catch (Exception ignored) {
        return DOUBLE_QUOTE;
      }
    }
  }
}
