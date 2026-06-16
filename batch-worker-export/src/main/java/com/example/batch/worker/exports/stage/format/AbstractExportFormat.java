package com.example.batch.worker.exports.stage.format;

import com.example.batch.common.logging.SwallowedExceptionLogger;
import com.example.batch.common.plugin.ExportDataContext;
import com.example.batch.common.plugin.ExportDataPlugin;
import com.example.batch.common.utils.Texts;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PipelineStageProgressSink;
import com.example.batch.worker.exports.domain.ExportJobContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public abstract class AbstractExportFormat implements ExportFormatStrategy {

  private static final String KEY_DETAIL_PREFIX = "detail.";
  private static final String KEY_QUERY_PARAM_SCHEMA = "query_param_schema";

  /**
   * A-3.12：列数硬上限。超过此值直接抛 {@link IllegalArgumentException}，防止宽表推断导致 Excel/CSV 生成时内存爆炸。1024
   * 列足够覆盖业务极端场景；真要超此值需显式把模板的 {@code max_columns} 字段调大（见 {@link #resolveMaxColumns}）。
   */
  private static final int DEFAULT_MAX_COLUMNS = 1024;

  /**
   * P-1/P-2 同系列防御：插件返回循环 cursor 时 fail-fast。1M 行 / 约数 GB 已足够任何合理业务； 超此值即视为 data plugin 返回陈旧 cursor
   * 的严重 bug。
   */
  protected static final int DEFAULT_MAX_PAGES = 100_000;

  /**
   * 2026-06-04 docs/design/pipeline-stage-progress-display.md:EXPORT GENERATE 行级进度上报节流。 心跳默认
   * 30s/次,每行 publish 是无意义的 AtomicReference set;每 1000 行 publish 一次足够细。
   */
  private static final int PROGRESS_PUBLISH_EVERY_N_ROWS = 1000;

  protected static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  protected final ObjectMapper objectMapper;

  protected AbstractExportFormat(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /**
   * 通用分页 generate 模板：负责 {@code loadDetailPage → iterate rows → nextCursor → MAX_PAGES} 循环骨架， 各
   * format 只需实现 row 写入逻辑。
   *
   * <p>当调用方已因 column 推断等原因预取了首页，可通过 {@code preFetchedFirstPage} 传入以避免重复 读取；为 {@code null}
   * 时由本方法负责首次加载。
   *
   * @return 已处理的 row 总数
   */
  protected long generatePaged(
      ExportFormatContext ctx,
      ExportDataPlugin.DetailPage preFetchedFirstPage,
      PageRowWriter rowWriter)
      throws Exception {
    return generatePaged(ctx, preFetchedFirstPage, null, rowWriter);
  }

  /** 无预取首页版本，从 {@code cursor=null} 开始加载。 */
  protected long generatePaged(ExportFormatContext ctx, PageRowWriter rowWriter) throws Exception {
    return generatePaged(ctx, null, null, rowWriter);
  }

  /**
   * ADR-038 P3 续跑感知版本:在 {@code preFetchedFirstPage} + {@code rowWriter} 之外接入 {@link
   * FileSync},于<b>分页边界</b> fsync 文件并推进续跑位点。
   *
   * <p>续跑(ctx.checkpoint().resuming())时:起始 recordCount = 续跑行数、忽略预取首页、从 {@code resumeCursor} 续拉;
   * 位点仅在 {@code cursor != null}(还有后继页)时推进 —— 终页交由 {@code GenerateStep.markCompleted} 收尾, 以保证存下来的
   * cursor 永远是有效续跑起点。{@code fileSync == null} 或无 checkpoint 时退化为纯全量写(行为同今天)。
   *
   * @return 已处理的 row 总数(含续跑前已写的行)
   */
  protected long generatePaged(
      ExportFormatContext ctx,
      ExportDataPlugin.DetailPage preFetchedFirstPage,
      FileSync fileSync,
      PageRowWriter rowWriter)
      throws Exception {
    Long batchIdLong = ctx.batchId() == null ? null : Long.valueOf(String.valueOf(ctx.batchId()));
    GenerateCheckpoint checkpoint = ctx.checkpoint();
    boolean resuming = checkpoint != null && checkpoint.resuming();
    long recordCount = resuming ? checkpoint.resumeRecordCount() : 0L;
    ExportDataPlugin.DetailPage page;
    if (resuming) {
      page =
          ctx.dataPlugin()
              .loadDetailPage(
                  ctx.dataCtx(), batchIdLong, ctx.pageSize(), checkpoint.resumeCursor());
    } else {
      page =
          preFetchedFirstPage != null
              ? preFetchedFirstPage
              : ctx.dataPlugin().loadDetailPage(ctx.dataCtx(), batchIdLong, ctx.pageSize(), null);
    }
    int pageNo = 0;
    while (true) {
      List<Map<String, Object>> details = page.rows();
      if (details.isEmpty()) {
        break;
      }
      for (Map<String, Object> detail : details) {
        rowWriter.writeRow(ctx.batch(), detail, recordCount);
        recordCount++;
        // 2026-06-04 docs/design/pipeline-stage-progress-display.md:每 1000 行 publish 一次,
        // 节流匹配心跳节奏(30s/次);AtomicReference set 极廉价但无须每行调。
        // totalRowsHint=null:export 走 cursor 分页 plugin 不报总量,FE 退化为只显计数器不显 ETA;
        // 未来 ExportDataPlugin 暴露 estimateTotal() 时可在此带上。
        if (recordCount % PROGRESS_PUBLISH_EVERY_N_ROWS == 0) {
          PipelineStageProgressSink.publish(recordCount, null);
        }
      }
      Object cursor = page.nextCursor();
      // ADR-038 P3:页边界 fsync + 推进位点。仅在还有后继页(cursor != null)时推进 ——
      // 终页不记位点,避免存下 null cursor 导致续跑从头重写。fileSync/checkpoint 为空时无位点(全量跑)。
      if (checkpoint != null && fileSync != null && cursor != null) {
        long byteOffset = fileSync.flushAndSync();
        checkpoint.advance(byteOffset, cursor, recordCount);
      }
      if (cursor == null) {
        break;
      }
      if (++pageNo >= DEFAULT_MAX_PAGES) {
        throw new IllegalStateException(
            "export page iteration exceeded MAX_PAGES="
                + DEFAULT_MAX_PAGES
                + "; data plugin likely returning stale cursor");
      }
      page = ctx.dataPlugin().loadDetailPage(ctx.dataCtx(), batchIdLong, ctx.pageSize(), cursor);
    }
    // 终态收尾上报(若总行数不是 1000 的倍数,最后一段不会被循环里的节流捕获)
    PipelineStageProgressSink.publish(recordCount, null);
    return recordCount;
  }

  /**
   * 按续跑状态打开生成文件:续跑则先 {@code FileChannel.truncate} 到已 fsync 的字节位点再 append;首跑则截断到 0 重写。
   * 续跑写出来的行序号(rowIndex)接着续跑行数往后排,故 JSON 的「行前逗号」、Delimited 的换行等增量语义天然衔接。
   */
  protected ResumableExportFile openExportFile(ExportFormatContext ctx) throws IOException {
    if (isResuming(ctx)) {
      try (FileChannel ch = FileChannel.open(ctx.generatedFile(), StandardOpenOption.WRITE)) {
        ch.truncate(ctx.checkpoint().resumeByteOffset());
      }
      return ResumableExportFile.append(ctx.generatedFile());
    }
    return ResumableExportFile.truncate(ctx.generatedFile());
  }

  /** 本次 generate 是否为续跑(已有可用位点 + 残文件)。 */
  protected boolean isResuming(ExportFormatContext ctx) {
    return ctx.checkpoint() != null && ctx.checkpoint().resuming();
  }

  /** 分页边界文件同步回调:flush + fsync,返回当前字节数(续跑 truncate 目标)。 */
  @FunctionalInterface
  protected interface FileSync {
    long flushAndSync() throws Exception;
  }

  /**
   * 分页模板的行写入回调。允许抛 {@link Exception}（IO / 格式转换）。
   *
   * @param batch batch-level 数据
   * @param detail 当前行
   * @param rowIndex 当前行序号（0-based），便于 format 决定是否插分隔符 / 换行 / flush
   */
  @FunctionalInterface
  protected interface PageRowWriter {
    void writeRow(Map<String, Object> batch, Map<String, Object> detail, long rowIndex)
        throws Exception;
  }

  /** 优先级：模板配置 &gt; 插件描述 &gt; 首页字段推断。 */
  protected List<ColumnLayout> resolveDelimitedColumns(
      ExportDataContext dataCtx,
      ExportDataPlugin dataPlugin,
      Map<String, Object> batch,
      List<Map<String, Object>> firstPage) {
    return resolveColumns(
        dataCtx,
        firstPage,
        new ColumnResolutionSpec(
            "delimited",
            templateDelimitedColumns(dataCtx.templateConfig()),
            dataPlugin.describeDelimitedColumns(dataCtx, batch),
            key -> new ColumnLayout(key, KEY_DETAIL_PREFIX + key, null, false, ' ')));
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
    return resolveColumns(
        dataCtx,
        firstPage,
        new ColumnResolutionSpec(
            "fixed-width",
            templateFixedWidthColumns(dataCtx.templateConfig()),
            dataPlugin.describeFixedWidthColumns(dataCtx, batch),
            key ->
                new ColumnLayout(
                    key, KEY_DETAIL_PREFIX + key, Math.max(key.length(), 16), false, ' ')));
  }

  /**
   * delimited / fixed-width 共享的列解析模板：按 template &gt; plugin &gt; inferred 顺序选源，并统一做 max_columns 截断。
   */
  private List<ColumnLayout> resolveColumns(
      ExportDataContext dataCtx, List<Map<String, Object>> firstPage, ColumnResolutionSpec spec) {
    int maxColumns = resolveMaxColumns(dataCtx.templateConfig());
    if (!spec.configured().isEmpty()) {
      return enforceMaxColumns(spec.configured(), maxColumns, spec.labelTag() + "-template");
    }
    if (!spec.pluginColumns().isEmpty()) {
      List<ColumnLayout> fromPlugin =
          spec.pluginColumns().stream()
              .map(col -> new ColumnLayout(col.header(), col.source(), null, false, ' '))
              .toList();
      return enforceMaxColumns(fromPlugin, maxColumns, spec.labelTag() + "-plugin");
    }
    if (firstPage == null || firstPage.isEmpty()) {
      return List.of();
    }
    List<ColumnLayout> inferred = new ArrayList<>();
    for (String key : firstPage.get(0).keySet()) {
      inferred.add(spec.inferredColumnFactory().apply(key));
    }
    return enforceMaxColumns(inferred, maxColumns, spec.labelTag() + "-inferred");
  }

  private record ColumnResolutionSpec(
      String labelTag,
      List<ColumnLayout> configured,
      List<ExportDataPlugin.DelimitedColumn> pluginColumns,
      Function<String, ColumnLayout> inferredColumnFactory) {}

  /**
   * A-3.12：解析模板中的 {@code max_columns} / {@code maxColumns}；未配置时走 {@link #DEFAULT_MAX_COLUMNS}。支持从
   * {@code query_param_schema} 继承配置。
   */
  protected int resolveMaxColumns(Map<String, Object> templateConfig) {
    if (templateConfig == null || templateConfig.isEmpty()) {
      return DEFAULT_MAX_COLUMNS;
    }
    Integer direct =
        integerValue(
            firstNonNull(templateConfig.get("max_columns"), templateConfig.get("maxColumns")));
    if (direct != null && direct > 0) {
      return direct;
    }
    Map<String, Object> schema = toMap(templateConfig.get(KEY_QUERY_PARAM_SCHEMA));
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
    Object querySchema = templateConfig.get(KEY_QUERY_PARAM_SCHEMA);
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
    Object querySchema = templateConfig.get(KEY_QUERY_PARAM_SCHEMA);
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
      // type / numberFormat / dateFormat 仅 Excel 类型化导出消费;delimited / fixed-width 忽略这三个字段,
      // 解析在此统一做不增加分支(未配=STRING + null 格式串 = 历史全文本行为)。
      ColumnType type = ColumnType.from(firstNonNull(map.get("type"), map.get("cell_type")));
      String numberFormat =
          textValue(firstNonNull(map.get("numberFormat"), map.get("number_format")));
      String dateFormat = textValue(firstNonNull(map.get("dateFormat"), map.get("date_format")));
      columns.add(
          new ColumnLayout(
              header,
              normalizedSource,
              width,
              "RIGHT".equalsIgnoreCase(align),
              resolvePadChar(padChar),
              type,
              numberFormat,
              dateFormat));
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
    Object schema = source.get(KEY_QUERY_PARAM_SCHEMA);
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
        SwallowedExceptionLogger.warn(AbstractExportFormat.class, "catch:Exception", ignored);

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
      SwallowedExceptionLogger.warn(AbstractExportFormat.class, "catch:Exception", ignored);

      return null;
    }
  }

  protected char resolvePadChar(String padChar) {
    if (!Texts.hasText(padChar)) {
      return ' ';
    }
    return padChar.charAt(0);
  }

  /**
   * 列布局。{@code type} / {@code numberFormat} / {@code dateFormat} 为 Excel 类型化导出新增的<b>可选</b>字段:不配=
   * {@link ColumnType#STRING}、无格式串,导出行为与历史一致(全文本)。delimited / fixed-width 不消费这三个字段。
   */
  public record ColumnLayout(
      String header,
      String source,
      Integer width,
      boolean rightAlign,
      char padChar,
      ColumnType type,
      String numberFormat,
      String dateFormat) {

    public ColumnLayout {
      type = type == null ? ColumnType.STRING : type;
    }

    /** 兼容旧 5 参签名:type 默认 STRING、无数字 / 日期格式串(delimited / fixed-width 走此入口)。 */
    public ColumnLayout(
        String header, String source, Integer width, boolean rightAlign, char padChar) {
      this(header, source, width, rightAlign, padChar, ColumnType.STRING, null, null);
    }
  }

  /** Excel 类型化导出的列类型。未配置或解析失败一律回退 {@link #STRING}(全文本,历史行为)。 */
  public enum ColumnType {
    STRING,
    NUMBER,
    DATE,
    BOOL;

    public static ColumnType from(Object value) {
      if (value == null) {
        return STRING;
      }
      try {
        return ColumnType.valueOf(String.valueOf(value).trim().toUpperCase());
      } catch (Exception ignored) {
        SwallowedExceptionLogger.warn(AbstractExportFormat.class, "catch:Exception", ignored);
        return STRING;
      }
    }
  }

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
        SwallowedExceptionLogger.warn(AbstractExportFormat.class, "catch:Exception", ignored);

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
        SwallowedExceptionLogger.warn(AbstractExportFormat.class, "catch:Exception", ignored);

        return DOUBLE_QUOTE;
      }
    }
  }
}
