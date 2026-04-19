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
import com.example.batch.common.utils.Texts;

public abstract class AbstractExportFormat implements ExportFormatStrategy {

  private static final String KEY_DETAIL_PREFIX = "detail.";
  /**
   * A-3.12：列数硬上限。超过此值直接抛 {@link IllegalArgumentException}，防止宽表推断导致
   * Excel/CSV 生成时内存爆炸。1024 列足够覆盖业务极端场景；真要超此值需显式把模板的
   * {@code max_columns} 字段调大（见 {@link #resolveMaxColumns}）。
   */
  private static final int DEFAULT_MAX_COLUMNS = 1024;

  protected static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  protected final ObjectMapper objectMapper;

  protected AbstractExportFormat(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }


  /** 优先级：模板配置 &gt; 插件描述 &gt; 首页字段推断。 */
  protected List<ColumnLayout> resolveDelimitedColumns(
      ExportDataContext dataCtx,
      ExportDataPlugin dataPlugin,
      Map<String, Object> batch,
      List<Map<String, Object>> firstPage) {
    int maxColumns = resolveMaxColumns(dataCtx.templateConfig());
    List<ColumnLayout> configured = templateDelimitedColumns(dataCtx.templateConfig());
    if (!configured.isEmpty()) {
      return enforceMaxColumns(configured, maxColumns, "delimited-template");
    }
    List<ExportDataPlugin.DelimitedColumn> pluginColumns =
        dataPlugin.describeDelimitedColumns(dataCtx, batch);
    if (!pluginColumns.isEmpty()) {
      List<ColumnLayout> fromPlugin =
          pluginColumns.stream()
              .map(col -> new ColumnLayout(col.header(), col.source(), null, false, ' '))
              .toList();
      return enforceMaxColumns(fromPlugin, maxColumns, "delimited-plugin");
    }
    if (firstPage == null || firstPage.isEmpty()) {
      return List.of();
    }
    List<ColumnLayout> inferred = new ArrayList<>();
    for (String key : firstPage.get(0).keySet()) {
      inferred.add(new ColumnLayout(key, KEY_DETAIL_PREFIX + key, null, false, ' '));
    }
    return enforceMaxColumns(inferred, maxColumns, "delimited-inferred");
  }

  protected List<ColumnLayout> resolveExcelColumns(
      ExportDataContext dataCtx,
      ExportDataPlugin dataPlugin,
      Map<String, Object> batch,
      List<Map<String, Object>> firstPage) {
    return resolveDelimitedColumns(dataCtx, dataPlugin, batch, firstPage);
  }

  protected List<ColumnLayout> resolveFixedWidthColumns(
      ExportDataContext dataCtx,
      ExportDataPlugin dataPlugin,
      Map<String, Object> batch,
      List<Map<String, Object>> firstPage) {
    int maxColumns = resolveMaxColumns(dataCtx.templateConfig());
    List<ColumnLayout> configured = templateFixedWidthColumns(dataCtx.templateConfig());
    if (!configured.isEmpty()) {
      return enforceMaxColumns(configured, maxColumns, "fixed-width-template");
    }
    List<ExportDataPlugin.DelimitedColumn> pluginColumns =
        dataPlugin.describeFixedWidthColumns(dataCtx, batch);
    if (!pluginColumns.isEmpty()) {
      List<ColumnLayout> fromPlugin =
          pluginColumns.stream()
              .map(col -> new ColumnLayout(col.header(), col.source(), null, false, ' '))
              .toList();
      return enforceMaxColumns(fromPlugin, maxColumns, "fixed-width-plugin");
    }
    if (firstPage == null || firstPage.isEmpty()) {
      return List.of();
    }
    List<ColumnLayout> inferred = new ArrayList<>();
    for (String key : firstPage.get(0).keySet()) {
      inferred.add(
          new ColumnLayout(key, KEY_DETAIL_PREFIX + key, Math.max(key.length(), 16), false, ' '));
    }
    return enforceMaxColumns(inferred, maxColumns, "fixed-width-inferred");
  }

  /**
   * A-3.12：解析模板中的 {@code max_columns} / {@code maxColumns}；未配置时走 {@link
   * #DEFAULT_MAX_COLUMNS}。支持从 {@code query_param_schema} 继承配置。
   */
  protected int resolveMaxColumns(Map<String, Object> templateConfig) {
    if (templateConfig == null || templateConfig.isEmpty()) {
      return DEFAULT_MAX_COLUMNS;
    }
    Integer direct =
        integerValue(firstNonNull(templateConfig.get("max_columns"), templateConfig.get("maxColumns")));
    if (direct != null && direct > 0) {
      return direct;
    }
    Map<String, Object> schema = toMap(templateConfig.get("query_param_schema"));
    Integer fromSchema =
        integerValue(firstNonNull(schema.get("maxColumns"), schema.get("max_columns")));
    if (fromSchema != null && fromSchema > 0) {
      return fromSchema;
    }
    return DEFAULT_MAX_COLUMNS;
  }

  /** A-3.12：列数超上限立即 fail-fast，避免后续 StringBuilder / workbook 爆内存。 */
  protected <T> List<T> enforceMaxColumns(List<T> columns, int maxColumns, String source) {
    if (columns.size() > maxColumns) {
      throw new IllegalArgumentException(
          "export column count "
              + columns.size()
              + " exceeds max_columns="
              + maxColumns
              + " (source="
              + source
              + "); declare template.max_columns to raise the limit deliberately");
    }
    return columns;
  }


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
      if (!Texts.hasText(source)) {
        continue;
      }
      String normalizedSource = normalizeDelimitedSource(source);
      String header = textValue(firstNonNull(map.get("header"), map.get("name")));
      if (!Texts.hasText(header)) {
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
    return Texts.hasText(text) ? sanitizeSheetName(text) : "Sheet1";
  }

  protected String sanitizeSheetName(String value) {
    String cleaned = value.replaceAll("[\\\\/?*\\[\\]:]", "_");
    return cleaned.length() > 31 ? cleaned.substring(0, 31) : cleaned;
  }


  protected Object resolveDelimitedValue(
      Map<String, Object> batch, Map<String, Object> detail, String source) {
    if (!Texts.hasText(source)) {
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
      if (value != null && Texts.hasText(String.valueOf(value))) {
        return Math.max(1, Integer.parseInt(String.valueOf(value)));
      }
    }
    return fallback;
  }


  protected String csv(Object value, DelimitedFormatConfig formatConfig) {
    String text = textValue(value);
    if (!Texts.hasText(text)) {
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
    return Texts.hasText(text) ? text.trim() : null;
  }

  protected Map<String, Object> toMap(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> converted = new LinkedHashMap<>();
      map.forEach((k, v) -> converted.put(String.valueOf(k), v));
      return converted;
    }
    if (value instanceof String text && Texts.hasText(text)) {
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
    if (!Texts.hasText(text)) {
      return null;
    }
    try {
      return Integer.parseInt(text);
    } catch (Exception ignored) {
      return null;
    }
  }

  protected char resolvePadChar(String padChar) {
    if (!Texts.hasText(padChar)) {
      return ' ';
    }
    return padChar.charAt(0);
  }


  public record ColumnLayout(
      String header, String source, Integer width, boolean rightAlign, char padChar) {}

  public record DelimitedFormatConfig(
      String delimiter,
      String quoteChar,
      QuotePolicy quotePolicy,
      EscapePolicy escapePolicy,
      int headerRows) {}

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
